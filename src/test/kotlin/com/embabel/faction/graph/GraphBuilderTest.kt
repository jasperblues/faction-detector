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
import com.embabel.faction.domain.ReviewState
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
@Import(GraphBuilder::class, GraphRepository::class)
class GraphBuilderTest {

    @Autowired
    lateinit var graphBuilder: GraphBuilder

    @Autowired
    lateinit var graphRepository: GraphRepository

    @Autowired
    @Qualifier("factionsManager")
    lateinit var pm: PersistenceManager

    private val now = Instant.now()

    @BeforeEach
    fun cleanup() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `persisted edges create Contributor nodes and REVIEWED relationships`() {
        val edges = listOf(
            edge("alice", "bob", ReviewState.APPROVED),
            edge("alice", "carol", ReviewState.CHANGES_REQUESTED),
        )
        graphBuilder.persist(edges, "run-test1")

        val contributors = graphRepository.findAllContributors()
        val logins = contributors.map { it.contributor.login }.toSet()
        assertTrue(logins.containsAll(setOf("alice", "bob", "carol")),
            "Expected alice, bob, carol — got $logins")

        val aliceReviews = contributors.single { it.contributor.login == "alice" }.reviews
        assertEquals(2, aliceReviews.size, "alice should have 2 outgoing reviews")
    }

    @Test
    fun `persist is idempotent — re-running same edges does not duplicate relationships`() {
        val edges = listOf(edge("igor", "rodney", ReviewState.CHANGES_REQUESTED))
        graphBuilder.persist(edges, "run-idem")
        graphBuilder.persist(edges, "run-idem")

        val igor = graphRepository.findContributor("igor")
        assertEquals(1, igor?.reviews?.size, "Re-persisting same edge should not create duplicates")
    }

    @Test
    fun `review state is stored correctly`() {
        graphBuilder.persist(listOf(
            edge("reviewer", "author", ReviewState.APPROVED),
        ), "run-state")
        val contributor = graphRepository.findContributor("reviewer")
        val review = contributor?.reviews?.single()
        assertEquals("APPROVED", review?.state)
    }

    @Test
    fun `different runIds produce independent node sets`() {
        val edge = edge("alice", "bob", ReviewState.APPROVED)
        graphBuilder.persist(listOf(edge), "run-A")
        graphBuilder.persist(listOf(edge), "run-B")

        val runACount = pm.query(
            QuerySpecification.withStatement(
                "MATCH (n:Contributor {runId: 'run-A'}) RETURN count(n) AS n"
            ).transform<Long>()
        ).first()
        val runBCount = pm.query(
            QuerySpecification.withStatement(
                "MATCH (n:Contributor {runId: 'run-B'}) RETURN count(n) AS n"
            ).transform<Long>()
        ).first()
        assertEquals(2L, runACount, "run-A should have 2 Contributor nodes")
        assertEquals(2L, runBCount, "run-B should have 2 Contributor nodes")
    }

    private fun edge(reviewer: String, author: String, state: ReviewState) = ReviewEdge(
        reviewer = reviewer,
        author = author,
        repo = "test/repo",
        prNumber = 1,
        timestamp = now,
        commentCount = 2,
        state = state,
        hoursToFirstReview = 4.0,
        daysMergedAfterReview = 2.0,
    )
}
