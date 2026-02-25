package com.example.sonya_front

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Configurable voice phrases loaded from assets JSON.
 *
 * Edit: app/src/main/assets/sonya_voice_responses.json
 */
object VoiceResponsesConfig {
    private const val ASSET_FILE = "sonya_voice_responses.json"

    // Fallback defaults (if asset missing/broken).
    private val defaultWake: List<String> = listOf(
        "да",
        "че",
        "мм",
        "угу",
        "ну",
        "побыстрее",
    )

    @Volatile private var cachedWake: List<String>? = null

    fun getWakeResponses(context: Context): List<String> {
        cachedWake?.let { return it }
        val loaded = loadWakeFromAssets(context) ?: defaultWake
        val sanitized = loaded.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { defaultWake }
        cachedWake = sanitized
        return sanitized
    }

    fun pickWakeResponse(context: Context): String {
        val list = getWakeResponses(context)
        return list[Random.nextInt(list.size)]
    }

    private fun loadWakeFromAssets(context: Context): List<String>? {
        return try {
            val raw = context.assets.open(ASSET_FILE).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val json = JSONObject(raw)
            val arr = json.optJSONArray("wake") ?: return null
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}

