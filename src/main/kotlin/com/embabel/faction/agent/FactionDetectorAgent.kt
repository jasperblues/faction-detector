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

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.faction.domain.ExodusDetection
import com.embabel.faction.domain.FractureDetection
import com.embabel.faction.domain.PairAnomaly
import com.embabel.faction.domain.ReviewEdge
import com.embabel.faction.domain.ScoredPair
import com.embabel.faction.domain.SplitPrediction
import com.embabel.faction.domain.TensionPattern
import com.embabel.faction.domain.WindowedScore
import com.embabel.faction.github.GitHubClient
import com.embabel.faction.graph.GdsService
import com.embabel.faction.graph.GraphBuilder
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit


private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

/**
 * Parses the space-separated shell input into an [AnalysisRequest].
 * Accepted formats:
 *   - `owner/repo` — defaults to 180-day window ending now
 *   - `owner/repo 90` — 90-day window ending now
 *   - `owner/repo 2013-06-01` — from that date to now
 *   - `owner/repo 2013-06-01 2015-06-01` — explicit date range
 */
internal fun parseAnalysisInput(input: String): AnalysisRequest {
    val parts = input.trim().split("\\s+".toRegex())
    val repoParts = parts[0].split("/")
    val owner = repoParts[0]
    val repo = repoParts.getOrElse(1) { repoParts[0] }

    val since: Instant
    val until: Instant?

    if (parts.getOrNull(1)?.matches(DATE_PATTERN) == true) {
        since = LocalDate.parse(parts[1]).atStartOfDay(ZoneOffset.UTC).toInstant()
        until = parts.getOrNull(2)?.takeIf { it.matches(DATE_PATTERN) }
            ?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }
    } else {
        val days = parts.getOrNull(1)?.toIntOrNull() ?: 180
        since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        until = null
    }

    return AnalysisRequest(owner = owner, repo = repo, since = since, until = until)
}

/**
 * Input parsed from user's shell command: owner/repo and history window.
 * [runId] is a short unique identifier that namespaces all Neo4j nodes and relationships
 * for this run, allowing multiple analyses to coexist in the same database.
 */
data class AnalysisRequest(
    val owner: String,
    val repo: String,
    val since: Instant,
    val until: Instant? = null,
    val runId: String = java.util.UUID.randomUUID().toString().take(8),
)

/**
 * Final agent output with LLM narrative and windowed scores.
 */
