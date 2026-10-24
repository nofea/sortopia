package com.sortopia.android

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.view.HapticFeedbackConstants
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidGraphics
import com.sortopia.platform.Haptic
import com.sortopia.platform.Platform
import com.sortopia.save.MacProvider
import java.io.File
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class AndroidPlatform(
    private val app: AndroidApplication,
) : Platform {
    override val saveDir: Path = app.filesDir.toPath().resolve("save")

    override val macProvider: MacProvider by lazy { KeystoreMac.create(app.noBackupFilesDir) }

    override val appVersion: String by lazy {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
        info.versionName.orEmpty()
    }

    override val fontScale: Float get() = app.resources.configuration.fontScale

    /** View haptics need no VIBRATE permission (SPEC §16: no permissions). */
    override fun haptic(kind: Haptic) {
        app.runOnUiThread {
            val view = (app.graphics as? AndroidGraphics)?.view ?: return@runOnUiThread
            val constant =
                when (kind) {
                    Haptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
                    Haptic.SUCCESS ->
                        if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.R
                        ) {
                            HapticFeedbackConstants.CONFIRM
                        } else {
                            HapticFeedbackConstants.LONG_PRESS
                        }
                }
            view.performHapticFeedback(constant)
        }
    }
}

/**
 * HMAC for save integrity (SPEC §16). The key is generated inside the Android Keystore and
 * never leaves it.
 */
class KeystoreMac private constructor(
    private val key: SecretKey,
) : MacProvider {
    override fun mac(data: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(key)
            doFinal(data)
        }

    companion object {
        private const val ALIAS = "sortopia-save-hmac"
        private const val ALGORITHM = "HmacSHA256"
        private const val PROVIDER = "AndroidKeyStore"
        private const val FALLBACK_FILE = "save-hmac.key"
        private const val KEY_BYTES = 32

        fun create(noBackupDir: File): KeystoreMac =
            try {
                KeystoreMac(keystoreKey())
            } catch (e: GeneralSecurityException) {
                // Rare devices with a broken Keystore: use a private per-install key rather than crash.
                Log.w("Sortopia", "Keystore unavailable (${e.javaClass.simpleName}), using app-private key")
                KeystoreMac(fallbackKey(noBackupDir))
            }

        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
            return generator.generateKey()
        }

        private fun fallbackKey(noBackupDir: File): SecretKey {
            val file = noBackupDir.resolve(FALLBACK_FILE)
            if (!file.exists()) file.writeBytes(ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) })
            return SecretKeySpec(file.readBytes(), ALGORITHM)
        }
    }
}
