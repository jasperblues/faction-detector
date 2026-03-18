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

import org.drivine.manager.GraphObjectManager
import org.drivine.transaction.DrivineTransactional
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository

/**
 * Read-side repository for contributor graph queries.
 */
@Repository
class GraphRepository(
    @param:Qualifier("factionsGraphObjectManager") private val graphObjectManager: GraphObjectManager,
) {
    @DrivineTransactional
    fun findAllContributors(): List<ContributorReviews> =
        graphObjectManager.loadAll(ContributorReviews::class.java)

    @DrivineTransactional
    fun findContributor(login: String): ContributorReviews? =
        graphObjectManager.load(login, ContributorReviews::class.java)

}
