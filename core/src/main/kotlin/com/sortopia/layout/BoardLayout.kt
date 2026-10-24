package com.sortopia.layout

import kotlin.math.ceil
import kotlin.math.hypot

/** An axis-aligned box in board coordinates (origin bottom-left, y up). */
data class Box(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val right: Float get() = x + w
    val top: Float get() = y + h
    val centerX: Float get() = x + w / 2
    val centerY: Float get() = y + h / 2

    /** Half-open, so neighbouring cells never both contain a point on their shared edge. */
    fun contains(
        px: Float,
        py: Float,
    ): Boolean = px >= x && px < right && py >= y && py < top

    fun overlaps(o: Box): Boolean = x < o.right && o.x < right && y < o.top && o.y < top
}

/**
 * Where the tubes go on the board, and which tube a tap means (SPEC §14).
 *
 * The row count is picked to make items as large as possible. Every point of the board belongs
 * to exactly one tube's [cells] entry: a tap in a gap or at an edge goes to the nearest tube in
 * its row, never to nothing.
 */
class BoardLayout private constructor(
    val tubes: List<Box>,
    val cells: List<Box>,
    val itemSize: Float,
    val pad: Float,
    val lift: Float,
) {
    /** The tube for a tap at ([x], [y]), or null on an empty board. */
    fun tubeAt(
        x: Float,
        y: Float,
    ): Int? {
        val i = cells.indexOfFirst { it.contains(x, y) }
        if (i >= 0) return i
        return tubes.indices.minByOrNull { hypot(tubes[it].centerX - x, tubes[it].centerY - y) }
    }

    /** Centre of slot [index] in tube [tube]; [lifted] raises it as when the tube is selected. */
    fun itemCenter(
        tube: Int,
        index: Int,
        lifted: Boolean = false,
    ): Pair<Float, Float> {
        val r = tubes[tube]
        val y = r.y + pad + itemSize * (index + HALF) + if (lifted) lift else 0f
        return r.centerX to y
    }

    companion object {
        /** Largest item, in virtual units: small boards stop growing here. */
        const val MAX_ITEM = 64f
        private const val MAX_ROWS = 4

        /** Item size as a share of the column width; the rest is glass and the gap between tubes. */
        private const val WIDTH_FACTOR = 0.68f

        /** Per row, in items, beyond the capacity: label below, glass padding, lid and lift above. */
        private const val ROW_EXTRA = 2.2f
        private const val LABEL_SPACE = 0.7f
        private const val PAD = 0.14f
        private const val LIFT = 0.95f
        private const val HALF = 0.5f

        private val NO_BOX = Box(0f, 0f, 0f, 0f)
        private val EMPTY = BoardLayout(emptyList(), emptyList(), MAX_ITEM, MAX_ITEM * PAD, MAX_ITEM * LIFT)

        fun compute(
            width: Float,
            height: Float,
            capacities: List<Int>,
        ): BoardLayout {
            val n = capacities.size
            if (n == 0 || width <= 0f || height <= 0f) return EMPTY
            var best: List<List<Int>> = emptyList()
            var bestSize = 0f
            for (rows in 1..minOf(MAX_ROWS, n)) {
                val perRow = ceil(n / rows.toFloat()).toInt()
                val split = capacities.indices.chunked(perRow)
                val size = itemSize(width, height, perRow, split.map { row -> row.maxOf { capacities[it] } })
                // Strictly larger only, so ties keep fewer rows.
                if (size > bestSize) {
                    best = split
                    bestSize = size
                }
            }
            return place(width, height, capacities, best, bestSize)
        }

        private fun itemSize(
            width: Float,
            height: Float,
            perRow: Int,
            rowCaps: List<Int>,
        ): Float {
            val byWidth = width / perRow * WIDTH_FACTOR
            val byHeight = height / rowCaps.sumOf { it + ROW_EXTRA.toDouble() }.toFloat()
            return minOf(byWidth, byHeight, MAX_ITEM)
        }

        private fun place(
            width: Float,
            height: Float,
            capacities: List<Int>,
            rows: List<List<Int>>,
            size: Float,
        ): BoardLayout {
            val pad = size * PAD
            val tubeW = size + pad * 2
            val slotW = width / rows.first().size
            val rowHeights = rows.map { row -> size * (row.maxOf { capacities[it] } + ROW_EXTRA) }
            val tubes = MutableList(capacities.size) { NO_BOX }
            val cells = MutableList(capacities.size) { NO_BOX }
            var rowTop = height - (height - rowHeights.sum()) / 2
            for ((r, row) in rows.withIndex()) {
                val rowY = rowTop - rowHeights[r]
                // The top row's cells reach the top of the board, the bottom row's reach the bottom.
                val cellTop = if (r == 0) height else rowTop
                val cellBottom = if (r == rows.lastIndex) 0f else rowY
                val offsetX = (width - slotW * row.size) / 2
                val centers = row.indices.map { offsetX + slotW * (it + HALF) }
                for ((c, i) in row.withIndex()) {
                    val cx = centers[c]
                    tubes[i] = Box(cx - tubeW / 2, rowY + size * LABEL_SPACE, tubeW, capacities[i] * size + pad * 2)
                    val left = if (c == 0) 0f else (centers[c - 1] + cx) / 2
                    val right = if (c == row.lastIndex) width else (cx + centers[c + 1]) / 2
                    cells[i] = Box(left, cellBottom, right - left, cellTop - cellBottom)
                }
                rowTop = rowY
            }
            return BoardLayout(tubes, cells, size, pad, size * LIFT)
        }
    }
}
