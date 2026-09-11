package com.guardianlayer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.guardianlayer.app.data.TrackerActivityStore
import com.guardianlayer.app.firewall.GuardianVpnService
import java.text.NumberFormat

class HomeDashboardActivity : AppCompatActivity() {

    private val page = Color.rgb(11, 12, 17)
    private val surface = Color.rgb(24, 26, 34)
    private val raised = Color.rgb(31, 33, 43)
    private val primary = Color.rgb(246, 246, 250)
    private val secondary = Color.rgb(177, 181, 193)
    private val violet = Color.rgb(166, 151, 255)
    private val green = Color.rgb(116, 213, 155)
    private val amber = Color.rgb(239, 191, 103)

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Guardian"
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(34))
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

    private fun render() {
        root.removeAllViews()

        root.addView(text("GUARDIAN", 32f, primary, Typeface.BOLD))
        root.addView(text("Security and privacy at a glance", 14f, secondary, Typeface.NORMAL).top(dp(4)))

        val mode = GuardianVpnService.currentMode()
        val snapshot = GuardianVpnService.trafficSnapshot()
        val profiles = TrackerActivityStore.profiles(this)
        val totalBlocks = profiles.sumOf { it.blockedDecisions }
        val totalDecisions = profiles.sumOf { it.cumulativeDecisions }

        val stateTitle = when (mode) {
            GuardianVpnService.Mode.LOCKDOWN -> "LOCK DOWN ACTIVE"
            GuardianVpnService.Mode.FIREWALL -> "SMART FIREWALL ACTIVE"
            GuardianVpnService.Mode.TRACKER_SHIELD -> "TRACKER SHIELD ACTIVE"
            GuardianVpnService.Mode.OFF -> "GUARDIAN READY"
        }
        val stateBody = when (mode) {
            GuardianVpnService.Mode.LOCKDOWN -> "Guardian is currently stopping network traffic through its emergency VPN mode."
            GuardianVpnService.Mode.FIREWALL -> "Selected apps are being routed into Guardian's blocking firewall."
            GuardianVpnService.Mode.TRACKER_SHIELD -> "Selected apps stay online while Guardian filters visible tracker DNS requests."
            GuardianVpnService.Mode.OFF -> "No Guardian VPN mode is active right now. Your saved rules and privacy history are still available."
        }
        val stateColor = when (mode) {
            GuardianVpnService.Mode.LOCKDOWN -> amber
            GuardianVpnService.Mode.OFF -> secondary
            else -> green
        }

        root.addView(hero(stateTitle, stateBody, stateColor).top(dp(22)))

        root.addView(section("TODAY'S VIEW").top(dp(24)))
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        metrics.addView(metric(compact(totalBlocks), "TRACKER BLOCKS"), weight())
        metrics.addView(metric(compact(totalDecisions), "DNS DECISIONS"), weight(dp(8)))
        metrics.addView(metric(profiles.size.toString(), "APP PROFILES"), weight(dp(8)))
        root.addView(metrics.top(dp(9)))

        root.addView(section("QUICK ACCESS").top(dp(25)))
        root.addView(actionCard(
            title = "Privacy Intelligence",
            subtitle = if (profiles.isEmpty()) {
                "No exact app profile yet. Shield one app at a time to begin."
            } else {
                "${profiles.size} app profile(s) · ${NumberFormat.getIntegerInstance().format(totalBlocks)} classified tracker request(s) blocked."
            },
            buttonText = "OPEN PRIVACY DASHBOARD"
        ) {
            startActivity(Intent(this, PrivacyProfilesActivity::class.java))
        }.top(dp(9)))

        root.addView(actionCard(
            title = "Network Controls",
            subtitle = when (mode) {
                GuardianVpnService.Mode.TRACKER_SHIELD -> "Tracker Shield is running. Open controls to change shielded apps or stop it."
                GuardianVpnService.Mode.FIREWALL -> "Smart Firewall is running. Open controls to change blocked apps or stop it."
                GuardianVpnService.Mode.LOCKDOWN -> "Lock Down is active. Open controls to restore networking or change modes."
                GuardianVpnService.Mode.OFF -> "Manage Smart Firewall, Tracker Shield, Lock Down, app rules, and live network evidence."
            },
            buttonText = "OPEN NETWORK CONTROLS"
        ) {
            startActivity(Intent(this, MainActivity::class.java))
        }.top(dp(11)))

        root.addView(section("RECENT PROTECTION").top(dp(25)))
        root.addView(
            card(
                buildString {
                    if (snapshot.sessionMode == GuardianVpnService.Mode.TRACKER_SHIELD || mode == GuardianVpnService.Mode.TRACKER_SHIELD) {
                        append("Tracker Shield\n")
                        append("${snapshot.dnsQueries} DNS queries · ${snapshot.trackerQueriesBlocked} blocked · ${snapshot.dnsFailures} upstream failures")
                        if (snapshot.uniqueTrackerDomainsBlocked > 0) {
                            append("\n${snapshot.uniqueTrackerDomainsBlocked} unique tracker domain(s) blocked")
                        }
                    } else if (snapshot.sessionMode == GuardianVpnService.Mode.FIREWALL || mode == GuardianVpnService.Mode.FIREWALL) {
                        append("Smart Firewall\n")
                        append("${snapshot.packets} packets · ${formatBytes(snapshot.bytes)} dropped")
                    } else if (snapshot.sessionMode == GuardianVpnService.Mode.LOCKDOWN || mode == GuardianVpnService.Mode.LOCKDOWN) {
                        append("Lock Down\n")
                        append("${snapshot.packets} packets · ${formatBytes(snapshot.bytes)} dropped")
                    } else {
                        append("No Guardian network session recorded yet.")
                    }
                }
            ).top(dp(9))
        )

        root.addView(
            text(
                "Guardian reports only what it can defend with local evidence. DNS-only Tracker Shield does not decrypt HTTPS or claim complete visibility into encrypted DNS, cached addresses, or direct-IP traffic.",
                11f,
                secondary,
                Typeface.NORMAL
            ).top(dp(18))
        )
    }

    private fun hero(title: String, body: String, accent: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(surface, 20f, accent)
            addView(text("PROTECTION STATUS", 11f, accent, Typeface.BOLD))
            addView(text(title, 23f, primary, Typeface.BOLD).top(dp(8)))
            addView(text(body, 13f, secondary, Typeface.NORMAL).top(dp(8)))
        }
    }

    private fun actionCard(
        title: String,
        subtitle: String,
        buttonText: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = rounded(surface, 18f)
            addView(text(title, 18f, primary, Typeface.BOLD))
            addView(text(subtitle, 13f, secondary, Typeface.NORMAL).top(dp(6)))
            addView(MaterialButton(this@HomeDashboardActivity).apply {
                text = buttonText
                textSize = 12f
                minHeight = dp(46)
                setOnClickListener { onClick() }
            }.top(dp(12)))
        }
    }

    private fun metric(value: String, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(15), dp(7), dp(13))
            background = rounded(raised, 16f)
            addView(text(value, 21f, primary, Typeface.BOLD))
            addView(text(label, 9f, violet, Typeface.BOLD).top(dp(4)))
        }
    }

    private fun card(value: String): TextView {
        return text(value, 14f, primary, Typeface.NORMAL).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(surface, 17f)
        }
    }

    private fun section(value: String) = text(value, 11f, violet, Typeface.BOLD)

    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
        setLineSpacing(0f, 1.08f)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun weight(left: Int = 0) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { leftMargin = left }

    private fun <T : android.view.View> T.top(px: Int): T {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams)?.apply { topMargin = px }
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px }
        return this
    }

    private fun compact(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
