package com.devhub.ui

import com.devhub.core.FailedPipeline
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Small presentation helpers shared by the panels. */
object Format {

    /** Compact "2h"/"3d"/"just now" relative age. */
    fun ago(instant: Instant?): String {
        if (instant == null) return "—"
        val secs = (Clock.System.now() - instant).inWholeSeconds
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            else -> "${secs / 86_400}d"
        }
    }

    fun coverage(value: Double?, delta: Double?): String {
        if (value == null) return "coverage —"
        val base = "coverage ${"%.1f".format(value)}%"
        if (delta == null || kotlin.math.abs(delta) < 0.05) return base
        val arrow = if (delta > 0) "▲" else "▼"
        return "$base ($arrow${"%.1f".format(kotlin.math.abs(delta))})"
    }

    fun pipelineHeadline(p: FailedPipeline): String =
        "${p.repo} · ${p.workflowName} · ${p.branch}" + (p.failedJob?.let { " · job: $it" } ?: "")

    fun shortSha(sha: String): String = sha.take(7)
}
