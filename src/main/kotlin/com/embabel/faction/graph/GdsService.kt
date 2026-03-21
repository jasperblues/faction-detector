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

import com.embabel.faction.domain.PairAnomaly
import com.embabel.faction.domain.ScoredPair
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.drivine.transaction.DrivineTransactional
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CommunityRow(val login: String, val communityId: Long)

/**
 * Graph Data Science operations: writes scoring results back to Neo4j relationships/nodes
 * and drives the Louvain community detection projection.
 *
 * All methods are scoped to a [runId] so multiple analysis runs can coexist in the same
 * database without interfering with each other.
 */
@Component
class GdsService(
    @param:Qualifier("factionsManager") private val pm: PersistenceManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Writes [PairAnomaly.anomalyScore] onto each REVIEWED relationship for the pair,
     * scoped to [runId].
     */
    @DrivineTransactional
    fun writeAnomalyScores(pairs: List<PairAnomaly>, runId: String) {
        if (pairs.isEmpty()) return
        logger.info("Writing anomaly scores for {} pairs (runId={})", pairs.size, runId)
        val rows = pairs.map { mapOf(
            "reviewer" to it.reviewer,
            "author" to it.author,
            "anomalyScore" to it.anomalyScore,
        ) }
        pm.execute(
            QuerySpecification.withStatement(
                "UNWIND \$rows AS row " +
                "MATCH (:Contributor {login: row.reviewer, runId: \$runId})" +
                "-[r:REVIEWED {runId: \$runId}]->" +
                "(:Contributor {login: row.author, runId: \$runId}) " +
                "SET r.anomalyScore = row.anomalyScore"
            ).bind(mapOf("rows" to rows, "runId" to runId))
        )
    }

    /**
     * Writes [ScoredPair.factionSignal] onto each REVIEWED relationship for the pair,
     * scoped to [runId].
     */
    @DrivineTransactional
    fun writeFactionSignals(pairs: List<ScoredPair>, runId: String) {
        if (pairs.isEmpty()) return
        logger.info("Writing faction signals for {} pairs (runId={})", pairs.size, runId)
        val rows = pairs.map { mapOf(
            "reviewer" to it.reviewer,
            "author" to it.author,
            "factionSignal" to it.factionSignal,
        ) }
        pm.execute(
            QuerySpecification.withStatement(
                "UNWIND \$rows AS row " +
                "MATCH (:Contributor {login: row.reviewer, runId: \$runId})" +
                "-[r:REVIEWED {runId: \$runId}]->" +
                "(:Contributor {login: row.author, runId: \$runId}) " +
                "SET r.factionSignal = row.factionSignal"
            ).bind(mapOf("rows" to rows, "runId" to runId))
        )
    }

    /**
     * Projects the anomaly-weighted Contributor→REVIEWED graph for this [runId], runs Louvain
     * community detection writing communityId to each node, then drops the projection.
     */
    fun runLouvain(runId: String) {
        val reviewCount = pm.query(
            QuerySpecification.withStatement(
                "MATCH ()-[r:REVIEWED {runId: \$runId}]->() RETURN count(r) AS n"
            ).bind(mapOf("runId" to runId)).transform<Long>()
        ).firstOrNull() ?: 0L

        if (reviewCount == 0L) {
            logger.warn("No REVIEWED relationships found for runId={} — skipping Louvain projection", runId)
            return
        }
        logger.info("Running GDS Louvain on {} review edges (runId={})", reviewCount, runId)

        // Tag core contributors (degree >= 3) so node and relationship projections stay consistent
        pm.execute(QuerySpecification.withStatement("""
            MATCH (n:Contributor {runId: "$runId"})
            WITH n,
                 COUNT { (n)-[:REVIEWED {runId: "$runId"}]-() } +
                 COUNT { ()-[:REVIEWED {runId: "$runId"}]->(n) } AS degree
            WHERE degree >= 3
            SET n:CoreContributor
        """.trimIndent()))

        val graphName = "faction-graph-$runId"
        pm.execute(QuerySpecification.withStatement(
            "CALL gds.graph.drop('$graphName', false) YIELD graphName RETURN { graphName: graphName } AS result"))
        pm.execute(QuerySpecification.withStatement("""
            CALL gds.graph.project.cypher(
              '$graphName',
              'MATCH (n:CoreContributor) WHERE n.runId = "$runId" RETURN id(n) AS id',
              'MATCH (s:CoreContributor)-[r:REVIEWED]->(t:CoreContributor) WHERE r.runId = "$runId"
               RETURN id(s) AS source, id(t) AS target,
                      coalesce(r.factionSignal, r.anomalyScore, 1.0) AS weight'
            ) YIELD graphName RETURN { graphName: graphName } AS result
        """.trimIndent()))
        pm.execute(QuerySpecification.withStatement("""
            CALL gds.louvain.write('$graphName', {
              writeProperty: 'communityId',
              relationshipWeightProperty: 'weight'
            }) YIELD communityCount RETURN { communityCount: communityCount } AS result
        """.trimIndent()))
        pm.execute(QuerySpecification.withStatement(
            "CALL gds.graph.drop('$graphName') YIELD graphName RETURN { graphName: graphName } AS result"))

        // Remove temporary label
        pm.execute(QuerySpecification.withStatement("""
            MATCH (n:CoreContributor {runId: "$runId"}) REMOVE n:CoreContributor
        """.trimIndent()))
        logger.info("Louvain complete — communityId written to Contributor nodes for runId={}", runId)
    }

    /**
     * Returns a map of contributor login → GDS community ID for this [runId].
     * Only contributors with a communityId (i.e. Louvain has been run) are included.
     */
    @DrivineTransactional
    fun queryCommunities(runId: String): Map<String, Int> =
        pm.query(
            QuerySpecification.withStatement(
                "MATCH (n:Contributor {runId: \$runId}) WHERE n.communityId IS NOT NULL " +
                "RETURN { login: n.login, communityId: n.communityId } AS result"
            ).bind(mapOf("runId" to runId)).transform<CommunityRow>()
        ).associate { it.login to it.communityId.toInt() }
}
