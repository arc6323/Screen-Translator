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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

class TranslatorService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var overlayWindow: WindowManager? = null
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var stopped = false
    private var generation = 0L

    private lateinit var latin: TextRecognizer
    private lateinit var chinese: TextRecognizer
    private lateinit var devanagari: TextRecognizer
    private lateinit var japanese: TextRecognizer
    private lateinit var korean: TextRecognizer
    private lateinit var languageId: LanguageIdentifier

    private val translators = mutableMapOf<String, Translator>()
    private val readyPairs = mutableSetOf<String>()
    private val translationCache = object : LinkedHashMap<String, String>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7, notification())
        overlayWindow = getSystemService(WINDOW_SERVICE) as WindowManager
        latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        languageId = LanguageIdentification.getClient()
        overlayView = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try { overlayWindow?.addView(overlayView, lp) } catch (e: Throwable) { e.printStackTrace() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) return START_NOT_STICKY
        try {
            val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                ?: return START_NOT_STICKY
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            if (data == null) return START_NOT_STICKY
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection?.let { old ->
                try { old.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
                try { old.stop() } catch (_: Throwable) {}
            }
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

            generation++
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(::scan, 1000)
        } catch (e: Throwable) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun scan() {
        if (stopped || busy) { scheduleNext(); return }
        var image: Image? = null
        val thisGeneration = ++generation
        try {
            image = reader?.acquireLatestImage()
            if (image == null) { scheduleNext(); return }
            busy = true
            val bitmap = imageToBitmap(image)
            image.close(); image = null
            if (bitmap == null) { busy = false; scheduleNext(); return }

            val selected = LanguageCacheManager.selected(this)
            val recognizers = mutableListOf<TextRecognizer>()
            recognizers.add(latin)
            if ("zh" in selected) recognizers.add(chinese)
            if ("hi" in selected) recognizers.add(devanagari)
            if ("ja" in selected) recognizers.add(japanese)
            if ("ko" in selected) recognizers.add(korean)

            val allLines = java.util.Collections.synchronizedList(mutableListOf<Text.Line>())
            val pendingRecognizers = AtomicInteger(recognizers.size)
            recognizers.forEach { recognizer ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> allLines.addAll(result.textBlocks.flatMap { it.lines }) }
                    .addOnCompleteListener {
                        if (pendingRecognizers.decrementAndGet() == 0) {
                            processLines(bitmap, allLines.distinctBy { "${it.text}|${it.boundingBox}" }, selected, thisGeneration)
                        }
                    }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            try { image?.close() } catch (_: Throwable) {}
            busy = false
            scheduleNext()
        }
    }

    private fun processLines(bitmap: Bitmap, rawLines: List<Text.Line>, selected: Set<String>, thisGeneration: Long) {
        val lines = rawLines.filter { it.text.trim().length >= 2 }.take(30)
        val target = LanguageCacheManager.targetLanguage()
        if (lines.isEmpty()) {
            handler.post { if (thisGeneration == generation) overlayView?.setItems(emptyList()) }
            finishFrame(bitmap)
            return
        }

        val items = java.util.Collections.synchronizedList(mutableListOf<OverlayView.Item>())
        val pending = AtomicInteger(lines.size)
        lines.forEach { line ->
            languageId.identifyLanguage(line.text)
                .addOnSuccessListener { source ->
                    val rect = line.boundingBox ?: Rect()
                    if (rect.width() > 0 && rect.height() > 0 && source in selected && source != target) {
                        translateLine(line.text, source, target, thisGeneration) { translated ->
                            if (translated != null) items.add(OverlayView.Item(Rect(rect), translated))
                            completeLine(pending, items, thisGeneration)
                        }
                    } else {
                        completeLine(pending, items, thisGeneration)
                    }
                }
                .addOnFailureListener { completeLine(pending, items, thisGeneration) }
        }
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun completeLine(pending: AtomicInteger, items: MutableList<OverlayView.Item>, thisGeneration: Long) {
        if (pending.decrementAndGet() == 0) {
            handler.post {
                if (!stopped && thisGeneration == generation) {
                    overlayView?.setItems(items.sortedWith(compareBy({ it.rect.top }, { it.rect.left })))
                }
            }
            busy = false
            scheduleNext()
        }
    }

    private fun translateLine(text: String, source: String, target: String, thisGeneration: Long, done: (String?) -> Unit) {
        if (thisGeneration != generation) { done(null); return }
        val key = "$source|$target|$text"
        translationCache[key]?.let { done(it); return }
        try {
            val pair = "$source->$target"
            val translator = translators.getOrPut(pair) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                )
            }
            fun translateNow() {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        if (thisGeneration == generation && translated.isNotBlank()) {
                            translationCache[key] = translated
                            done(translated)
                        } else done(null)
                    }
                    .addOnFailureListener { done(null) }
            }
            if (pair in readyPairs) translateNow()
            else translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { readyPairs.add(pair); translateNow() }
                .addOnFailureListener { done(null) }
        } catch (e: Throwable) {
            e.printStackTrace()
            done(null)
        }
    }

    private fun finishFrame(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
        busy = false
        scheduleNext()
    }

    private fun scheduleNext() { if (!stopped) handler.postDelayed(::scan, 1600) }

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
        try { overlayWindow?.removeView(overlayView) } catch (_: Throwable) {}
        translators.values.forEach { try { it.close() } catch (_: Throwable) {} }
        translators.clear()
        try { latin.close() } catch (_: Throwable) {}
        try { chinese.close() } catch (_: Throwable) {}
        try { devanagari.close() } catch (_: Throwable) {}
        try { japanese.close() } catch (_: Throwable) {}
        try { korean.close() } catch (_: Throwable) {}
        try { languageId.close() } catch (_: Throwable) {}
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null; reader = null; projection = null; overlayView = null
        super.onDestroy()
    }
}
