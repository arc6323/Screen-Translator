package com.arc6323.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object { const val REQUEST_CAPTURE = 4101 }

    private var waitingForOverlay = false
    private var captureRequested = false
    private lateinit var cacheStatus: TextView
    private lateinit var batteryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            requestScreenCapture()
        }
    }

    private fun refreshStatus() {
        val selected = LanguageCacheManager.selected(this)
        val names = LanguageCacheManager.languages.filter { it.code in selected }.joinToString(", ") { it.name }
        cacheStatus.text = "Кэш: $names\nЯзык телефона: ${LanguageCacheManager.targetLanguage()}"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryFree = android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (batteryFree) "Батарея: без ограничений" else "Батарея: оптимизация включена"
    }

    private fun showLanguageDialog() {
        val all = LanguageCacheManager.languages
        val selected = LanguageCacheManager.selected(this).toMutableSet()
        val checked = all.map { it.code in selected }.toBooleanArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Языки для кэширования")
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) selected.add(all[which].code) else selected.remove(all[which].code)
            }
            .setNegativeButton("ОТМЕНА", null)
            .setPositiveButton("СОХРАНИТЬ") { _, _ ->
                if (selected.isEmpty()) selected.add("en")
                LanguageCacheManager.saveSelected(this, selected)
                Toast.makeText(this, "Подготавливаю выбранные модели…", Toast.LENGTH_SHORT).show()
                LanguageCacheManager.prepareSelected(this) { done, total, failed ->
                    if (done == total) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                if (failed == 0) "Языковые модели готовы" else "Готово, ошибок: $failed",
                                Toast.LENGTH_LONG
                            ).show()
                            refreshStatus()
                        }
                    }
                }
                refreshStatus()
            }
            .show()
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
}
