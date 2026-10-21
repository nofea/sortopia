package com.sortopia.gen

import com.sortopia.model.LevelId
import kotlin.math.roundToInt

enum class Mechanic {
    HIDDEN,
    LOCKED,
    SINGLE_TYPE,
    MIXED_CAPACITY,
    GLUED,
    FROZEN,
}

/** Generator inputs for one level (SPEC §7). */
data class LevelParams(
    val types: Int,
    val height: Int,
    val mixedCapacity: Boolean = false,
    /** Lowest tube height when [mixedCapacity] is on; the highest is [height]. */
    val mixedMinHeight: Int = Tuning.MIN_HEIGHT,
    val spares: Int,
    val hiddenFraction: Double = 0.0,
    val lockedTubes: Int = 0,
    val singleTypeTubes: Int = 0,
    val gluedPairs: Int = 0,
    val frozenItems: Int = 0,
    val frozenMoves: Int = 0,
    val basePoints: Int,
) {
    val mechanics: Set<Mechanic>
        get() =
            buildSet {
                if (hiddenFraction > 0) add(Mechanic.HIDDEN)
                if (lockedTubes > 0) add(Mechanic.LOCKED)
                if (singleTypeTubes > 0) add(Mechanic.SINGLE_TYPE)
                if (mixedCapacity) add(Mechanic.MIXED_CAPACITY)
                if (gluedPairs > 0) add(Mechanic.GLUED)
                if (frozenItems > 0) add(Mechanic.FROZEN)
            }
}

/**
 * The tuning table of SPEC §7. Values within a world move linearly from level 1 to level 20;
 * breather levels (5, 10, 15) use the parameters of three levels earlier (SPEC §5.1).
 */
object Tuning {
    /** Bumped whenever generator output or tuning values change (SPEC §8: content versioning). */
    const val GENERATOR_VERSION = 1

    const val MIN_HEIGHT = 3
    const val MAX_HEIGHT = 6
    const val MAX_TYPES = 14

    /** Random layouts with fewer spares are almost never solvable beyond ~6 types (SPEC §7). */
    const val MIN_SPARES = 2

    /** Big combined boards with very short tubes are rarely solvable, so World 8 / Endless use 4–6. */
    const val MIXED_MIN_HEIGHT_COMBINED = 4
    private const val BREATHER_STEP = 3
    private const val ENDLESS_START_TYPES = 12
    private const val ENDLESS_START_POINTS = 600
    private const val ENDLESS_POINTS_STEP = 10
    private const val ENDLESS_MAX_POINTS = 1000

    /** The world whose new mechanic each world introduces. */
    val worldMechanic: Map<Int, Mechanic?> =
        mapOf(
            1 to null,
            2 to Mechanic.HIDDEN,
            3 to Mechanic.LOCKED,
            4 to Mechanic.SINGLE_TYPE,
            5 to Mechanic.MIXED_CAPACITY,
            6 to Mechanic.GLUED,
            7 to Mechanic.FROZEN,
            8 to null,
        )

    fun isBreather(level: Int): Boolean = level % 5 == 0 && level < LevelId.LEVELS_PER_WORLD

    private fun lerp(
        a: Double,
        b: Double,
        t: Double,
    ): Double = a + (b - a) * t

    private fun lerpInt(
        a: Int,
        b: Int,
        t: Double,
    ): Int = lerp(a.toDouble(), b.toDouble(), t).roundToInt()

    fun progress(level: Int): Double {
        val effective = if (isBreather(level)) level - BREATHER_STEP else level
        return (effective - 1) / (LevelId.LEVELS_PER_WORLD - 1).toDouble()
    }

    /** BasePoints only, without needing generation (used by save validation). */
    fun basePoints(id: LevelId): Int =
        when (id) {
            is LevelId.Campaign -> campaign(id.world, id.level, Rng(0)).basePoints
            is LevelId.Endless -> endlessBasePoints(id.number)
            is LevelId.Daily -> campaign(id.tier, DAILY_LEVEL, Rng(0)).basePoints
        }

    private const val DAILY_LEVEL = 10

    fun endlessBasePoints(n: Int): Int = minOf(ENDLESS_MAX_POINTS, ENDLESS_START_POINTS + ENDLESS_POINTS_STEP * (n - 1))

