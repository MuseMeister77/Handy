package com.offlinevoice.keyboard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup steps, a test box, settings and dictation history. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // Keep content clear of the status and navigation bars (Android 15
        // draws apps edge to edge).
        val content = findViewById<View>(R.id.content)
        val pad = content.paddingTop
        content.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, pad + top, v.paddingRight, pad + bottom)
            insets
        }

        findViewById<Button>(R.id.btn_bubble).setOnClickListener {
            if (!hasMic()) requestMic()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
        bindSwitch(R.id.sw_bubble_always, prefs.bubbleAlways) { prefs.bubbleAlways = it }

        findViewById<Button>(R.id.btn_grant).setOnClickListener { requestMic() }
        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        setUpSpeechEngine()
        bindSwitch(R.id.sw_auto_start, prefs.autoStart) { prefs.autoStart = it }
        bindSwitch(R.id.sw_auto_return, prefs.autoReturn) { prefs.autoReturn = it }
        bindSwitch(R.id.sw_live, prefs.liveTranscription) { prefs.liveTranscription = it }
        bindSwitch(R.id.sw_fillers, prefs.removeFillers) { prefs.removeFillers = it }
        bindSwitch(R.id.sw_punct, prefs.spokenPunctuation) { prefs.spokenPunctuation = it }
        bindSwitch(R.id.sw_tidy, prefs.smartTidy) { prefs.smartTidy = it }
        bindSwitch(R.id.sw_history, prefs.keepHistory) {
            prefs.keepHistory = it
            renderHistory()
        }

        val words = findViewById<EditText>(R.id.custom_words)
        words.setText(prefs.customWords)
        findViewById<Button>(R.id.btn_save_words).setOnClickListener {
            prefs.customWords = words.text.toString()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            prefs.clearHistory()
            renderHistory()
        }

        if (BuildConfig.AI_EDITION) setUpAiSettings()

        findViewById<TextView>(R.id.about).text = getString(R.string.about_text, ModelManager.modelName)
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
        renderHistory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog over this screen; refresh after it.
        if (hasFocus) renderSetup()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQ_MIC && results.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            // "Don't ask again" was chosen earlier; only App info can grant it now.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
        renderSetup()
    }

    private fun requestMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isBubbleEnabled(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == FloatingMicService::class.java.name
            }

    private fun renderSetup() {
        val bubbleOk = isBubbleEnabled()
        setStep(R.id.bubble_status, R.id.btn_bubble, bubbleOk, getString(R.string.bubble_step_todo))
        val hint = if (bubbleOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bubble_restricted).visibility = hint
        findViewById<View>(R.id.btn_app_info).visibility = hint

        val micOk = hasMic()
        val enabled = isKeyboardEnabled()
        val selected = isKeyboardSelected()

        setStep(R.id.step1_status, R.id.btn_grant, micOk, getString(R.string.status_todo))
        setStep(R.id.step2_status, R.id.btn_enable, enabled, getString(R.string.step2_todo))
        setStep(
            R.id.step3_status,
            R.id.btn_choose,
            selected,
            getString(if (enabled) R.string.step3_other else R.string.step3_todo),
        )
    }

    private fun setStep(statusId: Int, buttonId: Int, done: Boolean, todoText: String) {
        val status = findViewById<TextView>(statusId)
        status.text = if (done) getString(R.string.status_done) else todoText
        status.setTextColor(getColor(if (done) R.color.ok else R.color.status_text))
        findViewById<Button>(buttonId).visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun imeId(): String = "$packageName/${VoiceKeyboardService::class.java.name}"

    private fun isKeyboardEnabled(): Boolean =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == imeId() }

    private fun isKeyboardSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId()

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun renderHistory() {
        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        val items = prefs.history()
        findViewById<View>(R.id.history_empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_clear_history).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val padV = dp(10)
        for (text in items) {
            val row = TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.key_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, padV, 0, padV)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { copy(text) }
            }
            list.addView(row)
            list.addView(
                View(this).apply {
                    setBackgroundColor(getColor(R.color.divider))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
            )
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13+ shows its own confirmation when something is copied.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ---- AI polish (Spokify AI edition only) ----------------------------------

    private val providerButtons = mapOf(
        AiProvider.CLAUDE to R.id.ai_claude,
        AiProvider.OPENAI to R.id.ai_openai,
        AiProvider.GEMINI to R.id.ai_gemini,
        AiProvider.CUSTOM to R.id.ai_custom,
    )

    private fun setUpAiSettings() {
        findViewById<View>(R.id.ai_title).visibility = View.VISIBLE
        findViewById<View>(R.id.ai_card).visibility = View.VISIBLE
        val secrets = SecretStore(this)

        bindSwitch(R.id.sw_ai, prefs.aiEnabled) { prefs.aiEnabled = it }

        val providerGroup = findViewById<RadioGroup>(R.id.ai_provider_group)
        providerGroup.check(providerButtons.getValue(prefs.aiProvider))
        providerGroup.setOnCheckedChangeListener { _, id ->
            providerButtons.entries.firstOrNull { it.value == id }?.let {
                prefs.aiProvider = it.key
                renderAiProvider(secrets)
            }
        }

        val styleGroup = findViewById<RadioGroup>(R.id.ai_style_group)
        styleGroup.check(if (prefs.aiStyle == PolishStyle.LIGHT) R.id.ai_light else R.id.ai_polished)
        styleGroup.setOnCheckedChangeListener { _, id ->
            prefs.aiStyle = if (id == R.id.ai_polished) PolishStyle.POLISHED else PolishStyle.LIGHT
        }

        findViewById<Button>(R.id.btn_ai_save).setOnClickListener {
            saveAiFields(secrets)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_ai_test).setOnClickListener { button ->
            saveAiFields(secrets)
            val result = findViewById<TextView>(R.id.ai_test_result)
            result.setText(R.string.ai_testing)
            button.isEnabled = false
            val sample = TextCleanup.clean(getString(R.string.ai_test_sample), prefs.cleanupOptions)
            Thread {
                val start = System.currentTimeMillis()
                val outcome = Edition.polisher(this, ignoreSwitch = true)?.polish(sample)
                val ms = (System.currentTimeMillis() - start).toInt()
                runOnUiThread {
                    button.isEnabled = true
                    result.text = when (outcome) {
                        is PolishResult.Ok -> getString(R.string.ai_test_ok, ms, outcome.text)
                        is PolishResult.Failed -> getString(R.string.ai_test_failed, outcome.reason)
                        is PolishResult.Partial -> getString(R.string.ai_test_failed, outcome.reason)
                        null -> getString(R.string.ai_test_failed, "AI polish unavailable")
                    }
                }
            }.start()
        }

        renderAiProvider(secrets)
    }

    /** Saves the key (if a new one was typed), model and server address. */
    private fun saveAiFields(secrets: SecretStore) {
        val provider = prefs.aiProvider
        val keyField = findViewById<EditText>(R.id.ai_key)
        val newKey = keyField.text.toString().trim()
        if (newKey.isNotEmpty()) {
            secrets.put(provider.secretName, newKey)
            keyField.setText("")
        }
        prefs.setAiModel(provider, findViewById<EditText>(R.id.ai_model).text.toString())
        if (provider == AiProvider.CUSTOM) prefs.aiCustomBaseUrl = findViewById<EditText>(R.id.ai_url).text.toString()
        renderAiProvider(secrets)
    }

    private fun renderAiProvider(secrets: SecretStore) {
        val provider = prefs.aiProvider
        val saved = secrets.get(provider.secretName)
        findViewById<TextView>(R.id.ai_key_status).text =
            if (saved.isNullOrEmpty()) getString(R.string.ai_key_missing) else getString(R.string.ai_key_saved, saved.takeLast(4))
        val model = findViewById<EditText>(R.id.ai_model)
        model.setText(prefs.aiModel(provider))
        model.hint = provider.defaultModel.ifEmpty { getString(R.string.ai_model) }
        findViewById<TextView>(R.id.ai_model_hint).text = when (provider) {
            AiProvider.CLAUDE -> getString(R.string.ai_model_hint_claude, provider.defaultModel)
            AiProvider.GEMINI -> getString(R.string.ai_model_hint_gemini, provider.defaultModel)
            else -> getString(R.string.ai_model_hint_other)
        }
        val custom = if (provider == AiProvider.CUSTOM) View.VISIBLE else View.GONE
        findViewById<View>(R.id.ai_url_label).visibility = custom
        findViewById<EditText>(R.id.ai_url).apply {
            visibility = custom
            setText(prefs.aiCustomBaseUrl)
        }
        findViewById<TextView>(R.id.ai_test_result).text = ""
    }

    private fun setUpSpeechEngine() {
        val engineGroup = findViewById<RadioGroup>(R.id.engine_group)
        engineGroup.check(if (prefs.speechEngine == SpeechEngine.PHONE) R.id.engine_phone else R.id.engine_whisper)
        val langGroup = findViewById<RadioGroup>(R.id.speech_lang_group)
        langGroup.check(if (prefs.speechLanguage == SpeechLanguage.MALAY) R.id.speech_lang_ms else R.id.speech_lang_en)

        fun refresh() {
            val phone = prefs.speechEngine == SpeechEngine.PHONE
            // Whisper is English only.
            for (i in 0 until langGroup.childCount) langGroup.getChildAt(i).isEnabled = phone
            val status = findViewById<TextView>(R.id.engine_status)
            status.visibility = if (phone) View.VISIBLE else View.GONE
            val ok = DictationSessions.phoneEngineAvailable(this)
            status.setText(if (ok) R.string.engine_status_ok else R.string.engine_status_missing)
            status.setTextColor(getColor(if (ok) R.color.ok else R.color.recording))
        }
        engineGroup.setOnCheckedChangeListener { _, id ->
            prefs.speechEngine = if (id == R.id.engine_whisper) SpeechEngine.WHISPER else SpeechEngine.PHONE
            refresh()
        }
        langGroup.setOnCheckedChangeListener { _, id ->
            prefs.speechLanguage = if (id == R.id.speech_lang_ms) SpeechLanguage.MALAY else SpeechLanguage.ENGLISH
        }
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_MIC = 1
    }
}
