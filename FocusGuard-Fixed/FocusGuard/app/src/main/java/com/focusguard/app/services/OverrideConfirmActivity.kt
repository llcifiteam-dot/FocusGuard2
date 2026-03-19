package com.focusguard.app.services

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.focusguard.app.R

class OverrideConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_override_confirm)

        window.setBackgroundDrawableResource(android.R.color.transparent)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_override_confirm).setOnClickListener {
            // Stop the overlay
            stopService(Intent(this, OverlayService::class.java))
            FocusAccessibilityService.instance?.onBlockDismissed(true)
            finish()
        }
    }
}
