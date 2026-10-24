package com.sortopia.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.sortopia.SortopiaGame
import com.sortopia.theme.Look
import com.sortopia.ui.render.ItemArt
import com.sortopia.ui.render.Shapes

/** An actor drawn with the shared [ShapeRenderer]. */
open class ShapeActor(
    private val game: SortopiaGame,
    private val painter: (ShapeRenderer, Float, Float, Float, Float) -> Unit,
) : Actor() {
    override fun draw(
        batch: Batch,
        parentAlpha: Float,
    ) {
        batch.end()
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND)
        val sr = game.shapes
        sr.projectionMatrix = batch.projectionMatrix
        sr.transformMatrix = batch.transformMatrix
        sr.begin(ShapeRenderer.ShapeType.Filled)
        painter(sr, x, y, width, height)
        sr.end()
        batch.begin()
    }
}

/** One themed item, drawn from the art atlas. */
class ItemActor(
    private val game: SortopiaGame,
    private val look: Look,
    private val type: Int,
) : Actor() {
    override fun draw(
        batch: Batch,
        parentAlpha: Float,
    ) {
        val colorblind = game.settings.colorblindPalette
        ItemArt.draw(batch, game.assets.art, look, type, x + width / 2, y + height / 2, width, colorblind, alpha = color.a * parentAlpha)
    }
}

class Widgets(
    private val game: SortopiaGame,
) {
    private val assets get() = game.assets

    fun label(
        text: String,
        style: String = "default",
        wrap: Boolean = false,
        align: Int = Align.center,
    ): Label =
        Label(text, assets.skin, style).apply {
            this.wrap = wrap
            setAlignment(align)
        }

    fun button(
        text: String,
        style: String = "default",
        onClick: () -> Unit,
    ): TextButton =
        TextButton(text, assets.skin, style).apply {
            label.wrap = false
            addListener(
                object : ClickListener() {
                    override fun clicked(
                        event: InputEvent?,
                        x: Float,
                        y: Float,
                    ) {
                        if (isDisabled) return
                        game.audio.play(Sfx.CLICK)
                        onClick()
                    }
                },
            )
        }

    fun panel(color: Color = Theme.paper): Table =
        Table().apply {
            background = assets.round(color)
            pad(PANEL_PAD)
        }

    /** Row of three stars, [earned] of them filled. */
    fun stars(
        earned: Int,
        size: Float,
    ): Actor =
        ShapeActor(game) { sr, x, y, w, h ->
            val step = w / 3
            for (i in 0 until 3) {
                val cx = x + step * (i + 0.5f)
                val cy = y + h / 2
                sr.color = if (i < earned) Shapes.shade(Theme.star, 0.2f) else Theme.starEmpty
                Shapes.star(sr, cx, cy - 1f, size / 2, size / 4.2f)
                if (i < earned) {
                    sr.color = Theme.star
                    Shapes.star(sr, cx, cy, size / 2.3f, size / 5f)
                }
            }
        }.apply { setSize(size * 3.3f, size) }

    /** A world's emblem: its first item, with a face (SPEC §13: themed UI accents). */
    fun emblem(
        theme: WorldTheme,
        size: Float,
    ): Actor = ItemActor(game, Look(theme.def, theme.def.items), 0).apply { setSize(size, size) }

    fun coin(size: Float): Actor =
        ShapeActor(game) { sr, x, y, w, h ->
            sr.color = Shapes.shade(Theme.star, 0.25f)
            sr.circle(x + w / 2, y + h / 2, size / 2, 20)
            sr.color = Theme.star
            sr.circle(x + w / 2, y + h / 2 + 1, size / 2.6f, 20)
        }.apply { setSize(size, size) }

    companion object {
        const val PANEL_PAD = 24f
    }
}
