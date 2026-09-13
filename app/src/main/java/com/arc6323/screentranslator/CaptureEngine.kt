package com.arc6323.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/** Owns image buffers on a worker thread and keeps draining the producer between requests. */
class CaptureEngine(
    private val main: Handler,
    private val sceneChanged: () -> Unit
) : AutoCloseable {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile private var reader: ImageReader? = null
    private data class Request(
        val token: Long,
        val afterTimestamp: Long,
        val result: (Bitmap) -> Unit,
        var armed: Boolean = false,
        var candidate: Bitmap? = null
    )
    private data class Fingerprint(val width: Int, val height: Int, val shades: IntArray)
    private var pending: Request? = null
    private var lastTimestamp = Long.MIN_VALUE
    private var watching: Fingerprint? = null
    private var masks = emptyList<Rect>()
    private val columns = 24
    private val rows = 40

    fun attach(width: Int, height: Int): Surface {
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val previous = reader
        reader = next
        next.setOnImageAvailableListener({ source -> read(source) }, worker)
        worker.post {
            previous?.close()
            pending?.candidate?.recycle()
            pending = null
            watching = null
            lastTimestamp = Long.MIN_VALUE
        }
        return next.surface
    }

    /** Prepared runs on main before the UI hides its windows. */
    fun request(token: Long, prepared: () -> Unit, result: (Bitmap) -> Unit) {
        worker.post {
            if (closed) return@post
            pending?.candidate?.recycle()
            pending = null
            watching = null
            try {
                reader?.acquireLatestImage()?.use { lastTimestamp = maxOf(lastTimestamp, it.timestamp) }
            } catch (_: IllegalStateException) {}
            pending = Request(token, lastTimestamp, result)
            main.post { if (!closed) prepared() }
        }
    }

    /** Called after two UI animation frames, not after an arbitrary sleep. */
    fun arm(token: Long) {
        worker.post {
            pending?.takeIf { it.token == token }?.let {
                it.armed = true
                deliver(it)
            }
        }
    }

    fun cancel(token: Long) {
        worker.post {
            if (pending?.token == token) {
                pending?.candidate?.recycle()
                pending = null
            }
        }
    }

    fun setMasks(rects: List<Rect>) {
        val copy = rects.map { Rect(it).apply { inset(-16, -16) } }
        worker.post { masks = copy }
    }

    fun stopMonitoring() { worker.post { watching = null } }

    private fun read(source: ImageReader) {
        var image: Image? = null
        try {
            image = source.acquireLatestImage() ?: return
            if (closed || source !== reader) return
            lastTimestamp = maxOf(lastTimestamp, image.timestamp)
            val request = pending
            if (request != null) {
                if (image.timestamp <= request.afterTimestamp) return
                val bitmap = toBitmap(image)
                request.candidate?.recycle()
                request.candidate = bitmap
                deliver(request)
            } else {
                val reference = watching
                if (reference != null && differs(image, reference)) {
                    watching = null
                    main.post { if (!closed) sceneChanged() }
                }
            }
        } catch (_: IllegalStateException) {
            // A surface may be replaced during rotation. The UI request has a bounded timeout.
        } catch (_: RuntimeException) {
            // Buffer errors are reported by the same timeout, without wedging the producer.
        } finally {
            image?.close()
        }
    }

    private fun deliver(request: Request) {
        val bitmap = request.candidate ?: return
        if (!request.armed || pending !== request) return
        pending = null
        request.candidate = null
        watching = fingerprint(bitmap)
        main.post {
            if (closed) bitmap.recycle() else request.result(bitmap)
        }
    }

    private fun fingerprint(bitmap: Bitmap): Fingerprint {
        val shades = IntArray(columns * rows)
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = ((column + 0.5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
            val y = ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            shades[row * columns + column] = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        }
        return Fingerprint(bitmap.width, bitmap.height, shades)
    }

    private fun differs(image: Image, reference: Fingerprint): Boolean {
        if (image.width != reference.width || image.height != reference.height) return true
        val plane = image.planes[0]
        val buffer = plane.buffer
        var compared = 0
        var changed = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = ((column + 0.5f) * image.width / columns).toInt().coerceIn(0, image.width - 1)
            val y = ((row + 0.5f) * image.height / rows).toInt().coerceIn(0, image.height - 1)
            if (masks.any { it.contains(x, y) }) continue
            val offset = y * plane.rowStride + x * plane.pixelStride
            if (offset < 0 || offset + 2 >= buffer.limit()) continue
            val shade = ((buffer.get(offset).toInt() and 255) +
                (buffer.get(offset + 1).toInt() and 255) + (buffer.get(offset + 2).toInt() and 255)) / 3
            compared++
            if (kotlin.math.abs(shade - reference.shades[row * columns + column]) > 30) changed++
        }
        return compared > 20 && changed > maxOf(8, compared / 6)
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
            pending?.candidate?.recycle()
            pending = null
            watching = null
            reader?.close()
            reader = null
            thread.quitSafely()
        }
    }
}
