package com.guardianlayer.app.data

import android.content.Context
import com.guardianlayer.app.model.GuardianEvent
import org.json.JSONArray
import org.json.JSONObject

object GuardianEventStore {
    private const val PREFS = "guardian_events"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 100
    private const val TRACKER_BLOCK_TITLE = "Tracker request blocked"

    @Synchronized
    fun append(context: Context, level: String, title: String, detail: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = readArray(prefs.getString(KEY_EVENTS, null))
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("level", level)
                .put("title", title)
                .put("detail", detail)
        )

        var kept = 0
        for (index in 0 until existing.length()) {
            if (kept >= MAX_EVENTS - 1) break
            val item = existing.optJSONObject(index) ?: continue

            // Tracker Shield can see the same blocked hostname repeatedly across
            // sessions. Keep the newest identical signal instead of filling the
            // timeline and per-app signal view with duplicate entries. Session
            // totals remain available in Tracker Shield telemetry/history.
            val duplicateTrackerSignal = title == TRACKER_BLOCK_TITLE &&
                item.optString("title") == TRACKER_BLOCK_TITLE &&
                item.optString("detail") == detail
            if (duplicateTrackerSignal) continue

            next.put(item)
            kept++
        }

        prefs.edit().putString(KEY_EVENTS, next.toString()).apply()
    }

    fun recent(context: Context, limit: Int = 20): List<GuardianEvent> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = readArray(prefs.getString(KEY_EVENTS, null))
        val count = minOf(array.length(), limit)
        return buildList {
            for (index in 0 until count) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    GuardianEvent(
                        timestamp = item.optLong("timestamp"),
                        level = item.optString("level", "INFO"),
                        title = item.optString("title"),
                        detail = item.optString("detail")
                    )
                )
            }
        }
    }

    private fun readArray(raw: String?): JSONArray = try {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (_: Exception) {
        JSONArray()
    }
}
