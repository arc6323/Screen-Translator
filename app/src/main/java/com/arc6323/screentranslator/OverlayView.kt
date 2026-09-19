package com.arc6323.screentranslator

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max

class OverlayView(context: Context) : View(context) {
    data class Item(
        val rect: Rect, val text: String, val background: Int,
        val sourceText: String = text, val available: Rect = rect
    )
    private data class Rendered(val box: RectF, val layout: StaticLayout, val top: Float)
    private var items = emptyList<Item>()
    private var rendered = emptyList<Rendered>()
    private val backgroundPaint = Paint().apply { color = Color.BLACK }
    private var textVisible = true
    private var captureWidth = 1
    private var captureHeight = 1
    private var screenWidth = 1
    private var screenHeight = 1
    private var content = Rect()
    private val location = IntArray(2)
    val itemRects: List<Rect> get() = items.map { Rect(it.rect) }

    fun setCaptureSize(width: Int, height: Int, displayWidth: Int = width, displayHeight: Int = height) {
        captureWidth = width.coerceAtLeast(1)
        captureHeight = height.coerceAtLeast(1)
        screenWidth = displayWidth.coerceAtLeast(1)
        screenHeight = displayHeight.coerceAtLeast(1)
        content = Rect(0, 0, captureWidth, captureHeight)
        rebuild()
    }
    fun setContentBounds(bounds: Rect) { content = Rect(bounds); postInvalidateOnAnimation() }
    fun setTextVisible(visible: Boolean) { textVisible = visible; postInvalidateOnAnimation() }
    fun setItems(next: List<Item>) { items = next.toList(); rebuild() }

    companion object {
        /** OCR reports ink bounds, not font points. Preserve the source's glyph height. */
        fun sourceTextSize(text: String, glyphHeight: Int): Float {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f; typeface = Typeface.DEFAULT }
            val bounds = Rect()
            val sample = text.ifBlank { "Ag" }
            paint.getTextBounds(sample, 0, sample.length, bounds)
            return (100f * glyphHeight / bounds.height().coerceAtLeast(1)).coerceIn(8f, 180f)
        }
    }

    private fun rebuild() {
        rendered = items.mapNotNull { item ->
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0 || item.text.isBlank()) return@mapNotNull null
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT
                color = Color.WHITE
                textSize = sourceTextSize(item.sourceText, r.height())
            }
            val width = (item.available.right - r.left).coerceAtLeast(r.width())
            val lineHeight = paint.fontMetrics.let { it.descent - it.ascent }
            val maxLines = max(1, ((item.available.bottom - r.top) / lineHeight).toInt())
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f, 1f).setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END).build()
            val glyph = Rect()
            val firstLine = item.text.substring(0, layout.getLineEnd(0).coerceAtMost(item.text.length))
            paint.getTextBounds(firstLine, 0, firstLine.length, glyph)
            val top = r.top - (layout.getLineBaseline(0) + glyph.top).toFloat()
            val textBottom = top + layout.getLineBaseline(layout.lineCount - 1) + paint.fontMetrics.descent
            val box = RectF((r.left - 2).coerceAtLeast(0).toFloat(), (r.top - 2).coerceAtLeast(0).toFloat(),
                item.available.right.coerceAtMost(captureWidth).toFloat(),
                max(r.bottom.toFloat(), textBottom).coerceAtMost(item.available.bottom.toFloat()))
            Rendered(box, layout, top)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(location)
        val geometry = CaptureGeometry(captureWidth, captureHeight, screenWidth, screenHeight, location[0], location[1])
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        canvas.scale(geometry.scaleX, geometry.scaleY)
        // This layer stays present during OCR, movement and first translation: no brightness flashing.
        backgroundPaint.alpha = 85
        canvas.drawRect(content, backgroundPaint)
        if (textVisible) for (item in rendered) {
            backgroundPaint.alpha = 255
            canvas.drawRect(item.box, backgroundPaint)
            canvas.save()
            canvas.clipRect(item.box)
            canvas.translate(item.box.left + if (item.box.left > 0) 2f else 0f, item.top)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
