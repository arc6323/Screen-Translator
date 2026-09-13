package com.arc6323.screentranslator

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    data class Item(val rect: Rect, val text: String, val background: Int)
    private data class Rendered(val box: RectF, val layout: StaticLayout, val background: Int)
    private var items = emptyList<Item>()
    private var rendered = emptyList<Rendered>()
    private var snapshot: Bitmap? = null
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var captureWidth = 1
    private var captureHeight = 1
    private var screenWidth = 1
    private var screenHeight = 1
    private val location = IntArray(2)
    val itemRects: List<Rect> get() = items.map { Rect(it.rect) }

    fun setCaptureSize(width: Int, height: Int, displayWidth: Int = width, displayHeight: Int = height) {
        captureWidth = width.coerceAtLeast(1)
        captureHeight = height.coerceAtLeast(1)
        screenWidth = displayWidth.coerceAtLeast(1)
        screenHeight = displayHeight.coerceAtLeast(1)
        rebuild()
    }

    fun setItems(next: List<Item>) {
        items = next.toList()
        rebuild()
    }

    /** Transfers ownership. Called on the UI thread after the previous draw has completed. */
    fun setSnapshot(next: Bitmap?) {
        snapshot = next
        // Let the renderer release its reference before GC frees a previously drawn bitmap.
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val minTextSize = 12f * resources.displayMetrics.scaledDensity *
            captureWidth.toFloat() / screenWidth
        rendered = items.mapNotNull { item ->
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0 || item.text.isBlank()) return@mapNotNull null
            val pad = max(2f, minTextSize * 0.18f)
            val box = RectF(
                max(0f, r.left - pad), max(0f, r.top - pad),
                min(captureWidth.toFloat(), r.right + pad), min(captureHeight.toFloat(), r.bottom + pad)
            )
            val available = (box.width() - pad * 2).toInt().coerceAtLeast(1)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                color = if (Color.luminance(item.background) > 0.45f) Color.BLACK else Color.WHITE
            }
            var size = min(28f * resources.displayMetrics.scaledDensity, r.height() * 0.72f)
                .coerceAtLeast(minTextSize)
            fun layout(ellipsize: Boolean): StaticLayout {
                paint.textSize = size
                return StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, available)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.0f)
                    .apply {
                        if (ellipsize) {
                            setMaxLines(max(1, (box.height() / (size * 1.2f)).toInt()))
                            setEllipsize(TextUtils.TruncateAt.END)
                        }
                    }.build()
            }
            var textLayout = layout(false)
            while (textLayout.height > box.height() - pad * 2 && size > minTextSize) {
                size = max(minTextSize, size - 1f)
                textLayout = layout(false)
            }
            if (textLayout.height > box.height() - pad * 2) textLayout = layout(true)
            Rendered(box, textLayout, item.background)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(location)
        val geometry = CaptureGeometry(
            captureWidth, captureHeight, screenWidth, screenHeight, location[0], location[1]
        )
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        canvas.scale(geometry.scaleX, geometry.scaleY)
        snapshot?.takeUnless { it.isRecycled }?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, captureWidth.toFloat(), captureHeight.toFloat()), null)
        }
        for (item in rendered) {
            backgroundPaint.color = item.background
            backgroundPaint.alpha = 255
            canvas.drawRect(item.box, backgroundPaint)
            canvas.save()
            canvas.clipRect(item.box)
            val padding = max(2f, item.layout.paint.textSize * 0.18f)
            canvas.translate(item.box.left + padding,
                item.box.top + max(padding, (item.box.height() - item.layout.height) / 2f))
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
