package com.sortopia.game

/**
 * When the launch splash ends (SPEC §12): at the later of a minimum time and readiness, but
 * never after [CAP_SECONDS]. Reduce motion shortens the minimum (SPEC §14).
 */
object SplashTiming {
    const val MIN_SECONDS = 3f
    const val REDUCED_MIN_SECONDS = 1f
    const val CAP_SECONDS = 8f

    /** [fast] is the debug-only UI-test fast path. */
    fun minimum(
        reduceMotion: Boolean,
        fast: Boolean,
    ): Float =
        when {
            fast -> 0f
            reduceMotion -> REDUCED_MIN_SECONDS
            else -> MIN_SECONDS
        }

    fun shouldLeave(
        elapsed: Float,
        ready: Boolean,
        minimum: Float,
    ): Boolean = elapsed >= CAP_SECONDS || (ready && elapsed >= minimum)

    fun showLoadingHint(
        elapsed: Float,
        ready: Boolean,
    ): Boolean = !ready && elapsed >= MIN_SECONDS
}
