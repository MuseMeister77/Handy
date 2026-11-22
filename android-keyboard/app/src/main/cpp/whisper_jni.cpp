// JNI bridge between the Kotlin keyboard and whisper.cpp.
// Everything here runs on the phone; nothing touches the network.

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "OfflineVoiceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

std::string to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// NewStringUTF expects "modified UTF-8" and aborts on some 4-byte sequences
// (such as emoji). Build the Java string from UTF-16 instead.
jstring to_jstring(JNIEnv *env, const std::string &utf8) {
    std::u16string u16;
    u16.reserve(utf8.size());
    size_t i = 0;
    while (i < utf8.size()) {
        unsigned char c = utf8[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { i++; continue; }  // invalid lead byte: skip
        if (i + len > utf8.size()) break;
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            unsigned char cc = utf8[i + k];
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { i++; continue; }
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            u16.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                          static_cast<jsize>(u16.size()));
}

void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGW("%s", text);
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeLoadBackends(JNIEnv *env, jclass, jstring libDir) {
    whisper_log_set(log_cb, nullptr);
    std::string dir = to_std(env, libDir);
    LOGI("Loading ggml backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    std::string path = to_std(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) LOGW("Failed to load model %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

// Loads a model straight from the APK's assets, so it isn't stored twice on
// the phone (once in the APK and once as a copied file).
namespace {
// AAsset_read may return fewer bytes than asked; whisper expects them all.
size_t asset_read(void *ctx, void *output, size_t read_size) {
    auto *out = static_cast<char *>(output);
    size_t done = 0;
    while (done < read_size) {
        int n = AAsset_read(static_cast<AAsset *>(ctx), out + done, read_size - done);
        if (n <= 0) break;
        done += static_cast<size_t>(n);
    }
    return done;
}
bool asset_eof(void *ctx) { return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0; }
void asset_close(void *ctx) { AAsset_close(static_cast<AAsset *>(ctx)); }
}  // namespace

JNIEXPORT jlong JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeInitFromAsset(JNIEnv *env, jclass, jobject assets,
                                                                jstring assetPath) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assets);
    if (mgr == nullptr) return 0;
    std::string path = to_std(env, assetPath);
    AAsset *asset = AAssetManager_open(mgr, path.c_str(), AASSET_MODE_STREAMING);
    if (asset == nullptr) {
        LOGW("Asset %s not found", path.c_str());
        return 0;
    }
    whisper_model_loader loader = {};
    loader.context = asset;
    loader.read = asset_read;
    loader.eof = asset_eof;
    loader.close = asset_close;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    if (ctx == nullptr) LOGW("Failed to load model from asset %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                             jfloatArray samples, jint threads,
                                                             jstring prompt, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || samples == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    // Whisper ignores clips under one second, so pad short ones with silence.
    const size_t min_samples = WHISPER_SAMPLE_RATE * 11 / 10;
    if (pcm.size() < min_samples) pcm.resize(min_samples, 0.0f);

    std::string prompt_str = to_std(env, prompt);
    std::string language_str = to_std(env, language);
    if (language_str.empty()) language_str = "en";

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = language_str.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;  // drop "[MUSIC]"-style non-speech tokens
    p.temperature_inc = 0.2f;
    if (!prompt_str.empty()) p.initial_prompt = prompt_str.c_str();

    // Whisper always works on a 30-second window. Sizing the window to the
    // clip instead (50 positions per second) makes short clips several times
    // faster with the same text. The 4-second margin matters: with a 2-second
    // margin Whisper sometimes repeated a sentence in testing.
    const int frames = static_cast<int>(pcm.size() / 320);
    p.audio_ctx = std::min(1500, frames + 200);

    if (whisper_full(ctx, p, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGW("whisper_full failed");
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg) text += seg;
    }
    return to_jstring(env, text);
}

// ---- English-or-Malay detection ---------------------------------------------
//
// Whisper's own detection picks from ~100 languages and often mistook Malay
// for Thai or Latin in testing. Spokify only needs "English or Malay?", so it
// reads Whisper's per-language probabilities and compares just those two
// (Indonesian counts as Malay: Whisper often confuses them).
//
// Detection runs the encoder on a separate state whose audio window is fixed
// at DETECT_CTX (5 s), set once by a tiny priming run. That keeps detection
// fast: it never pays for Whisper's full 30-second window.

namespace {
constexpr int DETECT_CTX = 250;  // 50 per second -> 5 s
}

JNIEXPORT jlong JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeInitDetector(JNIEnv *, jclass, jlong handle,
                                                               jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || !whisper_is_multilingual(ctx)) return 0;
    whisper_state *st = whisper_init_state(ctx);
    if (st == nullptr) return 0;

    std::vector<float> silence(WHISPER_SAMPLE_RATE * 11 / 10, 0.0f);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = "en";
    p.audio_ctx = DETECT_CTX;  // the part that matters: stays on this state
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.max_tokens = 1;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    if (whisper_full_with_state(ctx, st, p, silence.data(), static_cast<int>(silence.size())) != 0) {
        whisper_free_state(st);
        return 0;
    }
    return reinterpret_cast<jlong>(st);
}

JNIEXPORT void JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeFreeDetector(JNIEnv *, jclass, jlong state) {
    auto *st = reinterpret_cast<whisper_state *>(state);
    if (st) whisper_free_state(st);
}

/** Share of Malay (incl. Indonesian) vs English, 0..1, or -1 on failure. */
JNIEXPORT jfloat JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeMalayScore(JNIEnv *env, jclass, jlong handle,
                                                             jlong state, jfloatArray samples,
                                                             jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    auto *st = reinterpret_cast<whisper_state *>(state);
    if (ctx == nullptr || st == nullptr || samples == nullptr) return -1.0f;

    const jsize n = env->GetArrayLength(samples);
    const jsize use = std::min<jsize>(n, DETECT_CTX * 320);  // first 5 s
    std::vector<float> pcm(static_cast<size_t>(use));
    env->GetFloatArrayRegion(samples, 0, use, pcm.data());
    const int nt = threads > 0 ? threads : 4;

    if (whisper_pcm_to_mel_with_state(ctx, st, pcm.data(), static_cast<int>(pcm.size()), nt) != 0) {
        return -1.0f;
    }
    std::vector<float> probs(static_cast<size_t>(whisper_lang_max_id() + 1), 0.0f);
    if (whisper_lang_auto_detect_with_state(ctx, st, 0, nt, probs.data()) < 0) return -1.0f;

    const float en = probs[whisper_lang_id("en")];
    const float ms = probs[whisper_lang_id("ms")] + probs[whisper_lang_id("id")];
    LOGI("Language check: en=%.3f ms+id=%.3f", en, ms);
    if (en + ms <= 0.0f) return 0.0f;
    return ms / (en + ms);
}

JNIEXPORT jstring JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeSystemInfo(JNIEnv *env, jclass) {
    return to_jstring(env, whisper_print_system_info());
}

}  // extern "C"
