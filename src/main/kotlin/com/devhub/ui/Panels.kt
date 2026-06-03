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
fun DevPanel(state: DashboardState, selection: Int, width: Int, showHidden: Boolean) {
    if (state.prs.isEmpty()) {
        Text(
            if (state.loading) "Loading review queue…" else "✓ Nothing needs your review right now.",
            color = if (state.loading) DIM else OK,
        )
    } else {
        Text("PRs that need your review", color = Color.Cyan, textStyle = TextStyle.Bold)
        // title flexes with terminal width; the rest are fixed-width columns.
        val titleW = (width - 34).coerceIn(20, 140)
        state.prs.forEachIndexed { i, pr ->
            val sel = i == selection
            Row {
                Text(if (sel) "● " else "  ", color = if (sel) Theme.accent else DIM)
                Text("#${pr.number} ".padEnd(7), color = if (sel) SELECTED else Color.White, textStyle = if (sel) TextStyle.Bold else TextStyle.Empty)
                Text(pad(pr.title, titleW), color = if (sel) SELECTED else Color.White)
                Text(" ✓CI", color = OK)
                Text(" ✓claude", color = OK)
                Text(" ${Format.ago(pr.updatedAt)}".padStart(5), color = DIM)
            }
            if (sel) Text("    ${repoShort(pr.repo)}  ·  @${pr.author}  ·  ${Format.shortSha(pr.headSha)}", color = DIM)
        }
    }

    if (state.prsHidden > 0) {
        val hint = if (showHidden) "press x to collapse" else "press x to expand"
        Text("  ⋯ ${state.prsHidden} hidden ($hint)", color = Theme.accent)
        if (showHidden) {
            val titleW = (width - 26).coerceIn(20, 140)
            state.hiddenPrs.forEach { h ->
                Row {
                    Text("    #${h.pr.number} ".padEnd(9), color = DIM)
                    Text(pad(h.pr.title, titleW), color = Color.White)
                    Text(" [${h.reasons.joinToString(", ")}]", color = WARN)
                }
            }
        }
    }
}

// ---------------- PLATFORM ----------------

@Composable
fun PlatformPanel(state: DashboardState, selection: Int, width: Int) {
    if (state.pipelines.isEmpty()) {
        Text(
            if (state.loading) "Checking pipelines…" else "✓ No failing pipelines in watched repos.",
            color = if (state.loading) DIM else OK,
        )
        return
    }
    Text("Failing pipelines (most recent ${state.pipelines.size})", color = Color.Cyan, textStyle = TextStyle.Bold)
    state.pipelines.forEachIndexed { i, p ->
        val sel = i == selection
        val headline = "${repoShort(p.repo)} · ${p.workflowName} · ${p.branch}" +
            (p.failedJob?.let { " · $it" } ?: "")
        Row {
            Text(if (sel) "✗ " else "  ", color = BAD, textStyle = if (sel) TextStyle.Bold else TextStyle.Empty)
            Text(pad(headline, (width - 8).coerceIn(20, 180)), color = if (sel) SELECTED else Color.White)
            Text(" ${Format.ago(p.startedAt)}".padStart(5), color = DIM)
        }
        // Prefer the AI root cause; fall back to the regex excerpt.
        val cause = p.rootCause
        if (cause != null) {
            if (sel) {
                cause.lines().filter { it.isNotBlank() }.forEach { line ->
                    val isPointer = line.trimStart().startsWith("→")
                    Text("    ${wrapIndent(line, width - 6)}", color = if (isPointer) WARN else Color.White)
                }
            } else {
                Text("    ${pad(cause.lines().first { it.isNotBlank() }, width - 8)}", color = DIM)
            }
        } else {
            if (sel) p.errorExcerpt.forEach { Text("    │ ${pad(it, width - 8)}", color = WARN) }
            else p.errorExcerpt.lastOrNull()?.let { Text("    │ ${pad(it, width - 8)}", color = DIM) }
        }
    }
}

// ---------------- MAINTENANCE ----------------

@Composable
fun MaintenancePanel(state: DashboardState, selection: Int, width: Int) {
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
        val gateColor = when (q.gateStatus) { "OK" -> OK; "ERROR" -> BAD; else -> DIM }
        val gateLabel = when (q.gateStatus) { "OK" -> "PASSED"; "ERROR" -> "FAILED"; else -> "—" }
        Row {
            Text(if (sel) "● " else "  ", color = if (sel) Theme.accent else DIM)
            Text(pad(q.projectName, (width - 40).coerceIn(16, 60)), color = if (sel) SELECTED else Color.White)
            Text(" ", color = DIM)
            Text(gateLabel.padEnd(7), color = gateColor, textStyle = TextStyle.Bold)
            if (q.coverage != null) Text("cov ${"%.1f".format(q.coverage)}%", color = coverageColor(q.coverageDelta))
            if (q.newCoverage != null) Text("  new ${"%.1f".format(q.newCoverage)}%", color = DIM)
        }
        // Ratings + counts line — the at-a-glance health summary.
        Row {
            Text("      ", color = DIM)
            rating("R", q.reliabilityRating); rating("S", q.securityRating); rating("M", q.maintainabilityRating)
            count("bugs", q.bugs, danger = true); count("vulns", q.vulnerabilities, danger = true)
            count("smells", q.codeSmells ?: q.newCodeSmells); count("hotspots", q.securityHotspots)
            if (q.duplication != null) Text("dup ${"%.1f".format(q.duplication)}%  ", color = if (q.duplication > 3) WARN else DIM)
            if (q.ncloc != null) Text("LOC ${loc(q.ncloc)}", color = DIM)
        }
        if (sel) {
            q.failingConditions.forEach { c ->
                Row {
                    Text("      └ ", color = DIM)
                    Text("${c.label}: ", color = Color.White)
                    Text("${c.actual} ${c.op} ${c.threshold}", color = BAD)
                }
            }
        }
    }
}

