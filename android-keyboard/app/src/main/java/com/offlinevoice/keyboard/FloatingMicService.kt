package com.offlinevoice.keyboard

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * A floating mic bubble that works with any keyboard.
 *
 * Hold the bubble and speak; let go and the text is typed into the text box
 * you are in. Slide your finger off the bubble to cancel. Drag it (move
 * straight away, without holding still) to put it somewhere else.
 *
 * It is an accessibility service because that is the only way Android lets
 * an app draw over other apps without an extra permission and type into a
 * text box in another app. It only looks at the focused text box, and the app
 * has no internet permission.
 */
class FloatingMicService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager

    private var bubble: FrameLayout? = null
    private lateinit var icon: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var params: WindowManager.LayoutParams

    private var state = State.IDLE
    private var recorder: AudioRecorder? = null
    /** Transcribes the current dictation piece by piece while recording. */
    private var stream: StreamingTranscriber? = null
    /** The text box that had focus when recording started. */
    private var target: AccessibilityNodeInfo? = null
    private var keyboardOpen = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        worker = Executors.newSingleThreadExecutor()
        wm = getSystemService(WindowManager::class.java)
        prefs.listen(this)
        addBubble()
        updateVisibility()
        worker.execute {
            try {
                WhisperEngine.ensureLoaded(this, SpeechLanguage.ENGLISH)
                // Malay and auto-detect also need the multilingual model.
                if (prefs.languageMode != LanguageMode.ENGLISH) {
                    WhisperEngine.ensureLoaded(this, SpeechLanguage.MALAY)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Preloading model failed", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            main.removeCallbacks(visibilityCheck)
            main.postDelayed(visibilityCheck, 150)
        }
    }

    override fun onInterrupt() {
        if (state == State.RECORDING) cancelRecording()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!::prefs.isInitialized) return
        prefs.unlisten(this)
        recorder?.cancel()
        recorder = null
        stream?.cancel()
        stream = null
        main.removeCallbacksAndMessages(null)
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        if (::worker.isInitialized) worker.shutdown()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == Prefs.KEY_BUBBLE_ALWAYS) updateVisibility()
    }

    // ---- Showing and hiding --------------------------------------------------

    private val visibilityCheck = Runnable {
        keyboardOpen = try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            false
        }
        updateVisibility()
    }

    /** Shown while a keyboard is open (or always, if chosen), and mid-dictation. */
    private fun updateVisibility() {
        val b = bubble ?: return
        val show = prefs.bubbleAlways || keyboardOpen || state != State.IDLE
        b.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---- The bubble ------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(BUBBLE_DP)
        val frame = FrameLayout(this)
        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_spokify_head)
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundResource(R.drawable.bubble_bg_idle)
            elevation = dp(4).toFloat()
            contentDescription = getString(R.string.bubble_cd)
        }
        frame.addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            elevation = dp(5).toFloat()
        }
        frame.addView(spinner, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val pad = dp(6) // room for the shadow and the "pressed" growth
        frame.setPadding(pad, pad, pad, pad)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take focus away from the text box being typed into.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (w, h) = screenSize()
            val full = size + 2 * pad
            x = prefs.bubbleX.takeIf { it >= 0 }?.coerceAtMost(w - full) ?: (w - full - dp(4))
            y = prefs.bubbleY.takeIf { it >= 0 }?.coerceAtMost(h - full) ?: (h * 0.35).toInt()
        }

        frame.setOnTouchListener(BubbleTouch())
        frame.visibility = View.GONE
        try {
            wm.addView(frame, params)
            bubble = frame
        } catch (e: Exception) {
            Log.e(TAG, "Could not show the floating mic", e)
        }
    }

    /**
     * Hold still = talk. Move straight away = drag. While talking, slide far
     * off the bubble and let go = cancel.
     */
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@FloatingMicService).scaledTouchSlop * 1.5f
        private val cancelDistance = dp(90).toFloat()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false
        private var willCancel = false

        private val beginHold = Runnable {
            holding = true
            bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startRecording()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    holding = false
                    willCancel = false
                    if (state == State.IDLE) main.postDelayed(beginHold, HOLD_DELAY_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val dist = hypot(dx, dy)
                    if (holding) {
                        if (state == State.RECORDING) {
                            val cancel = dist > cancelDistance
                            if (cancel != willCancel) {
                                willCancel = cancel
                                render(cancelHint = cancel)
                                if (cancel) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                    } else if (dragging || dist > slop) {
                        if (!dragging) {
                            dragging = true
                            main.removeCallbacks(beginHold)
                        }
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(beginHold)
                    when {
                        holding && state == State.RECORDING -> {
                            if (willCancel || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                                cancelRecording()
                                toast(R.string.status_cancelled)
                            } else {
                                stopAndTranscribe()
                            }
                        }
                        dragging -> snapToEdge(v)
                        !holding && e.actionMasked == MotionEvent.ACTION_UP -> {
                            v.performClick()
                            if (state == State.TRANSCRIBING) {
                                toast(R.string.status_transcribing)
                            } else {
                                toast(R.string.bubble_hold_hint)
                            }
                        }
                    }
                    holding = false
                    dragging = false
                }
            }
            return true
        }
    }

    private fun snapToEdge(v: View) {
        val (w, h) = screenSize()
        val width = v.width.takeIf { it > 0 } ?: dp(BUBBLE_DP + 12)
        val height = v.height.takeIf { it > 0 } ?: width
        params.x = if (params.x + width / 2 < w / 2) dp(4) else w - width - dp(4)
        params.y = params.y.coerceIn(dp(24), (h - height - dp(24)).coerceAtLeast(dp(24)))
        runCatching { wm.updateViewLayout(v, params) }
        prefs.bubbleX = params.x
        prefs.bubbleY = params.y
    }

    // ---- Dictation -------------------------------------------------------------

    private fun startRecording() {
        if (state != State.IDLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(R.string.status_need_permission)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        target = findFocusedTextBox()
        val st = StreamingTranscriber(
            this,
            worker,
            prefs.vocabularyPrompt,
            prefs.liveTranscription,
            prefs.languageMode.fixed,
        )
        val r = AudioRecorder(
            onLevel = { rms -> main.post { showLevel(rms) } },
            onMaxDurationReached = { main.post { if (state == State.RECORDING) stopAndTranscribe() } },
            onFrame = st::feed,
        )
        if (!r.start()) {
            st.cancel()
            toast(R.string.status_mic_busy)
            return
        }
        recorder = r
        stream = st
        state = State.RECORDING
        render()
    }

    private fun stopAndTranscribe() {
        val r = recorder ?: return
        recorder = null
        val st = stream ?: return
        stream = null
        val samples = r.stop()
        Dictation.precheck(samples, r.wasSilenced)?.let { early ->
            st.cancel()
            state = State.IDLE
            showResult(early)
            render()
            return
        }
        state = State.TRANSCRIBING
        render()
        st.finish(prefs.cleanupOptions) { result ->
            main.post {
                state = State.IDLE
                if (result is DictationResult.Text) {
                    prefs.addToHistory(result.text)
                    insert(result.text)
                } else {
                    showResult(result)
                }
                render()
                updateVisibility()
            }
        }
    }

    private fun cancelRecording() {
        recorder?.cancel()
        recorder = null
        stream?.cancel()
        stream = null
        target = null
        state = State.IDLE
        render()
        updateVisibility()
    }

    private fun showResult(result: DictationResult) {
        toast(
            when (result) {
                DictationResult.NoSpeech -> R.string.status_no_speech
                DictationResult.MicBlocked -> R.string.status_mic_blocked
                else -> R.string.status_error
            },
        )
    }

    // ---- Typing into the other app ---------------------------------------------

    private fun findFocusedTextBox(): AccessibilityNodeInfo? =
        try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        } catch (e: Exception) {
            null
        }

    /**
     * Inserts [text] at the cursor of the focused text box. Falls back to
     * pasting, and if that is not possible, leaves it on the clipboard.
     */
    private fun insert(text: String) {
        val node = findFocusedTextBox()
            ?: target?.takeIf { runCatching { it.refresh() }.getOrDefault(false) && it.isEditable }
        target = null
        if (node == null) {
            copyToClipboard(text)
            toast(R.string.bubble_copied)
            return
        }
        if (node.isPassword) {
            toast(R.string.bubble_password)
            return
        }

        val raw = node.text?.toString()
        val current = TextCleanup.existingText(raw, node.hintText?.toString(), node.isShowingHintText)
        val cursorKnown = node.textSelectionStart >= 0 && node.textSelectionEnd >= 0

        // The app doesn't say where the cursor is (WhatsApp's message box,
        // for one). Paste instead, so the app itself puts the words at the
        // cursor and clears any placeholder, as with a normal paste.
        if (!cursorKnown) {
            val before = if (looksUnreliable(current)) "" else current
            if (paste(node, TextCleanup.fitToContext(text, before))) return
        }

        val splice = TextCleanup.spliceAtCursor(
            current,
            node.textSelectionStart,
            node.textSelectionEnd,
            text,
        )
        val piece = splice.inserted
        val updated = splice.text

        val setOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            },
        )
        val landed = setOk && runCatching {
            node.refresh() && node.text?.toString()?.contains(piece.trim()) == true
        }.getOrDefault(true)
        if (landed) {
            val cursor = splice.cursor
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            return
        }

        // Some apps (often web pages) ignore "set text" but accept a paste.
        if (!paste(node, piece)) {
            copyToClipboard(piece)
            toast(R.string.bubble_copied)
        }
    }

    private fun looksUnreliable(current: String) = TextCleanup.looksLikePlaceholder(current)

    /**
     * Pastes [text] at the app's cursor. Puts back what was on the clipboard
     * afterwards when Android lets the app read it.
     */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val previous = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (ok && previous != null) {
            main.postDelayed({ runCatching { cm.setPrimaryClip(previous) } }, 500)
        }
        return ok
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    // ---- Drawing ------------------------------------------------------------------

    private fun render(cancelHint: Boolean = false) {
        if (bubble == null) return
        when (state) {
            State.IDLE -> {
                icon.setBackgroundResource(R.drawable.bubble_bg_idle)
                showHead()
                icon.alpha = 1f
                icon.scaleX = 1f
                icon.scaleY = 1f
                spinner.visibility = View.GONE
            }
            State.RECORDING -> {
                icon.setBackgroundResource(R.drawable.mic_bg_recording)
                if (cancelHint) {
                    icon.setImageResource(R.drawable.ic_close_white)
                    icon.scaleType = ImageView.ScaleType.CENTER
                } else {
                    showHead()
                }
                icon.alpha = if (cancelHint) 0.6f else 1f
                spinner.visibility = View.GONE
            }
            State.TRANSCRIBING -> {
                icon.setBackgroundResource(R.drawable.mic_bg_busy)
                showHead()
                icon.alpha = 1f
                icon.scaleX = 1f
                icon.scaleY = 1f
                spinner.visibility = View.VISIBLE
            }
        }
        updateVisibility()
    }

    /** The app's speaking-head logo, filling the bubble like the app icon. */
    private fun showHead() {
        icon.setImageResource(R.drawable.ic_spokify_head)
        icon.scaleType = ImageView.ScaleType.FIT_XY
    }

    /** The bubble grows a little with your voice, so you can see it hears you. */
    private fun showLevel(rms: Float) {
        if (state != State.RECORDING || bubble == null) return
        val s = 1.05f + kotlin.math.sqrt(rms.coerceIn(0f, 0.25f) / 0.25f) * 0.15f
        icon.scaleX = s
        icon.scaleY = s
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = resources.displayMetrics
            m.widthPixels to m.heightPixels
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingMic"
        private const val BUBBLE_DP = 56
        /** How long to hold still before recording starts (tells hold from drag). */
        private const val HOLD_DELAY_MS = 180L
    }
}
