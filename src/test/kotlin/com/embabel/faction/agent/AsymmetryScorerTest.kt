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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class AsymmetryScorerTest {

    private val scorer = AsymmetryScorer()

    private fun edge(reviewer: String, author: String, at: Instant) = ReviewEdge(
        reviewer = reviewer,
        author = author,
        repo = "test/repo",
        prNumber = 1,
        timestamp = at,
        commentCount = 4,
        state = ReviewState.APPROVED,
        hoursToFirstReview = 2.0,
        daysMergedAfterReview = 1.0,
    )

    @Test
    fun `score with until bounds windows to historical range`() {
        val since = Instant.parse("2014-01-01T00:00:00Z")
        val until = Instant.parse("2014-04-01T00:00:00Z")
        val mid = Instant.parse("2014-02-15T00:00:00Z")

        // Enough edges to be "core" contributors (>= minReviews=3 each)
        val edges = (1..5).flatMap { _ ->
            listOf(edge("alice", "bob", mid), edge("bob", "alice", mid))
        }
        val scores = scorer.score(edges, since, until = until)

        assertTrue(scores.isNotEmpty(), "Should produce windows within the range")
        assertTrue(scores.all { it.windowEnd <= until },
            "No window should extend past until — got ${scores.map { it.windowEnd }}")
    }

    @Test
    fun `score without until defaults to now`() {
        val since = Instant.now().minus(10, ChronoUnit.DAYS)
        val mid = Instant.now().minus(5, ChronoUnit.DAYS)
        val edges = (1..5).flatMap { _ ->
            listOf(edge("alice", "bob", mid), edge("bob", "alice", mid))
        }
        val scores = scorer.score(edges, since)
        assertTrue(scores.isNotEmpty())
    }

    @Test
    fun `score on empty edge list returns empty`() {
        val since = Instant.now().minus(30, ChronoUnit.DAYS)
        assertTrue(scorer.score(emptyList(), since).isEmpty())
    }
}
