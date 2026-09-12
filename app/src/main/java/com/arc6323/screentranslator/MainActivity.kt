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

class MainActivity : Activity() {
    companion object { const val REQUEST_CAPTURE = 4101 }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48,48,48,48) }
        val title = TextView(this).apply { text = "Screen Translator\n\nПеревод иностранного текста поверх экрана"; textSize = 22f; gravity = Gravity.CENTER }
        val start = Button(this).apply { text = "ВКЛЮЧИТЬ LIVE-ПЕРЕВОД" }
        val stop = Button(this).apply { text = "ВЫКЛЮЧИТЬ" }
        box.addView(title); box.addView(start); box.addView(stop)
        setContentView(box)
        start.setOnClickListener { requestOverlayAndCapture() }
        stop.setOnClickListener { stopService(Intent(this, TranslatorService::class.java)) }
    }

    private fun requestOverlayAndCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslatorService::class.java).apply {
                putExtra("resultCode", resultCode); putExtra("data", data)
            }
            startForegroundService(i)
        }
    }
}
