package com.devhub.github

import com.devhub.config.PrFilterConfig
import com.devhub.core.ReviewPr
import kotlinx.datetime.Instant

/** Why a PR was hidden, so the UI can show "N hidden (reasons)" instead of silently dropping. */
enum class HideReason(val label: String) {
    DRAFT("draft"),
    CI_NOT_GREEN("CI not green"),
    CLAUDE_NOT_REVIEWED("awaiting claude[bot]"),
}

data class FilterResult(
    val visible: List<ReviewPr>,
    val hidden: List<Pair<ReviewPr, List<HideReason>>>,
)

object PrFilter {

    /**
     * Apply the rule set. A PR is visible only when every enabled rule passes:
     *   1. you're a requested reviewer  (already guaranteed by the search query)
     *   2. claude[bot] has reviewed the *current head SHA*
     *   3. not a draft
     *   4. CI is green
     */
    fun apply(nodes: List<PrNode>, cfg: PrFilterConfig, claudeBotLogin: String): FilterResult {
        val visible = mutableListOf<ReviewPr>()
        val hidden = mutableListOf<Pair<ReviewPr, List<HideReason>>>()

        for (node in nodes) {
            if (node.typename != "PullRequest") continue
            val pr = node.toReviewPr(claudeBotLogin)
            val reasons = buildList {
                if (cfg.requireNotDraft && node.isDraft) add(HideReason.DRAFT)
                if (cfg.requireCiGreen && !pr.ciGreen) add(HideReason.CI_NOT_GREEN)
                if (cfg.requireClaudeBotReviewed && !pr.claudeReviewed) add(HideReason.CLAUDE_NOT_REVIEWED)
            }
            if (reasons.isEmpty()) visible.add(pr) else hidden.add(pr to reasons)
        }
        return FilterResult(
            visible = visible.sortedByDescending { it.updatedAt },
            hidden = hidden,
        )
    }

    /** A bot review counts only if its author is the Claude App *and* it targets the current head. */
    private fun PrNode.claudeReviewedHead(claudeBotLogin: String): Boolean {
        // GraphQL Bot logins have no "[bot]" suffix (that's a REST-only convention),
        // so normalize both sides — "claude" and "claude[bot]" should both match.
        val want = claudeBotLogin.removeSuffix("[bot]")
        return reviews.nodes.any { review ->
            val a = review.author
            a != null &&
                a.typename == "Bot" &&
                a.login.removeSuffix("[bot]").equals(want, ignoreCase = true) &&
                review.commit?.oid == headRefOid
        }
    }

    private fun PrNode.ciGreen(): Boolean =
        commits.nodes.firstOrNull()?.commit?.statusCheckRollup?.state == "SUCCESS"

    private fun PrNode.toReviewPr(claudeBotLogin: String) = ReviewPr(
        repo = repository?.nameWithOwner ?: "?",
        number = number,
        title = title,
        author = author?.login ?: "?",
        url = url,
        headSha = headRefOid,
        ciGreen = ciGreen(),
        claudeReviewed = claudeReviewedHead(claudeBotLogin),
        updatedAt = runCatching { Instant.parse(updatedAt) }.getOrElse { Instant.DISTANT_PAST },
    )
}
