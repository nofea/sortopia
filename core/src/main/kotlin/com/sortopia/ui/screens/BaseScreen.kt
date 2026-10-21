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
import com.sortopia.ui.Theme
import com.sortopia.ui.Widgets
import com.sortopia.ui.WorldTheme
import kotlin.math.sin

/**
 * Shared screen plumbing: a portrait [Stage], themed gradient background with idle bubbles,
 * modal overlays, the Android back key, and reporting element bounds to the UI-test probe.
 */
abstract class BaseScreen(
    protected val game: SortopiaGame,
    protected open val theme: WorldTheme = Theme.world(1),
) : ScreenAdapter() {
    protected val stage = Stage(ExtendViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT), game.batch)
    protected val ui = Widgets(game)
    protected val root = Table().apply { setFillParent(true) }
    protected val assets get() = game.assets

    protected fun str(
        key: String,
        vararg args: Any,
    ) = assets.str(key, *args)

    private val probed = LinkedHashMap<String, Actor>()
    private var overlay: Table? = null
    protected val hasOverlay: Boolean get() = overlay != null
    private var time = 0f

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
    }

    override fun render(delta: Float) {
        time += delta
        Gdx.gl.glClearColor(theme.backgroundBottom.r, theme.backgroundBottom.g, theme.backgroundBottom.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
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
        val motion = !game.settings.reduceMotion
        for (i in 0 until BUBBLES) {
            val seed = i * 97.31f
            val bx = (seed * 13.7f) % w
            val speed = 8f + (i % 4) * 4f
            val by = if (motion) (seed * 7.3f + time * speed) % (h + 80f) - 40f else (seed * 7.3f) % h
            val wobble = if (motion) sin(time * 0.8f + i) * 6f else 0f
            sr.color = BUBBLE
            sr.circle(bx + wobble, by, 10f + (i % 5) * 6f, 20)
        }
        sr.end()
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

    override fun dispose() {
        unprobe("")
        stage.dispose()
    }

    companion object {
        const val VIRTUAL_WIDTH = 480f
        const val VIRTUAL_HEIGHT = 860f
        private const val BUBBLES = 14
        private val BUBBLE = Color(1f, 1f, 1f, 0.22f)
        private val DIM = Color(0.17f, 0.12f, 0.25f, 0.55f)

        fun wobble(t: Float): Float = MathUtils.sin(t)
    }
}
