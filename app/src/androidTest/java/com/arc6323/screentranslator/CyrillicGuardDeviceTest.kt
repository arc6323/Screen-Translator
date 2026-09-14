package com.arc6323.screentranslator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CyrillicGuardDeviceTest {
    private fun line(text: String, dark: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) Color.BLACK else Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.BLACK
            textSize = 44f
        }
        canvas.drawText(text, 10f, 64f, paint)
        return bitmap
    }
    @Test fun russianUiIsRejectedOnLightAndDarkBackgrounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CyrillicGuard(context).use { guard ->
            for (dark in listOf(false, true)) {
                for (text in listOf("Домой", "Мои проекты", "Напиши на русском", "Обзор Текст песни")) {
                    val bitmap = line(text, dark)
                    try {
                        guard.beginFrame()
                        assertEquals(text, false, guard.allows(bitmap, Rect(0, 0, bitmap.width, bitmap.height), text + dark))
                    } finally { guard.endFrame(); bitmap.recycle() }
                }
            }
        }
    }
    @Test fun realEnglishRemainsAvailableForTranslation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CyrillicGuard(context).use { guard ->
            for (text in listOf("Home", "Pull requests", "Hello from the other side")) {
                val bitmap = line(text, true)
                try {
                    guard.beginFrame()
                    assertEquals(text, true, guard.allows(bitmap, Rect(0, 0, bitmap.width, bitmap.height), text))
                } finally { guard.endFrame(); bitmap.recycle() }
            }
        }
    }
}
