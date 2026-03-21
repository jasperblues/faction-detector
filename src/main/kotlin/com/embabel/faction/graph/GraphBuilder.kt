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
package com.embabel.faction.graph

import com.embabel.faction.domain.ReviewEdge
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.drivine.transaction.DrivineTransactional
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

private const val BATCH_SIZE = 1000

private val MERGE_REVIEW_EDGES_BATCH = """
    UNWIND ${'$'}rows AS row
    MERGE (reviewer:Contributor {login: row.reviewer, runId: row.runId})
    MERGE (author:Contributor {login: row.author, runId: row.runId})
    MERGE (reviewer)-[r:REVIEWED {repo: row.repo, timestamp: row.timestamp, runId: row.runId}]->(author)
    SET r.state = row.state,
        r.commentCount = row.commentCount,
        r.weight = row.weight,
        r.prNumber = row.prNumber,
        r.hoursToFirstReview = row.hoursToFirstReview,
        r.daysMergedAfterReview = row.daysMergedAfterReview
""".trimIndent()

/**
 * Persists [ReviewEdge] data from GitHub into the Neo4j factions graph.
 * Uses UNWIND + MERGE so re-runs are idempotent and writes are batched.
 */
@Component
class GraphBuilder(
    @param:Qualifier("factionsManager") private val persistenceManager: PersistenceManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @DrivineTransactional
    fun persist(edges: List<ReviewEdge>, runId: String) {
        logger.info("Persisting {} review edges to Neo4j (runId={})", edges.size, runId)
        val rows = edges.map { edge ->
            mapOf(
                "reviewer" to edge.reviewer,
                "author" to edge.author,
                "repo" to edge.repo,
                "timestamp" to edge.timestamp.toString(),
                "state" to edge.state.name,
                "commentCount" to edge.commentCount,
                "weight" to edge.weight,
                "prNumber" to edge.prNumber,
                "hoursToFirstReview" to edge.hoursToFirstReview,
                "daysMergedAfterReview" to edge.daysMergedAfterReview,
                "runId" to runId,
            )
        }
        rows.chunked(BATCH_SIZE).forEachIndexed { i, chunk ->
            persistenceManager.execute(
                QuerySpecification.withStatement(MERGE_REVIEW_EDGES_BATCH)
                    .bind(mapOf("rows" to chunk))
            )
            logger.debug("Persisted batch {}/{}", i + 1, (rows.size + BATCH_SIZE - 1) / BATCH_SIZE)
        }
        logger.info("Persisted {} review edges in {} batches", edges.size, (rows.size + BATCH_SIZE - 1) / BATCH_SIZE)
    }
}
