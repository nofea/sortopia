package com.sortopia.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplashTimingTest {
    @Test
    fun `minimum is 3 s, 1 s with reduce motion, 0 on the test fast path`() {
        assertEquals(3f, SplashTiming.minimum(reduceMotion = false, fast = false))
        assertEquals(1f, SplashTiming.minimum(reduceMotion = true, fast = false))
        assertEquals(0f, SplashTiming.minimum(reduceMotion = false, fast = true))
        assertEquals(0f, SplashTiming.minimum(reduceMotion = true, fast = true))
    }

    @Test
    fun `stays until the minimum even when ready`() {
        assertFalse(SplashTiming.shouldLeave(elapsed = 2.99f, ready = true, minimum = 3f))
        assertTrue(SplashTiming.shouldLeave(elapsed = 3f, ready = true, minimum = 3f))
    }

    @Test
    fun `waits past the minimum until ready`() {
        assertFalse(SplashTiming.shouldLeave(elapsed = 5f, ready = false, minimum = 3f))
        assertTrue(SplashTiming.shouldLeave(elapsed = 5f, ready = true, minimum = 3f))
    }

    @Test
    fun `leaves at the 8 s cap even if not ready`() {
        assertFalse(SplashTiming.shouldLeave(elapsed = 7.99f, ready = false, minimum = 3f))
        assertTrue(SplashTiming.shouldLeave(elapsed = 8f, ready = false, minimum = 3f))
        assertTrue(SplashTiming.shouldLeave(elapsed = 8f, ready = false, minimum = 0f))
    }

    @Test
    fun `loading hint shows only while still waiting after 3 s`() {
        assertFalse(SplashTiming.showLoadingHint(elapsed = 2.9f, ready = false))
        assertTrue(SplashTiming.showLoadingHint(elapsed = 3f, ready = false))
        assertFalse(SplashTiming.showLoadingHint(elapsed = 4f, ready = true))
    }
}
