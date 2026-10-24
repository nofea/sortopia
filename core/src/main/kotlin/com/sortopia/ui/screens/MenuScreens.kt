package com.sortopia.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.sortopia.SortopiaGame
import com.sortopia.game.EndlessPages
import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.LoadOutcome
import com.sortopia.theme.ThemeCatalog
import com.sortopia.ui.Panels
import com.sortopia.ui.Sfx
import com.sortopia.ui.ShapeActor
import com.sortopia.ui.Theme
import com.sortopia.ui.WorldTheme

class WorldMapScreen(
    game: SortopiaGame,
) : BaseScreen(game) {
    override val probeName = "map"
    private val progress get() = game.progress

    init {
        game.audio.playMusic(ThemeCatalog.MENU_TRACK)
        val header = Table()
        header.add(ui.label(str("map.title"), "title")).expandX().left()
        header.add(ui.coin(24f)).padRight(6f)
        header.add(probe("wallet", ui.label(progress.wallet.points.toString())))
        val gear =
            ui.button("") { game.go(SettingsScreen(game)) }.apply {
                add(
                    ShapeActor(game) { sr, x, y, w, h ->
                        sr.color = Theme.ink
                        com.sortopia.ui.render.Shapes.star(sr, x + w / 2, y + h / 2, 15f, 11f, 8)
                        sr.color = Theme.buttonFace
                        sr.circle(x + w / 2, y + h / 2, 5f, 12)
                    },
                ).size(30f)
            }
        val info = ui.button("i") { game.go(AboutScreen(game)) }
        header.add(probe("about", info)).size(56f).padLeft(12f)
        header.add(probe("settings", gear)).size(56f).padLeft(8f)
        root.top().pad(12f)
        root.add(header).expandX().fillX().padBottom(8f).row()

        val list = Table()
        list.defaults().expandX().fillX().padBottom(10f)
        list.add(dailyCard()).row()
        for (w in 1..LevelId.WORLDS) list.add(worldCard(w)).row()
        list.add(endlessCard()).row()
        val scroll = ScrollPane(list, assets.skin)
        scroll.setScrollingDisabled(true, false)
        scroll.setFadeScrollBars(true)
        root.add(scroll).expand().fill()
        showNotices()
    }

    private fun card(
        pid: String,
        title: String,
        subtitle: String,
        locked: Boolean,
        accent: com.badlogic.gdx.graphics.Color,
        emblem: WorldTheme? = null,
        onOpen: () -> Unit,
    ): Table {
        val t = ui.panel(if (locked) Theme.locked else Theme.paper)
        val stripe =
            ShapeActor(game) { sr, x, y, w, h ->
                sr.color = accent
                sr.rect(x, y, w, h)
            }
        t.add(stripe).width(10f).fillY().padRight(14f)
        val text = Table()
        text.add(ui.label(title, "title", align = Align.left)).expandX().fillX().row()
        text.add(ui.label(subtitle, "soft", wrap = true, align = Align.left)).expandX().fillX()
        t.add(text).expandX().fillX()
        if (emblem != null) {
            val icon = ui.emblem(emblem, CARD_EMBLEM)
            if (locked) icon.color.a = LOCKED_ALPHA
            t.add(icon).size(CARD_EMBLEM).padLeft(8f)
        }
        t.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
        t.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    if (hasOverlay) return
                    game.audio.play(Sfx.CLICK)
                    if (!locked) onOpen()
                }
            },
        )
        return probe(pid, t)
    }

    private fun worldCard(w: Int): Table {
        val unlocked = progress.isWorldUnlocked(w)
        val mechanic = Tuning.worldMechanic[w]
        val mechanicName =
            when {
                w == LevelId.WORLDS -> str("mechanic.ALL")
                mechanic == null -> str("mechanic.none")
                else -> str("mechanic.${mechanic.name}")
            }
        val subtitle =
            when {
                unlocked -> mechanicName + "  |  " + str("map.stars", progress.worldStars(w), LevelId.LEVELS_PER_WORLD * 3)
                !progress.worldFinished(w - 1) -> str("map.locked.finish", str(Theme.world(w - 1).nameKey))
                else -> str("map.locked.stars", progress.starsMissingFor(w))
            }
        val theme = Theme.world(w)
        return card("world-$w", "$w. ${str(theme.nameKey)}", subtitle, !unlocked, theme.accent, theme) {
            game.go(LevelSelectScreen(game, w))
        }
    }

    private fun endlessCard(): Table {
        val unlocked = progress.isEndlessUnlocked()
        val subtitle =
            if (unlocked) str("map.endless.info", progress.nextEndless(), progress.endlessTotal()) else str("map.endless.locked")
        return card("endless", str("endless"), subtitle, !unlocked, Theme.endless.accent) {
            game.go(EndlessSelectScreen(game))
        }
    }

    private fun dailyCard(): Table {
        val unlocked = progress.isDailyUnlocked()
        val today = game.platform.today()
        val subtitle =
            when {
                !unlocked -> str("map.daily.locked")
                progress.isDailyClaimed(today) -> str("map.daily.done") + "\n" + str("map.daily.streak", progress.dailyStreak(today))
                else -> str("map.daily.new") + "\n" + str("map.daily.streak", progress.dailyStreak(today))
            }
        return card("daily", str("daily"), subtitle, !unlocked, Theme.dailyAccent) {
            game.go(GameScreen(game, progress.dailyFor(today)))
        }
    }

    /** Save-reset notice first (SPEC §16), then the offer to resume an unfinished level (SPEC §15). */
    private fun showNotices() {
        val outcome = game.loadOutcome
        if (outcome == LoadOutcome.RESTORED_BACKUP || outcome == LoadOutcome.RESET) {
            game.loadOutcome = LoadOutcome.OK
            val panel = ui.panel()
            panel.add(ui.label(str("notice.title"), "title")).padBottom(10f).row()
            val key = if (outcome == LoadOutcome.RESET) "notice.reset" else "notice.restored"
            panel.add(ui.label(str(key), wrap = true)).fillX().padBottom(16f).row()
            panel
                .add(
                    probe(
                        "overlay-notice-ok",
                        ui.button(str("notice.ok"), "primary") {
                            closeOverlay()
                            offerResume()
                        },
                    ),
                ).width(Panels.WIDE_W)
                .height(Panels.BUTTON_H)
            showOverlay("notice", panel)
            return
        }
        offerResume()
    }

    private fun offerResume() {
        val ip = progress.data.inProgress ?: return
        val id = LevelId.parse(ip.levelKey) ?: return
        val name =
            when (id) {
                is LevelId.Campaign -> id.label
                is LevelId.Endless -> "${str("endless")} ${id.number}"
                is LevelId.Daily -> str("daily")
            }
        val panel = ui.panel()
        panel.add(ui.label(str("map.resume.title"), "title")).padBottom(10f).row()
        panel.add(ui.label(str("map.resume.body", name), wrap = true)).fillX().padBottom(16f).row()
        val row = Table()
        row.defaults().height(Panels.BUTTON_H).pad(4f).expandX().fillX()
        row.add(probe("overlay-resume-yes", ui.button(str("map.resume.yes"), "primary") { game.go(GameScreen(game, id, ip)) }))
        row.add(
            probe(
                "overlay-resume-no",
                ui.button(str("map.resume.no")) {
                    game.updateSave { it.copy(inProgress = null) }
                    closeOverlay()
                },
            ),
        )
        panel.add(row).fillX()
        showOverlay("resume", panel)
    }

    override fun onBack() = Gdx.app.exit()

    private companion object {
        const val CARD_EMBLEM = 48f
        const val LOCKED_ALPHA = 0.45f
    }
}

