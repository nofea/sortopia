package com.sortopia.ui.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.sortopia.theme.Look
import com.sortopia.theme.ThemeCatalog

/**
 * Draws themed items (SPEC §6, §13) from the art atlas: the fixed under-layer, the body tinted
 * with the item's colour (or the colorblind palette's, SPEC §14), the fixed details on top, and
 * a face unless the item is too small to show one.
 */
object ItemArt {
    private const val MIN_FACE_SIZE = 32f
    private const val DARK_LUMA = 0.35f
    private val colors = HashMap<String, Color>()
    private val tmp = Color()

    fun color(
        look: Look,
        type: Int,
        colorblind: Boolean,
    ): Color {
        val hex = if (colorblind) ThemeCatalog.colorblindColor(type) else look.item(type).color
        return colors.getOrPut(hex) { Color.valueOf(hex) }
    }

    /** Draws item [type] in a [size]-wide cell centred on ([cx], [cy]); the art fills about 80% of it. */
    @Suppress("LongParameterList")
    fun draw(
        batch: Batch,
        art: ArtAtlas,
        look: Look,
        type: Int,
        cx: Float,
        cy: Float,
        size: Float,
        colorblind: Boolean,
        face: Boolean = true,
        alpha: Float = 1f,
    ) {
        val key = look.item(type).art
        val x = cx - size / 2
        val y = cy - size / 2
        val old = tmp.set(batch.color)
        val tint = color(look, type, colorblind)
        batch.setColor(1f, 1f, 1f, alpha * old.a)
        art.region("$key.under")?.let { batch.draw(it, x, y, size, size) }
        batch.setColor(tint.r, tint.g, tint.b, alpha * old.a)
        art.region("$key.body")?.let { batch.draw(it, x, y, size, size) }
        batch.setColor(1f, 1f, 1f, alpha * old.a)
        art.region("$key.over")?.let { batch.draw(it, x, y, size, size) }
        if (face && size >= MIN_FACE_SIZE) {
            val anchor = art.face(key)
            val sprite = art.region(if (luma(tint) < DARK_LUMA) "face.light" else "face.ink")
            if (anchor != null && sprite != null) {
                val side = size * anchor.scale
                batch.draw(sprite, x + anchor.x * size - side / 2, y + (1f - anchor.y) * size - side / 2, side, side)
            }
        }
        batch.color = old
    }

    private fun luma(c: Color) = c.r * 0.3f + c.g * 0.55f + c.b * 0.15f
}
