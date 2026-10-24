package com.sortopia.model

/**
 * One item in a tube. [id] is unique within a level and stable across moves.
 *
 * - [hidden]: shown face-down as "?" (Candy Factory).
 * - [frozen]: remaining moves until the ice melts; 0 means not frozen (Ice Cream Parlor).
 * - [gluedUp]: this item is glued to the item directly above it, forming a pair (Aquarium).
 */
data class Item(
    val id: Int,
    val type: Int,
    val hidden: Boolean = false,
    val frozen: Int = 0,
    val gluedUp: Boolean = false,
) {
    val isFrozen: Boolean get() = frozen > 0
}

/**
 * A tube holding [items] bottom to top.
 *
 * - [lockType]: padlocked until a tube of this type is completed (Toy Workshop).
 * - [onlyType]: labeled single-type tube that accepts only this type (Post Office).
 */
data class Tube(
    val capacity: Int,
    val items: List<Item> = emptyList(),
    val lockType: Int? = null,
    val onlyType: Int? = null,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(items.size <= capacity) { "tube overfilled" }
    }

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val isFull: Boolean get() = items.size == capacity
    val isLocked: Boolean get() = lockType != null
    val top: Item? get() = items.lastOrNull()
    val freeSlots: Int get() = capacity - items.size

    /** Full and holding a single item type. A complete tube is sealed. */
    val isComplete: Boolean
        get() = isFull && items.all { it.type == items[0].type }
}

data class Board(
    val tubes: List<Tube>,
) {
    val isSolved: Boolean get() = tubes.all { it.isEmpty || it.isComplete }

    fun replaceTubes(changes: Map<Int, Tube>): Board = Board(tubes.mapIndexed { i, t -> changes[i] ?: t })
}

/** Pour from tube index [from] to tube index [to]. */
data class Move(
    val from: Int,
    val to: Int,
)
