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

import com.embabel.faction.domain.AuthorBaseline
import com.embabel.faction.domain.PairAnomaly
import com.embabel.faction.domain.ReviewEdge
import com.embabel.faction.domain.ReviewState
import com.embabel.faction.domain.ReviewerBaseline
import org.springframework.stereotype.Component

/**
 * Computes reviewer→author pair anomaly scores from raw review edges.
 *
 * Algorithm:
 * 1. Build reviewer baselines: their overall CHANGES_REQUESTED rate across all reviews.
 * 2. Build author baselines: their median days-to-merge as a code quality proxy.
 * 3. For each pair, compute anomaly = (actual rate − expected rate) − quality adjustment.
 *    A positive anomaly means the reviewer is harsher to this author than their baseline
 *    predicts, even accounting for the author's code quality.
 *
 * Pairs with fewer than [minReviews] reviews are excluded to avoid noise from rare interactions.
 */
@Component
class PairAnomalyScorer {

    fun score(edges: List<ReviewEdge>, minReviews: Int = 3): List<PairAnomaly> {
        if (edges.isEmpty()) return emptyList()

        val reviewerBaselines = buildReviewerBaselines(edges)
        val authorBaselines = buildAuthorBaselines(edges)
        val overallMedianDays = edges.mapNotNull { it.daysMergedAfterReview }.median() ?: 3.0

        return edges
            .groupBy { it.reviewer to it.author }
            .filter { (_, pairEdges) -> pairEdges.size >= minReviews }
            .map { (pair, pairEdges) ->
                val (reviewer, author) = pair
                val actual = pairEdges.changesRequestedRate()
                val expected = reviewerBaselines[reviewer]?.changesRequestedRate ?: actual
                val authorMedian = authorBaselines[author]?.medianDaysToMerge

                // Quality adjustment: if the author's PRs merge slowly (poor quality),
                // harsh reviews are more justified — reduce the anomaly.
                val qualityAdjustment = if (authorMedian != null && overallMedianDays > 0) {
                    ((authorMedian - overallMedianDays) / overallMedianDays).coerceIn(-0.3, 0.3)
                } else 0.0

                val anomaly = (actual - expected - qualityAdjustment).coerceIn(-1.0, 1.0)

                PairAnomaly(
                    reviewer = reviewer,
                    author = author,
                    reviewCount = pairEdges.size,
                    actualChangesRequestedRate = actual,
                    expectedChangesRequestedRate = expected,
                    qualityAdjustment = qualityAdjustment,
                    anomalyScore = anomaly,
                )
            }
    }

    fun topAnomalies(edges: List<ReviewEdge>, n: Int = 20, minReviews: Int = 2): List<PairAnomaly> =
        score(edges, minReviews)
            .sortedByDescending { it.anomalyScore }
            .take(n)

    private fun buildReviewerBaselines(edges: List<ReviewEdge>): Map<String, ReviewerBaseline> =
        edges.groupBy { it.reviewer }.mapValues { (login, reviews) ->
            ReviewerBaseline(
                login = login,
                changesRequestedRate = reviews.changesRequestedRate(),
                totalReviews = reviews.size,
            )
        }

    private fun buildAuthorBaselines(edges: List<ReviewEdge>): Map<String, AuthorBaseline> =
        edges.groupBy { it.author }.mapValues { (login, reviews) ->
            AuthorBaseline(
                login = login,
                medianDaysToMerge = reviews.mapNotNull { it.daysMergedAfterReview }.median(),
                totalPrs = reviews.size,
            )
        }
}

private fun List<ReviewEdge>.changesRequestedRate(): Double =
    if (isEmpty()) 0.0
    else count { it.state == ReviewState.CHANGES_REQUESTED }.toDouble() / size

private fun List<Double>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    return if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    else sorted[sorted.size / 2]
}
