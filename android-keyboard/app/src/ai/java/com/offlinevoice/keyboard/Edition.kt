package com.offlinevoice.keyboard

import android.content.Context

/** Spokify AI edition: builds the AI polisher from the user's settings. */
object Edition {
    private var cachedSignature: String? = null
    private var cached: TextPolisher? = null

    /** Null when AI polish is off ([ignoreSwitch]: for the settings Test button). */
    @Synchronized
    fun polisher(context: Context, ignoreSwitch: Boolean = false): TextPolisher? {
        val prefs = Prefs(context)
        if (!prefs.aiEnabled && !ignoreSwitch) return null
        val provider = prefs.aiProvider
        val apiKey = SecretStore(context).get(provider.secretName)
            ?: return FailingPolisher("no API key saved")
        val model = prefs.aiModel(provider).ifEmpty { provider.defaultModel }
        if (model.isEmpty()) return FailingPolisher("no model chosen")
        val baseUrl = if (provider == AiProvider.CUSTOM) prefs.aiCustomBaseUrl else provider.openAiBaseUrl
        if (provider != AiProvider.CLAUDE && baseUrl.isEmpty()) return FailingPolisher("no server address")
        val system = PolishPrompt.system(prefs.aiStyle, prefs.customWordList)

        val signature = listOf(provider.key, apiKey.hashCode(), model, baseUrl, system).joinToString("|")
        if (signature == cachedSignature) cached?.let { return it }
        (cached as? AutoCloseable)?.close()
        val polisher = try {
            when (provider) {
                AiProvider.CLAUDE -> ClaudePolisher(apiKey, model, system)
                // Gemini: keep "thinking" minimal. In testing it cut replies
                // from 16-21 s to 7 s on flash models with the same result.
                AiProvider.GEMINI -> OpenAiCompatiblePolisher(
                    baseUrl, apiKey, model, system, mapOf("reasoning_effort" to "none"),
                )
                else -> OpenAiCompatiblePolisher(baseUrl, apiKey, model, system)
            }
        } catch (e: Throwable) {
            // E.g. the Claude SDK needs something this phone's Android lacks.
            return FailingPolisher("couldn't start the AI service on this phone")
        }
        cached = polisher
        cachedSignature = signature
        return polisher
    }
}
