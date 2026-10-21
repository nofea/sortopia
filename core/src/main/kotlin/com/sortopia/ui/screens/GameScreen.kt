package com.sortopia.ui.screens

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.sortopia.SortopiaGame
import com.sortopia.game.EndlessPages
import com.sortopia.game.GameSession
import com.sortopia.game.HelperResult
import com.sortopia.game.Helpers
import com.sortopia.game.SolveOutcome
import com.sortopia.gen.GeneratedLevel
import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.model.Move
import com.sortopia.platform.Haptic
import com.sortopia.rules.PourResult
import com.sortopia.rules.Rules
import com.sortopia.save.EventCodec
import com.sortopia.save.InProgress
import com.sortopia.solver.Solver
import com.sortopia.ui.BoardView
import com.sortopia.ui.Panels
import com.sortopia.ui.Sfx
import com.sortopia.ui.ShapeActor
import com.sortopia.ui.Theme
import com.sortopia.ui.WorldTheme

/** Gameplay (SPEC §2, §4, §11, §12). [resume] replays a level left mid-play. */
class GameScreen(
    game: SortopiaGame,
    private val id: LevelId,
    private val resume: InProgress? = null,
) : BaseScreen(game) {
    override val theme: WorldTheme =
        when (id) {
            is LevelId.Campaign -> Theme.world(id.world)
            is LevelId.Endless -> Theme.endless
            is LevelId.Daily -> Theme.daily
        }
    override val probeName = "game"

    private val progress get() = game.progress
    private var session: GameSession? = null
    private val board = BoardView(game, ::onTubeTapped)
    private val panels = Panels(game, ui) { pid, b -> probe("overlay-$pid", b) }

    private val titleLabel = ui.label(levelTitle(), "default")
    private val movesLabel = ui.label("", "small")
    private val timerLabel = ui.label("", "small")
    private val walletLabel = ui.label("", "default")
    private val banner = ui.label("", "default", wrap = true)
    private val toast = ui.label("", "light", wrap = true)
    private val loading = ui.label(str("game.preparing"), "title")
    private val confetti = Confetti()

    private var tutorial = false
    private var tutorialSealedShown = false
    private var hintPending = false
    private var completeQueued = false

    init {
        buildLayout()
        game.audio.playMusic(
            when (id) {
                is LevelId.Campaign -> id.world
                is LevelId.Endless -> ENDLESS_TRACK
                is LevelId.Daily -> DAILY_TRACK
            },
        )
        game.level(id, ::start)
    }

    private fun levelTitle(): String =
        when (id) {
            is LevelId.Campaign -> str("hud.level", id.label)
            is LevelId.Endless -> str("hud.endless", id.number)
            is LevelId.Daily -> str("hud.daily", id.date.toString())
        }

    private fun buildLayout() {
        val top = Table()
        val pause = probe("pause", ui.button("II") { openPause() })
        top.add(pause).size(56f).left()
        val mid = Table()
        mid.add(titleLabel).row()
        mid.add(movesLabel).row()
        mid.add(timerLabel)
        top.add(mid).expandX()
        val wallet = Table()
        wallet.add(ui.coin(24f)).padRight(6f)
        wallet.add(probe("wallet", walletLabel))
        top.add(wallet).right().minWidth(90f)
        root.top().pad(12f)
        root.add(top).expandX().fillX().row()
        root.add(banner).expandX().fillX().height(64f).padTop(4f).row()
        val stack = com.badlogic.gdx.scenes.scene2d.ui.Stack()
        stack.add(board)
        stack.add(Table().apply { add(loading) })
        root.add(stack).expand().fill().row()
        root.add(toast).expandX().fillX().height(40f).row()
        val helpers = Table()
        helpers.defaults().expandX().fillX().height(72f).pad(4f)
        helpers.add(helperButton("undo", str("helper.undo"), Helpers.UNDO_COST) { undo() })
        helpers.add(helperButton("hint", str("helper.hint"), Helpers.HINT_COST) { hint() })
        helpers.add(helperButton("extra", str("helper.extra"), Helpers.EXTRA_TUBE_COST) { extraTube() })
        helpers.add(helperButton("restart", str("helper.restart"), 0) { restart() })
        root.add(helpers).expandX().fillX().padBottom(8f)
        stage.addActor(confetti)
        toast.color.a = 0f
    }

    private fun helperButton(
        pid: String,
        text: String,
        cost: Int,
        onClick: () -> Unit,
    ): TextButton {
        val b = ui.button(text, onClick = onClick)
        b.row()
        b.add(ui.label(if (cost == 0) str("helper.free") else str("helper.cost", cost), "small"))
        return probe("helper-$pid", b)
    }

    private fun start(level: GeneratedLevel) = start(level, replaySaved = true)

    private fun start(
        level: GeneratedLevel,
        replaySaved: Boolean,
    ) {
        val s = GameSession(level, progress.wallet)
        if (replaySaved && resume != null && resume.levelKey == id.key) {
            val events = resume.events.mapNotNull(EventCodec::decode)
            if (!s.replay(events, resume.elapsedSeconds)) {
                // The saved moves don't fit this level any more: start it fresh instead.
                game.updateSave { it.copy(inProgress = null) }
                start(level, replaySaved = false)
                return
            }
        }
        session = s
        loading.isVisible = false
        board.board = s.board
        refreshHud()
        prefetchNext()
        showIntroOverlays(level)
        publishSolution()
    }

    private fun showIntroOverlays(level: GeneratedLevel) {
        val campaign = id as? LevelId.Campaign
        when {
            campaign?.world == 1 && campaign.level == 1 && !progress.hasFlag(FLAG_TUTORIAL) -> {
                tutorial = true
                updateTutorial()
            }
            campaign != null && campaign.level == 1 && campaign.world > 1 && !progress.hasFlag(cardFlag(campaign.world)) -> {
                val mechanic = Tuning.worldMechanic[campaign.world]
                showOverlay(
                    "card",
                    panels.card(listOfNotNull(mechanic), all = mechanic == null) {
                        progress.setFlag(cardFlag(campaign.world))
                        game.save()
                        closeOverlay()
                    },
                )
            }
            !progress.hasFlag(FLAG_TIPS) && progress.hasFlag(FLAG_TUTORIAL) -> showTips()
        }
        if (level.params.mechanics.isEmpty() && tutorial) banner.setText(str("tutorial.step1"))
    }

    private fun showTips() {
        val panel = ui.panel()
        panel.add(ui.label(str("tips.title"), "title")).padBottom(12f).row()
        listOf(
            str("tips.undo", Helpers.UNDO_COST),
            str("tips.hint", Helpers.HINT_COST),
            str("tips.extra", Helpers.EXTRA_TUBE_COST),
            str("tips.restart"),
        ).forEach { panel.add(ui.label(it, wrap = true, align = Align.left)).fillX().padBottom(8f).row() }
        val ok =
            ui.button(str("card.ok"), "primary") {
                progress.setFlag(FLAG_TIPS)
                game.save()
                closeOverlay()
            }
        probe("overlay-tips-ok", ok)
        panel.add(ok).width(Panels.WIDE_W).height(Panels.BUTTON_H).padTop(8f)
        showOverlay("tips", panel)
    }

    private fun cardFlag(world: Int) = "card-$world"

    private fun prefetchNext() {
        game.prefetch(nextLevel())
    }

    private fun nextLevel(): LevelId? =
        when (id) {
            is LevelId.Campaign ->
                if (id.level < LevelId.LEVELS_PER_WORLD) {
                    LevelId.Campaign(id.world, id.level + 1)
                } else if (id.world < LevelId.WORLDS) {
                    LevelId.Campaign(id.world + 1, 1)
                } else {
                    LevelId.Endless(1)
                }
            is LevelId.Endless -> LevelId.Endless(id.number + 1)
            is LevelId.Daily -> null
        }

    override fun update(delta: Float) {
        val s = session ?: return
        if (!hasOverlay && !s.isSolved) s.tick(delta.toDouble())
        if (completeQueued && !board.isAnimating) {
            completeQueued = false
            complete()
        }
        refreshHud()
        reportTubes()
    }

    private fun refreshHud() {
        val s = session
        walletLabel.setText(progress.wallet.points.toString())
        if (s == null) return
        movesLabel.setText(str("hud.moves", s.moves) + "   " + str("hud.par", s.level.par))
        timerLabel.setText(formatTime(s.elapsedSeconds))
    }

    private val tmp = Vector2()

    private fun reportTubes() {
        val p = game.probe
        if (!p.active) return
        val s = session ?: return
        for (i in s.board.tubes.indices) {
            val r = board.hitRects.getOrNull(i) ?: continue
            reportRect("tube-$i", r)
        }
        p.state("level", id.key)
        p.state("moves", s.moves.toString())
        p.state("wallet", progress.wallet.points.toString())
        p.state("solved", s.isSolved.toString())
        p.state("tubes", s.board.tubes.size.toString())
        p.state("selected", board.selected?.toString() ?: "")
        p.state("hint", board.hint?.let { "${it.from}>${it.to}" } ?: "")
        p.state("animating", board.isAnimating.toString())
    }

    private fun reportRect(
        pid: String,
        r: Rectangle,
    ) {
        board.localToStageCoordinates(tmp.set(r.x, r.y + r.height))
        stage.stageToScreenCoordinates(tmp)
        val x0 = tmp.x
        val y0 = tmp.y
        board.localToStageCoordinates(tmp.set(r.x + r.width, r.y))
        stage.stageToScreenCoordinates(tmp)
        game.probe.element(pid, x0, y0, tmp.x - x0, tmp.y - y0)
    }

    /** Test-only: the solver's solution from the current board, published for the UI tests. */
    private fun publishSolution() {
        if (!game.probe.active) return
        val s = session ?: return
        val snapshot = s.board
        game.workExecutor.execute {
            val r = Solver().solve(snapshot)
            val text = (r as? Solver.Result.Solved)?.moves?.joinToString(",") { "${it.from}>${it.to}" } ?: "none"
            com.badlogic.gdx.Gdx.app.postRunnable { if (session?.board == snapshot) game.probe.state("solution", text) }
        }
    }

    private fun onTubeTapped(index: Int) {
        val s = session ?: return
        if (hasOverlay || board.isAnimating || s.isSolved) return
        val sel = board.selected
        when {
            sel == null -> {
                if (Rules.movableUnit(s.board.tubes[index]) != null) {
                    board.selected = index
                    game.audio.play(Sfx.SELECT)
                } else {
                    board.shake(index)
                    game.audio.play(Sfx.ILLEGAL)
                }
            }
            sel == index -> board.selected = null
            else -> pour(s, Move(sel, index))
        }
        if (tutorial) updateTutorial()
    }

    private fun pour(
        s: GameSession,
        move: Move,
    ) {
        val before = s.board
        when (val r = s.pour(move)) {
            is PourResult.Ok -> {
                board.animatePour(before, r.board, move, r.movedCount)
                board.selected = null
                board.hint = null
                game.audio.play(Sfx.POUR)
                game.audio.haptic(Haptic.LIGHT)
                if (r.completedTubes.isNotEmpty()) {
                    board.sealed(r.completedTubes)
                    game.audio.play(Sfx.TUBE)
                    game.audio.haptic(Haptic.SUCCESS)
                    if (tutorial && !tutorialSealedShown) {
                        tutorialSealedShown = true
                        banner.setText(str("tutorial.sealed"))
                    }
                }
                afterBoardChange()
            }
            is PourResult.Illegal -> {
                board.shake(move.to)
                board.selected = null
                game.audio.play(Sfx.ILLEGAL)
            }
        }
    }

    private fun afterBoardChange() {
        val s = session ?: return
        board.board = s.board
        when {
            s.isSolved -> completeQueued = true
            s.isStuck -> showStuck()
        }
        publishSolution()
    }

    private fun updateTutorial() {
        val s = session ?: return
        if (s.isSolved) {
            board.pointer = null
            return
        }
        val move = Solver().hint(s.board)
        val sel = board.selected
        when {
            move == null -> board.pointer = null
            sel == null -> {
                board.pointer = move.from
                if (s.moves == 0) banner.setText(str("tutorial.step1"))
            }
            else -> {
                board.pointer = if (sel == move.from) move.to else null
                if (s.moves == 0) banner.setText(str("tutorial.step2"))
            }
        }
        if (s.moves == 1 && !tutorialSealedShown) banner.setText(str("tutorial.step3"))
    }

    private fun undo() {
        val s = session ?: return
        if (hasOverlay || board.isAnimating) return
        when (s.undo()) {
            HelperResult.Done -> {
                game.audio.play(Sfx.HELPER)
                board.selected = null
                board.hint = null
                game.save()
                afterBoardChange()
            }
            HelperResult.CannotAfford -> showToast(str("game.noafford"))
            else -> showToast(str("game.nothingtoundo"))
        }
        if (tutorial) updateTutorial()
    }

    private fun hint() {
        val s = session ?: return
        if (hasOverlay || hintPending || s.isSolved) return
        if (!s.canAffordHint()) {
            showToast(str("game.noafford"))
            return
        }
        hintPending = true
        showToast(str("game.thinking"))
        val snapshot = s.board
        game.workExecutor.execute {
            val move = Solver().hint(snapshot)
            com.badlogic.gdx.Gdx.app.postRunnable {
                hintPending = false
                when (val r = s.applyHint(snapshot, move)) {
                    is HelperResult.Hint -> {
                        board.hint = r.move
                        game.audio.play(Sfx.HELPER)
                        game.save()
                        showToast("")
                    }
                    HelperResult.DeadEnd -> showToast(str("game.deadend"))
                    HelperResult.CannotAfford -> showToast(str("game.noafford"))
                    else -> showToast("")
                }
            }
        }
    }

    private fun extraTube() {
        val s = session ?: return
        if (hasOverlay || board.isAnimating) return
        when (s.extraTube()) {
            HelperResult.Done -> {
                game.audio.play(Sfx.HELPER)
                game.save()
                board.selected = null
                afterBoardChange()
            }
            HelperResult.CannotAfford -> showToast(str("game.noafford"))
            else -> showToast(str("game.usedextra"))
        }
    }

    private fun restart() {
        val s = session ?: return
        if (board.isAnimating) return
        closeOverlay()
        s.restart()
        board.selected = null
        board.hint = null
        afterBoardChange()
        if (tutorial) updateTutorial()
    }

    private fun showToast(text: String) {
        toast.setText(text)
        toast.clearActions()
        if (text.isEmpty()) {
            toast.color.a = 0f
            return
        }
        toast.color.a = 1f
        toast.addAction(Actions.sequence(Actions.delay(TOAST_TIME), Actions.fadeOut(0.4f)))
    }

    private fun showStuck() {
        val panel = ui.panel()
        panel.add(ui.label(str("stuck.title"), "title")).padBottom(8f).row()
        panel.add(ui.label(str("stuck.body"))).padBottom(16f).row()
        val row = Table()
        row.defaults().height(Panels.BUTTON_H).pad(4f).expandX().fillX()
        row.add(
            probe(
                "overlay-stuck-undo",
                ui.button("${str("helper.undo")} (${Helpers.UNDO_COST})", "primary") {
                    closeOverlay()
                    undo()
                },
            ),
        )
        row.add(probe("overlay-stuck-restart", ui.button(str("helper.restart"), "good") { restart() }))
        panel.add(row).fillX().row()
        panel.add(
            probe(
                "overlay-stuck-close",
                ui.button(str("common.close")) {
                    closeOverlay()
                },
            ),
        ).width(Panels.WIDE_W).height(Panels.BUTTON_H).padTop(8f)
        showOverlay("stuck", panel)
    }

    private fun openPause() {
        if (hasOverlay) return
        val panel = ui.panel()
        panel.add(ui.label(str("pause.title"), "title")).padBottom(16f).row()

        fun entry(
            pid: String,
            key: String,
            style: String = "default",
            action: () -> Unit,
        ) {
            panel.add(
                probe("overlay-pause-$pid", ui.button(str(key), style, action)),
            ).width(Panels.WIDE_W + 40f).height(Panels.BUTTON_H).padBottom(8f).row()
        }
        entry("resume", "pause.resume", "primary") { closeOverlay() }
        entry("restart", "pause.restart") { restart() }
        entry("help", "pause.help") { showHelp() }
        entry("settings", "pause.settings") { showOverlay("settings", panels.settings { openPauseAgain() }) }
        entry("quit", "pause.quit") { quit() }
        showOverlay("pause", panel)
    }

    private fun openPauseAgain() {
        closeOverlay()
        openPause()
    }

    private fun showHelp() {
        val mechanics = session?.level?.params?.mechanics.orEmpty().toList()
        showOverlay("card", panels.card(mechanics, all = false) { openPauseAgain() })
    }

    override fun onBack() = openPause()

    private fun quit() {
        game.updateSave { it.copy(inProgress = null) }
        game.go(
            when (id) {
                is LevelId.Campaign -> LevelSelectScreen(game, id.world)
                is LevelId.Endless -> EndlessSelectScreen(game, EndlessPages.pageOf(id.number))
                is LevelId.Daily -> WorldMapScreen(game)
            },
        )
    }

    private fun complete() {
        val s = session ?: return
        val result = s.result()
        val outcome = progress.recordSolve(id, result)
        if (tutorial) {
            tutorial = false
            progress.setFlag(FLAG_TUTORIAL)
            board.pointer = null
            banner.setText(str("tutorial.finish"))
        }
        game.save()
        game.audio.play(Sfx.COMPLETE)
        game.audio.haptic(Haptic.SUCCESS)
        showOverlay("complete", completePanel(s, outcome))
        if (!game.settings.reduceMotion) confetti.burst(stage.viewport.worldWidth, stage.viewport.worldHeight)
    }

    private fun completePanel(
        s: GameSession,
        outcome: SolveOutcome,
    ): Table {
        val panel = ui.panel()
        panel.add(ui.label(str("complete.title"), "huge")).row()
        val stars = ui.stars(outcome.stars, 48f)
        probe("overlay-stars", stars)
        panel.add(stars).size(stars.width, stars.height).pad(8f).row()
        if (!game.settings.reduceMotion) {
            stars.setOrigin(Align.center)
            stars.setScale(0.2f)
            stars.addAction(Actions.scaleTo(1f, 1f, 0.5f, com.badlogic.gdx.math.Interpolation.swingOut))
        }
        panel.add(ui.label(str("complete.moves", s.moves, s.level.par), "soft")).row()
        panel.add(ui.label(str("complete.time", formatTime(s.elapsedSeconds), formatTime(s.level.parTimeSeconds.toDouble())), "soft")).row()
        val points = ui.label(str("complete.points", outcome.pointsEarned), "title")
        probe("overlay-points", points)
        panel.add(points).padTop(8f).row()
        if (outcome.dailyBonus) panel.add(ui.label(str("complete.daily"))).row()
        if (outcome.newBest && s.moves > 0) panel.add(ui.label(str("complete.newbest"), "default")).row()
        val row = Table()
        row.defaults().height(Panels.BUTTON_H).pad(4f).expandX().fillX()
        val next = nextLevel()?.takeIf { progress.isUnlocked(it) }
        if (next != null) row.add(probe("overlay-next", ui.button(str("complete.next"), "primary") { game.go(GameScreen(game, next)) }))
        row.add(probe("overlay-replay", ui.button(str("complete.replay")) { game.go(GameScreen(game, id)) }))
        row.add(
            probe(
                "overlay-levels",
                ui.button(str(if (id is LevelId.Daily) "complete.done" else "complete.levels")) { quit() },
            ),
        )
        panel.add(row).fillX().padTop(16f)
        return panel
    }

    override fun onOverlayBack() {
        // The completion panel has no "back"; everything else closes like Resume.
        if (session?.isSolved == true) quit() else closeOverlay()
    }

    /** App went to the background: remember the level so it can be resumed (SPEC §15). */
    override fun pause() {
        val s = session ?: return
        if (s.isSolved) return
        game.progress.update {
            it.copy(inProgress = InProgress(id.key, s.events.map(EventCodec::encode), s.elapsedSeconds))
        }
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Simple confetti burst shown when a level is solved (skipped with Reduce motion). */
    private inner class Confetti : Actor() {
        private val particles = ArrayList<FloatArray>()
        private val painter =
            ShapeActor(game) { sr, _, _, _, _ ->
                for (p in particles) {
                    sr.color = Theme.itemColor(p[5].toInt(), false)
                    sr.rect(p[0], p[1], 8f, 12f)
                }
            }

        fun burst(
            w: Float,
            h: Float,
        ) {
            repeat(CONFETTI) {
                particles +=
                    floatArrayOf(
                        w / 2,
                        h * 0.55f,
                        MathUtils.random(-260f, 260f),
                        MathUtils.random(200f, 620f),
                        MathUtils.random(1.6f, 2.6f),
                        MathUtils.random(0, 13).toFloat(),
                    )
            }
            toFront()
        }

        override fun act(delta: Float) {
            for (p in particles) {
                p[0] += p[2] * delta
                p[1] += p[3] * delta
                p[3] -= GRAVITY * delta
                p[4] -= delta
            }
            particles.removeAll { it[4] <= 0f }
        }

        override fun draw(
            batch: com.badlogic.gdx.graphics.g2d.Batch,
            parentAlpha: Float,
        ) {
            if (particles.isNotEmpty()) painter.draw(batch, parentAlpha)
        }
    }

    companion object {
        const val FLAG_TUTORIAL = "tutorial-done"
        const val FLAG_TIPS = "tips-helpers"
        const val ENDLESS_TRACK = 6
        private const val DAILY_TRACK = 1
        private const val TOAST_TIME = 2.2f
        private const val CONFETTI = 90
        private const val GRAVITY = 700f
    }
}
