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
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.sortopia.SortopiaGame
import com.sortopia.layout.BoardLayout
import com.sortopia.model.Board
import com.sortopia.model.Item
import com.sortopia.model.Move
import com.sortopia.model.Tube
import com.sortopia.rules.Rules
import com.sortopia.theme.Look
import com.sortopia.ui.render.ItemArt
import com.sortopia.ui.render.Shapes
import com.sortopia.ui.render.TubeArt
import kotlin.math.sin

/**
 * Draws the tubes and items and turns taps into tube indices. All gameplay decisions stay in
 * [com.sortopia.game.GameSession]; this class only animates what it is told (SPEC §13).
 */
class BoardView(
    private val game: SortopiaGame,
    var theme: WorldTheme = Theme.endless,
    var look: Look = Look(theme.def, theme.def.items),
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

    /**
     * Tap target for each tube, in local coordinates. The cells tile the whole board, so a tap
     * always goes to the nearest tube in its row (SPEC §14).
     */
    val hitRects = ArrayList<Rectangle>()
    private var layout = BoardLayout.compute(0f, 0f, emptyList())
    private val tubeRects = ArrayList<Rectangle>()
    private var itemSize = 40f
    private var pad = 5f
    private var lift = 30f

    private val shakes = HashMap<Int, Float>()
    private val lids = HashMap<Int, Float>()
    private val flights = ArrayList<Flight>()
    private var time = 0f
    private val glyphs = GlyphLayout()
    private val tmpRect = Rectangle()
    private val tmpMatrix = Matrix4()
    private val tmpMatrix2 = Matrix4()
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
                    val index = layout.tubeAt(x, y) ?: return false
                    onTubeTapped(index)
                    return true
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
        layout = BoardLayout.compute(width, height, board.tubes.map { it.capacity })
        tubeRects.clear()
        hitRects.clear()
        for (b in layout.tubes) tubeRects += Rectangle(b.x, b.y, b.w, b.h)
        for (b in layout.cells) hitRects += Rectangle(b.x, b.y, b.w, b.h)
        itemSize = layout.itemSize
        pad = layout.pad
        lift = layout.lift
    }

    /** Center of slot [index] in tube [tube], in local coordinates. */
    fun itemCenter(
        tube: Int,
        index: Int,
        lifted: Boolean = false,
    ): Pair<Float, Float> = layout.itemCenter(tube, index, lifted)

    /** The drawn glass of tube [index], in local coordinates (for the UI-test probe). */
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
        // Pass one: glow, shadow and glass, with the shape renderer.
        batch.end()
        Gdx.gl.glEnable(GL20.GL_BLEND)
        val sr = game.shapes
        sr.projectionMatrix = batch.projectionMatrix
        sr.transformMatrix.set(batch.transformMatrix).translate(x, y, 0f)
        sr.begin(ShapeRenderer.ShapeType.Filled)
        for (i in board.tubes.indices) drawGlass(sr, i)
        sr.end()
        // Pass two: dressing, items and overlays, as sprites.
        batch.begin()
        val oldTransform = tmpMatrix.set(batch.transformMatrix)
        batch.transformMatrix = tmpMatrix2.set(oldTransform).translate(x, y, 0f)
        texts.clear()
        val flying = flights.mapTo(HashSet()) { it.item.id }
        board.tubes.forEachIndexed { i, tube -> drawTube(batch, i, tube, flying) }
        for (f in flights) drawFlight(batch, f)
        pointer?.let { drawPointer(batch, it) }
        for (t in texts) {
            t.font.color = t.color
            glyphs.setText(t.font, t.text)
            t.font.draw(batch, t.text, t.x - glyphs.width / 2, t.y + glyphs.height / 2)
        }
        batch.transformMatrix = oldTransform
    }

    private fun shakeOffset(i: Int): Float {
        val s = shakes[i] ?: return 0f
        return sin(s * SHAKE_FREQ) * SHAKE_AMP * (s / SHAKE_TIME)
    }

    private fun shaken(i: Int): Rectangle? {
        val base = tubeRects.getOrNull(i) ?: return null
        return tmpRect.set(base).apply { x += shakeOffset(i) }
    }

    private fun drawGlass(
        sr: ShapeRenderer,
        i: Int,
    ) {
        val r = shaken(i) ?: return
        val radius = r.width / 2
        val glow = hint?.let { it.from == i || it.to == i } == true || pointer == i
        if (glow) {
            val pulse = if (reduceMotion) 1f else 0.75f + 0.25f * sin(time * 6f)
            sr.color = Color(Theme.highlight).apply { a = 0.85f * pulse }
            Shapes.tubeBody(sr, r.x - 7, r.y - 7, r.width + 14, r.height + 14, radius + 7)
        }
        TubeArt.glass(sr, theme, r, time, !reduceMotion)
    }

    private fun drawTube(
        batch: Batch,
        i: Int,
        tube: Tube,
        flying: Set<Int>,
    ) {
        val r = shaken(i) ?: return
        val art = game.assets.art
        TubeArt.base(batch, art, theme, r)
        drawItems(batch, i, tube, r, flying)
        TubeArt.shine(batch, game.assets.white, r)
        TubeArt.collar(batch, art, theme, r, itemSize)
        if (tube.isComplete) drawLid(batch, i, r)
        tube.onlyType?.let { drawLabel(batch, it, r) }
        tube.lockType?.let { drawLock(batch, it, r) }
    }

    private fun drawItems(
        batch: Batch,
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
            drawItem(batch, item, cx, cy)
            if (item.gluedUp && k + 1 < tube.size) sprite(batch, "ov.glue", cx, cy + itemSize * 0.5f, itemSize * 0.62f)
        }
    }

    private fun drawItem(
        batch: Batch,
        item: Item,
        cx: Float,
        cy: Float,
    ) {
        val s = itemSize * ITEM_CELL
        if (item.hidden) {
            sprite(batch, "ov.hidden", cx, cy, itemSize * HIDDEN_CELL)
            return
        }
        ItemArt.draw(batch, game.assets.art, look, item.type, cx, cy, s, colorblind)
        if (item.isFrozen) {
            sprite(batch, "ov.ice", cx, cy, itemSize * ICE_CELL)
            texts += TextDraw(item.frozen.toString(), cx, cy, game.assets.body, Theme.ink)
        }
    }

    private fun sprite(
        batch: Batch,
        name: String,
        cx: Float,
        cy: Float,
        side: Float,
    ) {
        game.assets.art.region(name)?.let { batch.draw(it, cx - side / 2, cy - side / 2, side, side) }
    }

    private fun drawLid(
        batch: Batch,
        i: Int,
        r: Rectangle,
    ) {
        val t = lids[i]?.let { Interpolation.bounceOut.apply(1f - it / LID_TIME) } ?: 1f
        val drop = (1f - t) * itemSize
        val top = board.tubes[i].items.firstOrNull()?.type ?: 0
        TubeArt.lid(batch, game.assets.art, theme, r, drop, ItemArt.color(look, top, colorblind))
    }

    private fun drawLabel(
        batch: Batch,
        type: Int,
        r: Rectangle,
    ) {
        val cy = r.y - itemSize * 0.36f
        val cx = r.x + r.width / 2
        sprite(batch, "ov.label", cx, cy, (r.width + 10f) / LABEL_SPAN)
        ItemArt.draw(batch, game.assets.art, look, type, cx, cy, itemSize * 0.62f, colorblind, face = false)
    }

    private fun drawLock(
        batch: Batch,
        type: Int,
        r: Rectangle,
    ) {
        val old = batch.packedColor
        batch.color = LOCK_VEIL
        batch.draw(game.assets.white, r.x, r.y, r.width, r.height)
        batch.packedColor = old
        val cx = r.x + r.width / 2
        val cy = r.y + r.height * 0.55f
        val w = maxOf(itemSize * 1.25f, r.width * 1.1f)
        sprite(batch, "ov.lock", cx, cy, w)
        // The padlock's window sits at (50, 67) of its 100-unit cell.
        ItemArt.draw(batch, game.assets.art, look, type, cx, cy - w * 0.17f, w * 0.38f, colorblind, face = false)
    }

    private fun drawFlight(
        batch: Batch,
        f: Flight,
    ) {
        val p = ((f.t - f.delay) / f.duration).coerceIn(0f, 1f)
        val (tx, ty) = itemCenter(f.toTube, f.toIndex)
        val e = Interpolation.smooth.apply(p)
        val x = MathUtils.lerp(f.fromX, tx, e)
        val arc = if (reduceMotion) 0f else sin(p * MathUtils.PI) * itemSize * 1.6f
        val y = MathUtils.lerp(f.fromY, ty, e) + arc
        drawItem(batch, f.item, x, y)
    }

    private fun drawPointer(
        batch: Batch,
        i: Int,
    ) {
        val r = tubeRects.getOrNull(i) ?: return
        val bounce = if (reduceMotion) 0f else sin(time * 6f) * 8f
        val cx = r.x + r.width / 2
        val side = POINTER_SIZE
        sprite(batch, "ov.pointer", cx, r.y + r.height + itemSize * 1.2f + bounce + side / 2, side)
    }

    companion object {
        private const val FLIGHT = 0.28f
        private const val QUICK_FLIGHT = 0.08f
        private const val STAGGER = 0.04f
        private const val SHAKE_TIME = 0.3f
        private const val SHAKE_FREQ = 45f
        private const val SHAKE_AMP = 7f
        private const val LID_TIME = 0.45f
        private const val ITEM_CELL = 1.08f
        private const val HIDDEN_CELL = 0.98f
        private const val ICE_CELL = 1.02f
        private const val LABEL_SPAN = 0.88f
        private const val POINTER_SIZE = 46f
        private val LOCK_VEIL = Color(0.9f, 0.88f, 0.95f, 0.55f)
    }
}
