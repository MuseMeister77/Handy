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
) : TextPolisher {

    override fun polish(text: String): PolishResult {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", PolishPrompt.user(text))),
            )
            .toString()

        val conn = try {
            URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return PolishResult.Failed("invalid server address")
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val reply = json.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                    PolishPrompt.accept(text, reply)?.let { PolishResult.Ok(it) }
                        ?: PolishResult.Failed("unexpected reply")
                }
                401, 403 -> PolishResult.Failed("invalid API key")
                404 -> PolishResult.Failed("model \"$model\" or server address not found")
                429 -> PolishResult.Failed("rate limit or quota reached")
                400 -> PolishResult.Failed("request rejected (check the model name)")
                else -> PolishResult.Failed("AI service error $code")
            }
        } catch (e: SocketTimeoutException) {
            PolishResult.Failed("AI took too long")
        } catch (e: IOException) {
            PolishResult.Failed("no connection")
        } catch (e: Exception) {
            PolishResult.Failed("unexpected reply")
        } finally {
            conn.disconnect()
        }
    }
}
