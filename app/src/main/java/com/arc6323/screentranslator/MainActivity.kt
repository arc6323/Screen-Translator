package com.arc6323.screentranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object { const val REQUEST_CAPTURE = 4101 }

    private var waitingForOverlay = false
    private var captureRequested = false
    private lateinit var cacheStatus: TextView
    private lateinit var batteryStatus: TextView
    private var downloadDialog: AlertDialog? = null
    private var downloadProgress: ProgressBar? = null
    private var downloadText: TextView? = null
    private var downloadStartedAt = 0L
    private val progressTicker = android.os.Handler(mainLooper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // IMPORTANT: keep startup completely independent from ML Kit/Google Play services.
        // Model-manager calls are deferred until the user explicitly opens language settings.
        buildUi()
        showBasicStatus()
    }

    private fun buildUi() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
        }
        val title = TextView(this).apply {
            text = "Screen Translator\n\nПеревод иностранного текста поверх экрана"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply { text = "ВКЛЮЧИТЬ LIVE-ПЕРЕВОД" }
        val stop = Button(this).apply { text = "ВЫКЛЮЧИТЬ" }
        val languages = Button(this).apply { text = "ЯЗЫКИ ДЛЯ КЭШИРОВАНИЯ" }
        val battery = Button(this).apply { text = "НАСТРОЙКИ РАБОТЫ В ФОНЕ" }
        cacheStatus = TextView(this).apply { gravity = Gravity.CENTER; textSize = 14f }
        batteryStatus = TextView(this).apply { gravity = Gravity.CENTER; textSize = 14f }

        box.addView(title)
        box.addView(start)
        box.addView(stop)
        box.addView(languages)
        box.addView(cacheStatus)
        box.addView(battery)
        box.addView(batteryStatus)
        setContentView(box)

        start.setOnClickListener { requestOverlayAndCapture() }
        stop.setOnClickListener {
            stopService(Intent(this, TranslatorService::class.java))
            Toast.makeText(this, "Live-перевод выключен", Toast.LENGTH_SHORT).show()
        }
        languages.setOnClickListener { showLanguageDialog() }
        battery.setOnClickListener { requestBackgroundOperation() }
    }

    private fun showBasicStatus() {
        val target = try {
            val locale = if (android.os.Build.VERSION.SDK_INT >= 33) {
                getSystemService(android.app.LocaleManager::class.java)?.systemLocales?.get(0)
            } else if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.os.LocaleList.getDefault().get(0)
            } else {
                @Suppress("DEPRECATION") java.util.Locale.getDefault()
            }
            locale?.language ?: java.util.Locale.getDefault().language
        } catch (_: Throwable) {
            java.util.Locale.getDefault().language
        }
        cacheStatus.text = "Кэш: проверка при открытии языков\nЯзык телефона: $target"
        updateBatteryStatus()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            requestScreenCapture()
        }
    }

    private fun updateBatteryStatus() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val batteryFree = android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
            batteryStatus.text = if (batteryFree) "Батарея: без ограничений" else "Батарея: оптимизация включена"
        } catch (_: Throwable) {
            batteryStatus.text = "Батарея: состояние недоступно"
        }
    }

    private fun refreshStatus() {
        try {
            val selected = LanguageCacheManager.selected(this)
            val selectedNames = LanguageCacheManager.languages
                .filter { it.code in selected && it.code != "en" }
                .joinToString(", ") { it.name }
            val target = LanguageCacheManager.targetLanguageName(this)
            val targetCode = LanguageCacheManager.targetLanguage(this)
            LanguageCacheManager.isDownloaded("ru") { ruReady ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val russian = if (ruReady) "Русский ✓" else "Русский ⏳"
                    val extra = selectedNames.ifBlank { "нет" }
                    cacheStatus.text = "Кэш: $russian, English встроен\nВыбрано для загрузки: $extra\nЯзык телефона: $target ($targetCode)"
                }
            }
            updateBatteryStatus()
        } catch (t: Throwable) {
            t.printStackTrace()
            showBasicStatus()
        }
    }

    private fun showLanguageDialog() {
        // First ML Kit access is deliberately here, not during Activity startup.
        try {
            val all = LanguageCacheManager.languages
            val selected = LanguageCacheManager.selected(this).toMutableSet()
            val checked = all.map { it.code in selected }.toBooleanArray()

            AlertDialog.Builder(this)
                .setTitle("Языки для кэширования")
                .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                    if (isChecked) selected.add(all[which].code) else selected.remove(all[which].code)
                }
                .setNegativeButton("ОТМЕНА", null)
                .setPositiveButton("СОХРАНИТЬ") { _, _ ->
                    LanguageCacheManager.saveSelected(this, selected)
                    refreshStatus()
                    showDownloadProgress()
                    try {
                        LanguageCacheManager.prepareSelected(this) { state ->
                            runOnUiThread { if (!isFinishing && !isDestroyed) updateDownloadProgress(state) }
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        downloadText?.text = "Не удалось начать загрузку. Проверьте интернет и Google Play services."
                        downloadProgress?.isIndeterminate = false
                    }
                }
                .show()
        } catch (t: Throwable) {
            t.printStackTrace()
            Toast.makeText(this, "Не удалось открыть языки. Проверьте Google Play services.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadProgress() {
        if (downloadDialog?.isShowing == true) return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 16)
        }
        downloadText = TextView(this).apply {
            text = "Подготовка языковых моделей…"
            textSize = 15f
        }
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1
            progress = 0
            isIndeterminate = true
        }
        content.addView(downloadText)
        content.addView(downloadProgress)
        downloadDialog = AlertDialog.Builder(this)
            .setTitle("Кэширование языков")
            .setView(content)
            .setNegativeButton("СКРЫТЬ", null)
            .create()
        downloadDialog?.show()
        downloadStartedAt = SystemClock.elapsedRealtime()
        startProgressTicker()
    }

    private fun updateDownloadProgress(state: LanguageCacheManager.DownloadState) {
        if (downloadDialog?.isShowing != true && state.done < state.total) showDownloadProgress()

        downloadProgress?.max = state.total.coerceAtLeast(1)
        downloadProgress?.progress = state.done.coerceAtMost(state.total)
        downloadProgress?.isIndeterminate = state.done < state.total && state.currentCode != null

        if (state.done >= state.total) {
            progressTicker.removeCallbacksAndMessages(null)
            val result = if (state.failed == 0) {
                if (state.total == 0) "Готово: English встроен в ML Kit" else "Готово: все выбранные модели доступны"
            } else "Завершено с ошибками: ${state.failed}"
            downloadText?.text = result
            refreshStatus()
            if (downloadDialog?.isShowing == true) {
                downloadDialog?.window?.decorView?.postDelayed({ downloadDialog?.dismiss() }, 1400)
            }
            return
        }

        val name = state.currentName ?: "языковой модели"
        val elapsed = if (state.elapsedMs > 0) state.elapsedMs else SystemClock.elapsedRealtime() - downloadStartedAt
        val seconds = (elapsed / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val sec = seconds % 60
        val speed = LanguageCacheManager.estimatedSpeedMbPerSec(elapsed)
        val speedText = if (speed != null) String.format(java.util.Locale.US, "≈ %.1f МБ/с", speed) else "расчёт скорости…"
        val percent = if (state.total > 0) state.done * 100 / state.total else 0
        downloadText?.text = "Загрузка: $name\nМодель ≈ 30 МБ • $percent% • ${minutes}:${sec.toString().padStart(2, '0')}\nСкорость: $speedText (оценка)"
        startProgressTicker()
    }

    private fun startProgressTicker() {
        progressTicker.removeCallbacksAndMessages(null)
        progressTicker.postDelayed({
            if (downloadDialog?.isShowing == true) {
                val elapsed = SystemClock.elapsedRealtime() - downloadStartedAt
                val seconds = (elapsed / 1000).coerceAtLeast(0)
                val minutes = seconds / 60
                val sec = seconds % 60
                val speed = LanguageCacheManager.estimatedSpeedMbPerSec(elapsed)
                val speedText = if (speed != null) String.format(java.util.Locale.US, "≈ %.1f МБ/с", speed) else "расчёт скорости…"
                val current = downloadText?.text?.toString() ?: "Загрузка…"
                if (current.startsWith("Загрузка:")) {
                    val first = current.lineSequence().firstOrNull() ?: "Загрузка…"
                    val progress = downloadProgress?.progress ?: 0
                    val total = downloadProgress?.max ?: 1
                    downloadText?.text = "$first\nМодель ≈ 30 МБ • ${progress * 100 / total}% • ${minutes}:${sec.toString().padStart(2, '0')}\nСкорость: $speedText (оценка)"
                }
                startProgressTicker()
            }
        }, 1000)
    }

    private fun requestBackgroundOperation() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    return
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    return
                }
            }
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun requestOverlayAndCapture() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        if (captureRequested) return
        captureRequested = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Android API 30, kept for compatibility with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        captureRequested = false
        if (resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, TranslatorService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Live-перевод включён", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Захват экрана не разрешён", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        progressTicker.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
