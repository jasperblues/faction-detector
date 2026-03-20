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

/** Increment when the edge schema or filtering logic changes — invalidates all prior edge caches. */
internal const val EDGE_CACHE_VERSION = 2

/**
 * Removes any edges whose timestamp falls outside [since, until).
 * Guards against per-PR review caches that were populated by a wider window run —
 * a PR created before `until` may have reviews submitted after `until`, and those
 * cached reviews must be excluded from the bounded analysis.
 */
internal fun List<ReviewEdge>.filterEdgesByWindow(since: Instant, until: Instant?): List<ReviewEdge> =
    filter { edge ->
        !edge.timestamp.isBefore(since) && (until == null || edge.timestamp.isBefore(until))
    }

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
     * Crawl all PR comment edges for a repo within the given time window.
     * Returns one ReviewEdge per unique commenter per PR.
     */
    fun fetchReviewEdges(owner: String, repo: String, since: Instant, until: Instant? = null): List<ReviewEdge> {
        val cacheFile = cacheFile(owner, repo, since, until)
        if (cacheFile.exists()) {
            logger.info("Cache hit for {}/{} [{} → {}] — skipping GitHub fetch", owner, repo, since, until ?: "now")
            return cacheMapper.readValue<List<ReviewEdge>>(cacheFile.readText())
                .filter { it.reviewer.isHumanContributor() && it.author.isHumanContributor() }
        }

        logger.info("Crawling PRs for {}/{} since {} until {}", owner, repo, since, until ?: "now")
        val prs = fetchAllPrs(owner, repo, since, until)
        logger.info("Found {} merged PRs", prs.size)

        val edges = buildCommentEdges(owner, repo, prs, until)
        logger.info("Built {} edges from {} PRs", edges.size, prs.size)

        val bounded = edges.filterEdgesByWindow(since, until)
        if (bounded.isNotEmpty()) {
            cacheDir.createDirectories()
            cacheFile.writeText(cacheMapper.writeValueAsString(bounded))
            logger.info("Cached {} edges to {}", bounded.size, cacheFile)
        } else {
            logger.warn("Fetch returned no edges — skipping cache write for {}/{}", owner, repo)
        }
        return bounded
    }

    private fun cacheFile(owner: String, repo: String, since: Instant, until: Instant?): Path {
        val key = "${owner}_${repo}_${since.epochSecond}_${until?.epochSecond ?: "now"}_v${EDGE_CACHE_VERSION}.json"
        return cacheDir.resolve(key)
    }

    private fun String.isHumanContributor() = this != "ghost"
        && !this.endsWith("[bot]")
        && !this.endsWith("-bot")
        && !this.contains("-bot-")

    /**
     * Builds edges from inline PR comments. One edge per unique commenter per PR.
     * Merges inline diff comments and general issue thread comments.
     */
    private fun buildCommentEdges(
        owner: String,
        repo: String,
        prs: List<GitHubPr>,
        until: Instant? = null,
    ): List<ReviewEdge> {
        return prs.flatMapIndexed { idx, pr ->
            if (idx % 50 == 0) logger.info("Fetching comments for PR {}/{} (PR #{})", idx + 1, prs.size, pr.number)
            val prCreated = Instant.parse(pr.createdAt)
            val prMerged = pr.mergedAt?.let { Instant.parse(it) }
            val allCommenters: Map<String, List<Instant>> = buildMap {
                fetchReviewComments(owner, repo, pr.number)
                    .filter { it.user.login != pr.user.login && it.user.login.isHumanContributor() }
                    .groupBy { it.user.login }
                    .forEach { (login, comments) ->
                        merge(login, comments.mapNotNull { it.createdAt?.let { ts -> Instant.parse(ts) } }
                            .filter { until == null || it.isBefore(until) }) { a, b -> a + b }
                    }
                fetchIssueComments(owner, repo, pr.number)
                    .filter { it.user.login != pr.user.login && it.user.login.isHumanContributor() }
                    .groupBy { it.user.login }
                    .forEach { (login, comments) ->
                        merge(login, comments.map { Instant.parse(it.createdAt) }
                            .filter { until == null || it.isBefore(until) }) { a, b -> a + b }
                    }
            }
            allCommenters.mapNotNull { (commenter, timestamps) ->
                if (timestamps.isEmpty()) return@mapNotNull null
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
        // Always fill forward (asc) — ascending pages are immutable once full and
        // permanently cacheable. First run on a new repo is slow; all subsequent runs
        // load cached pages and only fetch the uncached tail from the API.
        // Desc gave faster cold-cache live runs but offered no cache benefit — once the
        // page cache is warm (after any historical analysis on the same repo), asc is
        // faster even for live windows.
        val historical = true
        val sort = if (historical) "sort=created&direction=asc" else "sort=created&direction=desc"

        val results = mutableListOf<GitHubPr>()
        var page = 1
        while (results.size < properties.maxPrsPerRepo) {
            val url = "/repos/$owner/$repo/pulls?state=closed&per_page=100&page=$page&$sort"
            val batch = fetchPrPage(owner, repo, page, url, historical) ?: break
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

    /**
     * Returns a page of PRs, using cache for historical ascending pages.
     * Full pages (100 items) are permanently cached — a PR created in 2014 always
     * lands on the same page number in asc order, so full pages are immutable.
     * Partial pages are cached too, but only as a resume checkpoint: on load we
     * check if the cached size is still < 100 and re-fetch, because the page may
     * have grown since it was last seen. This means a killed or rate-limited run
     * resumes from its last successfully fetched page rather than starting over.
     */
    private fun fetchPrPage(owner: String, repo: String, page: Int, url: String, historical: Boolean): List<GitHubPr>? {
        if (historical) {
            val cached = loadCachedPrPage(owner, repo, page)
            if (cached != null) {
                if (cached.size == 100) {
                    logger.info("PR page cache hit: {}/{} page {} ({} PRs)", owner, repo, page, cached.size)
                    return cached
                }
                // Partial page cached — could have grown, re-fetch to pick up new items
                logger.info("PR page cache stale (partial): {}/{} page {} ({} PRs) — re-fetching", owner, repo, page, cached.size)
            }
        }
        val fetched = getWithRateLimitHandling<List<GitHubPr>>(url) ?: return null
        if (historical) {
            cachePrPage(owner, repo, page, fetched)
            if (fetched.size < 100) {
                logger.info("Cached PR page {}/{} page {} (partial, {} PRs — resume checkpoint)", owner, repo, page, fetched.size)
            }
        }
        return fetched
    }

    private fun prPageCacheFile(owner: String, repo: String, page: Int): Path =
        cacheDir.resolve("${owner}_${repo}_prs_asc_p${page}.json")

    private fun loadCachedPrPage(owner: String, repo: String, page: Int): List<GitHubPr>? {
        val file = prPageCacheFile(owner, repo, page)
        if (!file.exists()) return null
        return cacheMapper.readValue(file.readText())
    }

    private fun cachePrPage(owner: String, repo: String, page: Int, prs: List<GitHubPr>) {
        cacheDir.createDirectories()
        prPageCacheFile(owner, repo, page).writeText(cacheMapper.writeValueAsString(prs))
    }

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

    private fun fetchIssueComments(owner: String, repo: String, prNumber: Int): List<GitHubIssueComment> {
        val cacheFile = cacheDir.resolve("${owner}_${repo}_pr${prNumber}_issue_comments.json")
        if (cacheFile.exists()) {
            return cacheMapper.readValue(cacheFile.readText())
        }
        val result = getWithRateLimitHandling<List<GitHubIssueComment>>(
            "/repos/$owner/$repo/issues/$prNumber/comments"
        ) ?: return emptyList()
        cacheDir.createDirectories()
        cacheFile.writeText(cacheMapper.writeValueAsString(result))
        return result
    }

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
                    e.message?.contains("RST_STREAM") == true || e.message?.contains("I/O error") == true
                        || e.message?.contains("502") == true || e.message?.contains("503") == true -> {
                        val backoff = 2_000L * (attempt + 1)
                        logger.warn("Transient error (attempt {}), retrying in {}ms: {}", attempt + 1, backoff, e.message)
                        Thread.sleep(backoff)
                    }
                    // Deserialization failures (GitHub returned an error object instead of a list)
                    // are expected for deleted forks or inaccessible PRs — downgrade to WARN.
                    e.message?.contains("extracting response") == true -> {
                        logger.warn("Skipping {} — unexpected response shape (deleted fork or inaccessible PR?)", url)
                        return null
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

}