/** A screen with a grid of level cells showing lock state, best stars and best score (SPEC §12). */
abstract class LevelGridScreen(
    game: SortopiaGame,
    theme: WorldTheme,
) : BaseScreen(game, theme) {
    protected val progress get() = game.progress

    protected fun header(title: String): Table {
        val header = Table()
        header.add(probe("back", ui.button("<") { onBack() }.apply { style = assets.accentStyle(theme.accent) })).size(56f)
        val name = Table()
        name.add(ui.emblem(theme, EMBLEM)).size(EMBLEM).padRight(6f)
        name.add(ui.label(title, "title"))
        header.add(name).expandX()
        header.add(ui.coin(24f)).padRight(6f)
        header.add(ui.label(progress.wallet.points.toString()))
        return header
    }

    protected fun levelCell(
        id: LevelId,
        text: String,
    ): Table {
        val unlocked = progress.isUnlocked(id)
        val button = TextButton(text, assets.accentStyle(theme.accent))
        button.isDisabled = !unlocked
        button.row()
        if (unlocked) {
            val stars = ui.stars(progress.stars(id), 18f)
            button.add(stars).size(stars.width, stars.height).row()
        }
        val best = progress.bestScore(id)
        if (best != null) {
            button.add(ui.label(str("select.best", best), "small"))
            game.probe.state("best-${id.key}", best.toString())
        }
        button.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    if (button.isDisabled) return
                    game.audio.play(Sfx.CLICK)
                    game.go(GameScreen(game, id))
                }
            },
        )
        val cell = Table()
        cell.add(button).expand().fill()
        if (!game.settings.reduceMotion && unlocked && !progress.isSolved(id)) {
            button.addAction(Actions.forever(Actions.sequence(Actions.alpha(0.75f, 0.8f), Actions.alpha(1f, 0.8f))))
        }
        probe("level-$text", button)
        return cell
    }

    protected fun grid(): Table = Table().apply { defaults().size(CELL_W, CELL_H).pad(CELL_PAD) }

    companion object {
        const val COLUMNS = 4
        private const val EMBLEM = 34f
        private const val CELL_W = 96f
        private const val CELL_H = 104f
        private const val CELL_PAD = 5f
    }
}

