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
 * Anthropic Messages-API backend. One batched request analyzes all failures at once.
 * Used only when the `api` backend is selected (needs a console API key).
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

    override suspend fun rootCauses(failures: List<FailureInput>): Map<Long, String> {
        if (failures.isEmpty()) return emptyMap()
        val resp: MessageResponse = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(
                MessageRequest(
                    model = model,
                    maxTokens = 1024,
                    temperature = 0.0,
                    system = BatchPrompt.INSTRUCTION,
                    messages = listOf(Message("user", BatchPrompt.buildInput(failures))),
                ),
            )
        }.body()
        val text = resp.content.firstOrNull { it.type == "text" }?.text.orEmpty()
        return BatchPrompt.parse(text)
    }

    override fun close() = http.close()

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
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
