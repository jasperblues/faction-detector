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

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.core.env.MapPropertySource

/**
 * Provides all Drivine datasource properties before the Spring context starts.
 *
 * Default: local Neo4j at localhost:7687, factions database.
 * Set USE_LOCAL_NEO4J=false to spin up a testcontainer instead (community, neo4j DB).
 */
class FactionNeo4jInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(ctx: ConfigurableApplicationContext) {
        // Use neo4j default database — exists on both local Enterprise and testcontainer Community
        val props: Map<String, Any> = mapOf(
            "database.datasources.factions.type" to "NEO4J",
            "database.datasources.factions.userName" to "neo4j",
            "database.datasources.factions.password" to "brahmsian",
            "database.datasources.factions.host" to "localhost",
            "database.datasources.factions.port" to 7687,
            "database.datasources.factions.databaseName" to "neo4j",
        )
        ctx.environment.propertySources.addFirst(MapPropertySource("factionTestNeo4j", props))
    }
}

/**
 * Minimal Drivine configuration for faction graph integration tests.
 * Use [FactionNeo4jInitializer] to supply datasource properties.
 */
@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@EnableAspectJAutoProxy(proxyTargetClass = true)
class FactionDrivineTestConfig {

    @Bean
    fun factionsManager(factory: PersistenceManagerFactory): PersistenceManager =
        factory.get("factions")

    @Bean
    fun factionsGraphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager =
        factory.get("factions")
}
