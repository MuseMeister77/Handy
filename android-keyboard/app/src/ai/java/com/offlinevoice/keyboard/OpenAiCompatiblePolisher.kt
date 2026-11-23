package com.offlinevoice.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * AI polish through the widely supported OpenAI-style "chat completions"
 * API: ChatGPT (OpenAI), Gemini (Google's OpenAI-compatible endpoint), or
 * any other compatible service the user points it at.
 */
class OpenAiCompatiblePolisher(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    /**
     * Extra request fields for this provider, e.g. Gemini's reasoning_effort.
     * Dropped automatically if the model rejects them.
     */
    private val extras: Map<String, Any> = emptyMap(),
) : TextPolisher {

    override fun polish(text: String): PolishResult {
        val first = request(text, extras)
        // Some models don't accept the extras (e.g. a "thinking" setting on a
        // model that doesn't think): try once more without them.
        return if (first is Attempt.Rejected && extras.isNotEmpty()) request(text, emptyMap()).result else first.result
    }

    private sealed class Attempt(val result: PolishResult) {
        class Done(result: PolishResult) : Attempt(result)
        class Rejected(result: PolishResult) : Attempt(result)
    }

    private fun request(text: String, extra: Map<String, Any>): Attempt {
        val body = JSONObject()
            .put("model", model)
            // Clean-up should be predictable, not creative.
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", PolishPrompt.user(text))),
            )

        for ((k, v) in extra) body.put(k, v)

        val conn = try {
            URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Attempt.Done(PolishResult.Failed("invalid server address"))
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val reply = choice?.optJSONObject("message")?.optString("content")
                    val cutOff = choice?.optString("finish_reason") == "length"
                    Attempt.Done(PolishPrompt.review(text, reply, truncated = cutOff))
                }
                401, 403 -> Attempt.Done(PolishResult.Failed("invalid API key"))
                404 -> Attempt.Done(PolishResult.Failed("model \"$model\" or server address not found"))
                429 -> Attempt.Done(PolishResult.Failed("usage limit reached at your AI provider"))
                503 -> Attempt.Done(PolishResult.Failed("AI service busy, try a lighter model"))
                400 -> Attempt.Rejected(PolishResult.Failed("request rejected (check the model name)"))
                else -> Attempt.Done(PolishResult.Failed("AI service error $code"))
            }
        } catch (e: SocketTimeoutException) {
            Attempt.Done(PolishResult.Failed("AI took too long, try a lighter model"))
        } catch (e: IOException) {
            Attempt.Done(PolishResult.Failed("no connection"))
        } catch (e: Exception) {
            Attempt.Done(PolishResult.Failed("unexpected reply"))
        } finally {
            conn.disconnect()
        }
    }
}
