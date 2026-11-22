package com.offlinevoice.keyboard

import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * Turns speech into text piece by piece while the person is still talking.
 *
 * Audio frames from the microphone go into a [SpeechChunker]. Each piece it
 * cuts at a pause is transcribed straight away on [worker]. When recording
 * stops, [finish] transcribes only what is left and joins everything up.
 *
 * One instance per dictation. [worker] must be single-threaded so pieces are
 * transcribed, and joined, in the order they were spoken.
 */
class StreamingTranscriber(
    private val context: Context,
    private val worker: ExecutorService,
    private val vocabulary: String,
    /** False = old behaviour: everything is transcribed in one go at the end. */
    live: Boolean = true,
    /** A fixed language, or null to detect English or Malay from the speech. */
    fixedLanguage: SpeechLanguage? = null,
) {
    private val parts = ArrayList<String>() // touched only on the worker thread
    private var anyFailed = false // worker thread only
    @Volatile private var cancelled = false

    /**
     * The dictation's language. With auto-detect it's decided once, from the
     * first few seconds of speech, while the person is still talking.
     */
    @Volatile private var language: SpeechLanguage? = fixedLanguage
    private val detectionWindow = if (fixedLanguage == null) DetectionWindow() else null

    private val chunker = if (live) {
        SpeechChunker(onChunk = { submit(it) })
    } else {
        // Pieces this long never happen, so all audio waits for finish().
        SpeechChunker(onChunk = { submit(it) }, minChunkSeconds = 3600f, maxChunkSeconds = 3600f)
    }

    /** Called from the microphone thread for every new frame of audio. */
    fun feed(frame: ShortArray) {
        if (cancelled) return
        val f = FloatArray(frame.size) { frame[it] / 32768f }
        synchronized(chunker) {
            detectionWindow?.feed(f)?.let { submitDetection(it) }
            chunker.feed(f)
        }
    }

    private fun submitDetection(sample: FloatArray) {
        worker.execute {
            if (!cancelled && language == null) detect(sample)
        }
    }

    private fun detect(sample: FloatArray) {
        language = try {
            WhisperEngine.detectLanguage(context, sample)
        } catch (e: Throwable) {
            Log.e(TAG, "Language check failed", e)
            SpeechLanguage.ENGLISH
        }
    }

    private fun submit(chunk: FloatArray) {
        worker.execute {
            if (!cancelled) transcribePart(chunk)
        }
    }

    private fun transcribePart(chunk: FloatArray) {
        try {
            // Normally decided already; if the dictation was very short,
            // decide from this piece.
            if (language == null) detect(chunk)
            val lang = language ?: SpeechLanguage.ENGLISH
            val text = WhisperEngine.transcribe(context, chunk, prompt(), lang)
            if (text == null) anyFailed = true else if (text.isNotBlank()) parts += text.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "Transcribing a piece failed", e)
            anyFailed = true
        }
    }

    /**
     * Whisper is given the custom words plus the end of what was already
     * said, so each piece continues the last one naturally.
     */
    private fun prompt(): String {
        val previous = parts.joinToString(" ").takeLast(PROMPT_CONTEXT_CHARS)
        return listOf(vocabulary, previous).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Transcribes whatever is left, then calls [onDone] on the worker thread
     * with the full cleaned-up text.
     */
    fun finish(options: TextCleanup.Options, onDone: (DictationResult) -> Unit) {
        val rest = synchronized(chunker) {
            detectionWindow?.flush()?.let { submitDetection(it) }
            chunker.finish()
        }
        worker.execute {
            if (cancelled) return@execute
            if (rest != null) transcribePart(rest)
            val raw = parts.joinToString(" ")
            val result = when {
                raw.isNotBlank() -> {
                    val lang = language ?: SpeechLanguage.ENGLISH
                    val text = TextCleanup.clean(raw, options.copy(language = lang))
                    if (text.isBlank()) DictationResult.NoSpeech else DictationResult.Text(text)
                }
                anyFailed -> DictationResult.Failed
                else -> DictationResult.NoSpeech
            }
            onDone(result)
        }
    }

    /** Throws the dictation away; pieces still queued are skipped. */
    fun cancel() {
        cancelled = true
    }

    companion object {
        private const val TAG = "StreamingTranscriber"
        private const val PROMPT_CONTEXT_CHARS = 200
    }
}
