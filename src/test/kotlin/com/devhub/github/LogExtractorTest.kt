package com.devhub.github

import kotlin.test.Test
import kotlin.test.assertTrue

class LogExtractorTest {

    @Test fun `strips timestamps and surfaces the error cluster`() {
        val log = """
            2026-06-01T10:00:00.1234567Z Run tests
            2026-06-01T10:00:01.1234567Z installing deps
            2026-06-01T10:00:05.1234567Z PASS src/a.test.ts
            2026-06-01T10:00:06.1234567Z FAIL src/b.test.ts
            2026-06-01T10:00:06.2234567Z   Error: expected 200 but got 500
            2026-06-01T10:00:07.1234567Z ##[error]Process completed with exit code 1
        """.trimIndent()

        val lines = LogExtractor.errorLines(log)
        assertTrue(lines.none { it.contains("2026-06-01T") }, "timestamps should be stripped")
        assertTrue(lines.any { it.contains("FAIL src/b.test.ts") })
        assertTrue(lines.any { it.contains("exit code 1") })
    }

    @Test fun `falls back to tail when no error markers`() {
        val log = (1..20).joinToString("\n") { "2026-06-01T10:00:0${it % 10}.0000000Z line $it" }
        val lines = LogExtractor.errorLines(log)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.last().contains("line 20"))
    }

    @Test fun `handles empty log`() {
        assertTrue(LogExtractor.errorLines("").isNotEmpty())
    }

    @Test fun `drops Actions noise and prefers stripped error annotations`() {
        val log = """
            2026-06-01T10:00:00.0000000Z ##[group]Run tests
            2026-06-01T10:00:00.1000000Z ##[debug]Result: 'failure'
            2026-06-01T10:00:01.0000000Z Some test output here
            2026-06-01T10:00:02.0000000Z ##[error]AssertionError: expected true but was false
            2026-06-01T10:00:03.0000000Z ##[endgroup]
        """.trimIndent()

        val lines = LogExtractor.errorLines(log)
        assertTrue(lines.none { it.contains("##[debug]") }, "debug noise must be dropped")
        assertTrue(lines.none { it.contains("##[group]") || it.contains("##[endgroup]") })
        assertTrue(lines.any { it == "AssertionError: expected true but was false" }, "##[error] prefix stripped")
    }
}
