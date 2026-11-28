// JNI bridge between the Kotlin keyboard and whisper.cpp.
// Everything here runs on the phone; nothing touches the network.

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

JNIEXPORT void JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                             jfloatArray samples, jint threads,
                                                             jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || samples == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    // Whisper ignores clips under one second, so pad short ones with silence.
    const size_t min_samples = WHISPER_SAMPLE_RATE * 11 / 10;
    if (pcm.size() < min_samples) pcm.resize(min_samples, 0.0f);

    std::string prompt_str = to_std(env, prompt);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = "en";
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

JNIEXPORT jstring JNICALL
Java_com_offlinevoice_keyboard_WhisperEngine_nativeSystemInfo(JNIEnv *env, jclass) {
    return to_jstring(env, whisper_print_system_info());
}

}  // extern "C"
