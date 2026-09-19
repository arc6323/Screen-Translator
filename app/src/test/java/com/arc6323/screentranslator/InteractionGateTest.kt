package com.arc6323.screentranslator

import org.junit.Assert.*
import org.junit.Test

class InteractionGateTest {
    @Test fun flingKeepsTranslationHiddenUntilThePageSettles() {
        val gate = InteractionGate()
        gate.touch(1000)
        assertEquals(400L, gate.delay(1000))
        gate.motion(1250)
        gate.motion(1500)
        assertEquals(240L, gate.delay(1500))
        assertEquals(1L, gate.delay(1739))
        assertEquals(0L, gate.delay(1740))
    }
    @Test fun shortMotionCannotShortenTouchCooldown() {
        val gate = InteractionGate()
        gate.touch(1000)
        gate.motion(1010)
        assertEquals(390L, gate.delay(1010))
    }
}
