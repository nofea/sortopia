package com.sortopia.android

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.sortopia.save.SaveData
import com.sortopia.save.SaveStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import java.io.File

/**
 * Drives the LibGDX game through the debug [DebugTestBridge]: reads element bounds and state
 * the screens publish, and taps real screen coordinates with UI Automator.
 */
class GameDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<AndroidLauncher>? = null

    val saveDir: File get() = File(context.filesDir, "save")

    fun store() = SaveStore(saveDir.toPath(), KeystoreMac.create(context.noBackupFilesDir))

    fun clearSave() {
        saveDir.deleteRecursively()
    }

    fun seed(data: SaveData) = store().save(data)

    fun readSave(): SaveData = store().load().data

    fun launch() {
        DebugTestBridge.reset()
        scenario = ActivityScenario.launch(AndroidLauncher::class.java)
        waitScreen("map", LAUNCH_TIMEOUT)
    }

    /** Sends the app to the background (saving in-progress state) and destroys it. */
    fun backgroundAndClose() {
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario?.close()
        scenario = null
    }

    fun close() {
        scenario?.close()
        scenario = null
    }

    fun waitUntil(
        what: String,
        timeoutMs: Long = DEFAULT_TIMEOUT,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        fail("Timed out waiting for $what (screen=${DebugTestBridge.currentScreen}, overlay=${state("overlay")})")
    }

    fun waitScreen(
        name: String,
        timeoutMs: Long = DEFAULT_TIMEOUT,
    ) = waitUntil("screen $name", timeoutMs) { DebugTestBridge.currentScreen == name }

    fun waitOverlay(
        name: String,
        timeoutMs: Long = DEFAULT_TIMEOUT,
    ) = waitUntil("overlay $name", timeoutMs) { state("overlay") == name }

    fun state(key: String): String? = DebugTestBridge.state(key)

    /** Saves a screenshot to the app's external cache (pull with adb) for visual review. */
    fun screenshot(name: String) {
        val dir = context.externalCacheDir ?: return
        Thread.sleep(SCREENSHOT_SETTLE_MS)
        device.takeScreenshot(File(dir, "$name.png"))
    }

    fun tap(id: String) {
        waitUntil("element $id") { DebugTestBridge.bounds(id) != null }
        scrollIntoView(id)
        val r = DebugTestBridge.bounds(id)
        assertNotNull(r)
        device.click(r!!.centerX(), r.centerY())
        // Let the render thread process the tap before the next step reads state.
        Thread.sleep(TAP_SETTLE_MS)
    }

    /** Swipes a scrolling list (e.g. the World Map) until [id] is fully on screen. */
    private fun scrollIntoView(id: String) {
        repeat(MAX_SCROLLS) {
            val r = DebugTestBridge.bounds(id) ?: return
            val h = device.displayHeight
            if (r.top >= 0 && r.bottom <= h) return
            val x = device.displayWidth / 2
            // Drag up to reveal what is below, down to reveal what is above.
            if (r.bottom > h) device.swipe(x, h * 3 / 4, x, h / 4, SWIPE_STEPS) else device.swipe(x, h / 4, x, h * 3 / 4, SWIPE_STEPS)
            Thread.sleep(SCROLL_SETTLE_MS)
        }
    }

    /** Waits for a level to be ready and dismisses first-time tips/cards. */
    fun waitLevelReady() {
        waitScreen("game")
        waitUntil("level generated", GENERATION_TIMEOUT) { !state("solution").isNullOrEmpty() && state("tubes") != null }
        if (state("overlay") == "tips") tap("overlay-tips-ok")
        if (state("overlay") == "card") tap("overlay-card-ok")
        waitUntil("no overlay") { state("overlay").isNullOrEmpty() }
    }

    fun solution(): List<Pair<Int, Int>> {
        val text = state("solution")!!
        return text.split(",").map { m -> m.split(">").let { it[0].toInt() to it[1].toInt() } }
    }

    fun pour(
        from: Int,
        to: Int,
    ) {
        val before = state("moves")!!.toInt()
        waitUntil("animation done") { state("animating") == "false" }
        tap("tube-$from")
        waitUntil("tube $from selected") { state("selected") == from.toString() }
        tap("tube-$to")
        waitUntil("move counted") { state("moves")!!.toInt() == before + 1 }
    }

    /** Plays the solver's solution from the current board to completion. */
    fun solveLevel() {
        for ((from, to) in solution()) pour(from, to)
        waitOverlay("complete", GENERATION_TIMEOUT)
    }

    companion object {
        const val DEFAULT_TIMEOUT = 15_000L
        const val LAUNCH_TIMEOUT = 30_000L
        const val GENERATION_TIMEOUT = 60_000L
        private const val POLL_MS = 100L
        private const val TAP_SETTLE_MS = 150L
        private const val SCREENSHOT_SETTLE_MS = 700L
        private const val MAX_SCROLLS = 5
        private const val SWIPE_STEPS = 20
        private const val SCROLL_SETTLE_MS = 600L
    }
}
