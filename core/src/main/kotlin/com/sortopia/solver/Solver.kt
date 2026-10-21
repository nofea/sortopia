package com.sortopia.solver

import com.sortopia.model.Board
import com.sortopia.model.Move
import com.sortopia.model.Tube
import com.sortopia.rules.PourResult
import com.sortopia.rules.Rules
import java.util.PriorityQueue

/**
 * Weighted A* over board states (SPEC §8). It knows the true types of hidden items.
 * The result is a minimal or near-minimal solution; its length is the level's par.
 *
 * Searches in [stages] of (weight, node budget): low weights give near-minimal solutions,
 * higher weights find some solution quickly on big boards. Running out of states in any
 * stage proves the board unsolvable.
 *
 * Fully deterministic: ties are broken by insertion order, so par never differs between devices.
 */
class Solver(
    private val stages: List<Stage> = DEFAULT_STAGES,
) {
    data class Stage(
        val weight: Int,
        val maxNodes: Int,
    )

    sealed interface Result {
        data class Solved(
            val moves: List<Move>,
        ) : Result

        /** Proven unsolvable: the whole reachable state space was searched. */
        data object Unsolvable : Result

        /** Gave up after [maxNodes] expansions. */
        data object Exhausted : Result
    }

    private class Node(
        val board: Board,
        val parent: Node?,
        val move: Move?,
        val g: Int,
        val f: Int,
        val h: Int,
        val seq: Long,
    )

    fun solve(start: Board): Result {
        if (start.isSolved) return Result.Solved(emptyList())
        for (stage in stages) {
            val r = search(start, stage.weight, stage.maxNodes)
            if (r != Result.Exhausted) return r
        }
        return Result.Exhausted
    }

    private fun search(
        start: Board,
        weight: Int,
        maxNodes: Int,
    ): Result {
        val open =
            PriorityQueue<Node>(
                compareBy<Node> { it.f }
                    .thenBy { it.h }
                    .thenBy { it.seq },
            )
        val bestG = HashMap<String, Int>()
        var seq = 0L
        val h0 = heuristic(start)
        open.add(Node(start, null, null, 0, weight * h0, h0, seq++))
        bestG[key(start)] = 0
        var expanded = 0
        while (open.isNotEmpty()) {
            val node = open.poll()
            if (node.board.isSolved) return Result.Solved(path(node))
            if (++expanded > maxNodes) return Result.Exhausted
            for (move in candidateMoves(node.board)) {
                val r = Rules.pour(node.board, move) as? PourResult.Ok ?: continue
                val g = node.g + 1
                val k = key(r.board)
                val known = bestG[k]
                if (known != null && known <= g) continue
                bestG[k] = g
                val h = heuristic(r.board)
                open.add(Node(r.board, node, move, g, g + weight * h, h, seq++))
            }
        }
        return Result.Unsolvable
    }

    /** The first move of a solution from [board], or null if none was found (SPEC §4: Hint). */
    fun hint(board: Board): Move? = (solve(board) as? Result.Solved)?.moves?.firstOrNull()

    private fun path(end: Node): List<Move> {
        val moves = ArrayList<Move>()
        var n: Node? = end
        while (n?.move != null) {
            moves += n.move!!
            n = n.parent
        }
        return moves.asReversed()
    }

    /** Legal moves with symmetric and pointless ones pruned. */
    private fun candidateMoves(board: Board): List<Move> {
        val tubes = board.tubes
        val result = ArrayList<Move>()
        for (from in tubes.indices) {
            val src = tubes[from]
            val unit = Rules.movableUnit(src) ?: continue
            val wholeTube = unit.size == src.size
            val seenEmpty = HashSet<String>()
            for (to in tubes.indices) {
                val m = Move(from, to)
                if (to != from && !redundantEmptyTarget(src, tubes[to], wholeTube, seenEmpty) && Rules.check(board, m) == null) {
                    result += m
                }
            }
        }
        return result
    }

    /**
     * Moving a tube's entire content into an equivalent empty tube changes nothing, and
     * equivalent empty tubes are interchangeable, so only the first of them is tried.
     */
    private fun redundantEmptyTarget(
        src: Tube,
        dst: Tube,
        wholeTube: Boolean,
        seenEmpty: MutableSet<String>,
    ): Boolean {
        if (!dst.isEmpty) return false
        if (wholeTube && dst.capacity == src.capacity && dst.onlyType == src.onlyType) return true
        return !seenEmpty.add("${dst.capacity}|${dst.onlyType}|${dst.lockType}")
    }

    companion object {
        val DEFAULT_STAGES = listOf(Stage(weight = 2, maxNodes = 15_000), Stage(weight = 5, maxNodes = 30_000))

        /**
         * Estimate of remaining moves. Every pour merges at most one pair of same-type runs, and each
         * type must end up as a single run. A bottom run that can never complete where it sits
         * (wrong capacity or wrong label) must move as well.
         */
        fun heuristic(board: Board): Int {
            var runs = 0
            val counts = HashMap<Int, Int>()
            for (t in board.tubes) for (item in t.items) counts[item.type] = (counts[item.type] ?: 0) + 1
            for (t in board.tubes) {
                var prev = -1
                for (i in t.items.indices) {
                    val item = t.items[i]
                    val glueBreak = i > 0 && t.items[i - 1].gluedUp
                    if (item.type != prev || glueBreak) runs++
                    prev = item.type
                }
                val bottom = t.items.firstOrNull() ?: continue
                val misplaced = counts[bottom.type] != t.capacity || (t.onlyType != null && t.onlyType != bottom.type)
                if (misplaced) runs++
            }
            return runs - counts.size
        }

        /** Canonical key: tube order doesn't matter for the state itself. */
        fun key(board: Board): String =
            board.tubes
                .map(::tubeKey)
                .sorted()
                .joinToString("|")

        private fun tubeKey(t: Tube): String {
            val sb = StringBuilder()
            sb
                .append(t.capacity)
                .append(',')
                .append(t.lockType ?: -1)
                .append(',')
                .append(t.onlyType ?: -1)
                .append(':')
            for (item in t.items) {
                sb.append(item.type)
                if (item.hidden) sb.append('h')
                if (item.frozen > 0) sb.append('f').append(item.frozen)
                if (item.gluedUp) sb.append('g')
                sb.append(' ')
            }
            return sb.toString()
        }
    }
}
