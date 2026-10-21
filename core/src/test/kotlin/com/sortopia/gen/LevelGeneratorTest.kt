package com.sortopia.gen

import com.sortopia.game.GameSession
import com.sortopia.game.Wallet
import com.sortopia.model.LevelId
import com.sortopia.rules.PourResult
import com.sortopia.solver.Solver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class LevelGeneratorTest {
    private val gen = LevelGenerator()

    @Test
    fun `rng is deterministic and stable across versions`() {
        val r = Rng(42)
        assertEquals(listOf(6, 5, 9, 2, 5), List(5) { r.nextInt(10) })
        assertEquals(-1829201268380037444L, Rng.seedOf("sortopia"))
    }

    /**
     * Golden layouts: these fingerprints must only change together with Tuning.GENERATOR_VERSION,
     * otherwise players' levels would silently change (SPEC §8).
     */
    @Test
    fun `generation is deterministic - golden fingerprints`() {
        val golden =
            mapOf(
                LevelId.Campaign(1, 1) to GOLDEN_1_1,
                LevelId.Campaign(2, 7) to GOLDEN_2_7,
                LevelId.Campaign(6, 3) to GOLDEN_6_3,
            )
        val actual =
            golden.keys.associateWith { id ->
                val a = gen.generate(id)
                val b = LevelGenerator().generate(id)
                assertEquals(Solver.key(a.board), Solver.key(b.board))
                assertEquals(a.solution, b.solution)
                fingerprint(a)
            }
        assertEquals(golden, actual)
    }

    @ParameterizedTest
    @MethodSource("sampleLevels")
    fun `generated levels follow their params and the solution solves them`(id: LevelId) {
        val lvl = gen.generate(id)
        val p = lvl.params
        val tubes = lvl.board.tubes
        val spares = tubes.count { it.isEmpty }
        assertTrue(spares >= p.spares, "at least ${p.spares} spare tubes")
        assertEquals(p.types, tubes.flatMap { t -> t.items.map { it.type } }.toSet().size)
        assertFalse(tubes.any { it.isComplete }, "no tube starts complete")
        assertFalse(tubes.any { it.top?.hidden == true }, "top items are never hidden")
        if (Mechanic.HIDDEN in p.mechanics) assertTrue(tubes.any { t -> t.items.any { it.hidden } })
        if (Mechanic.LOCKED in p.mechanics) assertTrue(tubes.any { it.isLocked })
        if (Mechanic.SINGLE_TYPE in p.mechanics) assertTrue(tubes.any { it.onlyType != null })
        if (Mechanic.GLUED in p.mechanics) assertTrue(tubes.any { t -> t.items.any { it.gluedUp } })
        if (Mechanic.FROZEN in p.mechanics) assertTrue(tubes.any { t -> t.items.any { it.isFrozen } })
        if (!p.mixedCapacity) assertTrue(tubes.all { it.capacity == p.height })

        val session = GameSession(lvl, Wallet(0))
        for (m in lvl.solution) assertTrue(session.pour(m) is PourResult.Ok)
        assertTrue(session.isSolved)
        assertEquals(lvl.par * GeneratedLevel.PAR_SECONDS_PER_MOVE, lvl.parTimeSeconds)
    }

    @Test
    fun `daily puzzle depends on date and tier`() {
        val d = LocalDate.of(2026, 10, 21)
        val a = gen.generate(LevelId.Daily(d, 1))
        assertEquals(Solver.key(a.board), Solver.key(gen.generate(LevelId.Daily(d, 1)).board))
        assertFalse(Solver.key(a.board) == Solver.key(gen.generate(LevelId.Daily(d.plusDays(1), 1)).board))
    }

    @Test
    fun `difficulty rises across the campaign and breathers are easier`() {
        assertTrue(Tuning.campaign(1, 20, Rng(0)).types > Tuning.campaign(1, 1, Rng(0)).types)
        assertTrue(Tuning.campaign(8, 1, Rng(0)).types > Tuning.campaign(7, 1, Rng(0)).types)
        assertTrue(Tuning.isBreather(5) && Tuning.isBreather(15) && !Tuning.isBreather(20))
        assertEquals(Tuning.progress(7), Tuning.progress(10))
        assertEquals(100, Tuning.basePoints(LevelId.Campaign(1, 1)))
        assertEquals(600, Tuning.basePoints(LevelId.Campaign(8, 20)))
        assertEquals(1000, Tuning.basePoints(LevelId.Endless(5000)))
        assertTrue((1..8).all { w -> (1..20).all { Tuning.campaign(w, it, Rng(0)).spares >= Tuning.MIN_SPARES } })
    }

    companion object {
        // Filled from the current generator; update only together with Tuning.GENERATOR_VERSION.
        const val GOLDEN_1_1 = -1847923358675599864L
        const val GOLDEN_2_7 = 3554175998770560680L
        const val GOLDEN_6_3 = -7763656377916272691L

        fun fingerprint(level: GeneratedLevel): Long = Rng.seedOf(Solver.key(level.board) + "#" + level.solution)

        @JvmStatic
        fun sampleLevels(): List<LevelId> =
            listOf(
                LevelId.Campaign(1, 1),
                LevelId.Campaign(1, 20),
                LevelId.Campaign(2, 10),
                LevelId.Campaign(3, 4),
                LevelId.Campaign(4, 18),
                LevelId.Campaign(5, 9),
                LevelId.Campaign(6, 12),
                LevelId.Campaign(7, 6),
                LevelId.Campaign(8, 3),
            )
    }
}
