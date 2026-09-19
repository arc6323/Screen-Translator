package com.arc6323.screentranslator

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayDeviceTest {
    @Test fun glyphHeightMatchesSourceAndDimmingSurvivesTextHiding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val source = "Good evening"
            val bounds = Rect()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; typeface = Typeface.DEFAULT }
            paint.getTextBounds(source, 0, source.length, bounds)
            assertEquals(36f, OverlayView.sourceTextSize(source, bounds.height()), 2f)
            val view = OverlayView(instrumentation.targetContext)
            view.setCaptureSize(400, 300); view.layout(0, 0, 400, 300)
            view.setItems(listOf(OverlayView.Item(Rect(10, 40, 200, 70), "Добрый вечер", Color.BLACK, source, Rect(10, 40, 390, 100))))
            val visible = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(visible))
            val first = visible.getPixel(300, 200)
            assertTrue(Color.alpha(first) > 0)
            view.setTextVisible(false)
            val hidden = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(hidden))
            assertEquals(first, hidden.getPixel(300, 200))
            assertEquals(first, hidden.getPixel(20, 50))
            assertTrue(Color.alpha(visible.getPixel(20, 50)) > Color.alpha(first))
            visible.recycle(); hidden.recycle()
        }
    }
}
