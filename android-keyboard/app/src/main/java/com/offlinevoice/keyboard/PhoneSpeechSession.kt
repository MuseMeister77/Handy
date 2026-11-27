package com.offlinevoice.keyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * Dictation with the phone's own speech recognition (on Samsung phones,
 * "Samsung voice input"), which is trained on far more accents than
 * Spokify's small Whisper model and supports Bahasa Melayu.
 *
 * Spokify asks the service to work offline (EXTRA_PREFER_OFFLINE), which it
 * can do with the phone's offline language packs. Spokify itself still has
 * no internet permission; the listening is done by the phone's service.
 *
 * Phone recognizers are built for short phrases and stop at a pause, so the
 * session restarts listening each time until the user finishes, then joins
 * the pieces.
 */
class PhoneSpeechSession(
    private val context: Context,
    private val prefs: Prefs,
    private val worker: ExecutorService,
    private val main: Handler,
    private val events: SessionEvents,
    private val polisher: TextPolisher?,
) : DictationSession, RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private val segments = ArrayList<String>()
    private var partial = ""
    private var listening = false
    private var onDone: ((DictationResult) -> Unit)? = null
    private var completed = false
    private var problem: String? = null

    private val maxReached = Runnable { if (listening) events.onMaxReached() }
    private val giveUpWaiting = Runnable { complete() }

    override fun start(): String? {
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "No speech recognizer", e)
            return context.getString(R.string.phone_unavailable)
        }
        r.setRecognitionListener(this)
        recognizer = r
        listening = true
        r.startListening(intent())
        main.postDelayed(maxReached, AudioRecorder.MAX_SECONDS * 1000L)
        return null
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.speechLanguage.tag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Ask for longer pauses before it stops (not every service honours this).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        if (Build.VERSION.SDK_INT >= 33) {
            // Punctuation and capitals, where the service supports it.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
    }

    override fun finish(onDone: (DictationResult) -> Unit) {
        this.onDone = onDone
        listening = false
        main.removeCallbacks(maxReached)
        recognizer?.stopListening()
        // The service normally sends its final words right away; don't wait forever.
        main.postDelayed(giveUpWaiting, FINAL_RESULT_WAIT_MS)
    }

    override fun cancel() {
        listening = false
        completed = true
        cleanUp()
    }

    private fun restart() {
        main.post {
            if (listening) recognizer?.startListening(intent())
        }
    }

    private fun keepPartial() {
        if (partial.isNotBlank()) segments += partial.trim()
        partial = ""
    }

    // ---- Recognition callbacks (main thread) ---------------------------------------

    override fun onResults(results: Bundle?) {
        val text = best(results)
        partial = ""
        if (text.isNotBlank()) segments += text.trim()
        if (listening) restart() else complete()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = best(partialResults)
        if (text.isNotBlank()) partial = text
    }

    override fun onError(error: Int) {
        if (!listening) {
            // Stopped by the user: whatever we have is final.
            keepPartial()
            complete()
            return
        }
        when (error) {
            // A pause with nothing said, or a pause that ended a phrase: keep going.
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                keepPartial()
                restart()
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recognizer?.cancel()
                main.postDelayed({ if (listening) recognizer?.startListening(intent()) }, 300)
            }
            SpeechRecognizer.ERROR_CLIENT -> Unit
            else -> {
                // Can't continue: report it, and let the screen finish with what we have.
                keepPartial()
                problem = errorMessage(error)
                listening = false
                events.onError(problem!!)
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) = events.onLevel(PhoneSpeech.level(rmsdB))
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    // ---- Finishing ---------------------------------------------------------------------

    private fun complete() {
        val done = onDone ?: return
        if (completed) return
        completed = true
        cleanUp()
        val raw = PhoneSpeech.join(segments, prefs.customWordList.toSet())
        if (raw.isBlank()) {
            done(problem?.let { DictationResult.Problem(it) } ?: DictationResult.NoSpeech)
            return
        }
        val options = prefs.cleanupOptions
        worker.execute {
            val text = TextCleanup.clean(raw, options)
            val result = if (text.isBlank()) DictationResult.NoSpeech else Dictation.applyPolish(polisher, text)
            main.post { done(result) }
        }
    }

    private fun cleanUp() {
        main.removeCallbacks(maxReached)
        main.removeCallbacks(giveUpWaiting)
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            context.getString(R.string.phone_error_offline_pack)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            context.getString(R.string.phone_error_language)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.phone_error_permission)
        SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.status_mic_busy)
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> context.getString(R.string.phone_error_busy)
        else -> context.getString(R.string.phone_error_other, code)
    }

    companion object {
        private const val TAG = "PhoneSpeech"
        private const val FINAL_RESULT_WAIT_MS = 4000L
    }
}

/** Pure helpers for the phone engine (unit tested). */
object PhoneSpeech {
    /**
     * Joins the phrases the phone heard between pauses. A phrase of four or
     * more words with no punctuation of its own is treated as a finished
     * sentence (the pause marked its end); shorter ones run on into the next.
     */
    fun join(segments: List<String>, customWords: Set<String> = emptySet()): String {
        val pieces = segments.map { it.trim() }.filter { it.isNotEmpty() }.map { s ->
            val words = s.split(Regex("\\s+")).size
            if (words >= 4 && s.last().isLetterOrDigit()) "$s." else s
        }
        return Tidy.joinPieces(pieces, customWords)
    }

    /** Speech services report loudness in dB, roughly -2 (silence) to 10 (loud). */
    fun level(rmsdB: Float): Float {
        val x = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        return x * x * 0.25f
    }
}
