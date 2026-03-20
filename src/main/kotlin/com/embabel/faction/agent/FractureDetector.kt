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

import com.embabel.faction.domain.ExodusDetection
import com.embabel.faction.domain.FractureDetection
import com.embabel.faction.domain.TensionPattern
import com.embabel.faction.domain.TensionSeverity
import com.embabel.faction.domain.WindowedScore
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Analyses a rolling window series to detect whether a fracture event has occurred,
 * is currently happening, or is building toward one.
 *
 * A "fracture" is a sharp asymmetry spike followed by resolution (fork or departure).
 * An "exodus" is a gradual sustained elevation that slowly resolves.
 * "Pre-fracture" means the peak is current — tension is unresolved.
 */
@Component
class FractureDetector {

    companion object {
        /** Asymmetry must exceed this to be considered a tension event. */
        private const val TENSION_THRESHOLD = 0.5

        /** Number of trailing windows considered "current". */
        private const val RECENT_WINDOW_COUNT = 4

        /** Post-cluster mean must drop below this fraction of peak to count as resolved. */
        private const val RESOLUTION_RATIO = 0.6

        /** Minimum post-cluster windows needed to confirm resolution. */
        private const val MIN_POST_WINDOWS = 3

        /** Peak must exceed pre-cluster mean by at least this delta to count as a sharp rise.
         *  Absolute baseline threshold is insufficient for commercially-contested projects
         *  (Redis, Terraform) where structural asymmetry bakes in a higher resting level.
         *  A delta-based check catches genuine spikes regardless of project baseline.
         *  Future: make this adaptive based on community centrality concentration. */
        private const val SHARP_RISE_DELTA = 0.4

        /** A single post-cluster window below this level is strong evidence the fracture
         *  briefly resolved — even if tension re-escalated afterward (multi-wave pattern).
         *  Used to detect FRACTURE_OCCURRED in windows that contain both an event and its aftermath. */
        private const val BRIEF_RESOLUTION_THRESHOLD = 0.4

        /** Peak below this level → FRACTURE_LIKELY rather than FRACTURE_IMMINENT.
         *  Reserves IMMINENT for clearly severe sustained tension. */
        private const val IMMINENT_THRESHOLD = 0.70

        /** ATTRITION: core impact below this fraction is consistent with natural turnover.
         *  At 12%, departure is a succession problem, not a faction problem. */
        private const val ATTRITION_CORE_THRESHOLD = 0.12

        /** ATTRITION: active-window mass drop below this fraction rules out coordinated departure.
         *  A 33% drop could be an organised faction; below that it's more likely natural churn. */
        private const val ATTRITION_DROP_THRESHOLD = 0.33
    }

