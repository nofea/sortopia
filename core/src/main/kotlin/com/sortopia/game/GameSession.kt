package com.sortopia.game

import com.sortopia.gen.GeneratedLevel
import com.sortopia.model.Board
import com.sortopia.model.Move
import com.sortopia.model.Tube
import com.sortopia.rules.IllegalReason
import com.sortopia.rules.PourResult
import com.sortopia.rules.Rules
import com.sortopia.solver.Solver

/** SPEC §4 helper prices. */
object Helpers {
    const val UNDO_COST = 10
    const val HINT_COST = 50
    const val EXTRA_TUBE_COST = 150
    const val STARTING_WALLET = 300
}

/** Events that change the board; persisted to resume a level mid-play (SPEC §15). */
sealed interface SessionEvent {
    data class Pour(
        val move: Move,
    ) : SessionEvent

    data object Undo : SessionEvent

    data object ExtraTube : SessionEvent

    data object Restart : SessionEvent
}

sealed interface HelperResult {
    data object Done : HelperResult

    data class Hint(
        val move: Move,
    ) : HelperResult

    data object CannotAfford : HelperResult

    data object NotAvailable : HelperResult

    /** The hint found no solution from here; nothing was charged (SPEC §4). */
    data object DeadEnd : HelperResult
}

/** Points wallet; never negative (SPEC §4). */
class Wallet(
    points: Int,
) {
    var points: Int = points
        private set

    init {
        require(points >= 0)
    }

    fun canAfford(cost: Int): Boolean = points >= cost

    fun trySpend(cost: Int): Boolean {
        if (!canAfford(cost)) return false
        points -= cost
        return true
    }

    fun add(amount: Int) {
        require(amount >= 0)
        points = (points.toLong() + amount).coerceAtMost(MAX_POINTS.toLong()).toInt()
    }

    companion object {
        const val MAX_POINTS = 100_000_000
    }
}

/**
 * One play-through of a level: board, move counter, history, timer and helpers.
 * Pure logic; the UI calls [tick] with frame time only while the level is actively played.
 */
class GameSession(
    val level: GeneratedLevel,
    private val wallet: Wallet,
    private val solver: Solver = Solver(),
) {
    var board: Board = level.board
        private set
    var moves: Int = 0
        private set
    var elapsedSeconds: Double = 0.0
        private set
    var extraTubeUsed: Boolean = false
        private set

    private val history = ArrayList<Board>()
    private val revealedIds = HashSet<Int>()
    private val _events = ArrayList<SessionEvent>()
    val events: List<SessionEvent> get() = _events

    val isSolved: Boolean get() = board.isSolved
    val canUndo: Boolean get() = history.isNotEmpty()
    val isStuck: Boolean get() = !isSolved && !Rules.hasLegalMove(board)

    fun tick(deltaSeconds: Double) {
        if (!isSolved && deltaSeconds > 0) elapsedSeconds += deltaSeconds.coerceAtMost(MAX_TICK)
    }

    fun pour(move: Move): PourResult {
        if (isSolved) return PourResult.Illegal(IllegalReason.SOURCE_SEALED)
        val result = Rules.pour(board, move)
        if (result is PourResult.Ok) {
            history += board
            board = result.board
            moves++
            rememberRevealed()
            _events += SessionEvent.Pour(move)
        }
        return result
    }

    /** Undo costs points; the undone move still counts (SPEC §4). */
    fun undo(): HelperResult {
        if (!canUndo) return HelperResult.NotAvailable
        if (!wallet.trySpend(Helpers.UNDO_COST)) return HelperResult.CannotAfford
        applyUndo()
        return HelperResult.Done
    }

    fun hint(): HelperResult = applyHint(board, if (canAffordHint()) solver.hint(board) else null)

    fun canAffordHint(): Boolean = wallet.canAfford(Helpers.HINT_COST)

    /**
     * Charges for a hint computed elsewhere (e.g. on a worker thread) from [forBoard].
     * A stale hint (the board changed meanwhile) is not charged.
     */
    fun applyHint(
        forBoard: Board,
        move: Move?,
    ): HelperResult {
        if (isSolved || forBoard != board) return HelperResult.NotAvailable
        if (!canAffordHint()) return HelperResult.CannotAfford
        if (move == null) return HelperResult.DeadEnd
        wallet.trySpend(Helpers.HINT_COST)
        return HelperResult.Hint(move)
    }

    fun extraTube(): HelperResult {
        if (extraTubeUsed || isSolved) return HelperResult.NotAvailable
        if (!wallet.trySpend(Helpers.EXTRA_TUBE_COST)) return HelperResult.CannotAfford
        applyExtraTube()
        return HelperResult.Done
    }

    /** Free. Revealed hidden items stay revealed (SPEC §6.3). */
    fun restart() {
        board = withRevealed(level.board)
        history.clear()
        moves = 0
        elapsedSeconds = 0.0
        extraTubeUsed = false
        _events += SessionEvent.Restart
    }

    fun result(): Scoring.Breakdown {
        check(isSolved) { "level not solved" }
        return Scoring.score(level.basePoints, level.par, moves, level.parTimeSeconds, elapsedSeconds)
    }

    /** Re-applies saved [events] without charging the wallet (already paid). Returns false if any is invalid. */
    fun replay(
        events: List<SessionEvent>,
        elapsed: Double,
    ): Boolean {
        for (e in events) {
            when (e) {
                is SessionEvent.Pour -> if (pour(e.move) !is PourResult.Ok) return false
                SessionEvent.Undo -> if (canUndo) applyUndo() else return false
                SessionEvent.ExtraTube -> if (!extraTubeUsed) applyExtraTube() else return false
                SessionEvent.Restart -> restart()
            }
        }
        elapsedSeconds = elapsed.coerceAtLeast(0.0)
        return true
    }

    private fun applyUndo() {
        board = withRevealed(history.removeAt(history.size - 1))
        _events += SessionEvent.Undo
    }

    private fun applyExtraTube() {
        val tube = Tube(level.standardCapacity)
        board = Board(board.tubes + tube)
        for (i in history.indices) history[i] = Board(history[i].tubes + tube)
        extraTubeUsed = true
        _events += SessionEvent.ExtraTube
    }

    private fun rememberRevealed() {
        for (t in board.tubes) for (item in t.items) if (!item.hidden) revealedIds += item.id
    }

    private fun withRevealed(b: Board): Board {
        if (revealedIds.isEmpty()) return b
        return Board(
            b.tubes.map { t ->
                if (t.items.none { it.hidden && it.id in revealedIds }) {
                    t
                } else {
                    t.copy(items = t.items.map { if (it.hidden && it.id in revealedIds) it.copy(hidden = false) else it })
                }
            },
        )
    }

    companion object {
        /** Caps a single frame's contribution so a stalled frame can't inflate the timer. */
        private const val MAX_TICK = 0.5
    }
}
