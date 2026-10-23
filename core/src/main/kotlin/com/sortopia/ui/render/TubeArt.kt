package com.sortopia.ui.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.sortopia.ui.Theme
import com.sortopia.ui.WorldTheme
import kotlin.math.sin

/**
 * A world's tube (SPEC §6, §13): the same upright glass everywhere, dressed per world with a
 * base, a collar, a lid and the glass's own tint, fill or stripes. Glass is drawn with the
 * [ShapeRenderer] (pass one), the dressing with the [Batch] (pass two, around the items).
 */
object TubeArt {
    /** Lid, collar and base art spans 80% of its cell's width. */
    private const val DRESS_SPAN = 0.8f
    private const val LID_BAND_BOTTOM = 0.4f
    private const val COLLAR_CENTER = 0.5f
    private const val BASE_BAND_TOP = 0.6f
    private const val BASE_WIDEN = 0.98f
    private const val BUBBLES = 3
    private val SHINE = Color(1f, 1f, 1f, 0.35f)
    private val BUBBLE = Color(1f, 1f, 1f, 0.55f)

    fun glass(
        sr: ShapeRenderer,
        theme: WorldTheme,
        r: Rectangle,
        time: Float,
        motion: Boolean,
    ) {
        val radius = r.width / 2
        sr.color = Theme.shadow
        Shapes.tubeBody(sr, r.x + 3, r.y - 4, r.width, r.height, radius)
        sr.color = theme.glassEdge
        Shapes.tubeBody(sr, r.x, r.y, r.width, r.height, radius)
        sr.color = theme.glass
        Shapes.tubeBody(sr, r.x + 3, r.y + 3, r.width - 6, r.height - 3, radius - 3)
        theme.glassFill?.let {
            sr.color = it
            Shapes.tubeBody(sr, r.x + 4, r.y + 4, r.width - 8, r.height * 0.9f - 4, radius - 4)
        }
        theme.glassStripes?.let {
            sr.color = it
            val w = r.width * 0.12f
            for (k in 1..2) sr.rect(r.x + r.width * k / 3f - w / 2, r.y + radius, w, r.height - radius)
        }
        if (theme.glassFill != null && theme.def.id == "aquarium") {
            sr.color = BUBBLE
            for (k in 0 until BUBBLES) {
                val travel = r.height * 0.8f
                val rise = if (motion) (time * (14f + k * 5f) + k * 37f) % travel else travel * (k + 1) / (BUBBLES + 1)
                val wobble = if (motion) sin(time * 2f + k) * 2f else 0f
                sr.circle(r.x + r.width * (0.3f + 0.2f * k) + wobble, r.y + radius * 0.6f + rise, r.width * 0.05f + k, 10)
            }
        }
    }

    fun shine(
        batch: Batch,
        white: TextureRegion,
        r: Rectangle,
    ) {
        val old = batch.packedColor
        batch.color = SHINE
        batch.draw(white, r.x + r.width * 0.18f, r.y + r.height * 0.2f, r.width * 0.1f, r.height * 0.7f)
        batch.packedColor = old
    }

    fun base(
        batch: Batch,
        art: ArtAtlas,
        theme: WorldTheme,
        r: Rectangle,
    ) {
        val sprite = art.region("${theme.def.id}.base") ?: return
        val side = (r.width + 8f) / DRESS_SPAN * BASE_WIDEN
        batch.draw(sprite, r.x + r.width / 2 - side / 2, r.y + 6f - side * BASE_BAND_TOP, side, side)
    }

    fun collar(
        batch: Batch,
        art: ArtAtlas,
        theme: WorldTheme,
        r: Rectangle,
        itemSize: Float,
    ) {
        val sprite = art.region("${theme.def.id}.collar") ?: return
        val side = (r.width + 8f) / DRESS_SPAN
        // At the rim, above the top item, so it never hides an item.
        val centerY = r.y + r.height + itemSize * 0.02f
        batch.draw(sprite, r.x + r.width / 2 - side / 2, centerY - side * COLLAR_CENTER, side, side)
    }

    /** The lid sealing a complete tube, [drop] above its resting place; its tag shows [tag]. */
    @Suppress("LongParameterList")
    fun lid(
        batch: Batch,
        art: ArtAtlas,
        theme: WorldTheme,
        r: Rectangle,
        drop: Float,
        tag: Color,
    ) {
        val side = (r.width + 8f) / DRESS_SPAN
        val x = r.x + r.width / 2 - side / 2
        val y = r.y + r.height + drop - 2f - side * LID_BAND_BOTTOM
        art.region("${theme.def.id}.lid")?.let { batch.draw(it, x, y, side, side) }
        art.region("${theme.def.id}.lidtag")?.let {
            val old = batch.packedColor
            batch.color = tag
            batch.draw(it, x, y, side, side)
            batch.packedColor = old
        }
    }
}