    fun detect(scores: List<WindowedScore>, exodus: ExodusDetection? = null): FractureDetection {
        if (scores.isEmpty()) return stable(Instant.now(), 0.0, 0)

        val peakWindow = scores.maxByOrNull { it.asymmetryRatio }!!

        if (peakWindow.asymmetryRatio < TENSION_THRESHOLD) {
            return stable(peakWindow.windowStart, peakWindow.asymmetryRatio, peakWindow.connectedComponents)
        }

        val cluster = findPeakCluster(scores, peakWindow)
        val peakDate = clusterCentroid(cluster)

        val clusterStartIdx = scores.indexOf(cluster.first())
        val clusterEndIdx = scores.indexOf(cluster.last())
        val beforeCluster = scores.subList(0, clusterStartIdx)
        val afterCluster = scores.subList(clusterEndIdx + 1, scores.size)

        val preClusterMean = beforeCluster.map { it.asymmetryRatio }.average().takeIf { !it.isNaN() } ?: 0.0
        val postClusterMean = afterCluster.map { it.asymmetryRatio }.average().takeIf { !it.isNaN() } ?: Double.MAX_VALUE

        val peakIdx = scores.indexOf(peakWindow)
        val peakIsRecent = peakIdx >= scores.size - RECENT_WINDOW_COUNT
        val exodusAfterPeak = exodus != null && exodus.inferredDate.isAfter(peakDate)
        val asymmetryDropped = afterCluster.size >= MIN_POST_WINDOWS &&
                postClusterMean < peakWindow.asymmetryRatio * RESOLUTION_RATIO
        // Exodus after the peak counts as resolution — the departure was the fracture event.
        // When exodus is near the end of the analysis window, asymmetryDropped may not fire
        // (not enough settled post-cluster data), so we keep exodusAfterPeak as an OR.
        // Multi-wave: a single post-cluster window well below tension threshold (< 0.4) is
        // strong evidence the first fracture event briefly resolved, even if tension re-escalated
        // afterward (e.g. nodejs io.js fork Dec 2014, brief calm Jan 2015, then Foundation
        // negotiations brought renewed tension). We treat this as FRACTURE_OCCURRED + re-escalation
        // rather than FRACTURE_IMMINENT, which would hide that a fork already happened.
        val hadBriefResolution = afterCluster.any { it.asymmetryRatio < BRIEF_RESOLUTION_THRESHOLD }
        val isResolved = asymmetryDropped || exodusAfterPeak || hadBriefResolution
        // Re-escalation: tension resolved (via any path) but trailing windows are still elevated.
        val trailingMean = scores.takeLast(RECENT_WINDOW_COUNT).map { it.asymmetryRatio }.average()
        val isReEscalating = isResolved && trailingMean >= TENSION_THRESHOLD && run {
            // Exodus-based: exclude the re-escalation from the run-up to the exodus itself.
            val postEventWindows = if (exodusAfterPeak && exodus != null)
                scores.filter { !it.windowStart.isBefore(exodus.inferredDate) }.takeLast(RECENT_WINDOW_COUNT)
            else
                scores.takeLast(RECENT_WINDOW_COUNT)
            postEventWindows.size >= 2 && postEventWindows.map { it.asymmetryRatio }.average() >= TENSION_THRESHOLD
        }
        // Sharp rise: peak exceeds pre-cluster mean by SHARP_RISE_DELTA, regardless of absolute level.
        // Handles commercially-contested projects (Redis, Terraform) where structural asymmetry
        // sets a higher resting baseline — what matters is how far the peak rose from *that* baseline.
        // When data starts mid-fracture (no pre-cluster), fall back to post-cluster dip as evidence
        // the high asymmetry was temporary rather than a persistent structural baseline.
        val isSharpRise = peakWindow.asymmetryRatio >= 0.7 && if (beforeCluster.isNotEmpty()) {
            peakWindow.asymmetryRatio - preClusterMean > SHARP_RISE_DELTA
        } else {
            afterCluster.any { it.asymmetryRatio < 0.4 }
        }
        val isRising = scores.size >= 2 &&
                scores.takeLast(4).zipWithNext { a, b -> b.asymmetryRatio - a.asymmetryRatio }.sum() > 0.0

        val pattern = when {
            // Unresolved: severity determines IMMINENT vs LIKELY
            !isResolved && peakWindow.asymmetryRatio >= IMMINENT_THRESHOLD -> TensionPattern.FRACTURE_IMMINENT
            !isResolved -> TensionPattern.FRACTURE_LIKELY
            isResolved && isSharpRise -> TensionPattern.FRACTURE_OCCURRED
            isResolved && exodus != null && isAttrition(exodus) -> TensionPattern.ATTRITION
            isResolved -> TensionPattern.EXODUS
            else -> TensionPattern.STABLE
        }

        val severity = when {
            // ATTRITION severity is departure-scale driven, not peak asymmetry — asymmetry
            // rises as coverage thins, not because of adversarial dynamics.
            pattern == TensionPattern.ATTRITION -> when {
                exodus != null && (exodus.dropFraction >= 0.20 || exodus.departureCentralityFraction >= 0.07) -> TensionSeverity.MODERATE
                else -> TensionSeverity.LOW
            }
            peakWindow.asymmetryRatio >= 0.85 -> TensionSeverity.EXTREME
            peakWindow.asymmetryRatio >= 0.65 -> TensionSeverity.HIGH
            peakWindow.asymmetryRatio >= 0.45 -> TensionSeverity.MODERATE
            else -> TensionSeverity.LOW
        }

        val fractureDate = when {
            exodusAfterPeak -> exodus!!.inferredDate
            isResolved || pattern == TensionPattern.FRACTURE_OCCURRED -> afterCluster.firstOrNull()?.windowStart
            else -> null
        }

        val resolutionDate = if (isResolved)
            afterCluster.firstOrNull { it.asymmetryRatio < peakWindow.asymmetryRatio * 0.4 }?.windowStart
        else null

        return FractureDetection(
            pattern = pattern,
            severity = severity,
            peakDate = peakDate,
            peakAsymmetry = peakWindow.asymmetryRatio,
            peakCommunityCount = peakWindow.connectedComponents,
            fractureDate = fractureDate,
            resolutionDate = resolutionDate,
            isResolved = isResolved,
            isRising = isRising,
            isReEscalating = isReEscalating,
        )
    }

    private fun stable(peakDate: Instant, peakAsymmetry: Double, peakCommunityCount: Int) = FractureDetection(
        pattern = TensionPattern.STABLE,
        severity = TensionSeverity.LOW,
        peakDate = peakDate,
        peakAsymmetry = peakAsymmetry,
        peakCommunityCount = peakCommunityCount,
        fractureDate = null,
        resolutionDate = null,
        isResolved = false,
        isRising = false,
    )

    /** Expands outward from [peakWindow] to include all contiguous windows above [threshold]. */
    private fun findPeakCluster(scores: List<WindowedScore>, peakWindow: WindowedScore): List<WindowedScore> {
        val peakIdx = scores.indexOf(peakWindow)
        var left = peakIdx
        while (left > 0 && scores[left - 1].asymmetryRatio >= TENSION_THRESHOLD) left--
        var right = peakIdx
        while (right < scores.size - 1 && scores[right + 1].asymmetryRatio >= TENSION_THRESHOLD) right++
        return scores.subList(left, right + 1)
    }

    /**
     * Returns true when an exodus looks like natural contributor lifecycle turnover rather than
     * a coordinated faction departure. Both conditions must hold:
     * - Core impact is small (< 12% of total project centrality) — not load-bearing contributors.
     * - Active-window mass drop is modest (< 33%) — no mass coordinated exit.
     */
    private fun isAttrition(exodus: ExodusDetection): Boolean =
        exodus.departureCentralityFraction < ATTRITION_CORE_THRESHOLD &&
            exodus.dropFraction < ATTRITION_DROP_THRESHOLD

    /** Asymmetry-weighted centroid of the cluster — biased toward the hottest windows. */
    private fun clusterCentroid(cluster: List<WindowedScore>): Instant {
        val totalWeight = cluster.sumOf { it.asymmetryRatio }
        if (totalWeight == 0.0) return cluster[cluster.size / 2].windowStart
        val weightedEpoch = cluster.sumOf { it.windowStart.toEpochMilli().toDouble() * it.asymmetryRatio }
        return Instant.ofEpochMilli((weightedEpoch / totalWeight).toLong())
    }
}
