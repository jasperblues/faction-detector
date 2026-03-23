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
    // Three phases of the Node.js governance saga, each with a distinct pattern.

    // io.js fork (Dec 2014): Joyent's control of Node.js frustrated core contributors.
    // Fedor Indutny, Ben Noordhuis and others forked to io.js with open governance.
    // Adversarial reviews visible in the PR stream — TSC members actively clashing.
    // Resolved: io.js merged back into Node.js under the Node Foundation (Jun 2015).
    @Test
    fun `nodejs io-js fork 2013-2015 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("nodejs/node 2013-06-01 2015-03-01")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    // TSC exodus (Oct–Nov 2017): Second governance crisis. High-centrality TSC members
    // (eugeneo, yorkie, estliberitas) departed. 29% mass drop, 2.1% core impact.
    // Window ends during active tension — unresolved and rising at window close.
    @Test
    fun `nodejs TSC exodus 2016-2017 is FRACTURE_IMMINENT`() {
        val result = analyse("nodejs/node 2016-01-01 2017-12-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    // Second wave (2018–2019): Natural contributor lifecycle turnover after the TSC exodus.
    // mcollina, evanlucas departed — low core impact (1.3%), moderate mass drop (29%).
    // No adversarial review signal. Call to action is succession planning, not governance.
    @Test
    fun `nodejs second wave 2018-2019 is ATTRITION`() {
        val result = analyse("nodejs/node 2018-01-01 2019-12-01")
        assertPattern(TensionPattern.ATTRITION, result)
    }

    // --- babel ---

    // Babel 7 release crunch (Aug–Oct 2018): Extreme review asymmetry during the
    // Babel 7 push, followed by gradual resolution. Henry Zhu was vocal about burnout
    // and sustainability. Contributors cycled out one by one over 2019–2020 — natural
    // turnover rather than factional dynamics. No adversarial signal in the reviews.
    @Test
    fun `babel 2018-2020 is ATTRITION`() {
        val result = analyse("babel/babel 2018-01-01 2020-06-01")
        assertPattern(TensionPattern.ATTRITION, result)
    }

    // --- rust ---

    // Mozilla layoffs (Aug 2020): External corporate event forced mass departure from
    // the Rust team. 41% mass drop but only 2.0% core impact — the departed were
    // Mozilla employees, not the volunteer core team. No internal faction war; the
    // community was unified in response to Mozilla's cuts. Gradual sustained elevation
    // that slowly resolved as Rust Foundation took over stewardship.
    @Test
    fun `rust mozilla layoffs 2020-2021 is EXODUS`() {
        val result = analyse("rust-lang/rust 2020-01-01 2021-06-01")
        assertPattern(TensionPattern.EXODUS, result)
    }

    // --- redis ---
    // Multi-wave fracture spanning 5+ years of licence and governance disputes.

    // Pre-Valkey tension (2021–2023): Window ends before the Valkey fork (Mar 2024).
    // Tension building but unresolved — the detector sees IMMINENT before the fork
    // is publicly announced. Validates early warning capability.
    @Test
    fun `redis pre-valkey 2021-2023 is FRACTURE_IMMINENT`() {
        val result = analyse("redis/redis 2021-01-01 2023-12-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    // Valkey fork (Mar 2024): Extended window captures the full fracture arc — tension
    // build-up, Valkey fork, contributor exodus (26% mass drop). Adversarial review
    // signal above baseline — internal faction war, not unified community uprising.
    @Test
    fun `redis valkey 2021-2024 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2021-01-01 2024-09-01")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    // RSALv2/SSPL first wave (2020–2021): First licence change for Redis modules.
    // itamarhaber (DevRel), gkorland (CTO) departed. Adversarial review signal
    // measurably above baseline — internal faction dynamics, not just policy disagreement.
    @Test
    fun `redis RSALv2 first wave 2020-2021 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2020-01-01 2021-06-01")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    // Wave 6 (2024–2026): Post-Valkey rump community. The remaining contributor pool
    // crystallised into warring communities. Sustained adversarial signal in reviews.
    @Test
    fun `redis wave 6 2024-2026 is FRACTURE_ADVERSARIAL_FORK`() {
        val result = analyse("redis/redis 2024-09-01 2026-03-08")
        assertPattern(TensionPattern.FRACTURE_ADVERSARIAL_FORK, result)
    }

    // --- moby ---

    // Docker Enterprise sold to Mirantis (Nov 2019): Community uprising against
    // corporate steward decision. Review signal stayed at or below baseline —
    // contributors were unified against Docker Inc's direction, not fighting each
    // other. Core team departed Dec 2019; re-escalation in 2020–2021 as the remaining
    // community restructured.
    @Test
    fun `moby docker enterprise 2019-2021 is FRACTURE_UPRISING`() {
        val result = analyse("moby/moby 2019-01-01 2021-06-01")
        assertPattern(TensionPattern.FRACTURE_UPRISING, result)
    }

    // --- terraform ---
    // HashiCorp BSL licence change (Aug 2023) and OpenTofu fork (Sep 2023).

    // Pre-BSL tension (2021–2022): Window ends before the BSL announcement.
    // Tension building and unresolved — early warning signal. Small contributor
    // pool produces structurally high asymmetry with sparse review windows.
    @Test
    fun `terraform pre-BSL 2021-2022 is FRACTURE_IMMINENT`() {
        val result = analyse("hashicorp/terraform 2021-06-01 2022-05-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    // BSL licence change (Aug 2023): Community uprising against HashiCorp's licence
    // change. Review signal below baseline — contributors unified against the steward,
    // not against each other. OpenTofu fork crystallised the split. Re-escalation
    // after brief resolution as both sides restructured.
    @Test
    fun `terraform BSL 2022-2024 is FRACTURE_UPRISING`() {
        val result = analyse("hashicorp/terraform 2022-06-01 2024-06-01")
        assertPattern(TensionPattern.FRACTURE_UPRISING, result)
    }

    // --- gogs ---

    // gogs was a single-maintainer project (unknwon/Jiahua Chen). PRs piled up
    // unreviewed — classic single-maintainer bottleneck. bkcsoft (Thomas Boerger),
    // a key contributor (57 centrality, 31 PRs authored, but only 6 reviews — he
    // wasn't given review authority?) departed Jan 2016 and co-founded gitea 10 months
    // later. unknwon reviewed 429 PRs; bkcsoft reviewed 6.
    // gitea overtook gogs (54k vs 48k stars) — the fork succeeded completely.
    //
    // Note on single-maintainer projects: these are hard to get a clean signal on,
    // especially for resolution. The single maintainer is always in their own community
    // of one, so asymmetry is structurally high and never resolves. IMMINENT is arguably
    // correct here — as long as the sole maintainer continues with the same pattern,
    // the conditions that caused the fork persist. The fracture IS imminent (or ongoing).
    @Test
    fun `gogs gitea fork 2016-2017 is FRACTURE_IMMINENT`() {
        val result = analyse("gogs/gogs 2016-01-01 2017-06-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    // gitea (the fork destination): Created Nov 2016. Early growth period should show
    // healthy contributor dynamics — the fork attracted contributors who left gogs.
    // Counterpoint to gogs's IMMINENT: the source repo stays sick while the fork thrives.
    @Test
    fun `gitea early growth 2016-2018 EXPLORATORY`() {
        val result = analyse("go-gitea/gitea 2016-11-01 2018-06-01")
        println(diagMsg(result))
    }

    // gitea mature period: By 2019 gitea had established governance and a broader
    // reviewer pool. Should show healthier dynamics than the chaotic early phase.
    @Test
    // gitea mature period: By 2019 gitea had established governance and a broader
    // reviewer pool. Healthy contributor dynamics — normal turnover, no factions.
    // Counterpoint to gogs's IMMINENT: the source repo stays sick while the fork thrives.
    fun `gitea mature 2019-2021 is ATTRITION`() {
        val result = analyse("go-gitea/gitea 2019-01-01 2021-01-01")
        assertPattern(TensionPattern.ATTRITION, result)
    }

    // --- presto ---

    // Facebook controlled presto development; community contributors (Martin Traverso,
    // Dain Sundstrom, David Phillips) forked to trino Jan 2019 over governance concerns.
    // The trino fork happened within both windows but presto's tension never resolved —
    // Facebook kept the repo going under corporate control with sustained high asymmetry.
    // Both windows show the same pattern — the parent never healed.
    @Test
    fun `presto trino fork 2018-2020 is FRACTURE_IMMINENT`() {
        val result = analyse("prestodb/presto 2018-06-01 2020-06-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    @Test
    fun `presto trino fork long window 2018-2021 is FRACTURE_IMMINENT`() {
        val result = analyse("prestodb/presto 2018-06-01 2021-06-01")
        assertPattern(TensionPattern.FRACTURE_IMMINENT, result)
    }

    // --- exploratory: godot (Redot protest fork Sep 2024) ---

    // Redot forked after a social media incident (Sep 27 2024 "Wokot" tweet +
    // mass-blocking of users). No core Godot contributors defected — Redot is
    // 100% new volunteers (5.8k stars vs Godot's 108k). However, the detector
    // finds genuine tension: 20–26% mass drop, peak 1.00, 87–100% confidence.
    // This may be structural asymmetry (small core team reviewing thousands of
    // community PRs) rather than factional, or genuine tension independent of
    // the Redot drama. Needs further investigation before locking in.
    @Test
    fun `godot redot fork 2023-2025 wide EXPLORATORY`() {
        val result = analyse("godotengine/godot 2023-06-01 2025-06-01")
        println(diagMsg(result))
    }

    @Test
    fun `godot redot fork 2024 tight EXPLORATORY`() {
        val result = analyse("godotengine/godot 2024-03-01 2025-03-01")
        println(diagMsg(result))
    }

    // --- RedisGraph ---

    // Redis Labs discontinued RedisGraph in Jan 2023. Community forked to FalkorDB
    // (created Jul 2023). filipecosta90 and chayim departed — 40% mass drop.
    // Community uprising against corporate steward's discontinuation decision.
    // Review signal below baseline — contributors unified against the corporate
    // decision, not fighting each other.
    @Test
    fun `redisgraph falkordb fork 2022-2024 is FRACTURE_UPRISING`() {
        val result = analyse("RedisGraph/RedisGraph 2022-01-01 2024-01-01")
        assertPattern(TensionPattern.FRACTURE_UPRISING, result)
    }

    // --- pre-fork validation ---
    // For confirmed fracture cases, run a window ending before the known fork/departure
    // to validate early warning capability. Key finding: contributor departures
    // consistently precede public fork announcements by months, so "pre-fork" windows
    // often still capture the actual departure.

    // redis pre-RSALv2: Window starts after itamarhaber's departure (Jul 2019) to
    // isolate the build-up to the Mar 2021 RSALv2 change. Result: TENSION
    // (rising, peak 0.67 just under IMMINENT threshold 0.70). Correct early warning.
    @Test
    fun `redis pre-RSALv2 2020-2021 EXPLORATORY`() {
        val result = analyse("redis/redis 2020-06-01 2021-02-01")
        println(diagMsg(result))
    }

    // moby pre-sale: Docker Enterprise sold Nov 2019. Window starts after ddebroy's
    // first departure (Mar 2018) but still captures a second departure wave (Jan 2019).
    // Departures preceded the public sale by 10 months.
    // Result: FRACTURE_UPRISING (53%, already classifying before the public event).
    @Test
    fun `moby pre-enterprise-sale 2019 EXPLORATORY`() {
        val result = analyse("moby/moby 2019-01-01 2019-10-01")
        println(diagMsg(result))
    }

    // RedisGraph pre-discontinuation: OfirMos departed Aug 2021, chayim departed
    // Mar 2022 — both well before the Jan 2023 discontinuation announcement.
    // Result: FRACTURE_ADVERSARIAL_FORK (83%). The detector catches the fracture
    // 10 months before the public discontinuation.
    @Test
    fun `redisgraph pre-discontinuation 2022 EXPLORATORY`() {
        val result = analyse("RedisGraph/RedisGraph 2022-01-01 2022-12-01")
        println(diagMsg(result))
    }

    // --- true negative validation: quiet/stable periods ---
    // For repos with known fractures, pick a window where nothing dramatic was
    // happening. These should return STABLE or ATTRITION — confirming the detector
    // doesn't false-positive on normal project dynamics.

    // nodejs 2019-2020: Post-second-wave, Foundation governance settled.
    // No major departures, no governance disputes. Should be calm.
    @Test
    fun `nodejs stable period 2019-2020 EXPLORATORY`() {
        val result = analyse("nodejs/node 2019-06-01 2020-12-01")
        println(diagMsg(result))
    }

    // redis 2017-2019: Between RSAL module change aftermath and RSALv2 build-up.
    // antirez still active, community relatively stable.
    @Test
    // redis 2017-2019: Between RSAL aftermath and RSALv2 build-up. Signal gate
    // downgrades — avgFactionSignal=0.000, crossCommunityScore=0.000. Zero adversarial
    // signal despite high asymmetry. antirez still active, community relatively stable.
    fun `redis quiet period 2017-2019 is TENSION`() {
        val result = analyse("redis/redis 2017-06-01 2019-06-01")
        assertPattern(TensionPattern.TENSION, result)
    }

    // moby 2016-2018: Docker's growth era. Before Enterprise sale talk.
    // Strong community, active development, no governance crisis yet.
    @Test
    // moby 2016-2018: Docker's growth era, before Enterprise sale talk.
    // Signal gate downgrades — avgFactionSignal=0.045, crossCommunityScore=0.039,
    // both below 0.05 threshold. High asymmetry but constructive reviews.
    fun `moby stable period 2016-2018 is TENSION`() {
        val result = analyse("moby/moby 2016-06-01 2018-06-01")
        assertPattern(TensionPattern.TENSION, result)
    }

    // rust 2022-2024: Post-Mozilla layoffs, Rust Foundation established.
    // Stable governance, growing community, no existential threats.
    // Note: rust doesn't use GitHub PR reviews (bors/highfive bot system) — returns 0 edges.
    // @Test
    // fun `rust stable period 2022-2024 EXPLORATORY`() {
    //     val result = analyse("rust-lang/rust 2022-06-01 2024-06-01")
    //     println(diagMsg(result))
    // }

    // terraform 2019-2021: Pre-BSL, HashiCorp growing but no licence drama.
    // Normal corporate-backed open source development.
    // Result: FRACTURE_UPRISING (73%) — tension already present. avgFactionSignal=0.022 (low).
    @Test
    fun `terraform quiet period 2019-2021 EXPLORATORY`() {
        val result = analyse("hashicorp/terraform 2019-06-01 2021-06-01")
        println(diagMsg(result))
    }

    // terraform 2016-2017: Community golden era — peak external contribution (3000+ PRs/yr),
    // before the provider split (2018) and corporate tightening. If terraform was ever
    // healthy, this is the window.
    @Test
    fun `terraform golden era 2016-2017 EXPLORATORY`() {
        val result = analyse("hashicorp/terraform 2016-06-01 2017-12-01")
        println(diagMsg(result))
    }

    // --- true negatives: healthy projects that never had significant forks ---
    // If these return STABLE or ATTRITION, IMMINENT is meaningful as a risk signal.
    // If they also return IMMINENT, the detector is just measuring "small core team."

    // kubernetes: CNCF distributed governance, massive contributor base, no significant fork.
    // Signal gate downgrades — avgFactionSignal=0.049 (just below 0.05 threshold).
    // High structural asymmetry (peak 1.00, 38% drop) but reviews are constructive.
    // Key true negative: the largest, best-governed project in the corpus.
    @Test
    fun `kubernetes healthy 2020-2022 is TENSION`() {
        val result = analyse("kubernetes/kubernetes 2020-06-01 2022-06-01")
        assertPattern(TensionPattern.TENSION, result)
    }

    // django: Mature, well-governed project. DSF stewardship, no fork drama.
    @Test
    fun `django healthy 2020-2022 EXPLORATORY`() {
        val result = analyse("django/django 2020-06-01 2022-06-01")
        println(diagMsg(result))
    }

    // rails: Long-running, opinionated but stable governance under DHH.
    @Test
    // rails: DHH-dominated but no fork. Signal gate correctly downgrades
    // from IMMINENT — high asymmetry but low adversarial signal (0.044).
    fun `rails healthy 2020-2022 is TENSION`() {
        val result = analyse("rails/rails 2020-06-01 2022-06-01")
        assertPattern(TensionPattern.TENSION, result)
    }

    // fastapi: Rapidly growing, single-maintainer (tiangolo) but healthy community.
    @Test
    // fastapi: Single-maintainer (tiangolo), structurally high asymmetry but no
    // adversarial dynamics (avgFactionSignal=0.032). Signal gate correctly downgrades.
    fun `fastapi healthy 2021-2023 is TENSION`() {
        val result = analyse("fastapi/fastapi 2021-06-01 2023-06-01")
        assertPattern(TensionPattern.TENSION, result)
    }

    // next.js: Vercel-backed, active open source community, no fork.
    @Test
    fun `nextjs healthy 2021-2023 EXPLORATORY`() {
        val result = analyse("vercel/next.js 2021-06-01 2023-06-01")
        println(diagMsg(result))
    }

    private fun diagMsg(result: SplitPredictionResult): String {
        val p = result.prediction
        val f = p.fracture
        val e = p.exodus
        return buildString {
            appendLine("=== ${p.repo} ===")
            appendLine("Pattern: ${f?.pattern}")
            f?.let {
                appendLine("  severity=${it.severity} peak=${"%.2f".format(it.peakAsymmetry)} resolved=${it.isResolved} rising=${it.isRising} reEscalating=${it.isReEscalating}")
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
    }
}
