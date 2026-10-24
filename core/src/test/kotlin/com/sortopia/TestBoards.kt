package com.sortopia

import com.sortopia.model.Board
import com.sortopia.model.Item
import com.sortopia.model.Tube

/**
 * Tiny DSL for test boards. Items are written bottom to top, separated by spaces:
 * `"1 2 ?3 *4:5 =6 7"` means type 1, type 2, hidden type 3, type 4 frozen for 5 moves,
 * type 6 glued to the item above it (type 7).
 */
object TestBoards {
    private var nextId = 0

    fun tube(
        spec: String,
        capacity: Int = 4,
        lock: Int? = null,
        only: Int? = null,
    ): Tube {
        val items =
            spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { token ->
                var t = token
                var hidden = false
                var glued = false
                var frozen = 0
                if (t.startsWith("?")) {
                    hidden = true
                    t = t.drop(1)
                }
                if (t.startsWith("=")) {
                    glued = true
                    t = t.drop(1)
                }
                if (t.startsWith("*")) {
                    val parts = t.drop(1).split(":")
                    t = parts[0]
                    frozen = parts[1].toInt()
                }
                Item(id = nextId++, type = t.toInt(), hidden = hidden, frozen = frozen, gluedUp = glued)
            }
        return Tube(capacity, items, lock, only)
    }

    fun board(vararg tubes: Tube): Board = Board(tubes.toList())

    fun types(tube: Tube): List<Int> = tube.items.map { it.type }
}
