package com.sortopia.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.sortopia.SortopiaGame
import com.sortopia.platform.Haptic
import com.sortopia.platform.Platform
import com.sortopia.save.MacProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Desktop launcher for fast local iteration only; never shipped (CLAUDE.md). */
private class DesktopPlatform : Platform {
    override val saveDir: Path = Paths.get(System.getProperty("user.home"), ".sortopia")

    /** Dev-only key stored next to the save; the Android build keeps its key in the Keystore. */
    override val macProvider: MacProvider by lazy {
        Files.createDirectories(saveDir)
        val keyFile = saveDir.resolve("dev-hmac.key")
        if (!Files.exists(keyFile)) {
            Files.write(keyFile, ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) })
        }
        val key = SecretKeySpec(Files.readAllBytes(keyFile), "HmacSHA256")
        MacProvider { data ->
            Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(data)
            }
        }
    }

    /** The desktop launcher is never shipped, so it has no release version. */
    override val appVersion = "dev"

    /** Desktop has no haptics. */
    override fun haptic(kind: Haptic) = Unit

    companion object {
        private const val KEY_BYTES = 32
    }
}

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("Sortopia")
            setWindowedMode(WIDTH, HEIGHT)
            useVsync(true)
            setForegroundFPS(FPS)
            setBackBufferConfig(COLOR_BITS, COLOR_BITS, COLOR_BITS, COLOR_BITS, DEPTH_BITS, 0, SAMPLES)
        }
    Lwjgl3Application(SortopiaGame(DesktopPlatform()), config)
}

private const val WIDTH = 450
private const val HEIGHT = 860
private const val FPS = 60
private const val SAMPLES = 4
private const val COLOR_BITS = 8
private const val DEPTH_BITS = 16
