package com.devhub.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thin Anthropic Messages-API client used to turn a noisy CI job log into a concise,
 * human root cause — far more useful than the regex excerpt for gnarly failures.
 */
class AnthropicClient(apiKey: String, private val model: String) : FailureAnalyzer {

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
        }
        expectSuccess = true
    }

    override suspend fun rootCause(workflow: String, job: String?, branch: String, logTail: String): String {
        val user = buildString {
            appendLine("Workflow: $workflow")
            appendLine("Job: ${job ?: "?"}")
            appendLine("Branch: $branch")
            appendLine()
            appendLine("Failed job log (tail):")
            append(logTail)
        }
        val resp: MessageResponse = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(
                MessageRequest(
                    model = model,
                    maxTokens = 250,
                    temperature = 0.0,
                    system = SYSTEM,
                    messages = listOf(Message("user", user)),
                ),
            )
        }.body()
        return resp.content.firstOrNull { it.type == "text" }?.text?.trim().orEmpty()
    }

    override fun close() = http.close()

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private val SYSTEM = """
            You analyze failed GitHub Actions job logs. Reply with the single most likely
            ROOT CAUSE of the failure in ONE short sentence, then a second line beginning
            "→ " with the key error/assertion/exception (verbatim if possible). Ignore
            generic wrapper noise like "Process completed with exit code 1", "##[debug]",
            and step boundaries. Be specific and terse. No preamble, no markdown.
        """.trimIndent()
    }
}

@Serializable
private data class MessageRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double,
    val system: String,
    val messages: List<Message>,
)

@Serializable
private data class Message(val role: String, val content: String)

@Serializable
private data class MessageResponse(val content: List<ContentBlock> = emptyList())

@Serializable
private data class ContentBlock(val type: String = "", val text: String = "")
