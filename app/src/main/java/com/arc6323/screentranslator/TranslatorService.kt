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
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TranslatorService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var overlay: WindowManager? = null
    private val labels = mutableListOf<TextView>()
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() { super.onCreate(); createChannel(); startForeground(7, notification()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        projection?.createVirtualDisplay("ScreenTranslator", dm.widthPixels, dm.heightPixels, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        overlay = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.postDelayed(::scan, 700)
        return START_STICKY
    }

    private fun scan() {
        val image = reader?.acquireLatestImage()
        if (image != null && !busy) {
            busy = true
            val buffer = image.planes[0].buffer
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind(); bitmap.copyPixelsFromBuffer(buffer); image.close()
            val input = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(input).addOnSuccessListener { result ->
                clearOverlay()
                val lines = result.textBlocks.flatMap { it.lines }.take(30)
                val lid = LanguageIdentification.getClient()
                lines.forEach { line ->
                    lid.identifyLanguage(line.text).addOnSuccessListener { lang ->
                        if (lang != "und" && lang != java.util.Locale.getDefault().language) translate(line, lang)
                    }
                }
            }.addOnCompleteListener { recognizer.close(); bitmap.recycle(); busy = false; handler.postDelayed(::scan, 900) }
        } else { image?.close(); handler.postDelayed(::scan, 900) }
    }

    private fun translate(line: Text.Line, source: String) {
        val target = java.util.Locale.getDefault().language
        if (target == source || source == "und") return
        val options = TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        val translator = Translation.getClient(options)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {
            translator.translate(line.text).addOnSuccessListener { translated -> addOverlay(line, translated) }
                .addOnCompleteListener { translator.close() }
        }.addOnFailureListener { translator.close() }
    }

    private fun addOverlay(line: Text.Line, text: String) {
        if (text.isBlank() || text.equals(line.text, true)) return
        handler.post {
            val r = line.boundingBox ?: return@post
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(225, 20,20,20))
                textSize = (r.height() * 0.72f).coerceAtLeast(10f)
                setPadding(4,0,4,0); gravity = Gravity.CENTER_VERTICAL; maxLines = 2
            }
            val p = WindowManager.LayoutParams(r.width().coerceAtLeast(40), r.height().coerceAtLeast(24), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            p.gravity = Gravity.TOP or Gravity.START; p.x = r.left; p.y = r.top
            runCatching { overlay?.addView(tv, p); labels.add(tv) }
        }
    }

    private fun clearOverlay() { handler.post { labels.forEach { runCatching { overlay?.removeView(it) } }; labels.clear() } }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) { val c=NotificationChannel("translator","Screen Translator",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java).createNotificationChannel(c) } }
    private fun notification(): Notification = Notification.Builder(this,"translator").setContentTitle("Screen Translator").setContentText("Live-перевод экрана включён").setSmallIcon(android.R.drawable.ic_menu_search).build()
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { clearOverlay(); reader?.close(); projection?.stop(); super.onDestroy() }
}
