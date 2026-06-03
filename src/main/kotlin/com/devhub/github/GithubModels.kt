package com.devhub.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- GraphQL request ----

@Serializable
data class GraphQLRequest(val query: String, val variables: Map<String, String> = emptyMap())

@Serializable
data class GraphQLResponse<T>(val data: T? = null, val errors: List<GraphQLError> = emptyList())

@Serializable
data class GraphQLError(val message: String)

// ---- Review-queue query shape ----

@Serializable
data class ReviewQueueData(val search: SearchResult, val rateLimit: RateLimit? = null)

@Serializable
data class RateLimit(val remaining: Int, val resetAt: String)

@Serializable
data class SearchResult(val nodes: List<PrNode> = emptyList())

@Serializable
data class PrNode(
    @SerialName("__typename") val typename: String = "",
    val number: Int = 0,
    val title: String = "",
    val url: String = "",
    val isDraft: Boolean = false,
    val updatedAt: String = "",
    val headRefOid: String = "",
    val isInMergeQueue: Boolean = false,
    val autoMergeRequest: AutoMergeRequest? = null,
    val author: Actor? = null,
    val repository: Repository? = null,
    val commits: CommitConnection = CommitConnection(),
    val reviews: ReviewConnection = ReviewConnection(),
)

@Serializable
data class Actor(val login: String = "", @SerialName("__typename") val typename: String = "")

@Serializable
data class AutoMergeRequest(val enabledAt: String? = null)

@Serializable
data class Repository(val nameWithOwner: String = "")

@Serializable
data class CommitConnection(val nodes: List<CommitNode> = emptyList())

@Serializable
data class CommitNode(val commit: Commit = Commit())

@Serializable
data class Commit(val statusCheckRollup: StatusCheckRollup? = null)

@Serializable
data class StatusCheckRollup(val state: String = "") // SUCCESS | FAILURE | PENDING | ERROR | EXPECTED

@Serializable
data class ReviewConnection(val nodes: List<ReviewNode> = emptyList())

@Serializable
data class ReviewNode(
    val state: String = "",          // APPROVED | CHANGES_REQUESTED | COMMENTED | ...
    val author: Actor? = null,
    val commit: ReviewCommit? = null,
)

@Serializable
data class ReviewCommit(val oid: String = "")

// ---- Branch-count query ----

@Serializable
data class BranchCountData(val repository: RepoRefs? = null)

@Serializable
data class RepoRefs(val refs: RefConnection = RefConnection())

@Serializable
data class RefConnection(val totalCount: Int = 0)

// ---- Actions REST shapes ----

@Serializable
data class WorkflowRunsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("workflow_runs") val workflowRuns: List<WorkflowRun> = emptyList(),
)

@Serializable
data class WorkflowRun(
    val id: Long = 0,
    val name: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    val status: String? = null,        // queued | in_progress | completed
    val conclusion: String? = null,    // success | failure | cancelled | ...
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("run_started_at") val runStartedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class JobsResponse(val jobs: List<Job> = emptyList())

@Serializable
data class Job(
    val id: Long = 0,
    val name: String = "",
    val conclusion: String? = null,   // success | failure | ...
    @SerialName("html_url") val htmlUrl: String? = null,
)
