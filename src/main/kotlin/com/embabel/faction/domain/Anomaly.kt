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
package com.embabel.faction.domain

/**
 * Sentiment score for a review comment, in the range [-1.0, 1.0].
 *
 * Reference points:
 * - `-1.0` — strongly hostile: dismissive, contemptuous, or aggressive tone
 * - `-0.5` — negative: frustrated, impatient, or unnecessarily harsh
 * - ` 0.0` — neutral: purely technical, no emotional tone
 * - `+0.5` — positive: constructive, encouraging, collaborative
 * - `+1.0` — strongly positive: enthusiastic, mentoring, appreciative
 *
 * Use [Sentiment.of] to construct from LLM output — it clamps out-of-range values
 * rather than throwing. The primary constructor enforces the range strictly.
 */
@JvmInline
value class Sentiment(val value: Double) {
    init {
        require(value in -1.0..1.0) { "Sentiment must be in [-1.0, 1.0], got $value" }
    }

    companion object {
        val STRONGLY_HOSTILE = Sentiment(-1.0)
        val NEGATIVE = Sentiment(-0.5)
        val NEUTRAL = Sentiment(0.0)
        val POSITIVE = Sentiment(0.5)
        val STRONGLY_POSITIVE = Sentiment(1.0)

        /** Clamps [raw] into [-1.0, 1.0] rather than throwing. Use for LLM output. */
        fun of(raw: Double) = Sentiment(raw.coerceIn(-1.0, 1.0))
    }
}

/**
 * Reviewer's overall harshness baseline across all their reviews.
 */
data class ReviewerBaseline(
    val login: String,
    val changesRequestedRate: Double,
    val totalReviews: Int,
)

/**
 * Author's code quality baseline: median days-to-merge across all their PRs.
 * Null if no merge data is available.
 */
data class AuthorBaseline(
    val login: String,
    val medianDaysToMerge: Double?,
    val totalPrs: Int,
)

/**
 * Anomaly score for a specific reviewer→author pair.
 *
 * [anomalyScore] > 0 means the reviewer is harsher to this author than their baseline predicts,
 * after adjusting for the author's code quality. High positive values are a faction signal.
 */
data class PairAnomaly(
    val reviewer: String,
    val author: String,
    val reviewCount: Int,
    val actualChangesRequestedRate: Double,
    val expectedChangesRequestedRate: Double,
    val qualityAdjustment: Double,
    val anomalyScore: Double,
)

enum class CommentSignificance { NITPICKY, FAIR, VERY_FAIR, ESSENTIAL }

enum class BlockingNature { BLOCKING, NON_BLOCKING }

/**
 * A single review comment with its fetched content and LLM-assigned scores.
 *
 * [sentiment] reflects the tone of the comment body, independent of its technical merit.
 * A NITPICKY comment can still be written with a neutral or positive tone;
 * hostile sentiment on top of nitpicking is a stronger faction signal.
 */
data class ScoredComment(
    val body: String,
    val diffHunk: String,
    val significance: CommentSignificance,
    val blocking: BlockingNature,
    val sentiment: Sentiment,
)

/**
 * A flagged reviewer→author pair with LLM-scored comments.
 *
 * [factionSignal] blends two signals equally:
 * - NITPICKY + NON_BLOCKING fraction (technical obstruction without substance)
 * - Average hostile sentiment fraction (tone-based antagonism)
 *
 * Range 0–1; higher values indicate stronger faction behaviour.
 */
data class ScoredPair(
    val reviewer: String,
    val author: String,
    val anomalyScore: Double,
    val scoredComments: List<ScoredComment>,
) {
    val factionSignal: Double get() {
        if (scoredComments.isEmpty()) return 0.0
        val nitpickyNonBlocking = scoredComments.count {
            it.significance == CommentSignificance.NITPICKY && it.blocking == BlockingNature.NON_BLOCKING
        }
        val nitpickyFraction = nitpickyNonBlocking.toDouble() / scoredComments.size
        // Negative sentiment only: neutral/positive tone contributes 0, not 0.5.
        // Clamp so positive sentiment doesn't reduce an already high nitpicky signal.
        val avgSentiment = scoredComments.map { it.sentiment.value }.average()
        val sentimentSignal = (-avgSentiment).coerceIn(0.0, 1.0)
        return nitpickyFraction * 0.6 + sentimentSignal * 0.4
    }
}
