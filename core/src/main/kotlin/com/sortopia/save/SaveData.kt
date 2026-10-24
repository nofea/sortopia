package com.sortopia.save

import com.sortopia.game.Helpers
import com.sortopia.game.SessionEvent
import com.sortopia.model.Move
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val sfx: Boolean = true,
    val music: Boolean = true,
    val haptics: Boolean = true,
    val colorblindPalette: Boolean = false,
    val reduceMotion: Boolean = false,
)

/** A level left mid-play (SPEC §15). Events are replayed against the regenerated level on resume. */
@Serializable
data class InProgress(
    val levelKey: String,
    val events: List<String>,
    val elapsedSeconds: Double,
)

/** Everything persisted on the device (SPEC §15). Validated by [SaveValidator] before use. */
@Serializable
data class SaveData(
    val version: Int = CURRENT_VERSION,
    val wallet: Int = Helpers.STARTING_WALLET,
    /** Best score per solved campaign ("c-w-l") or endless ("e-n") level. */
    val best: Map<String, Int> = emptyMap(),
    /** ISO dates whose daily-puzzle bonus has been claimed. */
    val dailyClaims: List<String> = emptyList(),
    /** Tutorial, explanation-card and tooltip flags already shown. */
    val flags: List<String> = emptyList(),
    val settings: Settings = Settings(),
    val inProgress: InProgress? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** A fresh start that keeps only the player's settings (SPEC §15: Clear progress). */
fun SaveData.clearedProgress(): SaveData = SaveData(settings = settings)

/** Compact, validated text form of [SessionEvent]s for [InProgress]. */
object EventCodec {
    private val pour = Regex("""p(\d{1,2})>(\d{1,2})""")

    fun encode(e: SessionEvent): String =
        when (e) {
            is SessionEvent.Pour -> "p${e.move.from}>${e.move.to}"
            SessionEvent.Undo -> "u"
            SessionEvent.ExtraTube -> "x"
            SessionEvent.Restart -> "r"
        }

    fun decode(s: String): SessionEvent? =
        when (s) {
            "u" -> SessionEvent.Undo
            "x" -> SessionEvent.ExtraTube
            "r" -> SessionEvent.Restart
            else -> pour.matchEntire(s)?.let { SessionEvent.Pour(Move(it.groupValues[1].toInt(), it.groupValues[2].toInt())) }
        }
}
