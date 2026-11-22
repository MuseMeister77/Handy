package com.offlinevoice.keyboard

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/** A language Spokify can type in. */
enum class SpeechLanguage(val code: String) {
    ENGLISH("en"),
    MALAY("ms"),
}

/**
 * On-device speech recognition with whisper.cpp.
 *
 * English uses the small, fast English-only model. Bahasa Melayu uses the
 * multilingual model, which also tells the two languages apart. Each model is
 * loaded on first use and then kept for the life of the process. All calls
 * are serialised: whisper contexts are not safe to use from two threads.
 */
object WhisperEngine {
    private const val TAG = "WhisperEngine"

    /**
     * Malay share (0..1) above which speech counts as Malay. Low on purpose:
     * English scored 0.00 in every test, while Malay scored 0.5-0.75.
     */
    private const val MALAY_THRESHOLD = 0.2f

    private val handles = HashMap<SpeechLanguage, Long>()
    private var detector: Long = 0
    private var backendsLoaded = false
    private val lock = Any()

    init {
        System.loadLibrary("whisper_jni")
    }

    /** True once the model for [language] is loaded. */
    fun isReady(language: SpeechLanguage): Boolean = synchronized(lock) { (handles[language] ?: 0L) != 0L }

    /**
     * Loads the model for [language] if needed. Slow the first time (about a
     * second for English, a few for Malay); call it off the main thread.
     */
    fun ensureLoaded(context: Context, language: SpeechLanguage): Boolean = synchronized(lock) {
        handleFor(context, language) != 0L
    }

    private fun handleFor(context: Context, language: SpeechLanguage): Long {
        handles[language]?.let { if (it != 0L) return it }
        if (!backendsLoaded) {
            nativeLoadBackends(context.applicationInfo.nativeLibraryDir)
            backendsLoaded = true
        }
        val name = if (language == SpeechLanguage.ENGLISH) ModelManager.englishModel else ModelManager.multilingualModel
        val start = System.currentTimeMillis()
        // Read straight from the APK; fall back to a copied file if that fails.
        var h = nativeInitFromAsset(context.assets, ModelManager.assetPath(name))
        if (h == 0L) {
            Log.w(TAG, "Loading $name from the APK failed; using a copied file")
            h = nativeInit(ModelManager.ensureModelFile(context, name).absolutePath)
        } else {
            ModelManager.deleteCopy(context, name)
        }
        Log.i(TAG, "Loaded $name in ${System.currentTimeMillis() - start} ms, ok=${h != 0L}")
        if (h != 0L) handles[language] = h
        return h
    }

    /**
     * Decides whether [samples] (the first few seconds of a dictation) are
     * English or Malay. Falls back to English if it can't tell.
     */
    fun detectLanguage(context: Context, samples: FloatArray): SpeechLanguage = synchronized(lock) {
        val multi = handleFor(context, SpeechLanguage.MALAY)
        if (multi == 0L) return SpeechLanguage.ENGLISH
        if (detector == 0L) detector = nativeInitDetector(multi, threadCount())
        if (detector == 0L) return SpeechLanguage.ENGLISH
        val start = System.currentTimeMillis()
        val score = nativeMalayScore(multi, detector, samples, threadCount())
        val result = if (score >= MALAY_THRESHOLD) SpeechLanguage.MALAY else SpeechLanguage.ENGLISH
        Log.i(TAG, "Language check: malay=$score -> $result in ${System.currentTimeMillis() - start} ms")
        result
    }

    /**
     * Transcribes 16 kHz mono audio in [language]. [prompt] (custom words and
     * the previous sentence) nudges Whisper's spelling and style.
     * Returns null if the engine failed.
     */
    fun transcribe(context: Context, samples: FloatArray, prompt: String, language: SpeechLanguage): String? =
        synchronized(lock) {
            val h = handleFor(context, language)
            if (h == 0L) return null
            val start = System.currentTimeMillis()
            val text = nativeTranscribe(h, samples, threadCount(), prompt, language.code)
            Log.i(
                TAG,
                "Transcribed ${samples.size / 16} ms ($language) in ${System.currentTimeMillis() - start} ms",
            )
            text
        }

    /** Use the big cores; going wider than 4 threads rarely helps on phones. */
    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    @JvmStatic private external fun nativeLoadBackends(libDir: String)
    @JvmStatic private external fun nativeInit(modelPath: String): Long
    @JvmStatic private external fun nativeInitFromAsset(assets: AssetManager, assetPath: String): Long
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        prompt: String,
        language: String,
    ): String?
    @JvmStatic private external fun nativeInitDetector(handle: Long, threads: Int): Long
    @JvmStatic private external fun nativeFreeDetector(state: Long)
    @JvmStatic private external fun nativeMalayScore(
        handle: Long,
        state: Long,
        samples: FloatArray,
        threads: Int,
    ): Float
    @JvmStatic private external fun nativeSystemInfo(): String
}
