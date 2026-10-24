package com.sortopia.android

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.sortopia.save.SaveData
import com.sortopia.save.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /** Cold-starts the app and waits for the World Map; [realSplash] keeps the splash's minimum time. */
    fun launch(realSplash: Boolean = false) {
        DebugTestBridge.reset()
        DebugTestBridge.fastSplash = !realSplash
        scenario = ActivityScenario.launch(AndroidLauncher::class.java)
        waitScreen("map", LAUNCH_TIMEOUT)
    }

    /** Cold-starts the app without waiting, so a test can watch the splash. */
    fun launchToSplash() {
        DebugTestBridge.reset()
        DebugTestBridge.fastSplash = false
        scenario = ActivityScenario.launch(AndroidLauncher::class.java)
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

    /**
     * Saves a screenshot for visual review. Gradle pulls AGP's additional test output directory
     * into `android/build/outputs/connected_android_test_additional_output/` before it
     * uninstalls the app; without that argument (e.g. `am instrument`) it goes to the external cache.
     */
    fun screenshot(name: String) {
        val dir = screenshotDir() ?: return
        Thread.sleep(SCREENSHOT_SETTLE_MS)
        device.takeScreenshot(File(dir, "$name.png"))
    }

    private fun screenshotDir(): File? {
        val extra = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        return extra?.takeIf { it.isDirectory || it.mkdirs() } ?: context.externalCacheDir
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

    /** Taps raw screen coordinates, e.g. between two tubes. */
    fun tapAt(
        x: Int,
        y: Int,
    ) {
        device.click(x, y)
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
        assertBoardReadable()
    }

    /** One item, label or padlock icon as the board drew it: its true [type] and its [look]. */
    data class Drawn(
        val type: Int,
        val look: String,
    ) {
        val hidden: Boolean get() = look == HIDDEN
    }

    /** What one tube showed, bottom to top, with its single-type label and padlock icons. */
    data class DrawnTube(
        val items: List<Drawn>,
        val label: Drawn?,
        val lock: Drawn?,
    )

    /** The board as last drawn on a settled frame (published by BoardView). */
    fun drawn(): List<DrawnTube> {
        val text = state("drawn") ?: return emptyList()
        return text.split("\n").map { line ->
            val fields =
                line.split(' ').filter { it.isNotEmpty() }.map { field ->
                    val (what, rest) = field.split('=', limit = 2)
                    val (type, look) = rest.split(':', limit = 2)
                    what to Drawn(type.toInt(), look)
                }
            DrawnTube(
                items = fields.filter { it.first == "item" }.map { it.second },
                label = fields.firstOrNull { it.first == "label" }?.second,
                lock = fields.firstOrNull { it.first == "lock" }?.second,
            )
        }
    }

    /**
     * What the player sees must match the rules: two visible items (or label/padlock icons) look
     * the same exactly when they are the same type. Catches a type drawn with another's art.
     */
    fun assertBoardReadable() {
        waitUntil("board drawn") { drawn().size == state("tubes")?.toInt() }
        val shown = drawn().flatMap { t -> t.items + listOfNotNull(t.label, t.lock) }.filterNot { it.hidden }
        for ((look, same) in shown.groupBy { it.look }) {
            val types = same.map { it.type }.toSortedSet()
            assertEquals("types $types on ${state("level")} are all drawn as $look", 1, types.size)
        }
        for ((type, same) in shown.groupBy { it.type }) {
            val looks = same.map { it.look }.toSortedSet()
            assertEquals("type $type on ${state("level")} is drawn as $looks", 1, looks.size)
        }
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
        assertPourLooksLegal(from, to)
        tap("tube-$from")
        waitUntil("tube $from selected") { state("selected") == from.toString() }
        tap("tube-$to")
        waitUntil("move counted") { state("moves")!!.toInt() == before + 1 }
        waitUntil("animation done") { state("animating") == "false" }
        assertBoardReadable()
    }

    /**
     * A player would only try this pour if the pictures allow it (SPEC §3): the source's top item
     * looks like the target's top item, or the target is empty, and it fits the target's label.
     */
    private fun assertPourLooksLegal(
        from: Int,
        to: Int,
    ) {
        val tubes = drawn()
        val level = state("level")
        val top = tubes[from].items.lastOrNull()
        assertNotNull("$level: tube $from looks empty but the solution pours from it", top)
        assertNotEquals("$level: tube $from's top item is face-down", HIDDEN, top!!.look)
        tubes[to].items.lastOrNull()?.let {
            assertEquals("$level: pouring $from>$to, but the tops look different", it.look, top.look)
        }
        tubes[to].label?.let {
            assertEquals("$level: pouring $from>$to, but tube $to's label shows another item", it.look, top.look)
        }
    }

    /** Plays the solver's solution from the current board to completion. */
    fun solveLevel() {
        for ((from, to) in solution()) pour(from, to)
        waitOverlay("complete", GENERATION_TIMEOUT)
    }

    companion object {
        private const val HIDDEN = "?"
        const val DEFAULT_TIMEOUT = 15_000L
        const val LAUNCH_TIMEOUT = 30_000L

        // e-21 alone takes ~1 min to generate and solve on a local emulator, and CI is slower.
        const val GENERATION_TIMEOUT = 240_000L
        private const val POLL_MS = 100L
        private const val TAP_SETTLE_MS = 150L
        private const val SCREENSHOT_SETTLE_MS = 700L
        private const val MAX_SCROLLS = 5
        private const val SWIPE_STEPS = 20
        private const val SCROLL_SETTLE_MS = 600L
    }
}
