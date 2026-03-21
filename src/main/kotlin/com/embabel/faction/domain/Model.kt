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
package com.embabel.faction.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

enum class ReviewState { APPROVED, CHANGES_REQUESTED, COMMENTED }

/**
 * A single review event: one contributor reviewing another's PR.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewEdge(
    val reviewer: String,
    val author: String,
    val repo: String,
    val prNumber: Int?,
    val timestamp: Instant,
    val commentCount: Int,
    val state: ReviewState,
    val hoursToFirstReview: Double?,
    val daysMergedAfterReview: Double?,
) {
    /**
     * Edge weight for graph construction.
     * CHANGES_REQUESTED carries more weight than APPROVED (indicates friction).
     * Comment density adds further weight.
     */
    @get:JsonIgnore
    val weight: Double get() {
        val stateMultiplier = when (state) {
            ReviewState.CHANGES_REQUESTED -> 2.0
            ReviewState.APPROVED -> 1.0
            ReviewState.COMMENTED -> 0.5
        }
        val commentBonus = 1.0 + (commentCount * 0.1).coerceAtMost(1.0)
        return stateMultiplier * commentBonus
    }
}

/**
 * Asymmetry score for a single rolling window — used for trend analysis.
 *
 * [edgeCount] is the number of core-contributor edges in the window after filtering.
 * Low edge counts indicate low-activity periods (holidays, summer lulls) that can
 * produce spurious asymmetry dips — used to gate brief-resolution detection in FractureDetector.
 */
data class WindowedScore(
    val windowStart: Instant,
    val windowEnd: Instant,
    val asymmetryRatio: Double,
    val connectedComponents: Int,
    val modularity: Double,
    val edgeCount: Int = 0,
    /** Average factionSignal across all scored reviewer→author pairs active in this window.
     *  Null when no pairs were scored (no LLM data available for this window).
     *  Used by FractureDetector to compute relative faction signal — crisis vs baseline — which
     *  distinguishes adversarial forks (signal rises above baseline) from uprisings
     *  (signal stays at or falls below baseline as the community unifies against the steward). */
    val windowFactionSignal: Double? = null,
)

enum class TensionPattern {
    /** No significant asymmetry detected. */
    STABLE,
    /** High-centrality contributors leaving through natural turnover, not faction dynamics.
     *  Asymmetry rises as coverage thins but there is no adversarial review signal.
     *  Call to action: succession planning and knowledge transfer, not governance intervention. */
    ATTRITION,
    /** Moderate unresolved tension (peak 0.5–0.7) — early warning signal, not yet critical.
     *  Worth monitoring; governance intervention may prevent escalation. */
    FRACTURE_LIKELY,
    /** Severe unresolved tension (peak > 0.7) — structural fracture appears imminent. */
    FRACTURE_IMMINENT,
    /** Sharp asymmetry spike that resolved, but without confirmed adversarial comment signal or
     *  a long enough sustained cluster to confirm a fork-level event. May represent an
     *  organisational restructuring, corporate withdrawal, or brief internal crisis that healed.
     *  Actionable but less certain than FRACTURE_ADVERSARIAL_FORK or FRACTURE_UPRISING. */
    GOVERNANCE_CRISIS,
    /** Sharp spike followed by resolution — a confirmed fork driven by internal faction war.
     *  Adversarial review signal is measurably above baseline: contributor groups were actively
     *  hostile to each other in the review stream before the split (e.g. TSC 2017, Valkey 2024,
     *  RSALv2 2021, Docker Enterprise 2019). The strongest adversarial claim the detector makes.
     *  Requires a sustained cluster (>= [DetectorWeights.minFractureClusterSize] weeks). */
    FRACTURE_ADVERSARIAL_FORK,
    /** Sharp spike followed by resolution — a confirmed community uprising against the project
     *  steward. Review signal stays at or below baseline: contributors were unified against
     *  external authority, not against each other (e.g. io.js 2015, LibreSSL 2014, neovim 2014).
     *  Identified by below-baseline cooperation signal or re-escalation after brief resolution
     *  (the fork crystallised; both sides restructuring produced the re-escalation).
     *  Requires a sustained cluster (>= [DetectorWeights.minFractureClusterSize] weeks). */
    FRACTURE_UPRISING,
    /** Gradual sustained elevation that resolved — coordinated faction-driven departure. */
    EXODUS,
}

