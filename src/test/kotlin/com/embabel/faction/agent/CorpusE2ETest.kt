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

    private fun assertPattern(expected: TensionPattern, result: SplitPredictionResult) {
        val p = result.prediction
        val f = p.fracture
        val e = p.exodus
        val msg = buildString {
            appendLine("Pattern: ${f?.pattern} (expected $expected)")
            f?.let {
                appendLine("  severity=${it.severity} peak=${it.peakAsymmetry} resolved=${it.isResolved} rising=${it.isRising} reEscalating=${it.isReEscalating}")
                appendLine("  peakDate=${it.peakDate} fractureDate=${it.fractureDate} resolutionDate=${it.resolutionDate}")
                appendLine("  alternative=${it.alternativePattern}")
            }
            e?.let {
                appendLine("  exodus: date=${it.inferredDate} drop=${"%.0f".format(it.dropFraction * 100)}% core=${"%.1f".format(it.departureCentralityFraction * 100)}%")
                appendLine("  mass: ${"%.1f".format(it.weightedMassBefore)} → ${"%.1f".format(it.weightedMassAfter)}")
                appendLine("  departed: ${it.departedContributors.joinToString { d -> "${d.login}(${d.centrality.toInt()})" }}")
            }
            appendLine("  confidence=${"%.0f".format(p.confidence * 100)}% backfilled=${p.backfilled}")
        }
        assertEquals(expected, f?.pattern, msg)
    }

    // --- nodejs ---

    @Test
    fun `nodejs io-js fork 2013-2015 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("nodejs/node 2013-06-01 2015-03-01")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    @Test
    fun `nodejs TSC exodus 2016-2017 is FRACTURE_IMMINENT`() {
        val result = analyse("nodejs/node 2016-01-01 2017-12-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    @Test
    fun `nodejs second wave 2018-2019 is ATTRITION`() {
        val result = analyse("nodejs/node 2018-01-01 2019-12-01")
        assertPattern(TensionPattern.ATTRITION, result)
    }

    // --- babel ---

    @Test
    fun `babel 2018-2020 is ATTRITION`() {
        val result = analyse("babel/babel 2018-01-01 2020-06-01")
        assertPattern(TensionPattern.ATTRITION, result)
    }

    // --- rust ---

    @Test
    fun `rust mozilla layoffs 2020-2021 is EXODUS`() {
        val result = analyse("rust-lang/rust 2020-01-01 2021-06-01")
        assertPattern(TensionPattern.EXODUS, result)
    }

    // --- redis ---

    @Test
    fun `redis pre-valkey 2021-2023 is FRACTURE_IMMINENT`() {
        val result = analyse("redis/redis 2021-01-01 2023-12-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    @Test
    fun `redis valkey 2021-2024 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2021-01-01 2024-09-01")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    @Test
    fun `redis RSALv2 first wave 2020-2021 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2020-01-01 2021-06-01")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    @Test
    fun `redis wave 6 2024-2026 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2024-09-01 2026-03-08")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    // --- moby ---

    @Test
    fun `moby docker enterprise 2019-2021 is FRACTURE_UPRISING`() {
        val result = analyse("moby/moby 2019-01-01 2021-06-01")
        assertPattern(TensionPattern.FRACTURE_UPRISING, result)
    }

    // --- terraform ---

    @Test
    fun `terraform pre-BSL 2021-2022 is FRACTURE_IMMINENT`() {
        val result = analyse("hashicorp/terraform 2021-06-01 2022-05-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    @Test
    fun `terraform BSL 2022-2024 is FRACTURE_UPRISING`() {
        val result = analyse("hashicorp/terraform 2022-06-01 2024-06-01")
        assertPattern(TensionPattern.FRACTURE_UPRISING, result)
    }
}