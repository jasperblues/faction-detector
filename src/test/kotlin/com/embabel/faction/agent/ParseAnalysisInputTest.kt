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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ParseAnalysisInputTest {

    @Test
    fun `days format parses correctly`() {
        val req = parseAnalysisInput("nodejs/node 90")
        assertEquals("nodejs", req.owner)
        assertEquals("node", req.repo)
        assertNull(req.until)
    }

    @Test
    fun `date range format parses since and until`() {
        val req = parseAnalysisInput("nodejs/node 2013-06-01 2015-06-01")
        assertEquals("nodejs", req.owner)
        assertEquals("node", req.repo)
        val expectedSince = LocalDate.parse("2013-06-01").atStartOfDay(ZoneOffset.UTC).toInstant()
        val expectedUntil = LocalDate.parse("2015-06-01").atStartOfDay(ZoneOffset.UTC).toInstant()
        assertEquals(expectedSince, req.since)
        assertEquals(expectedUntil, req.until)
    }

    @Test
    fun `since-only date format parses with null until`() {
        val req = parseAnalysisInput("nodejs/node 2013-06-01")
        val expectedSince = LocalDate.parse("2013-06-01").atStartOfDay(ZoneOffset.UTC).toInstant()
        assertEquals(expectedSince, req.since)
        assertNull(req.until)
    }

    @Test
    fun `defaults to 180 days when no second arg`() {
        val req = parseAnalysisInput("nodejs/node")
        assertNull(req.until)
        // since should be approximately 180 days ago
        val expectedApprox = java.time.Instant.now().minusSeconds(180L * 86400)
        val diff = Math.abs(req.since.epochSecond - expectedApprox.epochSecond)
        assertTrue(diff < 5, "since should be ~180 days ago")
    }

    @Test
    fun `--bots flag is parsed into excludeBots set`() {
        val req = parseAnalysisInput("elastic/elasticsearch 2020-09-01 2022-06-01 --bots=elasticmachine,dependabot")
        assertEquals("elastic", req.owner)
        assertEquals("elasticsearch", req.repo)
        assertEquals(setOf("elasticmachine", "dependabot"), req.excludeBots)
    }

    @Test
    fun `excludeBots is empty when --bots not provided`() {
        val req = parseAnalysisInput("nodejs/node 2013-06-01 2015-06-01")
        assertTrue(req.excludeBots.isEmpty())
    }
}
