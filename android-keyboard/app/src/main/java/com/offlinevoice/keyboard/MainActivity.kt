package com.offlinevoice.keyboard

import android.Manifest
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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

        findViewById<Button>(R.id.btn_grant).setOnClickListener { requestMic() }
        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        bindSwitch(R.id.sw_fillers, prefs.removeFillers) { prefs.removeFillers = it }
        bindSwitch(R.id.sw_punct, prefs.spokenPunctuation) { prefs.spokenPunctuation = it }
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

    private fun renderSetup() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_MIC = 1
    }
}
