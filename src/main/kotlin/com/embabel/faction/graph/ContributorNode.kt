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
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Neo4j node fragment representing a GitHub contributor.
 */
@NodeFragment(labels = ["Contributor"])
@JsonIgnoreProperties(ignoreUnknown = true)
data class ContributorNode(
    @NodeId
    val login: String,
    /** Community ID assigned by GDS Louvain on the anomaly-weighted review graph. */
    val communityId: Int? = null,
)
