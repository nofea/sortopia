package com.sortopia.game

import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.SaveData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ProgressTest {
    private fun c(
        w: Int,
        l: Int,
    ) = LevelId.Campaign(w, l)

    /** Solves every level of worlds 1..[through] with the given star count. */
    private fun solved(
        through: Int,
        stars: Int = 3,
    ): SaveData {
        val best = HashMap<String, Int>()
        for (w in 1..through) {
            for (l in 1..20) {
                val base = Tuning.basePoints(c(w, l))
                val score =
                    when (stars) {
                        3 -> base
                        2 -> (base * 0.6).toInt() + 1
                        else -> 1
                    }
                best[c(w, l).key] = score
            }
        }
        return SaveData(best = best)
    }

    private fun result(score: Int) = Scoring.Breakdown(score, 1.0, 1.0, 3)

    @Test
    fun `levels unlock in order within a world`() {
        val p = Progress(SaveData())
        assertTrue(p.isUnlocked(c(1, 1)))
        assertFalse(p.isUnlocked(c(1, 2)))
        p.recordSolve(c(1, 1), result(50))
        assertTrue(p.isUnlocked(c(1, 2)))
        assertFalse(p.isUnlocked(c(2, 1)))
    }

    @Test
    fun `next world needs last level solved and 60 percent of stars so far`() {
        assertTrue(Progress(solved(1, stars = 2)).isWorldUnlocked(2))
        // 1 star everywhere = 20 of 60 stars, below the 36 needed.
        val low = Progress(solved(1, stars = 1))
        assertFalse(low.isWorldUnlocked(2))
        assertEquals(16, low.starsMissingFor(2))
        assertEquals(36, low.starsRequiredFor(2))
        assertEquals(72, low.starsRequiredFor(3))
    }

    @Test
    fun `replays pay full points every time but best score never goes down`() {
        val p = Progress(SaveData(wallet = 0))
        p.recordSolve(c(1, 1), result(80))
        val second = p.recordSolve(c(1, 1), result(60))
        assertEquals(140, p.wallet.points)
        assertFalse(second.newBest)
        assertEquals(80, p.bestScore(c(1, 1)))
    }

    @Test
    fun `daily pays double once per date and tracks the streak`() {
        val p = Progress(solved(1).copy(wallet = 0))
        val today = LocalDate.of(2026, 10, 21)
        assertTrue(p.isDailyUnlocked())
        val first = p.recordSolve(LevelId.Daily(today, 2), result(100))
        assertTrue(first.dailyBonus)
        assertEquals(200, first.pointsEarned)
        val replay = p.recordSolve(LevelId.Daily(today, 2), result(100))
        assertFalse(replay.dailyBonus)
        assertEquals(300, p.wallet.points)
        p.recordSolve(LevelId.Daily(today.minusDays(1), 2), result(10))
        assertEquals(2, p.dailyStreak(today))
        assertEquals(2, p.dailyStreak(today.plusDays(1)))
        assertEquals(0, p.dailyStreak(today.plusDays(2)))
    }

    @Test
    fun `clock moved backwards does not re-grant a claimed bonus`() {
        val p = Progress(solved(1).copy(wallet = 0))
        val day = LocalDate.of(2026, 10, 21)
        p.recordSolve(LevelId.Daily(day, 1), result(100))
        p.recordSolve(LevelId.Daily(day.plusDays(1), 1), result(100))
        // The clock goes back a day: that date's bonus was already claimed.
        assertFalse(p.recordSolve(LevelId.Daily(day, 1), result(100)).dailyBonus)
    }

    @Test
    fun `endless unlocks after world 8 and continues from the highest solved`() {
        val p = Progress(solved(8))
        assertTrue(p.isEndlessUnlocked())
        assertEquals(1, p.nextEndless())
        p.recordSolve(LevelId.Endless(1), result(300))
        p.recordSolve(LevelId.Endless(2), result(400))
        assertEquals(3, p.nextEndless())
        assertEquals(700, p.endlessTotal())
        assertFalse(p.isUnlocked(LevelId.Endless(4)))
    }

    @Test
    fun `reached endless levels are listed in pages of 20`() {
        val p = Progress(solved(8))
        assertEquals(1, EndlessPages.count(p.nextEndless()))
        assertEquals(1..1, EndlessPages.levels(0, p.nextEndless()))
        for (n in 1..20) p.recordSolve(LevelId.Endless(n), result(300))
        // Level 21 is reached (playable) but unsolved, so it opens a second page.
        val reached = p.nextEndless()
        assertEquals(2, EndlessPages.count(reached))
        assertEquals(1..20, EndlessPages.levels(0, reached))
        assertEquals(21..21, EndlessPages.levels(1, reached))
        assertEquals(1, EndlessPages.pageOf(21))
        assertEquals(0, EndlessPages.pageOf(20))
    }

    @Test
    fun `next endless level never goes past the last one`() {
        val data = solved(8)
        val p = Progress(data.copy(best = data.best + (LevelId.Endless(LevelId.MAX_ENDLESS).key to 1)))
        val reached = p.nextEndless()
        assertEquals(LevelId.MAX_ENDLESS, reached)
        assertEquals(LevelId.MAX_ENDLESS, EndlessPages.levels(EndlessPages.count(reached) - 1, reached).last)
    }

    @Test
    fun `daily tier is the furthest unlocked world`() {
        assertEquals(1, Progress(SaveData()).furthestWorld())
        assertEquals(4, Progress(solved(3)).furthestWorld())
    }
}
