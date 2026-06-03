package com.devhub

import com.devhub.config.Config
import com.devhub.config.ConfigStore
import com.devhub.config.Keychain
import com.devhub.config.SonarConfig
import com.devhub.core.Poller
import com.devhub.ui.runDashboard
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private val t = Terminal()

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "dashboard", "run" -> dashboard()
        "auth" -> auth()
        "config" -> config()
        "doctor" -> doctor()
        "probe" -> probe()
        "-h", "--help", "help" -> help()
        else -> { t.println(brightRed("Unknown command: ${args[0]}")); help(); exitProcess(1) }
    }
}

private fun help() {
    t.println(
        """
        ${bold("devhub")} — single source of truth for your holacracy roles

        ${bold("Usage:")} devhub [command]

          ${brightCyan("(none)")}    launch the live dashboard (Dev / Platform / Maintenance)
          ${brightCyan("auth")}      store your GitHub + SonarCloud tokens in the macOS Keychain
          ${brightCyan("config")}    set up login, watched repos, SonarCloud projects, poll interval
          ${brightCyan("doctor")}    verify tokens + connectivity to GitHub and SonarCloud
          ${brightCyan("probe")}     run one poll and print a text snapshot (no TUI / no TTY needed)
          ${brightCyan("help")}      show this message

        Config lives in ${gray(ConfigStore.configPath.toString())}
        Tokens live in the macOS Keychain (service "devhub"), never in files.
        """.trimIndent(),
    )
}

// ---------------- dashboard ----------------

private fun dashboard() {
    if (!ConfigStore.exists()) {
        t.println(brightYellow("No config yet. Run ${bold("devhub config")} then ${bold("devhub auth")}."))
        return
    }
    val cfg = ConfigStore.load()
    val gh = Keychain.get(Keychain.GITHUB_TOKEN)
    if (gh == null) {
        t.println(brightYellow("No GitHub token. Run ${bold("devhub auth")} first."))
        return
    }
    cfg.validationIssues().forEach { t.println(gray("note: $it")) }

    val poller = Poller(cfg, gh, Keychain.get(Keychain.SONAR_TOKEN), anthropicToken())
    try {
        runBlocking { runDashboard(poller, cfg.pollSeconds) }
    } finally {
        poller.close()
    }
}

/** Anthropic key from the Keychain, falling back to the ANTHROPIC_API_KEY env var. */
private fun anthropicToken(): String? =
    Keychain.get(Keychain.ANTHROPIC_TOKEN) ?: System.getenv("ANTHROPIC_API_KEY")

// ---------------- auth ----------------

private fun auth() {
    t.println(bold("Store API tokens (kept in the macOS Keychain, not on disk)"))
    t.println(gray("GitHub: a fine-grained PAT with read access to Pull requests, Actions, Contents."))
    val gh = readSecret("GitHub token (blank to keep existing): ")
    if (gh.isNotBlank()) { Keychain.set(Keychain.GITHUB_TOKEN, gh); t.println(brightGreen("✓ GitHub token saved.")) }

    t.println(gray("SonarCloud: a user token (Account ▸ Security). Optional — leave blank to skip."))
    val sonar = readSecret("SonarCloud token (blank to skip): ")
    if (sonar.isNotBlank()) { Keychain.set(Keychain.SONAR_TOKEN, sonar); t.println(brightGreen("✓ SonarCloud token saved.")) }

    t.println(gray("Anthropic: an API key (console.anthropic.com) for AI root-cause analysis of pipeline failures. Optional."))
    val anthropic = readSecret("Anthropic API key (blank to skip): ")
    if (anthropic.isNotBlank()) { Keychain.set(Keychain.ANTHROPIC_TOKEN, anthropic); t.println(brightGreen("✓ Anthropic key saved.")) }

    t.println("Run ${bold("devhub doctor")} to verify connectivity.")
}

private fun readSecret(prompt: String): String {
    val console = System.console()
    return if (console != null) {
        print(prompt); System.out.flush()
        String(console.readPassword()).trim()
    } else {
        // No TTY (e.g. piped) — fall back to visible input.
        t.print(prompt); readlnOrNull()?.trim().orEmpty()
    }
}

// ---------------- config ----------------

