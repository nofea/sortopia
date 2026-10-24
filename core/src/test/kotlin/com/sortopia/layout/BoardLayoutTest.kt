package com.sortopia.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BoardLayoutTest {
    // The board area on the 480 × 860 virtual screen once the HUD and helper bar are placed.
    private val w = 464f
    private val h = 700f

    private fun layout(
        tubes: Int,
        capacity: Int,
    ) = BoardLayout.compute(w, h, List(tubes) { capacity })

    @ParameterizedTest
    @CsvSource(
        // tubes, capacity, minimum item size: every board is larger than before the redesign.
        "5, 4, 60",
        "7, 4, 54",
        "9, 4, 54",
        "9, 5, 46",
        "12, 5, 46",
        "13, 6, 40",
        "16, 6, 27",
        "17, 6, 27",
    )
    fun `items are at least the minimum size for each board shape`(
        tubes: Int,
        capacity: Int,
        min: Float,
    ) {
        val l = layout(tubes, capacity)
        assertTrue(l.itemSize >= min, "itemSize ${l.itemSize} < $min for $tubes×$capacity")
        assertEquals(tubes, l.tubes.size)
    }

    @Test
    fun `item size is capped on small boards`() {
        assertEquals(BoardLayout.MAX_ITEM, layout(3, 4).itemSize)
    }

    @Test
    fun `tubes fit inside the board and never overlap`() {
        for (n in 2..17) {
            for (cap in 3..6) {
                val l = layout(n, cap)
                for ((i, a) in l.tubes.withIndex()) {
                    assertTrue(a.x >= 0f && a.y >= 0f && a.right <= w + EPS && a.top <= h + EPS, "tube $i out of board ($n×$cap)")
                    for (b in l.tubes.drop(i + 1)) assertFalse(a.overlaps(b), "tubes overlap ($n×$cap)")
                }
            }
        }
    }

    @Test
    fun `mixed capacities give each tube its own height`() {
        val l = BoardLayout.compute(w, h, listOf(3, 4, 5, 6))
        val heights = l.tubes.map { it.h }
        assertTrue(heights.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `hit cells tile the whole board`() {
        for (n in listOf(4, 7, 9, 12, 17)) {
            val l = layout(n, 5)
            assertEquals(n, l.cells.size)
            var y = 1f
            while (y < h) {
                var x = 1f
                while (x < w) {
                    val hits = l.cells.count { it.contains(x, y) }
                    assertEquals(1, hits, "($x, $y) is in $hits cells on a $n-tube board")
                    x += 7f
                }
                y += 7f
            }
        }
    }

    @Test
    fun `every cell contains its own tube`() {
        val l = layout(11, 5)
        for ((i, t) in l.tubes.withIndex()) assertEquals(i, l.tubeAt(t.centerX, t.y + t.h / 2))
    }

    @Test
    fun `a tap in the gap between two tubes goes to the nearer one`() {
        val l = layout(5, 4)
        val a = l.tubes[1]
        val b = l.tubes[2]
        val gapMid = (a.right + b.x) / 2
        val y = a.y + a.h / 2
        assertEquals(1, l.tubeAt(gapMid - 1f, y))
        assertEquals(2, l.tubeAt(gapMid + 1f, y))
    }

    @Test
    fun `taps at the board edges go to the outermost tubes`() {
        val l = layout(5, 4)
        val y = l.tubes[0].y + 10f
        assertEquals(0, l.tubeAt(0.5f, y))
        assertEquals(4, l.tubeAt(w - 0.5f, y))
        assertEquals(0, l.tubeAt(0.5f, h - 0.5f))
        assertEquals(0, l.tubeAt(0.5f, 0.5f))
    }

    @Test
    fun `taps above a row's tubes stay in that row's cells`() {
        val l = layout(12, 4)
        // Two rows: the top row holds tubes 0..5. A tap just above the top row's lid goes to it.
        val t = l.tubes[3]
        assertEquals(3, l.tubeAt(t.centerX, t.top + l.itemSize * 0.5f))
    }

    @Test
    fun `an empty or zero-sized board has no tubes`() {
        assertTrue(BoardLayout.compute(w, h, emptyList()).tubes.isEmpty())
        assertTrue(BoardLayout.compute(0f, h, listOf(4, 4)).tubes.isEmpty())
        assertEquals(null, BoardLayout.compute(w, h, emptyList()).tubeAt(10f, 10f))
    }

    @Test
    fun `item centres sit inside their tube`() {
        val l = layout(6, 5)
        for (i in l.tubes.indices) {
            for (k in 0 until 5) {
                val (x, y) = l.itemCenter(i, k)
                assertTrue(l.tubes[i].contains(x, y))
            }
        }
    }

    private companion object {
        const val EPS = 0.01f
    }
}
