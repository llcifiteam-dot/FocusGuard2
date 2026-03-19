package com.focusguard.app.services

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AppDistraction(val app: String, var count: Int, var avgScore: Float)

data class ScanRecord(
    val appName: String,
    val score: Int,
    val verdict: String,
    val action: String,
    val reason: String,
    val timestamp: Long,
    var overridden: Boolean = false
)

data class UserProfile(
    var totalScans: Int = 0,
    var avgScore: Float = 50f,
    var topApps: MutableList<AppDistraction> = mutableListOf(),
    var peakHours: MutableList<Int> = mutableListOf(),
    var productiveHours: MutableList<Int> = mutableListOf(),
    var overrideRate: Int = 0,
    var focusStreak: Int = 0,
    var weeklyTrend: String = "STABLE"
)

class StorageManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("focusguard_prefs", Context.MODE_PRIVATE)

    // ── API Key ───────────────────────────────────────────────────────────────
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var blockingEnabled: Boolean
        get() = prefs.getBoolean("blocking_enabled", true)
        set(value) = prefs.edit().putBoolean("blocking_enabled", value).apply()

    var shieldActive: Boolean
        get() = prefs.getBoolean("shield_active", false)
        set(value) = prefs.edit().putBoolean("shield_active", value).apply()

    var scanIntervalSeconds: Int
        get() = prefs.getInt("scan_interval", 10)
        set(value) = prefs.edit().putInt("scan_interval", value).apply()

    var warnThreshold: Int
        get() = prefs.getInt("warn_threshold", 60)
        set(value) = prefs.edit().putInt("warn_threshold", value).apply()

    var blockThreshold: Int
        get() = prefs.getInt("block_threshold", 80)
        set(value) = prefs.edit().putInt("block_threshold", value).apply()

    var focusMode: Boolean
        get() = prefs.getBoolean("focus_mode", false)
        set(value) = prefs.edit().putBoolean("focus_mode", value).apply()

    var totalBlocks: Int
        get() = prefs.getInt("total_blocks", 0)
        set(value) = prefs.edit().putInt("total_blocks", value).apply()

    var totalScansToday: Int
        get() = prefs.getInt("scans_today", 0)
        set(value) = prefs.edit().putInt("scans_today", value).apply()

    // ── Scan History ──────────────────────────────────────────────────────────
    fun saveScanRecord(record: ScanRecord) {
        val history = getScanHistory().toMutableList()
        history.add(0, record)
        val trimmed = history.take(300)
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(JSONObject().apply {
                put("app", r.appName)
                put("score", r.score)
                put("verdict", r.verdict)
                put("action", r.action)
                put("reason", r.reason)
                put("timestamp", r.timestamp)
                put("overridden", r.overridden)
            })
        }
        prefs.edit().putString("scan_history", arr.toString()).apply()
        updateProfile(record)
        updateDailyStats(record)
    }

    fun getScanHistory(): List<ScanRecord> {
        val raw = prefs.getString("scan_history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ScanRecord(
                    appName    = obj.optString("app"),
                    score      = obj.optInt("score"),
                    verdict    = obj.optString("verdict"),
                    action     = obj.optString("action"),
                    reason     = obj.optString("reason"),
                    timestamp  = obj.optLong("timestamp"),
                    overridden = obj.optBoolean("overridden")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Profile ───────────────────────────────────────────────────────────────
    private fun updateProfile(record: ScanRecord) {
        val profile = getProfile()
        profile.totalScans++
        profile.avgScore = (profile.avgScore * (profile.totalScans - 1) + record.score) / profile.totalScans

        // Update top apps
        val existing = profile.topApps.find { it.app == record.appName }
        if (existing != null) {
            existing.avgScore = (existing.avgScore * existing.count + record.score) / (existing.count + 1)
            existing.count++
        } else {
            profile.topApps.add(AppDistraction(record.appName, 1, record.score.toFloat()))
        }
        profile.topApps.sortByDescending { it.count }
        if (profile.topApps.size > 20) profile.topApps = profile.topApps.take(20).toMutableList()

        // Update hour data
        val hour = java.util.Calendar.getInstance().apply {
            timeInMillis = record.timestamp
        }.get(java.util.Calendar.HOUR_OF_DAY)
        val hourData = getHourData().toMutableMap()
        val current = hourData[hour] ?: Pair(0, 0f)
        hourData[hour] = Pair(current.first + 1, (current.second * current.first + record.score) / (current.first + 1))
        saveHourData(hourData)

        profile.peakHours = hourData.entries
            .filter { it.value.first >= 2 && it.value.second > 65f }
            .map { it.key }.toMutableList()
        profile.productiveHours = hourData.entries
            .filter { it.value.first >= 2 && it.value.second < 40f }
            .map { it.key }.toMutableList()

        // Override rate
        val history = getScanHistory()
        val blocks = history.count { it.action == "BLOCK" }
        val overrides = history.count { it.overridden }
        profile.overrideRate = if (blocks > 0) (overrides * 100 / blocks) else 0

        // Streak
        profile.focusStreak = computeStreak(history)
        profile.weeklyTrend = computeTrend(history)

        saveProfile(profile)
    }

    private fun computeStreak(history: List<ScanRecord>): Int {
        val dayMap = history.groupBy {
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date(it.timestamp))
        }
        var streak = 0
        val cal = java.util.Calendar.getInstance()
        for (i in 0..29) {
            val key = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(cal.time)
            val dayScans = dayMap[key]
            if (dayScans != null && dayScans.isNotEmpty()) {
                val avg = dayScans.map { it.score }.average()
                if (avg < 55) streak++ else break
            } else if (i > 0) break
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private fun computeTrend(history: List<ScanRecord>): String {
        val now = System.currentTimeMillis()
        val week1 = history.filter { it.timestamp > now - 7 * 86400000L }
        val week2 = history.filter { it.timestamp > now - 14 * 86400000L && it.timestamp <= now - 7 * 86400000L }
        if (week1.size < 3 || week2.size < 3) return "STABLE"
        val avg1 = week1.map { it.score }.average()
        val avg2 = week2.map { it.score }.average()
        return when {
            avg1 < avg2 - 5 -> "IMPROVING"
            avg1 > avg2 + 5 -> "DECLINING"
            else -> "STABLE"
        }
    }

    fun getProfile(): UserProfile {
        val raw = prefs.getString("user_profile", null) ?: return UserProfile()
        return try {
            val obj = JSONObject(raw)
            UserProfile(
                totalScans      = obj.optInt("totalScans"),
                avgScore        = obj.optDouble("avgScore", 50.0).toFloat(),
                topApps         = JSONArray(obj.optString("topApps", "[]")).let { arr ->
                    (0 until arr.length()).map { i ->
                        val a = arr.getJSONObject(i)
                        AppDistraction(a.getString("app"), a.getInt("count"), a.getDouble("avgScore").toFloat())
                    }.toMutableList()
                },
                peakHours       = JSONArray(obj.optString("peakHours", "[]")).let { arr ->
                    (0 until arr.length()).map { arr.getInt(it) }.toMutableList()
                },
                productiveHours = JSONArray(obj.optString("productiveHours", "[]")).let { arr ->
                    (0 until arr.length()).map { arr.getInt(it) }.toMutableList()
                },
                overrideRate    = obj.optInt("overrideRate"),
                focusStreak     = obj.optInt("focusStreak"),
                weeklyTrend     = obj.optString("weeklyTrend", "STABLE")
            )
        } catch (e: Exception) { UserProfile() }
    }

    private fun saveProfile(profile: UserProfile) {
        val topAppsArr = JSONArray().apply {
            profile.topApps.forEach { put(JSONObject().apply { put("app", it.app); put("count", it.count); put("avgScore", it.avgScore) }) }
        }
        val obj = JSONObject().apply {
            put("totalScans", profile.totalScans)
            put("avgScore", profile.avgScore)
            put("topApps", topAppsArr.toString())
            put("peakHours", JSONArray(profile.peakHours).toString())
            put("productiveHours", JSONArray(profile.productiveHours).toString())
            put("overrideRate", profile.overrideRate)
            put("focusStreak", profile.focusStreak)
            put("weeklyTrend", profile.weeklyTrend)
        }
        prefs.edit().putString("user_profile", obj.toString()).apply()
    }

    // ── Daily Stats ───────────────────────────────────────────────────────────
    private fun updateDailyStats(record: ScanRecord) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val statsKey = "daily_$today"
        val raw = prefs.getString(statsKey, null)
        val stats = if (raw != null) JSONObject(raw) else JSONObject().apply {
            put("totalScans", 0); put("distractions", 0); put("blocks", 0); put("totalScore", 0)
        }
        stats.put("totalScans", stats.getInt("totalScans") + 1)
        stats.put("totalScore", stats.getInt("totalScore") + record.score)
        if (record.verdict == "DISTRACTION") stats.put("distractions", stats.getInt("distractions") + 1)
        if (record.action == "BLOCK") stats.put("blocks", stats.getInt("blocks") + 1)
        prefs.edit().putString(statsKey, stats.toString()).apply()
    }

    fun getWeeklyStats(): List<Pair<String, Int?>> {
        val result = mutableListOf<Pair<String, Int?>>()
        val cal = java.util.Calendar.getInstance()
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val key = "daily_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(cal.time)
            val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.time)
            val raw = prefs.getString(key, null)
            val score = if (raw != null) {
                val stats = JSONObject(raw)
                val total = stats.optInt("totalScans")
                val totalScore = stats.optInt("totalScore")
                if (total > 0) 100 - (totalScore / total) else null
            } else null
            result.add(Pair(day, score))
        }
        return result
    }

    private fun getHourData(): Map<Int, Pair<Int, Float>> {
        val raw = prefs.getString("hour_data", "{}") ?: "{}"
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<Int, Pair<Int, Float>>()
            obj.keys().forEach { k ->
                val v = obj.getJSONObject(k)
                result[k.toInt()] = Pair(v.getInt("count"), v.getDouble("avg").toFloat())
            }
            result
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveHourData(data: Map<Int, Pair<Int, Float>>) {
        val obj = JSONObject()
        data.forEach { (k, v) ->
            obj.put(k.toString(), JSONObject().apply { put("count", v.first); put("avg", v.second) })
        }
        prefs.edit().putString("hour_data", obj.toString()).apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }
}
