package com.sortopia.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sortopia.game.Helpers
import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.SaveData
import com.sortopia.save.SaveStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** End-to-end tests on an emulator (CLAUDE.md, Testing). */
@RunWith(AndroidJUnit4::class)
class SortopiaUiTest {
    private val d = GameDriver()

    @Before
    fun setUp() {
        d.clearSave()
    }

    @After
    fun tearDown() {
        d.close()
    }

    private fun openLevel(
        world: Int,
        level: Int,
    ) {
        d.tap("world-$world")
        d.waitScreen("levels")
        d.tap("level-$level")
        d.waitLevelReady()
    }

    @Test
    fun splashShowsTitleThenOpensWorldMap() {
        val start = System.currentTimeMillis()
        d.launchToSplash()
        d.waitScreen("splash")
        d.waitUntil("title on screen") { DebugTestBridge.bounds("splash-title") != null }
        d.waitUntil("animation finished") { d.state("splash.animated") == "true" }
        d.screenshot("splash")
        d.waitScreen("map", GameDriver.LAUNCH_TIMEOUT)
        val shown = System.currentTimeMillis() - start
        assertTrue("splash lasted its 3 s minimum, was $shown ms", shown >= SPLASH_MIN_MS)
        assertTrue("splash ended by its 8 s cap, was $shown ms", shown <= SPLASH_CAP_MS + SPLASH_SLACK_MS)
    }

    @Test
    fun tutorialLevelCanBeSolvedAndPaysPoints() {
        d.launch()
        openLevel(1, 1)
        assertEquals(Helpers.STARTING_WALLET.toString(), d.state("wallet"))
        d.solveLevel()
        d.screenshot("level-complete")
        val wallet = d.state("wallet")!!.toInt()
        assertTrue("points were added", wallet > Helpers.STARTING_WALLET)
        d.close()
        val save = d.readSave()
        assertTrue(save.best.containsKey(LevelId.Campaign(1, 1).key))
        assertTrue("tutorial-done" in save.flags)
        assertEquals(wallet, save.wallet)
    }

    @Test
    fun helpersSpendPoints() {
        d.launch()
        openLevel(1, 1)
        val (from, to) = d.solution().first()
        d.pour(from, to)
        d.tap("helper-undo")
        d.waitUntil("undo charged") { d.state("wallet") == (Helpers.STARTING_WALLET - Helpers.UNDO_COST).toString() }
        assertEquals("1", d.state("moves"))

        d.tap("helper-hint")
        d.waitUntil("hint shown") { !d.state("hint").isNullOrEmpty() }
        val afterHint = Helpers.STARTING_WALLET - Helpers.UNDO_COST - Helpers.HINT_COST
        assertEquals(afterHint.toString(), d.state("wallet"))

        val tubes = d.state("tubes")!!.toInt()
        d.tap("helper-extra")
        d.waitUntil("extra tube added") { d.state("tubes") == (tubes + 1).toString() }
        d.screenshot("helpers")
        assertEquals((afterHint - Helpers.EXTRA_TUBE_COST).toString(), d.state("wallet"))

        // Only once per level, and the wallet never goes negative.
        d.tap("helper-extra")
        assertEquals((tubes + 1).toString(), d.state("tubes"))
        assertEquals((afterHint - Helpers.EXTRA_TUBE_COST).toString(), d.state("wallet"))
    }

    @Test
    fun tapsBetweenTubesSelectTheNearestTube() {
        d.seed(SaveData(flags = listOf("tutorial-done", "tips-helpers")))
        d.launch()
        openLevel(1, 1)
        d.screenshot("board")
        val (from, _) = d.solution().first()
        val glass = DebugTestBridge.bounds("glass-$from")!!
        val cell = DebugTestBridge.bounds("tube-$from")!!
        // Beside the glass, in the gap towards the neighbour, but nearer this tube.
        val gapX =
            if (cell.right - glass.right >
                glass.left - cell.left
            ) {
                glass.right + (cell.right - glass.right) / 2
            } else {
                glass.left - (glass.left - cell.left) / 2
            }
        assertFalse("the tap point is outside the glass", glass.contains(gapX, glass.centerY()))
        d.tapAt(gapX, glass.centerY())
        d.waitUntil("tube $from selected from the gap") { d.state("selected") == from.toString() }
        // Above the glass also goes to the tube below.
        d.tap("tube-$from")
        d.waitUntil("deselected") { d.state("selected").isNullOrEmpty() }
        d.tapAt(glass.centerX(), glass.top - glass.width() / 2)
        d.waitUntil("tube $from selected from above") { d.state("selected") == from.toString() }
    }