enum class TensionSeverity { LOW, MODERATE, HIGH, EXTREME }

/**
 * Result of temporal fracture detection across rolling windows.
 *
 * [peakDate] is the weighted centroid of the highest-asymmetry cluster.
 * [fractureDate] is the first window after the peak where resolution begins (null if unresolved).
 * [resolutionDate] is when asymmetry returned to near-baseline (null if unresolved).
 * [isRising] is true when recent windows are trending upward.
 * [isReEscalating] is true when a resolved event is followed by renewed high asymmetry —
 * the departure happened but the graph didn't heal.
 */
data class FractureDetection(
    val pattern: TensionPattern,
    val severity: TensionSeverity,
    val peakDate: Instant,
    val peakAsymmetry: Double,
    val peakCommunityCount: Int,
    val fractureDate: Instant?,
    val resolutionDate: Instant?,
    val isResolved: Boolean,
    val isRising: Boolean,
    val isReEscalating: Boolean = false,
    /** The pattern this result nearly was — set when the decisive threshold was crossed by < [DetectorWeights.borderlineMargin].
     *  Null when the classification was clear-cut. */
    val alternativePattern: TensionPattern? = null,
)

/**
 * A contributor who departed during an inferred exodus event.
 * [centrality] is their total review degree across the full analysis window.
 * [lastActiveWindow] is the start of the last window they appeared in.
 */
data class DepartedContributor(
    val login: String,
    val centrality: Double,
    val lastActiveWindow: Instant,
)

/**
 * An inferred exodus event detected from a step-change in weighted contributor activity.
 * [inferredDate] is the first window of the drop.
 * [dropFraction] is (massBefore - massAfter) / massBefore.
 * [totalProjectCentrality] is the sum of centrality for contributors active within 6 months
 * before the departure — contemporaneous, not all-time.
 * [departureCentralityFraction] is sum(departed centrality) / totalProjectCentrality.
 */
data class ExodusDetection(
    val inferredDate: Instant,
    val departedContributors: List<DepartedContributor>,
    val weightedMassBefore: Double,
    val weightedMassAfter: Double,
    val dropFraction: Double,
    val totalProjectCentrality: Double,
    val departureCentralityFraction: Double,
)

/**
 * Tunable thresholds for fracture detection.
 * Externalised so tests can vary weights without recompiling.
 */
