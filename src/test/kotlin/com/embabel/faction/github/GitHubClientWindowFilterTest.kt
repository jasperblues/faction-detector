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
package com.embabel.faction.github

import com.embabel.faction.domain.ReviewEdge
import com.embabel.faction.domain.ReviewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class GitHubClientWindowFilterTest {

    private val since = LocalDate.of(2022, 4, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
    private val until = LocalDate.of(2022, 6, 25).atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun edge(submittedAt: String) = ReviewEdge(
        reviewer = "alice",
        author = "bob",
        repo = "test/repo",
        prNumber = 1,
        timestamp = Instant.parse("${submittedAt}T00:00:00Z"),
        commentCount = 1,
        state = ReviewState.APPROVED,
        hoursToFirstReview = 2.0,
        daysMergedAfterReview = 1.0,
    )

    @Test
    fun `edges exactly at since are included`() {
        val edges = listOf(edge("2022-04-01"))
        assertEquals(1, edges.filterEdgesByWindow(since, until).size)
    }

    @Test
    fun `edges before since are excluded`() {
        val edges = listOf(edge("2022-03-31"))
        assertTrue(edges.filterEdgesByWindow(since, until).isEmpty())
    }

    @Test
    fun `edges before until are included`() {
        val edges = listOf(edge("2022-06-24"))
        assertEquals(1, edges.filterEdgesByWindow(since, until).size)
    }

    @Test
    fun `edges exactly at until are excluded`() {
        // until is exclusive — matches isBefore(until) contract
        val edges = listOf(edge("2022-06-25"))
        assertTrue(edges.filterEdgesByWindow(since, until).isEmpty())
    }

    @Test
    fun `edges after until are excluded — the cache contamination scenario`() {
        // This is the bug: a PR created June 20 with a review submitted June 27
        // would be included in --until 2022-06-25 data via the per-PR review cache.
        val edges = listOf(edge("2022-06-27"))
        assertTrue(edges.filterEdgesByWindow(since, until).isEmpty())
    }

    @Test
    fun `edges well after until are excluded`() {
        val edges = listOf(edge("2022-07-15"))
        assertTrue(edges.filterEdgesByWindow(since, until).isEmpty())
    }

    @Test
    fun `null until keeps all edges after since`() {
        val edges = listOf(edge("2022-06-27"), edge("2025-01-01"))
        assertEquals(2, edges.filterEdgesByWindow(since, null).size)
    }

    @Test
    fun `mixed bag — only in-window edges survive`() {
        val edges = listOf(
            edge("2022-03-31"), // before since
            edge("2022-04-15"), // in window
            edge("2022-06-10"), // in window
            edge("2022-06-24"), // in window (last valid day)
            edge("2022-06-25"), // at until boundary — excluded
            edge("2022-06-27"), // after until — the cache contamination scenario
            edge("2022-07-15"), // well after until
        )
        val result = edges.filterEdgesByWindow(since, until)
        assertEquals(3, result.size)
        assertTrue(result.all { !it.timestamp.isBefore(since) && it.timestamp.isBefore(until) })
    }
}