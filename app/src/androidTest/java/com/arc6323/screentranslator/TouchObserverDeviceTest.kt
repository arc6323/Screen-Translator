package com.arc6323.screentranslator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TouchObserverDeviceTest {
    @Test fun contactHidesTranslationAndReachesAnotherAppsButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
        assertNotEquals(context.applicationInfo.uid, instrumentation.context.applicationInfo.uid)
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,
            TouchProbeActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun waitForText(text: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()?.let { return it }
                SystemClock.sleep(100)
            }
            throw AssertionError("Test activity did not show: $text")
        }
        val bounds = Rect()
        waitForText("TAP HERE").getBoundsInScreen(bounds)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val observed = CountDownLatch(1)
        var watcher: TouchObserver? = null
        var overlay: OverlayView? = null
        instrumentation.runOnMainSync {
            overlay = OverlayView(context).apply {
                setCaptureSize(400, 800)
                setItems(listOf(OverlayView.Item(Rect(10, 100, 180, 130), "Перевод", 0)))
            }
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                alpha = LoopTiming.touchAlpha(context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
            }
            manager.addView(overlay, params)
            watcher = TouchObserver(context) { overlay!!.setItems(emptyList()); observed.countDown() }
            manager.addView(watcher, watcher!!.windowParams())
        }
        try {
            instrumentation.waitForIdleSync()
            // Allow WindowManager to publish the two new input-window handles.
            SystemClock.sleep(250)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event, true)); event.recycle()
            }
            assertTrue("Observer must receive initial contact", observed.await(3, TimeUnit.SECONDS))
            assertEquals("TAPPED", waitForText("TAPPED").text.toString())
            instrumentation.runOnMainSync { assertTrue(overlay!!.itemRects.isEmpty()) }
        } finally {
            instrumentation.runOnMainSync {
                watcher?.let { manager.removeViewImmediate(it) }
                overlay?.let { manager.removeViewImmediate(it) }
            }
        }
    }
}
