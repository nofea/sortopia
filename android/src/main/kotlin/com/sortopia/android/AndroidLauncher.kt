package com.sortopia.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.sortopia.SortopiaGame

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds install the UI-test bridge here; release builds compile a no-op.
        ProbeInstaller.install(this)
        val config =
            AndroidApplicationConfiguration().apply {
                useImmersiveMode = true
                useAccelerometer = false
                useCompass = false
                useGyroscope = false
                numSamples = 2
            }
        initialize(SortopiaGame(AndroidPlatform(this)), config)
    }
}
