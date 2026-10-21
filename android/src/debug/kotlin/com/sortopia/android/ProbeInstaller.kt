package com.sortopia.android

import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidGraphics
import com.sortopia.platform.UiProbe

/** Debug builds only: installs the [DebugTestBridge] used by the emulator UI tests. */
object ProbeInstaller {
    fun install(app: AndroidApplication) {
        DebugTestBridge.viewOrigin = {
            val view = (app.graphics as? AndroidGraphics)?.view
            val loc = IntArray(2)
            view?.getLocationOnScreen(loc)
            loc
        }
        UiProbe.current = DebugTestBridge
    }
}
