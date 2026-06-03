package com.devhub.ui

import com.devhub.core.CiHealth
import com.devhub.core.DashboardState
import com.devhub.core.QualityReport
import com.devhub.core.TrackedMetric
import com.jakewharton.mosaic.ui.Color

private val SELECTED = Theme.selected
private val TEXT = Theme.text
private val DIM = Theme.dim
private val OK = Theme.ok
private val BAD = Theme.bad
private val WARN = Theme.warn

// ---------------- DEV ----------------

fun devLines(state: DashboardState, selection: Int, width: Int, expanded: Boolean): LineBuf {
    val b = LineBuf()
    if (state.prs.isEmpty()) {
        b.add(seg(if (state.loading) "◴ Loading review queue…" else "✓ Nothing needs your review right now.", if (state.loading) Theme.info else OK))
    } else {
        b.add(seg("PRs that need your review", Theme.accent, bold = true))
        val titleW = (width - 34).coerceIn(20, 140)
        state.prs.forEachIndexed { i, pr ->
            val sel = i == selection
            if (sel) b.anchorHere()
            b.add(
                seg(if (sel) "● " else "  ", if (sel) Theme.accent else DIM),
                seg("#${pr.number} ".padEnd(7), if (sel) SELECTED else TEXT, bold = sel),
                seg(pad(pr.title, titleW), if (sel) SELECTED else TEXT),
                seg(" ✓CI", OK), seg(" ✓claude", OK),
                seg(" ${Format.ago(pr.updatedAt)}".padStart(5), DIM),
            )
            if (sel) b.add(seg("    ${repoShort(pr.repo)}  ·  @${pr.author}  ·  ${Format.shortSha(pr.headSha)}", DIM))
        }
    }
    if (state.prsHidden > 0) {
        b.add(seg("  ⋯ ${state.prsHidden} hidden (${if (expanded) "press x to collapse" else "press x to expand"})", Theme.accent))
        if (expanded) {
            val titleW = (width - 26).coerceIn(20, 140)
            state.hiddenPrs.forEach { h ->
                b.add(
                    seg("    #${h.pr.number} ".padEnd(9), DIM),
                    seg(pad(h.pr.title, titleW), TEXT),
                    seg(" [${h.reasons.joinToString(", ")}]", WARN),
                )
            }
        }
    }
    return b
}

// ---------------- PLATFORM ----------------

fun platformLines(state: DashboardState, selection: Int, width: Int, expanded: Boolean): LineBuf {
    val b = LineBuf()
    if (state.pipelines.isEmpty()) {
        b.add(seg(if (state.loading) "◴ Analyzing pipelines…" else "✓ No failing pipelines in watched repos.", if (state.loading) Theme.info else OK))
        return b
    }
    b.add(seg("Failing pipelines (most recent ${state.pipelines.size})" + if (state.loading) "  ◴ analyzing…" else "", Theme.accent, bold = true))
    state.pipelines.forEachIndexed { i, p ->
        val sel = i == selection
        if (sel) b.anchorHere()
        val headline = "${repoShort(p.repo)} · ${p.workflowName} · ${p.branch}" + (p.failedJob?.let { " · $it" } ?: "")
        b.add(
            seg(if (sel) "✗ " else "  ", BAD, bold = sel),
            seg(pad(headline, (width - 8).coerceIn(20, 180)), if (sel) SELECTED else TEXT),
            seg(" ${Format.ago(p.startedAt)}".padStart(5), DIM),
        )
        val cause = p.rootCause
        if (cause != null) {
            val lines = cause.lines().filter { it.isNotBlank() }
            when {
                sel && expanded -> lines.forEach { line ->
                    val pointer = line.trimStart().startsWith("→")
                    wordWrap(line, width - 6).forEach { b.add(seg("    $it", if (pointer) WARN else TEXT)) }
                }
                sel -> lines.forEach { line ->
                    val pointer = line.trimStart().startsWith("→")
                    b.add(seg("    ${pad(line, width - 6)}", if (pointer) WARN else TEXT))
                }
                else -> b.add(seg("    ${pad(lines.first(), width - 8)}", DIM))
            }
        } else {
            if (sel) p.errorExcerpt.forEach { b.add(seg("    │ ${pad(it, width - 8)}", WARN)) }
            else p.errorExcerpt.lastOrNull()?.let { b.add(seg("    │ ${pad(it, width - 8)}", DIM)) }
        }
    }
    return b
}

