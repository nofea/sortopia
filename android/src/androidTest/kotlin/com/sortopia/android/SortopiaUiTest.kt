package com.sortopia.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sortopia.game.Helpers
import com.sortopia.game.Wallet
import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.SaveData
import com.sortopia.save.SaveStore
import com.sortopia.save.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
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

    /** Screenshots a failing test before the app closes, so CI keeps the evidence. */
    @get:Rule
    val closeApp =
        object : TestWatcher() {
            override fun failed(
                e: Throwable?,
                description: Description,
            ) = d.screenshot("FAILED-${description.methodName}")

            override fun finished(description: Description) = d.close()
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
        d.assertBoardReadable()
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
    fun solvedDailyIsLockedUntilTomorrow() {
        val best = (1..LevelId.LEVELS_PER_WORLD).associate { LevelId.Campaign(1, it).key to Tuning.basePoints(LevelId.Campaign(1, it)) }
        d.seed(SaveData(best = best, flags = listOf("tutorial-done", "tips-helpers")))
        d.launch()
        d.tap("daily")
        d.waitLevelReady()
        d.solveLevel()
        d.screenshot("daily-complete")
        assertNull("a solved Daily has no Replay", DebugTestBridge.bounds("overlay-replay"))
        assertNotNull("the countdown to the next Daily is shown", DebugTestBridge.bounds("overlay-daily-next"))
        d.tap("overlay-levels")
        d.waitScreen("map")
        d.screenshot("daily-locked")
        // The card no longer opens the puzzle.
        d.tap("daily")
        Thread.sleep(LOCKED_TAP_WAIT_MS)
        assertEquals("map", DebugTestBridge.currentScreen)
    }

    @Test
    fun hudIsCentredWhateverTheWallet() {
        for (wallet in listOf(0, Wallet.MAX_POINTS)) {
            d.clearSave()
            d.seed(SaveData(wallet = wallet, flags = listOf("tutorial-done", "tips-helpers")))
            d.launch()
            openLevel(1, 1)
            d.screenshot("hud-wallet-$wallet")
            val centre = d.device.displayWidth / 2
            for (id in listOf("hud-title", "hud-moves", "hud-timer")) {
                val r = DebugTestBridge.bounds(id)!!
                assertTrue(
                    "$id is centred with wallet $wallet: centre ${r.centerX()} vs screen $centre",
                    kotlin.math.abs(r.centerX() - centre) <= HUD_CENTRE_SLACK_PX,
                )
            }
            d.close()
        }
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
    fun clearProgressKeepsOnlyTheSettings() {
        val solved = (1..LevelId.LEVELS_PER_WORLD).map { LevelId.Campaign(1, it) }
        val seeded =
            SaveData(
                wallet = 777,
                best = solved.associate { it.key to Tuning.basePoints(it) },
                flags = listOf("tutorial-done", "tips-helpers"),
                settings = Settings(music = false, reduceMotion = true),
            )
        d.seed(seeded)
        d.seed(seeded) // A second write fills a backup, which the reset must delete.
        d.launch()
        d.tap("settings")
        d.waitScreen("settings")
        d.tap("setting-clear")
        d.waitOverlay("clear")
        d.tap("overlay-clear-cancel")
        d.waitUntil("overlay closed") { d.state("overlay") == "" }
        assertEquals(seeded, d.readSave())

        d.tap("setting-clear")
        d.waitOverlay("clear")
        d.screenshot("clear-confirm")
        d.tap("overlay-clear-confirm")
        d.waitScreen("map")
        d.waitOverlay("cleared")
        d.tap("overlay-cleared-ok")
        d.waitUntil("notice closed") { d.state("overlay") == "" }
        d.close()

        val cleared = SaveData(settings = seeded.settings)
        assertEquals(cleared, d.readSave())
        assertEquals(Helpers.STARTING_WALLET, d.readSave().wallet)
        // The old progress is gone from the backups too (closing the app saves again, so the
        // cleared save may already have rotated into one).
        for (file in d.saveDir.listFiles()!!) {
            assertEquals(file.name, cleared, d.store().decode(file.readBytes()))
        }
    }

    @Test
    fun aboutShowsTheInstalledVersion() {
        d.launch()
        d.tap("about")
        d.waitScreen("about")
        @Suppress("DEPRECATION") // The PackageInfoFlags overload needs API 33; minSdk is 26.
        val expected = d.context.packageManager.getPackageInfo(d.context.packageName, 0).versionName
        assertEquals(expected, d.state("about-version"))
        d.screenshot("about")
        d.tap("about-back")
        d.waitScreen("map")
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
        d.assertBoardReadable()
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

        // Long enough for a tap on the Daily card to have switched screens, had it opened.
        private const val LOCKED_TAP_WAIT_MS = 1_500L

        // Label widths round to whole pixels when centred.
        private const val HUD_CENTRE_SLACK_PX = 4
    }
}