    @Test
    fun solvingTheLastLevelUnlocksTheNextWorld() {
        val best = (1..19).associate { LevelId.Campaign(1, it).key to Tuning.basePoints(LevelId.Campaign(1, it)) }
        d.seed(SaveData(best = best, flags = listOf("tutorial-done", "tips-helpers")))
        d.launch()
        openLevel(1, 20)
        d.solveLevel()
        d.tap("overlay-next")
        d.waitScreen("game")
        // World 2 opens with its mechanic card (Hidden items).
        d.waitOverlay("card", GameDriver.GENERATION_TIMEOUT)
        d.screenshot("world2-card")
        d.tap("overlay-card-ok")
        d.screenshot("world2-level1")
        assertEquals(LevelId.Campaign(2, 1).key, d.state("level"))
    }

    @Test
    fun levelSelectShowsBestScores() {
        d.seed(SaveData(best = mapOf(LevelId.Campaign(1, 1).key to 87), flags = listOf("tutorial-done", "tips-helpers")))
        d.launch()
        d.tap("world-1")
        d.waitScreen("levels")
        d.waitUntil("best score shown") { d.state("best-${LevelId.Campaign(1, 1).key}") == "87" }
        d.screenshot("level-select")
        assertEquals(null, d.state("best-${LevelId.Campaign(1, 2).key}"))
    }

    @Test
    fun endlessPickerReplaysAnEarlierLevel() {
        val campaign = (1..8).flatMap { w -> (1..20).map { LevelId.Campaign(w, it) } }
        val endless = (1..21).map { LevelId.Endless(it) }
        val best = (campaign + endless).associate { it.key to Tuning.basePoints(it) }
        d.seed(SaveData(best = best, flags = listOf("tutorial-done", "tips-helpers")))
        d.launch()
        d.tap("endless")
        d.waitScreen("endless")
        // Level 22 is next, so the picker opens on the second page.
        d.waitUntil("second page") { d.state("endless-page") == "2/2" }
        d.tap("page-prev")
        d.waitUntil("first page") { d.state("endless-page") == "1/2" }
        d.screenshot("endless-picker")
        d.tap("level-3")
        d.waitLevelReady()
        assertEquals(LevelId.Endless(3).key, d.state("level"))
        d.tap("pause")
        d.waitOverlay("pause")
        d.tap("overlay-pause-quit")
        d.waitScreen("endless")
        d.waitUntil("back on level 3's page") { d.state("endless-page") == "1/2" }
    }

    @Test
    fun settingsTogglesArePersisted() {
        d.launch()
        d.tap("settings")
        d.waitScreen("settings")
        d.tap("setting-music")
        d.tap("setting-reducemotion")
        d.tap("settings-back")
        d.waitScreen("map")
        d.close()
        val settings = d.readSave().settings
        assertFalse(settings.music)
        assertTrue(settings.reduceMotion)
        assertTrue(settings.sfx)
    }

    @Test
    fun levelInProgressSurvivesAppRestart() {
        d.launch()
        openLevel(1, 1)
        val (from, to) = d.solution().first()
        d.pour(from, to)
        d.backgroundAndClose()
        assertEquals(LevelId.Campaign(1, 1).key, d.readSave().inProgress?.levelKey)

        d.launch()
        d.waitOverlay("resume")
        d.tap("overlay-resume-yes")
        d.waitScreen("game")
        d.waitUntil("level restored", GameDriver.GENERATION_TIMEOUT) { d.state("moves") == "1" }
    }

    @Test
    fun tamperedSaveFallsBackWithANotice() {
        d.seed(SaveData(wallet = 1234))
        val main = File(d.saveDir, SaveStore.MAIN)
        main.writeText(main.readText().replace("1234", "999999"))
        d.launch()
        d.waitOverlay("notice")
        d.tap("overlay-notice-ok")
        d.close()
        // No valid backup existed, so the game started fresh with the starting wallet.
        assertEquals(Helpers.STARTING_WALLET, d.readSave().wallet)
    }

    companion object {
        private const val SPLASH_MIN_MS = 3_000L
        private const val SPLASH_CAP_MS = 8_000L

        // App start-up and polling happen outside the splash's own clock.
        private const val SPLASH_SLACK_MS = 5_000L
    }
}
