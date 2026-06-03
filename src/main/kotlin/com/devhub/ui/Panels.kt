package com.devhub.ui

import androidx.compose.runtime.Composable
import com.devhub.core.CiHealth
import com.devhub.core.DashboardState
import com.devhub.core.TrackedMetric
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

private val SELECTED = Theme.selected
private val DIM = Theme.dim
private val OK = Theme.ok
private val BAD = Theme.bad
private val WARN = Theme.warn

// ---------------- DEV ----------------

@Composable
fun DevPanel(state: DashboardState, selection: Int) {
    if (state.prs.isEmpty()) {
        Text(
            if (state.loading) "Loading review queue…" else "✓ Nothing needs your review right now.",
            color = if (state.loading) DIM else OK,
        )
    } else {
        Text("PRs that need your review (filtered)", color = Color.Cyan, textStyle = TextStyle.Bold)
        state.prs.forEachIndexed { i, pr ->
            val sel = i == selection
            Row {
                Text(if (sel) "● " else "  ", color = if (sel) Theme.accent else DIM)
                Text("#${pr.number} ", color = if (sel) SELECTED else Color.White, textStyle = if (sel) TextStyle.Bold else TextStyle.Empty)
                Text(truncate(pr.title, 52).padEnd(52), color = if (sel) SELECTED else Color.White)
                Text("  ✓CI", color = OK)
                Text("  ✓claude", color = OK)
                Text("  ${Format.ago(pr.updatedAt)}", color = DIM)
            }
            if (sel) {
                Text("    ${pr.repo}  ·  @${pr.author}  ·  ${Format.shortSha(pr.headSha)}", color = DIM)
            }
        }
    }
    if (state.prsHidden > 0) {
        Text(
            "  ⋯ ${state.prsHidden} hidden (draft / CI not green / awaiting claude[bot])",
            color = DIM,
        )
    }
}

// ---------------- PLATFORM ----------------

@Composable
fun PlatformPanel(state: DashboardState, selection: Int) {
    if (state.pipelines.isEmpty()) {
        Text(
            if (state.loading) "Checking pipelines…" else "✓ No failing pipelines in watched repos.",
            color = if (state.loading) DIM else OK,
        )
        return
    }
    Text("Failing pipelines", color = Color.Cyan, textStyle = TextStyle.Bold)
    state.pipelines.forEachIndexed { i, p ->
        val sel = i == selection
        Row {
            Text(if (sel) "✗ " else "  ", color = BAD, textStyle = if (sel) TextStyle.Bold else TextStyle.Empty)
            Text(Format.pipelineHeadline(p), color = if (sel) SELECTED else Color.White)
            Text("  ${Format.ago(p.startedAt)}", color = DIM)
        }
        // Show the extracted error for the selected run; collapse the rest to one line.
        if (sel) {
            p.errorExcerpt.forEach { line -> Text("    │ $line", color = WARN) }
        } else {
            p.errorExcerpt.lastOrNull()?.let { Text("    │ ${truncate(it, 80)}", color = DIM) }
        }
    }
}

// ---------------- MAINTENANCE ----------------

@Composable
fun MaintenancePanel(state: DashboardState, selection: Int) {
    if (state.quality.isEmpty()) {
        Text(
            if (state.loading) "Fetching SonarCloud metrics…" else "No SonarCloud projects configured.",
            color = DIM,
        )
        return
    }
    Text("Code quality (SonarCloud)", color = Color.Cyan, textStyle = TextStyle.Bold)
    state.quality.forEachIndexed { i, q ->
        val sel = i == selection
        val gateColor = when (q.gateStatus) {
            "OK" -> OK; "ERROR" -> BAD; else -> DIM
        }
        val gateLabel = when (q.gateStatus) {
            "OK" -> "PASSED"; "ERROR" -> "FAILED"; else -> "—"
        }
        Row {
            Text(if (sel) "● " else "  ", color = if (sel) Theme.accent else DIM)
            Text(truncate(q.projectName, 30).padEnd(30), color = if (sel) SELECTED else Color.White)
            Text("gate ", color = DIM)
            Text(gateLabel.padEnd(7), color = gateColor, textStyle = TextStyle.Bold)
            if (q.coverage != null) {
                Text(Format.coverage(q.coverage, q.coverageDelta), color = coverageColor(q.coverageDelta))
            }
        }
        // The failing gate conditions are the actionable part: expand for the selected
        // project, summarize the rest on one line.
        if (q.failingConditions.isNotEmpty()) {
            if (sel) {
                q.failingConditions.forEach { c ->
                    Row {
                        Text("    └ ", color = DIM)
                        Text("${c.label}: ", color = Color.White)
                        Text("${c.actual} ${c.op} ${c.threshold}", color = BAD)
                    }
                }
            } else {
                val summary = q.failingConditions.joinToString(", ") { "${it.label} ${it.actual}" }
                Text("    └ ${truncate(summary, 76)}", color = DIM)
            }
        }
    }
}

