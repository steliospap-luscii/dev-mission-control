package com.devhub.ai

/**
 * Turns a failed CI job log into a concise root cause. Two backends implement this:
 *   - [ClaudeCliClient]  — shells out to the `claude` CLI using your existing Claude Code
 *                          login (subscription/org auth). No API key needed.
 *   - [AnthropicClient]  — calls the Anthropic API with a console API key.
 */
interface FailureAnalyzer : AutoCloseable {
    suspend fun rootCause(workflow: String, job: String?, branch: String, logTail: String): String
    override fun close() {}
}

object Analyzers {
    /**
     * Picks a backend from config. "auto" prefers the local `claude` CLI (no key, uses your
     * Claude Code auth), then the API if a key is present; "off" disables analysis.
     */
    fun create(backend: String, model: String, anthropicToken: String?): FailureAnalyzer? {
        val m = model.ifBlank { null }
        return when (backend.lowercase()) {
            "off" -> null
            "cli" -> if (claudeAvailable()) ClaudeCliClient(m) else null
            "api" -> anthropicToken?.let { AnthropicClient(it, m ?: DEFAULT_API_MODEL) }
            else -> when {                       // "auto"
                claudeAvailable() -> ClaudeCliClient(m)
                anthropicToken != null -> AnthropicClient(anthropicToken, m ?: DEFAULT_API_MODEL)
                else -> null
            }
        }
    }

    /** Human-readable description of which backend (if any) is active, for `doctor`. */
    fun describe(backend: String, anthropicToken: String?): String = when (backend.lowercase()) {
        "off" -> "disabled in config"
        "cli" -> if (claudeAvailable()) "Claude CLI (claude login)" else "CLI selected but `claude` not found"
        "api" -> if (anthropicToken != null) "Anthropic API key" else "API selected but no key set"
        else -> when {
            claudeAvailable() -> "Claude CLI (claude login)"
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

    private fun claudeAvailable() = claudeAvailable
}
