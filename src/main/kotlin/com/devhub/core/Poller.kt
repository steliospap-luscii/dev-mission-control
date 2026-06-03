package com.devhub.core

import com.devhub.ai.AnthropicClient
import com.devhub.config.Config
import com.devhub.github.GithubClient
import com.devhub.github.PrFilter
import com.devhub.sonar.SonarClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates every data source into a single [DashboardState] each poll,
 * computes local deltas (coverage), and fires desktop notifications for
 * genuinely new red signals.
 */
class Poller(
    private val config: Config,
    githubToken: String,
    sonarToken: String?,
    anthropicToken: String? = null,
) : AutoCloseable {

    private val github = GithubClient(githubToken)
    private val sonar = sonarToken?.let { SonarClient(config.sonar, it) }
    private val anthropic = anthropicToken
        ?.takeIf { config.pipelines.aiAnalysis }
        ?.let { AnthropicClient(it, config.pipelines.aiModel) }

    /** Emits a fresh state immediately, then re-polls every `config.pollSeconds`. */
    fun stream(): Flow<DashboardState> = flow {
        while (true) {
            emit(pollOnce())
            delay(config.pollSeconds.coerceAtLeast(15).seconds)
        }
    }

    suspend fun pollOnce(): DashboardState = coroutineScope {
        val errors = mutableListOf<String>()

        val prsDeferred = async {
            runCatching { github.fetchReviewQueue() }
                .onFailure { errors += "GitHub PRs: ${it.message}" }
                .getOrDefault(emptyList())
        }
        val pipelinesDeferred = async {
            if (config.pipelineRepos.isEmpty()) emptyList()
            else runCatching { github.fetchRecentFailures(config.pipelineRepos, config.pipelines.maxShown) }
                .onFailure { errors += "GitHub Actions: ${it.message}" }
                .getOrDefault(emptyList())
        }
        val qualityDeferred = async {
            if (sonar == null) emptyList()
            else runCatching { sonar.fetchAll() }
                .onFailure { errors += "SonarCloud: ${it.message}" }
                .getOrDefault(emptyList())
        }
        // Goals-tab inputs.
        val branchesDeferred = async {
            val repos = config.branchRepos()
            if (repos.isEmpty()) null
            else runCatching { repos.sumOf { github.fetchBranchCount(it) } }
                .onFailure { errors += "GitHub branches: ${it.message}" }
                .getOrNull()
        }
        val ciDeferred = async {
            config.ciRepos().mapNotNull { repo ->
                runCatching { github.fetchCiHealth(repo, config.progress.ciWindow) }
                    .onFailure { errors += "CI health ($repo): ${it.message}" }
                    .getOrNull()
            }
        }

        val prNodes = prsDeferred.await()
        val failures = pipelinesDeferred.await()
        val rawQuality = qualityDeferred.await()
        val branches = branchesDeferred.await()
        val ci = ciDeferred.await()

        val prev = SeenStore.load()
        val filter = PrFilter.apply(prNodes, config.prFilter, config.claudeBotLogin)

        // AI root-cause per failure, cached by run (Actions logs are immutable).
        val (pipelines, causes) = analyzeFailures(failures, prev, errors)

        // Coverage deltas from the seen-state store.
        val quality = rawQuality.map { q ->
            val last = prev.lastCoverage[q.projectKey]
            if (q.coverage != null && last != null) q.copy(coverageDelta = q.coverage - last) else q
        }
        val metrics = buildMetrics(branches, quality, prev)

        if (config.notifications) emitNotifications(prev, filter.visible, pipelines)

        SeenStore.save(
            prev.copy(
                initialized = true,
                seenPipelineRuns = pipelines.map { it.runId }.toSet(),
                seenPrUrls = filter.visible.map { it.url }.toSet(),
                lastCoverage = quality.mapNotNull { q -> q.coverage?.let { q.projectKey to it } }.toMap(),
                lastMetrics = metrics.mapNotNull { m -> m.value?.let { m.key to it } }.toMap(),
                pipelineCauses = causes,
            ),
        )

        DashboardState(
            prs = filter.visible,
            prsHidden = filter.hidden.size,
            hiddenPrs = filter.hidden.map { HiddenPr(it.first, it.second.map { r -> r.label }) },
            pipelines = pipelines,
            quality = quality,
            metrics = metrics,
            ci = ci,
            lastRefresh = Clock.System.now(),
            errors = errors,
            loading = false,
        )
    }

    /**
     * Runs Claude root-cause analysis on each failure (in parallel), reusing cached results
     * for runs we've already analyzed. Returns the enriched pipelines and the cache to persist.
     */
    private suspend fun analyzeFailures(
        failures: List<GithubClient.FailureWithLog>,
        prev: SeenState,
        errors: MutableList<String>,
    ): Pair<List<FailedPipeline>, Map<String, String>> = coroutineScope {
        val ai = anthropic ?: return@coroutineScope failures.map { it.pipeline } to emptyMap()
        val results = failures.map { fwl ->
            async {
                val key = "${fwl.pipeline.repo}#${fwl.pipeline.runId}"
                val cause = prev.pipelineCauses[key] ?: runCatching {
                    ai.rootCause(fwl.pipeline.workflowName, fwl.pipeline.failedJob, fwl.pipeline.branch, fwl.logTail)
                }.onFailure { errors += "AI analysis: ${it.message}" }.getOrNull()?.ifBlank { null }
                fwl.pipeline.copy(rootCause = cause) to cause?.let { key to it }
            }
        }.awaitAll()
        results.map { it.first } to results.mapNotNull { it.second }.toMap()
    }

    /** Builds the Goals-tab KPIs from the configured progress project, with deltas vs last poll. */
    private fun buildMetrics(branches: Int?, quality: List<QualityReport>, prev: SeenState): List<TrackedMetric> {
        val key = config.progressSonarProject()
        val q = quality.firstOrNull { it.projectKey == key } ?: quality.firstOrNull()
        val raw = listOf(
            TrackedMetric("branches", "Total branches", branches?.toDouble(), " branches", higherIsBetter = false),
            TrackedMetric("tests", "Unit tests", q?.tests?.toDouble(), " tests"),
            TrackedMetric("coverage", "Coverage", q?.coverage, "%", goal = config.progress.coverageGoalPct),
            TrackedMetric("new_coverage", "New-code coverage", q?.newCoverage, "%", goal = config.progress.newCoverageGoalPct),
        )
        return raw.map { m ->
            val last = prev.lastMetrics[m.key]
            if (m.value != null && last != null) m.copy(delta = m.value - last) else m
        }
    }

    private fun emitNotifications(prev: SeenState, prs: List<ReviewPr>, pipelines: List<FailedPipeline>) {
        if (!prev.initialized) return // first run: seed silently, don't flood on startup

        pipelines.filter { it.runId !in prev.seenPipelineRuns }.forEach { p ->
            Notifier.notify(
                title = "🔴 Pipeline failed — ${p.repo}",
                message = "${p.workflowName} on ${p.branch}: ${p.failedJob ?: "failed"}",
                openUrl = p.url,
            )
        }
        prs.filter { it.url !in prev.seenPrUrls }.forEach { pr ->
            Notifier.notify(
                title = "👀 PR ready for your review — ${pr.repo}",
                message = "#${pr.number} ${pr.title}",
                openUrl = pr.url,
            )
        }
    }

    override fun close() {
        github.close()
        sonar?.close()
        anthropic?.close()
    }
}
