package com.sortopia.gen

import com.sortopia.model.LevelId
import com.sortopia.solver.Solver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "survey", matches = "true")
class SpareSurveyTest {
    @Test
    fun survey() {
        for (id in listOf(LevelId.Campaign(8, 11), LevelId.Endless(28), LevelId.Endless(82), LevelId.Endless(1))) {
            val params = LevelGenerator.paramsFor(id)
            val counts = HashMap<String, Int>()
            var ms = 0L
            for (s in 0 until 12) {
                val b = LevelGenerator.build(params, Rng(s.toLong())) ?: continue
                val t0 = System.nanoTime()
                val r = Solver().solve(b)
                ms += (System.nanoTime() - t0) / 1_000_000
                val k = r::class.simpleName!!
                counts[k] = (counts[k] ?: 0) + 1
            }
            println("DIAG ${id.key} ${params.mechanics} types=${params.types} $counts ms=$ms")
        }
    }
}
