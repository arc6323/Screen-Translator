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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    private val downloadedLanguages = mutableSetOf<String>()
    private val translators = ConcurrentHashMap<String, Translator>()
    private var recognizer: TextRecognizer? = null
    private var languageIdentifier: LanguageIdentifier? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        languageIdentifier = LanguageIdentification.getClient()
        overlayView = TranslationOverlayView(this)
        refreshDownloadedLanguages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) return START_NOT_STICKY
        try {
            val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            if (data == null) return START_NOT_STICKY

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
            handler.postDelayed(::scan, 900)
        } catch (e: Throwable) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun attachOverlay() {
        try {
            val view = overlayView ?: return
            if (view.windowToken != null) return
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                dm.widthPixels, dm.heightPixels,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            windowManager?.addView(view, lp)
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun refreshDownloadedLanguages() {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                downloadedLanguages.clear()
                downloadedLanguages.addAll(models.map { it.language })
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    private fun scan() {
        if (stopped) return
        var image: Image? = null
        var bitmap: Bitmap? = null
        val currentFrame = ++frameId
        try {
            image = reader?.acquireLatestImage()
            if (image == null || busy) {
                image?.close()
                scheduleNext()
                return
            }
            busy = true
            bitmap = imageToBitmap(image)
            image.close(); image = null
            if (bitmap == null) { busy = false; scheduleNext(); return }

            overlayView?.setItems(emptyList())
            val frameBitmap = bitmap
            recognizer?.process(InputImage.fromBitmap(frameBitmap, 0))
                ?.addOnSuccessListener { result ->
                    if (stopped || currentFrame != frameId) return@addOnSuccessListener
                    val lines = result.textBlocks.flatMap { it.lines }
                        .filter { it.text.trim().length >= 2 && it.boundingBox != null }
                        .take(18)
                    lines.forEach { line -> identifyAndTranslate(line, frameBitmap, currentFrame) }
                }
                ?.addOnCompleteListener {
                    if (!frameBitmap.isRecycled) frameBitmap.recycle()
                    busy = false
                    scheduleNext()
                }
                ?: run {
                    frameBitmap.recycle(); busy = false; scheduleNext()
                }
        } catch (e: Throwable) {
            e.printStackTrace()
            try { image?.close() } catch (_: Throwable) {}
            try { bitmap?.recycle() } catch (_: Throwable) {}
            busy = false
            scheduleNext()
        }
    }

    private fun identifyAndTranslate(line: Text.Line, bitmap: Bitmap, frame: Long) {
        val sourceText = line.text.trim()
        val target = Locale.getDefault().language.lowercase(Locale.ROOT)
        languageIdentifier?.identifyLanguage(sourceText)
            ?.addOnSuccessListener { source ->
                if (stopped || frame != frameId || source == "und" || source == target) return@addOnSuccessListener
                val selected = getSharedPreferences("settings", MODE_PRIVATE)
                    .getStringSet("cached_sources", setOf("en")) ?: emptySet()
                if (!selected.contains(source)) return@addOnSuccessListener
                if (!downloadedLanguages.contains(source) || !downloadedLanguages.contains(target)) return@addOnSuccessListener
                val rect = line.boundingBox ?: return@addOnSuccessListener
                val background = sampleBackground(bitmap, rect)
                translate(line, source, target, frame, rect, background)
            }
    }

    private fun translate(line: Text.Line, source: String, target: String, frame: Long, rect: Rect, background: Int) {
        val sourceLang = TranslateLanguage.fromLanguageTag(source) ?: return
        val targetLang = TranslateLanguage.fromLanguageTag(target) ?: return
        try {
            val key = "$source->$target"
            val translator = translators.getOrPut(key) {
                Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang).setTargetLanguage(targetLang).build())
            }
            translator.translate(line.text)
                .addOnSuccessListener { translated ->
                    if (stopped || frame != frameId || translated.isBlank()) return@addOnSuccessListener
                    overlayView?.putItem(TranslationItem(rect, translated, background))
                }
                .addOnFailureListener { }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun sampleBackground(bitmap: Bitmap, rect: Rect): Int {
        val left = max(0, rect.left - 3)
        val right = min(bitmap.width - 1, rect.right + 3)
        val top = max(0, rect.top - 3)
        val bottom = min(bitmap.height - 1, rect.bottom + 3)
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        fun add(x: Int, y: Int) { val c = bitmap.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++ }
        for (x in left..right) { add(x, top); add(x, bottom) }
        for (y in top..bottom) { add(left, y); add(right, y) }
        return if (count == 0L) Color.rgb(25, 25, 25) else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun scheduleNext() { if (!stopped) handler.postDelayed(::scan, 1200) }

    private fun imageToBitmap(image: Image): Bitmap? = try {
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
        if (paddedWidth == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    } catch (e: Throwable) { e.printStackTrace(); null }

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
        recognizer?.close()
        languageIdentifier?.close()
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null; reader = null; projection = null; overlayView = null
        super.onDestroy()
    }

    data class TranslationItem(val rect: Rect, val text: String, val background: Int)

    private class TranslationOverlayView(context: Context) : View(context) {
        private val items = LinkedHashMap<String, TranslationItem>()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
        }

        fun setItems(newItems: List<TranslationItem>) {
            items.clear()
            newItems.forEach { items[key(it)] = it }
            invalidate()
        }

        fun putItem(item: TranslationItem) {
            items[key(item)] = item
            invalidate()
        }

        private fun key(item: TranslationItem): String = "${item.rect.left}:${item.rect.top}:${item.rect.right}:${item.rect.bottom}"

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            items.values.toList().forEach { item ->
                val r = item.rect
                val left = max(0, r.left - 5).toFloat()
                val top = max(0, r.top - 4).toFloat()
                val right = min(width, r.right + 5).toFloat()
                val bottom = min(height, r.bottom + 4).toFloat()
                bgPaint.color = item.background
                canvas.drawRect(left, top, right, bottom, bgPaint)

                var size = (r.height() * 0.72f).coerceIn(10f, 42f)
                textPaint.color = if (isLight(item.background)) Color.BLACK else Color.WHITE
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

        private fun isLight(color: Int): Boolean =
            (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 160
    }
}
