package com.devhub.github

/**
 * A single GraphQL query returns every open PR where you're a requested reviewer,
 * across all repos, with everything the filter rules need:
 *   - reviewRequests  → are you (or a team you're in) requested?  (search already scoped to @me)
 *   - isDraft         → rule: not a draft
 *   - statusCheckRollup.state → rule: CI green
 *   - reviews + headRefOid    → rule: claude[bot] reviewed the *current* head SHA
 */
object Queries {
    val REVIEW_QUEUE = """
        query ReviewQueue(${'$'}q: String!) {
          search(query: ${'$'}q, type: ISSUE, first: 50) {
            nodes {
              __typename
              ... on PullRequest {
                number
                title
                url
                isDraft
                updatedAt
                headRefOid
                author { login }
                repository { nameWithOwner }
                commits(last: 1) {
                  nodes { commit { statusCheckRollup { state } } }
                }
                reviews(last: 30) {
                  nodes {
                    state
                    author { login __typename }
                    commit { oid }
                  }
                }
              }
            }
          }
          rateLimit { remaining resetAt }
        }
    """.trimIndent()

    /** Search filter: open PRs where the viewer is a requested reviewer. */
    const val REVIEW_QUEUE_SEARCH = "is:open is:pr review-requested:@me archived:false"

    /** Total branch count for a repo (cheap — just the count, no ref nodes). */
    val BRANCH_COUNT = """
        query BranchCount(${'$'}owner: String!, ${'$'}name: String!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            refs(refPrefix: "refs/heads/") { totalCount }
          }
        }
    """.trimIndent()
}
