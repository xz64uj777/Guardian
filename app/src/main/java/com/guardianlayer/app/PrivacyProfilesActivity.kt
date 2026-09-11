package com.guardianlayer.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianlayer.app.data.TrackerActivityStore
import java.text.DateFormat
import java.util.Date

/**
 * Focused, read-only-first privacy intelligence dashboard built on Guardian's
 * exact-attribution Tracker Shield history. It never invents attribution and
 * clearly separates observed DNS evidence from coverage limitations.
 */
class PrivacyProfilesActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

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
            setBackgroundColor(Color.rgb(17, 19, 24))
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
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

        root.addView(text("GUARDIAN PRIVACY", 28f, Color.WHITE, Typeface.BOLD))
        root.addView(
            text(
                "Observed behavior, not guesses.",
                14f,
                Color.rgb(178, 181, 190),
                Typeface.NORMAL
            ).withTop(dp(4))
        )

        val profiles = TrackerActivityStore.profiles(this)
        val totalDecisions = profiles.sumOf { it.cumulativeDecisions }
        val totalBlocks = profiles.sumOf { it.blockedDecisions }
        val totalAllowed = profiles.sumOf { it.allowedDecisions }

        root.addView(section("AT A GLANCE").withTop(dp(24)))
        root.addView(
            card(
                buildString {
                    append("${profiles.size} app profile(s)\n")
                    append("$totalDecisions exact DNS decisions\n")
                    append("$totalBlocks classified tracker requests blocked\n")
                    append("$totalAllowed allowed DNS decisions")
                },
                16f,
                Typeface.BOLD
            ).withTop(dp(8))
        )

        root.addView(section("COVERAGE").withTop(dp(20)))
        root.addView(card(coverageText(), 14f, Typeface.NORMAL).withTop(dp(8)))

        if (profiles.isEmpty()) {
            root.addView(section("PRIVACY PROFILES").withTop(dp(20)))
            root.addView(
                card(
                    "No exact per-app history yet. In Guardian, shield one app at a time and use it normally. Exact single-app sessions build a trustworthy local profile.",
                    14f,
                    Typeface.NORMAL
                ).withTop(dp(8))
            )
            return
        }

        root.addView(section("PRIVACY PROFILES").withTop(dp(22)))
        profiles.forEach { profile ->
            root.addView(profileCard(profile).withTop(dp(10)))
        }

        root.addView(
            card(
                "Guardian stores this history locally. Recent detail is bounded, while cumulative counters remain compact. Shared multi-app sessions are excluded because Guardian will not guess which app made a DNS request.",
                12f,
                Typeface.NORMAL
            ).withTop(dp(18))
        )
    }

    private fun profileCard(profile: TrackerActivityStore.AppProfile): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = rounded(Color.rgb(27, 30, 37), 16f)
        }

        container.addView(text(profile.appLabel, 19f, Color.WHITE, Typeface.BOLD))
        container.addView(
            text(profile.packageName, 11f, Color.rgb(166, 170, 180), Typeface.NORMAL)
                .withTop(dp(2))
        )

        val blockRate = if (profile.cumulativeDecisions > 0L) {
            (profile.blockedDecisions * 100L / profile.cumulativeDecisions).coerceIn(0L, 100L)
        } else 0L

        container.addView(
            text(
                "${profile.blockedDecisions} blocked · ${profile.allowedDecisions} allowed · $blockRate% of observed DNS decisions matched Guardian tracker intelligence",
                14f,
                Color.rgb(230, 231, 236),
                Typeface.BOLD
            ).withTop(dp(12))
        )

        container.addView(
            text(summary(profile), 14f, Color.rgb(205, 208, 216), Typeface.NORMAL)
                .withTop(dp(10))
        )

        if (profile.providers.isNotEmpty()) {
            container.addView(text("TRACKER COMPANIES", 11f, Color.rgb(160, 151, 255), Typeface.BOLD).withTop(dp(14)))
            container.addView(
                text(
                    profile.providers.take(4).joinToString("\n") { "• ${it.name} · ${it.count}" },
                    13f,
                    Color.rgb(222, 224, 230),
                    Typeface.NORMAL
                ).withTop(dp(5))
            )
        }

        if (profile.categories.isNotEmpty()) {
            container.addView(text("CATEGORIES", 11f, Color.rgb(160, 151, 255), Typeface.BOLD).withTop(dp(12)))
            container.addView(
                text(
                    profile.categories.take(4).joinToString("\n") { "• ${it.name} · ${it.count}" },
                    13f,
                    Color.rgb(222, 224, 230),
                    Typeface.NORMAL
                ).withTop(dp(5))
            )
        }

        if (profile.topTrackers.isNotEmpty()) {
            container.addView(text("MOST ACTIVE TRACKERS", 11f, Color.rgb(160, 151, 255), Typeface.BOLD).withTop(dp(12)))
            container.addView(
                text(
                    profile.topTrackers.take(4).joinToString("\n") {
                        "• ${it.domain} · ${it.provider} · ×${it.count}"
                    },
                    13f,
                    Color.rgb(222, 224, 230),
                    Typeface.NORMAL
                ).withTop(dp(5))
            )
        }

        val first = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(profile.firstSeenAt))
        val last = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(profile.lastSeenAt))
        container.addView(
            text(
                "Observed $first → $last\nRecent detail window: ${profile.retainedDecisions} decisions",
                12f,
                Color.rgb(166, 170, 180),
                Typeface.NORMAL
            ).withTop(dp(12))
        )

        val clear = MaterialButton(this).apply {
            text = "CLEAR THIS APP'S HISTORY"
            textSize = 12f
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
            return "Guardian observed ${profile.cumulativeDecisions} exact DNS decisions and did not classify any retained domains as trackers. This is not proof that the app performs no tracking; encrypted DNS, cached addresses, and direct-IP traffic can be outside this view."
        }

        val provider = profile.providers.firstOrNull()?.name
        val category = profile.categories.firstOrNull()?.name
        return buildString {
            append("Guardian blocked ${profile.blockedDecisions} classified tracker DNS request(s) across ${profile.uniqueTrackerDomains} domain(s) while other observed DNS requests were allowed.")
            if (provider != null) append(" The largest observed tracker company was $provider.")
            if (category != null) append(" The leading category was $category.")
        }
    }

    private fun coverageText(): String {
        val privateDnsMode = runCatching {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull().orEmpty()

        val cm = getSystemService(ConnectivityManager::class.java)
        val activePrivateDns = runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            cm.getLinkProperties(network)?.isPrivateDnsActive == true
        }.getOrDefault(false)

        val privateDns = when {
            activePrivateDns -> "Android Private DNS is active. Encrypted DNS may be outside Tracker Shield's plaintext-DNS visibility."
            privateDnsMode == "off" -> "Android Private DNS is off. Guardian can filter ordinary DNS used by shielded apps when it passes through the DNS-only tunnel."
            else -> "Android Private DNS state is not currently validated as active. Guardian still does not claim visibility into app-specific DoH/DoT."
        }

        return "ORDINARY DNS · visible/filterable when routed through Tracker Shield\n\n" +
            "ENCRYPTED DNS · limited visibility\n$privateDns\n\n" +
            "CACHED / DIRECT-IP TRAFFIC · no DNS event to classify\n\n" +
            "Guardian does not decrypt HTTPS or claim that DNS-only filtering equals full tracker blocking."
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
        background = rounded(Color.rgb(27, 30, 37), 16f)
    }

    private fun section(value: String) = text(
        value,
        12f,
        Color.rgb(160, 151, 255),
        Typeface.BOLD
    )

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
