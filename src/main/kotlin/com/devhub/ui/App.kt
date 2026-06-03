package com.devhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.devhub.core.DashboardState
import com.devhub.core.Poller
import com.devhub.core.Role
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Launches the live dashboard. Blocks (suspends) until the user quits with `q`.
 *
 * NOTE: the Mosaic composable API (imports under com.jakewharton.mosaic.*) is the
 * one part of devhub that is version-sensitive. If your resolved Mosaic version
 * renames an import, the fix is mechanical and isolated to this file. See README.
 */
suspend fun runDashboard(poller: Poller, pollSeconds: Int) = runMosaic {
    var state by remember { mutableStateOf(DashboardState()) }
    var activeTab by remember { mutableIntStateOf(0) }
    var selection by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    val tabs = Role.entries
    // Responsive width: fill the terminal instead of hard-truncating at fixed columns.
    val width = (LocalTerminalState.current.size.columns - 2).coerceIn(40, 200)

    // Single poll path: both the periodic timer and manual `r` bump refreshKey. The poll emits
    // a fast slice (PRs/Sonar/Goals) first, then a full slice (pipelines + AI) — so the UI paints
    // immediately instead of waiting on the slow AI step.
    LaunchedEffect(refreshKey) {
        state = state.copy(loading = true)
        runCatching { poller.pollProgressive().collect { state = it } }
            .onFailure { state = state.copy(loading = false, errors = listOf("poll failed: ${it.message}")) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(pollSeconds.coerceAtLeast(15).seconds)
            refreshKey++
        }
    }

    fun itemCount(): Int = when (tabs[activeTab]) {
        Role.DEV -> state.prs.size
        Role.PLATFORM -> state.pipelines.size
        Role.MAINTENANCE -> state.quality.size
        Role.GOALS -> state.ci.size
    }

    fun openSelected() {
        val url = when (tabs[activeTab]) {
            Role.DEV -> state.prs.getOrNull(selection)?.url
            Role.PLATFORM -> state.pipelines.getOrNull(selection)?.url
            Role.MAINTENANCE -> state.quality.getOrNull(selection)?.url
            Role.GOALS -> state.ci.getOrNull(selection)?.let { actionsUrl(it.repo) }
        }
        url?.let { OpenUrl.open(it) }
    }

    Column(
        modifier = Modifier.padding(1).onKeyEvent { event: KeyEvent ->
            when (event.key) {
                "q", "Q" -> exitProcess(0)
                "r", "R" -> { refreshKey++; true }
                "Tab", "ArrowRight", "l" -> { activeTab = (activeTab + 1) % tabs.size; selection = 0; true }
                "ArrowLeft", "h" -> { activeTab = (activeTab - 1 + tabs.size) % tabs.size; selection = 0; true }
                "ArrowDown", "j" -> { if (itemCount() > 0) selection = (selection + 1).coerceAtMost(itemCount() - 1); true }
                "ArrowUp", "k" -> { selection = (selection - 1).coerceAtLeast(0); true }
                "Enter", "o", "O" -> { openSelected(); true }
                "x", "X", " " -> { expanded = !expanded; true }
                else -> false
            }
        },
    ) {
        Header(state)
        TabBar(tabs, activeTab, state)
        Text("")
        val body = when (tabs[activeTab]) {
            Role.DEV -> devLines(state, selection, width, expanded)
            Role.PLATFORM -> platformLines(state, selection, width, expanded)
            Role.MAINTENANCE -> maintenanceLines(state, selection, width)
            Role.GOALS -> goalsLines(state, selection, width)
        }
        // Clip the body to the terminal height (chrome ≈ 8 rows) so the inline frame never
        // overflows — overflow is what duplicates the UI when content grows.
        val rows = LocalTerminalState.current.size.rows
        RenderLines(body, (rows - 8).coerceAtLeast(3))
        Text("")
        Footer()
        if (state.errors.isNotEmpty()) {
            Text("⚠ " + state.errors.joinToString("  ·  "), color = Theme.warn)
        }
    }
}

@Composable
private fun Header(state: DashboardState) {
    Row {
        Text("◆ devhub", color = Theme.highlight, textStyle = TextStyle.Bold)
        Text("  mission control", color = Theme.accent)
        Text(
            "   ${if (state.loading) "◴ syncing…" else "✓ synced ${Format.ago(state.lastRefresh)} ago"}",
            color = if (state.loading) Theme.info else Theme.dim,
        )
    }
}

@Composable
private fun TabBar(tabs: List<Role>, active: Int, state: DashboardState) {
    Row {
        tabs.forEachIndexed { i, role ->
            val count = when (role) {
                Role.DEV -> state.prs.size
                Role.PLATFORM -> state.pipelines.size
                Role.MAINTENANCE -> state.quality.count { it.gateStatus == "ERROR" }
                Role.GOALS -> state.metrics.count { it.goal != null && (it.value ?: 0.0) < it.goal }
            }
            val isActive = i == active
            val label = " ${role.title}${if (count > 0) " ($count)" else ""} "
            Text(
                if (isActive) "▸$label" else " $label",
                color = if (isActive) Color.Black else Color.White,
                background = if (isActive) Theme.accent else Color.Unspecified,
                textStyle = if (isActive) TextStyle.Bold else TextStyle.Empty,
            )
            Text(" ")
        }
    }
}

@Composable
private fun Footer() {
    Text(
        "↑/↓ move   ←/→ Tab switch   enter/o open   x expand   r refresh   q quit",
        color = Theme.dim,
    )
}

/** The Actions page URL for a repo given as "owner/repo" or a full GitHub URL. */
internal fun actionsUrl(repo: String): String {
    val base = if (repo.startsWith("http")) repo.trimEnd('/') else "https://github.com/$repo"
    return "$base/actions"
}
