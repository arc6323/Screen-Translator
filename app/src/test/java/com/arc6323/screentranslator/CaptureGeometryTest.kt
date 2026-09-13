package com.arc6323.screentranslator

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGeometryTest {
    @Test fun windowOriginIsSubtractedAfterScaling() {
        val transform = CaptureGeometry(540, 1200, 1080, 2400, 0, 72)
        assertEquals(200f, transform.x(100f), 0.001f)
        assertEquals(128f, transform.y(100f), 0.001f)
    }
    @Test fun landscapeAndOffsetWindowMapCornersExactly() {
        val transform = CaptureGeometry(1200, 540, 2400, 1080, 24, 0)
        assertEquals(-24f, transform.x(0f), 0.001f)
        assertEquals(2376f, transform.x(1200f), 0.001f)
        assertEquals(1080f, transform.y(540f), 0.001f)
    }
}
