package com.offlinevoice.keyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** User settings and dictation history, stored privately on the phone. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var removeFillers: Boolean
        get() = sp.getBoolean(KEY_FILLERS, true)
        set(v) = sp.edit().putBoolean(KEY_FILLERS, v).apply()

    var spokenPunctuation: Boolean
        get() = sp.getBoolean(KEY_PUNCT, true)
        set(v) = sp.edit().putBoolean(KEY_PUNCT, v).apply()

    var keepHistory: Boolean
        get() = sp.getBoolean(KEY_KEEP_HISTORY, true)
        set(v) {
            sp.edit().putBoolean(KEY_KEEP_HISTORY, v).apply()
            if (!v) clearHistory()
        }

    /** Comma-separated names and terms Whisper should spell correctly. */
    var customWords: String
        get() = sp.getString(KEY_WORDS, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WORDS, v.trim()).apply()

    /** Transcribe while the person is still speaking, for a shorter wait at the end. */
    var liveTranscription: Boolean
        get() = sp.getBoolean(KEY_LIVE, true)
        set(v) = sp.edit().putBoolean(KEY_LIVE, v).apply()

    /** Show the floating mic all the time, not only while a keyboard is open. */
    var bubbleAlways: Boolean
        get() = sp.getBoolean(KEY_BUBBLE_ALWAYS, false)
        set(v) = sp.edit().putBoolean(KEY_BUBBLE_ALWAYS, v).apply()

    /** Where the user last left the floating mic, in pixels; -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(KEY_BUBBLE_X, v).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(KEY_BUBBLE_Y, v).apply()

    /** Fix self-corrections and small grammar slips. */
    var smartTidy: Boolean
        get() = sp.getBoolean(KEY_TIDY, true)
        set(v) = sp.edit().putBoolean(KEY_TIDY, v).apply()

    /** Which engine turns speech into text. */
    var speechEngine: SpeechEngine
        get() = SpeechEngine.entries.firstOrNull { it.key == sp.getString(KEY_ENGINE, null) } ?: SpeechEngine.PHONE
        set(v) = sp.edit().putString(KEY_ENGINE, v.key).apply()

    /** Language for the phone engine (Whisper is English only). */
    var speechLanguage: SpeechLanguage
        get() = SpeechLanguage.entries.firstOrNull { it.key == sp.getString(KEY_LANGUAGE, null) } ?: SpeechLanguage.ENGLISH
        set(v) = sp.edit().putString(KEY_LANGUAGE, v.key).apply()

    /** Spokify Keyboard starts listening as soon as it opens. */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    /** After typing a dictation, switch back to the previous keyboard. */
    var autoReturn: Boolean
        get() = sp.getBoolean(KEY_AUTO_RETURN, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_RETURN, v).apply()

    /** True when dictation will be in Bahasa Melayu. */
    val malayActive: Boolean
        get() = speechEngine == SpeechEngine.PHONE && speechLanguage == SpeechLanguage.MALAY

    val cleanupOptions: TextCleanup.Options
        get() = TextCleanup.Options(removeFillers, spokenPunctuation, smartTidy, malay = malayActive)

    // ---- AI polish (Spokify AI edition only) --------------------------------

    /** Off by default. Only the AI edition shows this setting. */
    var aiEnabled: Boolean
        get() = BuildConfig.AI_EDITION && sp.getBoolean(KEY_AI_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_AI_ENABLED, v).apply()

    var aiProvider: AiProvider
        get() = AiProvider.entries.firstOrNull { it.key == sp.getString(KEY_AI_PROVIDER, null) } ?: AiProvider.CLAUDE
        set(v) = sp.edit().putString(KEY_AI_PROVIDER, v.key).apply()

    var aiStyle: PolishStyle
        get() = PolishStyle.entries.firstOrNull { it.key == sp.getString(KEY_AI_STYLE, null) } ?: PolishStyle.LIGHT
        set(v) = sp.edit().putString(KEY_AI_STYLE, v.key).apply()

    /** Model name for a provider; empty means the provider's default. */
    fun aiModel(provider: AiProvider): String = sp.getString("$KEY_AI_MODEL.${provider.key}", "").orEmpty()

    fun setAiModel(provider: AiProvider, model: String) =
        sp.edit().putString("$KEY_AI_MODEL.${provider.key}", model.trim()).apply()

    /** Base URL for "Other (OpenAI-compatible)". */
    var aiCustomBaseUrl: String
        get() = sp.getString(KEY_AI_BASE_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_AI_BASE_URL, v.trim().trimEnd('/')).apply()

    /** The custom words as a list, for the AI prompt. */
    val customWordList: List<String>
        get() = customWords.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The custom words in the form Whisper understands best as a prompt. */
    val vocabularyPrompt: String
        get() {
            val words = customWords.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            return if (words.isEmpty()) "" else words.joinToString(", ") + "."
        }

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    fun history(): List<String> {
        val raw = sp.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToHistory(text: String) {
        if (!keepHistory || text.isBlank()) return
        val items = ArrayList<String>(MAX_HISTORY)
        items.add(text)
        items.addAll(history().take(MAX_HISTORY - 1))
        sp.edit().putString(KEY_HISTORY, JSONArray(items).toString()).apply()
    }

    fun clearHistory() {
        sp.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val KEY_FILLERS = "remove_fillers"
        private const val KEY_PUNCT = "spoken_punctuation"
        private const val KEY_TIDY = "smart_tidy"
        private const val KEY_ENGINE = "speech_engine"
        private const val KEY_LANGUAGE = "speech_language"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_AUTO_RETURN = "auto_return"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_STYLE = "ai_style"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_KEEP_HISTORY = "keep_history"
        private const val KEY_WORDS = "custom_words"
        private const val KEY_HISTORY = "history"
        private const val KEY_LIVE = "live_transcription"
        const val KEY_BUBBLE_ALWAYS = "bubble_always"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        const val MAX_HISTORY = 50
    }
}

enum class SpeechEngine(val key: String) {
    /** The phone's own speech recognition (e.g. Samsung voice input), asked to work offline. */
    PHONE("phone"),

    /** Spokify's built-in Whisper model: English only, everything inside the app. */
    WHISPER("whisper"),
}

enum class SpeechLanguage(val key: String, val tag: String) {
    // Matches the "English (United Kingdom)" offline pack on Samsung phones.
    ENGLISH("en", "en-GB"),
    MALAY("ms", "ms-MY"),
}
