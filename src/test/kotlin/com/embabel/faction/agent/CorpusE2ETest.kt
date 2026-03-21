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

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.api.common.TypedOps
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.faction.domain.TensionPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * End-to-end corpus tests: real GitHub data → full pipeline → expected TensionPattern.
 *
 * ## Requirements
 * - Local Neo4j running at localhost:7687 with the `factions` database.
 * - FACTION_GITHUB_TOKEN and ANTHROPIC_API_KEY set in the environment.
 * - ANTHROPIC_API_KEY is only needed when the scored-pairs cache is cold;
 *   warm-cache runs complete without any LLM calls.
 *
 * ## Running
 * ```
 * mvn test -Dgroups=e2e
 * ```
 *
 * ## CI
 * E2e tests are excluded from the default CI run via `@Tag("e2e")`.
 * The Maven surefire default config skips groups=e2e.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false",
        "spring.shell.script.enabled=false",
    ],
)
@EnableAutoConfiguration(exclude = [
    org.springframework.shell.boot.ShellRunnerAutoConfiguration::class,
    org.springframework.shell.boot.ApplicationRunnerAutoConfiguration::class,
])
@Tag("e2e")
class CorpusE2ETest {

    @Autowired
    private lateinit var autonomy: Autonomy

    private val typedOps: TypedOps by lazy {
        AgentPlatformTypedOps(autonomy.agentPlatform)
    }

    private fun analyse(repoAndArgs: String): SplitPredictionResult =
        typedOps.asFunction<UserInput, SplitPredictionResult>(
            outputClass = SplitPredictionResult::class.java,
            agentName = "FactionDetectorAgent",
        ).apply(
            UserInput("$repoAndArgs --no-narrative"),
            ProcessOptions(),
        )

    @Test
    fun `nodejs io-js fork 2013-2015 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("nodejs/node 2013-06-01 2015-03-01")
        assertEquals(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result.prediction.fracture?.pattern)
    }

    @Test
    fun `redis valkey 2021-2024 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2021-01-01 2024-09-01")
        assertEquals(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result.prediction.fracture?.pattern)
    }

    @Test
    fun `babel 2018-2020 is ATTRITION`() {
        val result = analyse("babel/babel 2018-01-01 2020-06-01")
        assertEquals(TensionPattern.ATTRITION, result.prediction.fracture?.pattern)
    }

    @Test
    fun `rust mozilla layoffs 2020-2021 is ATTRITION`() {
        val result = analyse("rust-lang/rust 2020-01-01 2021-06-01")
        assertEquals(TensionPattern.ATTRITION, result.prediction.fracture?.pattern)
    }

    @Test
    fun `nodejs TSC exodus 2016-2017 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("nodejs/node 2016-01-01 2017-12-01")
        assertEquals(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result.prediction.fracture?.pattern)
    }
}