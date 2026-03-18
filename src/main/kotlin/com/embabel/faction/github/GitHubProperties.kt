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
package com.embabel.faction.github

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Controls which GitHub data is used to build reviewer→author edges.
 *
 * - [REVIEWS] — formal review events only (APPROVED / CHANGES_REQUESTED). Requires GitHub's
 *   review system (introduced September 2016). Best signal quality when available.
 * - [COMMENTS] — inline PR comments as proxy reviews. Works for pre-2016 repos and repos
 *   where contributors comment without formally submitting a review. Sentiment scoring
 *   substitutes for review state.
 * - [BOTH] — union of reviews and comments. Comment edges are only added where no formal
 *   review edge already exists for that reviewer+PR combination.
 */
enum class EdgeSource { REVIEWS, COMMENTS, BOTH }

@ConfigurationProperties(prefix = "faction.github")
data class GitHubProperties(
    val token: String = "",
    val baseUrl: String = "https://api.github.com",
    val maxPrsPerRepo: Int = 5000,
    val rateLimitBackoffMs: Long = 60_000,
    val edgeSource: EdgeSource = EdgeSource.REVIEWS,
)
