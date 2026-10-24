package com.sortopia.gen

import com.sortopia.model.LevelId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Manual survey of every level: `./gradlew :core:test -Dsurvey=true --tests '*GenerationSurvey*'`. */
@EnabledIfSystemProperty(named = "survey", matches = "true")
class GenerationSurveyTest {
    @Test
    fun survey() {
        val gen = LevelGenerator()
        val ids = (1..8).flatMap { w -> (1..20).map { LevelId.Campaign(w, it) } } + (1..200 step 9).map { LevelId.Endless(it) }
        for (id in ids) {
            val t0 = System.nanoTime()
            val lvl = gen.generate(id)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val stage = lvl.attempt / LevelGenerator.ATTEMPTS_PER_STAGE
            println(
                "${id.key}\ttypes=${lvl.params.types}\ttubes=${lvl.board.tubes.size}\tspares+=$stage" +
                    "\tattempt=${lvl.attempt}\tpar=${lvl.par}\tms=$ms\t${lvl.params.mechanics}",
            )
        }
    }
}
