package com.sortopia.model

import java.time.LocalDate

/** Identifies a playable level (SPEC §5, §9, §10). [key] is the stable string used in saves. */
sealed interface LevelId {
    val key: String

    data class Campaign(
        val world: Int,
        val level: Int,
    ) : LevelId {
        init {
            require(world in 1..WORLDS && level in 1..LEVELS_PER_WORLD) { "invalid campaign level $world-$level" }
        }

        override val key: String get() = "c-$world-$level"
        val label: String get() = "$world-$level"
    }

    data class Endless(
        val number: Int,
    ) : LevelId {
        init {
            require(number in 1..MAX_ENDLESS) { "invalid endless level $number" }
        }

        override val key: String get() = "e-$number"
    }

    /** [tier] is the campaign world whose difficulty the puzzle uses. */
    data class Daily(
        val date: LocalDate,
        val tier: Int,
    ) : LevelId {
        init {
            require(tier in 1..WORLDS) { "invalid daily tier $tier" }
        }

        override val key: String get() = "d-$date-$tier"
    }

    companion object {
        const val WORLDS = 8
        const val LEVELS_PER_WORLD = 20
        const val MAX_ENDLESS = 100_000

        private val campaignKey = Regex("""c-(\d)-(\d{1,2})""")
        private val endlessKey = Regex("""e-(\d{1,6})""")
        private val dailyKey = Regex("""d-(\d{4}-\d{2}-\d{2})-(\d)""")

        /** Parses a key from untrusted input; returns null for anything malformed or out of range. */
        fun parse(key: String): LevelId? =
            runCatching {
                campaignKey.matchEntire(key)?.let { m ->
                    return@runCatching Campaign(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }
                endlessKey.matchEntire(key)?.let { m ->
                    return@runCatching Endless(m.groupValues[1].toInt())
                }
                dailyKey.matchEntire(key)?.let { m ->
                    return@runCatching Daily(LocalDate.parse(m.groupValues[1]), m.groupValues[2].toInt())
                }
                null
            }.getOrNull()
    }
}
