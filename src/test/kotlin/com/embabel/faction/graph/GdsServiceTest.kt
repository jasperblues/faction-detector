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
import com.embabel.faction.domain.ReviewEdge
import com.embabel.faction.domain.ReviewState
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant

@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [FactionDrivineTestConfig::class],
    initializers = [FactionNeo4jInitializer::class],
)
@Import(GraphBuilder::class, GraphRepository::class, GdsService::class)
class GdsServiceTest {

    @Autowired
    lateinit var graphBuilder: GraphBuilder

    @Autowired
    lateinit var gdsService: GdsService

    @Autowired
    @Qualifier("factionsManager")
    lateinit var pm: PersistenceManager

    private val now = Instant.now()

    @BeforeEach
    fun cleanup() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `writeAnomalyScores sets anomalyScore on REVIEWED relationships`() {
        val runId = "gds-test1"
        graphBuilder.persist(listOf(
            edge("alice", "bob", ReviewState.CHANGES_REQUESTED),
            edge("alice", "bob", ReviewState.CHANGES_REQUESTED),
        ), runId)
        gdsService.writeAnomalyScores(listOf(
            PairAnomaly("alice", "bob", 2, 1.0, 0.3, 0.0, 0.7),
        ), runId)

        val score = pm.query(
            QuerySpecification.withStatement(
                "MATCH (:Contributor {login:'alice', runId:\$runId})" +
                "-[r:REVIEWED {runId:\$runId}]->" +
                "(:Contributor {login:'bob', runId:\$runId}) " +
                "RETURN r.anomalyScore AS score LIMIT 1"
            ).bind(mapOf("runId" to runId)).transform<Double>()
        ).firstOrNull()
        assertNotNull(score, "anomalyScore should be written to REVIEWED relationship")
        assertTrue(score!! > 0.0, "anomalyScore should be positive")
    }

    @Test
    fun `runLouvain skips gracefully when graph is empty`() {
        // No edges — should not throw
        gdsService.runLouvain("no-such-run")
    }

    @Test
    fun `runLouvain assigns communityId to Contributor nodes (requires GDS + local Neo4j)`() {
        // GDS is only available on the local Neo4j Enterprise instance — skip in testcontainer mode
        assumeTrue(
            System.getProperty("test.neo4j.use-local") == "true",
            "Requires -Dtest.neo4j.use-local=true with GDS installed on local Neo4j"
        )

        val runId = "gds-louvain"
        graphBuilder.persist(listOf(
            edge("alice", "bob", ReviewState.APPROVED),
            edge("bob", "alice", ReviewState.APPROVED),
            edge("igor", "dan", ReviewState.CHANGES_REQUESTED),
            edge("dan", "igor", ReviewState.CHANGES_REQUESTED),
        ), runId)
        // anomalyScore must exist on at least one relationship before GDS can project the property
        gdsService.writeAnomalyScores(listOf(
            PairAnomaly("alice", "bob", 1, 0.0, 0.0, 0.0, 0.1),
        ), runId)
        gdsService.runLouvain(runId)

        val communities = gdsService.queryCommunities(runId)
        assertTrue(communities.isNotEmpty(), "Louvain should assign communityId to contributors")
        assertTrue(communities.containsKey("alice"), "alice should have a communityId")
        assertTrue(communities.containsKey("igor"), "igor should have a communityId")
    }

    private fun edge(reviewer: String, author: String, state: ReviewState) = ReviewEdge(
        reviewer = reviewer,
        author = author,
        repo = "test/repo",
        prNumber = 1,
        timestamp = now,
        commentCount = 1,
        state = state,
        hoursToFirstReview = 2.0,
        daysMergedAfterReview = 1.0,
    )
}
