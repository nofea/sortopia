package com.sortopia.rules

import com.sortopia.model.Board
import com.sortopia.model.Item
import com.sortopia.model.Move
import com.sortopia.model.Tube

/** Why a pour was rejected; used for feedback and tests. */
enum class IllegalReason {
    SAME_TUBE,
    BAD_INDEX,
    SOURCE_EMPTY,
    SOURCE_LOCKED,
    SOURCE_SEALED,
    SOURCE_FROZEN,
    TARGET_LOCKED,
    TARGET_SEALED,
    TARGET_FULL,
    TARGET_FROZEN,
    TYPE_MISMATCH,
    LABEL_MISMATCH,
    PAIR_DOES_NOT_FIT,
}

sealed interface PourResult {
    data class Ok(
        val board: Board,
        val movedCount: Int,
        val completedTubes: List<Int>,
    ) : PourResult

    data class Illegal(
        val reason: IllegalReason,
    ) : PourResult
}

/** The pure rules of Sortopia (SPEC §2 and §6.1). */
object Rules {
    /**
     * The unit that would leave [tube] when used as a source, bottom to top,
     * or null if the tube can't be a source.
     */
    fun movableUnit(tube: Tube): List<Item>? {
        if (tube.isEmpty || tube.isLocked || tube.isComplete) return null
        val items = tube.items
        val top = items.last()
        if (top.isFrozen || top.hidden) return null
        if (items.size >= 2 && items[items.size - 2].gluedUp) {
            return items.subList(items.size - 2, items.size)
        }
        var start = items.size - 1
        while (start > 0) {
            val below = items[start - 1]
            val belowInPair = below.gluedUp || (start >= 2 && items[start - 2].gluedUp)
            val joinsRun = below.type == top.type && !below.hidden && !below.isFrozen
            if (!joinsRun || belowInPair) break
            start--
        }
        return items.subList(start, items.size)
    }

    fun isPair(unit: List<Item>): Boolean = unit.size == 2 && unit[0].gluedUp

    @Suppress("ReturnCount") // One early exit per rule keeps each rule readable next to SPEC §2.2.
    fun check(
        board: Board,
        move: Move,
    ): IllegalReason? {
        val tubes = board.tubes
        if (move.from !in tubes.indices || move.to !in tubes.indices) return IllegalReason.BAD_INDEX
        if (move.from == move.to) return IllegalReason.SAME_TUBE
        val src = tubes[move.from]
        val dst = tubes[move.to]
        sourceProblem(src)?.let { return it }
        targetProblem(dst)?.let { return it }
        val unit = movableUnit(src) ?: return IllegalReason.SOURCE_EMPTY
        val pair = isPair(unit)
        // Pouring inverts order: the unit's top item lands first, onto the target's top.
        val landing = unit.last()
        val dstTop = dst.top
        if (dstTop != null && dstTop.type != landing.type) return IllegalReason.TYPE_MISMATCH
        if (dst.onlyType != null && unit.any { it.type != dst.onlyType }) return IllegalReason.LABEL_MISMATCH
        if (pair && dst.freeSlots < 2) return IllegalReason.PAIR_DOES_NOT_FIT
        return null
    }

    private fun sourceProblem(src: Tube): IllegalReason? =
        when {
            src.isEmpty -> IllegalReason.SOURCE_EMPTY
            src.isLocked -> IllegalReason.SOURCE_LOCKED
            src.isComplete -> IllegalReason.SOURCE_SEALED
            src.top!!.isFrozen -> IllegalReason.SOURCE_FROZEN
            else -> null
        }

    private fun targetProblem(dst: Tube): IllegalReason? =
        when {
            dst.isLocked -> IllegalReason.TARGET_LOCKED
            dst.isComplete -> IllegalReason.TARGET_SEALED
            dst.isFull -> IllegalReason.TARGET_FULL
            dst.top?.isFrozen == true -> IllegalReason.TARGET_FROZEN
            else -> null
        }

    fun isLegal(
        board: Board,
        move: Move,
    ): Boolean = check(board, move) == null

    fun pour(
        board: Board,
        move: Move,
    ): PourResult {
        check(board, move)?.let { return PourResult.Illegal(it) }
        val src = board.tubes[move.from]
        val dst = board.tubes[move.to]
        val unit = movableUnit(src)!!
        val count = if (isPair(unit)) 2 else minOf(unit.size, dst.freeSlots)
        val moving = unit.subList(unit.size - count, unit.size)
        // Pouring inverts the moving stack; for a pair the glue flag moves to the new lower item.
        val landed =
            if (isPair(moving)) {
                listOf(moving[1].copy(gluedUp = true), moving[0].copy(gluedUp = false))
            } else {
                moving.reversed()
            }
        val newSrcItems = src.items.subList(0, src.items.size - count).toMutableList()
        revealTop(newSrcItems)
        var tubes =
            board.tubes.toMutableList().also {
                it[move.from] = src.copy(items = newSrcItems)
                it[move.to] = dst.copy(items = dst.items + landed)
            }
        tubes = tickFrozen(tubes)
        val (afterCompletion, completed) = applyCompletions(board.tubes, tubes)
        return PourResult.Ok(Board(afterCompletion), count, completed)
    }

    private fun revealTop(items: MutableList<Item>) {
        val last = items.lastOrNull() ?: return
        if (last.hidden) items[items.size - 1] = last.copy(hidden = false)
    }

    private fun tickFrozen(tubes: MutableList<Tube>): MutableList<Tube> {
        for (i in tubes.indices) {
            val t = tubes[i]
            if (t.items.any { it.isFrozen }) {
                tubes[i] = t.copy(items = t.items.map { if (it.isFrozen) it.copy(frozen = it.frozen - 1) else it })
            }
        }
        return tubes
    }

    /**
     * Handles tubes that became complete on this move: reveal and thaw their items,
     * dissolve all glue on the board and open matching padlocks.
     */
    private fun applyCompletions(
        before: List<Tube>,
        after: MutableList<Tube>,
    ): Pair<List<Tube>, List<Int>> {
        val completed = after.indices.filter { after[it].isComplete && !before[it].isComplete }
        if (completed.isEmpty()) return after to completed
        val completedTypes = completed.map { after[it].items[0].type }.toSet()
        val result =
            after.mapIndexed { i, t ->
                var tube = t
                if (i in completed) {
                    tube = tube.copy(items = tube.items.map { it.copy(hidden = false, frozen = 0) })
                }
                if (tube.items.any { it.gluedUp }) {
                    tube = tube.copy(items = tube.items.map { if (it.gluedUp) it.copy(gluedUp = false) else it })
                }
                if (tube.lockType != null && tube.lockType in completedTypes) {
                    tube = tube.copy(lockType = null)
                }
                tube
            }
        return result to completed
    }

    fun legalMoves(board: Board): List<Move> {
        val moves = ArrayList<Move>()
        for (from in board.tubes.indices) {
            if (movableUnit(board.tubes[from]) == null) continue
            for (to in board.tubes.indices) {
                val m = Move(from, to)
                if (from != to && check(board, m) == null) moves += m
            }
        }
        return moves
    }

    fun hasLegalMove(board: Board): Boolean = legalMoves(board).isNotEmpty()
}
