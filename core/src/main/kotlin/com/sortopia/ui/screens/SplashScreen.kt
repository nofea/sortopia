package com.sortopia.ui.screens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.sortopia.SortopiaGame
import com.sortopia.game.SplashTiming
import com.sortopia.model.LevelId
import com.sortopia.ui.Sfx
import com.sortopia.ui.ShapeActor
import com.sortopia.ui.Theme
import com.sortopia.ui.render.ItemArt
import com.sortopia.ui.render.Shapes
import kotlin.math.sin

/**
 * Cold-start splash and loading screen (SPEC §12): items of one random type pour into a tube,
 * the lid seals, and the title bounces in. It stays until the levels the player is likely to
 * open next are generated, within [SplashTiming]'s minimum and cap.
 */
class SplashScreen(
    game: SortopiaGame,
) : BaseScreen(game) {
    override val probeName = "splash"
    private val motion = !game.settings.reduceMotion
    private val colorblind = game.settings.colorblindPalette
    private val minimum = SplashTiming.minimum(game.settings.reduceMotion, game.probe.fastSplash)
    private val itemType = MathUtils.random(ItemArt.SHAPES - 1)
    private var elapsed = if (motion) 0f else ANIMATION_END
    private var waited = 0f
    private var ready = false
    private var left = false
    private val loading = ui.label("", "soft")

    init {
        root.add(ShapeActor(game, ::drawTube)).size(TUBE_W, TUBE_H + LID_SPACE).padBottom(28f).row()
        root.add(title()).row()
        root.add(fadeIn(ui.label(str("app.tagline"), "soft"), TAGLINE_AT)).padTop(6f).row()
        root.add(loading).padTop(28f).height(loading.prefHeight)
        if (!motion) {
            root.color.a = 0f
            root.addAction(Actions.fadeIn(REDUCED_FADE))
            game.audio.play(Sfx.TUBE)
        }
        game.probe.state("splash.ready", "false")
        game.probe.state("splash.animated", (!motion).toString())
        val progress = game.progress
        val ids =
            listOfNotNull(
                progress.nextCampaign(),
                LevelId.Endless(progress.nextEndless()).takeIf { progress.isEndlessUnlocked() },
                progress.dailyFor(game.platform.today()).takeIf { progress.isDailyUnlocked() },
            )
        game.prefetchAll(ids) {
            ready = true
            game.probe.state("splash.ready", "true")
        }
    }

    /** "Sortopia", one transformable cell per letter so each can pop in on its own. */
    private fun title(): Table {
        val row = Table()
        val name = str("app.name")
        for ((i, ch) in name.withIndex()) {
            val cell =
                object : Container<Label>(ui.label(ch.toString(), "huge")) {
                    override fun layout() {
                        super.layout()
                        setOrigin(width / 2, height / 2)
                    }
                }
            cell.isTransform = true
            if (motion) {
                cell.setScale(0f)
                cell.color.a = 0f
                cell.addAction(
                    Actions.delay(
                        TITLE_AT + i * LETTER_STAGGER,
                        Actions.parallel(Actions.scaleTo(1f, 1f, LETTER_TIME, Interpolation.swingOut), Actions.fadeIn(LETTER_TIME / 2)),
                    ),
                )
            }
            row.add(cell)
        }
        return probe("splash-title", row)
    }

    private fun <T : Actor> fadeIn(
        actor: T,
        at: Float,
    ): T {
        if (motion) {
            actor.color.a = 0f
            actor.addAction(Actions.delay(at, Actions.fadeIn(FADE_TIME)))
        }
        return actor
    }

    override fun update(delta: Float) {
        val before = elapsed
        elapsed += delta
        waited += delta
        if (motion) playCues(before, elapsed)
        if (before < ANIMATION_END && elapsed >= ANIMATION_END) game.probe.state("splash.animated", "true")
        loading.isVisible = SplashTiming.showLoadingHint(waited, ready)
        if (loading.isVisible) {
            val dots = if (motion) 1 + (waited * DOTS_PER_SECOND).toInt() % MAX_DOTS else MAX_DOTS
            loading.setText(str("splash.loading") + ".".repeat(dots))
        }
        if (!left && SplashTiming.shouldLeave(waited, ready, minimum)) {
            left = true
            game.go(WorldMapScreen(game))
        }
    }

    /** Sounds synced to the animation: a pour per landing item, then the seal (SPEC §13). */
    private fun playCues(
        before: Float,
        now: Float,
    ) {
        for (i in 0 until CAPACITY) {
            val landed = itemStart(i) + DROP_TIME * LANDING_FRACTION
            if (before < landed && now >= landed) game.audio.play(Sfx.POUR)
        }
        if (before < LID_AT && now >= LID_AT) game.audio.play(Sfx.TUBE)
    }

    private fun itemStart(i: Int) = FIRST_ITEM_AT + i * ITEM_STAGGER

    private fun phase(
        start: Float,
        length: Float,
    ) = ((elapsed - start) / length).coerceIn(0f, 1f)

    private fun drawTube(
        sr: ShapeRenderer,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val bob = if (motion && elapsed > ANIMATION_END) sin((elapsed - ANIMATION_END) * BOB_SPEED) * BOB_HEIGHT else 0f
        val ty = y + bob
        val th = h - LID_SPACE
        val radius = w / 2
        sr.color = Theme.shadow
        Shapes.tubeBody(sr, x + 3, ty - 4, w, th, radius)
        sr.color = Theme.glassEdge
        Shapes.tubeBody(sr, x, ty, w, th, radius)
        sr.color = Theme.glass
        Shapes.tubeBody(sr, x + 3, ty + 3, w - 6, th - 3, radius - 3)

        val cx = x + w / 2
        val slot = (th - TUBE_PAD * 2) / CAPACITY
        for (i in 0 until CAPACITY) {
            val p = phase(itemStart(i), DROP_TIME)
            if (p <= 0f) continue
            val target = ty + TUBE_PAD + slot * (i + 0.5f)
            val cy = MathUtils.lerp(y + h + ITEM_SIZE, target, Interpolation.bounceOut.apply(p))
            ItemArt.draw(sr, itemType, cx, cy, ITEM_SIZE, colorblind)
        }
        sr.color = SHINE
        sr.rect(x + w * 0.18f, ty + th * 0.2f, w * 0.1f, th * 0.7f)

        val lid = phase(LID_AT, LID_TIME)
        if (lid > 0f) {
            val drop = (1f - Interpolation.bounceOut.apply(lid)) * LID_SPACE * 2
            val color = Theme.itemColor(itemType, colorblind)
            sr.color = Shapes.shade(color, 0.3f)
            Shapes.roundedRect(sr, x - 4, ty + th - 2 + drop, w + 8, ITEM_SIZE * 0.32f, ITEM_SIZE * 0.12f)
            sr.color = Shapes.tint(color, 0.15f)
            Shapes.roundedRect(sr, x - 4, ty + th + 1 + drop, w + 8, ITEM_SIZE * 0.27f, ITEM_SIZE * 0.12f)
        }
    }

    companion object {
        private const val CAPACITY = 4
        private const val TUBE_W = 84f
        private const val TUBE_H = 280f
        private const val TUBE_PAD = 6f
        private const val LID_SPACE = 24f
        private const val ITEM_SIZE = 60f

        // Animation timeline, in seconds; it ends just before SplashTiming.MIN_SECONDS.
        private const val FIRST_ITEM_AT = 0.3f
        private const val ITEM_STAGGER = 0.3f
        private const val DROP_TIME = 0.4f
        private const val LANDING_FRACTION = 0.36f
        private const val LID_AT = 1.6f
        private const val LID_TIME = 0.4f
        private const val TITLE_AT = 1.8f
        private const val LETTER_STAGGER = 0.07f
        private const val LETTER_TIME = 0.35f
        private const val TAGLINE_AT = 2.45f
        private const val FADE_TIME = 0.4f
        private const val ANIMATION_END = 2.9f

        private const val REDUCED_FADE = 0.2f
        private const val BOB_SPEED = 2.2f
        private const val BOB_HEIGHT = 4f
        private const val DOTS_PER_SECOND = 2.5f
        private const val MAX_DOTS = 3
        private val SHINE = Color(1f, 1f, 1f, 0.35f)
    }
}
