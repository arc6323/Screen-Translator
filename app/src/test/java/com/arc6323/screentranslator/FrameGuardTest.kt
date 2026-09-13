package com.arc6323.screentranslator

import org.junit.Assert.*
import org.junit.Test

class FrameGuardTest {
    @Test fun oldCallbackCannotCompleteAFrameAfterRestart() {
        val guard = FrameGuard()
        guard.start()
        val old = guard.begin()!!
        guard.start()
        val current = guard.begin()!!
        assertFalse(guard.finish(old))
        assertTrue(guard.accepts(current))
        assertTrue(guard.finish(current))
    }
    @Test fun onlyOneFrameCanRunAndOnlyFinishOnce() {
        val guard = FrameGuard()
        guard.start()
        val frame = guard.begin()!!
        assertNull(guard.begin())
        assertTrue(guard.finish(frame))
        assertFalse(guard.finish(frame))
        assertNotNull(guard.begin())
    }
    @Test fun screenChangeRejectsOldTranslationAndStopRejectsAllWork() {
        val guard = FrameGuard()
        guard.start()
        val old = guard.begin()!!
        guard.invalidate()
        assertFalse(guard.accepts(old))
        val current = guard.begin()!!
        guard.stop()
        assertFalse(guard.finish(current))
        assertNull(guard.begin())
    }
}
