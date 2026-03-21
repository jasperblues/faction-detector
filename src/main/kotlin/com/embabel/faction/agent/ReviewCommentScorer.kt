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

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.common.ai.model.LlmOptions
import com.embabel.faction.domain.BlockingNature
import com.embabel.faction.domain.CommentSignificance
import com.embabel.faction.domain.PairAnomaly
import com.embabel.faction.domain.ScoredComment
import com.embabel.faction.domain.ScoredPair
import com.embabel.faction.domain.Sentiment
import com.embabel.faction.github.GitHubClient
import com.embabel.faction.github.GitHubReviewComment
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Increment when the comment classification prompt or schema changes — invalidates cached scores. */
private const val COMMENT_SCORE_CACHE_VERSION = 5

/**
 * Increment when the scored-pairs aggregation logic or factionSignal formula changes.
 * Invalidates the scored-pairs cache, forcing a re-run of all pair scoring.
 * The per-comment cache (COMMENT_SCORE_CACHE_VERSION) is a finer-grained invalidation;
 * this version gates the higher-level aggregate.
 */
private const val SCORED_PAIRS_CACHE_VERSION = 4

/** Pair count threshold below which ensemble scoring is used to reduce LLM variance. */
private const val SMALL_PAIR_THRESHOLD = 8

/** Number of independent LLM runs for ensemble scoring of small pair sets. */
private const val ENSEMBLE_RUNS = 3

private val commentCacheMapper: ObjectMapper = jacksonObjectMapper()

/**
 * LLM-powered stage-2 scorer for flagged reviewer→author pairs.
 *
 * For each flagged pair, fetches the actual review comment text + diff hunks from GitHub
 * and asks the LLM to classify each comment as NITPICKY/FAIR/VERY_FAIR/ESSENTIAL
 * and BLOCKING/NON_BLOCKING.
 *
 * A high concentration of NITPICKY + NON_BLOCKING comments in a CHANGES_REQUESTED review
 * is a strong faction fingerprint — the reviewer is blocking work with trivial feedback.
 *
 * ## Caches
 * - Per-comment scores: `~/.faction-cache/{owner}_{repo}_comment_{id}_v{VERSION}.json`
 *   Increment [COMMENT_SCORE_CACHE_VERSION] when the prompt or schema changes.
 * - Scored-pairs aggregate: `~/.faction-cache/{owner}_{repo}_{since}_{until}_scored-pairs_v{VERSION}.json`
 *   Increment [SCORED_PAIRS_CACHE_VERSION] when the factionSignal formula changes.
 *   This cache provides determinism on re-runs and skips all LLM calls entirely.
 *
 * ## Ensemble scoring
 * When the flagged pair count is small (<= [SMALL_PAIR_THRESHOLD]), each comment is scored
 * [ENSEMBLE_RUNS] times and the results are averaged. This reduces LLM variance on sparse
 * graphs where a single marginal comment can flip the classification.
 *
 * ## LLM I/O logging
 * Every live LLM call (not a cache hit) is appended to
 * `~/.faction-logs/{owner}_{repo}_{since}_{until}_scoring.jsonl` for prompt review and tuning.
 */
