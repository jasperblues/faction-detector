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
 * Weighted review graph for a single time window.
 */
data class ReviewGraph(
    val repo: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    val contributors: Set<String>,
    val edges: List<ReviewEdge>,
)

/**
 * A detected community (faction) within the contributor graph.
 */
data class Community(
    val id: Int,
    val members: Set<String>,
)

/**
 * Result of community detection and asymmetry scoring for one time window.
 */
data class FactionAnalysis(
    val graph: ReviewGraph,
    val communities: List<Community>,
    val modularity: Double,
    val asymmetryRatio: Double,
    val splitRiskScore: Double,
)

/**
 * Asymmetry score for a single rolling window — used for trend analysis.
 */
data class WindowedScore(
    val windowStart: Instant,
    val windowEnd: Instant,
    val asymmetryRatio: Double,
    val connectedComponents: Int,
    val modularity: Double,
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
    /** Sharp spike followed by a brief resolution dip, possibly re-escalating afterward —
     *  a fracture event occurred within the window (fork or mass departure). */
    FRACTURE_OCCURRED,
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
 * [totalProjectCentrality] is the sum of all contributor centrality scores in the analysis window.
 * [departureCentralityFraction] is sum(departed centrality) / totalProjectCentrality — normalises
 * the impact of the departure against the full historical contributor pool, so a 31% active-window
 * drop on a mature project with thousands of contributors reads differently than the same drop
 * on a small project where the departed contributors were half the ecosystem.
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
