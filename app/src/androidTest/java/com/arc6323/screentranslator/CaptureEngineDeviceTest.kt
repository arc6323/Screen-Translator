package com.arc6323.screentranslator

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CaptureEngineDeviceTest {
    @Test fun motionMonitoringContinuesAcrossConsecutiveChanges() {
        val changes = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        val engine = CaptureEngine(Handler(Looper.getMainLooper())) { changes.offer(true) }
        try {
            val surface = engine.attach(160, 100)
            fun draw(top: Float) {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(Color.WHITE)
                canvas.drawRect(0f, top, 160f, top + 10f, android.graphics.Paint().apply { color = Color.BLACK })
                surface.unlockCanvasAndPost(canvas)
            }
            draw(0f)
            val ready = CountDownLatch(1)
            engine.request(1, false, { engine.arm(1) }) { it.recycle(); ready.countDown() }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            draw(30f)
            assertEquals(true, changes.poll(3, TimeUnit.SECONDS))
            android.os.SystemClock.sleep(70) // beyond motion callback coalescing
            draw(60f)
            assertEquals(true, changes.poll(3, TimeUnit.SECONDS))
        } finally { engine.close() }
    }

    @Test fun staticScreenCanBeReadTwiceWithoutWaitingForAnotherProducerFrame() {
        val engine = CaptureEngine(Handler(Looper.getMainLooper())) {}
        try {
            val surface = engine.attach(160, 100)
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.WHITE)
            surface.unlockCanvasAndPost(canvas)
            for (token in 1L..2L) {
                val done = CountDownLatch(1)
                engine.request(token, requireFresh = false, prepared = { engine.arm(token) }) { bitmap ->
                    bitmap.recycle()
                    done.countDown()
                }
                assertTrue("Static frame request " + token, done.await(3, TimeUnit.SECONDS))
            }
        } finally { engine.close() }
    }
}
