package com.arc6323.screentranslator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_CAPTURE = 4101
        private const val REQUEST_NOTIFICATIONS = 4102
    }
    private data class Row(
        val root: LinearLayout, val check: CheckBox, val state: TextView, val action: Button
    )
    private lateinit var status: TextView
    private lateinit var cacheStatus: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var target: Button
    private lateinit var languagesPanel: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pause: Button
    private lateinit var download: Button
    private lateinit var retry: Button
    private lateinit var wifi: CheckBox
    private val rows = linkedMapOf<String, Row>()
    private var waitingForOverlay = false
    private var captureRequested = false
    private var startAfterDownload = false
    private var resumed = false
    private var resumeRequested = false
    private var notificationSettingsPending = false
    private var search = ""
    private val observer: () -> Unit = { renderState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageCacheManager.initialize(this)
        waitingForOverlay = savedInstanceState?.getBoolean("overlay") ?: false
        captureRequested = savedInstanceState?.getBoolean("capture") ?: false
        startAfterDownload = savedInstanceState?.getBoolean("download_start") ?: false
        resumeRequested = savedInstanceState?.getBoolean("resume_requested") ?: (intent.action == TranslatorService.ACTION_RESUME_CAPTURE)
        notificationSettingsPending = savedInstanceState?.getBoolean("notification_settings") ?: false
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = column(20).apply { setBackgroundColor(Color.rgb(247, 248, 252)) }
        scroll.addView(root)
        root.addView(label("Screen Translator", 28).apply { setTypeface(null, Typeface.BOLD) })
        root.addView(label("Перевод экрана • версия ${BuildConfig.VERSION_NAME}", 13))
        status = label("Перевод выключен", 18)
        root.addView(status)
        cacheStatus = label("", 14)
        root.addView(cacheStatus)
        root.addView(label(
            "Перевод поверх приложения. Пауза, продолжение и выключение — в уведомлении.\n" +
                "При касании перевод скрывается, после остановки страницы появляется снова.\nТекст на языке результата пропускается. Неуверенно распознанные строки остаются без изменений.",
            14
        ))
        start = button("ВКЛЮЧИТЬ ПЕРЕВОД") { beginStart() }
        root.addView(start)
        stop = button("ОСТАНОВИТЬ") {
            startAfterDownload = false
            stopService(Intent(this, TranslatorService::class.java))
            renderState()
        }
        root.addView(stop)
        target = button("") { chooseTarget() }
        root.addView(target)
        root.addView(button("ЯЗЫКИ И ЗАГРУЗКИ") {
            languagesPanel.visibility = if (languagesPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (languagesPanel.visibility == View.VISIBLE) LanguageCacheManager.refresh()
        })
        languagesPanel = column(12).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            visibility = if (savedInstanceState?.getBoolean("languages") == true) View.VISIBLE else View.GONE
        }
        root.addView(languagesPanel)
        languagesPanel.addView(label("Языки", 23))
        languagesPanel.addView(label(
            "Выбор источника сохраняется сразу. Наличие модели показано отдельно.\n" +
                "Размер скачиваемой модели — примерно 30 МБ. English и русский перевод встроены и не требуют загрузки.",
            13
        ))
        val find = EditText(this).apply {
            hint = "Найти язык"
            isSingleLine = true
            setTextColor(Color.BLACK)
        }
        find.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s.toString().trim()
                filterRows()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        languagesPanel.addView(find)
        wifi = CheckBox(this).apply {
            text = "Скачивать только по Wi-Fi"
            setTextColor(Color.DKGRAY)
            isChecked = LanguageCacheManager.wifiOnly(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> LanguageCacheManager.setWifiOnly(this@MainActivity, checked) }
        }
        languagesPanel.addView(wifi)
        progressText = label("", 14)
        languagesPanel.addView(progressText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        languagesPanel.addView(progress, LinearLayout.LayoutParams(-1, dp(6)))
        download = button("СКАЧАТЬ ВЫБРАННЫЕ") {
            LanguageCacheManager.download(this, LanguageCacheManager.selected(this))
        }
        languagesPanel.addView(download)
        pause = button("ПАУЗА ОЧЕРЕДИ") {
            if (LanguageCacheManager.downloads.paused) LanguageCacheManager.resume()
            else LanguageCacheManager.pause()
        }
        languagesPanel.addView(pause)
        retry = button("ПОВТОРИТЬ ОШИБКИ") { LanguageCacheManager.retryFailed() }
        languagesPanel.addView(retry)
        LanguageCacheManager.languages.forEach { language ->
            val row = column(6)
            row.addView(label(language.name, 19).apply { setTypeface(null, Typeface.BOLD) })
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val state = label("", 13)
            line.addView(state, LinearLayout.LayoutParams(0, -2, 1f))
            val action = button("СКАЧАТЬ") {
                if (language.code in LanguageCacheManager.downloaded) {
                    LanguageCacheManager.delete(this, language.code)
                } else LanguageCacheManager.download(this, setOf(language.code))
            }.apply { textSize = 12f }
            line.addView(action, LinearLayout.LayoutParams(dp(110), -2))
            row.addView(line)
            val check = CheckBox(this).apply {
                text = "Переводить с этого языка"
                setTextColor(Color.DKGRAY)
            }
            row.addView(check)
            languagesPanel.addView(row)
            languagesPanel.addView(View(this).apply { setBackgroundColor(0xFFE7E8EF.toInt()) },
                LinearLayout.LayoutParams(-1, dp(1)))
            rows[language.code] = Row(row, check, state, action)
        }
        setContentView(scroll)
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(20) + insets.systemWindowInsetTop,
                dp(20) + insets.systemWindowInsetRight, dp(20) + insets.systemWindowInsetBottom)
            insets
        }
        root.requestApplyInsets()
        renderState()
    }

    override fun onStart() {
        super.onStart()
        LanguageCacheManager.observe(observer)
        TranslationStatus.observe(observer)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        LanguageCacheManager.refresh()
        if (notificationSettingsPending) {
            notificationSettingsPending = false
            if (notificationsEnabled()) resumeRequested = true
        }
        if (resumeRequested) {
            resumeRequested = false
            if (!TranslationStatus.state.running) beginStart()
        }
        if (waitingForOverlay) {
            waitingForOverlay = false
            if (Settings.canDrawOverlays(this)) requestCapture()
            else showMessage("Разрешите отображение поверх других приложений, чтобы включить перевод.")
        }
        renderState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == TranslatorService.ACTION_RESUME_CAPTURE && !TranslationStatus.state.running) {
            if (resumed) beginStart() else resumeRequested = true
        }
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onStop() {
        LanguageCacheManager.removeObserver(observer)
        TranslationStatus.removeObserver(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("resume_requested", resumeRequested)
        outState.putBoolean("notification_settings", notificationSettingsPending)
        outState.putBoolean("overlay", waitingForOverlay)
        outState.putBoolean("capture", captureRequested)
        outState.putBoolean("download_start", startAfterDownload)
        outState.putBoolean("languages", languagesPanel.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private fun requiredModels(): Set<String> =
        LanguageCacheManager.selected(this).filter { LanguageCacheManager.supportsScreenOcr(it) }.toSet() +
            setOf("ru", "en", LanguageCacheManager.targetLanguage(this))

    private fun renderState() {
        if (!::status.isInitialized || isFinishing || isDestroyed) return
        val manager = LanguageCacheManager
        val q = manager.downloads
        val running = TranslationStatus.state.running
        val missing = requiredModels() - manager.downloaded
        status.text = if (startAfterDownload) "Подготовка языков перед запуском…" else TranslationStatus.state.message
        cacheStatus.text = when {
            manager.checking -> "Проверяем языковые модели…"
            manager.lastError != null -> manager.lastError
            missing.isEmpty() -> "Выбранные модели готовы • можно переводить без интернета"
            else -> "Нужно скачать: ${missing.joinToString { manager.languageName(it) }}"
        }
        target.text = "ПЕРЕВОДИТЬ НА: ${manager.targetLanguageName(this)}"
        target.isEnabled = !running && !q.active && !startAfterDownload
        start.isEnabled = !running && !captureRequested && !startAfterDownload && !manager.checking
        start.text = if (missing.isEmpty()) "ВКЛЮЧИТЬ ПЕРЕВОД" else "СКАЧАТЬ МОДЕЛИ И ВКЛЮЧИТЬ"
        stop.isEnabled = running || startAfterDownload
        stop.text = if (running) "ОСТАНОВИТЬ" else "ОТМЕНИТЬ ЗАПУСК"
        download.isEnabled = !running && !q.active
        wifi.isEnabled = !q.active
        pause.isEnabled = q.active
        pause.text = if (q.paused) "ПРОДОЛЖИТЬ ОЧЕРЕДЬ" else "ПАУЗА ОЧЕРЕДИ"
        retry.visibility = if (q.errors.isNotEmpty()) View.VISIBLE else View.GONE
        retry.isEnabled = !q.active && !running
        progress.visibility = if (q.requested.isEmpty()) View.GONE else View.VISIBLE
        progress.isIndeterminate = q.current != null
        progress.progress = if (q.requested.isEmpty()) 0 else q.ready.size * 100 / q.requested.size
        val counts = "Доступно ${q.ready.size}/${q.requested.size} • ошибок ${q.errors.size}"
        progressText.text = when {
            q.paused && q.current != null ->
                "Завершаем ${manager.languageName(q.current)}. Затем очередь остановится.\n$counts"
            q.paused -> "Очередь на паузе\n$counts"
            q.current != null -> "Загрузка: ${manager.languageName(q.current)} • ≈30 МБ\n$counts"
            q.errors.isNotEmpty() -> "Не все модели скачаны.\n$counts\n" +
                q.errors.entries.joinToString("\n") { "${manager.languageName(it.key)}: ${it.value}" }
            q.successful -> "Все модели этой очереди доступны: ${q.ready.size}"
            else -> "Процент файла недоступен. Во время загрузки показываем текущий язык."
        }
        val selected = manager.selected(this)
        val targetCode = manager.targetLanguage(this)
        rows.forEach { (code, row) ->
            val exists = code in manager.downloaded
            val sourceAvailable = manager.supportsScreenOcr(code) && code != targetCode
            row.check.setOnCheckedChangeListener(null)
            row.check.visibility = if (sourceAvailable) View.VISIBLE else View.GONE
            row.check.isChecked = code in selected
            row.check.isEnabled = !running && !q.active && code !in setOf("ru", "en")
            row.check.setOnCheckedChangeListener { _, checked -> manager.setSelected(this, code, checked) }
            row.state.text = when {
                code in setOf("en", "ru") -> "Встроен • готов\n0 МБ загрузки"
                manager.isDeleting(code) -> "Удаляется…"
                q.current == code -> "Скачивается… • ≈30 МБ"
                code in q.queued -> "В очереди • ≈30 МБ"
                q.errors[code] != null -> "Ошибка: ${q.errors[code]}"
                manager.checking -> "Проверяется…"
                exists -> "Скачан • ≈30 МБ"
                else -> "Не скачан • ≈30 МБ"
            } + if (!sourceAvailable && code != "en") {
                if (code == targetCode) "\nЯзык результата" else "\nOCR этого письма пока недоступен"
            } else ""
            val protected = code in setOf("ru", "en", targetCode)
            row.action.text = when {
                exists && protected -> "ГОТОВ"
                exists -> "УДАЛИТЬ"
                else -> "СКАЧАТЬ"
            }
            row.action.isEnabled = !running && !q.active && !manager.checking &&
                !manager.isDeleting(code) && !(exists && protected)
        }
        if (startAfterDownload && q.finished && q.errors.isNotEmpty()) {
            startAfterDownload = false
            status.text = "Загрузка не завершена. Исправьте ошибку и повторите."
            start.isEnabled = true
        } else if (startAfterDownload && missing.isEmpty() && !manager.checking && resumed) {
            startAfterDownload = false
            status.post { requestOverlayAndCapture() }
        }
    }

    private fun filterRows() {
        rows.forEach { (code, row) ->
            val matches = search.isBlank() ||
                LanguageCacheManager.languageName(code).contains(search, ignoreCase = true) ||
                code.contains(search, ignoreCase = true)
            row.root.visibility = if (matches) View.VISIBLE else View.GONE
        }
    }

    private fun beginStart() {
        val missing = requiredModels() - LanguageCacheManager.downloaded
        if (missing.isNotEmpty()) {
            languagesPanel.visibility = View.VISIBLE
            LanguageCacheManager.download(this, requiredModels())
            startAfterDownload = true
            renderState()
        } else requestOverlayAndCapture()
    }

    private fun chooseTarget() {
        val all = LanguageCacheManager.languages
        val names = arrayOf("Как на телефоне") + all.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Язык перевода").setItems(names) { _, index ->
            LanguageCacheManager.setTargetLanguage(this, if (index == 0) null else all[index - 1].code)
            renderState()
        }.show()
    }

    private fun notificationsEnabled(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel("translator")?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestOverlayAndCapture() {
        if (captureRequested || TranslationStatus.state.running || isFinishing) return
        if (!notificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= 33 &&
                !getPreferences(MODE_PRIVATE).getBoolean("notifications_asked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            } else {
                AlertDialog.Builder(this).setTitle("Включите уведомления")
                    .setMessage("Управление переводом находится в уведомлении: пауза, продолжение и выключение.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        notificationSettingsPending = true
                        val manager = getSystemService(NotificationManager::class.java)
                        val channelDisabled = manager.getNotificationChannel("translator")?.importance == NotificationManager.IMPORTANCE_NONE
                        startActivity(Intent(if (channelDisabled) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                            else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, "translator"))
                    }.show()
            }
            return
        }
        try {
            if (!Settings.canDrawOverlays(this)) {
                waitingForOverlay = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else requestCapture()
        } catch (_: Exception) {
            waitingForOverlay = false
            showMessage("Не удалось открыть разрешения. Проверьте настройки приложения.")
        }
    }

    private fun requestCapture() {
        if (captureRequested || TranslationStatus.state.running) return
        captureRequested = true
        renderState()
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else manager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CAPTURE)
        } catch (_: Exception) {
            captureRequested = false
            showMessage("Не удалось запросить захват экрана.")
            renderState()
        }
    }

    @Deprecated("Uses the platform Activity capture permission result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        captureRequested = false
        if (resultCode != RESULT_OK || data == null) {
            showMessage("Захват экрана не разрешён.")
            renderState()
            return
        }
        try {
            startForegroundService(Intent(this, TranslatorService::class.java).apply {
                action = TranslatorService.ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            })
        } catch (_: Exception) { showMessage("Не удалось запустить перевод.") }
        renderState()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST_NOTIFICATIONS) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestOverlayAndCapture()
            else showMessage("Для управления переводом включите уведомления приложения.")
        }
    }

    private fun showMessage(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun column(padding: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(31, 35, 48))
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(value: String, clicked: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        minHeight = dp(48)
        isAllCaps = false
        setOnClickListener { clicked() }
    }
}
