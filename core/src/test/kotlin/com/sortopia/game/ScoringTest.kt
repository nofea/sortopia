package com.sortopia.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScoringTest {
    @Test
    fun `par moves at par time scores full base points and three stars`() {
        val b = Scoring.score(basePoints = 200, par = 10, moves = 10, parTimeSeconds = 30, timeSeconds = 30.0)
        assertEquals(200, b.score)
        assertEquals(3, b.stars)
    }

    @Test
    fun `beating par is capped at full points`() {
        assertEquals(200, Scoring.score(200, 10, 8, 30, 10.0).score)
    }

    @Test
    fun `formula weights moves 70 percent and time 30 percent`() {
        // moves factor 10/20 = 0.5, time factor 30/60 = 0.5 -> 0.5 * 200
        assertEquals(100, Scoring.score(200, 10, 20, 30, 60.0).score)
        // moves perfect, time 30/90 = 1/3 -> 200 * (0.7 + 0.1) = 160
        assertEquals(160, Scoring.score(200, 10, 10, 30, 90.0).score)
    }

    @Test
    fun `star thresholds are 60 and 90 percent of base points`() {
        assertEquals(1, Scoring.stars(119, 200))
        assertEquals(2, Scoring.stars(120, 200))
        assertEquals(2, Scoring.stars(179, 200))
        assertEquals(3, Scoring.stars(180, 200))
        assertEquals(0, Scoring.stars(null, 200))
    }
}
