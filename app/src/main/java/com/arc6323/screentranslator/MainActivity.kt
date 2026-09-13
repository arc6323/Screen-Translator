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
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object { const val REQUEST_CAPTURE = 4101 }

    private var waitingForOverlay = false
    private var captureRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the launcher path deliberately tiny. No ML Kit, MediaProjection,
        // battery APIs, model managers, or service classes are touched during startup.
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
                        LanguageCacheManager.prepareSelected(this) { state ->
                            runOnUiThread {
                                Toast.makeText(this, "Готово: ${state.done}/${state.total}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (_: Throwable) {
                        Toast.makeText(this, "Не удалось загрузить языковую модель", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Настройки языков временно недоступны", Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay) {
            try {
                if (Settings.canDrawOverlays(this)) {
                    waitingForOverlay = false
                    requestScreenCapture()
                }
            } catch (_: Throwable) {}
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
