package com.focusguard.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.focusguard.app.R
import com.focusguard.app.data.StorageManager
import com.focusguard.app.ui.MainActivity
import com.focusguard.app.data.ScanRecord
import com.focusguard.app.data.UserProfile

class FocusAccessibilityService : AccessibilityService() {

    private lateinit var storage: StorageManager
    private lateinit var aiService: AIService
    private val handler = Handler(Looper.getMainLooper())
    private var currentApp = ""
    private var isScanning = false
    private var lastScanTime = 0L
    private var isBlocking = false

    companion object {
        const val NOTIF_CHANNEL_ID = "focusguard_service"
        const val NOTIF_ID = 1001
        var instance: FocusAccessibilityService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        storage = StorageManager(this)
        aiService = AIService()
        createNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info

        if (storage.shieldActive) startScanLoop()
        showPersistentNotification("Shield Active — monitoring screen")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track current foreground app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != "android") {
                currentApp = pkg
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopScanLoop()
    }

    // ── Scan Loop: triggers every N seconds ──────────────────────────────────
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (storage.shieldActive && !isBlocking) {
                performScan()
            }
            val interval = storage.scanIntervalSeconds.toLong() * 1000
            handler.postDelayed(this, interval)
        }
    }

    fun startScanLoop() {
        handler.removeCallbacks(scanRunnable)
        val interval = storage.scanIntervalSeconds.toLong() * 1000
        handler.postDelayed(scanRunnable, interval)
        showPersistentNotification("Shield Active — scanning every ${storage.scanIntervalSeconds}s")
    }

    fun stopScanLoop() {
        handler.removeCallbacks(scanRunnable)
        showPersistentNotification("Shield Paused")
    }

    // ── Core: Read screen content and send to Claude ─────────────────────────
    private fun performScan() {
        if (isScanning) return
        val apiKey = storage.apiKey
        if (apiKey.isEmpty()) return
        val appToScan = currentApp
        if (appToScan.isEmpty() || appToScan == packageName) return

        // Skip system apps and launcher
        val skipApps = setOf("com.android.launcher", "com.android.systemui",
            "com.google.android.inputmethod", packageName)
        if (skipApps.any { appToScan.startsWith(it) }) return

        isScanning = true
        lastScanTime = System.currentTimeMillis()

        // Extract visible text from screen
        val screenText = extractScreenText()
        val appName = getAppName(appToScan)

        storage.totalScansToday++

        aiService.analyzeScreen(
            appName = appName,
            screenText = screenText,
            apiKey = apiKey,
            userProfile = storage.getProfile(),
            onResult = { result ->
                result ?: run { isScanning = false; return@analyzeScreen }
                handler.post {
                    processResult(result, appName)
                    isScanning = false
                }
            },
            onError = { error ->
                handler.post { isScanning = false }
            }
        )
    }

    private fun extractScreenText(): String {
        return try {
            val root = rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            extractTextFromNode(root, sb, 0)
            sb.toString().take(800)
        } catch (e: Exception) { "" }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 10) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 2) sb.append(text).append(" ")
        if (!desc.isNullOrEmpty() && desc.length > 2 && desc != text) sb.append(desc).append(" ")
        for (i in 0 until node.childCount) {
            extractTextFromNode(node.getChild(i), sb, depth + 1)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    // ── Process AI result ─────────────────────────────────────────────────────
    private fun processResult(result: AnalysisResult, appName: String) {
        val warnAt = if (storage.focusMode) 50 else storage.warnThreshold
        val blockAt = if (storage.focusMode) 65 else storage.blockThreshold

        val finalAction = when {
            result.score >= blockAt && storage.blockingEnabled -> "BLOCK"
            result.score >= warnAt -> "WARN"
            else -> "ALLOW"
        }

        val record = ScanRecord(
            appName   = appName,
            score     = result.score,
            verdict   = result.verdict,
            action    = finalAction,
            reason    = result.reason,
            timestamp = System.currentTimeMillis()
        )
        storage.saveScanRecord(record)

        when (finalAction) {
            "BLOCK" -> {
                storage.totalBlocks++
                isBlocking = true
                showBlockOverlay(result, appName)
            }
            "WARN" -> {
                sendWarnNotification(result, appName)
            }
        }

        // Notify main activity to refresh UI
        sendBroadcast(Intent("com.focusguard.SCAN_COMPLETE").apply {
            putExtra("score", result.score)
            putExtra("verdict", result.verdict)
            putExtra("action", finalAction)
            putExtra("reason", result.reason)
            putExtra("appName", appName)
        })
    }

    // ── Block Overlay ─────────────────────────────────────────────────────────
    private fun showBlockOverlay(result: AnalysisResult, appName: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra("appName", appName)
            putExtra("score", result.score)
            putExtra("reason", result.reason)
            putExtra("tip", result.tip)
            putExtra("blockMinutes", result.blockMinutes)
        }
        startService(intent)
    }

    fun onBlockDismissed(overridden: Boolean) {
        isBlocking = false
        if (overridden) {
            val history = storage.getScanHistory().toMutableList()
            if (history.isNotEmpty()) {
                // Mark last block as overridden
                val last = history[0].copy(overridden = true)
                history[0] = last
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "FocusGuard Shield",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Persistent shield status" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun showPersistentNotification(message: String) {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("🛡️ FocusGuard")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notif)
    }

    private fun sendWarnNotification(result: AnalysisResult, appName: String) {
        val channel = NotificationChannel(
            "focusguard_warn", "FocusGuard Warnings",
            NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        val notif = NotificationCompat.Builder(this, "focusguard_warn")
            .setContentTitle("⚠️ Distraction Warning — $appName")
            .setContentText(result.reason)
            .setSmallIcon(R.drawable.ic_shield)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
