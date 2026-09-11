package com.guardianlayer.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianlayer.app.data.TrackerActivityStore
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Focused privacy intelligence dashboard built on Guardian's exact-attribution
 * Tracker Shield history. It never invents attribution and clearly separates
 * observed DNS evidence from coverage limitations.
 */
class PrivacyProfilesActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    private val pageBackground = Color.rgb(13, 14, 19)
    private val surface = Color.rgb(25, 27, 34)
    private val surfaceRaised = Color.rgb(32, 34, 43)
    private val violet = Color.rgb(164, 151, 255)
    private val violetSoft = Color.rgb(111, 99, 194)
    private val textPrimary = Color.rgb(244, 244, 248)
    private val textSecondary = Color.rgb(183, 186, 197)
    private val positive = Color.rgb(117, 211, 154)
    private val warning = Color.rgb(240, 194, 105)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Guardian Privacy"
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        renderProfiles()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageBackground)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(36))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    private fun renderProfiles() {
        root.removeAllViews()

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(
            text("GUARDIAN PRIVACY", 27f, textPrimary, Typeface.BOLD),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val back = MaterialButton(this).apply {
            text = "GUARDIAN"
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(38)
            setOnClickListener {
                startActivity(Intent(this@PrivacyProfilesActivity, MainActivity::class.java))
                finish()
            }
        }
        topBar.addView(back)
        root.addView(topBar)

        root.addView(
            text(
                "Observed behavior, explained in plain English.",
                14f,
                textSecondary,
                Typeface.NORMAL
            ).withTop(dp(4))
        )
        root.addView(statusPill("LOCAL · EXACT ATTRIBUTION", positive).withTop(dp(12)))

        val profiles = TrackerActivityStore.profiles(this)
        val totalDecisions = profiles.sumOf { it.cumulativeDecisions }
        val totalBlocks = profiles.sumOf { it.blockedDecisions }
        val totalAllowed = profiles.sumOf { it.allowedDecisions }
        val overallBlockRate = if (totalDecisions > 0L) {
            (totalBlocks * 100L / totalDecisions).coerceIn(0L, 100L)
        } else 0L

        root.addView(section("PRIVACY ACTIVITY").withTop(dp(26)))
        root.addView(
            heroCard(
                when {
                    profiles.isEmpty() -> "Guardian is ready to build trustworthy app privacy profiles from exact single-app Tracker Shield sessions."
                    totalBlocks == 0L -> "Guardian has observed $totalDecisions exact DNS decisions across ${profiles.size} app profile(s). No retained domain has matched Guardian's tracker intelligence yet."
                    else -> "Guardian has blocked $totalBlocks classified tracker request(s) while allowing $totalAllowed other observed DNS decision(s)."
                }
            ).withTop(dp(8))
        )

        root.addView(metricStrip(profiles.size, totalDecisions, totalBlocks).withTop(dp(10)))

        if (totalDecisions > 0L) {
            root.addView(
                rateCard(
                    title = "Observed tracker share",
                    percent = overallBlockRate.toInt(),
                    detail = "$overallBlockRate% of exact DNS decisions matched Guardian's local tracker intelligence. This is an observed traffic ratio, not a risk score."
                ).withTop(dp(10))
            )
        }

        root.addView(section("VISIBILITY").withTop(dp(24)))
        root.addView(coveragePanel().withTop(dp(8)))

        if (profiles.isEmpty()) {
            root.addView(section("APP PROFILES").withTop(dp(24)))
            root.addView(
                card(
                    "No exact per-app history yet. In Guardian, shield one app at a time and use it normally. Exact single-app sessions build a trustworthy local profile.",
                    14f,
                    Typeface.NORMAL
                ).withTop(dp(8))
            )
            return
        }

        root.addView(section("APP PROFILES").withTop(dp(26)))
        root.addView(
            text(
                "Tap-free summaries: the most useful evidence is surfaced first, with raw domains still visible below.",
                12f,
                textSecondary,
                Typeface.NORMAL
            ).withTop(dp(5))
        )
        profiles.forEach { profile ->
            root.addView(profileCard(profile).withTop(dp(12)))
        }

        root.addView(
            card(
                "Guardian stores this history locally. Recent detail is bounded while cumulative counters remain compact. Shared multi-app sessions are excluded because Guardian will not guess which app made a DNS request.",
                12f,
                Typeface.NORMAL
            ).withTop(dp(20))
        )
    }

    private fun metricStrip(apps: Int, decisions: Long, blocks: Long): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(metricTile(apps.toString(), "APPS"), weightedMetricParams(0))
        row.addView(metricTile(compactNumber(decisions), "DNS"), weightedMetricParams(dp(7)))
        row.addView(metricTile(compactNumber(blocks), "BLOCKED"), weightedMetricParams(dp(7)))
        return row
    }

    private fun weightedMetricParams(left: Int) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { leftMargin = left }

    private fun metricTile(value: String, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(12))
            background = rounded(surfaceRaised, 15f)
            addView(text(value, 21f, textPrimary, Typeface.BOLD))
            addView(text(label, 10f, violet, Typeface.BOLD).withTop(dp(3)))
        }
    }

    private fun heroCard(message: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = rounded(surface, 18f, strokeColor = violetSoft)
            addView(text("WHAT GUARDIAN SAW", 11f, violet, Typeface.BOLD))
            addView(text(message, 16f, textPrimary, Typeface.BOLD).withTop(dp(8)))
            addView(
                text(
                    "Evidence comes from exact, single-app DNS attribution only.",
                    12f,
                    textSecondary,
                    Typeface.NORMAL
                ).withTop(dp(9))
            )
        }
    }

    private fun rateCard(title: String, percent: Int, detail: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(surface, 16f)
            val titleRow = LinearLayout(this@PrivacyProfilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(
                text(title, 13f, textPrimary, Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            titleRow.addView(text("$percent%", 14f, violet, Typeface.BOLD))
            addView(titleRow)
            addView(progress(percent).withTop(dp(9)))
            addView(text(detail, 11f, textSecondary, Typeface.NORMAL).withTop(dp(8)))
        }
    }

    private fun coveragePanel(): LinearLayout {
        val privateDnsMode = runCatching {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull().orEmpty()
        val cm = getSystemService(ConnectivityManager::class.java)
        val activePrivateDns = runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            cm.getLinkProperties(network)?.isPrivateDnsActive == true
        }.getOrDefault(false)

        val privateDnsDetail = when {
            activePrivateDns -> "Android Private DNS is active. Encrypted resolver traffic is outside Guardian's plaintext-DNS filter."
            privateDnsMode == "off" -> "Android Private DNS is off. Ordinary DNS routed through Tracker Shield is visible and filterable."
            else -> "Private DNS is not currently validated as active. App-specific encrypted DNS can still be outside this view."
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(surface, 16f)
            addView(coverageRow("ORDINARY DNS", "Visible / filterable", positive))
            addView(divider().withTop(dp(11)))
            addView(coverageRow("ENCRYPTED DNS", if (activePrivateDns) "Limited visibility" else "Not fully visible", warning).withTop(dp(11)))
            addView(text(privateDnsDetail, 11f, textSecondary, Typeface.NORMAL).withTop(dp(5)))
            addView(divider().withTop(dp(11)))
            addView(coverageRow("CACHED / DIRECT IP", "No DNS event", textSecondary).withTop(dp(11)))
            addView(
                text(
                    "Guardian does not decrypt HTTPS and does not claim DNS-only filtering equals full tracker blocking.",
                    11f,
                    textSecondary,
                    Typeface.NORMAL
                ).withTop(dp(10))
            )
        }
    }

    private fun coverageRow(label: String, state: String, stateColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text(label, 11f, textPrimary, Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text(state, 11f, stateColor, Typeface.BOLD))
        }
    }

    private fun profileCard(profile: TrackerActivityStore.AppProfile): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(15))
            background = rounded(surface, 18f)
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(profile.appLabel, 20f, textPrimary, Typeface.BOLD))
            addView(text(profile.packageName, 10f, textSecondary, Typeface.NORMAL).withTop(dp(2)))
        }
        heading.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(statusPill(recencyLabel(profile.lastSeenAt), violet))
        container.addView(heading)

        val blockRate = if (profile.cumulativeDecisions > 0L) {
            (profile.blockedDecisions * 100L / profile.cumulativeDecisions).coerceIn(0L, 100L)
        } else 0L

        container.addView(
            text(summary(profile), 14f, Color.rgb(220, 222, 230), Typeface.NORMAL)
                .withTop(dp(13))
        )

        container.addView(
            rateCard(
                title = "Observed tracker share",
                percent = blockRate.toInt(),
                detail = "${profile.blockedDecisions} blocked · ${profile.allowedDecisions} allowed · ${profile.uniqueTrackerDomains} tracker domain(s)"
            ).withTop(dp(12))
        )

        if (profile.providers.isNotEmpty()) {
            container.addView(subhead("TRACKER COMPANIES").withTop(dp(16)))
            container.addView(
                text(
                    profile.providers.take(5).joinToString("\n") { "• ${it.name}   ${it.count}" },
                    13f,
                    textPrimary,
                    Typeface.NORMAL
                ).withTop(dp(6))
            )
        }

        if (profile.categories.isNotEmpty()) {
            container.addView(subhead("CATEGORIES").withTop(dp(14)))
            container.addView(
                text(
                    profile.categories.take(5).joinToString("\n") { "• ${it.name}   ${it.count}" },
                    13f,
                    textPrimary,
                    Typeface.NORMAL
                ).withTop(dp(6))
            )
        }

        if (profile.topTrackers.isNotEmpty()) {
            container.addView(subhead("MOST ACTIVE TRACKERS").withTop(dp(14)))
            container.addView(
                text(
                    profile.topTrackers.take(5).joinToString("\n") {
                        "• ${it.domain}\n  ${it.provider} · ×${it.count}"
                    },
                    12f,
                    Color.rgb(219, 221, 228),
                    Typeface.NORMAL
                ).withTop(dp(6))
            )
        }

        if (profile.recentDecisions.isNotEmpty()) {
            container.addView(subhead("RECENT EXACT ACTIVITY").withTop(dp(14)))
            container.addView(
                text(
                    profile.recentDecisions.take(4).joinToString("\n") { decision ->
                        val state = if (decision.blocked) "BLOCKED" else "ALLOWED"
                        val repeat = if (decision.count > 1L) " ×${decision.count}" else ""
                        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(decision.lastSeenAt))
                        "• $time  $state  ${decision.domain}$repeat"
                    },
                    12f,
                    textSecondary,
                    Typeface.NORMAL
                ).withTop(dp(6))
            )
        }

        val first = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(profile.firstSeenAt))
        val last = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(profile.lastSeenAt))
        container.addView(
            text(
                "Observed $first → $last\nRecent detail window: ${profile.retainedDecisions} decisions",
                11f,
                textSecondary,
                Typeface.NORMAL
            ).withTop(dp(15))
        )

        val clear = MaterialButton(this).apply {
            text = "CLEAR THIS APP'S HISTORY"
            textSize = 11f
            minHeight = dp(42)
            setOnClickListener { confirmClear(profile.packageName, profile.appLabel) }
        }
        container.addView(clear.withTop(dp(12)))

        return container
    }

    private fun summary(profile: TrackerActivityStore.AppProfile): String {
        if (profile.cumulativeDecisions == 0L) {
            return "Guardian has not retained enough exact DNS evidence to summarize this app yet."
        }
        if (profile.blockedDecisions == 0L) {
            return "Guardian observed ${profile.cumulativeDecisions} exact DNS decisions and did not classify any retained domain as a tracker. That does not prove the app performs no tracking; encrypted DNS, cached addresses, and direct-IP traffic can be outside this view."
        }

        val provider = profile.providers.firstOrNull()?.name
        val category = profile.categories.firstOrNull()?.name
        return buildString {
            append("Guardian blocked ${profile.blockedDecisions} classified tracker DNS request(s) across ${profile.uniqueTrackerDomains} domain(s) while other observed DNS requests were allowed.")
            if (provider != null) append(" Most blocked activity was associated with $provider.")
            if (category != null) append(" The leading observed category was $category.")
        }
    }

    private fun recencyLabel(timestamp: Long): String {
        val ageMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMs)
        val hours = TimeUnit.MILLISECONDS.toHours(ageMs)
        val days = TimeUnit.MILLISECONDS.toDays(ageMs)
        return when {
            minutes < 2 -> "NOW"
            minutes < 60 -> "${minutes}M AGO"
            hours < 24 -> "${hours}H AGO"
            else -> "${days}D AGO"
        }
    }

    private fun compactNumber(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    private fun confirmClear(packageName: String, appLabel: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear $appLabel history?")
            .setMessage(
                "This removes Guardian's locally stored exact DNS history and cumulative counters for this app only. Firewall and Tracker Shield rules are not changed."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CLEAR") { _, _ ->
                TrackerActivityStore.clearApp(this, packageName)
                renderProfiles()
            }
            .show()
    }

    private fun progress(percent: Int) = ProgressBar(
        this,
        null,
        android.R.attr.progressBarStyleHorizontal
    ).apply {
        max = 100
        progress = percent.coerceIn(0, 100)
        progressTintList = ColorStateList.valueOf(violet)
        progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(52, 54, 65))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(7)
        )
    }

    private fun statusPill(value: String, color: Int) = text(
        value,
        10f,
        color,
        Typeface.BOLD
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(Color.rgb(39, 40, 49), 99f, strokeColor = color)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.rgb(50, 52, 61))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
        setLineSpacing(0f, 1.08f)
    }

    private fun card(value: String, size: Float, style: Int) = text(
        value,
        size,
        Color.rgb(226, 228, 234),
        style
    ).apply {
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(surface, 16f)
    }

    private fun section(value: String) = text(
        value,
        12f,
        violet,
        Typeface.BOLD
    )

    private fun subhead(value: String) = text(
        value,
        10f,
        violet,
        Typeface.BOLD
    )

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun TextView.withTop(top: Int): TextView {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        return this
    }

    private fun MaterialButton.withTop(top: Int): MaterialButton {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        return this
    }

    private fun LinearLayout.withTop(top: Int): LinearLayout {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        return this
    }

    private fun View.withTop(top: Int): View {
        val existing = layoutParams
        layoutParams = LinearLayout.LayoutParams(
            existing?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            existing?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
