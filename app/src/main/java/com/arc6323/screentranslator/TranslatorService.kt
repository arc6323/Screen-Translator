package com.arc6323.screentranslator

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

class TranslatorService : Service() {
    companion object {
        const val ACTION_START = "com.arc6323.screentranslator.START"
        const val ACTION_STOP = "com.arc6323.screentranslator.STOP"
        const val ACTION_PAUSE = "com.arc6323.screentranslator.PAUSE"
        const val ACTION_RESUME_CAPTURE = "com.arc6323.screentranslator.RESUME_CAPTURE"
        private const val CHANNEL = "translator"
        private const val NOTIFICATION_ID = 7
        private const val RECOVERY_ID = 8
    }
    private val main = Handler(Looper.getMainLooper())
    private val frames = FrameGuard()
    private lateinit var capture: CaptureEngine
    private lateinit var analyzer: FrameAnalyzer
    private lateinit var window: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var touchObserver: TouchObserver? = null
    private val interaction = InteractionGate()
    private var paused = false
    private var captureVisible = true
    private var stopping = false
    private var interrupted = false
    private var stopMessage = "Перевод выключен"
    private var lastNotification = ""
    private var screenWidth = 1
    private var screenHeight = 1
    private var captureWidth = 1
    private var captureHeight = 1
    private var topInset = 0
    private var bottomInset = 0
    private var translatedItems = emptyList<OverlayView.Item>()
    private var activeToken: Long? = null
    private var timeout: Runnable? = null
    private val scanTask = Runnable { scan() }
    private val resizeTask = Runnable { resizeCapture() }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopping) {
                interrupted = true
                stopWithMessage("Android завершил захват. Для продолжения нужно разрешить его снова.")
            }
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            main.removeCallbacks(resizeTask)
            main.postDelayed(resizeTask, 80)
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            captureVisible = isVisible
            if (!isVisible) {
                invalidateFrame()
                clearOverlay()
                setStatus("Захват временно не виден")
            } else if (!paused) {
                setStatus("Перевод включён")
                schedule(0)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        LanguageCacheManager.initialize(this)
        capture = CaptureEngine(main, ::onSceneChanged)
        analyzer = FrameAnalyzer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Перевод экрана", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopWithMessage("Перевод выключен"); return START_NOT_STICKY }
            ACTION_PAUSE -> {
                if (projection != null) togglePause() else stopSelf()
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
            getSystemService(NotificationManager::class.java).cancel(RECOVERY_ID)
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION_ID, notification("Подготовка перевода…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION_ID, notification("Подготовка перевода…"))
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(Activity.RESULT_OK, data)
            projection?.registerCallback(callback, main)
            updateDimensions()
            val surface = capture.attach(captureWidth, captureHeight)
            display = projection?.createVirtualDisplay(
                "ScreenTranslator", captureWidth, captureHeight, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main
            ) ?: error("Capture display unavailable")
            createOverlay()
            frames.start()
            setStatus("Перевод включён")
            schedule(0)
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

    private fun createOverlay() {
        val view = OverlayView(this).apply {
            setCaptureSize(captureWidth, captureHeight, screenWidth, screenHeight)
            isClickable = false
            isLongClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Visible window stays below the keyboard; transparent observer contributes zero opacity.
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = LoopTiming.touchAlpha(if (Build.VERSION.SDK_INT >= 31)
                getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 0.8f)
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        view.setOnApplyWindowInsetsListener { _, insets ->
            val oldTop = topInset
            val oldBottom = bottomInset
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                topInset = bars.top
                bottomInset = maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            if (oldTop != topInset || oldBottom != bottomInset) {
                invalidateFrame()
                clearOverlay()
                schedule(40)
            }
            insets
        }
        overlay = view
        window.addView(view, params)
        view.requestApplyInsets()
        touchObserver = TouchObserver(this, ::onScreenTouched).also { window.addView(it, it.windowParams()) }
    }

    private fun contentBounds() = Rect(
        0, (topInset.toLong() * captureHeight / screenHeight).toInt().coerceIn(0, captureHeight - 1),
        captureWidth, (captureHeight - bottomInset.toLong() * captureHeight / screenHeight).toInt()
            .coerceIn(1, captureHeight)
    )

    private fun scan() {
        if (stopping || paused || !captureVisible || projection == null) return
        val quietDelay = interaction.delay(SystemClock.uptimeMillis())
        if (quietDelay > 0) { schedule(quietDelay); return }
        if (analyzer.isBusy) { schedule(40); return }
        val token = frames.begin() ?: return
        activeToken = token
        val watchdog = Runnable {
            if (frames.finish(token)) {
                activeToken = null
                capture.cancel(token)
                restoreOverlay()
                setStatus("Обработка задержалась. Повторяем…")
                schedule(80)
            }
        }
        timeout = watchdog
        main.postDelayed(watchdog, 1800)
        capture.request(token, requireFresh = overlay?.visibility == View.VISIBLE && translatedItems.isNotEmpty(), prepared = {
            if (frames.accepts(token)) {
                capture.noteOverlayChange()
                overlay?.setTextVisible(false)
                overlay?.postOnAnimation {
                    overlay?.postOnAnimation { if (frames.accepts(token)) capture.arm(token) }
                }
            } else capture.cancel(token)
        }, result = { bitmap ->
            if (!frames.accepts(token)) { bitmap.recycle(); return@request }
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 45000)
            restoreOverlay()
            analyzer.analyze(
                bitmap, LanguageCacheManager.selected(this), LanguageCacheManager.targetLanguage(this),
                contentBounds(), current = { frames.accepts(token) },
                partial = { items ->
                    if (frames.accepts(token)) showItems(items)
                }
            ) { result ->
                if (frames.finish(token)) {
                    activeToken = null
                    main.removeCallbacks(watchdog)
                    timeout = null
                    showItems(result.items)
                    val message = result.error ?: if (result.items.isEmpty())
                        "Ждём текст выбранного исходного языка" else "Перевод включён"
                    setStatus(message, result.elapsedMs)
                    // Motion and touches trigger the next scan. Avoid repeatedly hiding a static translation.
                    if (result.error != null) schedule(1200)
                }
            }
        })
    }

    private fun showItems(items: List<OverlayView.Item>) {
        if (translatedItems == items) return
        capture.noteOverlayChange()
        translatedItems = items
        overlay?.setItems(items)
        restoreOverlay()
        updateMasks()
    }
    private fun clearOverlay() {
        if (translatedItems.isNotEmpty()) capture.noteOverlayChange()
        translatedItems = emptyList()
        overlay?.setItems(emptyList())
        restoreOverlay()
        updateMasks()
    }
    private fun restoreOverlay() {
        overlay?.setTextVisible(true)
        overlay?.visibility = if (stopping || paused || !captureVisible) View.INVISIBLE else View.VISIBLE
    }
    private fun invalidateFrame() {
        activeToken?.let { capture.cancel(it) }
        activeToken = null
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        frames.invalidate()
        main.removeCallbacks(scanTask)
    }
    private fun togglePause() {
        paused = !paused
        invalidateFrame()
        clearOverlay()
        setStatus(if (paused) "Перевод на паузе" else "Перевод включён")
        if (!paused) schedule(0)
    }
    private fun onSceneChanged() {
        if (stopping || paused || !captureVisible || projection == null) return
        interaction.motion(SystemClock.uptimeMillis())
        invalidateFrame()
        clearOverlay()
        schedule(interaction.delay(SystemClock.uptimeMillis()))
    }
    private fun onScreenTouched() {
        if (stopping || paused || projection == null) return
        interaction.touch(SystemClock.uptimeMillis())
        invalidateFrame()
        clearOverlay()
        schedule(interaction.delay(SystemClock.uptimeMillis()))
    }
    private fun updateMasks() {
        val bounds = contentBounds()
        overlay?.setContentBounds(bounds)
        val masks = mutableListOf<Rect>()
        masks.add(Rect(0, 0, captureWidth, bounds.top))
        masks.add(Rect(0, bounds.bottom, captureWidth, captureHeight))
        capture.setMasks(masks)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.removeCallbacks(resizeTask)
        main.postDelayed(resizeTask, 80)
    }
    private fun resizeCapture() {
        if (stopping || display == null) return
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        updateDimensions()
        if (oldWidth == screenWidth && oldHeight == screenHeight) return
        invalidateFrame()
        clearOverlay()
        try {
            val surface = capture.attach(captureWidth, captureHeight)
            display?.resize(captureWidth, captureHeight, resources.displayMetrics.densityDpi)
            display?.surface = surface
            overlay?.setCaptureSize(captureWidth, captureHeight, screenWidth, screenHeight)
            overlay?.requestApplyInsets()
            updateMasks()
            schedule(40)
        } catch (_: Exception) { stopWithMessage("Не удалось обновить захват после поворота экрана.") }
    }
    private fun schedule(delayMs: Long) {
        main.removeCallbacks(scanTask)
        if (!stopping && !paused && captureVisible && projection != null) main.postDelayed(scanTask, delayMs)
    }
    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(message: String, elapsedMs: Long? = null) {
        TranslationStatus.update(TranslationStatus.State(true, paused, message, elapsedMs))
        val key = message + "|" + paused
        if (key != lastNotification && canNotify()) {
            lastNotification = key
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
    }
    private fun openApp(resume: Boolean = false): PendingIntent = PendingIntent.getActivity(
        this, if (resume) 20 else 10, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (resume) action = ACTION_RESUME_CAPTURE
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(message: String): Notification {
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, TranslatorService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Screen Translator")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, if (paused) "Продолжить" else "Пауза",
                action(ACTION_PAUSE, 11)).build())
            .addAction(Notification.Action.Builder(null, "Выключить", action(ACTION_STOP, 12)).build())
            .build()
    }
    private fun recoveryNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("Перевод остановлен системой")
        .setContentText("После записи экрана нажмите «Возобновить».")
        .setStyle(Notification.BigTextStyle().bigText(
            "Android завершил захват: это возможно при другой записи экрана, блокировке или остановке через систему. " +
                "Завершите другую запись и разрешите захват снова."))
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setAutoCancel(true).setContentIntent(openApp(true))
        .addAction(Notification.Action.Builder(null, "Возобновить", openApp(true)).build())
        .build()

    private fun stopWithMessage(message: String) {
        stopMessage = message
        stopping = true
        invalidateFrame()
        frames.stop()
        TranslationStatus.update(TranslationStatus.State(message = message, needsCapture = interrupted))
        stopSelf()
    }
    override fun onDestroy() {
        stopping = true
        invalidateFrame()
        frames.stop()
        main.removeCallbacksAndMessages(null)
        try { overlay?.let { window.removeViewImmediate(it) } } catch (_: Exception) {}
        try { touchObserver?.let { window.removeViewImmediate(it) } } catch (_: Exception) {}
        touchObserver = null
        analyzer.close()
        capture.close()
        display?.release()
        projection?.unregisterCallback(callback)
        projection?.stop()
        display = null
        projection = null
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        TranslationStatus.update(TranslationStatus.State(message = stopMessage, needsCapture = interrupted))
        if (interrupted && canNotify())
            getSystemService(NotificationManager::class.java).notify(RECOVERY_ID, recoveryNotification())
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