data class DetectorWeights(
    /** Asymmetry must exceed this to be considered a tension event. */
    val tensionThreshold: Double = 0.5,
    /** Number of trailing windows considered "current". */
    val recentWindowCount: Int = 4,
    /** Post-cluster mean must drop below this fraction of peak to count as resolved. */
    val resolutionRatio: Double = 0.6,
    /** Minimum post-cluster windows needed to confirm resolution. */
    val minPostWindows: Int = 3,
    /** Peak must exceed pre-cluster mean by at least this delta to count as a sharp rise. */
    val sharpRiseDelta: Double = 0.38,
    /** A single post-cluster window below this level confirms a brief resolution (multi-wave). */
    val briefResolutionThreshold: Double = 0.4,
    /** Peak below this level → FRACTURE_LIKELY rather than FRACTURE_IMMINENT. */
    val imminentThreshold: Double = 0.70,
    /** ATTRITION: core impact below this fraction is consistent with natural turnover. */
    val attritionCoreThreshold: Double = 0.12,
    /** ATTRITION: active-window mass drop below this fraction rules out coordinated departure. */
    val attritionDropThreshold: Double = 0.33,
    /** Minimum edges in a post-cluster window for its asymmetry dip to count as a genuine
     *  brief resolution. Windows below this are likely low-activity periods (holidays, lulls)
     *  rather than real resolutions. Default 5 — sparse enough to include small active projects. */
    val minResolutionWindowEdges: Int = 5,
    /** Minimum number of consecutive windows that must form the peak cluster before any
     *  non-STABLE classification is returned. Prevents isolated noisy spikes in small reviewer
     *  pools from triggering false classifications. Default 3 ≈ 3 weeks of elevated asymmetry. */
    val minClusterSize: Int = 3,
    /** Minimum consecutive above-threshold windows to classify as GOVERNANCE_CRISIS.
     *  A shorter cluster demotes to STABLE. Must be >= minClusterSize. Default 5. */
    val minOccurredClusterSize: Int = 5,
    /** Minimum consecutive above-threshold windows to classify as FRACTURE_ADVERSARIAL_FORK
     *  or FRACTURE_UPRISING. Cases below this threshold demote to GOVERNANCE_CRISIS. Calibrated
     *  to the smallest confirmed corpus fracture (nodejs io.js = 9 windows). Cases shorter than
     *  9 weeks of sustained tension are more likely restructuring or brief crises than fork-level
     *  events. Must be >= minOccurredClusterSize. Default 9. */
    val minFractureClusterSize: Int = 9,
    /** Absolute faction signal threshold (fallback when no per-window baseline is available).
     *  When faction signal data is available (non-null) and this is > 0, at least one scored pair
     *  must exceed this value to confirm FRACTURE_ADVERSARIAL_FORK. 0.0 = gate disabled.
     *  When per-window [WindowedScore.windowFactionSignal] data is present, the relative signal
     *  thresholds [minRelativeFactionSignal] / [uprisingRelativeSignal] take precedence. */
    val minOccurredFactionSignal: Double = 0.35,
    /** Minimum relative faction signal (crisis window average − pre-cluster baseline average)
     *  to confirm FRACTURE_ADVERSARIAL_FORK. A positive delta means adversarial pairs were
     *  measurably worse than the project's own normal baseline. Requires per-window
     *  [WindowedScore.windowFactionSignal] data; falls back to [minOccurredFactionSignal] if
     *  not available. Default 0.10 — covers moby (+0.18) and TSC (+0.30). */
    val minRelativeFactionSignal: Double = 0.10,
    /** A relative faction signal below this magnitude (i.e. crisis is this many points BELOW
     *  baseline) suggests contributors unified against the steward rather than each other —
     *  the FRACTURE_UPRISING pattern. Expressed as a positive magnitude; the actual threshold
     *  is negative (baseline − crisis > uprisingRelativeSignal). Default 0.05. */
    val uprisingRelativeSignal: Double = 0.05,
    /** Only check this many afterCluster windows for a brief resolution dip. A dip months after
     *  the cluster ends (e.g. redis: first sub-0.40 dip is 5 months post-cluster) is a lull in
     *  sustained tension, not a brief post-fracture calm. Default 12 ≈ 3 months of 7-day steps.
     *  The nodejs io.js brief calm appeared in the 2nd afterCluster window — well within this. */
    val briefResolutionWindowSize: Int = 12,
    /** Relative margin within which a threshold crossing is flagged as borderline.
     *  0.10 = within 10% of the threshold value on either side.
     *  E.g. attritionCoreThreshold=12%: flags cases in [10.8%, 13.2%]. */
    val borderlineMargin: Double = 0.10,
    /** Lookback window (months) for contemporaneous centrality denominator in exodus detection.
     *  Contributors active within this many months before the departure count toward the
     *  denominator; older contributors are excluded. Default 24. */
    val activeCentralityMonths: Long = 12,
)

/**
 * Final prediction output, including an LLM-generated narrative.
 */
data class SplitPrediction(
    val repo: String,
    val windowedScores: List<WindowedScore>,
    val inflectionPointAt: Instant?,
    val predictedSplitDate: Instant?,
    val confidence: Double,
    val fracture: FractureDetection? = null,
    val exodus: ExodusDetection? = null,
    val backfilled: Boolean = false,
    val narrative: String = "",
)
