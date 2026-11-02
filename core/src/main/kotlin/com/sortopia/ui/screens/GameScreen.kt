package com.sortopia.ui.screens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.sortopia.SortopiaGame
import com.sortopia.game.DailyClock
import com.sortopia.game.EndlessPages
import com.sortopia.game.GameSession
import com.sortopia.game.HelperResult
import com.sortopia.game.Helpers
import com.sortopia.game.SolveOutcome
import com.sortopia.gen.GeneratedLevel
import com.sortopia.gen.Tuning
import com.sortopia.model.Board
import com.sortopia.model.LevelId
import com.sortopia.model.Move
import com.sortopia.platform.Haptic
import com.sortopia.rules.PourResult
import com.sortopia.rules.Rules
import com.sortopia.save.EventCodec
import com.sortopia.save.InProgress
import com.sortopia.solver.Solver
import com.sortopia.theme.ThemeCatalog
import com.sortopia.ui.BoardView
import com.sortopia.ui.Panels
import com.sortopia.ui.Sfx
import com.sortopia.ui.Theme
import com.sortopia.ui.WorldTheme
import java.util.concurrent.atomic.AtomicReference

/** Gameplay (SPEC §2, §4, §11, §12). [resume] replays a level left mid-play. */
class GameScreen(
    game: SortopiaGame,
    private val id: LevelId,
    private val resume: InProgress? = null,
) : BaseScreen(game) {
    override val theme: WorldTheme = Theme.forLevel(id)
    private val look = ThemeCatalog.lookFor(id)
    override val probeName = "game"

    private val progress get() = game.progress
    private var session: GameSession? = null
    private val board = BoardView(game, theme, look, ::onTubeTapped)
    private val panels = Panels(game, ui, theme, look) { pid, b -> probe("overlay-$pid", b) }

    private val titleLabel = ui.label(levelTitle(), "default")
    private val movesLabel = ui.label("", "small")
    private val timerLabel = ui.label("", "small")
    private val walletLabel = ui.label("", "default")
    private val wallet = Table()
    private lateinit var pauseCell: Cell<Table>
    private lateinit var walletCell: Cell<Table>
    private val banner = ui.label("", "default", wrap = true)
    private val toast = ui.label("", "light", wrap = true)
    private val loading = ui.label(str("game.preparing"), "title")
    private val confetti = Confetti()

    private var tutorial = false
    private var tutorialSealedShown = false
    private var hintPending = false
    private var completeQueued = false
    private val solveNext = AtomicReference<Board?>()

    init {
        buildLayout()
        game.audio.playMusic(theme.def.track)
        game.level(id, onReady = ::start)
    }

    private fun levelTitle(): String =
        when (id) {
            is LevelId.Campaign -> str("hud.level", id.label)
            is LevelId.Endless -> str("hud.endless", id.number)
            is LevelId.Daily -> str("hud.daily", id.date.toString())
        }

    private fun buildLayout() {
        val top = Table()
        val pause = probe("pause", ui.button("II") { openPause() }.apply { style = assets.accentStyle(theme.accent) })
        // The side cells are kept the same width (see fitHudSides), so the middle column sits on
        // the screen's centre line however wide the wallet gets.
        pauseCell = top.add(Table().apply { add(pause).size(PAUSE_SIZE).left().expandX() })
        val mid = Table()
        val title = Table()
        title.add(ui.emblem(theme, EMBLEM)).size(EMBLEM).padRight(4f)
        title.add(titleLabel)
        mid.add(probe("hud-title", title)).row()
        mid.add(probe("hud-moves", movesLabel)).row()
        mid.add(probe("hud-timer", timerLabel))
        top.add(mid).expandX()
        wallet.add(ui.coin(24f)).padRight(6f)
        wallet.add(probe("wallet", walletLabel))
        walletCell = top.add(Table().apply { add(wallet).right().expandX() })
        fitHudSides()
        root.top().pad(8f)
        root.add(top).expandX().fillX().row()
        // The banner and toast float over the board instead of taking rows, so the tubes get the
        // height (SPEC §14). They never take taps meant for the tubes.
        val notes =
            Table().apply {
                touchable = Touchable.disabled
                top()
                add(banner).expandX().fillX().row()
                add().expand().row()
                add(toast).expandX().fillX()
            }
        val stack = com.badlogic.gdx.scenes.scene2d.ui.Stack()
        stack.add(board)
        stack.add(Table().apply { add(loading) })
        stack.add(notes)
        root.add(stack).expand().fill().padTop(4f).row()
        val helpers = Table()
        helpers.defaults().expandX().fillX().height(HELPER_H).pad(3f)
        helpers.add(helperButton("undo", str("helper.undo"), Helpers.UNDO_COST) { undo() })
        helpers.add(helperButton("hint", str("helper.hint"), Helpers.HINT_COST) { hint() })
        helpers.add(helperButton("extra", str("helper.extra"), Helpers.EXTRA_TUBE_COST) { extraTube() })
        helpers.add(helperButton("restart", str("helper.restart"), 0) { restart() })
        root.add(helpers).expandX().fillX().padBottom(4f)
        stage.addActor(confetti)
        toast.color.a = 0f
        toast.style = Label.LabelStyle(toast.style).apply { background = assets.round(TOAST_BACK) }
        banner.style = Label.LabelStyle(banner.style).apply { background = assets.round(BANNER_BACK) }
        banner.isVisible = false
    }

    private fun showBanner(text: String) {
        banner.setText(text)
        banner.isVisible = text.isNotEmpty()
    }

    private fun helperButton(
        pid: String,
        text: String,
        cost: Int,
        onClick: () -> Unit,
    ): TextButton {
        val b = ui.button(text, onClick = onClick)
        b.style = assets.accentStyle(theme.accent)
        b.row()
        b.add(ui.label(if (cost == 0) str("helper.free") else str("helper.cost", cost), "small"))
        return probe("helper-$pid", b)
    }

    private fun start(level: GeneratedLevel) {
        // The player left before the level was generated.
        if (!disposed) start(level, replaySaved = true)
    }

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
        if (level.params.mechanics.isEmpty() && tutorial) showBanner(str("tutorial.step1"))
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
        val points = progress.wallet.points.toString()
        if (!walletLabel.textEquals(points)) {
            walletLabel.setText(points)
            fitHudSides()
        }
        if (s == null) return
        movesLabel.setText(str("hud.moves", s.moves) + "   " + str("hud.par", s.level.par))
        timerLabel.setText(formatTime(s.elapsedSeconds))
    }

    /** Gives the pause and wallet cells the same width, so the middle of the HUD is centred on screen. */
    private fun fitHudSides() {
        wallet.invalidate()
        val side = maxOf(PAUSE_SIZE, WALLET_MIN_W, wallet.prefWidth)
        pauseCell.width(side)
        walletCell.width(side)
        pauseCell.table?.invalidate()
    }

    private val tmp = Vector2()

    private fun reportTubes() {
        val p = game.probe
        if (!p.active) return
        val s = session ?: return
        for (i in s.board.tubes.indices) {
            board.hitRects.getOrNull(i)?.let { reportRect("tube-$i", it) }
            board.tubeRect(i)?.let { reportRect("glass-$i", it) }
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

    /**
     * Test-only: the solver's solution from the current board, published for the UI tests. Only the
     * latest board is solved: a big board can take seconds per solve on a slow device, so one solve
     * per move would pile up behind the player.
     */
    private fun publishSolution() {
        if (!game.probe.active) return
        val s = session ?: return
        // A solve is already queued; it picks up this board when it starts.
        if (solveNext.getAndSet(s.board) != null) return
        game.workExecutor.execute {
            val snapshot = solveNext.getAndSet(null) ?: return@execute
            val r = Solver().solve(snapshot)
            val text = (r as? Solver.Result.Solved)?.moves?.joinToString(",") { "${it.from}>${it.to}" } ?: "none"
            game.onRenderThread { if (!disposed && session?.board == snapshot) game.probe.state("solution", text) }
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
                        showBanner(str("tutorial.sealed"))
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
                if (s.moves == 0) showBanner(str("tutorial.step1"))
            }
            else -> {
                board.pointer = if (sel == move.from) move.to else null
                if (s.moves == 0) showBanner(str("tutorial.step2"))
            }
        }
        if (s.moves == 1 && !tutorialSealedShown) showBanner(str("tutorial.step3"))
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
            game.onRenderThread {
                if (disposed) return@onRenderThread
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
        entry("settings", "pause.settings") { showOverlay("settings", panels.settings(onClose = { openPauseAgain() })) }
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
            showBanner(str("tutorial.finish"))
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
        if (id is LevelId.Daily) {
            val wait = DailyClock.untilNext(game.platform.now())
            panel.add(probe("overlay-daily-next", ui.label(str("complete.daily.next", waitText(wait)), "soft"))).padTop(4f).row()
        }
        val row = Table()
        row.defaults().height(Panels.BUTTON_H).pad(4f).expandX().fillX()
        val next = nextLevel()?.takeIf { progress.isUnlocked(it) }
        if (next != null) row.add(probe("overlay-next", ui.button(str("complete.next"), "primary") { game.go(GameScreen(game, next)) }))
        // A solved Daily stays locked until the next date (SPEC §10), so it has no Replay.
        if (id !is LevelId.Daily) row.add(probe("overlay-replay", ui.button(str("complete.replay")) { game.go(GameScreen(game, id)) }))
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

    /**
     * Celebration when a level is solved (SPEC §13): the level's own items and the world's
     * particles burst out of the board. Skipped with Reduce motion.
     */
    private inner class Confetti : Actor() {
        private val particles = ArrayList<FloatArray>()

        fun burst(
            w: Float,
            h: Float,
        ) {
            val types = session?.level?.params?.types ?: look.items.size
            repeat(CONFETTI) {
                particles +=
                    floatArrayOf(
                        w / 2,
                        h * 0.55f,
                        MathUtils.random(-260f, 260f),
                        MathUtils.random(200f, 620f),
                        MathUtils.random(1.6f, 2.6f),
                        // Every third piece is a world particle (-1), the rest the level's items.
                        if (it % 3 == 0) -1f else MathUtils.random(0, types - 1).toFloat(),
                        MathUtils.random(0f, 360f),
                        MathUtils.random(-240f, 240f),
                        MathUtils.random(26f, 40f),
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
                p[6] += p[7] * delta
            }
            particles.removeAll { it[4] <= 0f }
        }

        override fun draw(
            batch: com.badlogic.gdx.graphics.g2d.Batch,
            parentAlpha: Float,
        ) {
            val art = assets.art
            val sprite = art.region(theme.def.particle.art)
            for (p in particles) {
                val size = p[8]
                val fade = p[4].coerceAtMost(0.5f) * 2f
                if (p[5] < 0f) {
                    if (sprite == null) continue
                    batch.setColor(1f, 1f, 1f, fade * parentAlpha)
                    batch.draw(sprite, p[0] - size / 2, p[1] - size / 2, size / 2, size / 2, size, size, 1f, 1f, p[6])
                } else {
                    batch.setColor(1f, 1f, 1f, parentAlpha)
                    val colorblind = game.settings.colorblindPalette
                    com.sortopia.ui.render.ItemArt
                        .draw(batch, art, look, p[5].toInt(), p[0], p[1], size, colorblind, alpha = fade)
                }
            }
            batch.setColor(1f, 1f, 1f, 1f)
        }
    }

    companion object {
        const val FLAG_TUTORIAL = "tutorial-done"
        const val FLAG_TIPS = "tips-helpers"
        private const val TOAST_TIME = 2.2f
        private const val HELPER_H = 64f
        private const val EMBLEM = 26f
        private const val PAUSE_SIZE = 56f
        private const val WALLET_MIN_W = 90f
        private val TOAST_BACK = Color(0.17f, 0.12f, 0.25f, 0.72f)
        private val BANNER_BACK = Color(1f, 0.99f, 0.97f, 0.9f)
        private const val CONFETTI = 90
        private const val GRAVITY = 700f
    }
}
