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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.drivine.annotation.RelationshipFragment

/**
 * Rich relationship fragment: a review event with properties stored on the Neo4j relationship.
 * The [target] is the PR author being reviewed.
 */
@RelationshipFragment
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewRelationship(
    val repo: String,
    val timestamp: String,
    val state: String,
    val commentCount: Int,
    val weight: Double,
    val hoursToFirstReview: Double?,
    val daysMergedAfterReview: Double?,
    val target: ContributorNode,
    /** Pair anomaly score written back after stage-1 scoring; used as GDS Louvain edge weight. */
    val anomalyScore: Double? = null,
    /** Faction signal written back after stage-2 LLM scoring; fraction of NITPICKY+NON_BLOCKING comments. */
    val factionSignal: Double? = null,
)
