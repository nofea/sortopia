package com.sortopia.theme

import com.sortopia.gen.Rng
import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

class ThemeCatalogTest {
    @Test
    fun `there is one theme per world plus a neutral one, all with distinct ids`() {
        assertEquals(LevelId.WORLDS, ThemeCatalog.worlds.size)
        val ids = (ThemeCatalog.worlds + ThemeCatalog.neutral).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every world has enough items for its biggest board`() {
        for (w in 1..LevelId.WORLDS) {
            val needed = (1..LevelId.LEVELS_PER_WORLD).maxOf { Tuning.campaign(w, it, Rng(it.toLong())).types }
            val items = ThemeCatalog.world(w).items.size
            assertTrue(items >= needed, "world $w has $items items but needs $needed")
            assertTrue(items > needed, "world $w should keep a spare item for tuning (SPEC §6)")
        }
    }

    @Test
    fun `endless mixes cover the largest endless board`() {
        assertEquals(ENDLESS_MIXES, ThemeCatalog.endlessMixes.size)
        for (mix in ThemeCatalog.endlessMixes) assertEquals(Tuning.MAX_TYPES, mix.size)
    }

    @Test
    fun `mixed items all come from the world themes`() {
        val known = ThemeCatalog.worlds.flatMap { t -> t.items.map { it.art } }.toSet()
        for (mix in ThemeCatalog.endlessMixes) for (item in mix) assertTrue(item.art in known, "${item.art} is not a world item")
    }

    @Test
    fun `silhouettes never repeat within a set`() {
        for (set in allSets()) assertEquals(set.size, set.map { it.art }.toSet().size)
    }

    @Test
    fun `item colours within a set are clearly different, in both palettes`() {
        for (set in allSets()) {
            assertDistinct(set.map { it.color })
            assertDistinct(set.indices.map { ThemeCatalog.colorblindColor(it) })
        }
    }

    @Test
    fun `colours are 6-digit hex`() {
        val hex = Regex("[0-9A-F]{6}")
        for (set in allSets()) for (item in set) assertTrue(hex.matches(item.color), item.color)
    }

    @Test
    fun `campaign levels use their world's look`() {
        for (w in 1..LevelId.WORLDS) {
            val look = ThemeCatalog.lookFor(LevelId.Campaign(w, 7))
            assertEquals(ThemeCatalog.world(w), look.theme)
            assertEquals(ThemeCatalog.world(w).items, look.items)
        }
    }

    @Test
    fun `daily puzzles use the look of their difficulty tier`() {
        val look = ThemeCatalog.lookFor(LevelId.Daily(LocalDate.of(2026, 10, 23), 4))
        assertEquals(ThemeCatalog.world(4), look.theme)
    }

    @Test
    fun `endless uses the neutral theme and switches mix every 10 levels`() {
        val first = ThemeCatalog.lookFor(LevelId.Endless(1))
        assertEquals(ThemeCatalog.neutral, first.theme)
        assertEquals(first.items, ThemeCatalog.lookFor(LevelId.Endless(10)).items)
        val second = ThemeCatalog.lookFor(LevelId.Endless(11))
        assertNotEquals(first.items, second.items)
        assertEquals(first.items, ThemeCatalog.lookFor(LevelId.Endless(1 + 10 * ENDLESS_MIXES)).items)
    }

    @Test
    fun `item lookup wraps instead of failing on an unexpected type`() {
        val look = ThemeCatalog.lookFor(LevelId.Campaign(1, 1))
        assertEquals(look.items[0], look.item(look.items.size))
        assertEquals(look.items.last(), look.item(-1))
    }

    @Test
    fun `every world theme has its own track and the neutral theme uses the menu track`() {
        val tracks = ThemeCatalog.worlds.map { it.track }
        assertEquals(tracks.size, tracks.toSet().size)
        assertEquals(ThemeCatalog.MENU_TRACK, ThemeCatalog.neutral.track)
    }

    private fun allSets(): List<List<ItemDef>> = ThemeCatalog.worlds.map { it.items } + ThemeCatalog.endlessMixes

    private fun assertDistinct(colors: List<String>) {
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val d = deltaE(colors[i], colors[j])
                assertTrue(d >= MIN_DELTA_E, "${colors[i]} and ${colors[j]} are too close (ΔE ${"%.1f".format(d)})")
            }
        }
    }

    /** CIE76 colour difference between two sRGB hex colours. */
    private fun deltaE(
        a: String,
        b: String,
    ): Double {
        val p = lab(a)
        val q = lab(b)
        return sqrt(p.indices.sumOf { (p[it] - q[it]).pow(2) })
    }

    private fun lab(hex: String): DoubleArray {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        val (r, g, b) = (0..2).map { lin(hex.substring(it * 2, it * 2 + 2).toInt(16) / 255.0) }
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private companion object {
        const val ENDLESS_MIXES = 3

        /** The colorblind palette's own closest pair is about 18.6. */
        const val MIN_DELTA_E = 18.0
    }
}
