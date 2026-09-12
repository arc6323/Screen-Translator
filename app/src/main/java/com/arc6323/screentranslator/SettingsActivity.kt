package com.arc6323.screentranslator

import android.app.Activity
import android.os.Bundle

/**
 * Compatibility entry point kept for installations that already have this activity
 * in the manifest. The current settings UI is hosted by MainActivity.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
