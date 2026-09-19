package com.arc6323.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface

/** Owns one latest Image on its worker; a static screen does not have to produce another frame. */
class CaptureEngine(private val main: Handler, private val sceneChanged: () -> Unit) : AutoCloseable {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile private var reader: ImageReader? = null
    private var latest: Image? = null
    private data class Request(
        val token: Long, val afterTimestamp: Long, val requireFresh: Boolean,
        val result: (Bitmap) -> Unit, var armed: Boolean = false
    )
    private data class Fingerprint(val width: Int, val height: Int, val shades: IntArray)
    private var pending: Request? = null
    private var watching: Fingerprint? = null
    private var masks = emptyList<Rect>()
    private val columns = 80
    private val rows = 144
    private var suppressUntil = 0L
    private var lastMotion = 0L

    fun attach(width: Int, height: Int): Surface {
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val previous = reader
        reader = next
        worker.post {
            latest?.close()
            latest = null
            previous?.close()
            pending = null
            watching = null
            next.setOnImageAvailableListener({ source -> read(source) }, worker)
        }
        return next.surface
    }
    fun request(token: Long, requireFresh: Boolean, prepared: () -> Unit, result: (Bitmap) -> Unit) {
        worker.post {
            if (closed) return@post
            pending = Request(token, latest?.timestamp ?: Long.MIN_VALUE, requireFresh, result)
            main.post { if (!closed) prepared() }
        }
    }
    fun arm(token: Long) {
        worker.post {
            try { pending?.takeIf { it.token == token }?.let { it.armed = true; deliver(it) } }
            catch (_: RuntimeException) { /* A later producer frame or timeout will retry. */ }
        }
    }
    fun cancel(token: Long) { worker.post { if (pending?.token == token) pending = null } }
    fun setMasks(rects: List<Rect>) {
        val copy = rects.map { Rect(it).apply { inset(-16, -16) } }
        worker.post { masks = copy }
    }
    fun stopMonitoring() { worker.post { watching = null } }
    fun noteOverlayChange() {
        worker.post { watching = null; suppressUntil = SystemClock.uptimeMillis() + 100 }
    }

    private fun read(source: ImageReader) {
        var acquired: Image? = null
        try {
            acquired = source.acquireLatestImage() ?: return
            if (closed || source !== reader) return
            latest?.close()
            latest = acquired
            acquired = null
            val now = SystemClock.uptimeMillis()
            val next = fingerprint(latest!!)
            val reference = watching
            watching = next
            if (now >= suppressUntil && reference != null && differs(next, reference) && now - lastMotion >= 50) {
                lastMotion = now
                main.post { if (!closed) sceneChanged() }
            }
            pending?.let { deliver(it) }
        } catch (_: RuntimeException) {
            // Surface replacement and capture shutdown race with producer callbacks.
        } finally { acquired?.close() }
    }
    private fun deliver(request: Request) {
        val image = latest ?: return
        if (!request.armed || pending !== request ||
            (request.requireFresh && image.timestamp <= request.afterTimestamp)) return
        val bitmap = toBitmap(image)
        pending = null
        main.post { if (closed) bitmap.recycle() else request.result(bitmap) }
    }

    private fun fingerprint(image: Image): Fingerprint {
        val shades = IntArray(columns * rows)
        val plane = image.planes[0]
        val buffer = plane.buffer
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = ((column + 0.5f) * image.width / columns).toInt().coerceIn(0, image.width - 1)
            val y = ((row + 0.5f) * image.height / rows).toInt().coerceIn(0, image.height - 1)
            val offset = y * plane.rowStride + x * plane.pixelStride
            if (offset + 2 < buffer.limit()) shades[row * columns + column] =
                ((buffer.get(offset).toInt() and 255) + (buffer.get(offset + 1).toInt() and 255) +
                    (buffer.get(offset + 2).toInt() and 255)) / 3
        }
        return Fingerprint(image.width, image.height, shades)
    }

    private fun differs(next: Fingerprint, reference: Fingerprint): Boolean {
        if (next.width != reference.width || next.height != reference.height) return true
        var compared = 0
        var changed = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = ((column + 0.5f) * next.width / columns).toInt()
            val y = ((row + 0.5f) * next.height / rows).toInt()
            if (masks.any { it.contains(x, y) }) continue
            compared++
            val index = row * columns + column
            if (kotlin.math.abs(next.shades[index] - reference.shades[index]) > 12) changed++
        }
        return compared > 20 && changed > maxOf(10, compared / 100)
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        require(plane.pixelStride == 4)
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        try {
            val input = plane.buffer
            input.rewind()
            if (input.remaining() >= padded.byteCount) padded.copyPixelsFromBuffer(input)
            else {
                // Some devices omit padding after the last row in the exposed buffer.
                val filled = java.nio.ByteBuffer.allocate(padded.byteCount)
                filled.put(input)
                filled.rewind()
                padded.copyPixelsFromBuffer(filled)
            }
            if (paddedWidth == image.width) return padded
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (error: RuntimeException) {
            padded.recycle()
            throw error
        }
    }

    override fun close() {
        closed = true
        worker.post {
            pending = null
            watching = null
            latest?.close()
            latest = null
            reader?.close()
            reader = null
            thread.quitSafely()
        }
    }
}
