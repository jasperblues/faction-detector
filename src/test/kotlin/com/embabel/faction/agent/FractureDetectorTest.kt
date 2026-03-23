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

import com.embabel.faction.domain.DetectorWeights
import com.embabel.faction.domain.TensionPattern
import com.embabel.faction.domain.TensionSeverity
import com.embabel.faction.domain.WindowedScore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class FractureDetectorTest {

    private val detector = FractureDetector()

    private fun week(weeksAgo: Int): Instant =
        Instant.now().minus((weeksAgo * 7).toLong(), ChronoUnit.DAYS)

    private fun score(asymmetry: Double, weeksAgo: Int, communities: Int = 3, edgeCount: Int = 0) = WindowedScore(
        windowStart = week(weeksAgo),
        windowEnd = week(weeksAgo).plus(30, ChronoUnit.DAYS),
        asymmetryRatio = asymmetry,
        connectedComponents = communities,
        modularity = asymmetry * 0.5,
        edgeCount = edgeCount,
    )

    @Test
    fun `empty scores returns STABLE`() {
        val result = detector.detect(emptyList())
        assertEquals(TensionPattern.STABLE, result.pattern)
        assertEquals(TensionSeverity.LOW, result.severity)
        assertFalse(result.isResolved)
    }

    @Test
    fun `all scores below threshold returns STABLE`() {
        val scores = listOf(score(0.2, 20), score(0.3, 15), score(0.25, 10), score(0.2, 5))
        val result = detector.detect(scores)
        assertEquals(TensionPattern.STABLE, result.pattern)
    }

    @Test
    fun `sharp spike in past with drop after returns GOVERNANCE_CRISIS`() {
        // Low baseline → sharp spike (5 windows — below minFractureClusterSize=9) → drop → resolved.
        // Short cluster and no faction signal → GOVERNANCE_CRISIS, not FRACTURE_ADVERSARIAL_FORK.
        val scores = listOf(
            score(0.2, 30), score(0.25, 28), score(0.2, 26),  // quiet baseline
            score(0.75, 22), score(0.8, 20), score(0.9, 18), score(1.0, 16), score(0.85, 14),  // spike (5 windows)
            score(0.3, 8), score(0.2, 6), score(0.15, 4), score(0.1, 2),     // resolved
        )
        val result = detector.detect(scores)
        assertEquals(TensionPattern.GOVERNANCE_CRISIS, result.pattern)
        assertEquals(TensionSeverity.EXTREME, result.severity)
        assertTrue(result.isResolved)
        assertNotNull(result.fractureDate)
        assertFalse(result.isRising)
    }

    @Test
    fun `gradual rise and fall returns EXODUS`() {
        // Gradual climb from above-baseline → gradual resolution
        val scores = listOf(
            score(0.45, 40), score(0.5, 35), score(0.55, 30),  // gradual rise from elevated base
            score(0.6, 25), score(0.65, 20), score(0.6, 15),   // plateau
            score(0.4, 8), score(0.3, 5), score(0.2, 2),       // gradual resolution
        )
        val result = detector.detect(scores)
        assertEquals(TensionPattern.EXODUS, result.pattern)
        assertTrue(result.isResolved)
    }

    @Test
    fun `peak at window end with no resolution is PRE_FRACTURE`() {
        val scores = listOf(
            score(0.2, 20), score(0.3, 16), score(0.5, 12),
            score(0.7, 8), score(0.85, 4), score(0.9, 1),
        )
        val result = detector.detect(scores)
        assertEquals(TensionPattern.FRACTURE_IMMINENT, result.pattern)
        assertEquals(TensionSeverity.EXTREME, result.severity)
        assertFalse(result.isResolved)
    }

    @Test
    fun `rising recent windows sets isRising true`() {
        // Cluster must be >= minClusterSize (3) for the pattern path to run and set isRising.
        val scores = listOf(
            score(0.2, 16), score(0.3, 12), score(0.5, 8), score(0.6, 4), score(0.75, 1),
        )
        val result = detector.detect(scores)
        assertTrue(result.isRising)
    }

    @Test
    fun `nodejs-like pattern scores EXTREME GOVERNANCE_CRISIS without faction signal`() {
        // 6-window cluster — below minFractureClusterSize=9 and no faction signal provided.
        // Real fracture requires 9+ sustained weeks; this grades as GOVERNANCE_CRISIS.
        val scores = listOf(
            score(0.6, 24), score(0.75, 22), score(0.9, 20),
            score(1.0, 18), score(0.95, 16), score(0.55, 14),            // 6-window cluster
            score(0.45, 8), score(0.35, 6),                              // drop
            score(0.25, 4), score(0.2, 2),                               // resolved
        )
        val result = detector.detect(scores)
        assertEquals(TensionPattern.GOVERNANCE_CRISIS, result.pattern)
        assertEquals(TensionSeverity.EXTREME, result.severity)
        assertTrue(result.isResolved)
        assertEquals(1.0, result.peakAsymmetry)
        assertNotNull(result.peakDate)
    }

    @Test
    fun `9-window cluster with faction signal gives FRACTURE_ADVERSARIAL_FORK`() {
        // 9 consecutive elevated windows (floor for FRACTURE_ADVERSARIAL_FORK) + faction signal provided.
        val scores = listOf(
            score(0.2, 36), score(0.25, 34),                             // baseline
            score(0.6, 28), score(0.75, 26), score(0.9, 24),
            score(1.0, 22), score(0.95, 20), score(0.85, 18),
            score(0.75, 16), score(0.7, 14), score(0.6, 12),            // 9-window cluster
            score(0.3, 6), score(0.2, 4), score(0.15, 2),               // resolved
        )
        val result = detector.detect(scores, factionSignal = 0.55)
        assertEquals(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result.pattern)
        assertEquals(TensionSeverity.EXTREME, result.severity)
        assertTrue(result.isResolved)
    }

    @Test
    fun `9-window cluster with low faction signal stays GOVERNANCE_CRISIS`() {
        // Gate enabled at 0.35; factionSignal=0.20 (below threshold) → demotes FRACTURE_ADVERSARIAL_FORK.
        // Mirrors the live terraform BSL result: license-driven fork, no adversarial review signal.
        val weights = DetectorWeights(minOccurredFactionSignal = 0.35)
        val gated = FractureDetector(weights)
        val scores = listOf(
            score(0.2, 36), score(0.25, 34),                             // baseline
            score(0.6, 28), score(0.75, 26), score(0.9, 24),
            score(1.0, 22), score(0.95, 20), score(0.85, 18),
            score(0.75, 16), score(0.7, 14), score(0.6, 12),            // 9-window cluster
            score(0.3, 6), score(0.2, 4), score(0.15, 2),               // resolved
        )
        val result = gated.detect(scores, factionSignal = 0.20)
        assertEquals(TensionPattern.GOVERNANCE_CRISIS, result.pattern)
        assertEquals(TensionSeverity.EXTREME, result.severity)
        assertTrue(result.isResolved)
    }

    @Test
    fun `governance-frustration fork with re-escalation gives FRACTURE_UPRISING despite low faction signal`() {
        // io.js pattern: contributors aligned against external steward (Joyent), not each other.
        // Per-window faction signal is LOW during the crisis (contributors cooperating) and
        // slightly higher at baseline — negative relative signal confirms uprising.
        // Brief resolution (fork crystallises) followed by re-escalation (both sides restructuring).
        val scores = listOf(
            score(0.2, 24).copy(windowFactionSignal = 0.10),             // baseline: normal friction
            score(0.25, 22).copy(windowFactionSignal = 0.12),
            score(1.0, 18).copy(windowFactionSignal = 0.03),             // crisis: cooperating against steward
            score(0.82, 17).copy(windowFactionSignal = 0.02),
            score(0.80, 16).copy(windowFactionSignal = 0.04),
            score(1.0, 15).copy(windowFactionSignal = 0.03),
            score(0.80, 14).copy(windowFactionSignal = 0.02),
            score(0.83, 13).copy(windowFactionSignal = 0.03),
            score(0.71, 12).copy(windowFactionSignal = 0.04),
            score(0.65, 11).copy(windowFactionSignal = 0.05),
            score(0.65, 10).copy(windowFactionSignal = 0.04),            // 9-window cluster
            score(0.33, 7, edgeCount = 10).copy(windowFactionSignal = 0.06), // brief resolution
            score(0.67, 5).copy(windowFactionSignal = 0.08),             // re-escalation
        )
        val result = detector.detect(scores, factionSignal = 0.20)
        assertEquals(TensionPattern.FRACTURE_UPRISING, result.pattern)
        assertEquals(TensionSeverity.EXTREME, result.severity)
        assertTrue(result.isResolved)
        assertTrue(result.isReEscalating)
    }

    @Test
    fun `BDFL-like pattern (high asymmetry, no tension) stays STABLE`() {
        // Consistently elevated but below tension threshold — structural asymmetry, not factional
        val scores = listOf(
            score(0.35, 20), score(0.4, 15), score(0.38, 10),
            score(0.42, 5), score(0.36, 2),
        )
        val result = detector.detect(scores)
        assertEquals(TensionPattern.STABLE, result.pattern)
    }

    @Test
    fun `peak date is centroid of cluster, not just max window`() {
        val scores = listOf(
            score(0.2, 30),
            score(0.9, 20), score(1.0, 18), score(0.9, 16),  // symmetric cluster around week 18
            score(0.2, 5),  score(0.15, 2), score(0.1, 1),
        )
        val result = detector.detect(scores)
        // Peak date should be near week(18) — centroid of the 3-window cluster
        val clusterCenter = week(18)
        val diffDays = Math.abs(result.peakDate.epochSecond - clusterCenter.epochSecond) / 86400
        assertTrue(diffDays < 14, "Peak date should be near cluster centroid, was $diffDays days off")
    }
}
