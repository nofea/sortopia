package com.sortopia.platform

import com.sortopia.save.MacProvider
import java.nio.file.Path
import java.time.LocalDate

enum class Haptic {
    LIGHT,
    SUCCESS,
}

/** Platform services the game needs; implemented by the android and desktop launchers. */
interface Platform {
    /** App-private directory for the save files. */
    val saveDir: Path

    val macProvider: MacProvider

    /** The app's version name, shown on the About screen. */
    val appVersion: String

    /** System font scale, clamped by the caller to what the layout allows. */
    val fontScale: Float get() = 1f

    fun haptic(kind: Haptic)

    fun today(): LocalDate = LocalDate.now()
}

/**
 * Hook for UI tests. Screens report element bounds (screen pixels, origin top-left) and state.
 * Release builds keep the no-op default; the real bridge only exists in the android debug
 * source set (SPEC §16).
 */
interface UiProbe {
    /** True only for the debug test bridge; screens skip test-only work otherwise. */
    val active: Boolean get() = false

    /** Debug-only UI-test fast path: the splash skips its minimum time (it still waits until ready). */
    val fastSplash: Boolean get() = false

    fun screen(name: String) {}

    fun element(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {}

    fun removeElementsWithPrefix(prefix: String) {}

    fun state(
        key: String,
        value: String,
    ) {}

    companion object {
        @Volatile
        var current: UiProbe = object : UiProbe {}
    }
}
