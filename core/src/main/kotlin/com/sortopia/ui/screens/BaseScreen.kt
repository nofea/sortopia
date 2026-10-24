package com.sortopia.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.sortopia.SortopiaGame
import com.sortopia.theme.Particle
import com.sortopia.ui.Theme
import com.sortopia.ui.Widgets
import com.sortopia.ui.WorldTheme
import kotlin.math.sin

/**
 * Shared screen plumbing: a portrait [Stage], the world's background with idle particles,
 * modal overlays, the Android back key, and reporting element bounds to the UI-test probe.
 */
abstract class BaseScreen(
    protected val game: SortopiaGame,
    protected open val theme: WorldTheme = Theme.menu,
) : ScreenAdapter() {
    protected val stage = Stage(ExtendViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT), game.batch)
    protected val ui = Widgets(game)

    /** Screen content, kept inside the display's safe area (see [applySafeArea]). */
    protected val root = Table()
    protected val assets get() = game.assets

    protected fun str(
        key: String,
        vararg args: Any,
    ) = assets.str(key, *args)

    private val probed = LinkedHashMap<String, Actor>()
    private var overlay: Table? = null
    protected val hasOverlay: Boolean get() = overlay != null
    private var time = 0f

    // The display's safe-area insets, in pixels as last applied and in world units.
    private var insets = Insets(-1, -1, -1, -1)
    private var safeLeft = 0f
    private var safeTop = 0f
    private var safeRight = 0f
    private var safeBottom = 0f

    abstract val probeName: String

    init {
        stage.addActor(root)
        stage.addListener(
            object : InputListener() {
                override fun keyDown(
                    event: InputEvent?,
                    keycode: Int,
                ): Boolean {
                    if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) {
                        if (overlay != null) onOverlayBack() else onBack()
                        return true
                    }
                    return false
                }
            },
        )
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        game.probe.screen(probeName)
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        stage.viewport.update(width, height, true)
        insets = Insets(-1, -1, -1, -1)
        applySafeArea()
    }

    /**
     * The world background fills the whole screen, but [root] and overlay content stay clear of
     * camera cutouts and rounded corners: the app targets Android 15+, which draws it edge to edge.
     * Insets can arrive after the first resize, so this re-checks every frame and only relayouts on
     * a change.
     */
    private fun applySafeArea() {
        val g = Gdx.graphics
        val now = Insets(g.safeInsetLeft, g.safeInsetTop, g.safeInsetRight, g.safeInsetBottom)
        if (now == insets) return
        insets = now
        val vp = stage.viewport
        val sx = vp.worldWidth / vp.screenWidth.coerceAtLeast(1)
        val sy = vp.worldHeight / vp.screenHeight.coerceAtLeast(1)
        safeLeft = now.left * sx
        safeTop = now.top * sy
        safeRight = now.right * sx
        safeBottom = now.bottom * sy
        root.setBounds(safeLeft, safeBottom, vp.worldWidth - safeLeft - safeRight, vp.worldHeight - safeTop - safeBottom)
        overlay?.pad(safeTop, safeLeft, safeBottom, safeRight)
    }

    private data class Insets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    override fun render(delta: Float) {
        time += delta
        Gdx.gl.glClearColor(theme.backgroundBottom.r, theme.backgroundBottom.g, theme.backgroundBottom.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        applySafeArea()
        drawBackground()
        update(delta)
        stage.act(delta)
        stage.draw()
        reportProbe()
    }

    /** Per-frame logic before the stage acts. */
    protected open fun update(delta: Float) {}

    protected open fun onBack() {}

    protected open fun onOverlayBack() = closeOverlay()

    /**
     * The world's background (SPEC §13): its gradient, an illustrated band pinned to the top and
     * one to the bottom (so any screen height works), and its idle particles.
     */
    private fun drawBackground() {
        val cam = stage.camera
        val sr = game.shapes
        val w = stage.viewport.worldWidth
        val h = stage.viewport.worldHeight
        Gdx.gl.glEnable(GL20.GL_BLEND)
        sr.projectionMatrix = cam.combined
        sr.transformMatrix.idt()
        sr.begin(ShapeRenderer.ShapeType.Filled)
        sr.rect(0f, 0f, w, h, theme.backgroundBottom, theme.backgroundBottom, theme.backgroundTop, theme.backgroundTop)
        sr.end()
        val batch = game.batch
        batch.projectionMatrix = cam.combined
        batch.transformMatrix.idt()
        batch.begin()
        batch.setColor(1f, 1f, 1f, 1f)
        val art = assets.art
        art.background(theme.def.id, "bottom")?.let { batch.draw(it, 0f, 0f, w, w * it.height / it.width) }
        art.background(theme.def.id, "top")?.let {
            val bh = w * it.height / it.width
            batch.draw(it, 0f, h - bh, w, bh)
        }
        if (!game.settings.reduceMotion) drawParticles(batch, w, h)
        batch.end()
    }

    private fun drawParticles(
        batch: com.badlogic.gdx.graphics.g2d.Batch,
        w: Float,
        h: Float,
    ) {
        val kind = theme.def.particle
        val sprite = assets.art.region(kind.art) ?: return
        val falls = kind in FALLING
        val rises = kind in RISING
        for (i in 0 until PARTICLES) {
            val seed = i * 97.31f
            val size = 14f + (i % 4) * 6f
            val speed = 10f + (i % 4) * 5f
            val bx = (seed * 13.7f) % w + sin(time * 0.8f + i) * 8f
            val base = (seed * 7.3f) % (h + 80f)
            val by =
                when {
                    rises -> (base + time * speed) % (h + 80f) - 40f
                    falls -> h + 40f - (base + time * speed) % (h + 80f)
                    else -> base
                }
            // Twinkling kinds pulse in place; drifting ones fade gently.
            val pulse = if (rises || falls) PARTICLE_ALPHA else PARTICLE_ALPHA * (0.5f + 0.5f * sin(time * 1.6f + i * 1.3f))
            batch.setColor(1f, 1f, 1f, pulse)
            val spin = if (falls) sin(time * 0.9f + i) * 40f else 0f
            batch.draw(sprite, bx - size / 2, by - size / 2, size / 2, size / 2, size, size, 1f, 1f, spin)
        }
        batch.setColor(1f, 1f, 1f, 1f)
    }

    /** Registers [actor] so UI tests can find it by [id]. */
    protected fun <T : Actor> probe(
        id: String,
        actor: T,
    ): T {
        probed[id] = actor
        return actor
    }

    protected fun unprobe(prefix: String) {
        probed.keys.removeAll { it.startsWith(prefix) }
        game.probe.removeElementsWithPrefix(prefix)
    }

    private val tmp = Vector2()
    private val tmp2 = Vector2()

    private fun reportProbe() {
        val p = game.probe
        for ((id, actor) in probed) {
            if (actor.stage == null || !actor.isVisible) {
                p.element(id, -1f, -1f, 0f, 0f)
                continue
            }
            actor.localToStageCoordinates(tmp.set(0f, 0f))
            actor.localToStageCoordinates(tmp2.set(actor.width, actor.height))
            stage.stageToScreenCoordinates(tmp)
            stage.stageToScreenCoordinates(tmp2)
            p.element(id, minOf(tmp.x, tmp2.x), minOf(tmp.y, tmp2.y), kotlin.math.abs(tmp2.x - tmp.x), kotlin.math.abs(tmp2.y - tmp.y))
        }
        p.state("overlay", overlay?.name ?: "")
    }

    /** Shows [content] centered over a dimmed screen; taps outside don't dismiss it. */
    protected fun showOverlay(
        name: String,
        content: Table,
    ) {
        // Replace any open overlay, but keep the probe ids the new content just registered.
        overlay?.remove()
        val layer =
            Table().apply {
                setFillParent(true)
                background = assets.solid(DIM)
                touchable = Touchable.enabled
                this.name = name
                pad(safeTop, safeLeft, safeBottom, safeRight)
                add(content).width(VIRTUAL_WIDTH - 48f)
            }
        if (!game.settings.reduceMotion) {
            layer.color.a = 0f
            layer.addAction(Actions.fadeIn(0.15f))
        }
        overlay = layer
        stage.addActor(layer)
        game.probe.state("overlay", name)
    }

    protected fun closeOverlay() {
        overlay?.remove()
        overlay = null
        unprobe("overlay-")
    }

    /** Set once the screen is gone; late background results must then be dropped. */
    protected var disposed = false
        private set

    override fun dispose() {
        disposed = true
        unprobe("")
        stage.dispose()
    }

    companion object {
        const val VIRTUAL_WIDTH = 480f
        const val VIRTUAL_HEIGHT = 860f
        private const val PARTICLES = 14
        private const val PARTICLE_ALPHA = 0.55f
        private val FALLING = setOf(Particle.LEAF, Particle.ENVELOPE, Particle.FLOUR, Particle.SNOWFLAKE)
        private val RISING = setOf(Particle.BUBBLE, Particle.DOT)
        private val DIM = Color(0.17f, 0.12f, 0.25f, 0.55f)

        fun wobble(t: Float): Float = MathUtils.sin(t)
    }
}