data class SplitPredictionResult(
    val prediction: SplitPrediction,
) : HasContent {
    override val content: String get() = buildString {
        appendLine("## Faction Analysis: ${prediction.repo}")
        appendLine()
        val f = prediction.fracture
        val e = prediction.exodus
        if (f != null) {
            val rows = mutableListOf<Pair<String, String>>()
            rows += "Pattern" to f.pattern.name
            rows += "Severity" to f.severity.name
            rows += "Confidence" to "${"%.0f".format(prediction.confidence * 100)}%"
            rows += "Peak tension" to "${f.peakDate.toString().take(10)} (asymmetry ${"%.2f".format(f.peakAsymmetry)})"
            rows += "Status" to when {
                f.isReEscalating -> "RESOLVED — POST-EXODUS RE-ESCALATION"
                f.pattern == TensionPattern.ATTRITION -> "RESOLVED — NATURAL ATTRITION"
                f.isResolved -> "RESOLVED"
                f.isRising -> "UNRESOLVED — RISING"
                else -> "UNRESOLVED"
            }
            f.fractureDate?.let { rows += "Fracture event" to it.toString().take(10) }
            f.resolutionDate?.let { rows += "Resolution" to it.toString().take(10) }
            if (prediction.backfilled) rows += "Baseline" to "backfilled +6mo prior"
            if (e != null) {
                rows += "Exodus date" to e.inferredDate.toString().take(10)
                rows += "Mass drop" to "${"%.0f".format(e.dropFraction * 100)}%  (${"%.1f".format(e.weightedMassBefore)} → ${"%.1f".format(e.weightedMassAfter)})"
                val departed = (e.dropFraction * 100).roundToInt()
                rows += "Split ratio" to "$departed:${100 - departed}  ${if (departed >= 40) "— near-equal split, departing faction was viable" else if (departed >= 30) "— minority departure" else "— small departure"}"
                rows += "Core impact" to "${"%.1f".format(e.departureCentralityFraction * 100)}% of total project centrality"
                rows += "Departed" to e.departedContributors.take(5).joinToString("  ") { "${it.login}(${it.centrality.toInt()})" }
            }
            prediction.predictedSplitDate?.let { rows += "Predicted split" to it.toString().take(10) }
            val keyWidth = rows.maxOf { it.first.length }
            val valWidth = rows.maxOf { it.second.length }
            val sep = "+-${"-".repeat(keyWidth)}-+-${"-".repeat(valWidth)}-+"
            appendLine(sep)
            rows.forEach { (k, v) -> appendLine("| ${k.padEnd(keyWidth)} | ${v.padEnd(valWidth)} |") }
            appendLine(sep)
        } else {
            appendLine("Confidence: ${"%.0f".format(prediction.confidence * 100)}%")
        }
        appendLine()
        appendLine(prediction.narrative)
        appendLine()
        appendLine("### Windowed asymmetry scores")
        prediction.windowedScores.forEach { w ->
            appendLine(
                "  ${w.windowStart.toString().take(10)}–${w.windowEnd.toString().take(10)}" +
                "  asymmetry=${w.asymmetryRatio.format(2)}" +
                "  modularity=${w.modularity.format(2)}"
            )
        }
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}

@Agent(description = "Detect contributor factions in a GitHub repo and predict project splits")
@Profile("!test")
class FactionDetectorAgent(
    private val gitHubClient: GitHubClient,
    private val graphBuilder: GraphBuilder,
    private val gdsService: GdsService,
    private val asymmetryScorer: AsymmetryScorer,
    private val pairAnomalyScorer: PairAnomalyScorer,
    private val reviewCommentScorer: ReviewCommentScorer,
    private val fractureDetector: FractureDetector,
    private val exodusDetector: ExodusDetector,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Action
    fun parseRequest(userInput: UserInput): AnalysisRequest = parseAnalysisInput(userInput.content)

    @Action
    fun fetchAndPersist(request: AnalysisRequest): FetchResult {
        val edges = gitHubClient.fetchReviewEdges(request.owner, request.repo, request.since, request.until)
        graphBuilder.persist(edges, request.runId)
        val windowedScores = asymmetryScorer.score(edges, request.since, until = request.until)
        val flaggedPairs = pairAnomalyScorer.topAnomalies(edges)
        gdsService.writeAnomalyScores(flaggedPairs, request.runId)
        return FetchResult(
            owner = request.owner,
            repo = "${request.owner}/${request.repo}",
            since = request.since,
            until = request.until,
            windowedScores = windowedScores,
            flaggedPairs = flaggedPairs,
            recentPrNumbers = edges.mapNotNull { it.prNumber }.distinct().take(50),
            edges = edges,
            runId = request.runId,
        )
    }

    @Action
    fun scoreFlaggedPairs(fetch: FetchResult, context: OperationContext): WindowedScores {
        val scoredPairs = if (fetch.flaggedPairs.isNotEmpty()) {
            reviewCommentScorer.scorePairs(
                owner = fetch.owner,
                repo = fetch.repo.substringAfter("/"),
                flaggedPairs = fetch.flaggedPairs,
                recentPrNumbers = fetch.recentPrNumbers,
                context = context,
            )
        } else emptyList()
        gdsService.writeFactionSignals(scoredPairs, fetch.runId)
        gdsService.runLouvain(fetch.runId)
        val communityAssignments = gdsService.queryCommunities(fetch.runId)
        return WindowedScores(
            repo = fetch.repo,
            since = fetch.since,
            until = fetch.until,
            scores = fetch.windowedScores,
            scoredPairs = scoredPairs,
            communityAssignments = communityAssignments,
            edges = fetch.edges,
            runId = fetch.runId,
        )
    }

    @AchievesGoal(description = "Faction analysis with split prediction narrative complete")
    @Action
    fun generateNarrative(windowed: WindowedScores, context: OperationContext): SplitPredictionResult {
        var scores = windowed.scores
        var allEdges = windowed.edges
        var backfilled = false

        var exodus = exodusDetector.detect(allEdges)
        var fracture = fractureDetector.detect(scores, exodus)

        // Backfill: if we classified EXODUS with a confirmed departure but the window opens
        // mid-tension (first 8 windows already elevated), fetch 6 months prior to find the
        // true calm baseline. A single genuine calm period before the cluster changes the
        // classification to FRACTURE_OCCURRED.
        val earlyMean = scores.take(8).map { it.asymmetryRatio }.average().takeIf { !it.isNaN() } ?: 0.0
        if (fracture.pattern == TensionPattern.EXODUS
            && exodus != null
            && earlyMean > 0.5
        ) {
            val owner = windowed.repo.substringBefore("/")
            val repo = windowed.repo.substringAfter("/")
            val backfillSince = windowed.since.minus(180, ChronoUnit.DAYS)
            logger.info("Backfilling baseline: fetching {}/{} from {} to {}", owner, repo, backfillSince.toString().take(10), windowed.since.toString().take(10))
            val backfillEdges = gitHubClient.fetchReviewEdges(owner, repo, backfillSince, windowed.since)
            if (backfillEdges.isNotEmpty()) {
                logger.info("Backfill complete: {} edges, re-running fracture detection", backfillEdges.size)
                allEdges = backfillEdges + allEdges
                scores = asymmetryScorer.score(allEdges, backfillSince, until = windowed.until)
                exodus = exodusDetector.detect(allEdges)
                fracture = fractureDetector.detect(scores, exodus)
                backfilled = true
            }
        }

        val corePairs = windowed.scoredPairs.filter { p ->
            windowed.communityAssignments[p.reviewer] != null &&
                windowed.communityAssignments[p.author] != null
        }
        val crossPairs = corePairs.filter { p ->
            windowed.communityAssignments[p.reviewer] != windowed.communityAssignments[p.author]
        }
        val crossCommunityFraction = if (corePairs.isEmpty()) 0.0 else
            crossPairs.size.toDouble() / corePairs.size
        val crossAdversarialSignal = if (crossPairs.isEmpty()) 0.0 else
            crossPairs.map { it.factionSignal }.average()
        // Cross-community fraction × adversarial signal on those pairs: captures the original
        // hypothesis that cliques review outsiders but with high faction tension
        val crossCommunityScore = crossCommunityFraction * crossAdversarialSignal

        // Faction formation score: are the top 2 communities comparable in *power* (centrality)?
        // Raw member count is misleading — 3 TSC members outweigh 10 drive-bys.
        // High = two power-balanced factions facing off.
        // Low = one dominant community + scattered periphery, or single high-power exodus.
        // (Single high-power departure with no opposing faction = EXODUS, not FRACTURE_IMMINENT.)
        // TODO: consider computing centrality in Neo4j (GDS degree centrality) rather than
        //       re-deriving from edges here — would also enable PageRank weighting later.
        val centrality = windowed.edges
            .flatMap { listOf(it.reviewer, it.author) }
            .groupingBy { it }
            .eachCount()
            .mapValues { it.value.toDouble() }
        val communityPower = windowed.communityAssignments.entries
            .groupBy { it.value }
            .mapValues { (_, members) -> members.sumOf { centrality[it.key] ?: 0.0 } }
            .entries.sortedByDescending { it.value }
        val top1Power = communityPower.firstOrNull()?.value ?: 0.0
        val top2Power = communityPower.drop(1).firstOrNull()?.value ?: 0.0
        val totalPower = communityPower.sumOf { it.value }
        val factionBalance = if (top1Power + top2Power == 0.0) 0.0 else
            (2.0 * minOf(top1Power, top2Power)) / (top1Power + top2Power)
        val factionCoverage = if (totalPower == 0.0) 0.0 else
            (top1Power + top2Power) / totalPower
        val factionFormationScore = factionBalance * factionCoverage

        // Peak modularity gates confidence: low modularity = Louvain found no meaningful structure
        val peakModularity = scores.maxOfOrNull { it.modularity } ?: 0.0

        val avgFactionSignal = windowed.scoredPairs.map { it.factionSignal }.average()
            .takeIf { !it.isNaN() } ?: 0.0

        // Post-exodus asymmetry delta: did the departure structurally change the graph?
        // |delta| near zero = normal churn. Large |delta| = structurally load-bearing departure.
        val asymmetryDelta = if (exodus != null) {
            val exodusDate = exodus.inferredDate
            val preWindows = scores.filter { it.windowStart.isBefore(exodusDate) }.takeLast(4)
            val postWindows = scores.filter { !it.windowStart.isBefore(exodusDate) }.take(4)
            if (preWindows.isNotEmpty() && postWindows.isNotEmpty()) {
                val preMean = preWindows.map { it.asymmetryRatio }.average()
                val postMean = postWindows.map { it.asymmetryRatio }.average()
                postMean - preMean
            } else null
        } else null

        val confidence = computeConfidence(fracture, crossCommunityScore, avgFactionSignal, exodus, factionFormationScore, peakModularity, asymmetryDelta)

        val pairSummary = windowed.scoredPairs
            .sortedByDescending { it.factionSignal }
            .take(5)
            .joinToString("\n") { p ->
                val crossCommunity = windowed.communityAssignments[p.reviewer] != null &&
                    windowed.communityAssignments[p.author] != null &&
                    windowed.communityAssignments[p.reviewer] != windowed.communityAssignments[p.author]
                val tag = if (crossCommunity) " [CROSS-COMMUNITY]" else ""
                "  ${p.reviewer}→${p.author}: factionSignal=${"%.2f".format(p.factionSignal)}, anomaly=${"%.2f".format(p.anomalyScore)}$tag"
            }

        val communityBreakdown = windowed.communityAssignments.entries
            .groupBy { it.value }
            .entries.sortedBy { it.key }
            .joinToString("\n") { (id, members) ->
                "  Community $id: ${members.map { it.key }.sorted().joinToString(", ")}"
            }

        val fractureContext = buildString {
            appendLine("Fracture detection:")
            appendLine("  Pattern: ${fracture.pattern} | Severity: ${fracture.severity}")
            appendLine("  Peak tension: week of ${fracture.peakDate.toString().take(10)} (asymmetry: ${"%.2f".format(fracture.peakAsymmetry)})")
            if (fracture.isResolved) {
                fracture.fractureDate?.let { appendLine("  Fracture event: ~${it.toString().take(10)} (drop followed peak)") }
                fracture.resolutionDate?.let { appendLine("  Resolution: ${it.toString().take(10)} (asymmetry returned to baseline)") }
                val resolvedStatus = when (fracture.pattern) {
                    TensionPattern.ATTRITION -> "RESOLVED — natural contributor lifecycle turnover; asymmetry rose as coverage thinned, not from adversarial dynamics. Call to action: succession planning and knowledge transfer, NOT governance intervention."
                    else -> "RESOLVED — tension released, likely fork or mass departure"
                }
                appendLine("  Status: $resolvedStatus")
            } else {
                appendLine("  Status: UNRESOLVED — peak is current, no resolution signal detected")
                if (fracture.isRising) appendLine("  Trajectory: RISING — asymmetry increasing in recent windows")
            }
        }

        val exodusContext = if (exodus != null) buildString {
            appendLine("Contributor exodus detection:")
            appendLine("  Inferred departure event: ${exodus.inferredDate.toString().take(10)}")
            appendLine("  Weighted contributor mass: ${"%.1f".format(exodus.weightedMassBefore)} → ${"%.1f".format(exodus.weightedMassAfter)} (${"%.0f".format(exodus.dropFraction * 100)}% drop)")
            appendLine("  Core impact: ${"%.1f".format(exodus.departureCentralityFraction * 100)}% of total project centrality (pool: ${"%.0f".format(exodus.totalProjectCentrality)})")
            if (asymmetryDelta != null) {
                val direction = when {
                    asymmetryDelta > 0.05 -> "ROSE after departure (graph structurally destabilised)"
                    asymmetryDelta < -0.05 -> "FELL after departure (departed contributors were tension source)"
                    else -> "UNCHANGED after departure (likely routine churn)"
                }
                appendLine("  Post-exodus asymmetry delta: ${"%.2f".format(asymmetryDelta)} — $direction")
            }
            appendLine("  Departed contributors (by centrality):")
            exodus.departedContributors.take(10).forEach { d ->
                appendLine("    ${d.login} (centrality=${"%.0f".format(d.centrality)}, last active ${d.lastActiveWindow.toString().take(10)})")
            }
        } else "No coordinated departure detected."

        val confidenceGuidance = when {
            confidence >= 0.80 -> "High confidence — state findings definitively."
            confidence >= 0.60 -> "Moderate confidence — present findings as likely but acknowledge uncertainty."
            confidence >= 0.40 -> "Low confidence — treat as preliminary signals only. Avoid alarming language. Note that structural factors (large project size, bot activity, hub-and-spoke contributor topology) may explain the patterns as well as genuine faction tension."
            else -> "Very low confidence — do NOT use alarming language or make definitive claims. Describe what the data shows structurally, note multiple alternative explanations, and recommend monitoring rather than intervention."
        }

        val narrative = context.ai().withAutoLlm().generateText(
            """
            You are an expert in open-source community dynamics.
            Analyse the following contributor faction data for ${windowed.repo}.

            $fractureContext
            $exodusContext
            Confidence score: ${"%.0f".format(confidence * 100)}% — $confidenceGuidance

            Rolling asymmetry scores (higher = more faction divergence):
            ${scores.joinToString("\n") { "  ${it.windowStart.toString().take(10)}: asymmetry=${it.asymmetryRatio}" }}

            ${if (communityBreakdown.isNotBlank()) "GDS Louvain community structure (anomaly-weighted):\n$communityBreakdown" else ""}

            ${if (pairSummary.isNotBlank()) "Top reviewer→author pairs by faction signal (NITPICKY+NON_BLOCKING rate):\n$pairSummary" else ""}

            Write a concise 2-3 paragraph analysis:
            1. ${if (fracture.pattern == TensionPattern.ATTRITION) "Who left and why — frame as natural contributor lifecycle, not faction conflict. Reference specific contributors and their likely motivations." else "What factions formed and why — or what is currently building — referencing specific contributors and community groupings"}
            2. Risk level consistent with the fracture pattern AND the confidence score above
            3. ${when (fracture.pattern) {
                TensionPattern.ATTRITION -> "Concrete succession planning and knowledge transfer recommendations. Do NOT suggest governance intervention or frame this as a political conflict."
                else -> if (fracture.isResolved) "What this historical event tells us about the project's trajectory" else "Recommendations for maintainers to de-escalate"
            }}
            """.trimIndent()
        )

        val prediction = SplitPrediction(
            repo = windowed.repo,
            windowedScores = scores,
            inflectionPointAt = fracture.peakDate,
            predictedSplitDate = if (!fracture.isResolved && fracture.isRising)
                Instant.now().plus(180, ChronoUnit.DAYS) else null,
            confidence = confidence,
            fracture = fracture,
            exodus = exodus,
            backfilled = backfilled,
            narrative = narrative,
        )
        return SplitPredictionResult(prediction)
    }

    private fun computeConfidence(
        fracture: FractureDetection,
        crossCommunityScore: Double,
        avgFactionSignal: Double,
        exodus: ExodusDetection? = null,
        factionFormationScore: Double = 0.0,
        peakModularity: Double = 0.0,
        asymmetryDelta: Double? = null,
    ): Double {
        // Past fracture: exodus drop fraction is the signal (how many key people left)
        // Pending fracture: cross-community adversarial reviews are the leading indicator
        val communitySignal = exodus?.dropFraction ?: crossCommunityScore
        val base = fracture.peakAsymmetry * 0.5 + communitySignal * 0.3 + avgFactionSignal * 0.2
        return when (fracture.pattern) {
            TensionPattern.FRACTURE_OCCURRED ->
                (base + 0.15).coerceIn(0.0, 1.0)
            TensionPattern.EXODUS -> {
                // Large projects always have contributors leaving for normal reasons (new jobs, burnout).
                // What distinguishes a faction exodus from routine churn is whether the departure
                // structurally changed the review graph. If asymmetry barely moves after the
                // departure, it was churn. If asymmetry shifts significantly (either direction),
                // the departure was load-bearing — the departed contributors were either holding
                // tension together or bridging across communities.
                // Normalise |delta| against 0.30 (a 30-point swing is clearly structural).
                // Floor at 0.5 so we never fully suppress a confirmed departure with mass drop.
                // Null delta (insufficient post-exodus windows) = no penalty applied.
                val structuralFactor = if (asymmetryDelta != null)
                    (Math.abs(asymmetryDelta) / 0.30).coerceIn(0.0, 1.0).let { it * 0.5 + 0.5 }
                else 1.0
                ((base + 0.10) * structuralFactor).coerceIn(0.0, 1.0)
            }
            TensionPattern.FRACTURE_IMMINENT -> {
                val risingBonus = if (fracture.isRising) 0.10 else 0.0
                val corroborationBonus = if (fracture.peakAsymmetry >= 0.8 && factionFormationScore >= 0.3) 0.10 else 0.0
                val raw = fracture.peakAsymmetry * 0.40 + factionFormationScore * 0.25 +
                    avgFactionSignal * 0.15 + risingBonus + corroborationBonus
                // Gate on modularity: low modularity = Louvain found no meaningful community
                // structure — likely hub-and-spoke topology, not genuine faction formation.
                // Normalize against 0.20 (observed peak in confirmed fracture events).
                val modularityFactor = (peakModularity / 0.20).coerceIn(0.0, 1.0)
                (raw * modularityFactor).coerceIn(0.0, 1.0)
            }
            TensionPattern.ATTRITION -> {
                // Natural turnover: confidence reflects departure scale (succession planning urgency),
                // not adversarial tension. Cap at 0.65 — we never have high confidence that
                // natural churn is a faction event. Floor at 0.30 so the signal isn't dismissed.
                val departureFraction = exodus?.dropFraction ?: 0.0
                (0.30 + departureFraction * 0.5).coerceIn(0.30, 0.65)
            }
            TensionPattern.STABLE ->
                base.coerceIn(0.0, 1.0)
        }
    }
}

/** Intermediate result after fetching and persisting edges. */
data class FetchResult(
    val owner: String,
    val repo: String,
    val since: Instant,
    val until: Instant?,
    val windowedScores: List<WindowedScore>,
    val flaggedPairs: List<PairAnomaly>,
    val recentPrNumbers: List<Int>,
    val edges: List<ReviewEdge>,
    val runId: String,
)

/** Intermediate domain object carrying windowed scores, LLM-scored pairs, and GDS community assignments. */
data class WindowedScores(
    val repo: String,
    val since: Instant,
    val until: Instant?,
    val scores: List<WindowedScore>,
    val scoredPairs: List<ScoredPair> = emptyList(),
    /** Login → communityId from GDS Louvain on the anomaly-weighted review graph. */
    val communityAssignments: Map<String, Int> = emptyMap(),
    val edges: List<ReviewEdge> = emptyList(),
    val runId: String,
)
