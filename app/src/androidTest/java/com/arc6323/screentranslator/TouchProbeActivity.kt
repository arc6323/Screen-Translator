package com.arc6323.screentranslator

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/** Runs in the test APK's separate UID: the overlay must not block this app's button. */
class TouchProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(Button(this).apply {
            text = "TAP HERE"
            setOnClickListener { text = "TAPPED" }
        })
    }
}
