package com.devhub.github

import com.devhub.config.PrFilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrFilterTest {

    private val cfg = PrFilterConfig()
    private val bot = "claude"

    private fun pr(
        number: Int,
        head: String = "sha-head",
        draft: Boolean = false,
        ci: String = "SUCCESS",
        reviews: List<ReviewNode> = listOf(claudeReviewOn("sha-head")),
    ) = PrNode(
        typename = "PullRequest",
        number = number,
        title = "PR $number",
        url = "https://github.com/o/r/pull/$number",
        isDraft = draft,
        updatedAt = "2026-06-01T10:00:00Z",
        headRefOid = head,
        author = Actor("alice", "User"),
        repository = Repository("o/r"),
        commits = CommitConnection(listOf(CommitNode(Commit(StatusCheckRollup(ci))))),
        reviews = ReviewConnection(reviews),
    )

    private fun claudeReviewOn(oid: String) =
        ReviewNode(state = "APPROVED", author = Actor(bot, "Bot"), commit = ReviewCommit(oid))

    @Test fun `passes when all rules satisfied`() {
        val r = PrFilter.apply(listOf(pr(1)), cfg, bot)
        assertEquals(1, r.visible.size)
        assertTrue(r.hidden.isEmpty())
        assertTrue(r.visible[0].ciGreen && r.visible[0].claudeReviewed)
    }

    @Test fun `hides draft`() {
        val r = PrFilter.apply(listOf(pr(1, draft = true)), cfg, bot)
        assertTrue(r.visible.isEmpty())
        assertEquals(listOf(HideReason.DRAFT), r.hidden.single().second)
    }

    @Test fun `hides when CI not green`() {
        val r = PrFilter.apply(listOf(pr(1, ci = "FAILURE")), cfg, bot)
        assertEquals(listOf(HideReason.CI_NOT_GREEN), r.hidden.single().second)
    }

    @Test fun `hides when claude reviewed an older SHA`() {
        val r = PrFilter.apply(
            listOf(pr(1, head = "sha-new", reviews = listOf(claudeReviewOn("sha-old")))),
            cfg, bot,
        )
        assertEquals(listOf(HideReason.CLAUDE_NOT_REVIEWED), r.hidden.single().second)
    }

    @Test fun `human review does not satisfy claude rule`() {
        val human = ReviewNode("APPROVED", Actor("bob", "User"), ReviewCommit("sha-head"))
        val r = PrFilter.apply(listOf(pr(1, reviews = listOf(human))), cfg, bot)
        assertTrue(r.visible.isEmpty())
        assertEquals(listOf(HideReason.CLAUDE_NOT_REVIEWED), r.hidden.single().second)
    }

    @Test fun `accumulates multiple hide reasons`() {
        val r = PrFilter.apply(
            listOf(pr(1, draft = true, ci = "FAILURE", reviews = emptyList())),
            cfg, bot,
        )
        val reasons = r.hidden.single().second
        assertTrue(HideReason.DRAFT in reasons)
        assertTrue(HideReason.CI_NOT_GREEN in reasons)
        assertTrue(HideReason.CLAUDE_NOT_REVIEWED in reasons)
    }
}
