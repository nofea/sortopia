package com.sortopia.game

import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import com.sortopia.save.SaveData
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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

/** When the next Daily Puzzle opens: at the next local midnight (SPEC §10). */
object DailyClock {
    fun untilNext(now: LocalDateTime): Duration = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay())

    /** [wait] as whole hours and minutes for display, rounded up so it never shows 0h 0m early. */
    fun hoursMinutes(wait: Duration): Pair<Int, Int> {
        val minutes = (wait.seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return (minutes / MINUTES_PER_HOUR).toInt() to (minutes % MINUTES_PER_HOUR).toInt()
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
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
@Suppress("TooManyFunctions") // One cohesive rule set over SaveData; splitting it would scatter the unlock rules.
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

    /** The first unlocked campaign level not yet solved, or null if there is none. */
    fun nextCampaign(): LevelId.Campaign? =
        (1..LevelId.WORLDS)
            .asSequence()
            .flatMap { w -> (1..LevelId.LEVELS_PER_WORLD).asSequence().map { LevelId.Campaign(w, it) } }
            .firstOrNull { !isSolved(it) && isUnlocked(it) }

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

    /** A day's Daily can be played until it is solved, then it is locked until the next date (SPEC §10). */
    fun isDailyPlayable(date: LocalDate): Boolean = isDailyUnlocked() && !isDailyClaimed(date)

    /** Whether a level left mid-play may be resumed: not a Daily whose date is already claimed (SPEC §10, §15). */
    fun isResumable(id: LevelId): Boolean = id !is LevelId.Daily || !isDailyClaimed(id.date)

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
