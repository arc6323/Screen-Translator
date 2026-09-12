package com.arc6323.screentranslator

import android.app.Activity
import android.content.Context
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Screen Translator\n\nПеревод иностранного текста поверх экрана"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply { text = "ВКЛЮЧИТЬ LIVE-ПЕРЕВОД" }
        val stop = Button(this).apply { text = "ВЫКЛЮЧИТЬ" }
        box.addView(title)
        box.addView(start)
        box.addView(stop)
        setContentView(box)

        start.setOnClickListener { requestOverlayAndCapture() }
        stop.setOnClickListener {
            stopService(Intent(this, TranslatorService::class.java))
            Toast.makeText(this, "Live-перевод выключен", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            requestScreenCapture()
        }
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
