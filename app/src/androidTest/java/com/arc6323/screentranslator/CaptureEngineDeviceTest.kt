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
