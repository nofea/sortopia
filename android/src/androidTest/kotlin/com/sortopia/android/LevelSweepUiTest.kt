package com.sortopia.android

import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.SaveData
import com.sortopia.save.Settings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Plays a spread of levels through the real UI: the start, middle and end of every world (so
 * every mechanic and every item set), the Daily puzzle, each Endless mix, and the colorblind
 * palette. Every pour is checked against what is drawn (GameDriver.assertBoardReadable and the
 * pour check), and each board is screenshotted as `sweep-<case>`.
 */
@RunWith(Parameterized::class)
class LevelSweepUiTest(
    private val case: Case,
) {
    /** One level to play: [open] navigates from the World Map to it. */
    data class Case(
        val name: String,
        val endlessSolved: Int = 0,
        val colorblind: Boolean = false,
        val open: (GameDriver) -> Unit,
    ) {
        override fun toString() = name
    }

    private val d = GameDriver()

    @get:Rule
    val closeApp =
        object : TestWatcher() {
            override fun failed(
                e: Throwable?,
                description: Description,
            ) = d.screenshot("FAILED-sweep-${case.name}")

            override fun finished(description: Description) = d.close()
        }

    @Before
    fun setUp() {
        d.clearSave()
        // Everything before the case is solved, so every world, Endless and the Daily are open.
        val campaign = (1..LevelId.WORLDS).flatMap { w -> (1..LevelId.LEVELS_PER_WORLD).map { LevelId.Campaign(w, it) } }
        val endless = (1..case.endlessSolved).map { LevelId.Endless(it) }
        val best = (campaign + endless).associate { it.key to Tuning.basePoints(it) }
        d.seed(
            SaveData(
                best = best,
                flags = listOf("tutorial-done", "tips-helpers"),
                settings = Settings(colorblindPalette = case.colorblind),
            ),
        )
    }

    @Test
    fun playsThroughAndLooksRight() {
        d.launch()
        case.open(d)
        d.waitLevelReady()
        d.screenshot("sweep-${case.name}")
        d.solveLevel()
    }

    companion object {
        private val SAMPLE_LEVELS = listOf(1, 10, LevelId.LEVELS_PER_WORLD)

        /** First level of each Endless mix (SPEC §6.1: the mix changes every 10 levels). */
        private val ENDLESS_LEVELS = listOf(1, 11, 21)

        private fun campaign(
            world: Int,
            level: Int,
        ): (GameDriver) -> Unit =
            { d ->
                d.tap("world-$world")
                d.waitScreen("levels")
                d.tap("level-$level")
            }

        /** Opens Endless level [n] as the next one to play ([Case.endlessSolved] = n - 1). */
        private val endless: (GameDriver) -> Unit = { d ->
            d.tap("endless")
            d.waitScreen("endless")
            d.tap("endless-continue")
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> =
            (1..LevelId.WORLDS).flatMap { w -> SAMPLE_LEVELS.map { l -> Case("c-$w-$l", open = campaign(w, l)) } } +
                // The Daily's date is the device's, so its layout changes from day to day.
                Case("daily", open = { it.tap("daily") }) +
                ENDLESS_LEVELS.map { n -> Case("e-$n", endlessSolved = n - 1, open = endless) } +
                Case("c-8-20-colorblind", colorblind = true, open = campaign(LevelId.WORLDS, LevelId.LEVELS_PER_WORLD))
    }
}
