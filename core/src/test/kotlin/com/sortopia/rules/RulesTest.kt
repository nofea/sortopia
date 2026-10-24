package com.sortopia.rules

import com.sortopia.TestBoards.board
import com.sortopia.TestBoards.tube
import com.sortopia.TestBoards.types
import com.sortopia.model.Move
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RulesTest {
    private fun ok(result: PourResult): PourResult.Ok {
        assertTrue(result is PourResult.Ok, "expected legal pour but was $result")
        return result as PourResult.Ok
    }

    private fun illegal(result: PourResult): IllegalReason {
        assertTrue(result is PourResult.Illegal, "expected illegal pour but was $result")
        return (result as PourResult.Illegal).reason
    }

    @Nested
    inner class BasicPouring {
        @Test
        fun `whole matching run pours together`() {
            val b = board(tube("1 2 2"), tube("2"))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(listOf(1), types(r.board.tubes[0]))
            assertEquals(listOf(2, 2, 2), types(r.board.tubes[1]))
            assertEquals(2, r.movedCount)
        }

        @Test
        fun `only as many as fit are poured`() {
            val b = board(tube("1 2 2 2"), tube("3 2"))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(listOf(1, 2), types(r.board.tubes[0]))
            assertEquals(listOf(3, 2, 2, 2), types(r.board.tubes[1]))
        }

        @Test
        fun `pour into empty tube is legal`() {
            val r = ok(Rules.pour(board(tube("1 2"), tube("")), Move(0, 1)))
            assertEquals(listOf(2), types(r.board.tubes[1]))
        }

        @Test
        fun `pour onto different type is illegal`() {
            assertEquals(IllegalReason.TYPE_MISMATCH, illegal(Rules.pour(board(tube("1 2"), tube("3")), Move(0, 1))))
        }

        @Test
        fun `pour into full tube is illegal`() {
            assertEquals(IllegalReason.TARGET_FULL, illegal(Rules.pour(board(tube("1 2"), tube("2 2 1 2")), Move(0, 1))))
        }

        @Test
        fun `pour from empty and onto self are illegal`() {
            assertEquals(IllegalReason.SOURCE_EMPTY, illegal(Rules.pour(board(tube(""), tube("1")), Move(0, 1))))
            assertEquals(IllegalReason.SAME_TUBE, illegal(Rules.pour(board(tube("1"), tube("1")), Move(0, 0))))
            assertEquals(IllegalReason.BAD_INDEX, illegal(Rules.pour(board(tube("1")), Move(0, 3))))
        }

        @Test
        fun `completed tube is sealed and the level is solved when all tubes are complete or empty`() {
            val b = board(tube("1 1 1"), tube("1"), tube(""))
            val r = ok(Rules.pour(b, Move(1, 0)))
            assertTrue(r.board.tubes[0].isComplete)
            assertEquals(listOf(0), r.completedTubes)
            assertTrue(r.board.isSolved)
            assertEquals(IllegalReason.SOURCE_SEALED, illegal(Rules.pour(r.board, Move(0, 2))))
            assertEquals(IllegalReason.TARGET_SEALED, Rules.check(board(tube("2"), tube("1 1 1 1")), Move(0, 1)))
        }

        @Test
        fun `no legal moves is detected`() {
            val stuck = board(tube("1 2 1 2"), tube("2 1 2 1"))
            assertFalse(Rules.hasLegalMove(stuck))
            assertFalse(stuck.isSolved)
        }
    }

    @Nested
    inner class HiddenItems {
        @Test
        fun `hidden item is revealed when it becomes the top`() {
            val b = board(tube("1 ?2 3"), tube(""))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertFalse(
                r.board.tubes[0]
                    .top!!
                    .hidden,
            )
            assertTrue(
                r.board.tubes[0]
                    .items[0]
                    .type == 1,
            )
        }

        @Test
        fun `hidden items are not part of the matching run`() {
            val b = board(tube("1 ?2 2"), tube(""))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(1, r.movedCount)
            assertEquals(listOf(1, 2), types(r.board.tubes[0]))
            assertFalse(
                r.board.tubes[0]
                    .top!!
                    .hidden,
            )
        }
    }

    @Nested
    inner class LockedTubes {
        @Test
        fun `locked tube can be neither source nor target`() {
            val b = board(tube("2", lock = 1), tube("2"))
            assertEquals(IllegalReason.SOURCE_LOCKED, illegal(Rules.pour(b, Move(0, 1))))
            assertEquals(IllegalReason.TARGET_LOCKED, illegal(Rules.pour(b, Move(1, 0))))
        }

        @Test
        fun `lock opens when a tube of its type is completed`() {
            val b = board(tube("1 1 1"), tube("1"), tube("2 3", lock = 1), tube("", lock = 3))
            val r = ok(Rules.pour(b, Move(1, 0)))
            assertEquals(null, r.board.tubes[2].lockType)
            assertEquals(3, r.board.tubes[3].lockType)
        }
    }

    @Nested
    inner class SingleTypeTubes {
        @Test
        fun `labeled tube accepts only its type`() {
            val b = board(tube("1 3"), tube("", only = 2), tube("2"))
            assertEquals(IllegalReason.LABEL_MISMATCH, illegal(Rules.pour(b, Move(0, 1))))
            ok(Rules.pour(b, Move(2, 1)))
        }

        @Test
        fun `labeled tube may start with other items that pour out normally`() {
            val b = board(tube("3 1", only = 2), tube(""))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(listOf(3), types(r.board.tubes[0]))
        }
    }

    @Nested
    inner class MixedCapacity {
        @Test
        fun `tube is complete when full at its own capacity`() {
            val b = board(tube("5 5", capacity = 3), tube("5", capacity = 6))
            val r = ok(Rules.pour(b, Move(1, 0)))
            assertTrue(r.board.tubes[0].isComplete)
        }
    }

    @Nested
    inner class GluedPairs {
        @Test
        fun `pair moves as one unit and lands inverted`() {
            // pair: 3 (bottom, glued up) + 4 (top). Pouring inverts it, so 4 lands on the target's 4.
            val b = board(tube("1 =3 4"), tube("4"))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(listOf(1), types(r.board.tubes[0]))
            assertEquals(listOf(4, 4, 3), types(r.board.tubes[1]))
            assertTrue(
                r.board.tubes[1]
                    .items[1]
                    .gluedUp,
            )
            assertEquals(2, r.movedCount)
        }

        @Test
        fun `pair top must match target top`() {
            val b = board(tube("1 =3 4"), tube("3"))
            assertEquals(IllegalReason.TYPE_MISMATCH, illegal(Rules.pour(b, Move(0, 1))))
        }

        @Test
        fun `pair needs two free slots`() {
            val b = board(tube("1 =3 4"), tube("2 2 4"))
            assertEquals(IllegalReason.PAIR_DOES_NOT_FIT, illegal(Rules.pour(b, Move(0, 1))))
        }

        @Test
        fun `pair is never combined into a larger run`() {
            val b = board(tube("4 =3 4"), tube(""))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(2, r.movedCount)
            assertEquals(listOf(4), types(r.board.tubes[0]))
        }

        @Test
        fun `items below a pair do not join a run`() {
            val b = board(tube("=2 3 3"), tube(""))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(1, r.movedCount)
        }

        @Test
        fun `completing any tube dissolves all glue on the board`() {
            val b = board(tube("1 1 1"), tube("1"), tube("=2 3"))
            val r = ok(Rules.pour(b, Move(1, 0)))
            assertFalse(
                r.board.tubes[2]
                    .items
                    .any { it.gluedUp },
            )
        }

        @Test
        fun `pair can not enter a single-type tube`() {
            val b = board(tube("=2 3"), tube("", only = 3))
            assertEquals(IllegalReason.LABEL_MISMATCH, Rules.check(b, Move(0, 1)))
        }
    }

    @Nested
    inner class FrozenItems {
        @Test
        fun `frozen top can not move and blocks pours onto it`() {
            val b = board(tube("1 *2:3"), tube("2"))
            assertEquals(IllegalReason.SOURCE_FROZEN, illegal(Rules.pour(b, Move(0, 1))))
            assertEquals(IllegalReason.TARGET_FROZEN, illegal(Rules.pour(b, Move(1, 0))))
        }

        @Test
        fun `frozen item blocks the items beneath it and is not part of a run`() {
            val b = board(tube("2 *2:5 2"), tube(""))
            val r = ok(Rules.pour(b, Move(0, 1)))
            assertEquals(1, r.movedCount)
        }

        @Test
        fun `every move counts down all ice and it melts at zero`() {
            var b = board(tube("1 *2:2"), tube("3 4"), tube(""), tube(""))
            b = ok(Rules.pour(b, Move(1, 2))).board
            assertEquals(1, b.tubes[0].top!!.frozen)
            b = ok(Rules.pour(b, Move(1, 3))).board
            assertEquals(0, b.tubes[0].top!!.frozen)
            ok(Rules.pour(b, Move(0, 1)))
        }
    }
}
