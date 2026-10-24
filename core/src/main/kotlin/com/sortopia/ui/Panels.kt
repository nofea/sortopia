package com.sortopia.ui

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.sortopia.SortopiaGame
import com.sortopia.gen.Mechanic
import com.sortopia.model.Board
import com.sortopia.model.Item
import com.sortopia.model.Tube
import com.sortopia.save.Settings
import com.sortopia.theme.Look

/** Panels shared between screens and overlays: settings and mechanic explanation cards. */
class Panels(
    private val game: SortopiaGame,
    private val ui: Widgets,
    private val theme: WorldTheme = Theme.endless,
    private val look: Look = Look(theme.def, theme.def.items),
    private val probe: (String, TextButton) -> Unit = { _, _ -> },
) {
    private fun str(
        key: String,
        vararg args: Any,
    ) = game.assets.str(key, *args)

    /** The settings panel; [onClearProgress] adds the Clear progress button (World Map only, SPEC §14). */
    fun settings(
        onClose: () -> Unit,
        onClearProgress: (() -> Unit)? = null,
    ): Table {
        val panel = ui.panel()
        panel.add(ui.label(str("settings.title"), "title")).padBottom(16f).row()

        fun toggle(
            id: String,
            key: String,
            get: (Settings) -> Boolean,
            set: (Settings, Boolean) -> Settings,
        ) {
            val row = Table()
            row.add(ui.label(str(key), align = com.badlogic.gdx.utils.Align.left)).expandX().fillX()
            lateinit var button: TextButton

            fun text() = str(if (get(game.settings)) "settings.on" else "settings.off")
            button =
                ui.button(text(), if (get(game.settings)) "good" else "default") {
                    game.updateSettings { set(it, !get(it)) }
                    button.setText(text())
                    button.style =
                        game.assets.skin.get(if (get(game.settings)) "good" else "default", TextButton.TextButtonStyle::class.java)
                }
            probe("setting-$id", button)
            row.add(button).width(TOGGLE_W).height(BUTTON_H)
            panel.add(row).fillX().padBottom(8f).row()
        }
        toggle("sfx", "settings.sfx", { it.sfx }) { s, v -> s.copy(sfx = v) }
        toggle("music", "settings.music", { it.music }) { s, v -> s.copy(music = v) }
        toggle("haptics", "settings.haptics", { it.haptics }) { s, v -> s.copy(haptics = v) }
        toggle("colorblind", "settings.colorblind", { it.colorblindPalette }) { s, v -> s.copy(colorblindPalette = v) }
        toggle("reducemotion", "settings.reducemotion", { it.reduceMotion }) { s, v -> s.copy(reduceMotion = v) }
        panel.add(ui.label(str("settings.credits"), "small", wrap = true)).fillX().padTop(12f).row()
        if (onClearProgress != null) {
            val clear = ui.button(str("settings.clear"), "danger", onClearProgress)
            probe("setting-clear", clear)
            panel.add(clear).width(WIDE_W).height(BUTTON_H).padTop(16f).row()
        }
        val close = ui.button(str("common.back"), "primary", onClose)
        probe("settings-back", close)
        panel.add(close).width(WIDE_W).height(BUTTON_H).padTop(16f)
        return panel
    }

    /** Explanation card for [mechanics] (empty = the basics), with a small demo board. */
    fun card(
        mechanics: List<Mechanic>,
        all: Boolean,
        onClose: () -> Unit,
    ): Table {
        val panel = ui.panel()
        val keys =
            when {
                all -> listOf("ALL")
                mechanics.isEmpty() -> listOf("basics")
                else -> mechanics.map { it.name }
            }
        val titleKey = if (keys.first() == "basics") "mechanic.none" else "mechanic.${keys.first()}"
        panel.add(ui.label(str(titleKey), "title")).padBottom(10f).row()
        val demo = BoardView(game, theme, look) { }
        demo.board = demoBoard(mechanics.firstOrNull().takeUnless { all })
        demo.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.disabled
        panel.add(demo).width(DEMO_W).height(DEMO_H).padBottom(10f).row()
        for (k in keys) panel.add(ui.label(str("card.$k"), wrap = true)).fillX().padBottom(8f).row()
        val ok = ui.button(str("card.ok"), "primary", onClose)
        probe("card-ok", ok)
        panel.add(ok).width(WIDE_W).height(BUTTON_H).padTop(8f)
        return panel
    }

    private fun demoBoard(m: Mechanic?): Board {
        var id = 0

        fun item(
            type: Int,
            hidden: Boolean = false,
            frozen: Int = 0,
            glued: Boolean = false,
        ) = Item(id++, type, hidden, frozen, glued)
        return when (m) {
            Mechanic.HIDDEN -> Board(listOf(Tube(3, listOf(item(1, true), item(2, true), item(0))), Tube(3, listOf(item(0)))))
            Mechanic.LOCKED ->
                Board(listOf(Tube(3, listOf(item(1), item(1), item(1))), Tube(3, listOf(item(2), item(0)), lockType = 1)))
            Mechanic.SINGLE_TYPE -> Board(listOf(Tube(3, listOf(item(3)), onlyType = 2), Tube(3, listOf(item(2), item(2)))))
            Mechanic.MIXED_CAPACITY -> Board(listOf(Tube(2, listOf(item(4))), Tube(4, listOf(item(5), item(4))), Tube(3)))
            Mechanic.GLUED -> Board(listOf(Tube(3, listOf(item(6, glued = true), item(1))), Tube(3, listOf(item(1)))))
            Mechanic.FROZEN -> Board(listOf(Tube(3, listOf(item(7), item(8, frozen = 3))), Tube(3, listOf(item(8)))))
            null -> Board(listOf(Tube(3, listOf(item(0), item(1))), Tube(3, listOf(item(1), item(0))), Tube(3)))
        }
    }

    companion object {
        const val BUTTON_H = 56f
        const val TOGGLE_W = 100f
        const val WIDE_W = 240f
        const val DEMO_W = 220f
        const val DEMO_H = 150f
    }
}
