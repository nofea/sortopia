package com.sortopia.solver

import com.sortopia.TestBoards.board
import com.sortopia.TestBoards.tube
import com.sortopia.model.Move
import com.sortopia.rules.PourResult
import com.sortopia.rules.Rules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SolverTest {
    @Test
    fun `finds the minimal solution on a small board`() {
        val b = board(tube("1 2", 2), tube("2 1", 2), tube("", 2))
        val r = Solver().solve(b) as Solver.Result.Solved
        assertEquals(3, r.moves.size)
        var cur = b
        for (m in r.moves) cur = (Rules.pour(cur, m) as PourResult.Ok).board
        assertTrue(cur.isSolved)
    }

    @Test
    fun `proves a board unsolvable`() {
        val stuck = board(tube("1 2 1 2"), tube("2 1 2 1"))
        assertEquals(Solver.Result.Unsolvable, Solver().solve(stuck))
        assertNull(Solver().hint(stuck))
    }

    @Test
    fun `solved board needs no moves`() {
        assertEquals(Solver.Result.Solved(emptyList()), Solver().solve(board(tube("1 1 1 1"), tube(""))))
    }

    @Test
    fun `respects special mechanics`() {
        // The lock on tube 2 only opens after type 1 is completed.
        val b = board(tube("1 1 1"), tube("2 2 1"), tube("2 2", lock = 1), tube(""))
        val moves = (Solver().solve(b) as Solver.Result.Solved).moves
        assertEquals(Move(1, 0), moves.first())
    }

    @Test
    fun `canonical key ignores tube order`() {
        val a = board(tube("1 2"), tube("2 1"))
        val b = board(tube("2 1"), tube("1 2"))
        assertEquals(Solver.key(a), Solver.key(b))
    }
}