// ---------------- GOALS ----------------

@Composable
fun GoalsPanel(state: DashboardState, selection: Int) {
    Text("Role progress", color = Color.Cyan, textStyle = TextStyle.Bold)
    if (state.metrics.all { it.value == null }) {
        Text(
            if (state.loading) "Loading metrics…" else "No metrics yet (check SonarCloud project + repos).",
            color = DIM,
        )
    } else {
        state.metrics.forEach { MetricRow(it) }
    }

    Text("")
    Text("CI health (recent runs)", color = Color.Cyan, textStyle = TextStyle.Bold)
    if (state.ci.isEmpty()) {
        Text(
            if (state.loading) "Loading CI stats…" else "No repos configured for CI health.",
            color = DIM,
        )
    } else {
        state.ci.forEachIndexed { i, h -> CiRow(h, i == selection) }
    }
}

@Composable
private fun MetricRow(m: TrackedMetric) {
    Row {
        Text("  ${m.label.padEnd(20)}", color = Color.White)
        Text((m.value?.let { fmt(it, m.unit) } ?: "—").padEnd(13), color = SELECTED, textStyle = TextStyle.Bold)
        if (m.goal != null && m.value != null) {
            Text(bar(m.value, m.goal, 18) + " ", color = barColor(m.value / m.goal))
            Text("→ ${fmt(m.goal, m.unit)}", color = DIM)
        }
        if (m.delta != null && kotlin.math.abs(m.delta) >= 0.01) {
            val up = m.delta > 0
            val good = up == m.higherIsBetter
            Text("  ${if (up) "▲" else "▼"}${fmt(kotlin.math.abs(m.delta), m.unit)}", color = if (good) OK else BAD)
        }
    }
}

@Composable
private fun CiRow(h: CiHealth, sel: Boolean) {
    val color = when {
        h.failureRatePct >= 30 -> BAD
        h.failureRatePct >= 10 -> WARN
        else -> OK
    }
    Row {
        Text(if (sel) "● " else "  ", color = if (sel) Theme.accent else DIM)
        Text(truncate(shortRepo(h.repo), 32).padEnd(32), color = if (sel) SELECTED else Color.White)
        Text("fail ", color = DIM)
        Text(("%.0f%%".format(h.failureRatePct)).padEnd(5), color = color, textStyle = TextStyle.Bold)
        Text(bar(h.failureRatePct, 100.0, 12) + " ", color = color)
        Text("${h.failed} failed / ${h.total} runs", color = DIM)
    }
    if (sel && h.topFailingWorkflow != null) {
        Text("    └ most-failing workflow: ${h.topFailingWorkflow}", color = DIM)
    }
}

private fun fmt(v: Double, unit: String): String = when {
    unit == "%" -> "%.1f%%".format(v)
    unit.isNotBlank() -> "${v.toInt()}$unit"
    else -> if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}

/** A [width]-cell progress bar for value/goal, clamped to [0,1]. */
private fun bar(value: Double, goal: Double, width: Int): String {
    val ratio = if (goal <= 0) 0.0 else (value / goal).coerceIn(0.0, 1.0)
    val filled = (ratio * width).toInt()
    return "[" + "█".repeat(filled) + "░".repeat((width - filled).coerceAtLeast(0)) + "]"
}

private fun barColor(ratio: Double): Color = when {
    ratio >= 0.9 -> OK
    ratio >= 0.6 -> WARN
    else -> BAD
}

private fun shortRepo(repo: String): String =
    repo.removePrefix("https://").removePrefix("http://").removePrefix("github.com/").trimEnd('/')

private fun coverageColor(delta: Double?): Color = when {
    delta == null -> Color.White
    delta < -0.05 -> BAD
    delta > 0.05 -> OK
    else -> Color.White
}

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max - 1) + "…"
