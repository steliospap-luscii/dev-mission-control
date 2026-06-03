package com.devhub.core

import kotlinx.datetime.Instant

/** The holacracy roles the dashboard surfaces, one per tab. */
enum class Role(val title: String) {
    DEV("Dev"),
    PLATFORM("Platform"),
    MAINTENANCE("Maintenance"),
    GOALS("Goals"),
}

/** A PR that survived the filter rules and genuinely needs your review. */
data class ReviewPr(
    val repo: String,
    val number: Int,
    val title: String,
    val author: String,
    val url: String,
    val headSha: String,
    val ciGreen: Boolean,
    val claudeReviewed: Boolean,
    val updatedAt: Instant,
)

/** A failed CI/CD pipeline run with the extracted error so you see *what* broke. */
data class FailedPipeline(
    val repo: String,
    val runId: Long,
    val workflowName: String,
    val branch: String,
    val failedJob: String?,
    val errorExcerpt: List<String>,
    val url: String,
    val startedAt: Instant?,
)

/** A single failed quality-gate condition (e.g. "Security Rating  D > A"). */
data class GateCondition(
    val label: String,
    val actual: String,
    val op: String,        // human comparator, e.g. "worse than" / ">" / "<"
    val threshold: String,
)

/** SonarCloud quality snapshot for one project. */
data class QualityReport(
    val projectKey: String,
    val projectName: String,
    val gateStatus: String,          // "OK" | "ERROR" | "NONE"
    val failingConditions: List<GateCondition> = emptyList(),
    val coverage: Double?,           // best-effort; null when the token can't read measures
    val coverageDelta: Double?,      // vs last poll, computed locally
    val newCoverage: Double? = null, // coverage on new code
    val tests: Int? = null,          // unit test count
    val newCodeSmells: Int?,
    val securityHotspots: Int?,
    val bugs: Int?,
    val vulnerabilities: Int?,
    val url: String,
)

/** A KPI tracked over time on the Goals tab, optionally with a target to progress toward. */
data class TrackedMetric(
    val key: String,                 // stable id, used for delta tracking in the seen-state store
    val label: String,
    val value: Double?,
    val unit: String = "",           // "%", " tests", " branches", …
    val goal: Double? = null,        // when set, rendered as a progress bar toward this target
    val delta: Double? = null,       // change since last poll
    val higherIsBetter: Boolean = true,
)

/** GitHub Actions reliability over a recent window of runs, for one repo. */
data class CiHealth(
    val repo: String,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val cancelled: Int,
    val topFailingWorkflow: String? = null,
) {
    val failureRatePct: Double get() = if (total == 0) 0.0 else failed * 100.0 / total
    val successRatePct: Double get() = if (total == 0) 0.0 else succeeded * 100.0 / total
}

/** Everything the UI renders, recomputed each poll. */
data class DashboardState(
    val prs: List<ReviewPr> = emptyList(),
    val prsHidden: Int = 0,
    val pipelines: List<FailedPipeline> = emptyList(),
    val quality: List<QualityReport> = emptyList(),
    val metrics: List<TrackedMetric> = emptyList(),
    val ci: List<CiHealth> = emptyList(),
    val lastRefresh: Instant? = null,
    val errors: List<String> = emptyList(),   // per-source fetch failures, shown non-fatally
    val loading: Boolean = true,
)
