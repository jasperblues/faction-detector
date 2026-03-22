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
class FractureDetector(private val weights: DetectorWeights = DetectorWeights()) {

    /**
     * [factionSignal] is the maximum [ScoredPair.factionSignal] across all LLM-scored pairs
     * in the analysis window. When non-null and [DetectorWeights.minOccurredFactionSignal] > 0,
     * the adversarial comment signal must clear the threshold before FRACTURE_OCCURRED is returned;
     * otherwise the result demotes to GOVERNANCE_CRISIS. Null = gate bypassed (backward compat).
     */
    fun detect(scores: List<WindowedScore>, exodus: ExodusDetection? = null, factionSignal: Double? = null): FractureDetection {
        if (scores.isEmpty()) return stable(Instant.now(), 0.0, 0)

        val globalPeak = scores.maxByOrNull { it.asymmetryRatio }!!

        if (globalPeak.asymmetryRatio < weights.tensionThreshold) {
            return stable(globalPeak.windowStart, globalPeak.asymmetryRatio, globalPeak.connectedComponents)
        }

        val peakCluster = findPeakCluster(scores, globalPeak)

        // If the peak cluster is too small for any non-trivial classification, check whether a more
        // sustained cluster exists elsewhere in the series. A brief initial spike followed by a
        // brief dip and then a long re-escalation (e.g. a holiday lull between two tension waves)
        // should classify on the dominant sustained period, not the small initial burst.
        val cluster = if (peakCluster.size < weights.minOccurredClusterSize) {
            findAllClusters(scores)
                .filter { it !== peakCluster && it.size >= weights.minClusterSize }
                .maxByOrNull { c -> c.sumOf { it.asymmetryRatio } }
                ?: peakCluster
        } else {
            peakCluster
        }

        // Cluster too small: isolated spike in a small reviewer pool, not sustained tension.
        if (cluster.size < weights.minClusterSize) {
            return stable(globalPeak.windowStart, globalPeak.asymmetryRatio, globalPeak.connectedComponents)
        }

        // Peak within the selected cluster (may differ from the global peak when the fallback
        // cluster is used).
        val peakWindow = cluster.maxByOrNull { it.asymmetryRatio }!!

        val peakDate = clusterCentroid(cluster)

        val clusterStartIdx = scores.indexOf(cluster.first())
        val clusterEndIdx = scores.indexOf(cluster.last())
        val beforeCluster = scores.subList(0, clusterStartIdx)
        val afterCluster = scores.subList(clusterEndIdx + 1, scores.size)

        val preClusterMean = beforeCluster.map { it.asymmetryRatio }.average().takeIf { !it.isNaN() } ?: 0.0
        val postClusterMean = afterCluster.map { it.asymmetryRatio }.average().takeIf { !it.isNaN() } ?: Double.MAX_VALUE

        val peakIsRecent = scores.indexOf(peakWindow) >= scores.size - weights.recentWindowCount
        val exodusAfterPeak = exodus != null && exodus.inferredDate.isAfter(peakDate)
        val asymmetryDropped = afterCluster.size >= weights.minPostWindows &&
                postClusterMean < peakWindow.asymmetryRatio * weights.resolutionRatio
        // Exodus after the peak counts as resolution — the departure was the fracture event.
        // When exodus is near the end of the analysis window, asymmetryDropped may not fire
        // (not enough settled post-cluster data), so we keep exodusAfterPeak as an OR.
        // Multi-wave: a single post-cluster window well below tension threshold (< 0.4) is
        // strong evidence the first fracture event briefly resolved, even if tension re-escalated
        // afterward (e.g. nodejs io.js fork Dec 2014, brief calm Jan 2015, then Foundation
        // negotiations brought renewed tension). We treat this as FRACTURE_OCCURRED + re-escalation
        // rather than FRACTURE_IMMINENT, which would hide that a fork already happened.
        // A post-cluster dip counts as genuine brief resolution only when we have a pre-cluster
        // baseline AND the window had sufficient activity. Without a baseline we cannot confirm
        // a spike occurred — the graph may simply be structurally sparse. Without sufficient
        // edges, the dip may be a holiday/lull artefact rather than a real resolution.
        val hadBriefResolution = beforeCluster.isNotEmpty() && afterCluster.take(weights.briefResolutionWindowSize).any {
            it.asymmetryRatio < weights.briefResolutionThreshold
                && it.edgeCount >= weights.minResolutionWindowEdges
        }
        val isResolved = asymmetryDropped || exodusAfterPeak || hadBriefResolution
        // Re-escalation: tension resolved (via any path) but trailing windows are still elevated.
        val trailingMean = scores.takeLast(weights.recentWindowCount).map { it.asymmetryRatio }.average()
        val isReEscalating = isResolved && trailingMean >= weights.tensionThreshold && run {
            // Exodus-based: exclude the re-escalation from the run-up to the exodus itself.
            val postEventWindows = if (exodusAfterPeak)
                scores.filter { !it.windowStart.isBefore(exodus!!.inferredDate) }.takeLast(weights.recentWindowCount)
            else
                scores.takeLast(weights.recentWindowCount)
            postEventWindows.size >= 2 && postEventWindows.map { it.asymmetryRatio }.average() >= weights.tensionThreshold
        }
        // Sharp rise: peak exceeds pre-cluster mean by SHARP_RISE_DELTA, regardless of absolute level.
        // Handles commercially-contested projects (Redis, Terraform) where structural asymmetry
        // sets a higher resting baseline — what matters is how far the peak rose from *that* baseline.
        // When data starts mid-fracture (no pre-cluster), fall back to post-cluster dip as evidence
        // the high asymmetry was temporary rather than a persistent structural baseline.
        val isSharpRise = peakWindow.asymmetryRatio >= weights.imminentThreshold && if (beforeCluster.isNotEmpty()) {
            peakWindow.asymmetryRatio - preClusterMean > weights.sharpRiseDelta
        } else {
            afterCluster.any { it.asymmetryRatio < weights.briefResolutionThreshold }
        }
        val isRising = scores.size >= 2 &&
                scores.takeLast(weights.recentWindowCount).zipWithNext { a, b -> b.asymmetryRatio - a.asymmetryRatio }.sum() > 0.0

        // Relative faction signal: compare crisis-window average to pre-cluster baseline.
        // When per-window data is available:
        //   positive delta → adversarial pairs worsened during the cluster (FRACTURE_ADVERSARIAL_FORK)
        //   negative delta → adversarial pairs improved during the cluster (FRACTURE_UPRISING —
        //     contributors unified against the steward, not against each other)
        // Falls back to the absolute factionSignal parameter when no baseline data is available.
        val baselineFactionSignal = beforeCluster
            .mapNotNull { it.windowFactionSignal }.average().takeIf { !it.isNaN() }
        val crisisFactionSignal = cluster
            .mapNotNull { it.windowFactionSignal }.maxOrNull()
        val relativeFactionSignal = if (baselineFactionSignal != null && crisisFactionSignal != null)
            crisisFactionSignal - baselineFactionSignal else null

        // Determine which confirmed-fracture type is supported by the available signal.
        // null → GOVERNANCE_CRISIS (not enough evidence to confirm a fork-level event).
        // factionSignal == null → gate bypassed (backward compat) → default to adversarial.
        val fractureType: TensionPattern? = when {
            // Relative signal takes precedence when per-window data is available — this is the
            // most authoritative signal because it compares crisis to the project's own baseline.
            relativeFactionSignal != null && relativeFactionSignal > weights.minRelativeFactionSignal ->
                TensionPattern.FRACTURE_ADVERSARIAL_FORK
            relativeFactionSignal != null && relativeFactionSignal < -weights.uprisingRelativeSignal ->
                TensionPattern.FRACTURE_UPRISING
            // Structural uprising confirmation: per-window signal data is available but ambiguous
            // (not clearly adversarial), and re-escalation after brief resolution suggests the fork
            // crystallised (brief calm) then both sides restructured (re-escalation).
            // Requires relativeFactionSignal data — without it we can't distinguish uprising from
            // adversarial fork and should fall back to backward compat.
            relativeFactionSignal != null && hadBriefResolution && isReEscalating ->
                TensionPattern.FRACTURE_UPRISING
            // Backward compat: no faction signal data, or ambiguous relative signal with no
            // absolute signal to break the tie → default to adversarial.
            factionSignal == null -> TensionPattern.FRACTURE_ADVERSARIAL_FORK
            // Absolute signal fallback when no per-window baseline is available
            weights.minOccurredFactionSignal <= 0.0 -> TensionPattern.FRACTURE_ADVERSARIAL_FORK
            factionSignal != null && factionSignal >= weights.minOccurredFactionSignal -> TensionPattern.FRACTURE_ADVERSARIAL_FORK
            else -> null
        }

        val rawPattern = when {
            // Unresolved: severity determines IMMINENT vs LIKELY
            !isResolved && peakWindow.asymmetryRatio >= weights.imminentThreshold -> TensionPattern.FRACTURE_IMMINENT
            !isResolved -> TensionPattern.FRACTURE_LIKELY
            // Resolved sharp rise: fracture type if signal confirms it,
            // otherwise GOVERNANCE_CRISIS (structural disruption without fork-level evidence).
            isResolved && isSharpRise && fractureType != null -> fractureType
            isResolved && isSharpRise -> TensionPattern.GOVERNANCE_CRISIS
            isResolved && exodus != null && isAttrition(exodus) -> TensionPattern.ATTRITION
            isResolved -> TensionPattern.EXODUS
            else -> TensionPattern.STABLE
        }
        // Confirmed fractures require a long sustained cluster (minFractureClusterSize ≈ 9 weeks)
        // calibrated to the smallest confirmed corpus fracture (io.js). Shorter clusters demote
        // to GOVERNANCE_CRISIS if still long enough (minOccurredClusterSize), else STABLE.
        val isFracture = rawPattern == TensionPattern.FRACTURE_ADVERSARIAL_FORK
            || rawPattern == TensionPattern.FRACTURE_UPRISING
        val pattern = when {
            isFracture && cluster.size < weights.minFractureClusterSize ->
                // Re-escalation override: if the initial fracture was too short for confirmation
                // but tension re-escalated, the original resolution is moot — the fracture is
                // ongoing. Use post-cluster faction signal as reference to distinguish uprising
                // (crisis signal below post-cluster baseline — community unified against steward)
                // from generic imminent fracture. This fallback baseline is only used here in
                // the demotion context, not for primary classification, to avoid sampling bias
                // when adversarial members depart (post-departure rump has different signal profile).
                if (isReEscalating) {
                    val overrideSignal = relativeFactionSignal ?: run {
                        val postBaseline = afterCluster
                            .mapNotNull { it.windowFactionSignal }.average().takeIf { !it.isNaN() }
                        if (postBaseline != null && crisisFactionSignal != null)
                            crisisFactionSignal - postBaseline else null
                    }
                    // In the demotion context, structural evidence (re-escalation) is already
                    // strong — any negative signal (crisis below post-cluster baseline) is
                    // sufficient to confirm uprising. The primary gate's stricter threshold
                    // applies to the main classification path.
                    if (overrideSignal != null && overrideSignal < 0)
                        TensionPattern.FRACTURE_UPRISING
                    else TensionPattern.FRACTURE_IMMINENT
                }
                else if (cluster.size >= weights.minOccurredClusterSize) TensionPattern.GOVERNANCE_CRISIS
                else TensionPattern.STABLE
            rawPattern == TensionPattern.GOVERNANCE_CRISIS && cluster.size < weights.minOccurredClusterSize ->
                TensionPattern.STABLE
            else -> rawPattern
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
            isResolved || isFracture || pattern == TensionPattern.GOVERNANCE_CRISIS ->
                afterCluster.firstOrNull()?.windowStart
            else -> null
        }

        val resolutionDate = if (isResolved)
            afterCluster.firstOrNull { it.asymmetryRatio < peakWindow.asymmetryRatio * 0.4 }?.windowStart
        else null

        val alternativePattern = computeAlternativePattern(
            pattern = pattern,
            peakAsymmetry = peakWindow.asymmetryRatio,
            preClusterMean = preClusterMean,
            hasBaseline = beforeCluster.isNotEmpty(),
            exodus = exodus,
        )

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
            alternativePattern = alternativePattern,
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

    /** Expands outward from [peakWindow] to include all contiguous windows above threshold. */
    private fun findPeakCluster(scores: List<WindowedScore>, peakWindow: WindowedScore): List<WindowedScore> {
        val peakIdx = scores.indexOf(peakWindow)
        var left = peakIdx
        while (left > 0 && scores[left - 1].asymmetryRatio >= weights.tensionThreshold) left--
        var right = peakIdx
        while (right < scores.size - 1 && scores[right + 1].asymmetryRatio >= weights.tensionThreshold) right++
        return scores.subList(left, right + 1)
    }

    /** Returns all contiguous above-threshold clusters in chronological order. */
    private fun findAllClusters(scores: List<WindowedScore>): List<List<WindowedScore>> {
        val clusters = mutableListOf<List<WindowedScore>>()
        var i = 0
        while (i < scores.size) {
            if (scores[i].asymmetryRatio >= weights.tensionThreshold) {
                val start = i
                while (i < scores.size && scores[i].asymmetryRatio >= weights.tensionThreshold) i++
                clusters.add(scores.subList(start, i))
            } else {
                i++
            }
        }
        return clusters
    }

    /**
     * Returns true when an exodus looks like natural contributor lifecycle turnover rather than
     * a coordinated faction departure. Both conditions must hold:
     * - Core impact is small (<= 12% of total project centrality) — not load-bearing contributors.
     * - Active-window mass drop is modest (<= 33%) — no mass coordinated exit.
     */
    private fun isAttrition(exodus: ExodusDetection): Boolean =
        exodus.departureCentralityFraction <= weights.attritionCoreThreshold &&
            exodus.dropFraction <= weights.attritionDropThreshold

    /**
     * Returns the pattern this result nearly was, when the decisive threshold was within
     * [DetectorWeights.borderlineMargin] of the crossing point. Null = clear-cut classification.
     *
     * Checks boundaries in priority order:
     * 1. ATTRITION ↔ EXODUS — driven by attritionCoreThreshold / attritionDropThreshold
     * 2. FRACTURE_OCCURRED ↔ GOVERNANCE_CRISIS — driven by sharpRiseDelta (when baseline exists)
     * 3. GOVERNANCE_CRISIS ↔ EXODUS — driven by sharpRiseDelta
     * 4. FRACTURE_IMMINENT ↔ FRACTURE_LIKELY — driven by imminentThreshold
     */
    private fun computeAlternativePattern(
        pattern: TensionPattern,
        peakAsymmetry: Double,
        preClusterMean: Double,
        hasBaseline: Boolean,
        exodus: ExodusDetection?,
    ): TensionPattern? {
        val m = weights.borderlineMargin
        return when (pattern) {
            TensionPattern.ATTRITION ->
                if (exodus != null && (
                        isNear(exodus.departureCentralityFraction, weights.attritionCoreThreshold, m) ||
                        isNear(exodus.dropFraction, weights.attritionDropThreshold, m)))
                    TensionPattern.EXODUS else null

            TensionPattern.EXODUS -> when {
                // Nearly ATTRITION: core impact just over threshold and drop already qualifies
                exodus != null &&
                        exodus.dropFraction < weights.attritionDropThreshold &&
                        isNear(exodus.departureCentralityFraction, weights.attritionCoreThreshold, m) ->
                    TensionPattern.ATTRITION
                // Nearly GOVERNANCE_CRISIS: isSharpRise delta was just below sharpRiseDelta
                hasBaseline && isNear(peakAsymmetry - preClusterMean, weights.sharpRiseDelta, m) ->
                    TensionPattern.GOVERNANCE_CRISIS
                else -> null
            }

            TensionPattern.GOVERNANCE_CRISIS ->
                if (hasBaseline && isNear(peakAsymmetry - preClusterMean, weights.sharpRiseDelta, m))
                    TensionPattern.FRACTURE_ADVERSARIAL_FORK else null

            TensionPattern.FRACTURE_ADVERSARIAL_FORK, TensionPattern.FRACTURE_UPRISING ->
                if (hasBaseline && isNear(peakAsymmetry - preClusterMean, weights.sharpRiseDelta, m))
                    TensionPattern.GOVERNANCE_CRISIS else null

            TensionPattern.FRACTURE_IMMINENT ->
                if (isNear(peakAsymmetry, weights.imminentThreshold, m)) TensionPattern.FRACTURE_LIKELY else null

            TensionPattern.FRACTURE_LIKELY ->
                if (isNear(peakAsymmetry, weights.imminentThreshold, m)) TensionPattern.FRACTURE_IMMINENT else null

            else -> null
        }
    }

    /** True when [value] is within [margin] fraction of [threshold] on either side. */
    private fun isNear(value: Double, threshold: Double, margin: Double) =
        threshold > 0 && kotlin.math.abs(value - threshold) / threshold < margin

    /** Asymmetry-weighted centroid of the cluster — biased toward the hottest windows. */
    private fun clusterCentroid(cluster: List<WindowedScore>): Instant {
        val totalWeight = cluster.sumOf { it.asymmetryRatio }
        if (totalWeight == 0.0) return cluster[cluster.size / 2].windowStart
        val weightedEpoch = cluster.sumOf { it.windowStart.toEpochMilli().toDouble() * it.asymmetryRatio }
        return Instant.ofEpochMilli((weightedEpoch / totalWeight).toLong())
    }
}
