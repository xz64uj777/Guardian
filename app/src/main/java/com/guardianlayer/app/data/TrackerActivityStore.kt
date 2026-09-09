package com.guardianlayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local-only history for DNS decisions that Guardian can attribute to exactly
 * one shielded app. Recent decision rows are bounded, while compact cumulative
 * counters are stored separately so totals do not go backwards when old rows
 * age out. Shared-attribution sessions are never written here.
 */
object TrackerActivityStore {
    private const val PREFS = "guardian_tracker_activity"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_LIFETIME = "lifetime_v1"
    private const val MAX_ENTRIES = 300
    private const val MAX_TRACKER_DOMAINS_PER_APP = 120
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
        val retainedBlockedDecisions: Long,
        val retainedAllowedDecisions: Long,
        val cumulativeDecisions: Long,
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

    private data class LifetimeTracker(
        val domain: String,
        val category: String,
        val provider: String,
        val count: Long,
        val lastSeenAt: Long
    )

    private data class LifetimeAggregate(
        val packageName: String,
        val appLabel: String,
        val totalDecisions: Long,
        val blockedDecisions: Long,
        val allowedDecisions: Long,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val trackers: MutableMap<String, LifetimeTracker>
    )

    @Volatile
    private var cache: MutableList<Decision>? = null

    @Volatile
    private var lifetimeCache: MutableMap<String, LifetimeAggregate>? = null

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
        val lifetime = lifetime(context, entries)
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

        val previousAggregate = lifetime[packageName]
        val trackers = previousAggregate?.trackers?.toMutableMap() ?: linkedMapOf()
        if (blocked) {
            val previousTracker = trackers[normalizedDomain]
            trackers[normalizedDomain] = LifetimeTracker(
                domain = normalizedDomain,
                category = category ?: previousTracker?.category ?: "Tracker",
                provider = provider ?: previousTracker?.provider ?: "Unknown provider",
                count = (previousTracker?.count ?: 0L) + 1L,
                lastSeenAt = timestamp
            )
            trimTrackerDomains(trackers)
        }

        lifetime[packageName] = LifetimeAggregate(
            packageName = packageName,
            appLabel = appLabel,
            totalDecisions = (previousAggregate?.totalDecisions ?: 0L) + 1L,
            blockedDecisions = (previousAggregate?.blockedDecisions ?: 0L) + if (blocked) 1L else 0L,
            allowedDecisions = (previousAggregate?.allowedDecisions ?: 0L) + if (blocked) 0L else 1L,
            firstSeenAt = previousAggregate?.firstSeenAt?.takeIf { it > 0L } ?: timestamp,
            lastSeenAt = timestamp,
            trackers = trackers
        )

