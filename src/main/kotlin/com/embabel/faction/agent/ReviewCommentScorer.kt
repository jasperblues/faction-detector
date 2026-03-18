/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.faction.agent

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.common.ai.model.LlmOptions
import com.embabel.faction.domain.BlockingNature
import com.embabel.faction.domain.CommentSignificance
import com.embabel.faction.domain.PairAnomaly
import com.embabel.faction.domain.ScoredComment
import com.embabel.faction.domain.ScoredPair
import com.embabel.faction.domain.Sentiment
import com.embabel.faction.github.GitHubClient
import com.embabel.faction.github.GitHubReviewComment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * LLM-powered stage-2 scorer for flagged reviewer→author pairs.
 *
 * For each flagged pair, fetches the actual review comment text + diff hunks from GitHub
 * and asks the LLM to classify each comment as NITPICKY/FAIR/VERY_FAIR/ESSENTIAL
 * and BLOCKING/NON_BLOCKING.
 *
 * A high concentration of NITPICKY + NON_BLOCKING comments in a CHANGES_REQUESTED review
 * is a strong faction fingerprint — the reviewer is blocking work with trivial feedback.
 */
@Component
class ReviewCommentScorer(
    private val gitHubClient: GitHubClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Score the top anomalous pairs using LLM analysis of their review comments.
     * Fetches recent PRs from GitHub to find actual comment content for the pair.
     */
    fun scorePairs(
        owner: String,
        repo: String,
        flaggedPairs: List<PairAnomaly>,
        recentPrNumbers: List<Int>,
        context: OperationContext,
    ): List<ScoredPair> {
        return flaggedPairs.map { pair ->
            logger.info("Stage-2 scoring pair {}→{}", pair.reviewer, pair.author)
            val comments = collectCommentsForPair(owner, repo, pair.reviewer, pair.author, recentPrNumbers)
            val scored = comments.map { scoreComment(it, context) }
            ScoredPair(
                reviewer = pair.reviewer,
                author = pair.author,
                anomalyScore = pair.anomalyScore,
                scoredComments = scored,
            )
        }
    }

    private fun collectCommentsForPair(
        owner: String,
        repo: String,
        reviewer: String,
        author: String,
        prNumbers: List<Int>,
    ): List<GitHubReviewComment> =
        prNumbers
            .flatMap { gitHubClient.fetchReviewComments(owner, repo, it) }
            .filter { it.user.login == reviewer }
            .take(20)  // cap per pair to bound LLM cost

    private fun scoreComment(comment: GitHubReviewComment, context: OperationContext): ScoredComment {
        val result = context.ai().withLlm(LlmOptions.withModel(AnthropicModels.CLAUDE_HAIKU_4_5)).create<CommentScoreResult>(
            """
            You are evaluating a GitHub pull request review comment to determine if it represents
            fair technical feedback or nitpicking.

            Rate the comment on three dimensions:

            SIGNIFICANCE — pick exactly one:
            - NITPICKY: style preferences, trivial renaming, personal taste, minor formatting
            - FAIR: reasonable feedback that improves clarity or correctness
            - VERY_FAIR: important feedback that prevents bugs or significantly improves quality
            - ESSENTIAL: critical issue — security, data loss, correctness bug, breaking change

            BLOCKING — pick exactly one:
            - BLOCKING: the PR genuinely should not merge without addressing this
            - NON_BLOCKING: the PR could reasonably merge even if this is left for later

            SENTIMENT — a decimal in [-1.0, 1.0] reflecting the tone of the comment:
            - -1.0: strongly hostile — dismissive, contemptuous, or aggressive
            - -0.5: negative — frustrated, impatient, or unnecessarily harsh
            -  0.0: neutral — purely technical, no emotional tone
            - +0.5: positive — constructive, encouraging, collaborative
            - +1.0: strongly positive — enthusiastic, mentoring, or appreciative

            ## Review comment
            ${comment.body}

            ## Code being reviewed (diff hunk)
            ```
            ${comment.diffHunk.take(500)}
            ```
            """.trimIndent()
        )
        return ScoredComment(
            body = comment.body,
            diffHunk = comment.diffHunk,
            significance = result.significance,
            blocking = result.blocking,
            sentiment = Sentiment.of(result.sentiment),
        )
    }
}

/** Intermediate structured response from the LLM comment scorer. */
private data class CommentScoreResult(
    val significance: CommentSignificance,
    val blocking: BlockingNature,
    val sentiment: Double,
)
