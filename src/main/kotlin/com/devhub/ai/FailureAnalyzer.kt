package com.devhub.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** One failed job to analyze, identified by its Actions run id. */
data class FailureInput(
    val runId: Long,
    val workflow: String,
    val job: String?,
    val branch: String,
    val logTail: String,
)

/**
 * Turns failed CI job logs into concise root causes — **in one batched call** for all failures.
 * Two backends implement this:
 *   - [ClaudeCliClient]  — shells out to the `claude` CLI using your existing Claude Code login.
 *   - [AnthropicClient]  — calls the Anthropic API with a console API key.
 *
 * Returns a map of run id → root cause (1-line cause + a "→ " key-error line). Missing entries
 * just fall back to the regex excerpt, so partial/!empty results are fine.
 */
interface FailureAnalyzer : AutoCloseable {
    suspend fun rootCauses(failures: List<FailureInput>): Map<Long, String>
    override fun close() {}
}

object Analyzers {
    fun create(backend: String, model: String, anthropicToken: String?): FailureAnalyzer? {
        val m = model.ifBlank { null }
        return when (backend.lowercase()) {
            "off" -> null
            "cli" -> if (claudeAvailable) ClaudeCliClient(m) else null
            "api" -> anthropicToken?.let { AnthropicClient(it, m ?: DEFAULT_API_MODEL) }
            else -> when {                       // "auto"
                claudeAvailable -> ClaudeCliClient(m)
                anthropicToken != null -> AnthropicClient(anthropicToken, m ?: DEFAULT_API_MODEL)
                else -> null
            }
        }
    }

    fun describe(backend: String, anthropicToken: String?): String = when (backend.lowercase()) {
        "off" -> "disabled in config"
        "cli" -> if (claudeAvailable) "Claude CLI (claude login)" else "CLI selected but `claude` not found"
        "api" -> if (anthropicToken != null) "Anthropic API key" else "API selected but no key set"
        else -> when {
            claudeAvailable -> "Claude CLI (claude login)"
            anthropicToken != null -> "Anthropic API key"
            else -> "unavailable (no `claude` CLI and no API key)"
        }
    }

    private const val DEFAULT_API_MODEL = "claude-haiku-4-5"

    private val claudeAvailable: Boolean by lazy {
        runCatching {
            ProcessBuilder("claude", "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }
}

/** Shared prompt construction + JSON parsing for the batched analysis. */
internal object BatchPrompt {

    val INSTRUCTION = """
        You are a CI failure analyst. The input contains one or more failed GitHub Actions jobs,
        each delimited by a line "=== RUN <id> ===". For EACH run, determine the real root cause.
        Reply with ONLY a JSON object (no markdown fences, no prose) mapping the run id (as a string)
        to a value of the form: "<one-sentence root cause>\n→ <key error/exception verbatim>".
        Ignore generic wrapper noise like "Process completed with exit code 1", "##[debug]", and step
        boundaries. Be specific and terse.
    """.trimIndent()

    fun buildInput(failures: List<FailureInput>): String = buildString {
        failures.forEach { f ->
            appendLine("=== RUN ${f.runId} ===")
            appendLine("Workflow: ${f.workflow}")
            appendLine("Job: ${f.job ?: "?"}")
            appendLine("Branch: ${f.branch}")
            appendLine("Log:")
            appendLine(f.logTail)
            appendLine()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the model's JSON object, tolerating ```fences``` and stray prose around it. */
    fun parse(output: String): Map<Long, String> {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        return runCatching {
            json.parseToJsonElement(output.substring(start, end + 1))
                .let { it as kotlinx.serialization.json.JsonObject }
                .mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> id to v.jsonPrimitive.content } }
                .toMap()
        }.getOrDefault(emptyMap())
    }
}
