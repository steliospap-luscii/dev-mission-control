package com.devhub.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the `claude` CLI once (headless print mode) to analyze ALL failures in a single batch,
 * reusing whatever authentication Claude Code already has (subscription/org browser login) — no
 * API key. Tools are disabled so it can only produce text.
 */
class ClaudeCliClient(
    private val model: String? = null,
    private val claudePath: String = "claude",
) : FailureAnalyzer {

    override suspend fun rootCauses(failures: List<FailureInput>): Map<Long, String> {
        if (failures.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val cmd = buildList {
                add(claudePath); add("-p"); add(BatchPrompt.INSTRUCTION)
                add("--output-format"); add("text")
                add("--allowedTools"); add("")          // text-only: no tool use, no permission prompts
                if (!model.isNullOrBlank()) { add("--model"); add(model) }
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
            proc.outputStream.bufferedWriter().use { it.write(BatchPrompt.buildInput(failures)) }
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            check(code == 0) { "claude CLI exited $code: ${err.trim().take(200)}" }
            BatchPrompt.parse(out)
        }
    }
}
