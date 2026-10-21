package com.sortopia.game

import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.SaveData
import java.time.LocalDate
import kotlin.math.ceil

/** Pages of the Endless level picker (SPEC §9). [reached] is the highest playable level, [Progress.nextEndless]. */
object EndlessPages {
    const val SIZE = 20

    /** The 0-based page that holds endless level [number]. */
    fun pageOf(number: Int): Int = (number - 1) / SIZE

    fun count(reached: Int): Int = pageOf(reached) + 1

    /** The endless levels on [page], up to [reached]. */
    fun levels(
        page: Int,
        reached: Int,
    ): IntRange {
        val first = page * SIZE + 1
        return first..minOf(first + SIZE - 1, reached)
    }
}

/** Result of recording a solved level. */
data class SolveOutcome(
    val score: Int,
    val pointsEarned: Int,
    val stars: Int,
    val newBest: Boolean,
    val dailyBonus: Boolean,
)

/**
 * Progression rules over [SaveData] (SPEC §3.2, §5, §9, §10). Stars are derived from best
 * scores, and unlocks from solved levels, so nothing about them can drift out of sync.
 */
class Progress(
    data: SaveData,
) {
    var data: SaveData = data
        private set

    val wallet: Wallet = Wallet(data.wallet)

    /** Copies the wallet into [data]; call before saving. */
    fun snapshot(): SaveData = data.copy(wallet = wallet.points).also { data = it }

    fun update(transform: (SaveData) -> SaveData) {
        data = transform(snapshot())
    }

    fun bestScore(id: LevelId): Int? = data.best[id.key]

    fun isSolved(id: LevelId): Boolean = bestScore(id) != null

    fun stars(id: LevelId): Int = Scoring.stars(bestScore(id), Tuning.basePoints(id))

    fun worldStars(world: Int): Int = (1..LevelId.LEVELS_PER_WORLD).sumOf { stars(LevelId.Campaign(world, it)) }

    fun starsThrough(world: Int): Int = (1..world).sumOf { worldStars(it) }

    /** Stars required (in worlds 1..world-1) to open [world]. */
    fun starsRequiredFor(world: Int): Int =
        if (world <= 1) 0 else ceil(UNLOCK_STAR_RATIO * MAX_STARS * LevelId.LEVELS_PER_WORLD * (world - 1)).toInt()

    fun worldFinished(world: Int): Boolean = isSolved(LevelId.Campaign(world, LevelId.LEVELS_PER_WORLD))

    fun isWorldUnlocked(world: Int): Boolean =
        world == 1 || (worldFinished(world - 1) && starsThrough(world - 1) >= starsRequiredFor(world))

    /** How many more stars are needed for [world], or 0. */
    fun starsMissingFor(world: Int): Int = (starsRequiredFor(world) - starsThrough(world - 1)).coerceAtLeast(0)

    fun isUnlocked(id: LevelId): Boolean =
        when (id) {
            is LevelId.Campaign ->
                isWorldUnlocked(id.world) && (id.level == 1 || isSolved(LevelId.Campaign(id.world, id.level - 1)))
            is LevelId.Endless -> isEndlessUnlocked() && id.number <= nextEndless()
            is LevelId.Daily -> isDailyUnlocked()
        }

    fun isEndlessUnlocked(): Boolean = worldFinished(LevelId.WORLDS)

    fun isDailyUnlocked(): Boolean = worldFinished(1)

    fun highestEndlessSolved(): Int =
        data.best.keys
            .mapNotNull { (LevelId.parse(it) as? LevelId.Endless)?.number }
            .maxOrNull() ?: 0

    /** The level to continue Endless with; every level up to it is reached and can be replayed (SPEC §9). */
    fun nextEndless(): Int = (highestEndlessSolved() + 1).coerceAtMost(LevelId.MAX_ENDLESS)

    fun endlessTotal(): Int =
        data.best
            .filterKeys { it.startsWith("e-") }
            .values
            .sum()

    /** The furthest unlocked world, used as the daily puzzle's difficulty tier. */
    fun furthestWorld(): Int = (LevelId.WORLDS downTo 1).first { isWorldUnlocked(it) }

    fun dailyFor(today: LocalDate): LevelId.Daily = LevelId.Daily(today, furthestWorld())

    fun isDailyClaimed(date: LocalDate): Boolean = date.toString() in data.dailyClaims

    /** Consecutive claimed days ending today, or yesterday if today isn't done yet (SPEC §10). */
    fun dailyStreak(today: LocalDate): Int {
        val claimed = data.dailyClaims.toHashSet()
        var day = if (today.toString() in claimed) today else today.minusDays(1)
        var streak = 0
        while (day.toString() in claimed) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    fun hasFlag(flag: String): Boolean = flag in data.flags

    fun setFlag(flag: String) {
        if (!hasFlag(flag)) update { it.copy(flags = it.flags + flag) }
    }

    /** Records a solve: pays full points every time (2× on a day's first daily), keeps the best score. */
    fun recordSolve(
        id: LevelId,
        result: Scoring.Breakdown,
    ): SolveOutcome {
        var points = result.score
        var dailyBonus = false
        if (id is LevelId.Daily) {
            if (!isDailyClaimed(id.date)) {
                points *= DAILY_MULTIPLIER
                dailyBonus = true
                update { it.copy(dailyClaims = (it.dailyClaims + id.date.toString()).takeLast(MAX_DAILY_CLAIMS)) }
            }
        }
        wallet.add(points)
        var newBest = false
        if (id !is LevelId.Daily) {
            val prev = bestScore(id)
            if (prev == null || result.score > prev) {
                newBest = true
                update { it.copy(best = it.best + (id.key to result.score)) }
            }
        }
        update { it.copy(inProgress = null) }
        return SolveOutcome(result.score, points, result.stars, newBest, dailyBonus)
    }

    companion object {
        const val UNLOCK_STAR_RATIO = 0.6
        const val MAX_STARS = 3
        const val DAILY_MULTIPLIER = 2
        const val MAX_DAILY_CLAIMS = 2000
    }
}
