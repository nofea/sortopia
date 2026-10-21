package com.sortopia.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.sortopia.SortopiaGame
import com.sortopia.model.Board
import com.sortopia.model.Item
import com.sortopia.model.Move
import com.sortopia.model.Tube
import com.sortopia.rules.Rules
import com.sortopia.ui.render.ItemArt
import com.sortopia.ui.render.Shapes
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Draws the tubes and items and turns taps into tube indices. All gameplay decisions stay in
 * [com.sortopia.game.GameSession]; this class only animates what it is told (SPEC §13).
 */
class BoardView(
    private val game: SortopiaGame,
    private val onTubeTapped: (Int) -> Unit,
) : Actor() {
    var board: Board = Board(emptyList())
        set(value) {
            val relayout = field.tubes.size != value.tubes.size
            field = value
            if (relayout) layoutTubes()
        }

    var selected: Int? = null
    var hint: Move? = null
    var pointer: Int? = null

    /** Tap target for each tube, in local coordinates (the whole column, not just the glass). */
    val hitRects = ArrayList<Rectangle>()
    private val tubeRects = ArrayList<Rectangle>()
    private var itemSize = 40f
    private var pad = 5f
    private var lift = 30f

    private val shakes = HashMap<Int, Float>()
    private val lids = HashMap<Int, Float>()
    private val flights = ArrayList<Flight>()
    private var time = 0f
    private val glyphs = GlyphLayout()
    private val texts = ArrayList<TextDraw>()

    private class Flight(
        val item: Item,
        val fromX: Float,
        val fromY: Float,
        val toTube: Int,
        val toIndex: Int,
        val delay: Float,
        val duration: Float,
    ) {
        var t = 0f
    }

    private class TextDraw(
        val text: String,
        val x: Float,
        val y: Float,
        val font: BitmapFont,
        val color: Color,
    )

    init {
        addListener(
            object : InputListener() {
                override fun touchDown(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                    pointer: Int,
                    button: Int,
                ): Boolean {
                    val index = hitRects.indexOfFirst { it.contains(x, y) }
                    if (index >= 0) onTubeTapped(index)
                    return index >= 0
                }
            },
        )
    }

    override fun sizeChanged() = layoutTubes()

    private val reduceMotion get() = game.settings.reduceMotion
    private val colorblind get() = game.settings.colorblindPalette

    val isAnimating: Boolean get() = flights.isNotEmpty()

    fun shake(tube: Int) {
        if (!reduceMotion) shakes[tube] = SHAKE_TIME
    }

    fun sealed(tubes: List<Int>) {
        for (t in tubes) lids[t] = if (reduceMotion) 0f else LID_TIME
    }

    /** Animates the [count] items that just moved from [before] into [after] along [move]. */
    fun animatePour(
        before: Board,
        after: Board,
        move: Move,
        count: Int,
    ) {
        flights.clear()
        val src = before.tubes[move.from]
        val dst = after.tubes[move.to]
        val duration = if (reduceMotion) QUICK_FLIGHT else FLIGHT
        for (k in 0 until count) {
            val fromIndex = src.size - 1 - k
            val (fx, fy) = itemCenter(move.from, fromIndex, lifted = selected == move.from)
            val toIndex = dst.size - count + k
            flights += Flight(dst.items[toIndex], fx, fy, move.to, toIndex, k * if (reduceMotion) 0f else STAGGER, duration)
        }
        board = after
    }

    private fun layoutTubes() {
        tubeRects.clear()
        hitRects.clear()
        val tubes = board.tubes
        val n = tubes.size
        if (n == 0 || width <= 0f || height <= 0f) return
        val rows =
            when {
                n <= ONE_ROW -> 1
                n <= TWO_ROWS -> 2
                else -> 3
            }
        val perRow = ceil(n / rows.toFloat()).toInt()
        val maxCap = tubes.maxOf { it.capacity }
        val slotW = width / perRow
        // Item size limited by width (tube ~1.25 items wide) and by height (capacity + lift + label).
        itemSize = minOf(slotW * 0.62f, height / rows / (maxCap + ROW_EXTRA_ITEMS), MAX_ITEM)
        val rowH = itemSize * (maxCap + ROW_EXTRA_ITEMS)
        val topOffset = (height - rowH * rows) / 2
        pad = itemSize * 0.14f
        lift = itemSize * 0.95f
        val tubeW = itemSize + pad * 2
        for (r in 0 until rows) {
            val start = r * perRow
            val count = minOf(perRow, n - start)
            if (count <= 0) break
            val rowY = height - topOffset - (r + 1) * rowH
            val baseY = rowY + itemSize * LABEL_SPACE
            val rowSlot = width / count.coerceAtLeast(1)
            val usedSlot = minOf(rowSlot, slotW)
            val offsetX = (width - usedSlot * count) / 2
            for (c in 0 until count) {
                val i = start + c
                val cx = offsetX + usedSlot * (c + 0.5f)
                val h = tubes[i].capacity * itemSize + pad * 2
                tubeRects += Rectangle(cx - tubeW / 2, baseY, tubeW, h)
                hitRects += Rectangle(cx - usedSlot / 2, rowY, usedSlot, rowH)
            }
        }
    }

    /** Center of slot [index] in tube [tube], in local coordinates. */
    fun itemCenter(
        tube: Int,
        index: Int,
        lifted: Boolean = false,
    ): Pair<Float, Float> {
        val r = tubeRects[tube]
        val y = r.y + pad + itemSize * (index + 0.5f) + if (lifted) lift else 0f
        return (r.x + r.width / 2) to y
    }

    fun tubeRect(index: Int): Rectangle? = tubeRects.getOrNull(index)

    override fun act(delta: Float) {
        super.act(delta)
        time += delta
        countDown(shakes, delta)
        countDown(lids, delta)
        for (f in flights) f.t += delta
        flights.removeAll { it.t >= it.delay + it.duration }
    }

    private fun countDown(
        timers: MutableMap<Int, Float>,
        delta: Float,
    ) {
        val it = timers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.setValue(e.value - delta)
            if (e.value <= 0f) it.remove()
        }
    }

    override fun draw(
        batch: Batch,
        parentAlpha: Float,
    ) {
        if (tubeRects.size != board.tubes.size) layoutTubes()
        batch.end()
        Gdx.gl.glEnable(GL20.GL_BLEND)
        val sr = game.shapes
        sr.projectionMatrix = batch.projectionMatrix
        sr.transformMatrix.set(batch.transformMatrix).translate(x, y, 0f)
        sr.begin(ShapeRenderer.ShapeType.Filled)
        texts.clear()
        val flying = flights.map { it.item.id }.toHashSet()
        board.tubes.forEachIndexed { i, tube -> drawTube(sr, i, tube, flying) }
        for (f in flights) drawFlight(sr, f)
        pointer?.let { drawPointer(sr, it) }
        sr.end()
        batch.begin()
        for (t in texts) {
            t.font.color = t.color
            glyphs.setText(t.font, t.text)
            t.font.draw(batch, t.text, x + t.x - glyphs.width / 2, y + t.y + glyphs.height / 2)
        }
    }

    private fun shakeOffset(i: Int): Float {
        val s = shakes[i] ?: return 0f
        return sin(s * SHAKE_FREQ) * SHAKE_AMP * (s / SHAKE_TIME)
    }

    private fun drawTube(
        sr: ShapeRenderer,
        i: Int,
        tube: Tube,
        flying: Set<Int>,
    ) {
        val base = tubeRects.getOrNull(i) ?: return
        val r = Rectangle(base).apply { x += shakeOffset(i) }
        val radius = r.width / 2
        val glow = hint?.let { it.from == i || it.to == i } == true || pointer == i
        if (glow) {
            val pulse = if (reduceMotion) 1f else 0.75f + 0.25f * sin(time * 6f)
            sr.color = Color(Theme.highlight).apply { a = 0.85f * pulse }
            Shapes.tubeBody(sr, r.x - 7, r.y - 7, r.width + 14, r.height + 14, radius + 7)
        }
        sr.color = Theme.shadow
        Shapes.tubeBody(sr, r.x + 3, r.y - 4, r.width, r.height, radius)
        sr.color = Theme.glassEdge
        Shapes.tubeBody(sr, r.x, r.y, r.width, r.height, radius)
        sr.color = Theme.glass
        Shapes.tubeBody(sr, r.x + 3, r.y + 3, r.width - 6, r.height - 3, radius - 3)

        drawItems(sr, i, tube, r, flying)
        sr.color = GLASS_SHINE
        sr.rect(r.x + r.width * 0.18f, r.y + r.height * 0.2f, r.width * 0.1f, r.height * 0.7f)

        if (tube.isComplete) drawLid(sr, i, r)
        tube.onlyType?.let { drawLabel(sr, it, r) }
        tube.lockType?.let { drawLock(sr, it, r) }
    }

    private fun drawItems(
        sr: ShapeRenderer,
        i: Int,
        tube: Tube,
        r: Rectangle,
        flying: Set<Int>,
    ) {
        val unit = if (selected == i) Rules.movableUnit(tube) else null
        val liftFrom = if (unit != null) tube.size - unit.size else Int.MAX_VALUE
        tube.items.forEachIndexed { k, item ->
            if (item.id in flying) return@forEachIndexed
            val cx = r.x + r.width / 2
            val bob = if (k >= liftFrom && !reduceMotion) sin(time * 5f) * 2f else 0f
            val cy = r.y + pad + itemSize * (k + 0.5f) + if (k >= liftFrom) lift + bob else 0f
            drawItem(sr, item, cx, cy)
            if (item.gluedUp && k + 1 < tube.size) {
                sr.color = GLUE
                Shapes.roundedRect(sr, cx - itemSize * 0.18f, cy + itemSize * 0.28f, itemSize * 0.36f, itemSize * 0.44f, itemSize * 0.15f)
            }
        }
    }

    private fun drawItem(
        sr: ShapeRenderer,
        item: Item,
        cx: Float,
        cy: Float,
    ) {
        val s = itemSize * 0.86f
        if (item.hidden) {
            sr.color = HIDDEN_BACK
            Shapes.roundedRect(sr, cx - s / 2, cy - s / 2, s, s, s * 0.3f)
            texts += TextDraw("?", cx, cy, game.assets.body, Color.WHITE)
            return
        }
        ItemArt.draw(sr, item.type, cx, cy, s, colorblind)
        if (item.isFrozen) {
            sr.color = ICE
            Shapes.roundedRect(sr, cx - s * 0.55f, cy - s * 0.55f, s * 1.1f, s * 1.1f, s * 0.25f)
            sr.color = ICE_EDGE
            sr.rect(cx - s * 0.4f, cy + s * 0.25f, s * 0.3f, s * 0.08f)
            texts += TextDraw(item.frozen.toString(), cx, cy, game.assets.body, Theme.ink)
        }
    }

    private fun drawLid(
        sr: ShapeRenderer,
        i: Int,
        r: Rectangle,
    ) {
        val t = lids[i]?.let { Interpolation.bounceOut.apply(1f - it / LID_TIME) } ?: 1f
        val drop = (1f - t) * itemSize
        val top = board.tubes[i].items.firstOrNull()?.type ?: 0
        val color = Theme.itemColor(top, colorblind)
        sr.color = Shapes.shade(color, 0.3f)
        Shapes.roundedRect(sr, r.x - 4, r.y + r.height - 2 + drop, r.width + 8, itemSize * 0.32f, itemSize * 0.12f)
        sr.color = Shapes.tint(color, 0.15f)
        Shapes.roundedRect(sr, r.x - 4, r.y + r.height + 1 + drop, r.width + 8, itemSize * 0.27f, itemSize * 0.12f)
    }

    private fun drawLabel(
        sr: ShapeRenderer,
        type: Int,
        r: Rectangle,
    ) {
        val s = itemSize * 0.5f
        val cy = r.y - itemSize * 0.36f
        sr.color = Theme.paper
        Shapes.roundedRect(sr, r.x - 2, cy - s * 0.6f, r.width + 4, s * 1.2f, s * 0.3f)
        ItemArt.draw(sr, type, r.x + r.width / 2, cy, s, colorblind, face = false)
    }

    private fun drawLock(
        sr: ShapeRenderer,
        type: Int,
        r: Rectangle,
    ) {
        sr.color = LOCK_VEIL
        Shapes.tubeBody(sr, r.x, r.y, r.width, r.height, r.width / 2)
        val cx = r.x + r.width / 2
        val cy = r.y + r.height * 0.55f
        val w = itemSize * 0.9f
        sr.color = Theme.inkSoft
        sr.arc(cx, cy + w * 0.35f, w * 0.32f, 0f, 180f, 16)
        sr.color = LOCK_VEIL_SOLID
        sr.arc(cx, cy + w * 0.35f, w * 0.18f, 0f, 180f, 16)
        sr.color = Theme.inkSoft
        Shapes.roundedRect(sr, cx - w / 2, cy - w * 0.4f, w, w * 0.78f, w * 0.15f)
        ItemArt.draw(sr, type, cx, cy - w * 0.02f, w * 0.55f, colorblind, face = false)
    }

    private fun drawFlight(
        sr: ShapeRenderer,
        f: Flight,
    ) {
        val p = ((f.t - f.delay) / f.duration).coerceIn(0f, 1f)
        val (tx, ty) = itemCenter(f.toTube, f.toIndex)
        val e = Interpolation.smooth.apply(p)
        val x = MathUtils.lerp(f.fromX, tx, e)
        val arc = if (reduceMotion) 0f else sin(p * MathUtils.PI) * itemSize * 1.6f
        val y = MathUtils.lerp(f.fromY, ty, e) + arc
        drawItem(sr, f.item, x, y)
    }

    private fun drawPointer(
        sr: ShapeRenderer,
        i: Int,
    ) {
        val r = tubeRects.getOrNull(i) ?: return
        val bounce = if (reduceMotion) 0f else sin(time * 6f) * 8f
        val cx = r.x + r.width / 2
        val top = r.y + r.height + itemSize * 1.2f + bounce
        sr.color = Theme.primary
        sr.triangle(cx - 16, top + 22, cx + 16, top + 22, cx, top)
        sr.rect(cx - 6f, top + 20f, 12f, 18f)
    }

    companion object {
        private const val ONE_ROW = 7
        private const val TWO_ROWS = 14
        private const val MAX_ITEM = 52f
        private const val ROW_EXTRA_ITEMS = 2.6f
        private const val LABEL_SPACE = 0.8f
        private const val FLIGHT = 0.28f
        private const val QUICK_FLIGHT = 0.08f
        private const val STAGGER = 0.04f
        private const val SHAKE_TIME = 0.3f
        private const val SHAKE_FREQ = 45f
        private const val SHAKE_AMP = 7f
        private const val LID_TIME = 0.45f
        private val GLUE = Color(1f, 0.97f, 0.85f, 0.95f)
        private val HIDDEN_BACK = Color.valueOf("8E86A8")
        private val ICE = Color(0.75f, 0.9f, 1f, 0.72f)
        private val ICE_EDGE = Color(1f, 1f, 1f, 0.9f)
        private val GLASS_SHINE = Color(1f, 1f, 1f, 0.35f)
        private val LOCK_VEIL = Color(0.9f, 0.88f, 0.95f, 0.6f)
        private val LOCK_VEIL_SOLID = Color(0.93f, 0.91f, 0.97f, 1f)
    }
}
