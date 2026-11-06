package com.pgratz.multiviewpdf.tts

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Base64

/** What the model is shown for one figure. */
data class DescribeRequest(
    /** "Figure 3" / "Table 1". */
    val label: String,
    val caption: String,
    val subCaptions: List<String>,
    /** The body sentence that referred to the figure, if known. */
    val referencingSentence: String?,
    val png: ByteArray,
)

sealed interface ClaudeResult {
    data class Ok(val text: String) : ClaudeResult
    data object Refused : ClaudeResult
    data class Failed(val reason: String, val retryable: Boolean, val unauthorized: Boolean = false) : ClaudeResult
}

interface ClaudeClient {
    val model: String
    suspend fun describe(req: DescribeRequest): ClaudeResult

    /** A tiny request that proves the key and model work. */
    suspend fun ping(): ClaudeResult
}

object FigurePrompts {
    const val SYSTEM =
        "You describe figures and tables from research papers for someone who is hearing the paper read aloud " +
            "and cannot see the page. Answer with two to four plain sentences of spoken prose: no markdown, lists, " +
            "headings or preamble. Begin with what kind of figure it is and its main takeaway, then the key comparison " +
            "or trend. Mention specific numbers only when they carry the point. Use the caption and the sentence that " +
            "referenced the figure for context, and do not repeat the caption word for word. Do not describe colours " +
            "or styling unless they are needed to convey the result. If the image is unreadable, say what the caption " +
            "implies instead."

    fun user(req: DescribeRequest): String = buildString {
        append(req.label).append(" of the paper. Caption: ").append(req.caption.trim())
        if (req.subCaptions.isNotEmpty()) append(" Sub-captions: ").append(req.subCaptions.joinToString(" "))
        req.referencingSentence?.takeIf { it.isNotBlank() }?.let { append(" The text referred to it in this sentence: ").append(it.trim()) }
        append(" Describe it for the listener.")
    }

    /** Turns model output into something an engine can speak. */
    fun clean(text: String): String = text
        .replace(Regex("""[*_#`>]+"""), "")
        .replace(Regex("""^\s*[-•]\s*""", RegexOption.MULTILINE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

/**
 * Anthropic Java SDK client. Thinking is left at the model's default (it cannot be
 * disabled on Opus 5.5); cost is kept down with low effort and a small output cap.
 * A refusal falls back to the caption locally, so the server-side fallback beta is not used.
 */
class SdkClaudeClient(val apiKey: String, override val model: String) : ClaudeClient {
    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .maxRetries(1)
            .timeout(Duration.ofSeconds(90))
            .build()
    }

    override suspend fun describe(req: DescribeRequest): ClaudeResult = withContext(Dispatchers.IO) {
        call {
            val image = ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                        .data(Base64.getEncoder().encodeToString(req.png))
                        .build()
                )
                .build()
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(600L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(FigurePrompts.SYSTEM)
                .addUserMessageOfContentBlockParams(
                    listOf(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(FigurePrompts.user(req)).build()),
                    )
                )
                .build()
        }
    }

    override suspend fun ping(): ClaudeResult = withContext(Dispatchers.IO) {
        call {
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(32L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("Reply with the single word OK.")
                .build()
        }
    }

    private fun call(params: () -> MessageCreateParams): ClaudeResult = try {
        val resp: Message = client.messages().create(params())
        val refused = resp.stopReason().map { it == StopReason.REFUSAL }.orElse(false)
        if (refused) {
            ClaudeResult.Refused
        } else {
            val text = resp.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString(" ")
            val cut = if (resp.stopReason().map { it == StopReason.MAX_TOKENS }.orElse(false)) text.substringBeforeLast('.') + "." else text
            ClaudeResult.Ok(FigurePrompts.clean(cut))
        }
    } catch (e: UnauthorizedException) {
        ClaudeResult.Failed("API key rejected", retryable = false, unauthorized = true)
    } catch (e: RateLimitException) {
        ClaudeResult.Failed("rate limited", retryable = true)
    } catch (e: AnthropicServiceException) {
        ClaudeResult.Failed("service error ${e.statusCode()}", retryable = e.statusCode() >= 500)
    } catch (e: AnthropicIoException) {
        ClaudeResult.Failed("network error", retryable = true)
    } catch (e: Exception) {
        ClaudeResult.Failed(e.javaClass.simpleName, retryable = false)
    }
}
