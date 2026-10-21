package com.sortopia.game

import com.sortopia.TestBoards.board
import com.sortopia.TestBoards.tube
import com.sortopia.gen.GeneratedLevel
import com.sortopia.gen.LevelParams
import com.sortopia.model.Board
import com.sortopia.model.LevelId
import com.sortopia.model.Move
import com.sortopia.rules.PourResult
import com.sortopia.solver.Solver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameSessionTest {
    private fun level(b: Board): GeneratedLevel {
        val solution = (Solver().solve(b) as Solver.Result.Solved).moves
        return GeneratedLevel(LevelId.Campaign(1, 1), LevelParams(types = 2, height = 2, spares = 1, basePoints = 100), b, solution, 0)
    }

    private val simple = board(tube("1 2", 2), tube("2 1", 2), tube("", 2))

    @Test
    fun `pours count moves and solving produces a score`() {
        val s = GameSession(level(simple), Wallet(0))
        for (m in s.level.solution) assertTrue(s.pour(m) is PourResult.Ok)
        assertTrue(s.isSolved)
        assertEquals(s.level.par, s.moves)
        assertEquals(100, s.result().score)
    }

    @Test
    fun `illegal pour does not count as a move`() {
        val s = GameSession(level(simple), Wallet(0))
        assertFalse(s.pour(Move(0, 1)) is PourResult.Ok)
        assertEquals(0, s.moves)
    }

    @Test
    fun `undo costs points, reverts the board and still counts the move`() {
        val w = Wallet(100)
        val s = GameSession(level(simple), w)
        val before = s.board
        s.pour(Move(0, 2))
        assertEquals(HelperResult.Done, s.undo())
        assertEquals(before, s.board)
        assertEquals(1, s.moves)
        assertEquals(100 - Helpers.UNDO_COST, w.points)
    }

    @Test
    fun `helpers need enough points and the wallet never goes negative`() {
        val w = Wallet(5)
        val s = GameSession(level(simple), w)
        s.pour(Move(0, 2))
        assertEquals(HelperResult.CannotAfford, s.undo())
        assertEquals(HelperResult.CannotAfford, s.hint())
        assertEquals(HelperResult.CannotAfford, s.extraTube())
        assertEquals(5, w.points)
    }

    @Test
    fun `hint highlights a solving move and charges`() {
        val w = Wallet(100)
        val s = GameSession(level(simple), w)
        val r = s.hint()
        assertTrue(r is HelperResult.Hint)
        assertEquals(100 - Helpers.HINT_COST, w.points)
        assertTrue(s.pour((r as HelperResult.Hint).move) is PourResult.Ok)
    }

    @Test
    fun `hint in a dead end is free`() {
        // After 3 -> 2 completes type 3, the lone 4 can go nowhere and nothing else fits.
        val dead = board(tube("1 2 1 2"), tube("2 1 2 1"), tube("3 3 3"), tube("4 3"))
        val params = LevelParams(types = 3, height = 4, spares = 0, basePoints = 100)
        val lvl = GeneratedLevel(LevelId.Campaign(1, 1), params, dead, emptyList(), 0)
        val w = Wallet(100)
        val s = GameSession(lvl, w)
        s.pour(Move(3, 2))
        assertEquals(HelperResult.DeadEnd, s.hint())
        assertEquals(100, w.points)
        assertTrue(s.isStuck)
    }

    @Test
    fun `extra tube adds a standard tube once per level and survives undo`() {
        val w = Wallet(1000)
        val s = GameSession(level(simple), w)
        s.pour(Move(0, 2))
        assertEquals(HelperResult.Done, s.extraTube())
        assertEquals(4, s.board.tubes.size)
        assertEquals(HelperResult.NotAvailable, s.extraTube())
        s.undo()
        assertEquals(4, s.board.tubes.size)
        assertEquals(1000 - Helpers.EXTRA_TUBE_COST - Helpers.UNDO_COST, w.points)
    }

    @Test
    fun `restart is free and resets moves, timer and extra tube`() {
        val w = Wallet(1000)
        val s = GameSession(level(simple), w)
        s.pour(Move(0, 2))
        s.extraTube()
        s.tick(5.0)
        val points = w.points
        s.restart()
        assertEquals(0, s.moves)
        assertEquals(0.0, s.elapsedSeconds)
        assertEquals(3, s.board.tubes.size)
        assertEquals(points, w.points)
    }

    @Test
    fun `revealed items stay revealed after undo and restart`() {
        val b = board(tube("2 ?1 2"), tube("1 ?2 1"), tube(""), tube(""))
        val params = LevelParams(types = 2, height = 4, spares = 2, basePoints = 100)
        val lvl = GeneratedLevel(LevelId.Campaign(2, 1), params, b, emptyList(), 0)
        val s = GameSession(lvl, Wallet(100))
        s.pour(Move(0, 2))
        assertFalse(
            s.board.tubes[0]
                .top!!
                .hidden,
        )
        s.undo()
        assertFalse(
            s.board.tubes[0]
                .items[1]
                .hidden,
        )
        s.restart()
        assertFalse(
            s.board.tubes[0]
                .items[1]
                .hidden,
        )
        assertTrue(
            s.board.tubes[1]
                .items[1]
                .hidden,
        )
    }

    @Test
    fun `timer runs only through ticks, caps long frames and stops when solved`() {
        val s = GameSession(level(simple), Wallet(0))
        s.tick(0.25)
        s.tick(10.0)
        assertEquals(0.75, s.elapsedSeconds, 1e-9)
        for (m in s.level.solution) s.pour(m)
        s.tick(1.0)
        assertEquals(0.75, s.elapsedSeconds, 1e-9)
    }

    @Test
    fun `replaying recorded events restores the same state without charging`() {
        val w = Wallet(1000)
        val s = GameSession(level(simple), w)
        s.pour(Move(0, 2))
        s.extraTube()
        s.undo()
        s.pour(Move(1, 3))
        val w2 = Wallet(0)
        val copy = GameSession(level(simple), w2)
        assertTrue(copy.replay(s.events, 12.0))
        assertEquals(s.board, copy.board)
        assertEquals(s.moves, copy.moves)
        assertEquals(12.0, copy.elapsedSeconds)
        assertEquals(0, w2.points)
    }

    @Test
    fun `replay rejects impossible events`() {
        val copy = GameSession(level(simple), Wallet(0))
        assertFalse(copy.replay(listOf(SessionEvent.Undo), 0.0))
        assertFalse(GameSession(level(simple), Wallet(0)).replay(listOf(SessionEvent.Pour(Move(0, 1))), 0.0))
    }
}
