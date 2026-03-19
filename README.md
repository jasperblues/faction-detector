<img align="left" src="logo.png" width="180">

# Faction Detector

![Build](https://github.com/embabel/faction-detector/actions/workflows/maven.yml/badge.svg)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white) ![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white) ![Neo4j](https://img.shields.io/badge/Neo4j-008CC1?style=for-the-badge&logo=neo4j&logoColor=white) ![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

<br clear="left"/>

> *Open source projects fork. Enterprise codebases rot. The review graph tells you which one is coming — and when.*

Gauge the health of open (or closed!) source projects using nothing but pull request comment dynamics.

> ⚠️ **Warning:** This tool can detect forks before they occur. Use responsibly.

📖 **[Read the full analysis: Faction Detection — Reading the Review Graph Before the Fork](https://embabel.com/blog/faction-detector)** — retrodiction results across nodejs, redis, hashicorp/terraform, babel, and a live case with no known resolution yet.

Faction Detector analyses GitHub PR review activity to detect contributor faction dynamics and predict project splits. It builds a weighted directed graph of who reviews whom, runs community detection, and scores asymmetry across rolling time windows — then feeds everything into an LLM for narrative analysis.

Built with [Embabel](https://github.com/embabel/embabel-agent) + Spring Boot + Neo4j + GDS.

---

## What it detects

| Pattern | Description |
|---------|-------------|
| `FRACTURE_OCCURRED` | Sharp asymmetry spike followed by resolution — fork or mass departure happened |
| `FRACTURE_IMMINENT` | Peak is current and unresolved — split may be imminent |
| `EXODUS` | Gradual sustained elevation that resolved — coordinated departure without fork |
| `ATTRITION` | Natural contributor lifecycle turnover — succession problem, not faction problem |
| `STABLE` | No significant asymmetry detected |

---

## Example: The node-pocolypse (2013–2015)

```
faction-detector:> analyse --repo nodejs/node --since 2013-06-01 --until 2015-06-01
```

```
+----------------+---------------------------------------------------------------------------------+
| Pattern        | FRACTURE_OCCURRED                                                               |
| Severity       | EXTREME                                                                         |
| Confidence     | 80%                                                                             |
| Peak tension   | 2014-12-10 (asymmetry 1.00)                                                     |
| Status         | RESOLVED                                                                        |
| Fracture event | 2015-05-29                                                                      |
| Resolution     | 2015-01-24                                                                      |
| Exodus date    | 2015-05-29                                                                      |
| Mass drop      | 46%  (673.0 → 366.3)                                                            |
| Split ratio    | 46:54  — near-equal split, departing faction was viable                         |
| Core impact    | 11.7% of total project centrality                                               |
| Departed       | piscisaureus(42)  chrisdickinson(27)  brendanashworth(8)  mscdex(8)  domenic(7) |
+----------------+---------------------------------------------------------------------------------+
```

The io.js fork was announced December 9, 2014. The review graph peaked **November 15** — 3 weeks earlier. The model found the right people, the right month, the right severity from review patterns alone. No commit messages. No mailing lists. No drama threads.

---

## Prerequisites

- JDK 21+
- Neo4j running locally with GDS plugin installed
- GitHub personal access token (with `repo` read scope)
- Anthropic API key (or other Spring AI-compatible LLM)

---

## Running

### 1. GitHub token

Create a personal access token at **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens**. It only needs read access to public repositories — no write permissions required.

```bash
export FACTION_GITHUB_TOKEN=ghp_...
```

### 2. Anthropic API key

Get an API key at **[console.anthropic.com](https://console.anthropic.com)**.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Add both to your shell profile (`~/.zshrc`, `~/.bashrc`) to avoid setting them each session.

### 3. Start the shell:

```bash
mvn spring-boot:run
```

Analyse a repo:

```bash
# Named date range
analyse --repo nodejs/node --since 2013-06-01 --until 2015-06-01

# Last N days
analyse --repo redis/redis --days 365
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
mvn test
```

---

## Contributors

[![Faction Detector contributors](https://contrib.rocks/image?repo=embabel/faction-detector)](https://github.com/embabel/faction-detector/graphs/contributors)