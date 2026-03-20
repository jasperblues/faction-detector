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
package com.embabel.faction.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SentimentTest {

    @Test
    fun `constructor rejects out-of-range values`() {
        assertThrows<IllegalArgumentException> { Sentiment(1.1) }
        assertThrows<IllegalArgumentException> { Sentiment(-1.1) }
    }

    @Test
    fun `constructor accepts boundary values`() {
        assertEquals(-1.0, Sentiment(-1.0).value)
        assertEquals(1.0, Sentiment(1.0).value)
        assertEquals(0.0, Sentiment(0.0).value)
    }

    @Test
    fun `of clamps rather than throws`() {
        assertEquals(1.0, Sentiment.of(99.0).value)
        assertEquals(-1.0, Sentiment.of(-99.0).value)
        assertEquals(0.5, Sentiment.of(0.5).value)
    }

    @Test
    fun `neutral sentiment contributes zero faction signal`() {
        val comment = ScoredComment(
            body = "nit: rename this",
            diffHunk = "",
            significance = CommentSignificance.NITPICKY,
            blocking = BlockingNature.NON_BLOCKING,
            sentiment = Sentiment.NEUTRAL,
        )
        val pair = ScoredPair("alice", "bob", 1.0, listOf(comment))
        // 100% nitpicky * 0.5 + 0 hostile sentiment * 0.5 = 0.5
        assertEquals(0.5, pair.factionSignal, 0.001)
    }

    @Test
    fun `hostile sentiment on nitpicky comment pushes signal toward 1`() {
        val comment = ScoredComment(
            body = "this is terrible",
            diffHunk = "",
            significance = CommentSignificance.NITPICKY,
            blocking = BlockingNature.NON_BLOCKING,
            sentiment = Sentiment.STRONGLY_HOSTILE,
        )
        val pair = ScoredPair("alice", "bob", 1.0, listOf(comment))
        // 1.0 * 0.6 + 1.0 * 0.4 = 1.0
        assertEquals(1.0, pair.factionSignal, 0.001)
    }

    @Test
    fun `positive sentiment on fair comment gives near-zero signal`() {
        val comment = ScoredComment(
            body = "great work, just a small suggestion",
            diffHunk = "",
            significance = CommentSignificance.FAIR,
            blocking = BlockingNature.NON_BLOCKING,
            sentiment = Sentiment.STRONGLY_POSITIVE,
        )
        val pair = ScoredPair("alice", "bob", 1.0, listOf(comment))
        // 0 nitpicky * 0.6 + 0 hostile * 0.4 = 0.0
        assertEquals(0.0, pair.factionSignal, 0.001)
    }
}
