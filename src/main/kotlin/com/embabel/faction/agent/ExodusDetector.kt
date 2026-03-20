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

import com.embabel.faction.domain.DepartedContributor
import com.embabel.faction.domain.ExodusDetection
import com.embabel.faction.domain.ReviewEdge
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Detects coordinated contributor departures by finding step-changes in the
 * centrality-weighted active contributor mass across rolling windows.
 *
 * Normal churn is gradual; a coordinated exodus produces a step-change — multiple
 * established contributors going silent within the same short window.
 *
 * Centrality is measured as total review degree (edges given + received) across the
 * full analysis period, so departing core reviewers are weighted more heavily than
 * peripheral contributors.
 */
@Component
class ExodusDetector {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val WINDOW_DAYS = 30L
        private const val STEP_DAYS = 7L
        private const val DROP_THRESHOLD = 0.15       // 15% weighted mass loss = step-change (tuned: nodejs Aug 2017 TSC exodus = 18%, nodejs 2018-19 attrition = 16%)
        private const val MIN_STABLE_WINDOWS = 3      // windows on each side to confirm step
        private const val MIN_CENTRALITY = 3.0        // ignore drive-by contributors
    }

    /**
     * Returns an [ExodusDetection] if a coordinated departure is found, null otherwise.
     *
     * [windowUntil] should be passed as the analysis window's `until` bound so that
     * post-departure silence is measured up to the intended cutoff, not just to the
     * last edge timestamp. Without this, the contamination fix (filtering edges to the
     * window) would eliminate the post-departure slack that confirms the step-change.
     */
    fun detect(edges: List<ReviewEdge>, windowUntil: Instant? = null): ExodusDetection? {
        if (edges.isEmpty()) return null

        val centrality = computeCentrality(edges)
        val since = edges.minOf { it.timestamp }
        val until = windowUntil ?: edges.maxOf { it.timestamp }

        val windows = buildWindows(edges, since, until, centrality)
        if (windows.size < MIN_STABLE_WINDOWS * 2 + 1) return null

        val mass = windows.map { it.weightedMass }
        val stepIdx = findStepChange(mass) ?: return null

        val activeBefore = windows.subList(maxOf(0, stepIdx - MIN_STABLE_WINDOWS), stepIdx)
            .flatMap { it.activeContributors }.toSet()
        // Start activeAfter at stepIdx+1: window stepIdx is the transition window where mass begins
        // to drop. Contributors last seen there are in the process of departing; including it in the
        // "after" set would hide them from the departed list.
        val afterStart = minOf(stepIdx + 1, windows.size)
        val activeAfter = windows.subList(afterStart, minOf(windows.size, afterStart + MIN_STABLE_WINDOWS))
            .flatMap { it.activeContributors }.toSet()

        val departed = (activeBefore - activeAfter)
            .filter { (centrality[it] ?: 0.0) >= MIN_CENTRALITY }
            .map { login ->
                DepartedContributor(
                    login = login,
                    centrality = centrality[login]!!,
                    lastActiveWindow = windows.lastOrNull { login in it.activeContributors }?.windowStart
                        ?: windows[stepIdx].windowStart,
                )
            }
            .sortedByDescending { it.centrality }

        if (departed.isEmpty()) return null

        val massBefore = mass.subList(maxOf(0, stepIdx - MIN_STABLE_WINDOWS), stepIdx).average()
        val massAfter = mass.subList(afterStart, minOf(mass.size, afterStart + MIN_STABLE_WINDOWS)).average()

        val totalProjectCentrality = centrality.values.sum()
        val departedCentrality = departed.sumOf { it.centrality }

        return ExodusDetection(
            inferredDate = windows[stepIdx].windowStart,
            departedContributors = departed,
            weightedMassBefore = massBefore,
            weightedMassAfter = massAfter,
            dropFraction = if (massBefore > 0) (massBefore - massAfter) / massBefore else 0.0,
            totalProjectCentrality = totalProjectCentrality,
            departureCentralityFraction = if (totalProjectCentrality > 0) departedCentrality / totalProjectCentrality else 0.0,
        )
    }

    /** Total review interactions per contributor across the full window. */
    private fun computeCentrality(edges: List<ReviewEdge>): Map<String, Double> {
        val counts = mutableMapOf<String, Double>()
        edges.forEach { e ->
            counts[e.reviewer] = (counts[e.reviewer] ?: 0.0) + 1.0
            counts[e.author] = (counts[e.author] ?: 0.0) + 1.0
        }
        return counts
    }

    private data class Window(
        val windowStart: Instant,
        val activeContributors: Set<String>,
        val weightedMass: Double,
    )

    private fun buildWindows(
        edges: List<ReviewEdge>,
        since: Instant,
        until: Instant,
        centrality: Map<String, Double>,
    ): List<Window> {
        val windows = mutableListOf<Window>()
        var start = since
        while (start.isBefore(until)) {
            val end = start.plus(WINDOW_DAYS, ChronoUnit.DAYS)
            val active = edges
                .filter { it.timestamp >= start && it.timestamp < end }
                .flatMap { listOf(it.reviewer, it.author) }
                .filter { (centrality[it] ?: 0.0) >= MIN_CENTRALITY }
                .toSet()
            val mass = active.sumOf { centrality[it] ?: 0.0 }
            windows.add(Window(start, active, mass))
            start = start.plus(STEP_DAYS, ChronoUnit.DAYS)
        }
        return windows
    }

    /**
     * Returns the index of the earliest window where the [MIN_STABLE_WINDOWS]-window
     * rolling average drops by at least [DROP_THRESHOLD] relative to the preceding average.
     */
    private fun findStepChange(mass: List<Double>): Int? {
        var bestIdx: Int? = null
        var bestDrop = 0.0
        for (i in MIN_STABLE_WINDOWS until mass.size - MIN_STABLE_WINDOWS) {
            val before = mass.subList(i - MIN_STABLE_WINDOWS, i).average()
            val after = mass.subList(i, i + MIN_STABLE_WINDOWS).average()
            if (before > 0) {
                val drop = (before - after) / before
                if (drop > bestDrop) { bestDrop = drop; bestIdx = i }
                if (drop >= DROP_THRESHOLD) return i
            }
        }
        if (bestIdx != null) logger.debug("ExodusDetector: best step-change candidate drop={} at index {} (threshold={}) — no exodus fired", bestDrop, bestIdx, DROP_THRESHOLD)
        return null
    }
}