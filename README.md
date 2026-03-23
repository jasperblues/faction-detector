<img align="left" src="logo.png" width="180">

# Faction Detector

![Build](https://github.com/jasperblues/faction-detector/actions/workflows/maven.yml/badge.svg)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white) ![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white) ![Neo4j](https://img.shields.io/badge/Neo4j-008CC1?style=for-the-badge&logo=neo4j&logoColor=white) ![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

<br clear="left"/>

> *Open source projects fork. Enterprise codebases rot. The review graph tells you which one is coming — and when.*

Gauge the health of open (or closed!) source projects using nothing but pull request comment dynamics.

> ⚠️ **Warning:** This tool can detect forks before they occur. Use responsibly.

📖 **[Read the full analysis: Faction Detection — Reading the Review Graph Before the Fork](https://medium.com/p/75e100898160)** — retrodiction results across 15 repos including nodejs, redis, terraform, kubernetes, django, and more.

Faction Detector analyses GitHub PR review activity to detect contributor faction dynamics and predict project splits. It builds a weighted directed graph of who reviews whom, runs community detection, and scores asymmetry across rolling time windows — then feeds everything into an LLM for narrative analysis.

Built with [Embabel](https://github.com/embabel/embabel-agent) + [Neo4j](https://neo4j.com) + [Neo4j Graph Data Science](https://neo4j.com/docs/graph-data-science/current/) + [Spring Boot](https://spring.io/projects/spring-boot).

---

## What it detects

| Pattern | Description |
|---------|-------------|
| `FRACTURE_ADVERSARIAL_FORK` | Internal faction war: 9+ weeks of sustained high asymmetry AND adversarial comment signal above baseline. The strongest claim the tool makes. |
| `FRACTURE_UPRISING` | Community unified against an external steward: 9+ weeks of high asymmetry, but low faction signal (contributors were aligned, not fighting each other) plus post-resolution re-escalation. |
| `GOVERNANCE_CRISIS` | Real structural disruption visible in the review graph — organisational restructuring, corporate withdrawal, or brief crisis — but without fork-level evidence. |
| `FRACTURE_IMMINENT` | Unresolved factional tension with adversarial review signal — split may be imminent. Requires both high asymmetry AND adversarial evidence (avgFactionSignal or crossCommunityScore >= 0.05); without adversarial signal, downgrades to TENSION. |
| `TENSION` | Elevated review asymmetry without adversarial dynamics. Common in single-gatekeeper or BDFL projects where structural asymmetry is high but reviews are constructive. Worth monitoring but not an imminent fork risk. |
| `EXODUS` | Gradual sustained elevation that resolved — coordinated departure without adversarial spike |
| `ATTRITION` | Natural contributor lifecycle turnover — succession problem, not faction problem |
| `STABLE` | No significant asymmetry detected |

> **Minimum data requirement:** 4 months (120 days) of PR review history. A confirmed fork pattern requires 9 consecutive weeks of elevated asymmetry plus pre- and post-cluster context — roughly 16 rolling windows.

---

## Example: The node-pocolypse (2013–2015)

```
faction-detector:> analyse --repo nodejs/node --since 2013-06-01 --until 2015-06-01
```

```
+----------------+-----------------------------------+
| Pattern        | FRACTURE_ADVERSARIAL_FORK         |
| Severity       | EXTREME                           |
| Confidence     | 75%                               |
| Peak tension   | 2014-12-10 (asymmetry 1.00)       |
| Status         | RESOLVED — RE-ESCALATION DETECTED |
| Fracture event | 2015-01-17                        |
| Resolution     | 2015-01-24                        |
+----------------+-----------------------------------+
```

The io.js fork was announced December 9, 2014. The review graph peaked **November 15** — 3 weeks earlier. The model found the right people, the right month, the right severity from review patterns alone. No commit messages. No mailing lists. No drama threads.

---

## Limitations

- **Minimum project size**: Results are unreliable for projects with fewer than ~5 active reviewers in a given window. When only 2–3 people are reviewing, asymmetry scores collapse to binary 0/1 noise — the metric requires a real reviewer graph to be meaningful. Projects in managed decline (e.g. a framework superseded by its own successor) often fall below this threshold and can produce spurious FRACTURE_IMMINENT readings.
- **GitHub PR reviews only**: The tool only sees review activity on GitHub pull requests. Projects that use email, Gerrit, Phabricator, or bot-mediated approvals will appear to have little or no data.
- **Review asymmetry ≠ conflict**: High asymmetry can reflect structural specialisation (separate frontend/backend teams) as well as adversarial dynamics. The LLM narrative attempts to distinguish these, but treat results as signals to investigate, not verdicts.
- **Comment scoring model**: Stage-2 comment scoring uses `claude-haiku-4-5` by default for speed and cost. Haiku tends to classify ambiguous comments as `FAIR` rather than `NITPICKY`, which slightly suppresses faction signals on marginal cases. To use a more nuanced model, change `AnthropicModels.CLAUDE_HAIKU_4_5` to `AnthropicModels.CLAUDE_SONNET_4_5` in `ReviewCommentScorer.kt` and bump `COMMENT_SCORE_CACHE_VERSION` to invalidate cached scores. Note that if you tune `DetectorWeights` to compensate for Haiku's FAIR bias, those weights will not transfer correctly to Sonnet.
- **Bot accounts**: Accounts matching common bot patterns (`[bot]`, `-bot`) and a built-in list of known service accounts (codecov-io, coveralls, CLAassistant, etc.) are filtered automatically. Project-specific automation accounts (e.g. `elasticmachine`) are not. If the narrative mentions a bot or automation account as a significant reviewer or bridge figure, re-run with the `--bots` flag to exclude it:
  ```
  analyse --repo elastic/elasticsearch --since 2020-01-01 --bots elasticmachine,merge-bot
  ```

---

## Prerequisites

### Java (JDK 21+)

If you don't have Java installed, the easiest way is [SDKMAN](https://sdkman.io) — a version manager that works on Mac and Linux:

```bash
curl -s "https://get.sdkman.io" | bash   # installs SDKMAN
sdk install java 21-tem                  # installs Temurin JDK 21
```

On Mac you can also use Homebrew: `brew install openjdk@21`

Verify it worked: `java -version` should show `21` or higher.

### Docker

Neo4j (the graph database) runs in Docker. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) if you don't have it, then verify with `docker ps`.

### GitHub personal access token

Create one at **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens**. It only needs read access to public repositories — no write permissions required.

### Anthropic API key

Get one at **[console.anthropic.com](https://console.anthropic.com)**.

---

## Neo4j with Docker

Faction Detector requires Neo4j with the **Graph Data Science (GDS)** plugin for community detection. Start it with:

```bash
docker run \
  --name neo4j-factions \
  -p 7474:7474 -p 7687:7687 \
  -v $HOME/.neo4j-factions/data:/data \
  -e NEO4J_AUTH=neo4j/brahmsian \
  -e NEO4J_PLUGINS='["graph-data-science"]' \
  neo4j:5
```

On first start Neo4j downloads and installs GDS automatically — allow a minute or two. Once ready, open `http://localhost:7474` and run:

```cypher
CREATE DATABASE factions IF NOT EXISTS
```

> **Note:** Tests use a Neo4j testcontainer automatically — no local Neo4j needed to run the test suite.

---

## Running

### 1. Set your API keys

```bash
export FACTION_GITHUB_TOKEN=ghp_...    # GitHub personal access token
export ANTHROPIC_API_KEY=sk-ant-...    # Anthropic API key
```

Add both to your shell profile (`~/.zshrc` on Mac, `~/.bashrc` on Linux) so you don't have to set them each session:

```bash
echo 'export FACTION_GITHUB_TOKEN=ghp_...'   >> ~/.zshrc
echo 'export ANTHROPIC_API_KEY=sk-ant-...'   >> ~/.zshrc
source ~/.zshrc
```

### 2. Start Neo4j

See the [Neo4j with Docker](#neo4j-with-docker) section below, then come back here.

### 3. Build

The project includes a Maven wrapper — no need to install Maven separately:

```bash
./mvnw install -DskipTests
```

This downloads dependencies and compiles the project. First run takes a few minutes.

### 4a. Interactive shell

```bash
./mvnw spring-boot:run
```

Then at the prompt:

```
faction-detector:> analyse --repo nodejs/node --since 2013-06-01 --until 2015-06-01
faction-detector:> analyse --repo redis/redis --days 365
```

### 4b. One-shot CLI

The `faction` script in the project root wraps the built jar:

```bash
./faction analyse --repo nodejs/node --since 2013-06-01 --until 2015-06-01
./faction analyse --repo redis/redis --days 365
```
```

This is useful for batch runs or scripting hypotheses overnight:

```bash
#!/bin/bash
./faction analyse --repo nodejs/node --since 2018-06-01 --until 2020-01-01
./faction analyse --repo babel/babel  --since 2020-06-01 --until 2022-06-01
./faction analyse --repo rust-lang/rust --since 2021-06-01 --until 2023-01-01
```

---

## How it works

1. Fetch all PR review comments from the GitHub API for the repo + date range
2. Build a directed weighted graph: reviewer → author edges
3. Score each reviewer→author pair for asymmetry and anomaly vs baseline
4. Run Neo4j GDS Louvain community detection on the weighted graph
5. Roll a 30-day window across the period, computing asymmetry ratio per window
6. Detect peak clusters, classify fracture pattern, detect contributor exodus step-changes
7. Feed everything into an LLM for narrative analysis

---

## Testing

```bash
mvn test                                    # unit tests (no external dependencies)
mvn test -DexcludedGroups='' -Dgroups=e2e   # E2E corpus tests (requires Neo4j + GitHub token)
```

The E2E corpus includes 26 confirmed test cases across 15 repos — confirmed fractures (nodejs io.js, redis Valkey, terraform BSL, moby Docker Enterprise, RedisGraph/FalkorDB, gogs/gitea, presto/trino), true negatives (kubernetes, django, rails, fastapi, next.js), and pre-fork/quiet-period validations.

**Snapshot cache**: E2E tests save gzipped intermediate data (`WindowedScores`) to `src/test/resources/snapshots/`. In CI (`CI=true`), tests load snapshots from classpath — no GitHub, Neo4j, or LLM calls needed. Locally, snapshots are always rebuilt from the full pipeline.

---

## Contributors

[![Faction Detector contributors](https://contrib.rocks/image?repo=jasperblues/faction-detector)](https://github.com/jasperblues/faction-detector/graphs/contributors)
