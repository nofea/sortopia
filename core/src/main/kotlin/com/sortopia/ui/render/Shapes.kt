package com.sortopia.ui.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import kotlin.math.cos
import kotlin.math.sin

/** Filled-shape helpers on top of [ShapeRenderer] (which must be in Filled mode). */
object Shapes {
    /** A tube: rounded at the bottom, square at the top. */
    fun tubeBody(
        sr: ShapeRenderer,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
    ) {
        val r = minOf(radius, w / 2, h / 2)
        sr.rect(x + r, y, w - 2 * r, r)
        sr.rect(x, y + r, w, h - r)
        sr.arc(x + r, y + r, r, 180f, 90f, 12)
        sr.arc(x + w - r, y + r, r, 270f, 90f, 12)
    }

    fun star(
        sr: ShapeRenderer,
        cx: Float,
        cy: Float,
        outer: Float,
        inner: Float,
        points: Int = 5,
    ) {
        val pts = FloatArray(points * 4)
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = MathUtils.degreesToRadians * (90f + 180f * i / points)
            pts[i * 2] = cx + r * cos(a)
            pts[i * 2 + 1] = cy + r * sin(a)
        }
        fan(sr, cx, cy, pts)
    }

    /** Triangle fan around a center for convex or star-shaped outlines. */
    fun fan(
        sr: ShapeRenderer,
        cx: Float,
        cy: Float,
        pts: FloatArray,
    ) {
        val n = pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            sr.triangle(cx, cy, pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1])
        }
    }

    fun shade(
        c: Color,
        amount: Float,
    ): Color = Color(c).lerp(Color.BLACK, amount)
}