// ---------------- MAINTENANCE ----------------

fun maintenanceLines(state: DashboardState, selection: Int, width: Int): LineBuf {
    val b = LineBuf()
    if (state.quality.isEmpty()) {
        b.add(seg(if (state.loading) "◴ Fetching SonarCloud metrics…" else "No SonarCloud projects configured.", if (state.loading) Theme.info else DIM))
        return b
    }
    b.add(seg("Code quality (SonarCloud)", Theme.accent, bold = true))
    state.quality.forEachIndexed { i, q ->
        val sel = i == selection
        if (sel) b.anchorHere()
        val gateColor = when (q.gateStatus) { "OK" -> OK; "ERROR" -> BAD; else -> DIM }
        val gateLabel = when (q.gateStatus) { "OK" -> "PASSED"; "ERROR" -> "FAILED"; else -> "—" }
        val head = mutableListOf(
            seg(if (sel) "● " else "  ", if (sel) Theme.accent else DIM),
            seg(pad(q.projectName, (width - 40).coerceIn(16, 60)), if (sel) SELECTED else TEXT),
            seg(" ", DIM),
            seg(gateLabel.padEnd(7), gateColor, bold = true),
        )
        if (q.coverage != null) head += seg("cov ${"%.1f".format(q.coverage)}%", coverageColor(q.coverageDelta))
        if (q.newCoverage != null) head += seg("  new ${"%.1f".format(q.newCoverage)}%", DIM)
        b.add(head)

        // Ratings + counts line.
        val line = mutableListOf(seg("      ", DIM))
        line += ratingSegs("R", q.reliabilityRating); line += ratingSegs("S", q.securityRating); line += ratingSegs("M", q.maintainabilityRating)
        line += countSeg("bugs", q.bugs, danger = true); line += countSeg("vulns", q.vulnerabilities, danger = true)
        line += countSeg("smells", q.codeSmells ?: q.newCodeSmells); line += countSeg("hotspots", q.securityHotspots)
        if (q.duplication != null) line += seg("dup ${"%.1f".format(q.duplication)}%  ", if (q.duplication > 3) WARN else DIM)
        if (q.ncloc != null) line += seg("LOC ${loc(q.ncloc)}", DIM)
        b.add(line)

        if (sel) q.failingConditions.forEach { c ->
            b.add(seg("      └ ", DIM), seg("${c.label}: ", TEXT), seg("${c.actual} ${c.op} ${c.threshold}", BAD))
        }
    }
    return b
}

// ---------------- GOALS ----------------

fun goalsLines(state: DashboardState, selection: Int, width: Int): LineBuf {
    val b = LineBuf()
    b.add(seg("Role progress", Theme.accent, bold = true))
    if (state.metrics.all { it.value == null }) {
        b.add(seg(if (state.loading) "◴ Loading metrics…" else "No metrics yet (check SonarCloud project + repos).", DIM))
    } else {
        val barW = (width / 3).coerceIn(14, 40)
        state.metrics.forEach { b.add(metricSegs(it, barW)) }
    }
    b.blank()
    b.add(seg("CI health (recent runs)", Theme.accent, bold = true))
    if (state.ci.isEmpty()) {
        b.add(seg(if (state.loading) "◴ Loading CI stats…" else "No repos configured for CI health.", DIM))
    } else {
        val barW = (width / 4).coerceIn(10, 30)
        state.ci.forEachIndexed { i, h ->
            val sel = i == selection
            if (sel) b.anchorHere()
            b.add(ciSegs(h, sel, barW))
            if (sel && h.topFailingWorkflow != null) b.add(seg("    └ most-failing workflow: ${h.topFailingWorkflow}", DIM))
        }
    }
    return b
}

