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

        /** Pre-cluster mean below this + peak above 0.7 = sharp rise (fracture, not exodus). */
        private const val SHARP_RISE_BASELINE = 0.4
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
        val isResolved = (afterCluster.size >= MIN_POST_WINDOWS &&
                postClusterMean < peakWindow.asymmetryRatio * RESOLUTION_RATIO)
                || exodusAfterPeak
        // Sharp rise from calm: pre-cluster mean below baseline, or — when data starts mid-fracture
        // (no pre-cluster windows) — post-cluster dips clearly below 0.4, proving the high asymmetry
        // was temporary rather than a persistent structural baseline.
        val isSharpRise = peakWindow.asymmetryRatio >= 0.7 && if (beforeCluster.isNotEmpty()) {
            preClusterMean < SHARP_RISE_BASELINE
        } else {
            afterCluster.any { it.asymmetryRatio < 0.4 }
        }
        val isRising = scores.size >= 2 &&
                scores.takeLast(4).zipWithNext { a, b -> b.asymmetryRatio - a.asymmetryRatio }.sum() > 0.0

        val pattern = when {
            peakIsRecent && !isResolved -> TensionPattern.FRACTURE_IMMINENT
            !peakIsRecent && !isResolved -> TensionPattern.FRACTURE_IMMINENT // sustained unresolved = still active
            isResolved && isSharpRise -> TensionPattern.FRACTURE_OCCURRED
            isResolved -> TensionPattern.EXODUS
            else -> TensionPattern.STABLE
        }

        val severity = when {
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

    /** Asymmetry-weighted centroid of the cluster — biased toward the hottest windows. */
    private fun clusterCentroid(cluster: List<WindowedScore>): Instant {
        val totalWeight = cluster.sumOf { it.asymmetryRatio }
        if (totalWeight == 0.0) return cluster[cluster.size / 2].windowStart
        val weightedEpoch = cluster.sumOf { it.windowStart.toEpochMilli().toDouble() * it.asymmetryRatio }
        return Instant.ofEpochMilli((weightedEpoch / totalWeight).toLong())
    }
}