// ---------------- GOALS ----------------

@Composable
fun GoalsPanel(state: DashboardState, selection: Int, width: Int) {
    Text("Role progress", color = Color.Cyan, textStyle = TextStyle.Bold)
    if (state.metrics.all { it.value == null }) {
        Text(if (state.loading) "Loading metrics…" else "No metrics yet (check SonarCloud project + repos).", color = DIM)
    } else {
        val barW = (width / 3).coerceIn(14, 40)
        state.metrics.forEach { MetricRow(it, barW) }
    }

    Text("")
    Text("CI health (recent runs)", color = Color.Cyan, textStyle = TextStyle.Bold)
    if (state.ci.isEmpty()) {
        Text(if (state.loading) "Loading CI stats…" else "No repos configured for CI health.", color = DIM)
    } else {
        val barW = (width / 4).coerceIn(10, 30)
        state.ci.forEachIndexed { i, h -> CiRow(h, i == selection, barW) }
    }
}

@Composable
private fun MetricRow(m: TrackedMetric, barW: Int) {
    Row {
        Text("  ${m.label.padEnd(20)}", color = Color.White)
        Text((m.value?.let { fmtMetric(it, m.unit) } ?: "—").padEnd(12), color = SELECTED, textStyle = TextStyle.Bold)
        if (m.goal != null && m.value != null) {
            Text(bar(m.value, m.goal, barW) + " ", color = barColor(m.value / m.goal))
            Text("→ ${fmtMetric(m.goal, m.unit)}", color = DIM)
        }
        if (m.delta != null && kotlin.math.abs(m.delta) >= 0.01) {
            val up = m.delta > 0
            Text("  ${if (up) "▲" else "▼"}${fmtMetric(kotlin.math.abs(m.delta), m.unit)}", color = if (up == m.higherIsBetter) OK else BAD)
        }
    }
}

@Composable
private fun CiRow(h: CiHealth, sel: Boolean, barW: Int) {
    val color = when { h.failureRatePct >= 30 -> BAD; h.failureRatePct >= 10 -> WARN; else -> OK }
    Row {
        Text(if (sel) "● " else "  ", color = if (sel) Theme.accent else DIM)
        Text(pad(repoShort(h.repo), 28).padEnd(28), color = if (sel) SELECTED else Color.White)
        Text("fail ", color = DIM)
        Text(("%.0f%%".format(h.failureRatePct)).padEnd(5), color = color, textStyle = TextStyle.Bold)
        Text(bar(h.failureRatePct, 100.0, barW) + " ", color = color)
        Text("${h.failed} failed / ${h.total} runs", color = DIM)
    }
    if (sel && h.topFailingWorkflow != null) {
        Text("    └ most-failing workflow: ${h.topFailingWorkflow}", color = DIM)
    }
}

// ---------------- shared helpers ----------------

@Composable
private fun rating(label: String, r: Int?) {
    Text("$label:", color = DIM)
    Text("${ratingLetter(r)} ", color = ratingColor(r), textStyle = TextStyle.Bold)
}

@Composable
private fun count(label: String, value: Int?, danger: Boolean = false) {
    val v = value ?: 0
    val color = when { v == 0 -> DIM; danger -> BAD; else -> WARN }
    Text("$label $v  ", color = color)
}

private fun ratingLetter(r: Int?): String = when (r) { 1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; 5 -> "E"; else -> "—" }
private fun ratingColor(r: Int?): Color = when (r) { 1, 2 -> OK; 3 -> WARN; 4, 5 -> BAD; else -> DIM }

private fun fmtMetric(v: Double, unit: String): String = when {
    unit == "%" -> "%.1f%%".format(v)
    unit.isNotBlank() -> "${v.toInt()}$unit"
    else -> if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}

private fun loc(n: Int): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

private fun bar(value: Double, goal: Double, width: Int): String {
    val ratio = if (goal <= 0) 0.0 else (value / goal).coerceIn(0.0, 1.0)
    val filled = (ratio * width).toInt()
    return "[" + "█".repeat(filled) + "░".repeat((width - filled).coerceAtLeast(0)) + "]"
}

private fun barColor(ratio: Double): Color = when { ratio >= 0.9 -> OK; ratio >= 0.6 -> WARN; else -> BAD }

private fun coverageColor(delta: Double?): Color = when {
    delta == null -> Color.White
    delta < -0.05 -> BAD
    delta > 0.05 -> OK
    else -> Color.White
}

/** Repo display name without owner or URL: "owner/Repo" or a URL → "Repo". */
private fun repoShort(repo: String): String =
    repo.removePrefix("https://").removePrefix("http://").removePrefix("github.com/")
        .trimEnd('/').substringAfterLast('/')

/** Pad-or-truncate to exactly [w] cells so columns line up. */
private fun pad(s: String, w: Int): String =
    if (s.length <= w) s.padEnd(w) else s.take((w - 1).coerceAtLeast(0)) + "…"

/** Truncate a single line to fit, leaving the indent intact. */
private fun wrapIndent(s: String, w: Int): String =
    if (s.length <= w) s else s.take((w - 1).coerceAtLeast(0)) + "…"