class LevelSelectScreen(
    game: SortopiaGame,
    private val world: Int,
) : LevelGridScreen(game, Theme.world(world)) {
    override val probeName = "levels"

    init {
        game.audio.playMusic(theme.def.track)
        root.top().pad(12f)
        root.add(header(str("select.title", str(theme.nameKey)))).expandX().fillX().padBottom(8f).row()
        root.add(ui.label(str("map.stars", progress.worldStars(world), LevelId.LEVELS_PER_WORLD * 3), "soft")).padBottom(12f).row()
        val grid = grid()
        for (l in 1..LevelId.LEVELS_PER_WORLD) {
            grid.add(levelCell(LevelId.Campaign(world, l), l.toString()))
            if (l % COLUMNS == 0) grid.row()
        }
        root.add(grid).expand().top()
        game.prefetch(firstUnsolved())
    }

    private fun firstUnsolved(): LevelId.Campaign? =
        (1..LevelId.LEVELS_PER_WORLD).map { LevelId.Campaign(world, it) }.firstOrNull { !progress.isSolved(it) && progress.isUnlocked(it) }

    override fun onBack() = game.go(WorldMapScreen(game))
}

/**
 * Endless level picker (SPEC §9): continue with the next unsolved level, or replay any level
 * already reached, [EndlessPages.SIZE] per page.
 */
class EndlessSelectScreen(
    game: SortopiaGame,
    startPage: Int = EndlessPages.pageOf(game.progress.nextEndless()),
) : LevelGridScreen(game, Theme.endless) {
    override val probeName = "endless"
    private val grid = grid()
    private val pageLabel = ui.label("", "soft")
    private val prev = probe("page-prev", ui.button("<") { showPage(this.page - 1) })
    private val next = probe("page-next", ui.button(">") { showPage(this.page + 1) })
    private var page = startPage.coerceIn(0, EndlessPages.count(progress.nextEndless()) - 1)

    init {
        game.audio.playMusic(ThemeCatalog.MENU_TRACK)
        val nextLevel = progress.nextEndless()
        root.top().pad(12f)
        root.add(header(str("endless"))).expandX().fillX().padBottom(8f).row()
        val play =
            ui.button(str("endless.continue", nextLevel), "primary") {
                game.go(GameScreen(game, LevelId.Endless(nextLevel)))
            }
        root.add(probe("endless-continue", play)).width(Panels.WIDE_W + 40f).height(Panels.BUTTON_H).row()
        root.add(ui.label(str("endless.total", progress.endlessTotal()), "soft")).padTop(6f).padBottom(8f).row()
        root.add(grid).expand().top().row()
        val pager = Table()
        pager.add(prev).size(56f)
        pager.add(pageLabel).expandX()
        pager.add(next).size(56f)
        root.add(pager).expandX().fillX().padBottom(8f)
        showPage(this.page)
        game.prefetch(LevelId.Endless(nextLevel))
    }

    private fun showPage(target: Int) {
        val count = EndlessPages.count(progress.nextEndless())
        if (target !in 0 until count) return
        page = target
        unprobe("level-")
        grid.clearChildren()
        for ((i, n) in EndlessPages.levels(page, progress.nextEndless()).withIndex()) {
            grid.add(levelCell(LevelId.Endless(n), n.toString()))
            if ((i + 1) % COLUMNS == 0) grid.row()
        }
        prev.isDisabled = page == 0
        next.isDisabled = page == count - 1
        pageLabel.setText(str("endless.page", page + 1, count))
        game.probe.state("endless-page", "${page + 1}/$count")
    }

    override fun onBack() = game.go(WorldMapScreen(game))
}

class SettingsScreen(
    game: SortopiaGame,
) : BaseScreen(game) {
    override val probeName = "settings"

    init {
        val panels = Panels(game, ui) { pid, b -> probe(pid, b) }
        root.add(panels.settings { onBack() }).width(VIRTUAL_WIDTH - 48f)
    }

    override fun onBack() = game.go(WorldMapScreen(game))
}

/** Who made the game and which version is installed (SPEC §12). */
class AboutScreen(
    game: SortopiaGame,
) : BaseScreen(game) {
    override val probeName = "about"

    init {
        val version = game.platform.appVersion
        game.probe.state("about-version", version)
        val panel = ui.panel()
        panel.add(ui.label(str("about.title"), "title")).padBottom(16f).row()
        panel.add(ui.label(str("map.title"), "title")).padBottom(8f).row()
        panel.add(ui.label(str("about.creator"), wrap = true)).fillX().padBottom(8f).row()
        panel.add(ui.label(str("about.version", version), "soft", wrap = true)).fillX().padBottom(8f).row()
        panel.add(ui.label(str("settings.credits"), "small", wrap = true)).fillX().padTop(12f).row()
        panel.add(
            probe(
                "about-back",
                ui.button(str("common.back"), "primary") {
                    onBack()
                },
            ),
        ).width(Panels.WIDE_W).height(Panels.BUTTON_H).padTop(16f)
        root.add(panel).width(VIRTUAL_WIDTH - 48f)
    }

    override fun onBack() = game.go(WorldMapScreen(game))
}