private fun config() {
    val existing = if (ConfigStore.exists()) ConfigStore.load() else Config()
    t.println(bold("devhub configuration") + gray("  (press enter to keep the shown default)"))

    val login = ask("Your GitHub login", existing.githubLogin)
    val repos = ask(
        "Repos to watch for pipeline failures (comma-separated owner/repo)",
        existing.pipelineRepos.joinToString(","),
    ).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val botLogin = ask("Claude review bot login", existing.claudeBotLogin.ifBlank { "claude" })

    val sonarOrg = ask("SonarCloud organization", existing.sonar.organization)
    val sonarBase = ask("SonarCloud base URL", existing.sonar.baseUrl.ifBlank { "https://sonarcloud.io" })
    val sonarProjects = ask(
        "SonarCloud project keys (comma-separated)",
        existing.sonar.projectKeys.joinToString(","),
    ).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val covGoal = ask("Coverage goal % (Goals tab)", existing.progress.coverageGoalPct.toInt().toString())
        .toDoubleOrNull() ?: existing.progress.coverageGoalPct
    val newCovGoal = ask("New-code coverage goal %", existing.progress.newCoverageGoalPct.toInt().toString())
        .toDoubleOrNull() ?: existing.progress.newCoverageGoalPct

    val maxPipes = ask("Max pipeline failures to show", existing.pipelines.maxShown.toString())
        .toIntOrNull() ?: existing.pipelines.maxShown
    val aiOn = ask("AI root-cause analysis of failures? (y/n)", if (existing.pipelines.aiAnalysis) "y" else "n")
        .lowercase().startsWith("y")

    val poll = ask("Poll interval (seconds)", existing.pollSeconds.toString()).toIntOrNull() ?: existing.pollSeconds
    val notify = ask("Desktop notifications? (y/n)", if (existing.notifications) "y" else "n")
        .lowercase().startsWith("y")

    val cfg = existing.copy(
        githubLogin = login,
        pipelineRepos = repos,
        claudeBotLogin = botLogin,
        sonar = SonarConfig(baseUrl = sonarBase, organization = sonarOrg, projectKeys = sonarProjects),
        progress = existing.progress.copy(coverageGoalPct = covGoal, newCoverageGoalPct = newCovGoal),
        pipelines = existing.pipelines.copy(maxShown = maxPipes, aiAnalysis = aiOn),
        pollSeconds = poll,
        notifications = notify,
    )
    ConfigStore.save(cfg)
    t.println(brightGreen("✓ Saved to ${ConfigStore.configPath}"))
    cfg.validationIssues().forEach { t.println(gray("note: $it")) }
}

private fun ask(label: String, default: String): String {
    val shown = if (default.isNotBlank()) " ${gray("[$default]")}" else ""
    t.print("$label$shown: ")
    val input = readlnOrNull()?.trim().orEmpty()
    return input.ifBlank { default }
}

// ---------------- probe (headless snapshot) ----------------

