package com.sortopia.game

import kotlin.math.roundToInt

/** SPEC §3: efficiency-only scoring and stars. */
object Scoring {
    const val MOVE_WEIGHT = 0.7
    const val TIME_WEIGHT = 0.3
    const val TWO_STAR_RATIO = 0.6
    const val THREE_STAR_RATIO = 0.9

    data class Breakdown(
        val score: Int,
        val moveFactor: Double,
        val timeFactor: Double,
        val stars: Int,
    )

    fun score(
        basePoints: Int,
        par: Int,
        moves: Int,
        parTimeSeconds: Int,
        timeSeconds: Double,
    ): Breakdown {
        val moveFactor = if (moves <= 0) 1.0 else minOf(1.0, par.toDouble() / moves)
        val timeFactor = if (timeSeconds <= 0.0) 1.0 else minOf(1.0, parTimeSeconds / timeSeconds)
        val score = (basePoints * (MOVE_WEIGHT * moveFactor + TIME_WEIGHT * timeFactor)).roundToInt()
        return Breakdown(score, moveFactor, timeFactor, stars(score, basePoints))
    }

    /** Stars for a solved level with [score]; 0 means not solved. */
    fun stars(
        score: Int?,
        basePoints: Int,
    ): Int =
        when {
            score == null -> 0
            score >= THREE_STAR_RATIO * basePoints -> 3
            score >= TWO_STAR_RATIO * basePoints -> 2
            else -> 1
        }
}
