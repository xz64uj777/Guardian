package com.guardianlayer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.guardianlayer.app.data.TrackerActivityStore
import com.guardianlayer.app.firewall.FirewallRuleStore
import com.guardianlayer.app.firewall.GuardianVpnService
import com.guardianlayer.app.firewall.TrackerShieldRuleStore
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
    private val red = Color.rgb(244, 122, 132)
    private lateinit var root: LinearLayout
    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); title = "Guardian"; setContentView(buildUi()) }
    override fun onResume() { super.onResume(); render() }

    private fun buildUi(): ScrollView = ScrollView(this).apply {
        setBackgroundColor(page); isFillViewport = true
        root = LinearLayout(this@HomeDashboardActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(34)) }
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun render() {
        root.removeAllViews()
        val mode = GuardianVpnService.currentMode()
        val snapshot = GuardianVpnService.trafficSnapshot()
        val profiles = TrackerActivityStore.profiles(this)
        val totalBlocks = profiles.sumOf { it.blockedDecisions }
        val totalDecisions = profiles.sumOf { it.cumulativeDecisions }
        val shieldCount = TrackerShieldRuleStore.protectedCount(this)
        val firewallCount = FirewallRuleStore.blockedCount(this)

        root.addView(text("GUARDIAN", 32f, primary, Typeface.BOLD))
        root.addView(text("Your phone. Your traffic. Your rules.", 14f, secondary, Typeface.NORMAL).top(dp(4)))

        val stateTitle = when (mode) { GuardianVpnService.Mode.LOCKDOWN -> "LOCK DOWN ACTIVE"; GuardianVpnService.Mode.FIREWALL -> "SMART FIREWALL ACTIVE"; GuardianVpnService.Mode.TRACKER_SHIELD -> "TRACKER SHIELD ACTIVE"; else -> "GUARDIAN READY" }
        val stateBody = when (mode) { GuardianVpnService.Mode.LOCKDOWN -> "Emergency network protection is active. Tap Restore Network when you're ready."; GuardianVpnService.Mode.FIREWALL -> "$firewallCount selected app(s) are being blocked by Guardian."; GuardianVpnService.Mode.TRACKER_SHIELD -> "$shieldCount selected app(s) are shielded while normal app traffic stays online."; else -> "Protection is ready. Start Tracker Shield, Firewall, or Lock Down below." }
        val stateColor = when (mode) { GuardianVpnService.Mode.LOCKDOWN -> amber; GuardianVpnService.Mode.OFF -> secondary; else -> green }
        root.addView(hero(stateTitle, stateBody, stateColor).top(dp(22)))

        root.addView(section("PROTECTION CONTROLS").top(dp(24)))
        val shieldReady = shieldCount > 0
        root.addView(controlCard("Tracker Shield", if (shieldReady) "$shieldCount app(s) selected · filters visible tracker DNS" else "Choose shielded apps in Network Controls first.", mode == GuardianVpnService.Mode.TRACKER_SHIELD, if (mode == GuardianVpnService.Mode.TRACKER_SHIELD) "STOP SHIELD" else "START SHIELD", green) {
            if (mode == GuardianVpnService.Mode.TRACKER_SHIELD) stopVpn() else if (!shieldReady) openControls() else requestVpnAndStart(GuardianVpnService.ACTION_TRACKER_SHIELD)
        }.top(dp(9)))
        root.addView(controlCard("Smart Firewall", if (firewallCount > 0) "$firewallCount app(s) selected · blocks their network traffic" else "Choose blocked apps in Network Controls first.", mode == GuardianVpnService.Mode.FIREWALL, if (mode == GuardianVpnService.Mode.FIREWALL) "STOP FIREWALL" else "START FIREWALL", violet) {
            if (mode == GuardianVpnService.Mode.FIREWALL) stopVpn() else if (firewallCount == 0) openControls() else requestVpnAndStart(GuardianVpnService.ACTION_FIREWALL)
        }.top(dp(10)))
        root.addView(controlCard("Emergency Lock Down", "Stops network traffic through Guardian until you restore it.", mode == GuardianVpnService.Mode.LOCKDOWN, if (mode == GuardianVpnService.Mode.LOCKDOWN) "RESTORE NETWORK" else "LOCK DOWN NOW", red) {
            if (mode == GuardianVpnService.Mode.LOCKDOWN) stopVpn() else requestVpnAndStart(GuardianVpnService.ACTION_LOCKDOWN)
        }.top(dp(10)))

        root.addView(section("PRIVACY AT A GLANCE").top(dp(25)))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        metrics.addView(metric(compact(totalBlocks), "BLOCKED"), weight())
        metrics.addView(metric(compact(totalDecisions), "OBSERVED"), weight(dp(8)))
        metrics.addView(metric(profiles.size.toString(), "PROFILES"), weight(dp(8)))
        root.addView(metrics.top(dp(9)))
        root.addView(actionCard("Privacy Intelligence", if (profiles.isEmpty()) "Shield one app at a time to build an evidence-based Privacy Profile." else "${profiles.size} app profile(s) · ${NumberFormat.getIntegerInstance().format(totalBlocks)} classified tracker request(s) blocked.", "VIEW PRIVACY PROFILES") { startActivity(Intent(this, PrivacyProfilesActivity::class.java)) }.top(dp(11)))

        root.addView(section("RECENT PROTECTION").top(dp(25)))
        root.addView(card(when {
            snapshot.sessionMode == GuardianVpnService.Mode.TRACKER_SHIELD || mode == GuardianVpnService.Mode.TRACKER_SHIELD -> "Tracker Shield\n${snapshot.dnsQueries} DNS queries · ${snapshot.trackerQueriesBlocked} blocked · ${snapshot.dnsFailures} upstream failures"
            snapshot.sessionMode == GuardianVpnService.Mode.FIREWALL || mode == GuardianVpnService.Mode.FIREWALL -> "Smart Firewall\n${snapshot.packets} packets · ${formatBytes(snapshot.bytes)} dropped"
            snapshot.sessionMode == GuardianVpnService.Mode.LOCKDOWN || mode == GuardianVpnService.Mode.LOCKDOWN -> "Lock Down\n${snapshot.packets} packets · ${formatBytes(snapshot.bytes)} dropped"
            else -> "No Guardian network session recorded yet."
        }).top(dp(9)))
        root.addView(MaterialButton(this).apply { text = "ADVANCED NETWORK CONTROLS"; setOnClickListener { openControls() } }.top(dp(12)))
        root.addView(text("Guardian reports only what local evidence supports. Tracker Shield does not decrypt HTTPS or claim complete visibility into encrypted DNS, cached addresses, or direct-IP traffic.", 11f, secondary, Typeface.NORMAL).top(dp(18)))
    }

    private fun requestVpnAndStart(action: String) {
        val prepare = VpnService.prepare(this)
        if (prepare != null) { pendingAction = action; @Suppress("DEPRECATION") startActivityForResult(prepare, 901) } else startVpn(action)
    }

    @Deprecated("Deprecated in Android API; retained for VpnService consent compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 901) {
            val action = pendingAction
            pendingAction = null
            if (resultCode == RESULT_OK && action != null) startVpn(action)
        }
    }

    private fun startVpn(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, GuardianVpnService::class.java).setAction(action))
        Toast.makeText(this, "Guardian protection starting", Toast.LENGTH_SHORT).show()
        val expected = when (action) {
            GuardianVpnService.ACTION_TRACKER_SHIELD -> GuardianVpnService.Mode.TRACKER_SHIELD
            GuardianVpnService.ACTION_FIREWALL -> GuardianVpnService.Mode.FIREWALL
            GuardianVpnService.ACTION_LOCKDOWN -> GuardianVpnService.Mode.LOCKDOWN
            else -> GuardianVpnService.Mode.OFF
        }
        refreshUntilMode(expected)
    }

    private fun stopVpn() {
        startService(Intent(this, GuardianVpnService::class.java).setAction(GuardianVpnService.ACTION_STOP))
        Toast.makeText(this, "Guardian protection stopping", Toast.LENGTH_SHORT).show()
        refreshUntilMode(GuardianVpnService.Mode.OFF)
    }

    private fun refreshUntilMode(expected: GuardianVpnService.Mode, attempt: Int = 0) {
        root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            render()
            if (GuardianVpnService.currentMode() != expected && attempt < 12) {
                refreshUntilMode(expected, attempt + 1)
            }
        }, if (attempt == 0) 180L else 250L)
    }

    private fun openControls() = startActivity(Intent(this, MainActivity::class.java))

    private fun hero(title: String, body: String, accent: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(surface, 20f, accent); addView(text("PROTECTION STATUS", 11f, accent, Typeface.BOLD)); addView(text(title, 23f, primary, Typeface.BOLD).top(dp(8))); addView(text(body, 13f, secondary, Typeface.NORMAL).top(dp(8))) }
    private fun controlCard(title: String, subtitle: String, active: Boolean, buttonText: String, accent: Int, onClick: () -> Unit) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(14)); background = rounded(if (active) raised else surface, 18f, if (active) accent else null); addView(text(if (active) "$title · ACTIVE" else title, 18f, if (active) accent else primary, Typeface.BOLD)); addView(text(subtitle, 12f, secondary, Typeface.NORMAL).top(dp(5))); addView(MaterialButton(this@HomeDashboardActivity).apply { text = buttonText; textSize = 12f; minHeight = dp(44); setOnClickListener { onClick() } }.top(dp(11))) }
    private fun actionCard(title: String, subtitle: String, buttonText: String, onClick: () -> Unit) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(14)); background = rounded(surface, 18f); addView(text(title, 18f, primary, Typeface.BOLD)); addView(text(subtitle, 13f, secondary, Typeface.NORMAL).top(dp(6))); addView(MaterialButton(this@HomeDashboardActivity).apply { text = buttonText; textSize = 12f; minHeight = dp(46); setOnClickListener { onClick() } }.top(dp(12))) }
    private fun metric(value: String, label: String) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(15), dp(7), dp(13)); background = rounded(raised, 16f); addView(text(value, 21f, primary, Typeface.BOLD)); addView(text(label, 9f, violet, Typeface.BOLD).top(dp(4))) }
    private fun card(value: String) = text(value, 14f, primary, Typeface.NORMAL).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(surface, 17f) }
    private fun section(value: String) = text(value, 11f, violet, Typeface.BOLD)
    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setTypeface(typeface, style); setLineSpacing(0f, 1.08f) }
    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun weight(left: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = left }
    private fun <T : android.view.View> T.top(px: Int): T { layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams)?.apply { topMargin = px } ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px }; return this }
    private fun compact(value: Long) = when { value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0); value >= 1_000 -> String.format("%.1fK", value / 1_000.0); else -> value.toString() }
    private fun formatBytes(bytes: Long) = when { bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0)); bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0); else -> "$bytes B" }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
