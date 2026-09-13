package com.arc6323.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    data class Item(val rect: Rect, val text: String, val background: Int)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.NORMAL)
        isDither = true
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var items: List<Item> = emptyList()
    private var captureWidth = 0
    private var captureHeight = 0

    fun setCaptureSize(width: Int, height: Int) {
        captureWidth = width
        captureHeight = height
    }

    fun setItems(newItems: List<Item>) {
        items = newItems.toList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val sx = if (captureWidth > 0) width.toFloat() / captureWidth else 1f
        val sy = if (captureHeight > 0) height.toFloat() / captureHeight else 1f
        canvas.save()
        canvas.scale(sx, sy)

        for (item in items) {
            val src = item.rect
            if (src.width() <= 0 || src.height() <= 0 || item.text.isBlank()) continue

            // Expand the OCR rectangle slightly so the original glyphs are actually covered.
            val padX = max(4, src.height() / 5)
            val padY = max(3, src.height() / 6)
            val left = max(0, src.left - padX)
            val top = max(0, src.top - padY)
            val right = min(captureWidth.coerceAtLeast(width), src.right + padX)
            val bottom = min(captureHeight.coerceAtLeast(height), src.bottom + padY)
            val boxW = max(20, right - left)
            val boxH = max(16, bottom - top)

            val bg = softenBackground(item.background)
            backgroundPaint.color = bg
            backgroundPaint.alpha = 255
            canvas.drawRoundRect(
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                3f, 3f, backgroundPaint
            )

            // Use Android's default sans/Roboto and fit the translated text into the
            // original OCR line instead of drawing an oversized second paragraph.
            var size = (src.height() * 0.78f).coerceIn(11f, 40f)
            textPaint.textSize = size
            val maxTextWidth = (boxW - 8).coerceAtLeast(20).toFloat()
            while (textPaint.measureText(item.text) > maxTextWidth && size > 9f) {
                size -= 0.5f
                textPaint.textSize = size
            }
            textPaint.color = if (isLight(bg)) Color.BLACK else Color.WHITE

            val fm = textPaint.fontMetrics
            val baseline = top + boxH / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(item.text, (left + 4).toFloat(), baseline, textPaint)
        }
        canvas.restore()
    }

    private fun softenBackground(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Move extreme colors slightly toward neutral to make the replacement box
        // less visible while still covering the original glyphs.
        val avg = (r + g + b) / 3
        return Color.rgb(
            ((r * 3 + avg) / 4).coerceIn(0, 255),
            ((g * 3 + avg) / 4).coerceIn(0, 255),
            ((b * 3 + avg) / 4).coerceIn(0, 255)
        )
    }

    private fun isLight(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 160
}
