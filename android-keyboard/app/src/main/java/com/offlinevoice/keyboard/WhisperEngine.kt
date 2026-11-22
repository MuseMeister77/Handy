package com.offlinevoice.keyboard

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-device speech recognition with whisper.cpp.
 *
 * One model stays loaded for the life of the keyboard process. All calls are
 * serialised: whisper contexts are not safe to use from two threads at once.
 */
object WhisperEngine {
    private const val TAG = "WhisperEngine"

    @Volatile private var handle: Long = 0
    private var backendsLoaded = false
    private val lock = Any()

    init {
        System.loadLibrary("whisper_jni")
    }

    /** True once the model is loaded and ready. */
    val isReady: Boolean get() = handle != 0L

    /**
     * Loads the model if it isn't loaded yet. Slow (about a second) the first
     * time; call it off the main thread.
     */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (handle != 0L) return true
        if (!backendsLoaded) {
            nativeLoadBackends(context.applicationInfo.nativeLibraryDir)
            backendsLoaded = true
        }
        val model: File = ModelManager.ensureModelFile(context)
        val start = System.currentTimeMillis()
        handle = nativeInit(model.absolutePath)
        Log.i(TAG, "Model load took ${System.currentTimeMillis() - start} ms, ok=${handle != 0L}")
        if (handle != 0L) Log.i(TAG, nativeSystemInfo())
        handle != 0L
    }

    /**
     * Transcribes 16 kHz mono audio. [vocabulary] is passed to Whisper as a
     * prompt, which nudges it towards spelling those words correctly.
     * Returns null if the engine failed.
     */
    fun transcribe(context: Context, samples: FloatArray, vocabulary: String): String? =
        synchronized(lock) {
            if (!ensureLoaded(context)) return null
            val start = System.currentTimeMillis()
            val text = nativeTranscribe(handle, samples, threadCount(), vocabulary)
            Log.i(
                TAG,
                "Transcribed ${samples.size / 16} ms of audio in ${System.currentTimeMillis() - start} ms",
            )
            text
        }

    fun release() = synchronized(lock) {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }

    /** Use the big cores; going wider than 4 threads rarely helps on phones. */
    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    @JvmStatic private external fun nativeLoadBackends(libDir: String)
    @JvmStatic private external fun nativeInit(modelPath: String): Long
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        prompt: String,
    ): String?
    @JvmStatic private external fun nativeSystemInfo(): String
}
