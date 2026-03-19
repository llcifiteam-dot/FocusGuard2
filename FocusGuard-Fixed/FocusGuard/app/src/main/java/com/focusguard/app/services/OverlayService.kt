package com.focusguard.app.services

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.focusguard.app.R

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var countdownTimer: CountDownTimer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY

        val appName    = intent.getStringExtra("appName") ?: "App"
        val score      = intent.getIntExtra("score", 85)
        val reason     = intent.getStringExtra("reason") ?: ""
        val tip        = intent.getStringExtra("tip") ?: "Stay focused!"
        val blockMins  = intent.getIntExtra("blockMinutes", 5)

        showOverlay(appName, score, reason, tip, blockMins)
        vibrate()

        return START_NOT_STICKY
    }

    private fun showOverlay(appName: String, score: Int, reason: String, tip: String, blockMins: Int) {
        dismissOverlay()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView?.apply {
            findViewById<TextView>(R.id.tv_app_name).text = appName
            findViewById<TextView>(R.id.tv_score).text = "$score/100"
            findViewById<TextView>(R.id.tv_reason).text = reason
            findViewById<TextView>(R.id.tv_tip).text = "💡 $tip"

            val timerTv = findViewById<TextView>(R.id.tv_timer)
            val totalSecs = blockMins * 60L

            countdownTimer = object : CountDownTimer(totalSecs * 1000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val secs = millisUntilFinished / 1000
                    val m = secs / 60
                    val s = secs % 60
                    timerTv.text = String.format("%d:%02d", m, s)
                }
                override fun onFinish() {
                    dismissOverlay()
                    FocusAccessibilityService.instance?.onBlockDismissed(false)
                }
            }.start()

            findViewById<Button>(R.id.btn_override).setOnClickListener {
                showOverrideConfirmation()
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun showOverrideConfirmation() {
        // We need to allow focusable for the dialog
        val params = (overlayView?.layoutParams as? WindowManager.LayoutParams)?.apply {
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        params?.let { windowManager?.updateViewLayout(overlayView, it) }

        val intent = Intent(this, OverrideConfirmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun dismissOverlay() {
        countdownTimer?.cancel()
        countdownTimer = null
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {}
        overlayView = null
        stopSelf()
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissOverlay()
    }
}
