package com.guardianlayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local archive of user-frozen exact-watch tests. The report text is captured
 * at freeze time so later live traffic cannot rewrite the saved evidence.
 */
object GuardianTestSnapshotStore {
    private const val PREFS = "guardian_test_snapshots"
    private const val KEY_SNAPSHOTS = "snapshots"
    private const val MAX_TOTAL = 30
    private const val MAX_PER_APP = 8

    data class Snapshot(
        val id: String,
        val packageName: String,
        val appLabel: String,
        val startedAt: Long,
        val endedAt: Long,
        val decisions: Long,
        val blocked: Long,
        val allowed: Long,
        val report: String
    )

    @Synchronized
    fun save(context: Context, snapshot: Snapshot) {
        if (snapshot.packageName.isBlank() || snapshot.endedAt <= 0L) return
        val candidates = (listOf(snapshot) + read(context).filterNot { it.id == snapshot.id })
            .sortedByDescending { it.endedAt }

        val perApp = mutableMapOf<String, Int>()
        val kept = mutableListOf<Snapshot>()
        for (item in candidates) {
            if (kept.size >= MAX_TOTAL) break
            val current = perApp[item.packageName] ?: 0
            if (current >= MAX_PER_APP) continue
            kept += item
            perApp[item.packageName] = current + 1
        }
        write(context, kept)
    }

    @Synchronized
    fun snapshots(context: Context, limit: Int = MAX_TOTAL): List<Snapshot> =
        read(context)
            .sortedByDescending { it.endedAt }
            .take(limit.coerceAtLeast(1))

    @Synchronized
    fun count(context: Context): Int = read(context).size

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SNAPSHOTS)
            .apply()
    }

    private fun read(context: Context): List<Snapshot> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOTS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_TOTAL)) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("packageName")
                    val endedAt = item.optLong("endedAt")
                    if (packageName.isBlank() || endedAt <= 0L) continue
                    add(
                        Snapshot(
                            id = item.optString("id", "$packageName:$endedAt"),
                            packageName = packageName,
                            appLabel = item.optString("appLabel", packageName),
                            startedAt = item.optLong("startedAt"),
                            endedAt = endedAt,
                            decisions = item.optLong("decisions").coerceAtLeast(0L),
                            blocked = item.optLong("blocked").coerceAtLeast(0L),
                            allowed = item.optLong("allowed").coerceAtLeast(0L),
                            report = item.optString("report")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, snapshots: List<Snapshot>) {
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(
                JSONObject()
                    .put("id", snapshot.id)
                    .put("packageName", snapshot.packageName)
                    .put("appLabel", snapshot.appLabel)
                    .put("startedAt", snapshot.startedAt)
                    .put("endedAt", snapshot.endedAt)
                    .put("decisions", snapshot.decisions)
                    .put("blocked", snapshot.blocked)
                    .put("allowed", snapshot.allowed)
                    .put("report", snapshot.report)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOTS, array.toString())
            .apply()
    }
}
