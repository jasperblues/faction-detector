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
package com.embabel.faction.github

import com.embabel.faction.domain.ReviewEdge
import com.embabel.faction.domain.ReviewState
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.client.RestClient
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubPr(
    val number: Int,
    val user: GitHubUser,
    @JsonProperty("merged_at") val mergedAt: String?,
    @JsonProperty("created_at") val createdAt: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubUser(val login: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubReview(
    val user: GitHubUser,
    val state: String,
    @JsonProperty("submitted_at") val submittedAt: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubComment(val id: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubIssueComment(
    val id: Long,
    val user: GitHubUser,
    val body: String,
    @JsonProperty("created_at") val createdAt: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubReviewComment(
    val id: Long,
    val user: GitHubUser,
    val body: String,
    @JsonProperty("diff_hunk") val diffHunk: String,
    @JsonProperty("pull_request_review_id") val reviewId: Long?,
    @JsonProperty("created_at") val createdAt: String? = null,
)

/**
 * GitHub REST API client.
 * Handles pagination via Link headers and exponential backoff on rate limits.
 */
private val cacheMapper: ObjectMapper = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

@Component
@EnableConfigurationProperties(GitHubProperties::class)
class GitHubClient(private val properties: GitHubProperties) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val cacheDir: Path = Path.of(System.getProperty("user.home"), ".faction-cache")

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.token}")
        .defaultHeader("Accept", "application/vnd.github.v3+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .build()

    /**
     * Crawl all PR review edges for a repo since the given timestamp.
     * Returns one ReviewEdge per review event.
     */
    fun fetchReviewEdges(owner: String, repo: String, since: Instant, until: Instant? = null): List<ReviewEdge> {
        val cacheFile = cacheFile(owner, repo, since, until)
        if (cacheFile.exists()) {
            logger.info("Cache hit for {}/{} [{} → {}] — skipping GitHub fetch", owner, repo, since, until ?: "now")
            return cacheMapper.readValue<List<ReviewEdge>>(cacheFile.readText())
                .filter { it.reviewer.isHumanContributor() && it.author.isHumanContributor() }
        }

        logger.info("Crawling PRs for {}/{} since {} until {} (mode: {})", owner, repo, since, until ?: "now", properties.edgeSource)
        val prs = fetchAllPrs(owner, repo, since, until)
        logger.info("Found {} merged PRs", prs.size)

        val reviewEdges = if (properties.edgeSource != EdgeSource.COMMENTS) buildReviewEdges(owner, repo, prs) else emptyList()
        val commentEdges = if (properties.edgeSource != EdgeSource.REVIEWS) buildCommentEdges(owner, repo, prs, reviewEdges) else emptyList()
        val total = reviewEdges + commentEdges
        logger.info("Built {} edges ({} review, {} comment) from {} PRs", total.size, reviewEdges.size, commentEdges.size, prs.size)

        if (total.isNotEmpty()) {
            cacheDir.createDirectories()
            cacheFile.writeText(cacheMapper.writeValueAsString(total))
            logger.info("Cached {} edges to {}", total.size, cacheFile)
        } else {
            logger.warn("Fetch returned no edges — skipping cache write for {}/{}", owner, repo)
        }
        return total
    }

    private fun cacheFile(owner: String, repo: String, since: Instant, until: Instant?): Path {
        val key = "${owner}_${repo}_${since.epochSecond}_${until?.epochSecond ?: "now"}.json"
        return cacheDir.resolve(key)
    }

    private fun String.isHumanContributor() = this != "ghost"
        && !this.endsWith("[bot]")
        && !this.endsWith("-bot")
        && !this.contains("-bot-")

    private fun buildReviewEdges(owner: String, repo: String, prs: List<GitHubPr>): List<ReviewEdge> {
        return prs.flatMapIndexed { idx, pr ->
            if (idx % 50 == 0) logger.info("Fetching reviews for PR {}/{} (PR #{})", idx + 1, prs.size, pr.number)
            val reviews = fetchReviews(owner, repo, pr.number)
            val commentCount = fetchCommentCount(owner, repo, pr.number)
            val prCreated = Instant.parse(pr.createdAt)
            val prMerged = pr.mergedAt?.let { Instant.parse(it) }

            reviews.mapNotNull { review ->
                if (review.user.login == pr.user.login) return@mapNotNull null
                if (!review.user.login.isHumanContributor()) return@mapNotNull null
                val reviewedAt = Instant.parse(review.submittedAt)
                val hoursToFirstReview = java.time.Duration.between(prCreated, reviewedAt).toHours().toDouble()
                val daysMerged = prMerged?.let {
                    java.time.Duration.between(reviewedAt, it).toDays().toDouble()
                }
                ReviewEdge(
                    reviewer = review.user.login,
                    author = pr.user.login,
                    repo = "$owner/$repo",
                    prNumber = pr.number,
                    timestamp = reviewedAt,
                    commentCount = commentCount,
                    state = parseState(review.state),
                    hoursToFirstReview = hoursToFirstReview,
                    daysMergedAfterReview = daysMerged,
                )
            }
        }
    }

    /**
     * Builds edges from inline PR comments, used when formal review events are absent.
     * One edge per unique commenter per PR. In [EdgeSource.BOTH] mode, only adds edges
     * where no review edge already exists for that reviewer+PR combination.
     */
    private fun buildCommentEdges(
        owner: String,
        repo: String,
        prs: List<GitHubPr>,
        existingEdges: List<ReviewEdge>,
    ): List<ReviewEdge> {
        val reviewedPrsByReviewer = existingEdges.groupBy { it.reviewer }.mapValues { (_, edges) ->
            edges.mapNotNull { it.prNumber }.toSet()
        }
        return prs.flatMapIndexed { idx, pr ->
            if (idx % 50 == 0) logger.info("Fetching comments for PR {}/{} (PR #{})", idx + 1, prs.size, pr.number)
            val prCreated = Instant.parse(pr.createdAt)
            val prMerged = pr.mergedAt?.let { Instant.parse(it) }
            // Merge inline diff comments + general issue thread comments
            val allCommenters: Map<String, List<Instant>> = buildMap {
                fetchReviewComments(owner, repo, pr.number)
                    .filter { it.user.login != pr.user.login && it.user.login.isHumanContributor() }
                    .groupBy { it.user.login }
                    .forEach { (login, comments) ->
                        merge(login, comments.mapNotNull { it.createdAt?.let { ts -> Instant.parse(ts) } }) { a, b -> a + b }
                    }
                fetchIssueComments(owner, repo, pr.number)
                    .filter { it.user.login != pr.user.login && it.user.login.isHumanContributor() }
                    .groupBy { it.user.login }
                    .forEach { (login, comments) ->
                        merge(login, comments.map { Instant.parse(it.createdAt) }) { a, b -> a + b }
                    }
            }
            allCommenters
                .mapNotNull { (commenter, timestamps) ->
                    // In BOTH mode, skip if a formal review edge already covers this reviewer+PR
                    if (reviewedPrsByReviewer[commenter]?.contains(pr.number) == true) return@mapNotNull null
                    val firstCommentAt = timestamps.minOrNull() ?: prCreated
                    val hoursToFirst = java.time.Duration.between(prCreated, firstCommentAt).toHours().toDouble()
                    val daysMerged = prMerged?.let {
                        java.time.Duration.between(firstCommentAt, it).toDays().toDouble()
                    }
                    ReviewEdge(
                        reviewer = commenter,
                        author = pr.user.login,
                        repo = "$owner/$repo",
                        prNumber = pr.number,
                        timestamp = firstCommentAt,
                        commentCount = timestamps.size,
                        state = ReviewState.COMMENTED,
                        hoursToFirstReview = hoursToFirst,
                        daysMergedAfterReview = daysMerged,
                    )
                }
        }
    }

    private fun fetchAllPrs(owner: String, repo: String, since: Instant, until: Instant? = null): List<GitHubPr> {
        // For historical windows sort oldest-first so we reach the target range immediately.
        // For recent windows sort newest-first and stop when we go past `since`.
        // Use asc (oldest-first) only for deep historical windows where the target period
        // is near the repo's beginning — avoids paging through years of modern activity.
        // Use desc (newest-first) for recent or mid-period windows — skip from now back to `until`.
        // Threshold: 8 years covers pre-2018 deep history (e.g. Node 2013-2015) but not
        // more recent windows (e.g. Vue 2018-2020) where desc is more efficient.
        val historical = until != null && until.isBefore(Instant.now().minus(8 * 365L, java.time.temporal.ChronoUnit.DAYS))
        val sort = if (historical) "sort=created&direction=asc" else "sort=created&direction=desc"

        val results = mutableListOf<GitHubPr>()
        var page = 1
        while (results.size < properties.maxPrsPerRepo) {
            val url = "/repos/$owner/$repo/pulls?state=closed&per_page=100&page=$page&$sort"
            logger.info("Fetching PR page {} ({} matched so far)...", page, results.size)
            val batch = getWithRateLimitHandling<List<GitHubPr>>(url) ?: break
            if (batch.isEmpty()) break
            val filtered = batch.filter { pr ->
                pr.mergedAt != null &&
                    Instant.parse(pr.createdAt).isAfter(since) &&
                    (until == null || Instant.parse(pr.createdAt).isBefore(until))
            }
            results.addAll(filtered)
            val newestInBatch = batch.last().createdAt.take(10)
            logger.info("Page {}: {} in window (newest in batch: {})", page, filtered.size, newestInBatch)
            if (historical) {
                if (until != null && batch.last().let { Instant.parse(it.createdAt).isAfter(until) }) break
            } else {
                if (batch.last().let { Instant.parse(it.createdAt).isBefore(since) }) break
            }
            page++
        }
        return results
    }

    private fun fetchReviews(owner: String, repo: String, prNumber: Int): List<GitHubReview> =
        getWithRateLimitHandling<List<GitHubReview>>("/repos/$owner/$repo/pulls/$prNumber/reviews") ?: emptyList()

    private fun fetchCommentCount(owner: String, repo: String, prNumber: Int): Int =
        getWithRateLimitHandling<List<GitHubComment>>("/repos/$owner/$repo/pulls/$prNumber/comments")?.size ?: 0

    /**
     * Fetch full review comment bodies and diff hunks for a PR.
     * Used in the stage-2 LLM scoring pass for flagged reviewer→author pairs.
     * Results are cached per PR so stage-2 re-runs never hit the GitHub API again.
     */
    fun fetchReviewComments(owner: String, repo: String, prNumber: Int): List<GitHubReviewComment> {
        val cacheFile = cacheDir.resolve("${owner}_${repo}_pr${prNumber}_comments.json")
        if (cacheFile.exists()) {
            return cacheMapper.readValue(cacheFile.readText())
        }
        val result = getWithRateLimitHandling<List<GitHubReviewComment>>(
            "/repos/$owner/$repo/pulls/$prNumber/comments"
        ) ?: return emptyList()  // don't cache on failure
        cacheDir.createDirectories()
        cacheFile.writeText(cacheMapper.writeValueAsString(result))
        return result
    }

    private fun fetchIssueComments(owner: String, repo: String, prNumber: Int): List<GitHubIssueComment> =
        getWithRateLimitHandling<List<GitHubIssueComment>>("/repos/$owner/$repo/issues/$prNumber/comments") ?: emptyList()

    private inline fun <reified T> getWithRateLimitHandling(url: String): T? {
        repeat(3) { attempt ->
            try {
                return restClient.get().uri(url).retrieve().body(object : ParameterizedTypeReference<T>() {})
            } catch (e: Exception) {
                when {
                    e.message?.contains("403") == true || e.message?.contains("429") == true -> {
                        logger.warn("Rate limited, backing off {}ms (attempt {})", properties.rateLimitBackoffMs, attempt + 1)
                        Thread.sleep(properties.rateLimitBackoffMs)
                    }
                    e.message?.contains("RST_STREAM") == true || e.message?.contains("I/O error") == true -> {
                        val backoff = 2_000L * (attempt + 1)
                        logger.warn("Transient I/O error (attempt {}), retrying in {}ms: {}", attempt + 1, backoff, e.message)
                        Thread.sleep(backoff)
                    }
                    else -> {
                        logger.error("Error fetching {}: {}", url, e.message)
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun parseState(raw: String): ReviewState = when (raw.uppercase()) {
        "APPROVED" -> ReviewState.APPROVED
        "CHANGES_REQUESTED" -> ReviewState.CHANGES_REQUESTED
        else -> ReviewState.COMMENTED
    }
}
