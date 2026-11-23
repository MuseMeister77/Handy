package com.offlinevoice.keyboard

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/** AI polish with Claude, through Anthropic's official Java SDK. */
class ClaudePolisher(
    apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    baseUrl: String? = null, // tests only
) : TextPolisher, AutoCloseable {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // Someone is waiting with the keyboard open: give up quickly and
        // let Spokify type its own clean-up instead.
        .timeout(Duration.ofSeconds(15))
        .maxRetries(1)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override fun polish(text: String): PolishResult = try {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .system(systemPrompt)
            .addUserMessage(PolishPrompt.user(text))
            // Tidying a dictation is a light task: low effort keeps it fast.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .apply {
                // If the model declines, Anthropic re-runs the request on a
                // suitable fallback model within the same call.
                if (model in SERVER_FALLBACK_MODELS) {
                    addBeta("server-side-fallback-2026-07-01")
                    fallbacksDefault()
                }
            }
            .build()
        val message = client.beta().messages().create(params)
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            PolishResult.Failed("Claude declined this text")
        } else {
            val reply = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            PolishPrompt.accept(text, reply)?.let { PolishResult.Ok(it) }
                ?: PolishResult.Failed("unexpected reply")
        }
    } catch (e: UnauthorizedException) {
        PolishResult.Failed("invalid API key")
    } catch (e: PermissionDeniedException) {
        PolishResult.Failed("API key not allowed to use this model")
    } catch (e: NotFoundException) {
        PolishResult.Failed("model \"$model\" not found")
    } catch (e: RateLimitException) {
        PolishResult.Failed("rate limit reached")
    } catch (e: BadRequestException) {
        PolishResult.Failed("request rejected (check the model name)")
    } catch (e: AnthropicServiceException) {
        PolishResult.Failed("Claude error ${e.statusCode()}")
    } catch (e: AnthropicIoException) {
        PolishResult.Failed("no connection")
    } catch (e: Throwable) {
        // Includes errors from parts of the SDK an older phone can't run.
        PolishResult.Failed("unexpected error")
    }

    override fun close() = client.close()

    companion object {
        /** Models that support Anthropic's server-side refusal fallback. */
        private val SERVER_FALLBACK_MODELS = setOf(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-5", "claude-fable-5-2", "claude-fable-5-1",
        )
    }
}
