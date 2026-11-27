package com.offlinevoice.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The voice keyboard. Tap the mic, speak, tap again: the speech is turned
 * into text on the phone and typed into whatever field has focus.
 */
class VoiceKeyboardService : InputMethodService() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService
    private lateinit var prefs: Prefs

    private var state = State.IDLE
    /** The dictation in progress (Whisper or the phone's speech engine). */
    private var session: DictationSession? = null
    private var recordingStartedAt = 0L
    /** Incremented to discard a transcription whose field has gone away. */
    private var sessionNumber = 0

    /**
     * Set when the keyboard closes while a dictation is in flight. The text
     * then goes to History instead of being typed into whatever field is
     * focused by the time it is ready.
     */
    private var targetLost = false

    /** The exact text the last dictation typed, so Undo can remove it. */
    private var lastInserted: String? = null

    private var root: View? = null
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var busy: ProgressBar
    private lateinit var mic: ImageButton
    private lateinit var undo: ImageButton

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        worker = Executors.newSingleThreadExecutor()
        // Load the model in the background so the first dictation is quick.
        if (prefs.speechEngine == SpeechEngine.WHISPER) worker.execute {
            try {
                WhisperEngine.ensureLoaded(this)
            } catch (e: Throwable) {
                Log.e(TAG, "Preloading model failed", e)
            }
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.keyboard_view, null)
        status = v.findViewById(R.id.status)
        level = v.findViewById(R.id.level)
        busy = v.findViewById(R.id.busy)
        mic = v.findViewById(R.id.btn_mic)
        undo = v.findViewById(R.id.btn_undo)

        mic.setOnClickListener { haptic(it); onMicTapped() }
        undo.setOnClickListener { haptic(it); onUndoTapped() }
        v.findViewById<View>(R.id.btn_space).setOnClickListener {
            haptic(it); currentInputConnection?.commitText(" ", 1)
        }
        v.findViewById<View>(R.id.btn_enter).setOnClickListener { haptic(it); sendEnter() }
        v.findViewById<View>(R.id.btn_switch).apply {
            setOnClickListener { haptic(it); switchKeyboard() }
            setOnLongClickListener { showPicker(); true }
        }
        v.findViewById<View>(R.id.btn_backspace).setOnTouchListener(BackspaceRepeater())

        // Keep the keys clear of the gesture/navigation bar.
        val basePadding = v.paddingBottom
        v.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom)
            insets
        }

        root = v
        render()
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) lastInserted = null
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (state == State.IDLE && !targetLost) showIdleStatus()
        render()
        // Switched to Spokify (or moved to a new text box): start listening
        // straight away, like a voice keyboard. Never in password boxes.
        if (!restarting && state == State.IDLE && prefs.autoStart && !isPassword(info)) {
            main.post { if (state == State.IDLE && root?.isShown != false) startRecording() }
        }
    }

    private fun isPassword(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        return variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Hidden mid-dictation: finish it, but keep the text in History only.
        if (state == State.RECORDING) stopAndTranscribe()
        if (state == State.TRANSCRIBING) targetLost = true
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    // ---- Mic -------------------------------------------------------------

    private fun onMicTapped() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> Unit
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.status_need_permission)
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val (newSession, fellBack) = DictationSessions.create(
            this,
            prefs,
            worker,
            main,
            SessionEvents(
                onLevel = { rms -> showLevel(rms) },
                onMaxReached = {
                    if (state == State.RECORDING) {
                        stopAndTranscribe()
                        if (state == State.TRANSCRIBING) status.setText(R.string.status_max_reached)
                    }
                },
                onError = { message ->
                    if (state == State.RECORDING) stopAndTranscribe()
                    status.text = message
                },
            ),
        )
        newSession.start()?.let { problem ->
            status.text = problem
            return
        }
        session = newSession
        state = State.RECORDING
        recordingStartedAt = SystemClock.elapsedRealtime()
        main.post(timerTick)
        render()
        if (fellBack) status.setText(R.string.phone_fell_back)
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val secs = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
            status.text = getString(R.string.status_listening, secs / 60, secs % 60)
            main.postDelayed(this, 500)
        }
    }

    private fun stopAndTranscribe() {
        val current = session ?: return
        session = null
        main.removeCallbacks(timerTick)
        state = State.TRANSCRIBING
        status.setText(
            if (prefs.speechEngine == SpeechEngine.WHISPER && !WhisperEngine.isReady) {
                R.string.status_loading_model
            } else {
                R.string.status_transcribing
            },
        )
        render()

        targetLost = false
        val mySession = ++this.sessionNumber
        current.finish { result -> onTranscribed(mySession, result) }
    }

    private fun onTranscribed(forSession: Int, result: DictationResult) {
        if (forSession != sessionNumber) return
        state = State.IDLE
        val lost = targetLost
        targetLost = false
        if (result !is DictationResult.Text) {
            showResultStatus(result)
            render()
            return
        }
        val text = result.text
        val ic = currentInputConnection
        if (lost || ic == null) {
            prefs.addToHistory(text)
            status.setText(R.string.status_saved_to_history)
        } else {
            val before = ic.getTextBeforeCursor(64, 0)
            val toType = TextCleanup.fitToContext(text, before)
            ic.commitText(toType, 1)
            lastInserted = toType
            prefs.addToHistory(text)
            val aiProblem = result.aiProblem
            if (aiProblem != null) status.text = getString(R.string.ai_fallback, aiProblem) else showIdleStatus()
            // Done: go back to the keyboard the user came from. A short delay
            // lets the text land first. (Kept here if a note needs reading.)
            if (prefs.autoReturn && aiProblem == null) {
                main.postDelayed({ if (state == State.IDLE) switchBack() }, 350)
            }
        }
        render()
    }

    private fun showResultStatus(result: DictationResult) {
        if (result is DictationResult.Problem) {
            status.text = result.message
            return
        }
        status.setText(
            when (result) {
                DictationResult.NoSpeech -> R.string.status_no_speech
                DictationResult.MicBlocked -> R.string.status_mic_blocked
                else -> R.string.status_error
            },
        )
    }

    private fun cancelRecording() {
        session?.cancel()
        session = null
        main.removeCallbacks(timerTick)
        state = State.IDLE
        showIdleStatus()
        render()
    }

    // ---- Other keys --------------------------------------------------------

    private fun onUndoTapped() {
        if (state == State.RECORDING) {
            cancelRecording()
            status.setText(R.string.status_cancelled)
            return
        }
        val ic = currentInputConnection ?: return
        val last = lastInserted
        if (last.isNullOrEmpty()) {
            status.setText(R.string.status_nothing_to_undo)
            return
        }
        val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
        if (before == last) {
            ic.deleteSurroundingText(last.length, 0)
            status.setText(R.string.status_undone)
        } else {
            // The text was edited since, so removing it blindly could delete
            // something else.
            status.setText(R.string.status_nothing_to_undo)
        }
        lastInserted = null
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        lastInserted = null
    }

    /** Deletes once on press, then repeats while held. */
    private inner class BackspaceRepeater : View.OnTouchListener {
        private val repeat = object : Runnable {
            override fun run() {
                deleteOne()
                main.postDelayed(this, 60)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    deleteOne()
                    main.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            return true
        }
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!noEnterAction && !multiLine &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        lastInserted = null
    }

    private fun switchKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        } else {
            val token = window?.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null && getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
        if (!switched) showPicker()
    }

    /** Back to the previous keyboard, without opening the picker if there is none. */
    private fun switchBack() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        } else {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
    }

    private fun showPicker() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    // ---- UI ------------------------------------------------------------------

    private fun showIdleStatus() {
        if (root == null) return
        status.setText(
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                R.string.status_idle
            } else {
                R.string.status_need_permission
            },
        )
    }

    private fun showLevel(rms: Float) {
        if (state != State.RECORDING || root == null) return
        // Speech RMS is roughly 0.01-0.3; map it onto the bar with a curve.
        level.progress = (kotlin.math.sqrt(rms.coerceIn(0f, 0.25f) / 0.25f) * 100).toInt()
    }

    private fun render() {
        if (root == null) return
        when (state) {
            State.IDLE -> {
                mic.setBackgroundResource(R.drawable.mic_bg_idle)
                mic.setImageResource(R.drawable.ic_mic)
                mic.contentDescription = getString(R.string.cd_mic_start)
                mic.isEnabled = true
                busy.visibility = View.GONE
                level.visibility = View.INVISIBLE
                undo.setImageResource(R.drawable.ic_undo)
                undo.contentDescription = getString(R.string.cd_undo)
            }
            State.RECORDING -> {
                mic.setBackgroundResource(R.drawable.mic_bg_recording)
                mic.setImageResource(R.drawable.ic_stop)
                mic.contentDescription = getString(R.string.cd_mic_stop)
                mic.isEnabled = true
                busy.visibility = View.GONE
                level.progress = 0
                level.visibility = View.VISIBLE
                undo.setImageResource(R.drawable.ic_close)
                undo.contentDescription = getString(R.string.cd_cancel)
            }
            State.TRANSCRIBING -> {
                mic.setBackgroundResource(R.drawable.mic_bg_busy)
                mic.setImageResource(R.drawable.ic_mic)
                mic.isEnabled = false
                busy.visibility = View.VISIBLE
                level.visibility = View.INVISIBLE
                undo.setImageResource(R.drawable.ic_undo)
                undo.contentDescription = getString(R.string.cd_undo)
            }
        }
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val TAG = "VoiceKeyboard"
    }
}
