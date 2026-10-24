package com.sortopia.gen

import com.sortopia.model.Board
import com.sortopia.model.Item
import com.sortopia.model.LevelId
import com.sortopia.model.Move
import com.sortopia.model.Tube
import com.sortopia.solver.Solver
import kotlin.math.roundToInt

data class GeneratedLevel(
    val id: LevelId,
    val params: LevelParams,
    val board: Board,
    val solution: List<Move>,
    val attempt: Int,
) {
    val par: Int get() = solution.size
    val parTimeSeconds: Int get() = par * PAR_SECONDS_PER_MOVE
    val basePoints: Int get() = params.basePoints

    /** Standard capacity for the Extra tube helper (SPEC §4). */
    val standardCapacity: Int get() = board.tubes.maxOf { it.capacity }

    companion object {
        const val PAR_SECONDS_PER_MOVE = 3
    }
}

/**
 * Procedural, seeded level generation (SPEC §8). Each candidate layout is checked by the
 * [Solver]; unsolvable candidates are retried with the next derived seed.
 */
class LevelGenerator(
    private val solver: Solver = Solver(),
) {
    fun generate(id: LevelId): GeneratedLevel {
        val baseParams = paramsFor(id)
        for (extraSpares in 0..MAX_EXTRA_SPARES) {
            val params = baseParams.copy(spares = baseParams.spares + extraSpares)
            for (attempt in 0 until ATTEMPTS_PER_STAGE) {
                val n = extraSpares * ATTEMPTS_PER_STAGE + attempt
                val rng = Rng(Rng.seedOf("v${Tuning.GENERATOR_VERSION}|${id.key}|$n"))
                val board = build(params, rng) ?: continue
                val result = solver.solve(board)
                if (result is Solver.Result.Solved && result.moves.isNotEmpty()) {
                    return GeneratedLevel(id, params, board, result.moves, n)
                }
            }
        }
        error("could not generate a solvable level for ${id.key}")
    }

    companion object {
        /** Candidates per stage before adding one spare tube as a fallback (SPEC §8). */
        const val ATTEMPTS_PER_STAGE = 25
        const val MAX_EXTRA_SPARES = 2
        private const val ITEM_KINDS = Tuning.MAX_TYPES

        fun paramsFor(id: LevelId): LevelParams {
            val rng = Rng(Rng.seedOf("v${Tuning.GENERATOR_VERSION}|${id.key}|params"))
            return when (id) {
                is LevelId.Campaign -> Tuning.campaign(id.world, id.level, rng)
                is LevelId.Endless -> Tuning.endless(id.number, rng)
                is LevelId.Daily -> Tuning.daily(id.tier, rng)
            }
        }

        /** Builds one random candidate layout, or null if it came out trivially sorted. */
        fun build(
            params: LevelParams,
            rng: Rng,
        ): Board? {
            val kinds = (0 until ITEM_KINDS).toMutableList().also(rng::shuffle).take(params.types)
            val capacities =
                kinds.map { if (params.mixedCapacity) rng.nextInt(params.mixedMinHeight, params.height) else params.height }
            var nextId = 0
            val pool = ArrayList<Item>()
            kinds.forEachIndexed { i, kind -> repeat(capacities[i]) { pool += Item(nextId++, kind) } }
            rng.shuffle(pool)

            val tubeCaps = capacities.toMutableList().also(rng::shuffle)
            var cursor = 0
            val filled =
                tubeCaps.map { cap ->
                    val items = pool.subList(cursor, cursor + cap).toMutableList()
                    cursor += cap
                    items
                }
            if (filled.any { items -> items.all { it.type == items[0].type } }) return null

            applyHidden(filled, params, rng)
            applyGlue(filled, params, rng)
            applyFrozen(filled, params, rng)

            val tubes = filled.mapIndexed { i, items -> Tube(tubeCaps[i], items) }.toMutableList()
            applyLocks(tubes, kinds, params, rng)
            applyLabels(tubes, kinds, capacities, params, rng)
            val spareCap = capacities.max()
            repeat(params.spares) { tubes += Tube(spareCap) }
            return Board(tubes)
        }

        private fun applyHidden(
            filled: List<MutableList<Item>>,
            params: LevelParams,
            rng: Rng,
        ) {
            if (params.hiddenFraction <= 0) return
            val total = filled.sumOf { it.size }
            val candidates = filled.flatMapIndexed { t, items -> (0 until items.size - 1).map { t to it } }.toMutableList()
            rng.shuffle(candidates)
            val count = minOf(candidates.size, (total * params.hiddenFraction).roundToInt())
            for ((t, i) in candidates.take(count)) filled[t][i] = filled[t][i].copy(hidden = true)
        }

        private fun applyGlue(
            filled: List<MutableList<Item>>,
            params: LevelParams,
            rng: Rng,
        ) {
            if (params.gluedPairs <= 0) return
            val candidates =
                filled
                    .flatMapIndexed { t, items ->
                        (0 until items.size - 1).filter { items[it].type != items[it + 1].type }.map { t to it }
                    }.toMutableList()
            rng.shuffle(candidates)
            var placed = 0
            for ((t, i) in candidates) {
                if (placed == params.gluedPairs) break
                val items = filled[t]
                val free =
                    !items[i].hidden &&
                        !items[i + 1].hidden &&
                        !items[i].gluedUp &&
                        !items[i + 1].gluedUp &&
                        (i == 0 || !items[i - 1].gluedUp)
                if (!free) continue
                items[i] = items[i].copy(gluedUp = true)
                placed++
            }
        }

        private fun applyFrozen(
            filled: List<MutableList<Item>>,
            params: LevelParams,
            rng: Rng,
        ) {
            if (params.frozenItems <= 0) return
            val candidates =
                filled
                    .flatMapIndexed { t, items ->
                        items.indices
                            .filter { i ->
                                val it = items[i]
                                !it.hidden && !it.gluedUp && (i == 0 || !items[i - 1].gluedUp)
                            }.map { t to it }
                    }.toMutableList()
            rng.shuffle(candidates)
            for ((t, i) in candidates.take(params.frozenItems)) {
                filled[t][i] = filled[t][i].copy(frozen = params.frozenMoves)
            }
        }

        private fun applyLocks(
            tubes: MutableList<Tube>,
            kinds: List<Int>,
            params: LevelParams,
            rng: Rng,
        ) {
            if (params.lockedTubes <= 0) return
            val order = tubes.indices.toMutableList().also(rng::shuffle)
            val lockedTubes = order.take(minOf(params.lockedTubes, tubes.size - 1))
            // A lock whose key type sits inside a locked tube can easily deadlock, so pick key types
            // that no locked tube contains, and give each lock its own key.
            val inLocked = lockedTubes.flatMap { t -> tubes[t].items.map { it.type } }.toSet()
            val keys = kinds.filter { it !in inLocked }.toMutableList().also(rng::shuffle)
            for ((i, t) in lockedTubes.withIndex()) {
                val lockType = keys.getOrNull(i) ?: kinds[rng.nextInt(kinds.size)]
                tubes[t] = tubes[t].copy(lockType = lockType)
            }
        }

        private fun applyLabels(
            tubes: MutableList<Tube>,
            kinds: List<Int>,
            capacities: List<Int>,
            params: LevelParams,
            rng: Rng,
        ) {
            if (params.singleTypeTubes <= 0) return
            val order =
                tubes.indices
                    .filter { !tubes[it].isLocked }
                    .toMutableList()
                    .also(rng::shuffle)
            val usedTypes = HashSet<Int>()
            var placed = 0
            for (t in order) {
                if (placed == params.singleTypeTubes) break
                val fitting = kinds.indices.filter { capacities[it] == tubes[t].capacity && kinds[it] !in usedTypes }
                if (fitting.isEmpty()) continue
                val kind = kinds[fitting[rng.nextInt(fitting.size)]]
                usedTypes += kind
                tubes[t] = tubes[t].copy(onlyType = kind)
                placed++
            }
        }
    }
}
