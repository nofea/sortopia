package com.sortopia.ui

import com.badlogic.gdx.graphics.Color

/** Per-world look (SPEC §6, §13). */
data class WorldTheme(
    val nameKey: String,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color,
)

object Theme {
    val ink: Color = Color.valueOf("3B3355")
    val inkSoft: Color = Color.valueOf("6E668A")
    val paper: Color = Color.valueOf("FFFDF8")
    val glass: Color = Color(1f, 1f, 1f, 0.35f)
    val glassEdge: Color = Color(1f, 1f, 1f, 0.6f)
    val shadow: Color = Color(0.2f, 0.15f, 0.3f, 0.18f)
    val star: Color = Color.valueOf("FFC531")
    val starEmpty: Color = Color(0.2f, 0.15f, 0.3f, 0.18f)
    val buttonFace: Color = Color.valueOf("FFFFFF")
    val primary: Color = Color.valueOf("FF8A5B")
    val good: Color = Color.valueOf("3DBE8B")
    val locked: Color = Color(1f, 1f, 1f, 0.42f)
    val highlight: Color = Color.valueOf("FFE066")

    val worlds: List<WorldTheme> =
        listOf(
            WorldTheme("world1", Color.valueOf("FFF1D6"), Color.valueOf("FFD9B0"), Color.valueOf("F28C38")),
            WorldTheme("world2", Color.valueOf("FFE3F1"), Color.valueOf("F8C8E6"), Color.valueOf("E8559A")),
            WorldTheme("world3", Color.valueOf("E3F0FF"), Color.valueOf("C6DBFF"), Color.valueOf("4D7DF2")),
            WorldTheme("world4", Color.valueOf("FFF6D9"), Color.valueOf("F1DFA8"), Color.valueOf("C9962E")),
            WorldTheme("world5", Color.valueOf("FBEBDD"), Color.valueOf("EFD0B4"), Color.valueOf("B4703C")),
            WorldTheme("world6", Color.valueOf("DDF6F7"), Color.valueOf("AEE3EA"), Color.valueOf("1F9DAE")),
            WorldTheme("world7", Color.valueOf("F2EAFF"), Color.valueOf("DCCEFA"), Color.valueOf("8A63D2")),
            WorldTheme("world8", Color.valueOf("E4E6F5"), Color.valueOf("C2C6E6"), Color.valueOf("5257A8")),
        )

    val endless: WorldTheme = WorldTheme("endless", Color.valueOf("E8F7EA"), Color.valueOf("BFE8C6"), Color.valueOf("3DAA5C"))
    val daily: WorldTheme = WorldTheme("daily", Color.valueOf("FFF4E0"), Color.valueOf("FFDCA8"), Color.valueOf("F2A22C"))

    fun world(world: Int): WorldTheme = worlds[(world - 1).coerceIn(0, worlds.size - 1)]

    /** 14 item colors; shapes differ too, so color is never the only cue (SPEC §14). */
    private val standardPalette: List<Color> =
        listOf(
            "EF5350",
            "FFA726",
            "FFD54F",
            "66BB6A",
            "26C6DA",
            "42A5F5",
            "5C6BC0",
            "AB47BC",
            "F06292",
            "8D6E63",
            "90A4AE",
            "2E4057",
            "C0CA33",
            "00897B",
        ).map(Color::valueOf)

    /** High-distinction palette (Okabe-Ito plus Tol), for the colorblind setting. */
    private val colorblindPalette: List<Color> =
        listOf(
            "E69F00",
            "56B4E9",
            "009E73",
            "F0E442",
            "0072B2",
            "D55E00",
            "CC79A7",
            "222222",
            "999999",
            "AA4499",
            "332288",
            "44AA99",
            "999933",
            "882255",
        ).map(Color::valueOf)

    fun itemColor(
        type: Int,
        colorblind: Boolean,
    ): Color {
        val palette = if (colorblind) colorblindPalette else standardPalette
        return palette[type.mod(palette.size)]
    }
}
