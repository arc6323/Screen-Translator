package com.arc6323.screentranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.translate.TranslateLanguage
import com.google.mlkit.translate.TranslateRemoteModel

class MainActivity : Activity() {
    companion object { const val REQUEST_CAPTURE = 4101 }

    private var waitingForOverlay = false
    private var captureRequested = false
    private lateinit var statusText: TextView

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
            val selectedNames = LanguageCacheManager.languages
                .filter { it.code in selected }
                .joinToString(", ") { it.name }
            statusText.text = "Кэш: проверяется…\nВыбрано: $selectedNames\nЯзык телефона: $targetName ($targetCode)"
            LanguageCacheManager.isDownloaded("ru") { ru ->
                LanguageCacheManager.isDownloaded("en") { en ->
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        statusText.text = "Кэш: Русский ${if (ru) "✓" else "⏳"}, English ${if (en) "✓" else "✗"}\nВыбрано: $selectedNames\nЯзык телефона: $targetName ($targetCode)"
                    }
                }
            }
        } catch (_: Throwable) {
            statusText.text = "Кэш: проверка недоступна"
        }
    }

    private fun showLanguageDialog() {
        val selected = LanguageCacheManager.selected(this).toMutableSet()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 4, 24, 0)
        }
        val info = TextView(this).apply {
            text = "Размер модели: ≈30 МБ\nEnglish встроен в ML Kit и готов сразу.\nГалочка у дополнительного языка = добавить его в загрузку."
            textSize = 13f
            setPadding(0, 0, 0, 14)
        }
        root.addView(info)

        val progressText = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, 4, 0, 6)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progress = 0
            visibility = View.GONE
        }
        root.addView(progressText)
        root.addView(progress)

        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        var dialog: AlertDialog? = null
        var downloadRunning = false
        var lastRequested = selected.toSet()

        fun rowButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
            this.text = text
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(12, 0, 12, 0)
            setOnClickListener { onClick() }
        }

        fun renderRows() {
            rows.removeAllViews()
            val all = LanguageCacheManager.languages
            all.forEach { language ->
                val code = language.code
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 4)
                }
                val check = CheckBox(this)
                val mandatory = code == "ru" || code == "en"
                check.isChecked = if (code == "en") true else if (mandatory) false else code in selected
                check.isEnabled = !mandatory
                if (!mandatory) {
                    check.setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(code) else selected.remove(code)
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(52, -2))

                val textBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(4, 0, 6, 0)
                }
                val name = TextView(this).apply {
                    text = language.name
                    textSize = 17f
                }
                val state = TextView(this).apply {
                    text = if (code == "en") "Встроен • готов • 0 МБ загрузки"
                    else "Проверка… • ≈${LanguageCacheManager.ESTIMATED_MODEL_MB.toInt()} МБ"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                }
                textBox.addView(name)
                textBox.addView(state)
                row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))

                val action = rowButton("…") {}
                row.addView(action, LinearLayout.LayoutParams(100, 48))
                rows.addView(row)

                if (code == "en") {
                    action.text = "ГОТОВ"
                    action.isEnabled = false
                    return@forEach
                }

                LanguageCacheManager.isDownloaded(code) { downloaded ->
                    runOnUiThread {
                        if (dialog?.isShowing != true) return@runOnUiThread
                        check.isChecked = if (mandatory) downloaded else code in selected
                        state.text = if (downloaded) "Скачан ✓ • ≈${LanguageCacheManager.ESTIMATED_MODEL_MB.toInt()} МБ"
                        else if (downloadRunning && code == LanguageCacheManager.selected(this).firstOrNull()) "Загрузка… • ≈${LanguageCacheManager.ESTIMATED_MODEL_MB.toInt()} МБ"
                        else "Не скачан • ≈${LanguageCacheManager.ESTIMATED_MODEL_MB.toInt()} МБ"
                        action.text = if (downloaded) "УДАЛИТЬ" else "СКАЧАТЬ"
                        action.setOnClickListener {
                            if (downloaded) {
                                if (mandatory) {
                                    Toast.makeText(this, "Русский — обязательная модель", Toast.LENGTH_SHORT).show()
                                } else {
                                    val model = TranslateLanguage.fromLanguageTag(code)?.let { TranslateRemoteModel.Builder(it).build() }
                                    if (model != null) {
                                        RemoteModelManager.getInstance().deleteDownloadedModel(model)
                                            .addOnSuccessListener {
                                                selected.remove(code)
                                                runOnUiThread {
                                                    Toast.makeText(this, "${language.name} удалён из кэша", Toast.LENGTH_SHORT).show()
                                                    renderRows()
                                                    refreshStatus()
                                                }
                                            }
                                    }
                                }
                            } else {
                                selected.add(code)
                                startLanguageDownload(setOf(code), progressText, progress, {
                                    downloadRunning = false
                                    renderRows()
                                }) { downloadRunning = true }
                            }
                        }
                    }
                }
            }
        }

        renderRows()

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pause = Button(this).apply {
            text = "ПАУЗА"
            setOnClickListener {
                if (downloadRunning) {
                    LanguageCacheManager.setPaused(true)
                    progressText.text = "Пауза после текущей загрузки. Активную загрузку ML Kit нельзя прервать программно."
                    text = "ПРОДОЛЖИТЬ"
                } else if (LanguageCacheManager.isPaused()) {
                    LanguageCacheManager.setPaused(false)
                    text = "ПАУЗА"
                    startLanguageDownload(lastRequested, progressText, progress, {
                        downloadRunning = false
                        renderRows()
                    }) { downloadRunning = true }
                }
            }
        }
        val download = Button(this).apply {
            text = "СКАЧАТЬ ВЫБРАННЫЕ"
            setOnClickListener {
                lastRequested = selected.toSet()
                startLanguageDownload(lastRequested, progressText, progress, {
                    downloadRunning = false
                    renderRows()
                }) { downloadRunning = true }
            }
        }
        footer.addView(pause, LinearLayout.LayoutParams(0, 52, 1f))
        footer.addView(download, LinearLayout.LayoutParams(0, 52, 1.7f))
        root.addView(footer)

        dialog = AlertDialog.Builder(this)
            .setTitle("Языки для кэширования")
            .setView(root)
            .setNegativeButton("ЗАКРЫТЬ", null)
            .create()
        dialog.show()
    }

    private fun startLanguageDownload(
        requested: Set<String>,
        progressText: TextView,
        progress: ProgressBar,
        onFinished: () -> Unit,
        onStarted: () -> Unit
    ) {
        onStarted()
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progressText.text = "Подготовка…\nПроцент появится только после завершения модели — ML Kit не отдаёт Android байтовый прогресс."
        LanguageCacheManager.prepareCodes(this, requested) { state ->
            runOnUiThread {
                val total = state.total.coerceAtLeast(1)
                val done = state.done.coerceIn(0, total)
                if (state.downloading) {
                    progress.isIndeterminate = true
                    progressText.text = "Загрузка: ${state.currentName}\n≈${LanguageCacheManager.ESTIMATED_MODEL_MB.toInt()} МБ • система показывает реальный прогресс в уведомлении"
                } else if (state.paused) {
                    progress.isIndeterminate = false
                    progress.progress = done * 100 / total
                    progressText.text = "Пауза: готово $done/$total"
                } else {
                    progress.isIndeterminate = false
                    progress.max = 100
                    progress.progress = done * 100 / total
                    val speed = LanguageCacheManager.estimatedSpeedMbPerSec(state.elapsedMs)
                    val speedText = if (speed != null) " • оценка ${"%.1f".format(speed)} МБ/с" else ""
                    val error = state.errorMessage?.let { "\nОшибка: $it" } ?: ""
                    progressText.text = if (state.done >= state.total && state.total > 0) {
                        "Готово: ${state.done}/${state.total}$speedText$error"
                    } else {
                        "Готово: ${state.done}/${state.total}$error"
                    }
                }
                refreshStatus()
                if (!state.downloading && !state.paused && state.done >= state.total) onFinished()
            }
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