        schedulePersist(context.applicationContext)
    }

    @Synchronized
    fun profile(context: Context, packageName: String): AppProfile? {
        val entries = entries(context)
        val lifetime = lifetime(context, entries)
        return buildProfile(
            entries.filter { it.packageName == packageName },
            lifetime[packageName]
        )
    }

    @Synchronized
    fun profiles(context: Context): List<AppProfile> {
        val entries = entries(context)
        val lifetime = lifetime(context, entries)
        val grouped = entries.groupBy { it.packageName }
        val packageNames = linkedSetOf<String>().apply {
            addAll(lifetime.keys)
            addAll(grouped.keys)
        }
        return packageNames
            .mapNotNull { packageName -> buildProfile(grouped[packageName].orEmpty(), lifetime[packageName]) }
            .sortedWith(
                compareByDescending<AppProfile> { it.lastSeenAt }
                    .thenByDescending { it.blockedDecisions }
            )
    }

    @Synchronized
    fun flush(context: Context) {
        val entriesSnapshot = entries(context).toList()
        val lifetimeSnapshot = copyLifetime(lifetime(context, entries(context)))
        persistNow(context.applicationContext, entriesSnapshot, lifetimeSnapshot)
        persistScheduled = false
    }

    /** Delete the exact-attribution history and cumulative counters for one app. */
    @Synchronized
    fun clearApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val entries = entries(context)
        val lifetime = lifetime(context, entries)
        val removedEntries = entries.removeAll { it.packageName == packageName }
        val removedLifetime = lifetime.remove(packageName) != null
        val removed = removedEntries || removedLifetime
        if (removed) {
            persistNow(
                context.applicationContext,
                entries.toList(),
                copyLifetime(lifetime)
            )
        }
        return removed
    }

    /** Delete all locally retained exact-attribution Tracker Shield history. */
    @Synchronized
    fun clear(context: Context) {
        cache = mutableListOf()
        lifetimeCache = linkedMapOf()
        persistScheduled = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .remove(KEY_LIFETIME)
            .apply()
    }

    private fun buildProfile(
        appEntries: List<Decision>,
        aggregate: LifetimeAggregate?
    ): AppProfile? {
        if (appEntries.isEmpty() && aggregate == null) return null

        val retained = appEntries.sumOf { it.count }
        val retainedBlocked = appEntries.filter { it.blocked }.sumOf { it.count }
        val retainedAllowed = retained - retainedBlocked

        val effectiveAggregate = aggregate ?: migrateAggregate(appEntries)
        val trackers = effectiveAggregate.trackers.values.toList()

        val topTrackers = trackers
            .sortedWith(
                compareByDescending<LifetimeTracker> { it.count }
                    .thenByDescending { it.lastSeenAt }
            )
            .take(8)
            .map {
                TrackerStat(
                    domain = it.domain,
                    category = it.category,
                    provider = it.provider,
                    count = it.count,
                    lastSeenAt = it.lastSeenAt
                )
            }

        val categories = trackers
            .groupBy { it.category }
            .map { (name, matches) -> NamedCount(name, matches.sumOf { it.count }) }
            .sortedByDescending { it.count }
            .take(6)

        val providers = trackers
            .groupBy { it.provider }
            .map { (name, matches) -> NamedCount(name, matches.sumOf { it.count }) }
            .sortedByDescending { it.count }
            .take(6)

        return AppProfile(
            packageName = effectiveAggregate.packageName,
            appLabel = effectiveAggregate.appLabel,
            retainedDecisions = retained,
            retainedBlockedDecisions = retainedBlocked,
            retainedAllowedDecisions = retainedAllowed,
            cumulativeDecisions = effectiveAggregate.totalDecisions,
            blockedDecisions = effectiveAggregate.blockedDecisions,
            allowedDecisions = effectiveAggregate.allowedDecisions,
            uniqueTrackerDomains = trackers.size,
            firstSeenAt = effectiveAggregate.firstSeenAt,
            lastSeenAt = effectiveAggregate.lastSeenAt,
            topTrackers = topTrackers,
            categories = categories,
            providers = providers,
            recentDecisions = appEntries.sortedByDescending { it.lastSeenAt }.take(8)
        )
    }

    private fun entries(context: Context): MutableList<Decision> {
        cache?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = readEntries(prefs.getString(KEY_ENTRIES, null))
        cache = loaded
        return loaded
    }

    private fun lifetime(
        context: Context,
        fallbackEntries: List<Decision>
    ): MutableMap<String, LifetimeAggregate> {
        lifetimeCache?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIFETIME, null)
        val loaded = readLifetime(raw)
        val migrated = if (raw.isNullOrBlank() && loaded.isEmpty() && fallbackEntries.isNotEmpty()) {
            fallbackEntries
                .groupBy { it.packageName }
                .mapValuesTo(linkedMapOf()) { (_, decisions) -> migrateAggregate(decisions) }
        } else {
            loaded
        }
        lifetimeCache = migrated
        if (raw.isNullOrBlank() && migrated.isNotEmpty()) {
            persistNow(context.applicationContext, fallbackEntries.toList(), copyLifetime(migrated))
        }
        return migrated
    }

    private fun migrateAggregate(entries: List<Decision>): LifetimeAggregate {
        require(entries.isNotEmpty())
        val newest = entries.maxByOrNull { it.lastSeenAt } ?: entries.first()
        val trackerMap = linkedMapOf<String, LifetimeTracker>()
        entries.filter { it.blocked }.groupBy { it.domain }.forEach { (domain, matches) ->
            val latest = matches.maxByOrNull { it.lastSeenAt } ?: matches.first()
            trackerMap[domain] = LifetimeTracker(
                domain = domain,
                category = latest.category ?: "Tracker",
                provider = latest.provider ?: "Unknown provider",
                count = matches.sumOf { it.count },
                lastSeenAt = matches.maxOf { it.lastSeenAt }
            )
        }
        trimTrackerDomains(trackerMap)
        val total = entries.sumOf { it.count }
        val blocked = entries.filter { it.blocked }.sumOf { it.count }
        return LifetimeAggregate(
            packageName = newest.packageName,
            appLabel = newest.appLabel,
            totalDecisions = total,
            blockedDecisions = blocked,
            allowedDecisions = total - blocked,
            firstSeenAt = entries.minOf { it.firstSeenAt },
            lastSeenAt = entries.maxOf { it.lastSeenAt },
            trackers = trackerMap
        )
    }

    private fun trimTrackerDomains(trackers: MutableMap<String, LifetimeTracker>) {
        while (trackers.size > MAX_TRACKER_DOMAINS_PER_APP) {
            val victim = trackers.values.minWithOrNull(
                compareBy<LifetimeTracker> { it.count }.thenBy { it.lastSeenAt }
            ) ?: break
            trackers.remove(victim.domain)
        }
    }

    private fun schedulePersist(context: Context) {
        if (persistScheduled) return
        persistScheduled = true
        persistExecutor.schedule(
            {
                val snapshots = synchronized(this) {
                    persistScheduled = false
                    val entrySnapshot = cache?.toList() ?: emptyList()
                    val lifetimeSnapshot = copyLifetime(lifetimeCache.orEmpty())
                    entrySnapshot to lifetimeSnapshot
                }
                persistNow(context, snapshots.first, snapshots.second)
            },
            PERSIST_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun copyLifetime(
        source: Map<String, LifetimeAggregate>
    ): Map<String, LifetimeAggregate> = source.mapValues { (_, aggregate) ->
        aggregate.copy(trackers = LinkedHashMap(aggregate.trackers))
    }

    private fun persistNow(
        context: Context,
        entries: List<Decision>,
        lifetime: Map<String, LifetimeAggregate>
    ) {
        val entryArray = JSONArray()
        entries.forEach { entry ->
            entryArray.put(
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

        val lifetimeArray = JSONArray()
        lifetime.values.forEach { aggregate ->
            val trackerArray = JSONArray()
            aggregate.trackers.values.forEach { tracker ->
                trackerArray.put(
                    JSONObject()
                        .put("domain", tracker.domain)
                        .put("category", tracker.category)
                        .put("provider", tracker.provider)
                        .put("count", tracker.count)
                        .put("lastSeenAt", tracker.lastSeenAt)
                )
            }
            lifetimeArray.put(
                JSONObject()
                    .put("packageName", aggregate.packageName)
                    .put("appLabel", aggregate.appLabel)
                    .put("totalDecisions", aggregate.totalDecisions)
                    .put("blockedDecisions", aggregate.blockedDecisions)
                    .put("allowedDecisions", aggregate.allowedDecisions)
                    .put("firstSeenAt", aggregate.firstSeenAt)
                    .put("lastSeenAt", aggregate.lastSeenAt)
                    .put("trackers", trackerArray)
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, entryArray.toString())
            .putString(KEY_LIFETIME, lifetimeArray.toString())
            .apply()
    }

    private fun readEntries(raw: String?): MutableList<Decision> {
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
                            category = item.optString("category")
                                .takeIf { it.isNotBlank() && it != "null" },
                            provider = item.optString("provider")
                                .takeIf { it.isNotBlank() && it != "null" },
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

    private fun readLifetime(raw: String?): MutableMap<String, LifetimeAggregate> {
        if (raw.isNullOrBlank()) return linkedMapOf()
        return try {
            val array = JSONArray(raw)
            val result = linkedMapOf<String, LifetimeAggregate>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("packageName")
                if (packageName.isBlank()) continue
                val trackers = linkedMapOf<String, LifetimeTracker>()
                val trackerArray = item.optJSONArray("trackers") ?: JSONArray()
                for (trackerIndex in 0 until trackerArray.length()) {
                    val tracker = trackerArray.optJSONObject(trackerIndex) ?: continue
                    val domain = tracker.optString("domain")
                    if (domain.isBlank()) continue
                    trackers[domain] = LifetimeTracker(
                        domain = domain,
                        category = tracker.optString("category", "Tracker"),
                        provider = tracker.optString("provider", "Unknown provider"),
                        count = tracker.optLong("count", 1L).coerceAtLeast(1L),
                        lastSeenAt = tracker.optLong("lastSeenAt")
                    )
                }
                trimTrackerDomains(trackers)
                result[packageName] = LifetimeAggregate(
                    packageName = packageName,
                    appLabel = item.optString("appLabel", packageName),
                    totalDecisions = item.optLong("totalDecisions").coerceAtLeast(0L),
                    blockedDecisions = item.optLong("blockedDecisions").coerceAtLeast(0L),
                    allowedDecisions = item.optLong("allowedDecisions").coerceAtLeast(0L),
                    firstSeenAt = item.optLong("firstSeenAt"),
                    lastSeenAt = item.optLong("lastSeenAt"),
                    trackers = trackers
                )
            }
            result
        } catch (_: Exception) {
            linkedMapOf()
        }
    }
}
