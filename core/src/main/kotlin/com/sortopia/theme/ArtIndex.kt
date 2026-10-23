package com.sortopia.theme

/**
 * The art atlas index written by `tools/gen_art.py`: which sheet cell holds each sprite, and
 * where each item's face goes. Read from a packaged file, but still parsed defensively: a bad
 * line is skipped, never fatal (SPEC §16).
 */
class ArtIndex(
    val cellPx: Int,
    val grid: Int,
    val cells: Map<String, Cell>,
    val faces: Map<String, FaceAnchor>,
) {
    data class Cell(
        val sheet: Int,
        val col: Int,
        val row: Int,
    )

    /** Face centre ([x], [y] as fractions of the cell, y down) and size relative to the item. */
    data class FaceAnchor(
        val x: Float,
        val y: Float,
        val scale: Float,
    )

    companion object {
        const val DEFAULT_CELL = 128
        const val DEFAULT_GRID = 16
        private const val MAX_SHEETS = 8
        private const val MAX_CELL = 1024
        private const val MAX_GRID = 64
        private const val MIN_FACE = 0.1f
        private const val MAX_FACE = 2f
        private const val FACE_PREFIX = "face."
        private const val FIELDS = 3

        fun parse(text: String): ArtIndex {
            val entries =
                text
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val eq = line.indexOf('=')
                        if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
                    }.toList()
            val header = entries.toMap()
            val cell = header["cell"]?.toIntOrNull()?.takeIf { it in 1..MAX_CELL } ?: DEFAULT_CELL
            val grid = header["grid"]?.toIntOrNull()?.takeIf { it in 1..MAX_GRID } ?: DEFAULT_GRID
            val cells = HashMap<String, Cell>()
            val faces = HashMap<String, FaceAnchor>()
            for ((key, value) in entries) {
                if (key == "cell" || key == "grid") continue
                if (key.startsWith(FACE_PREFIX) && key.count { it == '.' } > 1) {
                    face(value)?.let { faces[key.removePrefix(FACE_PREFIX)] = it }
                } else {
                    cell(value, grid)?.let { cells[key] = it }
                }
            }
            return ArtIndex(cell, grid, cells, faces)
        }

        private fun cell(
            value: String,
            grid: Int,
        ): Cell? {
            val parts = value.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != FIELDS) return null
            val (sheet, col, row) = parts
            val valid = sheet in 0 until MAX_SHEETS && col in 0 until grid && row in 0 until grid
            return if (valid) Cell(sheet, col, row) else null
        }

        private fun face(value: String): FaceAnchor? {
            val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (parts.size != FIELDS) return null
            val (x, y, scale) = parts
            val valid = x in 0f..1f && y in 0f..1f && scale in MIN_FACE..MAX_FACE
            return if (valid) FaceAnchor(x, y, scale) else null
        }
    }
}