private fun probe() {
    if (!ConfigStore.exists()) { t.println(brightYellow("No config. Run `devhub config` then `devhub auth`.")); return }
    val cfg = ConfigStore.load()
    val gh = Keychain.get(Keychain.GITHUB_TOKEN) ?: run {
        t.println(brightYellow("No GitHub token. Run `devhub auth`.")); return
    }
    val state = Poller(cfg, gh, Keychain.get(Keychain.SONAR_TOKEN), anthropicToken())
        .use { runBlocking { it.pollOnce() } }

    t.println(bold("\nDEV — ${state.prs.size} PR(s) need your review") + gray("  (${state.prsHidden} hidden by filter)"))
    state.prs.forEach { t.println("  ${brightGreen("●")} #${it.number} ${it.title}  ${gray(it.repo)}") }

    t.println(bold("\nPLATFORM — ${state.pipelines.size} failing pipeline(s)"))
    state.pipelines.forEach { p ->
        val repo = p.repo.substringAfterLast('/')
        t.println("  ${brightRed("✗")} $repo · ${p.workflowName} · ${p.branch}" + gray("  (${p.failedJob ?: "?"})"))
        val cause = p.rootCause
        if (cause != null) cause.lines().filter { it.isNotBlank() }.forEach { t.println("      $it") }
        else p.errorExcerpt.lastOrNull()?.let { t.println(gray("      │ $it")) }
    }

    t.println(bold("\nMAINTENANCE — ${state.quality.size} project(s)"))
    state.quality.forEach { q ->
        val gate = if (q.gateStatus == "OK") brightGreen("PASSED") else if (q.gateStatus == "ERROR") brightRed("FAILED") else gray("—")
        t.println("  ${q.projectName}  gate $gate" + (q.coverage?.let { gray("  cov ${"%.1f".format(it)}%") } ?: "") + (q.newCoverage?.let { gray("  new ${"%.1f".format(it)}%") } ?: ""))
        fun letter(r: Int?) = when (r) { 1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; 5 -> "E"; else -> "—" }
        val extra = listOfNotNull(q.duplication?.let { "dup ${"%.1f".format(it)}%" }, q.ncloc?.let { "LOC $it" }).joinToString(" · ")
        t.println(gray("      R:${letter(q.reliabilityRating)} S:${letter(q.securityRating)} M:${letter(q.maintainabilityRating)} · bugs ${q.bugs ?: 0} · vulns ${q.vulnerabilities ?: 0} · smells ${q.codeSmells ?: q.newCodeSmells ?: 0} · hotspots ${q.securityHotspots ?: 0}" + if (extra.isNotBlank()) " · $extra" else ""))
        q.failingConditions.forEach { c -> t.println("      ${brightRed("└")} ${c.label}: ${c.actual} ${c.op} ${c.threshold}") }
    }

    t.println(bold("\nGOALS — role progress"))
    state.metrics.forEach { m ->
        val v = m.value?.let { if (m.unit == "%") "%.1f%%".format(it) else "${it.toInt()}${m.unit}" } ?: "—"
        val goal = m.goal?.let { gray("  → goal ${if (m.unit == "%") "%.0f%%".format(it) else it.toInt().toString()}") } ?: ""
        val delta = m.delta?.takeIf { kotlin.math.abs(it) >= 0.01 }
            ?.let { (if (it > 0) "  ▲" else "  ▼") + "%.1f".format(kotlin.math.abs(it)) } ?: ""
        t.println("  ${m.label}: $v$goal$delta")
    }
    t.println(bold("CI health (recent runs):"))
    state.ci.forEach { h ->
        val top = h.topFailingWorkflow?.let { gray("  (most-failing: $it)") } ?: ""
        t.println("  ${h.repo.substringAfterLast('/')}: ${"%.0f".format(h.failureRatePct)}% fail · ${h.failed}/${h.total} runs$top")
    }

    if (state.errors.isNotEmpty()) state.errors.forEach { t.println(brightYellow("⚠ $it")) }
    t.println("")
}

// ---------------- doctor ----------------

private fun doctor() {
    val cfg = if (ConfigStore.exists()) ConfigStore.load() else Config()
    t.println(bold("devhub doctor"))

    val gh = Keychain.get(Keychain.GITHUB_TOKEN)
    if (gh == null) {
        t.println(brightRed("✗ GitHub token missing — run `devhub auth`."))
    } else {
        runBlocking {
            runCatching {
                com.devhub.github.GithubClient(gh).use { client -> client.fetchReviewQueue().size }
            }.onSuccess { t.println(brightGreen("✓ GitHub OK — $it PR(s) in your review queue (pre-filter).")) }
                .onFailure { t.println(brightRed("✗ GitHub failed: ${it.message}")) }
        }
    }

    when {
        anthropicToken() == null -> t.println(gray("• Anthropic key not set — pipeline AI root-cause off (regex excerpt used)."))
        !cfg.pipelines.aiAnalysis -> t.println(gray("• AI analysis disabled in config."))
        else -> t.println(brightGreen("✓ AI root-cause enabled (model ${cfg.pipelines.aiModel})."))
    }

    val sonar = Keychain.get(Keychain.SONAR_TOKEN)
    when {
        sonar == null -> t.println(gray("• SonarCloud token not set (Maintenance panel disabled)."))
        cfg.sonar.projectKeys.isEmpty() -> t.println(gray("• No SonarCloud projects configured."))
        else -> runBlocking {
            runCatching { com.devhub.sonar.SonarClient(cfg.sonar, sonar).use { it.fetchAll() } }
                .onSuccess { reports ->
                    val ok = reports.count { it.gateStatus != "NONE" }
                    t.println(brightGreen("✓ SonarCloud OK — $ok/${reports.size} project(s) returned a quality gate."))
                }
                .onFailure { t.println(brightRed("✗ SonarCloud failed: ${it.message}")) }
        }
    }
}