@Component
class ReviewCommentScorer(
    private val gitHubClient: GitHubClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val llmPool = Executors.newFixedThreadPool(4)
    private val cacheDir: Path = Path.of(System.getProperty("user.home"), ".faction-cache")
    private val logDir: Path = Path.of(System.getProperty("user.home"), ".faction-logs")

    /**
     * Score the top anomalous pairs using LLM analysis of their review comments.
     *
     * Results are loaded from the scored-pairs cache when available, providing determinism
     * across repeated runs. On a cache miss, each comment is scored [ENSEMBLE_RUNS] times
     * when the pair count is small, then the aggregate is written to the scored-pairs cache.
     */
    fun scorePairs(
        owner: String,
        repo: String,
        flaggedPairs: List<PairAnomaly>,
        recentPrNumbers: List<Int>,
        since: Instant,
        until: Instant?,
        context: OperationContext,
    ): List<ScoredPair> {
        // Scored-pairs cache: determinism + fast re-runs
        val pairsCache = pairsCacheFile(owner, repo, since, until)
        if (pairsCache.exists()) {
            val cached = commentCacheMapper.readValue<List<ScoredPair>>(pairsCache.readText())
            logger.info("Scored-pairs cache hit for {}/{} — {} pairs", owner, repo, cached.size)
            return cached
        }

        val isSmall = flaggedPairs.size <= SMALL_PAIR_THRESHOLD
        val runs = if (isSmall) ENSEMBLE_RUNS else 1
        if (isSmall) {
            logger.info(
                "Small pair count ({} <= {}) — {}-run ensemble to reduce LLM variance",
                flaggedPairs.size, SMALL_PAIR_THRESHOLD, runs,
            )
        }

        val result = flaggedPairs.map { pair ->
            logger.info("Stage-2 scoring pair {}→{} (runs={})", pair.reviewer, pair.author, runs)
            val (comments, allCommentById) = collectCommentsForPair(owner, repo, pair.reviewer, pair.author, recentPrNumbers)
            val scored = comments
                .map { comment -> llmPool.submit(Callable { scoreComment(owner, repo, comment, allCommentById, since, until, context, runs) }) }
                .map { it.get() }
            ScoredPair(
                reviewer = pair.reviewer,
                author = pair.author,
                anomalyScore = pair.anomalyScore,
                scoredComments = scored,
            )
        }

        cacheDir.createDirectories()
        pairsCache.writeText(commentCacheMapper.writeValueAsString(result))
        logger.info("Scored-pairs written to cache for {}/{} ({} pairs)", owner, repo, result.size)
        return result
    }

    /**
     * Fetches reviewer comments for the pair and a full id→comment map for thread reconstruction.
     * The map covers all comments across the sampled PRs (all authors), allowing
     * [buildThread] to walk up the [GitHubReviewComment.inReplyToId] chain to root.
     */
    private fun collectCommentsForPair(
        owner: String,
        repo: String,
        reviewer: String,
        author: String,
        prNumbers: List<Int>,
    ): Pair<List<GitHubReviewComment>, Map<Long, GitHubReviewComment>> {
        val allComments = prNumbers.flatMap { gitHubClient.fetchReviewComments(owner, repo, it) }
        val commentById = allComments.associateBy { it.id }
        val reviewerComments = allComments.filter { it.user.login == reviewer }.take(20)
        return reviewerComments to commentById
    }

    /**
     * Walks [GitHubReviewComment.inReplyToId] toward the root, returning ancestors in
     * chronological order (oldest first). The comment itself is not included.
     */
    private fun buildThread(
        comment: GitHubReviewComment,
        allCommentById: Map<Long, GitHubReviewComment>,
    ): List<GitHubReviewComment> {
        val ancestors = mutableListOf<GitHubReviewComment>()
        var parentId = comment.inReplyToId
        while (parentId != null) {
            val parent = allCommentById[parentId] ?: break
            ancestors.add(0, parent)  // prepend → chronological order
            parentId = parent.inReplyToId
        }
        return ancestors
    }

    /**
     * Score a single comment via LLM.
     *
     * When [runs] > 1 (ensemble mode), the per-comment cache is bypassed, the LLM is called
     * [runs] times independently, and the results are averaged before caching. On subsequent
     * runs the cached average is returned directly.
     *
     * When [runs] == 1, the per-comment cache is checked first and the LLM is only called on
     * a miss.
     *
     * All live LLM calls (not cache hits) are appended to the run-scoped scoring log.
     */
    private fun scoreComment(
        owner: String,
        repo: String,
        comment: GitHubReviewComment,
        allCommentById: Map<Long, GitHubReviewComment>,
        since: Instant,
        until: Instant?,
        context: OperationContext,
        runs: Int = 1,
    ): ScoredComment {
        val cacheFile = cacheDir.resolve("${owner}_${repo}_comment_${comment.id}_v${COMMENT_SCORE_CACHE_VERSION}.json")

        // Single-run path: use per-comment cache if available
        if (runs == 1 && cacheFile.exists()) {
            logger.debug("LLM cache hit for comment {} ({}/{})", comment.id, owner, repo)
            val cached = commentCacheMapper.readValue<CommentScoreResult>(cacheFile.readText())
            return cached.toScoredComment(comment)
        }

        val thread = buildThread(comment, allCommentById)
        val prompt = buildPrompt(comment, thread)
        val llm = context.ai().withLlm(LlmOptions.withModel(AnthropicModels.CLAUDE_HAIKU_4_5))

        val results = (1..runs).map { llm.create<CommentScoreResult>(prompt) }
        val averaged = if (results.size == 1) results[0] else averageResults(results)

        cacheDir.createDirectories()
        cacheFile.writeText(commentCacheMapper.writeValueAsString(averaged))

        appendToLog(owner, repo, since, until, comment, thread, prompt, results, averaged)

        return averaged.toScoredComment(comment)
    }

    /**
     * Averages N [CommentScoreResult]s:
     * - sentiment: arithmetic mean
     * - significance and blocking: majority vote (ties broken by most conservative option)
     */
    private fun averageResults(results: List<CommentScoreResult>): CommentScoreResult {
        val avgSentiment = results.map { it.sentiment }.average()
        val significance = results.map { it.significance }
            .groupingBy { it }.eachCount()
            .maxByOrNull { (sig, count) -> count * 10 + sig.ordinal }!!.key
        val blocking = results.map { it.blocking }
            .groupingBy { it }.eachCount()
            .maxByOrNull { (_, count) -> count }!!.key
        return CommentScoreResult(significance = significance, blocking = blocking, sentiment = avgSentiment)
    }

    private fun buildPrompt(comment: GitHubReviewComment, thread: List<GitHubReviewComment> = emptyList()) = buildString {
        appendLine("""
            You are evaluating a GitHub pull request review comment to determine if it represents
            fair technical feedback or nitpicking.

            Rate the comment on three dimensions:

            SIGNIFICANCE — pick exactly one:
            - NITPICKY: style preferences, trivial renaming, personal taste, minor formatting,
              or suggesting a stylistically different but functionally equivalent rewrite of
              working code; when in doubt between NITPICKY and FAIR, ask: does this comment
              have a specific technical reason beyond style or personal preference?
            - FAIR: reasonable feedback that improves clarity or correctness, OR a short
              acknowledgment/agreement ("yes please", "+1", "lgtm", "thanks", "good point",
              brief positive or neutral reactions) — these signal consensus, not criticism
            - VERY_FAIR: important feedback that prevents bugs or significantly improves quality
            - ESSENTIAL: critical issue — security, data loss, correctness bug, breaking change

            BLOCKING — pick exactly one:
            - BLOCKING: the PR genuinely should not merge without addressing this
            - NON_BLOCKING: the PR could reasonably merge even if this is left for later

            SENTIMENT — a decimal in [-1.0, 1.0] reflecting the tone of the comment:
            - -1.0: strongly hostile — dismissive, contemptuous, or aggressive
            - -0.5: negative — frustrated, impatient, or unnecessarily harsh
            -  0.0: neutral — purely technical, no emotional tone
            - +0.5: positive — constructive, encouraging, collaborative
            - +1.0: strongly positive — enthusiastic, mentoring, or appreciative
        """.trimIndent())
        if (thread.isNotEmpty()) {
            appendLine()
            appendLine("## Thread context (earlier comments in this discussion)")
            thread.forEach { c -> appendLine("@${c.user.login}: ${c.body}") }
        }
        appendLine()
        appendLine("## Review comment (by @${comment.user.login})")
        appendLine(comment.body)
        appendLine()
        appendLine("## Code being reviewed (diff hunk)")
        appendLine("```")
        appendLine(comment.diffHunk)
        append("```")
    }

    private fun appendToLog(
        owner: String,
        repo: String,
        since: Instant,
        until: Instant?,
        comment: GitHubReviewComment,
        thread: List<GitHubReviewComment>,
        prompt: String,
        results: List<CommentScoreResult>,
        averaged: CommentScoreResult,
    ) {
        try {
            logDir.createDirectories()
            val sinceEpoch = since.epochSecond
            val untilEpoch = until?.epochSecond ?: Instant.now().truncatedTo(ChronoUnit.DAYS).epochSecond
            val logFile = logDir.resolve("${owner}_${repo}_${sinceEpoch}_${untilEpoch}_scoring.jsonl")
            val entry = mapOf(
                "timestamp" to Instant.now().toString(),
                "commentId" to comment.id,
                "reviewer" to comment.user.login,
                "body" to comment.body,
                "diffHunk" to comment.diffHunk.take(300),
                "thread" to thread.map { mapOf("user" to it.user.login, "body" to it.body) },
                "prompt" to prompt,
                "runs" to results.size,
                "results" to results,
                "averaged" to averaged,
            )
            Files.writeString(logFile, commentCacheMapper.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.warn("Failed to write scoring log for comment {}: {}", comment.id, e.message, e)
        }
    }

    private fun pairsCacheFile(owner: String, repo: String, since: Instant, until: Instant?): Path {
        val sinceEpoch = since.epochSecond
        val untilEpoch = until?.epochSecond ?: Instant.now().truncatedTo(ChronoUnit.DAYS).epochSecond
        return cacheDir.resolve("${owner}_${repo}_${sinceEpoch}_${untilEpoch}_scored-pairs_v${SCORED_PAIRS_CACHE_VERSION}.json")
    }
}

/** Intermediate structured response from the LLM comment scorer. */
private data class CommentScoreResult(
    val significance: CommentSignificance,
    val blocking: BlockingNature,
    val sentiment: Double,
) {
    fun toScoredComment(comment: GitHubReviewComment) = ScoredComment(
        body = comment.body,
        diffHunk = comment.diffHunk,
        significance = significance,
        blocking = blocking,
        sentiment = Sentiment.of(sentiment),
    )
}