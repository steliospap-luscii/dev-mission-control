package com.devhub.core

import com.devhub.ai.Analyzers
import com.devhub.ai.FailureInput
import com.devhub.config.Config
import com.devhub.github.GithubClient
import com.devhub.github.PrFilter
import com.devhub.sonar.SonarClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.datetime.Clock
import java.util.concurrent.CopyOnWriteArrayList

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
    private val analyzer = if (!config.pipelines.aiAnalysis) null
        else Analyzers.create(config.pipelines.aiBackend, config.pipelines.aiModel, anthropicToken)

    /** Single full poll (used by `probe`). */
    suspend fun pollOnce(): DashboardState = pollProgressive().last()

    /**
     * One poll, emitted in two slices so the UI paints fast:
     *   1. PRs + Sonar + Goals KPIs (everything cheap) — emitted immediately, `loading = true`.
     *   2. pipelines + batched AI root-cause — emitted when ready, `loading = false`.
     */
    fun pollProgressive(): Flow<DashboardState> = flow {
        coroutineScope {
            val errors = CopyOnWriteArrayList<String>()

            // Kick everything off concurrently; the slow part (pipelines + AI) is awaited last.
            val pipelinesDeferred = async {
                if (config.pipelineRepos.isEmpty()) emptyList()
                else runCatching { github.fetchRecentFailures(config.pipelineRepos, config.pipelines.maxShown) }
                    .onFailure { errors += "GitHub Actions: ${it.message}" }
                    .getOrDefault(emptyList())
            }
            val prsDeferred = async {
                runCatching { github.fetchReviewQueue() }
                    .onFailure { errors += "GitHub PRs: ${it.message}" }.getOrDefault(emptyList())
            }
            val qualityDeferred = async {
                if (sonar == null) emptyList()
                else runCatching { sonar.fetchAll() }
                    .onFailure { errors += "SonarCloud: ${it.message}" }.getOrDefault(emptyList())
            }
            val branchesDeferred = async {
                val repos = config.branchRepos()
                if (repos.isEmpty()) null
                else runCatching { repos.sumOf { github.fetchBranchCount(it) } }
                    .onFailure { errors += "GitHub branches: ${it.message}" }.getOrNull()
            }
            val ciDeferred = async {
                config.ciRepos().mapNotNull { repo ->
                    runCatching { github.fetchCiHealth(repo, config.progress.ciWindow) }
                        .onFailure { errors += "CI health ($repo): ${it.message}" }.getOrNull()
                }
            }

            val prev = SeenStore.load()

            // --- fast slice ---
            val filter = PrFilter.apply(prsDeferred.await(), config.prFilter, config.claudeBotLogin)
            val quality = qualityDeferred.await().map { q ->
                val last = prev.lastCoverage[q.projectKey]
                if (q.coverage != null && last != null) q.copy(coverageDelta = q.coverage - last) else q
            }
            val metrics = buildMetrics(branchesDeferred.await(), quality, prev)

            val base = DashboardState(
                prs = filter.visible,
                prsHidden = filter.hidden.size,
                hiddenPrs = filter.hidden.map { HiddenPr(it.first, it.second.map { r -> r.label }) },
                quality = quality,
                metrics = metrics,
                ci = ciDeferred.await(),
                lastRefresh = Clock.System.now(),
                errors = errors.toList(),
                loading = true, // pipelines + AI still pending
            )
            emit(base)

            // --- slow slice: pipelines + batched AI ---
            val (pipelines, causes) = analyzeFailures(pipelinesDeferred.await(), prev, errors)
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
            emit(base.copy(pipelines = pipelines, errors = errors.toList(), loading = false))
        }
    }

    /**
     * Batched Claude root-cause analysis: one call for ALL uncached failures, merged with the
     * per-run cache (Actions logs are immutable, so a run is analyzed at most once).
     */
    private suspend fun analyzeFailures(
        failures: List<GithubClient.FailureWithLog>,
        prev: SeenState,
        errors: MutableList<String>,
    ): Pair<List<FailedPipeline>, Map<String, String>> {
        fun key(p: FailedPipeline) = "${p.repo}#${p.runId}"
        val ai = analyzer ?: return failures.map { it.pipeline } to emptyMap()

        val uncached = failures.filter { prev.pipelineCauses[key(it.pipeline)] == null }
        val fresh: Map<Long, String> = if (uncached.isEmpty()) emptyMap() else runCatching {
            ai.rootCauses(
                uncached.map {
                    FailureInput(it.pipeline.runId, it.pipeline.workflowName, it.pipeline.failedJob, it.pipeline.branch, it.logTail)
                },
            )
        }.onFailure { errors += "AI analysis: ${it.message}" }.getOrDefault(emptyMap())

        val pipelines = failures.map { fwl ->
            fwl.pipeline.copy(rootCause = prev.pipelineCauses[key(fwl.pipeline)] ?: fresh[fwl.pipeline.runId])
        }
        return pipelines to pipelines.mapNotNull { p -> p.rootCause?.let { key(p) to it } }.toMap()
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
        analyzer?.close()
    }
}