    /**
     * Parameters for campaign level [world]-[level]. [rng] is only consulted for World 8's
     * choice of mechanics, so BasePoints never depend on it.
     */
    @Suppress("CyclomaticComplexMethod")
    fun campaign(
        world: Int,
        level: Int,
        rng: Rng,
    ): LevelParams {
        val t = progress(level)
        return when (world) {
            1 -> LevelParams(types = lerpInt(3, 5, t), height = 4, spares = MIN_SPARES, basePoints = lerpInt(100, 200, t))
            2 ->
                LevelParams(
                    types = lerpInt(4, 6, t),
                    height = 4,
                    spares = MIN_SPARES,
                    hiddenFraction = lerp(0.15, 0.40, t),
                    basePoints = lerpInt(160, 250, t),
                )
            3 ->
                LevelParams(
                    types = lerpInt(5, 7, t),
                    height = 4,
                    spares = MIN_SPARES,
                    lockedTubes = lerpInt(1, 2, t),
                    basePoints = lerpInt(210, 300, t),
                )
            4 ->
                LevelParams(
                    types = lerpInt(5, 7, t),
                    height = lerpInt(4, 5, t),
                    spares = MIN_SPARES,
                    singleTypeTubes = lerpInt(1, 2, t),
                    basePoints = lerpInt(260, 350, t),
                )
            5 ->
                LevelParams(
                    types = lerpInt(6, 8, t),
                    height = MAX_HEIGHT,
                    mixedCapacity = true,
                    spares = MIN_SPARES,
                    basePoints = lerpInt(310, 400, t),
                )
            6 ->
                LevelParams(
                    types = lerpInt(7, 9, t),
                    height = 5,
                    spares = MIN_SPARES,
                    gluedPairs = lerpInt(1, 3, t),
                    basePoints = lerpInt(360, 450, t),
                )
            7 ->
                LevelParams(
                    types = lerpInt(8, 10, t),
                    height = 5,
                    spares = MIN_SPARES,
                    frozenItems = lerpInt(1, 3, t),
                    frozenMoves = lerpInt(8, 4, t),
                    basePoints = lerpInt(410, 500, t),
                )
            8 ->
                mixed(
                    base =
                        LevelParams(
                            types = lerpInt(9, 12, t),
                            height = lerpInt(5, 6, t),
                            spares = MIN_SPARES,
                            basePoints = lerpInt(460, 600, t),
                        ),
                    count = lerpInt(2, 3, t),
                    rng = rng,
                )
            else -> throw IllegalArgumentException("world $world")
        }
    }

    fun endless(
        n: Int,
        rng: Rng,
    ): LevelParams =
        mixed(
            base =
                LevelParams(
                    types = minOf(MAX_TYPES, ENDLESS_START_TYPES + (n - 1) / 10),
                    height = MAX_HEIGHT,
                    spares = MIN_SPARES,
                    basePoints = endlessBasePoints(n),
                ),
            count = minOf(4, 2 + (n - 1) / 20),
            rng = rng,
        )

    fun daily(
        tier: Int,
        rng: Rng,
    ): LevelParams = campaign(tier, DAILY_LEVEL, rng)

    /** Adds [count] randomly chosen mechanics at mid-range densities (World 8 and Endless). */
    private fun mixed(
        base: LevelParams,
        count: Int,
        rng: Rng,
    ): LevelParams {
        val pool = Mechanic.entries.toMutableList()
        rng.shuffle(pool)
        var p = base
        for (m in pool.take(count)) {
            p =
                when (m) {
                    Mechanic.HIDDEN -> p.copy(hiddenFraction = 0.25)
                    Mechanic.LOCKED -> p.copy(lockedTubes = 1)
                    Mechanic.SINGLE_TYPE -> p.copy(singleTypeTubes = 1)
                    Mechanic.MIXED_CAPACITY -> p.copy(mixedCapacity = true, height = MAX_HEIGHT, mixedMinHeight = MIXED_MIN_HEIGHT_COMBINED)
                    Mechanic.GLUED -> p.copy(gluedPairs = 2)
                    Mechanic.FROZEN -> p.copy(frozenItems = 2, frozenMoves = 6)
                }
        }
        return p
    }
}
