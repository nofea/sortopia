package com.sortopia.ui

import com.badlogic.gdx.graphics.Color
import com.sortopia.model.LevelId
import com.sortopia.theme.ThemeCatalog
import com.sortopia.theme.ThemeDef

/**
 * Per-world look (SPEC §6, §13): background gradient, UI accent, and the glass of its tubes.
 * [def] holds the rest (items, dressing art, particles, music). [glassFill] is an optional inner
 * fill (water, frost) and [glassStripes] optional candy stripes.
 */
data class WorldTheme(
    val nameKey: String,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color,
    val def: ThemeDef,
    val glass: Color = Color(1f, 1f, 1f, 0.35f),
    val glassEdge: Color = Color(1f, 1f, 1f, 0.6f),
    val glassFill: Color? = null,
    val glassStripes: Color? = null,
)

object Theme {
    val ink: Color = Color.valueOf("3B3355")
    val inkSoft: Color = Color.valueOf("6E668A")
    val paper: Color = Color.valueOf("FFFDF8")
    val shadow: Color = Color(0.2f, 0.15f, 0.3f, 0.18f)
    val star: Color = Color.valueOf("FFC531")
    val starEmpty: Color = Color(0.2f, 0.15f, 0.3f, 0.18f)
    val buttonFace: Color = Color.valueOf("FFFFFF")
    val primary: Color = Color.valueOf("FF8A5B")
    val good: Color = Color.valueOf("3DBE8B")
    val danger: Color = Color.valueOf("E5534B")
    val locked: Color = Color(1f, 1f, 1f, 0.42f)
    val highlight: Color = Color.valueOf("FFE066")

    private fun c(
        hex: String,
        alpha: Float = 1f,
    ): Color = Color.valueOf(hex).apply { a = alpha }

    val worlds: List<WorldTheme> =
        ThemeCatalog.worlds.let { defs ->
            listOf(
                WorldTheme("world1", c("FFF1D6"), c("FFD9B0"), c("F28C38"), defs[0], glassEdge = c("FFF3DD", 0.75f)),
                WorldTheme(
                    "world2",
                    c("FFE3F1"),
                    c("F8C8E6"),
                    c("E8559A"),
                    defs[1],
                    glass = c("FFE3F1", 0.4f),
                    glassEdge = c("FFFFFF", 0.75f),
                    glassStripes = c("F48FB1", 0.28f),
                ),
                WorldTheme(
                    "world3",
                    c("E3F0FF"),
                    c("C6DBFF"),
                    c("4D7DF2"),
                    defs[2],
                    glass = c("EAF4FF", 0.4f),
                    glassEdge = c("FFFFFF", 0.75f),
                ),
                WorldTheme(
                    "world4",
                    c("FFF6D9"),
                    c("F1DFA8"),
                    c("C9962E"),
                    defs[3],
                    glass = c("FFF8E6", 0.4f),
                    glassEdge = c("D4A54A", 0.6f),
                ),
                WorldTheme(
                    "world5",
                    c("FBEBDD"),
                    c("EFD0B4"),
                    c("B4703C"),
                    defs[4],
                    glass = c("FFFFFF", 0.42f),
                    glassEdge = c("FFFFFF", 0.75f),
                ),
                WorldTheme(
                    "world6",
                    c("DDF6F7"),
                    c("AEE3EA"),
                    c("1F9DAE"),
                    defs[5],
                    glassEdge = c("FFFFFF", 0.75f),
                    glassFill = c("4FC3F7", 0.26f),
                ),
                WorldTheme(
                    "world7",
                    c("F2EAFF"),
                    c("DCCEFA"),
                    c("8A63D2"),
                    defs[6],
                    glass = c("FFFFFF", 0.5f),
                    glassEdge = c("FFFFFF", 0.85f),
                    glassFill = c("FFFFFF", 0.18f),
                ),
                WorldTheme(
                    "world8",
                    c("E4E6F5"),
                    c("C2C6E6"),
                    c("5257A8"),
                    defs[7],
                    glass = c("E4E6F5", 0.38f),
                    glassEdge = c("5D6470", 0.6f),
                ),
            )
        }

    val endless: WorldTheme = WorldTheme("endless", c("E8F7EA"), c("BFE8C6"), c("3DAA5C"), ThemeCatalog.neutral)

    /** Menus use the neutral look; only the Daily card keeps its own accent. */
    val menu: WorldTheme = endless.copy(nameKey = "app.name")
    val dailyAccent: Color = c("F2A22C")

    fun world(world: Int): WorldTheme = worlds[(world - 1).coerceIn(0, worlds.size - 1)]

    /** The look a level is played in: its world, its daily tier, or neutral for Endless. */
    fun forLevel(id: LevelId): WorldTheme =
        when (id) {
            is LevelId.Campaign -> world(id.world)
            is LevelId.Daily -> world(id.tier)
            is LevelId.Endless -> endless
        }
}
