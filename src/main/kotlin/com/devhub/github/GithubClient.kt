package com.devhub.github

import com.devhub.core.CiHealth
import com.devhub.core.FailedPipeline
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class GithubClient(private val token: String) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-GitHub-Api-Version", "2022-11-28")
            header(HttpHeaders.UserAgent, "devhub/0.1")
        }
        expectSuccess = true
    }

    /** All open PRs where you're a requested reviewer (unfiltered — caller applies rules). */
    suspend fun fetchReviewQueue(): List<PrNode> {
        val resp: GraphQLResponse<ReviewQueueData> = http.post(GRAPHQL) {
            contentType(ContentType.Application.Json)
            setBody(
                GraphQLRequest(
                    query = Queries.REVIEW_QUEUE,
                    variables = mapOf("q" to Queries.REVIEW_QUEUE_SEARCH),
                ),
            )
        }.body()
        if (resp.errors.isNotEmpty()) {
            error("GitHub GraphQL: " + resp.errors.joinToString("; ") { it.message })
        }
        return resp.data?.search?.nodes.orEmpty()
    }

    /** Total number of branches in a repo (open-ended; cheap count-only query). */
    suspend fun fetchBranchCount(repo: String): Int {
        val (owner, name) = parseRepo(repo)
        val resp: GraphQLResponse<BranchCountData> = http.post(GRAPHQL) {
            contentType(ContentType.Application.Json)
            setBody(GraphQLRequest(Queries.BRANCH_COUNT, mapOf("owner" to owner, "name" to name)))
        }.body()
        if (resp.errors.isNotEmpty()) error(resp.errors.joinToString("; ") { it.message })
        return resp.data?.repository?.refs?.totalCount ?: 0
    }

    /** Actions reliability over the most recent [window] runs for a repo. */
    suspend fun fetchCiHealth(repo: String, window: Int = 100): CiHealth {
        val (owner, name) = parseRepo(repo)
        val runs: WorkflowRunsResponse = http.get("$REST/repos/$owner/$name/actions/runs") {
            parameter("per_page", window.coerceIn(1, 100))
        }.body()
        val completed = runs.workflowRuns.filter { it.status == "completed" }
        val topFailing = completed.filter { it.conclusion == "failure" }
            .groupingBy { it.name ?: "workflow" }.eachCount()
            .maxByOrNull { it.value }?.key
        return CiHealth(
            repo = repo,
            total = completed.size,
            succeeded = completed.count { it.conclusion == "success" },
            failed = completed.count { it.conclusion == "failure" || it.conclusion == "startup_failure" },
            cancelled = completed.count { it.conclusion == "cancelled" },
            topFailingWorkflow = topFailing,
        )
    }

    /** Failed Actions runs across the watched repos, each with an extracted error excerpt. */
    suspend fun fetchFailedPipelines(repos: List<String>, perRepo: Int = 5): List<FailedPipeline> =
        repos.flatMap { repo -> runCatching { failedRunsFor(repo, perRepo) }.getOrElse { emptyList() } }
            .sortedByDescending { it.startedAt ?: Instant.DISTANT_PAST }

    private suspend fun failedRunsFor(repo: String, perRepo: Int): List<FailedPipeline> {
        val (owner, name) = parseRepo(repo)
        val runs: WorkflowRunsResponse = http.get("$REST/repos/$owner/$name/actions/runs") {
            parameter("status", "failure")
            parameter("per_page", perRepo)
        }.body()

        return runs.workflowRuns.map { run ->
            val (jobName, excerpt) = runCatching { firstFailedJobError(owner, name, run.id) }
                .getOrElse { null to listOf("(could not read job logs: ${it.message})") }
            FailedPipeline(
                repo = repo,
                runId = run.id,
                workflowName = run.name ?: run.displayTitle ?: "workflow",
                branch = run.headBranch ?: "?",
                failedJob = jobName,
                errorExcerpt = excerpt,
                url = run.htmlUrl,
                startedAt = run.runStartedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
    }

    private suspend fun firstFailedJobError(owner: String, name: String, runId: Long): Pair<String?, List<String>> {
        val jobs: JobsResponse = http.get("$REST/repos/$owner/$name/actions/runs/$runId/jobs").body()
        val failed = jobs.jobs.firstOrNull { it.conclusion == "failure" } ?: return null to emptyList()
        // /logs 302-redirects to a (pre-signed) plain-text log; Ktor follows it.
        val log = http.get("$REST/repos/$owner/$name/actions/jobs/${failed.id}/logs").bodyAsText()
        return failed.name to LogExtractor.errorLines(log)
    }

    override fun close() = http.close()

    companion object {
        private const val GRAPHQL = "https://api.github.com/graphql"
        private const val REST = "https://api.github.com"

        /** Accepts "owner/repo" or a full "https://github.com/owner/repo[...]" URL. */
        fun parseRepo(input: String): Pair<String, String> {
            val cleaned = input.trim()
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("github.com/")
                .removeSuffix(".git")
                .removeSuffix("/")
            val parts = cleaned.split("/").filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> parts[0] to parts[1]
                else -> cleaned to ""
            }
        }
    }
}
