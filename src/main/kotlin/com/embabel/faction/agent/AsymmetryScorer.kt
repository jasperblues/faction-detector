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
import com.embabel.faction.domain.WindowedScore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Computes rolling 30-day window summary scores over a list of review edges.
 *
 * Each window records:
 * - [WindowedScore.asymmetryRatio]: fraction of directed review pairs with no reverse pair.
 * - [WindowedScore.connectedComponents]: number of connected components (union-find).
 * - [WindowedScore.modularity]: rough estimate of community structure strength.
 *
 * Only core contributors (those with both incoming and outgoing review edges in the window)
 * are included, filtering out newcomers and peripheral contributors.
 */
@Component
class AsymmetryScorer {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun score(
        edges: List<ReviewEdge>,
        since: Instant,
        until: Instant? = null,
        windowDays: Long = 30,
        stepDays: Long = 7,
        minReviews: Int = 2,
    ): List<WindowedScore> {
        if (edges.isEmpty()) return emptyList()

        logger.info("Scoring asymmetry over {} edges ({} → {})", edges.size, since.toString().take(10), (until ?: Instant.now()).toString().take(10))
        val ceiling = until ?: Instant.now()
        val results = mutableListOf<WindowedScore>()
        var windowStart = since

        while (windowStart.isBefore(ceiling)) {
            val windowEnd = windowStart.plus(windowDays, ChronoUnit.DAYS)
            val window = edges.filter { it.timestamp in windowStart..windowEnd }
            val coreEdges = filterCoreContributors(window, minReviews)
            if (coreEdges.isNotEmpty()) {
                results.add(scoreWindow(coreEdges, windowStart, windowEnd))
            }
            windowStart = windowStart.plus(stepDays, ChronoUnit.DAYS)
        }
        logger.info("Asymmetry scoring complete — {} windows", results.size)
        return results
    }

    /**
     * Retain only edges where both reviewer and author are "core" contributors —
     * those with at least [minReviews] review events in this window.
     */
    private fun filterCoreContributors(edges: List<ReviewEdge>, minReviews: Int): List<ReviewEdge> {
        val reviewerCounts = edges.groupBy { it.reviewer }.mapValues { it.value.size }
        val core = reviewerCounts.filter { it.value >= minReviews }.keys
        return edges.filter { it.reviewer in core && it.author in core }
    }

    private fun scoreWindow(edges: List<ReviewEdge>, start: Instant, end: Instant): WindowedScore {
        val pairs = edges.map { it.reviewer to it.author }.toSet()
        val asymmetric = pairs.count { (r, a) -> (a to r) !in pairs }
        val asymmetryRatio = if (pairs.isEmpty()) 0.0 else asymmetric.toDouble() / pairs.size

        val contributors = edges.flatMap { listOf(it.reviewer, it.author) }.toSet()
        val communities = connectedComponents(contributors, edges)
        val modularity = estimateModularity(contributors.size, communities)

        return WindowedScore(
            windowStart = start,
            windowEnd = end,
            asymmetryRatio = asymmetryRatio,
            connectedComponents = communities,
            modularity = modularity,
        )
    }

    private fun connectedComponents(contributors: Set<String>, edges: List<ReviewEdge>): Int {
        val parent = contributors.associateWith { it }.toMutableMap()
        fun find(x: String): String {
            if (parent[x] != x) parent[x] = find(parent[x]!!)
            return parent[x]!!
        }
        edges.forEach { e ->
            val ra = find(e.reviewer); val rb = find(e.author)
            if (ra != rb) parent[ra] = rb
        }
        return contributors.map { find(it) }.toSet().size
    }

    private fun estimateModularity(totalContributors: Int, communities: Int): Double =
        if (totalContributors <= 1) 0.0
        else (communities.toDouble() / totalContributors).coerceIn(0.0, 1.0)
}

private operator fun ClosedRange<Instant>.contains(value: Instant): Boolean =
    value >= start && value <= endInclusive
