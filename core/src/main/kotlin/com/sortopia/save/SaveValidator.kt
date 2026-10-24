package com.sortopia.save

import com.sortopia.game.Progress
import com.sortopia.game.Wallet
import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Strict validation of untrusted save data (SPEC §16). [validate] returns the problems found;
 * an empty list means the save is consistent. [repair] drops only the in-progress level,
 * which is safe to lose; everything else invalid rejects the whole save.
 */
object SaveValidator {
    private const val MAX_FLAGS = 200
    private const val MAX_EVENTS = 20_000
    private const val MAX_ELAPSED = 10_000_000.0
    private val flagPattern = Regex("[a-z0-9-]{1,40}")

    fun validate(data: SaveData): List<String> {
        val problems = ArrayList<String>()
        if (data.version != SaveData.CURRENT_VERSION) problems += "unknown version ${data.version}"
        if (data.wallet !in 0..Wallet.MAX_POINTS) problems += "wallet out of range"
        checkScores(data, problems)
        if (problems.isNotEmpty()) return problems
        checkProgression(data, problems)
        checkDaily(data, problems)
        if (data.flags.size > MAX_FLAGS || data.flags.any { !flagPattern.matches(it) }) problems += "bad flags"
        return problems
    }

    /** Removes an in-progress level that is malformed or not unlocked. */
    fun repair(data: SaveData): SaveData {
        val ip = data.inProgress ?: return data
        val id = LevelId.parse(ip.levelKey)
        val valid =
            id != null &&
                Progress(data).isUnlocked(id) &&
                ip.events.size <= MAX_EVENTS &&
                ip.events.all { EventCodec.decode(it) != null } &&
                ip.elapsedSeconds.isFinite() &&
                ip.elapsedSeconds in 0.0..MAX_ELAPSED
        return if (valid) data else data.copy(inProgress = null)
    }

    private fun checkScores(
        data: SaveData,
        problems: MutableList<String>,
    ) {
        for ((key, score) in data.best) {
            val id = LevelId.parse(key)
            if (id == null || id is LevelId.Daily) {
                problems += "bad level key"
                continue
            }
            if (score !in 0..Tuning.basePoints(id)) problems += "score out of range for $key"
        }
    }

    private fun checkProgression(
        data: SaveData,
        problems: MutableList<String>,
    ) {
        val progress = Progress(data)
        for ((key, _) in data.best) {
            val id = LevelId.parse(key)!!
            val unlocked =
                when (id) {
                    is LevelId.Campaign -> {
                        // Every solved level must have been unlocked: its predecessors solved, its world open.
                        progress.isWorldUnlocked(id.world) &&
                            (id.level == 1 || progress.isSolved(LevelId.Campaign(id.world, id.level - 1)))
                    }
                    is LevelId.Endless ->
                        progress.isEndlessUnlocked() &&
                            (id.number == 1 || progress.isSolved(LevelId.Endless(id.number - 1)))
                    is LevelId.Daily -> false
                }
            if (!unlocked) problems += "solved level $key was never unlocked"
        }
    }

    private fun checkDaily(
        data: SaveData,
        problems: MutableList<String>,
    ) {
        if (data.dailyClaims.isEmpty()) return
        if (data.dailyClaims.size > Progress.MAX_DAILY_CLAIMS) problems += "too many daily claims"
        if (data.dailyClaims.toSet().size != data.dailyClaims.size) problems += "duplicate daily claims"
        val parsed =
            data.dailyClaims.all {
                try {
                    LocalDate.parse(it)
                    true
                } catch (e: DateTimeParseException) {
                    false
                }
            }
        if (!parsed) problems += "unparseable daily date"
        if (!Progress(data).isDailyUnlocked()) problems += "daily claimed before unlock"
    }
}
