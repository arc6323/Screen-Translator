package com.arc6323.screentranslator

import org.junit.Assert.*
import org.junit.Test

class LoopTimingTest {
    @Test fun processingTimeDoesNotGetAnotherLongWait() {
        assertEquals(16L, LoopTiming.nextDelay(800, false))
        assertEquals(40L, LoopTiming.nextDelay(80, false))
        assertEquals(240L, LoopTiming.nextDelay(20, true))
    }
    @Test fun touchOpacityStaysStrictlyBelowThePlatformLimit() {
        for (maximum in listOf(0.8f, 0.5f, 0.2f)) {
            assertTrue(LoopTiming.touchAlpha(maximum) < maximum)
        }
        assertEquals(0.76f, LoopTiming.touchAlpha(Float.NaN), 0.0001f)
        assertEquals(0f, LoopTiming.touchAlpha(0f), 0.0001f)
    }
}
