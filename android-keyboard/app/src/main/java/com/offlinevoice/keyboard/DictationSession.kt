package com.offlinevoice.keyboard

import android.content.Context
import android.os.Handler
import android.speech.SpeechRecognizer
import java.util.concurrent.ExecutorService

/**
 * One dictation, from "start listening" to text, whichever speech engine
 * runs it. Used by both the keyboard and the floating mic. All methods are
 * called on the main thread, and callbacks arrive on the main thread.
 */
interface DictationSession {
    /** Starts listening. Returns a message to show if it couldn't, else null. */
    fun start(): String?

    /** Stops listening and delivers the final result to [onDone]. */
    fun finish(onDone: (DictationResult) -> Unit)

    /** Stops and throws everything away. */
    fun cancel()
}

/** What the session tells the screen while listening. */
class SessionEvents(
    /** Loudness, about 0 (silence) to 0.25 (loud speech). */
    val onLevel: (Float) -> Unit,
    /** The 15-minute limit was reached; the screen should call finish(). */
    val onMaxReached: () -> Unit,
    /** Listening stopped because of a problem; the screen should call finish(). */
    val onError: (String) -> Unit = {},
)

object DictationSessions {
    /** True if the phone has a speech service Spokify can use. */
    fun phoneEngineAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Creates a session for the engine chosen in settings. If the phone
     * engine is chosen but the phone has no usable speech service, Whisper is
     * used instead and [fellBack] is true.
     */
    fun create(
        context: Context,
        prefs: Prefs,
        worker: ExecutorService,
        main: Handler,
        events: SessionEvents,
    ): Pair<DictationSession, Boolean> {
        val polisher = Edition.polisher(context)
        if (prefs.speechEngine == SpeechEngine.PHONE) {
            if (phoneEngineAvailable(context)) {
                return PhoneSpeechSession(context, prefs, worker, main, events, polisher) to false
            }
            return WhisperSession(context, prefs, worker, main, events, polisher) to true
        }
        return WhisperSession(context, prefs, worker, main, events, polisher) to false
    }
}

/** Spokify's built-in Whisper engine (English only, all inside the app). */
class WhisperSession(
    private val context: Context,
    private val prefs: Prefs,
    worker: ExecutorService,
    private val main: Handler,
    private val events: SessionEvents,
    polisher: TextPolisher?,
) : DictationSession {

    private val stream = StreamingTranscriber(context, worker, prefs.vocabularyPrompt, prefs.liveTranscription, polisher)
    private val recorder = AudioRecorder(
        onLevel = { rms -> main.post { events.onLevel(rms) } },
        onMaxDurationReached = { main.post { events.onMaxReached() } },
        onFrame = stream::feed,
    )

    override fun start(): String? {
        if (recorder.start()) return null
        stream.cancel()
        return context.getString(R.string.status_mic_busy)
    }

    override fun finish(onDone: (DictationResult) -> Unit) {
        val samples = recorder.stop()
        Dictation.precheck(samples, recorder.wasSilenced)?.let { early ->
            stream.cancel()
            main.post { onDone(early) }
            return
        }
        stream.finish(prefs.cleanupOptions) { result -> main.post { onDone(result) } }
    }

    override fun cancel() {
        recorder.cancel()
        stream.cancel()
    }
}
