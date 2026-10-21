package com.sortopia.ui.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.sortopia.ui.Theme

/**
 * Draws item types as distinct shapes as well as colors (SPEC §14), with a small face to keep
 * the cozy cartoon tone (SPEC §13). Placeholder for per-world illustrated icons.
 */
object ItemArt {
    const val SHAPES = 14

    fun draw(
        sr: ShapeRenderer,
        type: Int,
        cx: Float,
        cy: Float,
        size: Float,
        colorblind: Boolean,
        face: Boolean = true,
    ) {
        val color = Theme.itemColor(type, colorblind)
        val r = size / 2
        sr.color = Shapes.shade(color, 0.35f)
        shape(sr, type, cx, cy - r * 0.06f, r)
        sr.color = color
        shape(sr, type, cx, cy, r * 0.88f)
        sr.color = Shapes.tint(color, 0.45f)
        sr.circle(cx - r * 0.32f, cy + r * 0.38f, r * 0.12f, 10)
        if (face && size >= MIN_FACE_SIZE) drawFace(sr, cx, cy, r, color)
    }

    private fun drawFace(
        sr: ShapeRenderer,
        cx: Float,
        cy: Float,
        r: Float,
        color: Color,
    ) {
        val dark = color.r * 0.3f + color.g * 0.55f + color.b * 0.15f < 0.35f
        sr.color = if (dark) Color.WHITE else Theme.ink
        val eye = r * 0.09f
        sr.circle(cx - r * 0.22f, cy + r * 0.02f, eye, 8)
        sr.circle(cx + r * 0.22f, cy + r * 0.02f, eye, 8)
        sr.arc(cx, cy - r * 0.12f, r * 0.14f, 200f, 140f, 8)
        sr.color = color
        sr.circle(cx, cy - r * 0.05f, r * 0.1f, 8)
    }

    @Suppress("CyclomaticComplexMethod")
    fun shape(
        sr: ShapeRenderer,
        type: Int,
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        when (type.mod(SHAPES)) {
            0 -> sr.circle(cx, cy, r, 28)
            1 -> Shapes.roundedRect(sr, cx - r * 0.85f, cy - r * 0.85f, r * 1.7f, r * 1.7f, r * 0.3f)
            2 -> Shapes.polygon(sr, cx, cy - r * 0.12f, r * 1.12f, 3)
            3 -> Shapes.polygon(sr, cx, cy, r * 1.05f, 4)
            4 -> Shapes.star(sr, cx, cy - r * 0.05f, r * 1.1f, r * 0.55f)
            5 -> Shapes.heart(sr, cx, cy + r * 0.05f, r * 0.82f)
            6 -> Shapes.polygon(sr, cx, cy, r, 6, 0f)
            7 -> {
                val t = r * 0.62f
                Shapes.roundedRect(sr, cx - t / 2, cy - r, t, 2 * r, t * 0.3f)
                Shapes.roundedRect(sr, cx - r, cy - t / 2, 2 * r, t, t * 0.3f)
            }
            8 -> {
                sr.arc(cx, cy - r * 0.45f, r * 1.05f, 0f, 180f, 24)
                Shapes.roundedRect(sr, cx - r * 1.05f, cy - r * 0.75f, r * 2.1f, r * 0.4f, r * 0.15f)
            }
            9 -> Shapes.polygon(sr, cx, cy - r * 0.04f, r * 1.02f, 5)
            10 -> {
                // Ring: a donut with a light center.
                sr.circle(cx, cy, r, 28)
                val c = Color(sr.color)
                sr.color = Shapes.tint(c, 0.8f)
                sr.circle(cx, cy, r * 0.42f, 20)
                sr.color = c
            }
            11 -> {
                sr.circle(cx, cy - r * 0.25f, r * 0.75f, 24)
                sr.triangle(cx - r * 0.68f, cy - r * 0.05f, cx + r * 0.68f, cy - r * 0.05f, cx, cy + r * 1.05f)
            }
            12 -> {
                val c = r * 0.45f
                sr.circle(cx - c, cy, c * 1.05f, 16)
                sr.circle(cx + c, cy, c * 1.05f, 16)
                sr.circle(cx, cy - c, c * 1.05f, 16)
                sr.circle(cx, cy + c, c * 1.05f, 16)
            }
            else -> {
                sr.triangle(cx - r, cy - r * 0.8f, cx - r, cy + r * 0.8f, cx + r * 0.1f, cy)
                sr.triangle(cx + r, cy - r * 0.8f, cx + r, cy + r * 0.8f, cx - r * 0.1f, cy)
                sr.circle(cx, cy, r * 0.3f, 12)
            }
        }
    }

    private const val MIN_FACE_SIZE = 26f
}
