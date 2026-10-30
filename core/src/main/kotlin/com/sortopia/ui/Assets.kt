package com.sortopia.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.GdxRuntimeException
import com.badlogic.gdx.utils.I18NBundle
import com.sortopia.platform.Haptic
import com.sortopia.platform.Platform
import com.sortopia.save.Settings
import com.sortopia.ui.render.ArtAtlas

/** Fonts, the programmatic UI skin and localized strings. */
class Assets(
    fontScale: Float,
) : Disposable {
    val strings: I18NBundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"))

    private val generator = FreeTypeFontGenerator(Gdx.files.internal("fonts/Nunito-ExtraBold.ttf"))
    private val scale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    /** Fonts are rasterized at the screen's density, then drawn in virtual units, so text stays crisp. */
    private val density = (Gdx.graphics.width / VIRTUAL_WIDTH).coerceIn(1f, MAX_DENSITY)

    val small: BitmapFont = font(15)
    val body: BitmapFont = font(19)
    val title: BitmapFont = font(27)
    val huge: BitmapFont = font(46)

    private val pixel: Texture = solidTexture()
    val white: TextureRegion = TextureRegion(pixel)
    private val roundTexture: Texture = roundedTexture()

    val skin: Skin = buildSkin()

    val art: ArtAtlas = ArtAtlas()
    private val accentStyles = HashMap<Int, TextButton.TextButtonStyle>()

    /** A pale button in a world's [accent] colour (SPEC §13: themed UI accents). */
    fun accentStyle(accent: Color): TextButton.TextButtonStyle =
        accentStyles.getOrPut(Color.rgba8888(accent)) { buttonStyle(Color(accent).lerp(Color.WHITE, ACCENT_PALE), Theme.ink) }

    fun str(
        key: String,
        vararg args: Any,
    ): String = if (args.isEmpty()) strings.get(key) else strings.format(key, *args)

    private fun font(size: Int): BitmapFont {
        val params =
            FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                this.size = (size * scale * density).toInt()
                color = Color.WHITE
                minFilter = Texture.TextureFilter.Linear
                magFilter = Texture.TextureFilter.Linear
                characters = FreeTypeFontGenerator.DEFAULT_CHARS + "×–…"
            }
        return generator.generateFont(params).apply {
            data.setScale(1f / density)
            setUseIntegerPositions(false)
        }
    }

    private fun solidTexture(): Texture {
        val pm =
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            }
        return Texture(pm).also { pm.dispose() }
    }

    private fun roundedTexture(): Texture {
        val s = ROUND_TEX
        val r = ROUND_RADIUS
        val pm = Pixmap(s, s, Pixmap.Format.RGBA8888)
        pm.setColor(Color.WHITE)
        pm.fillRectangle(r, 0, s - 2 * r, s)
        pm.fillRectangle(0, r, s, s - 2 * r)
        pm.fillCircle(r, r, r)
        pm.fillCircle(s - r - 1, r, r)
        pm.fillCircle(r, s - r - 1, r)
        pm.fillCircle(s - r - 1, s - r - 1, r)
        return Texture(pm).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pm.dispose()
        }
    }

    fun round(color: Color): NinePatchDrawable {
        val r = ROUND_RADIUS + 2
        val patch = NinePatch(roundTexture, r, r, r, r).apply { this.color = color }
        return NinePatchDrawable(patch).apply {
            // Draw the 9-patch at half size so corners stay crisp on the 2x texture.
            patch.scale(0.5f, 0.5f)
            leftWidth = PAD
            rightWidth = PAD
            topHeight = PAD_V
            bottomHeight = PAD_V
        }
    }

    fun solid(color: Color): Drawable = TextureRegionDrawable(white).tint(color)

    private fun buildSkin(): Skin {
        val skin = Skin()
        skin.add("small", Label.LabelStyle(small, Theme.ink))
        skin.add("default", Label.LabelStyle(body, Theme.ink))
        skin.add("soft", Label.LabelStyle(body, Theme.inkSoft))
        skin.add("title", Label.LabelStyle(title, Theme.ink))
        skin.add("huge", Label.LabelStyle(huge, Theme.ink))
        skin.add("light", Label.LabelStyle(body, Color.WHITE))
        skin.add("default", buttonStyle(Theme.buttonFace, Theme.ink))
        skin.add("primary", buttonStyle(Theme.primary, Color.WHITE))
        skin.add("good", buttonStyle(Theme.good, Color.WHITE))
        skin.add("danger", buttonStyle(Theme.danger, Color.WHITE))
        skin.add("default", ScrollPane.ScrollPaneStyle())
        return skin
    }

    private fun buttonStyle(
        face: Color,
        text: Color,
    ): TextButton.TextButtonStyle =
        TextButton.TextButtonStyle().apply {
            up = round(face)
            down = round(Color(face).lerp(Color.BLACK, 0.12f))
            disabled = round(Color(face).lerp(Theme.locked, 0.7f))
            font = body
            fontColor = text
            disabledFontColor = Theme.inkSoft
        }

    override fun dispose() {
        listOf(small, body, title, huge).forEach { it.dispose() }
        generator.dispose()
        pixel.dispose()
        roundTexture.dispose()
        skin.dispose()
        art.dispose()
    }

    companion object {
        const val VIRTUAL_WIDTH = 480f
        private const val MAX_DENSITY = 3f
        private const val MIN_FONT_SCALE = 1f
        private const val MAX_FONT_SCALE = 1.3f
        private const val ROUND_TEX = 96
        private const val ROUND_RADIUS = 30
        private const val PAD = 18f
        private const val PAD_V = 12f
        private const val ACCENT_PALE = 0.62f
    }
}

