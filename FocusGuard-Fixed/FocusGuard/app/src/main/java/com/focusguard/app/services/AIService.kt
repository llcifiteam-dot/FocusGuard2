package com.focusguard.app.services

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AnalysisResult(
    val verdict: String,        // DISTRACTION | FOCUSED | NEUTRAL
    val score: Int,             // 0-100 distraction score
    val appCategory: String,
    val detectedApp: String?,
    val confidence: Int,
    val reason: String,
    val action: String,         // BLOCK | WARN | ALLOW
    val tip: String,
    val isKnownTrigger: Boolean,
    val blockMinutes: Int
)

class AIService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun analyzeScreen(
        appName: String,
        screenText: String,
        apiKey: String,
        userProfile: UserProfile,
        onResult: (AnalysisResult?) -> Unit,
        onError: (String) -> Unit
    ) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val day = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
            .format(java.util.Date())
        val isPeak = userProfile.peakHours.contains(hour)

        val profileCtx = if (userProfile.totalScans < 3) {
            "New user, no behavior data yet."
        } else {
            "Scans:${userProfile.totalScans} | AvgScore:${userProfile.avgScore.toInt()} | " +
            "TopApps:${userProfile.topApps.take(3).joinToString(",") { it.app }} | " +
            "PeakHours:${userProfile.peakHours.joinToString(",")} | " +
            "OverrideRate:${userProfile.overrideRate}%"
        }

        val systemPrompt = """You are FocusGuard, an AI distraction detection engine running on Android.
You receive the current foreground app name and its visible screen text.
Analyze if the user is being distracted.

USER BEHAVIOR PROFILE:
$profileCtx

CONTEXT:
- Current time: ${hour}h on $day
- Is peak distraction hour for this user: $isPeak
- Strictness: ${if (isPeak) "strict" else "balanced"}

SCORING GUIDE:
0-25: Highly productive (coding, studying, writing, professional email, maps)
26-50: Mildly productive (reading articles, educational content, useful browsing)
51-70: Neutral (messaging, calendar, quick tasks)
71-85: Distracting (social media, entertainment feeds)
86-100: Highly distracting (TikTok, Instagram Reels, gaming, binge content)

SMART RULES:
- YouTube = 20 if educational/tutorial, 80 if entertainment/vlogs
- WhatsApp = 40 normally, 65 if used excessively
- If app matches user's known top distraction apps, add 10 to score
- During peak distraction hours, be 10 points stricter

Respond ONLY with valid JSON, no other text:
{"verdict":"DISTRACTION","score":85,"appCategory":"Social Media","detectedApp":"TikTok","confidence":95,"reason":"User is watching TikTok entertainment videos","action":"BLOCK","tip":"Take a break and get back to your goals","isKnownTrigger":true,"blockMinutes":5}"""

        val userMessage = "App: $appName\nScreen text: ${screenText.take(500)}"

        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 300)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            }))
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        onError("API error ${response.code}: $responseBody")
                        return
                    }
                    val json = JSONObject(responseBody)
                    val content = json.getJSONArray("content")
                        .getJSONObject(0).getString("text")
                    val result = JSONObject(content.trim())
                    onResult(AnalysisResult(
                        verdict      = result.optString("verdict", "NEUTRAL"),
                        score        = result.optInt("score", 50),
                        appCategory  = result.optString("appCategory", "Unknown"),
                        detectedApp  = result.optString("detectedApp").takeIf { it.isNotEmpty() && it != "null" },
                        confidence   = result.optInt("confidence", 70),
                        reason       = result.optString("reason", ""),
                        action       = result.optString("action", "ALLOW"),
                        tip          = result.optString("tip", "Stay focused!"),
                        isKnownTrigger = result.optBoolean("isKnownTrigger", false),
                        blockMinutes = result.optInt("blockMinutes", 5)
                    ))
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }
}
