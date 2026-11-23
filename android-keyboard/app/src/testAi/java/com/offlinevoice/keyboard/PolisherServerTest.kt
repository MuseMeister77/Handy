package com.offlinevoice.keyboard

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs the AI edition's polishers against a fake AI server on this machine,
 * to check exactly what is sent and how every kind of reply is handled.
 */
class PolisherServerTest {
    private lateinit var server: ServerSocket
    @Volatile private var status = 200
    @Volatile private var reply = ""
    @Volatile private var requests = 0
    @Volatile private var lastPath = ""
    @Volatile private var lastBody = JSONObject()
    private val lastHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val base get() = "http://127.0.0.1:${server.localPort}"

    /** A minimal HTTP server: one request per connection, then close. */
    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                try { handle(socket) } catch (_: Exception) { } finally { socket.close() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun readLine(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            return line.toString("UTF-8")
        }
        val requestLine = readLine()
        lastPath = requestLine.split(" ").getOrElse(1) { "" }.substringBefore('?')
        lastHeaders.clear()
        var length = 0
        while (true) {
            val h = readLine()
            if (h.isEmpty()) break
            val name = h.substringBefore(':').trim().lowercase()
            val value = h.substringAfter(':').trim()
            lastHeaders[name] = lastHeaders[name]?.let { "$it,$value" } ?: value
            if (name == "content-length") length = value.toInt()
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        lastBody = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
        requests++
        val bytes = reply.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    @After fun stop() = server.close()

    private fun claudeMessage(text: String, stop: String = "end_turn") = JSONObject()
        .put("id", "msg_test").put("type", "message").put("role", "assistant")
        .put("model", "claude-opus-5-5")
        .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
        .toString()

    private fun claudeError(type: String) =
        JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", "x")).toString()

    private fun claude(model: String = "claude-opus-5-5") = ClaudePolisher("sk-ant-api03-test", model, "SYSTEM", base)

    // ---- Claude (official SDK) ---------------------------------------------------

    @Test fun claudeSendsTheRightRequest() {
        reply = claudeMessage("Let's meet on Monday.")
        val r = claude().polish("lets meet on monday")
        assertEquals(PolishResult.Ok("Let's meet on Monday."), r)
        assertTrue(lastPath, lastPath.endsWith("/v1/messages"))
        // The SDK sends the key as x-api-key or "Authorization: Bearer" depending on version.
        val key = "sk-ant-api03-test"
        assertTrue(lastHeaders["x-api-key"] == key || lastHeaders["authorization"] == "Bearer $key")
        assertTrue(lastHeaders["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        assertEquals("claude-opus-5-5", lastBody.getString("model"))
        assertEquals("default", lastBody.get("fallbacks"))
        assertEquals("low", lastBody.getJSONObject("output_config").getString("effort"))
        assertEquals("SYSTEM", lastBody.getString("system"))
        val user = lastBody.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", user.getString("role"))
        assertTrue(user.toString().contains("<dictation>"))
    }

    @Test fun claudeModelsWithoutServerFallbackDontSendIt() {
        reply = claudeMessage("Hi.")
        claude("claude-haiku-5-5").polish("hi")
        assertTrue(!lastBody.has("fallbacks"))
        assertTrue(!lastHeaders["anthropic-beta"].orEmpty().contains("server-side-fallback"))
    }

    @Test fun claudeRefusalFallsBackToSpokifyText() {
        reply = claudeMessage("", stop = "refusal")
        assertEquals(PolishResult.Failed("Claude declined this text"), claude().polish("hello"))
    }

    @Test fun claudeWrongKey() {
        status = 401
        reply = claudeError("authentication_error")
        assertEquals(PolishResult.Failed("invalid API key"), claude().polish("hello"))
        assertEquals(1, requests) // not retried
    }

    @Test fun claudeRateLimit() {
        status = 429
        reply = claudeError("rate_limit_error")
        assertEquals(PolishResult.Failed("rate limit reached"), claude().polish("hello"))
    }

    @Test fun claudeUnknownModel() {
        status = 404
        reply = claudeError("not_found_error")
        assertEquals(PolishResult.Failed("model \"claude-opus-5-5\" not found"), claude().polish("hello"))
    }

    @Test fun claudeReplyThatIsNotAClean_upIsRejected() {
        reply = claudeMessage("Sure! Here is a long essay. ".repeat(20))
        assertEquals(PolishResult.Failed("AI added text that wasn't said"), claude().polish("hello"))
    }

    @Test fun claudeServerDown() {
        val polisher = claude()
        server.close()
        assertEquals(PolishResult.Failed("no connection"), polisher.polish("hello"))
    }

    // ---- OpenAI-compatible (ChatGPT, Gemini, others) -----------------------------

    private fun openAi(extras: Map<String, Any> = emptyMap()) =
        OpenAiCompatiblePolisher("$base/v1/", "sk-test", "some-model", "SYSTEM", extras)

    private fun chatReply(text: String, finish: String = "stop") = JSONObject().put(
        "choices",
        org.json.JSONArray().put(
            JSONObject().put("finish_reason", finish)
                .put("message", JSONObject().put("role", "assistant").put("content", text)),
        ),
    ).toString()

    @Test fun openAiSendsTheRightRequest() {
        reply = chatReply("Send the invoice to finance.")
        assertEquals(PolishResult.Ok("Send the invoice to finance."), openAi().polish("send the invoice to finance"))
        assertEquals("/v1/chat/completions", lastPath)
        assertEquals("Bearer sk-test", lastHeaders["authorization"])
        assertEquals("some-model", lastBody.getString("model"))
        assertEquals(0, lastBody.getInt("temperature"))
        val messages = lastBody.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYSTEM", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("<dictation>"))
    }

    @Test fun openAiErrors() {
        status = 401; reply = "{}"
        assertEquals(PolishResult.Failed("invalid API key"), openAi().polish("hi"))
        status = 429
        assertEquals(PolishResult.Failed("usage limit reached at your AI provider"), openAi().polish("hi"))
        status = 404
        assertEquals(PolishResult.Failed("model \"some-model\" or server address not found"), openAi().polish("hi"))
        status = 500
        assertEquals(PolishResult.Failed("AI service error 500"), openAi().polish("hi"))
        status = 200; reply = "not json"
        assertEquals(PolishResult.Failed("unexpected reply"), openAi().polish("hi"))
    }

    @Test fun openAiCutOffReplyIsRejected() {
        reply = chatReply("Send the invoice", finish = "length")
        assertEquals(PolishResult.Failed("AI reply was cut off"), openAi().polish("send the invoice to finance today"))
    }

    @Test fun geminiExtrasAreSentAndDroppedIfRejected() {
        reply = chatReply("Hi there.")
        openAi(mapOf("reasoning_effort" to "none")).polish("hi there")
        assertEquals("none", lastBody.getString("reasoning_effort"))

        // A model that rejects the extra field: Spokify retries without it.
        status = 400
        reply = "{}"
        requests = 0
        val r = openAi(mapOf("reasoning_effort" to "none")).polish("hi there")
        assertEquals(2, requests)
        assertTrue(!lastBody.has("reasoning_effort"))
        assertEquals(PolishResult.Failed("request rejected (check the model name)"), r)
    }

    @Test fun openAiBusy() {
        status = 503
        reply = "{}"
        assertEquals(PolishResult.Failed("AI service busy, try a lighter model"), openAi().polish("hi"))
    }
}
