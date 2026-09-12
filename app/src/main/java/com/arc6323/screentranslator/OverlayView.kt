package com.arc6323.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    data class Item(val rect: Rect, val text: String, val background: Int)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var items: List<Item> = emptyList()

    fun setItems(newItems: List<Item>) {
        items = newItems.toList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0 || item.text.isBlank()) continue
            val size = (r.height() * 0.72f).coerceIn(12f, 42f)
            textPaint.textSize = size
            textPaint.color = if (isLight(item.background)) Color.BLACK else Color.WHITE
            val desired = textPaint.measureText(item.text) + 14f
            val available = (width - r.left - 4).coerceAtLeast(40)
            val layoutWidth = min(max(r.width(), desired.toInt()), available)
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            val boxHeight = max(r.height(), layout.height + 8)
            val right = min(r.left + layoutWidth, width - 2)
            val bottom = min(r.top + boxHeight, height - 2)
            backgroundPaint.color = Color.rgb(Color.red(item.background), Color.green(item.background), Color.blue(item.background))
            canvas.drawRoundRect(r.left.toFloat(), r.top.toFloat(), right.toFloat(), bottom.toFloat(), 4f, 4f, backgroundPaint)
            canvas.save()
            canvas.translate((r.left + 6).toFloat(), (r.top + (boxHeight - layout.height) / 2f))
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun isLight(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 160
}
