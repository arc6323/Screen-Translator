package com.arc6323.screentranslator

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlin.math.min
import kotlin.math.roundToInt

class TranslatorService : Service() {
    companion object {
        const val ACTION_START = "com.arc6323.screentranslator.START"
        const val ACTION_STOP = "com.arc6323.screentranslator.STOP"
        const val ACTION_TOGGLE = "com.arc6323.screentranslator.TOGGLE"
        private const val CHANNEL = "translator"
        private const val NOTIFICATION_ID = 7
    }
    private val main = Handler(Looper.getMainLooper())
    private val frames = FrameGuard()
    private lateinit var capture: CaptureEngine
    private lateinit var analyzer: FrameAnalyzer
    private lateinit var window: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var controls: LinearLayout? = null
    private var modeButton: Button? = null
    private var detail: AlertDialog? = null
    private var frozen = false
    private var freezeRequested = false
    private var stopping = false
    private var stopMessage = "Перевод выключен"
    private var lastNotification = ""
    private var screenWidth = 1
    private var screenHeight = 1
    private var captureWidth = 1
    private var captureHeight = 1
    private var translatedItems = emptyList<OverlayView.Item>()
    private val scanTask = Runnable { scan() }
    private val resizeTask = Runnable { resizeCapture() }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopping) stopWithMessage("Захват экрана остановлен. Включите перевод снова.")
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            main.removeCallbacks(resizeTask)
            main.postDelayed(resizeTask, 120)
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible && !frozen) {
                frames.invalidate()
                overlay?.setItems(emptyList())
                translatedItems = emptyList()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        LanguageCacheManager.initialize(this)
        capture = CaptureEngine(main, ::onSceneChanged)
        analyzer = FrameAnalyzer()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Перевод экрана", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopWithMessage("Перевод выключен"); return START_NOT_STICKY }
            ACTION_TOGGLE -> {
                if (projection != null) toggleMode() else stopSelf()
                return START_NOT_STICKY
            }
        }
        if (projection != null || stopping) return START_NOT_STICKY
        try {
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
            val result = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            if (result != Activity.RESULT_OK || data == null) {
                stopWithMessage("Для запуска требуется разрешение захвата экрана.")
                return START_NOT_STICKY
            }
            if (!Settings.canDrawOverlays(this)) {
                stopWithMessage("Разрешите отображение поверх приложений.")
                return START_NOT_STICKY
            }
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION_ID, notification("Подготовка перевода…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            ) else startForeground(NOTIFICATION_ID, notification("Подготовка перевода…"))
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(Activity.RESULT_OK, data)
            projection?.registerCallback(callback, main)
            updateDimensions()
            val surface = capture.attach(captureWidth, captureHeight)
            display = projection?.createVirtualDisplay(
                "ScreenTranslator", captureWidth, captureHeight, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main
            ) ?: error("Capture display unavailable")
            createWindows()
            frames.start()
            setStatus("Live-перевод включён")
            schedule(200)
        } catch (_: Exception) {
            stopWithMessage("Не удалось запустить захват. Откройте приложение и попробуйте снова.")
        }
        return START_NOT_STICKY
    }

    private fun updateDimensions() {
        val bounds = if (Build.VERSION.SDK_INT >= 30) window.maximumWindowMetrics.bounds else {
            val size = Point()
            @Suppress("DEPRECATION")
            window.defaultDisplay.getRealSize(size)
            Rect(0, 0, size.x, size.y)
        }
        screenWidth = bounds.width().coerceAtLeast(1)
        screenHeight = bounds.height().coerceAtLeast(1)
        val scale = min(1f, 1920f / maxOf(screenWidth, screenHeight))
        captureWidth = (screenWidth * scale).roundToInt().coerceAtLeast(1)
        captureHeight = (screenHeight * scale).roundToInt().coerceAtLeast(1)
    }

    private fun createWindows() {
        val view = OverlayView(this).apply {
            setCaptureSize(captureWidth, captureHeight, screenWidth, screenHeight)
            setOnClickListener { if (frozen) showFullText() }
        }
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = liveAlpha()
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        overlay = view
        overlayParams = params
        window.addView(view, params)
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xEE202536.toInt())
            modeButton = Button(this@TranslatorService).apply {
                text = "Снимок"
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { toggleMode() }
            }
            addView(modeButton)
            addView(Button(this@TranslatorService).apply {
                text = "×"
                contentDescription = "Остановить перевод"
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener { stopWithMessage("Перевод выключен") }
            })
        }
        window.addView(controls, WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(8)
        })
        controls?.post { updateMasks() }
    }

    private fun liveAlpha(): Float = if (Build.VERSION.SDK_INT >= 31) {
        min(0.8f, getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
    } else 0.8f

    private fun scan() {
        if (stopping || frozen || projection == null) return
        if (analyzer.isBusy) { schedule(120); return }
        val token = frames.begin() ?: return
        val snapshotRequested = freezeRequested
        val timeout = Runnable {
            if (frames.finish(token)) {
                capture.cancel(token)
                restoreWindows()
                setStatus("Обработка заняла слишком много времени. Повторяем…")
                if (!frozen) schedule(300)
            }
        }
        main.postDelayed(timeout, 5000)
        capture.request(token, prepared = {
            if (frames.accepts(token)) {
                overlay?.visibility = View.INVISIBLE
                controls?.visibility = View.INVISIBLE
                overlay?.postOnAnimation {
                    overlay?.postOnAnimation {
                        if (frames.accepts(token)) capture.arm(token)
                    }
                }
            } else capture.cancel(token)
        }, result = { bitmap ->
            if (!frames.accepts(token)) {
                bitmap.recycle()
                return@request
            }
            if (snapshotRequested) {
                frozen = true
                freezeRequested = false
                translatedItems = emptyList()
                overlay?.setItems(emptyList())
                overlay?.setSnapshot(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                capture.stopMonitoring()
                applyMode()
            }
            restoreWindows()
            val selected = LanguageCacheManager.selected(this)
            val target = LanguageCacheManager.targetLanguage(this)
            analyzer.analyze(bitmap, selected, target, current = { frames.accepts(token) }) { result ->
                if (frames.finish(token)) {
                    main.removeCallbacks(timeout)
                    translatedItems = result.items
                    overlay?.setItems(result.items)
                    updateMasks()
                    val message = result.error ?: if (frozen) {
                        "Снимок готов • нажмите на экран, чтобы прочитать весь перевод"
                    } else if (result.items.isEmpty()) "Ищем текст для перевода…" else "Live-перевод включён"
                    setStatus(message, result.elapsedMs)
                    if (!frozen) schedule(if (freezeRequested) 0 else if (result.unchanged) 650 else 180)
                }
            }
        })
    }

    private fun toggleMode() {
        if (stopping) return
        if (frozen) {
            detail?.dismiss()
            detail = null
            frozen = false
            freezeRequested = false
            frames.invalidate()
            translatedItems = emptyList()
            overlay?.setItems(emptyList())
            overlay?.setSnapshot(null)
            applyMode()
            setStatus("Live-перевод включён")
            schedule(0)
        } else {
            freezeRequested = true
            modeButton?.text = "Снимаем…"
            setStatus("Готовим снимок для чтения…")
            schedule(0)
        }
    }

    private fun applyMode() {
        val params = overlayParams ?: return
        params.alpha = if (frozen) 1f else liveAlpha()
        params.flags = if (frozen) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        overlay?.let { window.updateViewLayout(it, params) }
        modeButton?.text = if (frozen) "Вернуться в Live" else "Снимок"
    }

    private fun restoreWindows() {
        if (stopping) return
        overlay?.visibility = View.VISIBLE
        controls?.visibility = View.VISIBLE
    }

    private fun onSceneChanged() {
        if (stopping || frozen || projection == null) return
        frames.invalidate()
        translatedItems = emptyList()
        overlay?.setItems(emptyList())
        restoreWindows()
        updateMasks()
        schedule(100)
    }

    private fun updateMasks() {
        val masks = translatedItems.map { Rect(it.rect) }.toMutableList()
        controls?.let { bar ->
            val at = IntArray(2)
            bar.getLocationOnScreen(at)
            masks.add(Rect(
                (at[0].toFloat() * captureWidth / screenWidth).toInt(),
                (at[1].toFloat() * captureHeight / screenHeight).toInt(),
                ((at[0] + bar.width).toFloat() * captureWidth / screenWidth).toInt(),
                ((at[1] + bar.height).toFloat() * captureHeight / screenHeight).toInt()
            ))
        }
        capture.setMasks(masks)
    }

    private fun showFullText() {
        if (translatedItems.isEmpty()) {
            Toast.makeText(this, "На снимке пока нет готового перевода", Toast.LENGTH_SHORT).show()
            return
        }
        detail?.dismiss()
        val text = TextView(this).apply {
            this.text = translatedItems.joinToString("\n\n") { it.text }
            textSize = 18f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        detail = AlertDialog.Builder(this).setTitle("Полный перевод")
            .setView(scroll).setPositiveButton("Закрыть", null).create().also {
                it.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                it.show()
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.removeCallbacks(resizeTask)
        main.postDelayed(resizeTask, 120)
    }

    private fun resizeCapture() {
        if (stopping || display == null) return
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        updateDimensions()
        if (oldWidth == screenWidth && oldHeight == screenHeight) return
        frames.invalidate()
        frozen = false
        freezeRequested = false
        detail?.dismiss()
        detail = null
        translatedItems = emptyList()
        overlay?.setItems(emptyList())
        overlay?.setSnapshot(null)
        try {
            val surface = capture.attach(captureWidth, captureHeight)
            display?.resize(captureWidth, captureHeight, resources.displayMetrics.densityDpi)
            display?.surface = surface
            overlay?.setCaptureSize(captureWidth, captureHeight, screenWidth, screenHeight)
            applyMode()
            restoreWindows()
            updateMasks()
            schedule(180)
        } catch (_: Exception) { stopWithMessage("Не удалось обновить захват после поворота экрана.") }
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(scanTask)
        if (!stopping && !frozen && projection != null) main.postDelayed(scanTask, delayMs)
    }

    private fun setStatus(message: String, elapsedMs: Long? = null) {
        TranslationStatus.update(TranslationStatus.State(true, frozen, message, elapsedMs))
        if (message != lastNotification) {
            lastNotification = message
            if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
            }
        }
    }

    private fun notification(message: String): Notification {
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, TranslatorService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Screen Translator")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 10, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .addAction(Notification.Action.Builder(null, if (frozen) "Live" else "Снимок", action(ACTION_TOGGLE, 11)).build())
            .addAction(Notification.Action.Builder(null, "Остановить", action(ACTION_STOP, 12)).build())
            .build()
    }

    private fun stopWithMessage(message: String) {
        stopMessage = message
        stopping = true
        frames.stop()
        TranslationStatus.update(TranslationStatus.State(message = message))
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        frames.stop()
        main.removeCallbacksAndMessages(null)
        detail?.dismiss()
        detail = null
        try { controls?.let { window.removeViewImmediate(it) } } catch (_: Exception) {}
        try { overlay?.let { window.removeViewImmediate(it) } } catch (_: Exception) {}
        overlay?.setSnapshot(null)
        analyzer.close()
        capture.close()
        display?.release()
        projection?.unregisterCallback(callback)
        projection?.stop()
        display = null
        projection = null
        controls = null
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        TranslationStatus.update(TranslationStatus.State(message = stopMessage))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()
}
