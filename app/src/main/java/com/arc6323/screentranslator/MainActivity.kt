package com.arc6323.screentranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
    private lateinit var statusText: TextView
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        box.addView(TextView(this).apply {
            text = "Screen Translator\n\nГотов к запуску"
            textSize = 24f
            gravity = Gravity.CENTER
        })
        statusText = TextView(this).apply {
            text = "Кэш: проверяется…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        box.addView(statusText)
        box.addView(Button(this).apply {
            text = "ВКЛЮЧИТЬ LIVE-ПЕРЕВОД"
            setOnClickListener { requestOverlayAndCapture() }
        })
        box.addView(Button(this).apply {
            text = "ЯЗЫКИ ДЛЯ КЭШИРОВАНИЯ"
            setOnClickListener { showLanguageDialog() }
        })
        box.addView(Button(this).apply {
            text = "ВЫКЛЮЧИТЬ"
            setOnClickListener {
                try { stopService(Intent().setClassName(packageName, "$packageName.TranslatorService")) } catch (_: Throwable) {}
                Toast.makeText(this@MainActivity, "Live-перевод выключен", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(box)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (waitingForOverlay) {
            try {
                if (Settings.canDrawOverlays(this)) {
                    waitingForOverlay = false
                    requestScreenCapture()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun refreshStatus() {
        try {
            val targetCode = LanguageCacheManager.targetLanguage(this)
            val targetName = LanguageCacheManager.targetLanguageName(this)
            val selected = LanguageCacheManager.selected(this)
            val selectedNames = LanguageCacheManager.languages.filter { it.code in selected }.joinToString(", ") { it.name }
            statusText.text = "Кэш: проверяется…\nВыбрано: $selectedNames\nЯзык телефона: $targetName ($targetCode)"
            LanguageCacheManager.isDownloaded("ru") { ru ->
                LanguageCacheManager.isDownloaded("en") { en ->
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        statusText.text = "Кэш: Русский ${if (ru) "✓" else "✗"}, English ${if (en) "✓" else "✗"}\nВыбрано: $selectedNames\nЯзык телефона: $targetName ($targetCode)"
                    }
                }
            }
        } catch (_: Throwable) {
            statusText.text = "Кэш: проверка недоступна"
        }
    }

    private fun showLanguageDialog() {
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
                    try {
                        LanguageCacheManager.saveSelected(this, selected)
                        showDownloadProgress()
                        LanguageCacheManager.prepareSelected(this) { state ->
                            runOnUiThread { updateDownloadProgress(state) }
                        }
                    } catch (e: Throwable) {
                        progressText?.text = "Ошибка запуска: ${e.localizedMessage ?: e.javaClass.simpleName}"
                        progressBar?.isIndeterminate = false
                    }
                }
                .show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Настройки языков временно недоступны", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadProgress() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 25, 50, 10)
        }
        progressText = TextView(this).apply {
            text = "Подготовка языковых моделей…"
            textSize = 16f
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        content.addView(progressText)
        content.addView(progressBar)
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Загрузка языков")
            .setView(content)
            .setNegativeButton("СКРЫТЬ", null)
            .create()
        progressDialog?.show()
    }

    private fun updateDownloadProgress(state: LanguageCacheManager.DownloadState) {
        if (isFinishing) return
        val total = state.total
        val done = state.done.coerceIn(0, total.coerceAtLeast(1))
        progressBar?.isIndeterminate = false
        progressBar?.max = total.coerceAtLeast(1)
        progressBar?.progress = done
        val current = state.currentName ?: "Подготовка"
        val percent = if (total > 0) done * 100 / total else 100
        val speed = LanguageCacheManager.estimatedSpeedMbPerSec(state.elapsedMs)
        val speedText = if (speed != null) "\nОценка: %.1f МБ/с".format(speed) else ""
        val error = state.errorMessage?.let { "\nОшибка: $it" } ?: ""
        progressText?.text = when {
            state.failed > 0 && state.done >= total -> "Готово: ${state.done}/$total\nОшибок: ${state.failed}$error"
            state.done >= total && total > 0 -> "Готово: $total/$total"
            else -> "Загрузка: $current\n$percent% ($done/$total)$speedText$error"
        }
        refreshStatus()
        if (total > 0 && state.done >= total) {
            progressDialog?.window?.decorView?.postDelayed({ progressDialog?.dismiss() }, 1800)
        }
    }

    private fun requestOverlayAndCapture() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                waitingForOverlay = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            requestScreenCapture()
        } catch (_: Throwable) {
            Toast.makeText(this, "Не удалось открыть разрешение захвата экрана", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestScreenCapture() {
        if (captureRequested) return
        captureRequested = true
        try {
            val manager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (_: Throwable) {
            captureRequested = false
            Toast.makeText(this, "Захват экрана недоступен на этом устройстве", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        captureRequested = false
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Захват экрана не разрешён", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val serviceIntent = Intent().setClassName(packageName, "$packageName.TranslatorService").apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Live-перевод включён", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Не удалось запустить перевод", Toast.LENGTH_LONG).show()
        }
    }
}
