package com.devhub.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the `claude` CLI in headless print mode to analyze a failure log, reusing whatever
 * authentication Claude Code already has (subscription/org browser login) — no API key.
 * Tools are disabled so it can only produce text.
 */
class ClaudeCliClient(
    private val model: String? = null,
    private val claudePath: String = "claude",
) : FailureAnalyzer {

    override suspend fun rootCause(workflow: String, job: String?, branch: String, logTail: String): String =
        withContext(Dispatchers.IO) {
            val cmd = buildList {
                add(claudePath); add("-p"); add(INSTRUCTION)
                add("--output-format"); add("text")
                add("--allowedTools"); add("")          // text-only: no tool use, no permission prompts
                if (!model.isNullOrBlank()) { add("--model"); add(model) }
            }
            val input = buildString {
                appendLine("Workflow: $workflow")
                appendLine("Job: ${job ?: "?"}")
                appendLine("Branch: $branch")
                appendLine()
                appendLine("Failed job log (tail):")
                append(logTail)
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
            proc.outputStream.bufferedWriter().use { it.write(input) } // small (≤ a few KB), safe to write then read
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            check(code == 0) { "claude CLI exited $code: ${err.trim().take(200)}" }
            out.trim()
        }

    companion object {
        private val INSTRUCTION = """
            You are analyzing a failed GitHub Actions job log provided on stdin. Reply with the
            single most likely ROOT CAUSE in ONE short sentence, then a second line starting with
            "→ " quoting the key error/assertion/exception. Ignore generic wrapper noise like
            "Process completed with exit code 1", "##[debug]", and step boundaries. Be specific
            and terse. No preamble, no markdown, no tool use.
        """.trimIndent()
    }
}
