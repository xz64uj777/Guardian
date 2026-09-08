package com.guardianlayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded, local-only history for DNS decisions that Guardian can attribute to
 * exactly one shielded app. Shared-attribution sessions are intentionally not
 * written here because Guardian will not guess which app owned a request.
 */
object TrackerActivityStore {
    private const val PREFS = "guardian_tracker_activity"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 300
    private const val DEDUPE_WINDOW_MS = 1_500L
    private const val PERSIST_DEBOUNCE_MS = 750L

    data class Decision(
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val packageName: String,
        val appLabel: String,
        val domain: String,
        val category: String?,
        val provider: String?,
        val blocked: Boolean,
        val count: Long
    )

    data class TrackerStat(
        val domain: String,
        val category: String,
        val provider: String,
        val count: Long,
        val lastSeenAt: Long
    )

    data class NamedCount(
        val name: String,
        val count: Long
    )

    data class AppProfile(
        val packageName: String,
        val appLabel: String,
        val retainedDecisions: Long,
        val blockedDecisions: Long,
        val allowedDecisions: Long,
        val uniqueTrackerDomains: Int,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val topTrackers: List<TrackerStat>,
        val categories: List<NamedCount>,
        val providers: List<NamedCount>,
        val recentDecisions: List<Decision>
    )

    @Volatile
    private var cache: MutableList<Decision>? = null

    @Volatile
    private var persistScheduled = false

    private val persistExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "guardian-tracker-history").apply { isDaemon = true }
    }

    @Synchronized
    fun recordExact(
        context: Context,
        packageName: String,
        appLabel: String,
        domain: String,
        category: String?,
        provider: String?,
        blocked: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (packageName.isBlank() || domain.isBlank()) return

        val entries = entries(context)
        val normalizedDomain = domain.trim().trimEnd('.').lowercase(Locale.US)
        val duplicateIndex = entries.indexOfFirst { existing ->
            existing.packageName == packageName &&
                existing.domain == normalizedDomain &&
                existing.blocked == blocked &&
                timestamp - existing.lastSeenAt in 0..DEDUPE_WINDOW_MS
        }

        val next = if (duplicateIndex >= 0) {
            val previous = entries.removeAt(duplicateIndex)
            previous.copy(
                lastSeenAt = timestamp,
                appLabel = appLabel,
                category = category ?: previous.category,
                provider = provider ?: previous.provider,
                count = previous.count + 1L
            )
        } else {
            Decision(
                firstSeenAt = timestamp,
                lastSeenAt = timestamp,
                packageName = packageName,
                appLabel = appLabel,
                domain = normalizedDomain,
                category = category,
                provider = provider,
                blocked = blocked,
                count = 1L
            )
        }

        entries.add(0, next)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        schedulePersist(context.applicationContext)
    }

    @Synchronized
    fun profile(context: Context, packageName: String): AppProfile? {
        val appEntries = entries(context)
            .filter { it.packageName == packageName }
        if (appEntries.isEmpty()) return null

        val retained = appEntries.sumOf { it.count }
        val blocked = appEntries.filter { it.blocked }.sumOf { it.count }
        val allowed = retained - blocked
        val trackerEntries = appEntries.filter { it.blocked }

        val topTrackers = trackerEntries
            .groupBy { it.domain }
            .map { (domain, matches) ->
                val newest = matches.maxByOrNull { it.lastSeenAt }!!
                TrackerStat(
                    domain = domain,
                    category = newest.category ?: "Tracker",
                    provider = newest.provider ?: "Unknown provider",
                    count = matches.sumOf { it.count },
                    lastSeenAt = matches.maxOf { it.lastSeenAt }
                )
            }
            .sortedWith(
                compareByDescending<TrackerStat> { it.count }
                    .thenByDescending { it.lastSeenAt }
            )
            .take(8)

        val categories = trackerEntries
            .mapNotNull { entry -> entry.category?.let { it to entry.count } }
            .groupBy({ it.first }, { it.second })
            .map { (name, counts) -> NamedCount(name, counts.sum()) }
            .sortedByDescending { it.count }
            .take(6)

        val providers = trackerEntries
            .mapNotNull { entry -> entry.provider?.let { it to entry.count } }
            .groupBy({ it.first }, { it.second })
            .map { (name, counts) -> NamedCount(name, counts.sum()) }
            .sortedByDescending { it.count }
            .take(6)

        return AppProfile(
            packageName = packageName,
            appLabel = appEntries.first().appLabel,
            retainedDecisions = retained,
            blockedDecisions = blocked,
            allowedDecisions = allowed,
            uniqueTrackerDomains = trackerEntries.map { it.domain }.toSet().size,
            firstSeenAt = appEntries.minOf { it.firstSeenAt },
            lastSeenAt = appEntries.maxOf { it.lastSeenAt },
            topTrackers = topTrackers,
            categories = categories,
            providers = providers,
            recentDecisions = appEntries.sortedByDescending { it.lastSeenAt }.take(8)
        )
    }

    @Synchronized
    fun flush(context: Context) {
        val snapshot = entries(context).toList()
        persistNow(context.applicationContext, snapshot)
        persistScheduled = false
    }

    @Synchronized
    fun clear(context: Context) {
        cache = mutableListOf()
        persistScheduled = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }

    private fun entries(context: Context): MutableList<Decision> {
        cache?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = read(prefs.getString(KEY_ENTRIES, null))
        cache = loaded
        return loaded
    }

    private fun schedulePersist(context: Context) {
        if (persistScheduled) return
        persistScheduled = true
        persistExecutor.schedule(
            {
                val snapshot = synchronized(this) {
                    persistScheduled = false
                    cache?.toList() ?: emptyList()
                }
                persistNow(context, snapshot)
            },
            PERSIST_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun persistNow(context: Context, entries: List<Decision>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("firstSeenAt", entry.firstSeenAt)
                    .put("lastSeenAt", entry.lastSeenAt)
                    .put("packageName", entry.packageName)
                    .put("appLabel", entry.appLabel)
                    .put("domain", entry.domain)
                    .put("category", entry.category ?: JSONObject.NULL)
                    .put("provider", entry.provider ?: JSONObject.NULL)
                    .put("blocked", entry.blocked)
                    .put("count", entry.count)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }

    private fun read(raw: String?): MutableList<Decision> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("packageName")
                    val domain = item.optString("domain")
                    if (packageName.isBlank() || domain.isBlank()) continue
                    add(
                        Decision(
                            firstSeenAt = item.optLong("firstSeenAt"),
                            lastSeenAt = item.optLong("lastSeenAt"),
                            packageName = packageName,
                            appLabel = item.optString("appLabel", packageName),
                            domain = domain,
                            category = item.optString("category").takeIf { it.isNotBlank() && it != "null" },
                            provider = item.optString("provider").takeIf { it.isNotBlank() && it != "null" },
                            blocked = item.optBoolean("blocked"),
                            count = item.optLong("count", 1L).coerceAtLeast(1L)
                        )
                    )
                }
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }
}