private fun metricSegs(m: TrackedMetric, barW: Int): List<Seg> {
    val out = mutableListOf(
        seg("  ${m.label.padEnd(20)}", TEXT),
        seg((m.value?.let { fmtMetric(it, m.unit) } ?: "—").padEnd(12), SELECTED, bold = true),
    )
    if (m.goal != null && m.value != null) {
        out += seg(bar(m.value, m.goal, barW) + " ", barColor(m.value / m.goal))
        out += seg("→ ${fmtMetric(m.goal, m.unit)}", DIM)
    }
    if (m.delta != null && kotlin.math.abs(m.delta) >= 0.01) {
        val up = m.delta > 0
        out += seg("  ${if (up) "▲" else "▼"}${fmtMetric(kotlin.math.abs(m.delta), m.unit)}", if (up == m.higherIsBetter) OK else BAD)
    }
    return out
}

private fun ciSegs(h: CiHealth, sel: Boolean, barW: Int): List<Seg> {
    val color = when { h.failureRatePct >= 30 -> BAD; h.failureRatePct >= 10 -> WARN; else -> OK }
    return listOf(
        seg(if (sel) "● " else "  ", if (sel) Theme.accent else DIM),
        seg(pad(repoShort(h.repo), 28), if (sel) SELECTED else TEXT),
        seg("fail ", DIM),
        seg(("%.0f%%".format(h.failureRatePct)).padEnd(5), color, bold = true),
        seg(bar(h.failureRatePct, 100.0, barW) + " ", color),
        seg("${h.failed} failed / ${h.total} runs", DIM),
    )
}

// ---------------- shared helpers ----------------

private fun ratingSegs(label: String, r: Int?): List<Seg> =
    listOf(seg("$label:", DIM), seg("${ratingLetter(r)} ", ratingColor(r), bold = true))

private fun countSeg(label: String, value: Int?, danger: Boolean = false): Seg {
    val v = value ?: 0
    val color = when { v == 0 -> DIM; danger -> BAD; else -> WARN }
    return seg("$label $v  ", color)
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
    return "▕" + "▰".repeat(filled) + "▱".repeat((width - filled).coerceAtLeast(0)) + "▏"
}

private fun barColor(ratio: Double): Color = when { ratio >= 0.9 -> OK; ratio >= 0.6 -> WARN; else -> BAD }

private fun coverageColor(delta: Double?): Color = when {
    delta == null -> TEXT
    delta < -0.05 -> BAD
    delta > 0.05 -> OK
    else -> TEXT
}

/** Repo display name without owner or URL: "owner/Repo" or a URL → "Repo". */
private fun repoShort(repo: String): String =
    repo.removePrefix("https://").removePrefix("http://").removePrefix("github.com/")
        .trimEnd('/').substringAfterLast('/')

/** Pad-or-truncate to exactly [w] cells so columns line up. */
private fun pad(s: String, w: Int): String =
    if (s.length <= w) s.padEnd(w) else s.take((w - 1).coerceAtLeast(0)) + "…"

/** Word-wrap a line to [w] cells, breaking very long tokens; never returns empty. */
private fun wordWrap(text: String, w: Int): List<String> {
    if (w <= 0) return listOf(text)
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    for (word in text.trim().split(" ").filter { it.isNotEmpty() }) {
        if (sb.isEmpty()) sb.append(word)
        else if (sb.length + 1 + word.length <= w) sb.append(' ').append(word)
        else { out.add(sb.toString()); sb.setLength(0); sb.append(word) }
        while (sb.length > w) { out.add(sb.substring(0, w)); val rest = sb.substring(w); sb.setLength(0); sb.append(rest) }
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out.ifEmpty { listOf("") }
}