enum class Sfx(
    val file: String,
) {
    SELECT("select"),
    POUR("pour"),
    ILLEGAL("illegal"),
    TUBE("tube"),
    COMPLETE("complete"),
    HELPER("helper"),
    CLICK("click"),
}

/** Sound effects, per-world music and haptics, honoring the settings (SPEC §13, §14). */
class Audio(
    private val platform: Platform,
    private val settings: () -> Settings,
) : Disposable {
    private val sounds = Sfx.entries.associateWith { Gdx.audio.newSound(Gdx.files.internal("sfx/${it.file}.wav")) }
    private var music: Music? = null
    private var musicFile: String? = null

    fun play(sfx: Sfx) {
        if (settings().sfx) sounds[sfx]?.play(SFX_VOLUME)
    }

    fun haptic(kind: Haptic) {
        if (settings().haptics) platform.haptic(kind)
    }

    /** Switches to music [track] (a world's or the menu's, SPEC §13); no-op if it's already playing. */
    fun playMusic(track: String) {
        val file = "music/$track.$MUSIC_EXT"
        if (file != musicFile) {
            music?.dispose()
            music = null
            musicFile = file
            // A missing or unreadable track only means silence, never a crash.
            val handle = Gdx.files.internal(file)
            if (handle.exists()) {
                music =
                    try {
                        Gdx.audio.newMusic(handle).apply {
                            isLooping = true
                            volume = MUSIC_VOLUME
                        }
                    } catch (e: GdxRuntimeException) {
                        Gdx.app.error("Sortopia", "Music unavailable: ${e.javaClass.simpleName}")
                        null
                    }
            }
        }
        refreshMusic()
    }

    fun refreshMusic() {
        val m = music ?: return
        if (settings().music) {
            if (!m.isPlaying) m.play()
        } else {
            m.pause()
        }
    }

    fun pauseMusic() {
        music?.pause()
    }

    override fun dispose() {
        sounds.values.forEach(Sound::dispose)
        music?.dispose()
    }

    companion object {
        private const val SFX_VOLUME = 0.7f
        private const val MUSIC_VOLUME = 0.35f
        private const val MUSIC_EXT = "ogg"
    }
}
