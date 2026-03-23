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

import com.embabel.faction.github.EDGE_CACHE_VERSION
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Detector logic version — bump when FractureDetector, ExodusDetector, signal gate,
 * or confidence computation changes. Snapshot cache keys include this so stale
 * snapshots are automatically bypassed.
 */
const val DETECTOR_VERSION = 1

/**
 * Composite version key: any change to edge construction, LLM scoring, or detector
 * logic invalidates snapshots. Individual upstream caches (edges, LLM scores) are
 * NOT invalidated — only the snapshot layer rebuilds.
 */
private val SNAPSHOT_VERSION = "$EDGE_CACHE_VERSION.$SCORED_PAIRS_VERSION.$DETECTOR_VERSION"

/**
 * Gzipped JSON cache for [WindowedScores] — the complete intermediate state between
 * data acquisition (GitHub + Neo4j + LLM) and classification (FractureDetector +
 * ExodusDetector + signal gate).
 *
 * Cache hierarchy: PR pages → edges → LLM scores → **snapshots**.
 * When a snapshot hits, the entire upstream pipeline is skipped — no GitHub API calls,
 * no Neo4j graph construction, no LLM scoring. This keeps the Neo4j database clean
 * (only fresh/interesting data gets persisted) and eliminates rate limit pressure on
 * repeated test runs.
 *
 * Snapshots are stored as gzipped JSON (~90% compression on structured review data).
 * Files are keyed on repo + date range + composite version.
 *
 * For CI: committed snapshots in `src/test/resources/snapshots/` are checked first
 * (classpath lookup). Locally: `~/.faction-cache/snapshots/` is used.
 */
@Component
class SnapshotCache {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val cacheDir: Path = Path.of(System.getProperty("user.home"), ".faction-cache", "snapshots")
        .also { it.createDirectories() }

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * Attempts to load a snapshot. Checks classpath resources first (CI), then local cache.
     * Returns null if no snapshot exists for this key.
     */
    fun load(repo: String, since: Instant, until: Instant?): WindowedScores? {
        val key = cacheKey(repo, since, until)

        // Check classpath (committed snapshots for CI)
        val classpathResource = javaClass.getResourceAsStream("/snapshots/$key")
        if (classpathResource != null) {
            logger.info("Snapshot classpath hit: {}", key)
            return classpathResource.use { stream ->
                mapper.readValue<WindowedScores>(GZIPInputStream(stream).readBytes())
            }
        }

        // Check local cache
        val localFile = cacheDir.resolve(key)
        if (localFile.exists()) {
            logger.info("Snapshot cache hit: {}", key)
            return mapper.readValue<WindowedScores>(ungzip(localFile.readBytes()))
        }

        logger.info("Snapshot cache miss: {}", key)
        return null
    }

    /**
     * Saves a snapshot to the local cache. Does NOT write to classpath resources —
     * those are committed manually (or via a build task) after local validation.
     */
    fun save(scores: WindowedScores) {
        val key = cacheKey(scores.repo, scores.since, scores.until)
        val localFile = cacheDir.resolve(key)
        val json = mapper.writeValueAsBytes(scores)
        localFile.writeBytes(gzip(json))
        val compressedSize = localFile.toFile().length()
        val pct = if (json.isNotEmpty()) "%.0f".format((1.0 - compressedSize.toDouble() / json.size) * 100) else "0"
        logger.info("Snapshot saved: {} ({} bytes JSON → {} bytes gzipped, {}% compression)",
            key, json.size, compressedSize, pct)
    }

    private fun cacheKey(repo: String, since: Instant, until: Instant?): String {
        val repoKey = repo.replace("/", "_")
        val sinceKey = since.epochSecond
        val untilKey = until?.epochSecond ?: "now"
        return "${repoKey}_${sinceKey}_${untilKey}_v${SNAPSHOT_VERSION}.json.gz"
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun ungzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).readBytes()
}