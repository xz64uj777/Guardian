package com.guardianlayer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.guardianlayer.app.data.GuardianEventStore
import com.guardianlayer.app.data.GuardianStateStore
import com.guardianlayer.app.data.TrackerActivityStore
import com.guardianlayer.app.firewall.FirewallApp
import com.guardianlayer.app.firewall.FirewallRuleStore
import com.guardianlayer.app.firewall.GuardianVpnService
import com.guardianlayer.app.firewall.LauncherAppCatalog
import com.guardianlayer.app.firewall.TrackerShieldRuleStore
import com.guardianlayer.app.privacy.InstalledAppRiskAnalyzer
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var privacyView: TextView
    private lateinit var lockdownButton: MaterialButton
    private lateinit var firewallButton: MaterialButton
    private lateinit var trackerShieldButton: MaterialButton
    private lateinit var firewallStatusView: TextView
    private lateinit var trackerShieldStatusView: TextView
    private lateinit var firewallActivityView: TextView
    private lateinit var firewallApps: LinearLayout
    private lateinit var timeline: LinearLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingVpnAction = GuardianVpnService.ACTION_LOCKDOWN

    private val expandedAppPackages = mutableSetOf<String>()
    private val appTrafficViews = mutableMapOf<String, TextView>()
    private val appTrafficHints = mutableMapOf<String, TextView>()
    private val appBlockButtons = mutableMapOf<String, MaterialButton>()
    private val appShieldButtons = mutableMapOf<String, MaterialButton>()
    private val appCatalogByPackage = mutableMapOf<String, FirewallApp>()

    private val trafficTicker = object : Runnable {
        override fun run() {
            if (::firewallActivityView.isInitialized) refreshTrafficActivity()
            refreshExpandedAppTraffic()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startGuardianVpn(pendingVpnAction)
        } else {
            GuardianEventStore.append(
                this,
                "INFO",
                "VPN permission not granted",
                "Guardian did not start the requested network protection mode."
            )
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (GuardianStateStore.isLockdownActive(this) && !GuardianVpnService.isRunning()) {
            GuardianStateStore.setLockdownActive(this, false)
            GuardianEventStore.append(
                this,
                "INFO",
                "Recovered stale Lock Down state",
                "Guardian cleared a saved Lock Down flag because no Guardian VPN service was running."
            )
        }

        setContentView(buildUi())
        loadFirewallApps()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        uiHandler.removeCallbacks(trafficTicker)
        uiHandler.post(trafficTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(trafficTicker)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(17, 19, 24))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(text("GUARDIAN", 30f, Color.WHITE, Typeface.BOLD))
        root.addView(
            text(
                "Understand. Control. Protect.",
                15f,
                Color.rgb(168, 173, 183),
                Typeface.NORMAL
            ).withTop(dp(4))
        )

        statusView = text("", 18f, Color.WHITE, Typeface.BOLD).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(27, 30, 37), 18f)
        }
        root.addView(statusView.withTop(dp(24)))

        lockdownButton = MaterialButton(this).apply {
            textSize = 16f
            minHeight = dp(56)
            setOnClickListener { toggleLockdown() }
        }
        root.addView(lockdownButton.withTop(dp(14)))

        val emergencyButton = MaterialButton(this).apply {
            text = "EMERGENCY RESTORE NETWORK"
            textSize = 14f
            minHeight = dp(52)
            setOnClickListener { emergencyRestoreNetwork() }
        }
        root.addView(emergencyButton.withTop(dp(10)))

        root.addView(sectionLabel("SMART FIREWALL").withTop(dp(28)))
        firewallStatusView = cardText("Loading apps…")
        root.addView(firewallStatusView.withTop(dp(8)))

        firewallButton = MaterialButton(this).apply {
            text = "START FIREWALL"
            textSize = 15f
            minHeight = dp(52)
            setOnClickListener { toggleFirewall() }
        }
        root.addView(firewallButton.withTop(dp(10)))

        root.addView(sectionLabel("TRACKER SHIELD · DNS FILTERING").withTop(dp(22)))
        trackerShieldStatusView = cardText(
            "Mark one or more apps SHIELDED below. Tracker Shield keeps normal app traffic online and filters ordinary DNS requests that match Guardian's local tracker intelligence."
        )
        root.addView(trackerShieldStatusView.withTop(dp(8)))

        trackerShieldButton = MaterialButton(this).apply {
            text = "START TRACKER SHIELD"
            textSize = 15f
            minHeight = dp(52)
            setOnClickListener { toggleTrackerShield() }
        }
        root.addView(trackerShieldButton.withTop(dp(10)))

        root.addView(sectionLabel("NETWORK ACTIVITY").withTop(dp(22)))
        firewallActivityView = cardText("No Guardian network session recorded yet.")
        root.addView(firewallActivityView.withTop(dp(8)))

        root.addView(sectionLabel("APP RULES · TAP AN APP FOR TRAFFIC").withTop(dp(22)))
        firewallApps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(firewallApps.withTop(dp(8)))

        val privacyButton = MaterialButton(this).apply {
            text = "RUN PRIVACY SNAPSHOT"
            textSize = 15f
            minHeight = dp(52)
            setOnClickListener { runPrivacySnapshot() }
        }
        root.addView(privacyButton.withTop(dp(18)))

        root.addView(sectionLabel("PRIVACY SNAPSHOT").withTop(dp(28)))
        privacyView = cardText(
            "No snapshot yet. Guardian will review visible launcher apps and explain sensitive permissions that are currently granted."
        )
        root.addView(privacyView.withTop(dp(8)))

        root.addView(sectionLabel("GUARDIAN TIMELINE").withTop(dp(28)))
        timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(timeline.withTop(dp(8)))
        return scroll
    }

    private fun toggleLockdown() {
        if (GuardianVpnService.currentMode() == GuardianVpnService.Mode.LOCKDOWN) {
            stopGuardianVpn()
            return
        }
        requestVpnStart(GuardianVpnService.ACTION_LOCKDOWN)
    }

    private fun toggleFirewall() {
        if (GuardianVpnService.currentMode() == GuardianVpnService.Mode.FIREWALL) {
            stopGuardianVpn()
            return
        }
        if (FirewallRuleStore.blockedCount(this) == 0) {
            firewallStatusView.text = "Choose at least one app and tap BLOCK before starting the firewall."
            return
        }
        requestVpnStart(GuardianVpnService.ACTION_FIREWALL)
    }

    private fun toggleTrackerShield() {
        if (GuardianVpnService.currentMode() == GuardianVpnService.Mode.TRACKER_SHIELD) {
            stopGuardianVpn()
            return
        }
        if (TrackerShieldRuleStore.protectedCount(this) == 0) {
            trackerShieldStatusView.text =
                "Choose at least one app and tap SHIELD before starting Tracker Shield."
            return
        }
        requestVpnStart(GuardianVpnService.ACTION_TRACKER_SHIELD)
    }

    private fun requestVpnStart(action: String) {
        pendingVpnAction = action
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) vpnPermissionLauncher.launch(permissionIntent)
        else startGuardianVpn(action)
    }

    private fun startGuardianVpn(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, GuardianVpnService::class.java).setAction(action)
        )
        uiHandler.postDelayed({ refresh() }, 450)
    }

    private fun stopGuardianVpn() {
        lockdownButton.isEnabled = false
        firewallButton.isEnabled = false
        trackerShieldButton.isEnabled = false
        GuardianStateStore.setLockdownActive(this, false)

        runCatching {
            startService(
                Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_STOP)
            )
        }

        uiHandler.postDelayed({
            runCatching { stopService(Intent(this, GuardianVpnService::class.java)) }
            lockdownButton.isEnabled = true
            firewallButton.isEnabled = true
            trackerShieldButton.isEnabled = true
            refresh()
        }, 500)
    }

    private fun emergencyRestoreNetwork() {
        GuardianStateStore.setLockdownActive(this, false)
        runCatching {
            startService(
                Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_STOP)
            )
        }
        uiHandler.postDelayed({
            runCatching { stopService(Intent(this, GuardianVpnService::class.java)) }
            GuardianEventStore.append(
                this,
                "INFO",
                "Emergency network restore requested",
                "Guardian stopped its VPN service. Android VPN settings were opened so system-level VPN restrictions can also be disabled if needed."
            )
            refresh()
            runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        }, 350)
    }

    private fun loadFirewallApps() {
        firewallApps.removeAllViews()
        appTrafficViews.clear()
        appTrafficHints.clear()
        appBlockButtons.clear()
        appShieldButtons.clear()
        appCatalogByPackage.clear()

        firewallApps.addView(
            text("Loading visible apps…", 14f, Color.rgb(168, 173, 183), Typeface.NORMAL)
        )

        Thread {
            val apps = LauncherAppCatalog(this).load()
            runOnUiThread {
                firewallApps.removeAllViews()
                if (apps.isEmpty()) {
                    firewallApps.addView(
                        text(
                            "No launcher apps were visible to Guardian.",
                            14f,
                            Color.rgb(168, 173, 183),
                            Typeface.NORMAL
                        )
                    )
                } else {
                    apps.forEach { app ->
                        appCatalogByPackage[app.packageName] = app
                        firewallApps.addView(buildFirewallRow(app).withTop(dp(6)))
                    }
                }
                refreshRuleButtons()
                refreshSummaries()
                refreshExpandedAppTraffic()
            }
        }.start()
    }

    private fun buildFirewallRow(app: FirewallApp): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(Color.rgb(27, 30, 37), 14f)
        }

        val trafficHint = text(
            if (expandedAppPackages.contains(app.packageName)) "TRAFFIC ▴" else "TRAFFIC ▾",
            11f,
            Color.rgb(151, 143, 255),
            Typeface.BOLD
        ).withTop(dp(5))

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            addView(text(app.label, 15f, Color.WHITE, Typeface.BOLD))
            addView(
                text(
                    app.packageName,
                    11f,
                    Color.rgb(168, 173, 183),
                    Typeface.NORMAL
                ).withTop(dp(2))
            )
            addView(trafficHint)
        }
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val blockButton = smallRuleButton().apply {
            setOnClickListener { toggleBlockRule(app) }
        }
        val shieldButton = smallRuleButton().apply {
            setOnClickListener { toggleShieldRule(app) }
        }
        controls.addView(blockButton)
        controls.addView(shieldButton.withTop(dp(4)))
        row.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val trafficView = text("", 13f, Color.rgb(220, 222, 228), Typeface.NORMAL).apply {
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = rounded(Color.rgb(22, 24, 30), 12f)
            visibility = if (expandedAppPackages.contains(app.packageName)) View.VISIBLE else View.GONE
        }

        labels.setOnClickListener { toggleAppTraffic(app.packageName) }
        appTrafficViews[app.packageName] = trafficView
        appTrafficHints[app.packageName] = trafficHint
        appBlockButtons[app.packageName] = blockButton
        appShieldButtons[app.packageName] = shieldButton

        container.addView(row)
        container.addView(trafficView.withTop(dp(4)))
        if (trafficView.visibility == View.VISIBLE) refreshAppTraffic(app, trafficView)
        return container
    }

    private fun smallRuleButton() = MaterialButton(this).apply {
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(36)
        textSize = 11f
    }

    private fun toggleBlockRule(app: FirewallApp) {
        val nowBlocked = !FirewallRuleStore.isBlocked(this, app.packageName)
        FirewallRuleStore.setBlocked(this, app.packageName, nowBlocked)
        if (nowBlocked) TrackerShieldRuleStore.setProtected(this, app.packageName, false)

        GuardianEventStore.append(
            this,
            "INFO",
            if (nowBlocked) "App blocked" else "App allowed",
            "${app.label} (${app.packageName}) ${if (nowBlocked) "will be routed into Guardian's blocking VPN" else "will no longer be blocked by Smart Firewall"}."
        )

        refreshRuleButtons()
        refreshSummaries()
        refreshTimeline()
        refreshExpandedAppTraffic()
        applyLiveRuleChanges()
    }

    private fun toggleShieldRule(app: FirewallApp) {
        val nowShielded = !TrackerShieldRuleStore.isProtected(this, app.packageName)
        TrackerShieldRuleStore.setProtected(this, app.packageName, nowShielded)
        if (nowShielded) FirewallRuleStore.setBlocked(this, app.packageName, false)

        GuardianEventStore.append(
            this,
            "INFO",
            if (nowShielded) "Tracker Shield enabled for app" else "Tracker Shield disabled for app",
            "${app.label} (${app.packageName}) ${if (nowShielded) "will use Guardian's DNS tracker filter while normal app traffic remains online" else "will use Android's normal DNS path when Tracker Shield is not applied"}."
        )

        refreshRuleButtons()
        refreshSummaries()
        refreshTimeline()
        refreshExpandedAppTraffic()
        applyLiveRuleChanges()
    }

    private fun applyLiveRuleChanges() {
        when (GuardianVpnService.currentMode()) {
            GuardianVpnService.Mode.FIREWALL -> startService(
                Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_FIREWALL)
            )
            GuardianVpnService.Mode.TRACKER_SHIELD -> startService(
                Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_TRACKER_SHIELD)
            )
            else -> Unit
        }
        uiHandler.postDelayed({ refresh() }, 350)
    }

    private fun refreshRuleButtons() {
        appCatalogByPackage.keys.forEach { packageName ->
            val blocked = FirewallRuleStore.isBlocked(this, packageName)
            val shielded = TrackerShieldRuleStore.isProtected(this, packageName)
            appBlockButtons[packageName]?.text = if (blocked) "ALLOW" else "BLOCK"
            appShieldButtons[packageName]?.text = if (shielded) "UNSHIELD" else "SHIELD"
        }
    }

    private fun toggleAppTraffic(packageName: String) {
        val view = appTrafficViews[packageName] ?: return
        val hint = appTrafficHints[packageName]
        val app = appCatalogByPackage[packageName] ?: return

        if (expandedAppPackages.contains(packageName)) {
            expandedAppPackages.remove(packageName)
            view.visibility = View.GONE
            hint?.text = "TRAFFIC ▾"
        } else {
            expandedAppPackages.add(packageName)
            view.visibility = View.VISIBLE
            hint?.text = "TRAFFIC ▴"
            refreshAppTraffic(app, view)
        }
    }

    private fun refreshExpandedAppTraffic() {
        if (expandedAppPackages.isEmpty()) return
        expandedAppPackages.toList().forEach { packageName ->
            val view = appTrafficViews[packageName] ?: return@forEach
            val app = appCatalogByPackage[packageName] ?: return@forEach
            if (view.visibility == View.VISIBLE) refreshAppTraffic(app, view)
        }
    }

    private fun refreshAppTraffic(app: FirewallApp, view: TextView) {
        val snapshot = GuardianVpnService.trafficSnapshot()
        val mode = GuardianVpnService.currentMode()
        val blockedPackages = FirewallRuleStore.blockedPackages(this)
        val shieldedPackages = TrackerShieldRuleStore.protectedPackages(this)
        val isBlocked = blockedPackages.contains(app.packageName)
        val isShielded = shieldedPackages.contains(app.packageName)
        val privacyProfile = TrackerActivityStore.profile(this, app.packageName)

        val appPrefix = "${app.label} → "
        val blockedEndpoints = snapshot.recent.filter { it.protocol.startsWith(appPrefix) }
        val appDns = snapshot.recentDns.filter { it.sourcePackage == app.packageName }
        val exactEvents = GuardianEventStore.recent(this, 100)
            .filter { event -> event.detail.contains("(${app.packageName})") }
            .take(4)

        if (mode == GuardianVpnService.Mode.LOCKDOWN) {
            view.text = buildString {
                append("LOCK DOWN ACTIVE · DEVICE-WIDE TRAFFIC\n\n")
                append("Guardian cannot safely separate this app's packets while Lock Down is routing the whole device through one blocking tunnel.")
                appendAppSignals(exactEvents)
                appendPrivacyHistory(privacyProfile)
            }
            return
        }

        if (mode == GuardianVpnService.Mode.FIREWALL && isBlocked) {
            if (blockedPackages.size > 1) {
                view.text = buildString {
                    append("BLOCKED · SHARED ATTRIBUTION\n\n")
                    append("${blockedPackages.size} apps are sharing Guardian's blocking VPN. Guardian will not guess which individual packets belong to ${app.label}.\n\n")
                    append("For exact traffic here, temporarily leave only ${app.label} blocked.")
                    appendAppSignals(exactEvents)
                    appendPrivacyHistory(privacyProfile)
                }
            } else {
                val endpoints = if (blockedEndpoints.isEmpty()) {
                    "No destination metadata parsed yet."
                } else {
                    blockedEndpoints.take(5).joinToString("\n") { drop ->
                        "• ${drop.protocol.removePrefix(appPrefix)} ${formatEndpoint(drop.destination, drop.port)}"
                    }
                }
                view.text = buildString {
                    append("BLOCKED · EXACT LIVE TRAFFIC\n\n")
                    append("${snapshot.packets} packets · ${formatBytes(snapshot.bytes)} dropped")
                    append("\nTCP ${snapshot.tcpPackets} · UDP ${snapshot.udpPackets} · Other ${snapshot.otherPackets}")
                    append("\n\nRecent destinations\n")
                    append(endpoints)
                    appendDnsActivity(appDns)
                    appendAppSignals(exactEvents)
                    appendPrivacyHistory(privacyProfile)
                    append("\n\nExact because this is the only app routed into Smart Firewall.")
                }
            }
            return
        }

        if (mode == GuardianVpnService.Mode.TRACKER_SHIELD && isShielded) {
            if (shieldedPackages.size > 1) {
                view.text = buildString {
                    append("SHIELDED · SHARED DNS ATTRIBUTION\n\n")
                    append("${shieldedPackages.size} apps are sharing Tracker Shield. Guardian is blocking matched tracker DNS requests, but will not guess which app made each query.\n\n")
                    append("Leave only ${app.label} shielded for exact per-app DNS activity.")
                    appendPrivacyHistory(privacyProfile)
                }
            } else {
                val blocked = appDns.count { it.blockedByTrackerShield }
                val allowed = appDns.count { !it.blockedByTrackerShield }
                view.text = buildString {
                    append("SHIELDED · EXACT LIVE DNS\n\n")
                    append("${snapshot.dnsQueries} DNS queries · ${snapshot.trackerQueriesBlocked} tracker requests blocked")
                    append("\n${snapshot.dnsQueriesForwarded} forwarded · ${snapshot.dnsFailures} upstream failures")
                    append("\n${snapshot.dnsUnsupportedPackets} unsupported packets · ${snapshot.uniqueTrackerDomainsBlocked} unique tracker domains blocked")
                    append("\nVisible here: $blocked blocked · $allowed allowed recent entries")
                    appendDnsActivity(appDns)
                    appendPrivacyHistory(privacyProfile)
                    append("\n\nNormal app traffic bypasses Guardian and stays online. This first shield only filters ordinary IPv4/UDP DNS; cached IPs and encrypted DNS can bypass it.")
                }
            }
            return
        }

        val lastExactBlocked = mode == GuardianVpnService.Mode.OFF && blockedEndpoints.isNotEmpty()
        val lastExactShield = mode == GuardianVpnService.Mode.OFF && appDns.isNotEmpty()
        if (lastExactBlocked || lastExactShield) {
            view.text = buildString {
                append("LAST EXACT GUARDIAN SESSION\n")
                if (lastExactBlocked) {
                    append("\nBlocked traffic\n")
                    blockedEndpoints.take(5).forEach { drop ->
                        append("• ${drop.protocol.removePrefix(appPrefix)} ${formatEndpoint(drop.destination, drop.port)}\n")
                    }
                }
                if (lastExactShield) appendDnsActivity(appDns)
                if (privacyProfile != null) appendPrivacyHistory(privacyProfile)
                else appendAppSignals(exactEvents)
            }
            return
        }

        view.text = buildString {
            when {
                isBlocked -> {
                    append("BLOCKED · WAITING FOR FIREWALL\n\n")
                    append("Start Smart Firewall, then use ${app.label}. If it is the only blocked app, Guardian will show exact dropped traffic here.")
                }
                isShielded -> {
                    append("SHIELDED · WAITING FOR TRACKER SHIELD\n\n")
                    append("Start Tracker Shield, then use ${app.label}. The app should remain online while matched plaintext-DNS tracker requests are blocked.")
                }
                else -> {
                    append("ALLOWED · NOT CAPTURED\n\n")
                    append("This app currently bypasses Guardian's VPN modes. Tap BLOCK for a full network block or SHIELD for DNS-level tracker filtering.")
                }
            }
            if (privacyProfile != null) appendPrivacyHistory(privacyProfile)
            else appendAppSignals(exactEvents)
        }
    }

    private fun StringBuilder.appendDnsActivity(entries: List<GuardianVpnService.DnsActivity>) {
        if (entries.isEmpty()) return
        append("\n\nRecent DNS activity\n")
        entries.take(6).forEach { entry ->
            if (entry.blockedByTrackerShield) {
                append("• BLOCKED")
                if (!entry.category.isNullOrBlank()) append(" ${entry.category}")
                append(" · ${entry.domain}")
                if (!entry.provider.isNullOrBlank()) append(" · ${entry.provider}")
                append("\n")
            } else {
                append("• ALLOWED · ${entry.domain}\n")
            }
        }
    }

    private fun StringBuilder.appendPrivacyHistory(profile: TrackerActivityStore.AppProfile?) {
        if (profile == null) return

        val lastSeen = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT
        ).format(Date(profile.lastSeenAt))

        append("\n\nPRIVACY HISTORY · EXACT ATTRIBUTION\n")
        append("${profile.retainedDecisions} retained DNS decisions · ${profile.blockedDecisions} tracker blocks · ${profile.allowedDecisions} allowed\n")
        append("${profile.uniqueTrackerDomains} unique tracker domains · Last seen $lastSeen")

        if (profile.topTrackers.isNotEmpty()) {
            append("\n\nTop blocked tracker domains\n")
            profile.topTrackers.take(5).forEach { tracker ->
                append("• ${tracker.domain} · ${tracker.provider} · ${tracker.category} · ×${tracker.count}\n")
            }
        }

        if (profile.categories.isNotEmpty()) {
            append("\nCategories: ")
            append(
                profile.categories.take(4).joinToString(" · ") {
                    "${it.name} ${it.count}"
                }
            )
        }

        append("\n\nStored locally on this device from exact single-app Tracker Shield sessions. Guardian keeps a bounded history and does not add ambiguous multi-app DNS to this profile.")
    }

    private fun StringBuilder.appendAppSignals(
        events: List<com.guardianlayer.app.model.GuardianEvent>
    ) {
        if (events.isEmpty()) return
        append("\n\nRecent privacy signals\n")
        events.forEach { event ->
            val requestedDomain = Regex("requested ([^\\s.]+(?:\\.[^\\s.]+)+)")
                .find(event.detail)
                ?.groupValues
                ?.getOrNull(1)
            append("• ${event.title}")
            if (!requestedDomain.isNullOrBlank()) append(": $requestedDomain")
            append("\n")
        }
    }

    private fun refreshSummaries() {
        val blocked = FirewallRuleStore.blockedCount(this)
        val shielded = TrackerShieldRuleStore.protectedCount(this)
        val mode = GuardianVpnService.currentMode()

        firewallStatusView.text = when {
            mode == GuardianVpnService.Mode.FIREWALL ->
                "FIREWALL ACTIVE · $blocked app(s) blocked\n\nOnly BLOCK rules are routed into Guardian's blackhole VPN. Other apps stay online."
            blocked > 0 ->
                "$blocked app(s) marked BLOCKED\n\nStart Firewall to enforce full network blocking."
            else ->
                "No apps blocked. BLOCK is the hard rule: all network traffic from selected apps is stopped."
        }

        trackerShieldStatusView.text = when {
            mode == GuardianVpnService.Mode.TRACKER_SHIELD ->
                "TRACKER SHIELD ACTIVE · $shielded app(s) shielded\n\nNormal traffic stays online. Guardian routes ordinary DNS through a local filter and blocks domains matched by its offline tracker intelligence."
            shielded > 0 ->
                "$shielded app(s) marked SHIELDED\n\nStart Tracker Shield to filter visible tracker DNS while leaving the apps online."
            else ->
                "No apps shielded. Tap SHIELD beside an app to enable DNS-level tracker filtering for it."
        }
    }

    private fun refreshTrafficActivity() {
        val snapshot = GuardianVpnService.trafficSnapshot()
        val mode = GuardianVpnService.currentMode()
        val displayMode = if (mode == GuardianVpnService.Mode.OFF) snapshot.sessionMode else mode

        if (displayMode == GuardianVpnService.Mode.TRACKER_SHIELD) {
            val heading = if (mode == GuardianVpnService.Mode.TRACKER_SHIELD) {
                "LIVE TRACKER SHIELD"
            } else {
                "LAST TRACKER SHIELD SESSION"
            }
            val recent = if (snapshot.recentDns.isEmpty()) {
                "No ordinary DNS activity recorded yet."
            } else {
                snapshot.recentDns.take(7).joinToString("\n") { entry ->
                    val prefix = if (entry.blockedByTrackerShield) "BLOCKED" else "ALLOWED"
                    val category = entry.category?.let { " · $it" } ?: ""
                    "• $prefix$category · ${entry.domain} · ${entry.sourceLabel}"
                }
            }
            val topBlocked = if (snapshot.topTrackerBlocks.isEmpty()) {
                "No classified tracker domains blocked in this session."
            } else {
                snapshot.topTrackerBlocks.take(5).joinToString("\n") { tracker ->
                    "• ${tracker.domain} · ${tracker.provider} · ×${tracker.count}"
                }
            }
            firewallActivityView.text = buildString {
                append(heading)
                append("\n\n")
                append("${snapshot.dnsQueries} DNS queries · ${snapshot.trackerQueriesBlocked} tracker requests blocked")
                append("\n${snapshot.dnsQueriesForwarded} forwarded · ${snapshot.dnsFailures} upstream failures")
                append("\n${snapshot.dnsUnsupportedPackets} unsupported packets · ${snapshot.uniqueTrackerDomainsBlocked} unique tracker domains blocked")
                append("\n\nTop blocked tracker domains\n")
                append(topBlocked)
                append("\n\nRecent DNS decisions\n")
                append(recent)
                append("\n\nTracker Shield is intentionally DNS-only in this alpha. It does not decrypt HTTPS or encrypted DNS and does not claim to block cached/direct-IP tracker traffic.")
            }
            return
        }

        if (snapshot.packets == 0L) {
            firewallActivityView.text = when (mode) {
                GuardianVpnService.Mode.FIREWALL ->
                    "LIVE SMART FIREWALL\n\nNo blocked packets have reached Guardian yet. Open a blocked app to generate activity."
                GuardianVpnService.Mode.LOCKDOWN ->
                    "LIVE LOCK DOWN\n\nNo dropped packets recorded yet."
                else -> "No blocked traffic recorded in the last Guardian firewall session."
            }
            return
        }

        val heading = when (displayMode) {
            GuardianVpnService.Mode.FIREWALL -> if (mode == GuardianVpnService.Mode.FIREWALL) "LIVE SMART FIREWALL" else "LAST FIREWALL SESSION"
            GuardianVpnService.Mode.LOCKDOWN -> if (mode == GuardianVpnService.Mode.LOCKDOWN) "LIVE LOCK DOWN" else "LAST LOCK DOWN SESSION"
            else -> "LAST NETWORK SESSION"
        }

        val lastActivity = if (snapshot.lastActivityAt > 0) {
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(snapshot.lastActivityAt))
        } else "—"

        val recentText = if (snapshot.recent.isEmpty()) {
            "No destination metadata parsed yet."
        } else {
            snapshot.recent.take(6).joinToString("\n") { drop ->
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(drop.timestamp))
                "• $time · ${drop.protocol} ${formatEndpoint(drop.destination, drop.port)}"
            }
        }

        firewallActivityView.text = buildString {
            append(heading)
            append("\n\n")
            append("${snapshot.packets} packets · ${formatBytes(snapshot.bytes)} dropped")
            append("\nTCP ${snapshot.tcpPackets} · UDP ${snapshot.udpPackets} · Other ${snapshot.otherPackets}")
            append("\nLast blocked traffic: $lastActivity")
            append("\n\nRecent blocked destinations\n")
            append(recentText)
            append("\n\nWith one selected app Guardian can label traffic directly. With multiple selected apps attribution remains shared instead of guessed.")
        }
    }

    private fun runPrivacySnapshot() {
        privacyView.text = "Reviewing launcher apps and granted sensitive permissions…"
        Thread {
            val snapshot = InstalledAppRiskAnalyzer(this).analyze()
            runOnUiThread {
                val top = if (snapshot.topApps.isEmpty()) {
                    "No launcher apps with reviewable permission data were visible to Guardian."
                } else {
                    snapshot.topApps.joinToString("\n\n") { app ->
                        val permissions = if (app.grantedSensitivePermissions.isEmpty()) {
                            "No scored sensitive permissions granted"
                        } else {
                            app.grantedSensitivePermissions.joinToString(", ")
                        }
                        "${app.label} · exposure ${app.score}/100\n$permissions"
                    }
                }
                privacyView.text =
                    "${snapshot.appsReviewed} apps reviewed · ${snapshot.reviewCount} worth a closer look\n\n$top"
                GuardianEventStore.append(
                    this,
                    "INFO",
                    "Privacy snapshot completed",
                    "Reviewed ${snapshot.appsReviewed} launcher apps; ${snapshot.reviewCount} crossed the review threshold. Scores reflect permission exposure, not malware verdicts."
                )
                refreshTimeline()
            }
        }.start()
    }

    private fun refresh() {
        val mode = GuardianVpnService.currentMode()
        if (mode == GuardianVpnService.Mode.OFF && GuardianStateStore.isLockdownActive(this)) {
            GuardianStateStore.setLockdownActive(this, false)
        }

        statusView.text = when (mode) {
            GuardianVpnService.Mode.LOCKDOWN ->
                "LOCK DOWN ACTIVE\n\nGuardian is blocking network traffic for the device."
            GuardianVpnService.Mode.FIREWALL ->
                "FIREWALL ACTIVE\n\n${FirewallRuleStore.blockedCount(this)} selected app(s) are fully blocked while other apps remain online."
            GuardianVpnService.Mode.TRACKER_SHIELD ->
                "TRACKER SHIELD ACTIVE\n\n${TrackerShieldRuleStore.protectedCount(this)} selected app(s) remain online while Guardian filters visible tracker DNS."
            GuardianVpnService.Mode.OFF ->
                "DEVICE ONLINE\n\nGuardian's VPN protection is not currently active."
        }
        statusView.setTextColor(
            if (mode == GuardianVpnService.Mode.LOCKDOWN) Color.rgb(255, 160, 160)
            else Color.WHITE
        )

        lockdownButton.text = if (mode == GuardianVpnService.Mode.LOCKDOWN) {
            "STOP LOCK DOWN"
        } else {
            "ACTIVATE LOCK DOWN"
        }
        firewallButton.text = if (mode == GuardianVpnService.Mode.FIREWALL) {
            "STOP FIREWALL"
        } else {
            "START FIREWALL"
        }
        trackerShieldButton.text = if (mode == GuardianVpnService.Mode.TRACKER_SHIELD) {
            "STOP TRACKER SHIELD"
        } else {
            "START TRACKER SHIELD"
        }

        val lockedDown = mode == GuardianVpnService.Mode.LOCKDOWN
        firewallButton.isEnabled = !lockedDown
        trackerShieldButton.isEnabled = !lockedDown

        refreshRuleButtons()
        refreshSummaries()
        refreshTrafficActivity()
        refreshExpandedAppTraffic()
        refreshTimeline()
    }

    private fun refreshTimeline() {
        timeline.removeAllViews()
        val events = GuardianEventStore.recent(this, 12)
        if (events.isEmpty()) {
            timeline.addView(
                text(
                    "Guardian events will appear here as the app observes or changes security state.",
                    14f,
                    Color.rgb(168, 173, 183),
                    Typeface.NORMAL
                )
            )
            return
        }

        events.forEach { event ->
            val time = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            ).format(Date(event.timestamp))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(Color.rgb(27, 30, 37), 14f)
                addView(
                    text(
                        "${event.level} · $time",
                        11f,
                        Color.rgb(168, 173, 183),
                        Typeface.BOLD
                    )
                )
                addView(text(event.title, 16f, Color.WHITE, Typeface.BOLD).withTop(dp(4)))
                addView(
                    text(
                        event.detail,
                        14f,
                        Color.rgb(220, 222, 228),
                        Typeface.NORMAL
                    ).withTop(dp(4))
                )
            }
            timeline.addView(card.withTop(dp(8)))
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(
            Locale.US,
            "%.1f MB",
            bytes / (1024.0 * 1024.0)
        )
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatEndpoint(address: String, port: Int?): String {
        if (port == null) return address
        return if (address.contains(':')) "[$address]:$port" else "$address:$port"
    }

    private fun sectionLabel(value: String) =
        text(value, 13f, Color.rgb(168, 173, 183), Typeface.BOLD)

    private fun cardText(value: String) =
        text(value, 14f, Color.WHITE, Typeface.NORMAL).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(27, 30, 37), 16f)
        }

    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        gravity = Gravity.START
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun <T : View> T.withTop(top: Int): T {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        return this
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
