package com.sortopia.android

import com.badlogic.gdx.backends.android.AndroidApplication

/** Release builds ship no UI-test bridge (SPEC §16); the probe stays the core no-op. */
object ProbeInstaller {
    @Suppress("UNUSED_PARAMETER")
    fun install(app: AndroidApplication) = Unit
}
