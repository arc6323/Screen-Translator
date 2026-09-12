package com.arc6323.screentranslator

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class TranslatorService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var overlayView: TranslationOverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var stopped = false
    private var frameId = 0L
    private val cachedLanguages = mutableSetOf<String>()
    private val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = TranslationOverlayView(this)
        refreshCachedLanguages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) return START_NOT_STICKY
        try {
            val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                ?: return START_NOT_STICKY
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            } ?: return START_NOT_STICKY

            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(code, data) ?: throw IllegalStateException("MediaProjection unavailable")
            projection?.registerCallback(projectionCallback, handler)

            val dm = resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels
            display?.release()
            reader?.close()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            display = projection?.createVirtualDisplay(
                "ScreenTranslator", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler
            ) ?: throw IllegalStateException("Virtual display unavailable")

            attachOverlay()
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(::scan, 1000)
        } catch (e: Throwable) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun attachOverlay() {
        if (overlayView == null || windowManager == null) return
        try {
            if (overlayView?.windowToken != null) return
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                dm.widthPixels,
                dm.heightPixels,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            windowManager?.addView(overlayView, lp)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun refreshCachedLanguages() {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                cachedLanguages.clear()
                models.forEach { cachedLanguages.add(it.language) }
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    private fun scan() {
        if (stopped) return
        var image: Image? = null
        try {
            image = reader?.acquireLatestImage()
            if (image == null || busy) {
                image?.close()
                scheduleNext()
                return
            }
            busy = true
            val bitmap = imageToBitmap(image)
            image.close(); image = null
            if (bitmap == null) {
                busy = false; scheduleNext(); return
            }

            val currentFrame = ++frameId
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (stopped || currentFrame != frameId) return@addOnSuccessListener
                    val lines = result.textBlocks.flatMap { it.lines }.take(20)
                    if (lines.isEmpty()) {
                        handler.post { overlayView?.setItems(emptyList()) }
                        return@addOnSuccessListener
                    }
                    val lid = LanguageIdentification.getClient()
                    val translated = mutableListOf<TranslationItem>()
                    var pending = lines.size
                    lines.forEach { line ->
                        lid.identifyLanguage(line.text).addOnSuccessListener { lang ->
                            val target = Locale.getDefault().language
                            if (lang != "und" && lang != target && cachedLanguages.contains(lang)) {
                                translate(line, lang, target, currentFrame) { item ->
                                    if (item != null && currentFrame == frameId) translated.add(item)
                                    pending--
                                    if (pending <= 0 && currentFrame == frameId) {
                                        handler.post { overlayView?.setItems(translated.toList()) }
                                    }
                                }
                            } else {
                                pending--
                                if (pending <= 0 && currentFrame == frameId) {
                                    handler.post { overlayView?.setItems(translated.toList()) }
                                }
                            }
                        }.addOnFailureListener {
                            pending--
                            if (pending <= 0 && currentFrame == frameId) {
                                handler.post { overlayView?.setItems(translated.toList()) }
                            }
                        }
                    }
                }
                .addOnFailureListener { it.printStackTrace() }
                .addOnCompleteListener {
                    recognizer.close()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    busy = false
                    refreshCachedLanguages()
                    scheduleNext()
                }
        } catch (e: Throwable) {
            e.printStackTrace()
            try { image?.close() } catch (_: Throwable) {}
            busy = false
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (!stopped) handler.postDelayed(::scan, 1600)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride < pixelStride * image.width) return null
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == image.width) padded else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()
                cropped
            }
        } catch (e: Throwable) {
            e.printStackTrace(); null
        }
    }

    private fun translate(line: Text.Line, source: String, target: String, frame: Long, done: (TranslationItem?) -> Unit) {
        if (source == target || source == "und" || target == "und" || !cachedLanguages.contains(source)) {
            done(null); return
        }
        try {
            val key = "$source->$target"
            val translator = translators.getOrPut(key) {
                Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(source).setTargetLanguage(target).build())
            }
            translator.translate(line.text)
                .addOnSuccessListener { translated ->
                    if (frame != frameId) done(null) else done(TranslationItem(line.boundingBox, translated))
                }
                .addOnFailureListener { done(null) }
        } catch (e: Throwable) {
            e.printStackTrace(); done(null)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("translator", "Screen Translator", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification = Notification.Builder(this, "translator")
        .setContentTitle("Screen Translator")
        .setContentText("Live-перевод экрана включён")
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try { windowManager?.removeView(overlayView) } catch (_: Throwable) {}
        translators.values.forEach { try { it.close() } catch (_: Throwable) {} }
        translators.clear()
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null; reader = null; projection = null; overlayView = null
        super.onDestroy()
    }

    data class TranslationItem(val rect: Rect?, val text: String)

    private class TranslationOverlayView(context: Context) : View(context) {
        private val items = mutableListOf<TranslationItem>()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
        }

        fun setItems(newItems: List<TranslationItem>) {
            items.clear()
            items.addAll(newItems.filter { it.rect != null && it.text.isNotBlank() })
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            items.forEach { item ->
                val r = item.rect ?: return@forEach
                val left = max(0, r.left - 5).toFloat()
                val top = max(0, r.top - 4).toFloat()
                val right = min(width, r.right + 5).toFloat()
                val bottom = min(height, r.bottom + 4).toFloat()
                bgPaint.color = Color.argb(242, 25, 25, 25)
                canvas.drawRect(left, top, right, bottom, bgPaint)

                var size = (r.height() * 0.72f).coerceIn(10f, 42f)
                textPaint.color = Color.WHITE
                textPaint.textSize = size
                val maxWidth = max(40f, right - left - 10f)
                while (textPaint.measureText(item.text) > maxWidth && size > 9f) {
                    size -= 1f
                    textPaint.textSize = size
                }
                val fm = textPaint.fontMetrics
                val baseline = top + (bottom - top - fm.bottom - fm.top) / 2f
                canvas.drawText(item.text, left + 5f, baseline, textPaint)
            }
        }
    }
}
