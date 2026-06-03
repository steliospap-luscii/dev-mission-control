package com.devhub.github

/**
 * Pulls the meaningful error lines out of a raw GitHub Actions job log so the
 * Platform panel shows what broke without you opening the browser.
 *
 * Strategy: drop Actions workflow-command noise (##[debug]/##[group]/...), then
 * prefer the cluster around explicit ##[error] annotations; fall back to generic
 * error-ish lines, then to the tail of the log.
 */
object LogExtractor {

    // GitHub prefixes every log line with an ISO-8601 timestamp + space.
    private val TIMESTAMP = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s""")

    // ANSI CSI sequences: ESC '[' params <letter>. ESC is built from its code
    // point so the source stays plain ASCII.
    private val ANSI = Regex(Char(0x1B) + """\[[0-9;]*[A-Za-z]""")

    // Workflow-command lines that are bookkeeping, not failure content.
    private val NOISE = Regex("""^##\[(debug|group|endgroup|command|section|notice|warning)]""")
    private const val ERROR_MARKER = "##[error]"

    private val ERROR_HINT = Regex(
        """(?i)(\b(error|errors|fail|failed|failing|failure|exception|fatal|panic|traceback|cannot|not found|undefined|exit code [1-9])\b|##\[error])""",
    )

    private const val MAX_LINES = 8
    private const val MAX_WIDTH = 200

    fun errorLines(rawLog: String): List<String> {
        val lines = rawLog.lineSequence()
            .map { it.replace(TIMESTAMP, "").replace(ANSI, "").trimEnd() }
            .filter { it.isNotBlank() && !NOISE.containsMatchIn(it) }
            .map { it.removePrefix(ERROR_MARKER) to it.startsWith(ERROR_MARKER) }
            .toList()
        if (lines.isEmpty()) return listOf("(no error detail in log)")

        val explicit = lines.withIndex().filter { it.value.second }.map { it.index }
        val hints = lines.withIndex().filter { ERROR_HINT.containsMatchIn(it.value.first) }.map { it.index }

        val chosen = when {
            explicit.isNotEmpty() -> window(lines.size, explicit.last())
            hints.isNotEmpty() -> window(lines.size, hints.last(), hints.first())
            else -> (lines.size - MAX_LINES).coerceAtLeast(0) until lines.size
        }
        return chosen.map { lines[it].first }.map { line ->
            if (line.length > MAX_WIDTH) line.take(MAX_WIDTH - 1) + "…" else line
        }
    }

    /** Cleaned (timestamp/ANSI/noise-stripped) tail of a log, for feeding to an LLM. */
    fun cleanTail(rawLog: String, maxChars: Int = 6000): String {
        val cleaned = rawLog.lineSequence()
            .map { it.replace(TIMESTAMP, "").replace(ANSI, "").trimEnd() }
            .filter { it.isNotBlank() && !NOISE.containsMatchIn(it) }
            .map { it.removePrefix(ERROR_MARKER) }
            .joinToString("\n")
        return if (cleaned.length <= maxChars) cleaned else cleaned.takeLast(maxChars)
    }

    /** A window of up to MAX_LINES ending at [end], not starting before [floor]. */
    private fun window(size: Int, end: Int, floor: Int = 0): IntRange {
        val start = (end - MAX_LINES + 1).coerceAtLeast(floor).coerceAtLeast(0)
        return start..end.coerceAtMost(size - 1)
    }
}
