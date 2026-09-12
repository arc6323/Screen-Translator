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
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class TranslatorService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var overlay: WindowManager? = null
    private val labels = mutableListOf<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var stopped = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7, notification())
        overlay = getSystemService(WINDOW_SERVICE) as WindowManager
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

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(::scan, 1200)
        } catch (e: Throwable) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
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

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    clearOverlay()
                    val lines = result.textBlocks.flatMap { it.lines }.take(30)
                    if (lines.isEmpty()) return@addOnSuccessListener
                    val lid = LanguageIdentification.getClient()
                    lines.forEach { line ->
                        lid.identifyLanguage(line.text).addOnSuccessListener { lang ->
                            val target = Locale.getDefault().language
                            if (lang != "und" && lang != target) translate(line, lang, target)
                        }
                    }
                }
                .addOnCompleteListener {
                    recognizer.close()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    busy = false
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
        if (!stopped) handler.postDelayed(::scan, 1200)
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
            e.printStackTrace()
            null
        }
    }

    private fun translate(line: Text.Line, source: String, target: String) {
        if (source == target || source == "und" || target == "und") return
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(target).build()
            val translator = Translation.getClient(options)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    translator.translate(line.text)
                        .addOnSuccessListener { translated -> addOverlay(line, translated) }
                        .addOnCompleteListener { translator.close() }
                }
                .addOnFailureListener { translator.close() }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun addOverlay(line: Text.Line, text: String) {
        if (text.isBlank() || text.equals(line.text, true) || stopped) return
        val r = line.boundingBox ?: return
        handler.post {
            if (stopped) return@post
            try {
                val tv = TextView(this).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(235, 20, 20, 20))
                    textSize = (r.height() * 0.72f).coerceAtLeast(10f)
                    setPadding(6, 0, 6, 0)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    includeFontPadding = false
                }
                val lp = WindowManager.LayoutParams(
                    r.width().coerceAtLeast(40), r.height().coerceAtLeast(24),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = r.left; lp.y = r.top
                overlay?.addView(tv, lp)
                labels.add(tv)
            } catch (e: Throwable) { e.printStackTrace() }
        }
    }

    private fun clearOverlay() {
        handler.post {
            labels.toList().forEach { view ->
                try { overlay?.removeView(view) } catch (_: Throwable) {}
            }
            labels.clear()
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
        clearOverlay()
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null; reader = null; projection = null
        super.onDestroy()
    }
}
