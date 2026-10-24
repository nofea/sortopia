package com.sortopia.android

import android.graphics.Rect
import com.sortopia.platform.UiProbe
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only bridge between the LibGDX game and UI Automator tests. LibGDX draws everything on
 * one GL surface, so screens report element bounds and state here, and tests read them to tap
 * real coordinates. Lives in the debug source set, so it never compiles into release.
 */
object DebugTestBridge : UiProbe {
    @Volatile
    var currentScreen: String = ""
        private set

    @Volatile
    var viewOrigin: () -> IntArray = { IntArray(2) }

    private val elements = ConcurrentHashMap<String, FloatArray>()
    private val states = ConcurrentHashMap<String, String>()

    override val active: Boolean get() = true

    /** Set by tests; [GameDriver.launch] turns it on unless a test needs the real splash. */
    @Volatile
    override var fastSplash: Boolean = false

    override fun screen(name: String) {
        currentScreen = name
    }

    override fun element(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        elements[id] = floatArrayOf(x, y, width, height)
    }

    override fun removeElementsWithPrefix(prefix: String) {
        elements.keys.removeAll { it.startsWith(prefix) }
    }

    override fun state(
        key: String,
        value: String,
    ) {
        states[key] = value
    }

    fun state(key: String): String? = states[key]

    /** On-screen bounds of [id] in device pixels, or null if it isn't visible. */
    fun bounds(id: String): Rect? {
        val e = elements[id] ?: return null
        if (e[2] <= 0f || e[3] <= 0f) return null
        val o = viewOrigin()
        return Rect((o[0] + e[0]).toInt(), (o[1] + e[1]).toInt(), (o[0] + e[0] + e[2]).toInt(), (o[1] + e[1] + e[3]).toInt())
    }

    fun reset() {
        elements.clear()
        states.clear()
        currentScreen = ""
    }
}
