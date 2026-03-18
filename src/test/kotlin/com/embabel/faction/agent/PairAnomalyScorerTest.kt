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

import com.embabel.faction.domain.ReviewEdge
import com.embabel.faction.domain.ReviewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PairAnomalyScorerTest {

    private val scorer = PairAnomalyScorer()
    private val now = Instant.now()

    private fun edge(
        reviewer: String,
        author: String,
        state: ReviewState,
        daysMergedAfterReview: Double? = 1.0,
    ) = ReviewEdge(
        reviewer = reviewer,
        author = author,
        repo = "test/repo",
        prNumber = null,
        timestamp = now,
        commentCount = 1,
        state = state,
        hoursToFirstReview = 2.0,
        daysMergedAfterReview = daysMergedAfterReview,
    )

    @Test
    fun `uniform reviewer has low anomaly for all pairs`() {
        // alice reviews bob, carol, dan all at the same CHANGES_REQUESTED rate
        val edges = listOf(
            edge("alice", "bob", ReviewState.CHANGES_REQUESTED),
            edge("alice", "bob", ReviewState.CHANGES_REQUESTED),
            edge("alice", "carol", ReviewState.CHANGES_REQUESTED),
            edge("alice", "carol", ReviewState.CHANGES_REQUESTED),
            edge("alice", "dan", ReviewState.CHANGES_REQUESTED),
            edge("alice", "dan", ReviewState.CHANGES_REQUESTED),
        )
        val anomalies = scorer.score(edges)
        // All pairs should have near-zero anomaly — alice is equally harsh to everyone
        anomalies.forEach { a ->
            assertTrue(a.anomalyScore < 0.15, "Expected low anomaly for ${a.reviewer}→${a.author}, got ${a.anomalyScore}")
        }
    }

    @Test
    fun `reviewer harsh only to one author scores high anomaly for that pair`() {
        // igor approves most people but always changes_requested on rodney
        val edges = listOf(
            edge("igor", "alice", ReviewState.APPROVED),
            edge("igor", "alice", ReviewState.APPROVED),
            edge("igor", "alice", ReviewState.APPROVED),
            edge("igor", "bob", ReviewState.APPROVED),
            edge("igor", "bob", ReviewState.APPROVED),
            edge("igor", "bob", ReviewState.APPROVED),
            edge("igor", "rodney", ReviewState.CHANGES_REQUESTED),
            edge("igor", "rodney", ReviewState.CHANGES_REQUESTED),
            edge("igor", "rodney", ReviewState.CHANGES_REQUESTED),
        )
        val anomalies = scorer.score(edges)
        val rodneysAnomaly = anomalies.single { it.reviewer == "igor" && it.author == "rodney" }
        val alicesAnomaly = anomalies.single { it.reviewer == "igor" && it.author == "alice" }

        assertTrue(
            rodneysAnomaly.anomalyScore > alicesAnomaly.anomalyScore,
            "Rodney should have higher anomaly than alice: rodney=${rodneysAnomaly.anomalyScore}, alice=${alicesAnomaly.anomalyScore}"
        )
        assertTrue(
            rodneysAnomaly.anomalyScore > 0.4,
            "Expected high anomaly for igor→rodney, got ${rodneysAnomaly.anomalyScore}"
        )
    }

    @Test
    fun `poor code quality reduces anomaly score`() {
        // same reviewer, same harshness, but one author's PRs take much longer to merge
        val fastMergeEdges = listOf(
            edge("arjen", "goodcoder", ReviewState.CHANGES_REQUESTED, daysMergedAfterReview = 1.0),
            edge("arjen", "goodcoder", ReviewState.CHANGES_REQUESTED, daysMergedAfterReview = 1.0),
            edge("arjen", "goodcoder", ReviewState.CHANGES_REQUESTED, daysMergedAfterReview = 1.0),
            edge("arjen", "ok1", ReviewState.APPROVED, daysMergedAfterReview = 1.0),
            edge("arjen", "ok1", ReviewState.APPROVED, daysMergedAfterReview = 1.0),
            edge("arjen", "ok1", ReviewState.APPROVED, daysMergedAfterReview = 1.0),
        )
        val slowMergeEdges = listOf(
            edge("arjen", "badcoder", ReviewState.CHANGES_REQUESTED, daysMergedAfterReview = 30.0),
            edge("arjen", "badcoder", ReviewState.CHANGES_REQUESTED, daysMergedAfterReview = 30.0),
            edge("arjen", "badcoder", ReviewState.CHANGES_REQUESTED, daysMergedAfterReview = 30.0),
            edge("arjen", "ok1", ReviewState.APPROVED, daysMergedAfterReview = 1.0),
            edge("arjen", "ok1", ReviewState.APPROVED, daysMergedAfterReview = 1.0),
            edge("arjen", "ok1", ReviewState.APPROVED, daysMergedAfterReview = 1.0),
        )
        val fastAnomalies = scorer.score(fastMergeEdges)
        val slowAnomalies = scorer.score(slowMergeEdges)

        val fastAnomaly = fastAnomalies.single { it.author == "goodcoder" }.anomalyScore
        val slowAnomaly = slowAnomalies.single { it.author == "badcoder" }.anomalyScore

        assertTrue(
            fastAnomaly > slowAnomaly,
            "Fast-merging author (likely good code) should yield higher anomaly than slow-merging: fast=$fastAnomaly, slow=$slowAnomaly"
        )
    }

    @Test
    fun `excludes pairs with fewer than minimum reviews`() {
        val edges = listOf(
            edge("alice", "bob", ReviewState.CHANGES_REQUESTED),  // only 1 review — too few
            edge("alice", "carol", ReviewState.APPROVED),
            edge("alice", "carol", ReviewState.APPROVED),
            edge("alice", "carol", ReviewState.APPROVED),
        )
        val anomalies = scorer.score(edges, minReviews = 3)
        assertTrue(anomalies.none { it.author == "bob" }, "bob should be excluded with only 1 review")
        assertTrue(anomalies.any { it.author == "carol" }, "carol should be included with 3 reviews")
    }

    @Test
    fun `top returns at most n results sorted by anomaly descending`() {
        val edges = listOf(
            edge("igor", "alice", ReviewState.APPROVED),
            edge("igor", "alice", ReviewState.APPROVED),
            edge("igor", "alice", ReviewState.APPROVED),
            edge("igor", "bob", ReviewState.APPROVED),
            edge("igor", "bob", ReviewState.APPROVED),
            edge("igor", "bob", ReviewState.APPROVED),
            edge("igor", "rodney", ReviewState.CHANGES_REQUESTED),
            edge("igor", "rodney", ReviewState.CHANGES_REQUESTED),
            edge("igor", "rodney", ReviewState.CHANGES_REQUESTED),
        )
        val top = scorer.topAnomalies(edges, n = 2)
        assertEquals(2, top.size)
        assertTrue(top.first().anomalyScore >= top.last().anomalyScore, "Should be sorted descending")
    }
}
