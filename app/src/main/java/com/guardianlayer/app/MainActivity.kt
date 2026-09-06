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
import com.guardianlayer.app.firewall.FirewallApp
import com.guardianlayer.app.firewall.FirewallRuleStore
import com.guardianlayer.app.firewall.GuardianVpnService
import com.guardianlayer.app.firewall.LauncherAppCatalog
import com.guardianlayer.app.privacy.InstalledAppRiskAnalyzer
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var privacyView: TextView
    private lateinit var lockdownButton: MaterialButton
    private lateinit var firewallButton: MaterialButton
    private lateinit var firewallStatusView: TextView
    private lateinit var firewallActivityView: TextView
    private lateinit var firewallApps: LinearLayout
    private lateinit var timeline: LinearLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingVpnAction = GuardianVpnService.ACTION_LOCKDOWN

    private val trafficTicker = object : Runnable {
        override fun run() {
            if (::firewallActivityView.isInitialized) refreshTrafficActivity()
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

        root.addView(
            text(
                "SMART FIREWALL",
                13f,
                Color.rgb(168, 173, 183),
                Typeface.BOLD
            ).withTop(dp(28))
        )

        firewallStatusView = text("Loading apps…", 15f, Color.WHITE, Typeface.NORMAL).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(27, 30, 37), 16f)
        }
        root.addView(firewallStatusView.withTop(dp(8)))

        firewallButton = MaterialButton(this).apply {
            text = "START FIREWALL"
            textSize = 15f
            minHeight = dp(52)
            setOnClickListener { toggleFirewall() }
        }
        root.addView(firewallButton.withTop(dp(10)))

        root.addView(
            text(
                "FIREWALL ACTIVITY",
                13f,
                Color.rgb(168, 173, 183),
                Typeface.BOLD
            ).withTop(dp(22))
        )
        firewallActivityView = text("No blocked traffic recorded yet.", 14f, Color.WHITE, Typeface.NORMAL).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(27, 30, 37), 16f)
        }
        root.addView(firewallActivityView.withTop(dp(8)))

        root.addView(
            text(
                "APP RULES",
                13f,
                Color.rgb(168, 173, 183),
                Typeface.BOLD
            ).withTop(dp(22))
        )
        firewallApps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(firewallApps.withTop(dp(8)))

        val privacyButton = MaterialButton(this).apply {
            text = "RUN PRIVACY SNAPSHOT"
            textSize = 15f
            minHeight = dp(52)
            setOnClickListener { runPrivacySnapshot() }
        }
        root.addView(privacyButton.withTop(dp(18)))

        root.addView(
            text(
                "PRIVACY SNAPSHOT",
                13f,
                Color.rgb(168, 173, 183),
                Typeface.BOLD
            ).withTop(dp(28))
        )
        privacyView = text(
            "No snapshot yet. Guardian will review visible launcher apps and explain sensitive permissions that are currently granted.",
            15f,
            Color.WHITE,
            Typeface.NORMAL
        ).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(27, 30, 37), 16f)
        }
        root.addView(privacyView.withTop(dp(8)))

        root.addView(
            text(
                "GUARDIAN TIMELINE",
                13f,
                Color.rgb(168, 173, 183),
                Typeface.BOLD
            ).withTop(dp(28))
        )
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

        val blockedCount = FirewallRuleStore.blockedCount(this)
        if (blockedCount == 0) {
            firewallStatusView.text = "Choose at least one app and tap BLOCK before starting the firewall."
            return
        }

        requestVpnStart(GuardianVpnService.ACTION_FIREWALL)
    }

    private fun requestVpnStart(action: String) {
        pendingVpnAction = action
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) vpnPermissionLauncher.launch(permissionIntent)
        else startGuardianVpn(action)
    }

    private fun startGuardianVpn(action: String) {
        val intent = Intent(this, GuardianVpnService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
        uiHandler.postDelayed({ refresh() }, 450)
    }

    private fun stopGuardianVpn() {
        lockdownButton.isEnabled = false
        firewallButton.isEnabled = false
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
        firewallApps.addView(
            text(
                "Loading visible apps…",
                14f,
                Color.rgb(168, 173, 183),
                Typeface.NORMAL
            )
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
                        firewallApps.addView(buildFirewallRow(app).withTop(dp(6)))
                    }
                }
                refreshFirewallSummary()
            }
        }.start()
    }

    private fun buildFirewallRow(app: FirewallApp): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(Color.rgb(27, 30, 37), 14f)
        }

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(app.label, 15f, Color.WHITE, Typeface.BOLD))
            addView(
                text(
                    app.packageName,
                    11f,
                    Color.rgb(168, 173, 183),
                    Typeface.NORMAL
                ).withTop(dp(2))
            )
        }
        row.addView(
            labels,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val ruleButton = MaterialButton(this).apply {
            minWidth = 0
            minimumWidth = 0
            textSize = 12f
            updateRuleButton(this, app.packageName)
            setOnClickListener {
                val nowBlocked = !FirewallRuleStore.isBlocked(
                    this@MainActivity,
                    app.packageName
                )
                FirewallRuleStore.setBlocked(
                    this@MainActivity,
                    app.packageName,
                    nowBlocked
                )
                updateRuleButton(this, app.packageName)
                GuardianEventStore.append(
                    this@MainActivity,
                    "INFO",
                    if (nowBlocked) "App blocked" else "App allowed",
                    "${app.label} (${app.packageName}) ${if (nowBlocked) "will be routed into Guardian's blocking VPN" else "will use Android's normal network route"}."
                )
                refreshFirewallSummary()
                refreshTimeline()

                if (GuardianVpnService.currentMode() == GuardianVpnService.Mode.FIREWALL) {
                    startService(
                        Intent(this@MainActivity, GuardianVpnService::class.java)
                            .setAction(GuardianVpnService.ACTION_FIREWALL)
                    )
                    uiHandler.postDelayed({ refresh() }, 350)
                }
            }
        }
        row.addView(
            ruleButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }

    private fun updateRuleButton(button: MaterialButton, packageName: String) {
        val blocked = FirewallRuleStore.isBlocked(this, packageName)
        button.text = if (blocked) "ALLOW" else "BLOCK"
    }

    private fun refreshFirewallSummary() {
        val count = FirewallRuleStore.blockedCount(this)
        val mode = GuardianVpnService.currentMode()
        firewallStatusView.text = when {
            mode == GuardianVpnService.Mode.FIREWALL ->
                "FIREWALL ACTIVE · $count app(s) blocked\n\nOnly selected apps are routed into Guardian's blocking VPN. Other apps stay online. Rule changes apply immediately."
            count > 0 ->
                "$count app(s) marked BLOCKED\n\nStart Firewall to enforce these rules. Guardian only inspects metadata for traffic it is already blocking."
            else ->
                "No apps blocked yet. Tap BLOCK beside an app, then start the firewall."
        }
    }

    private fun refreshTrafficActivity() {
        val snapshot = GuardianVpnService.trafficSnapshot()
        val mode = GuardianVpnService.currentMode()

        if (snapshot.packets == 0L) {
            firewallActivityView.text = when (mode) {
                GuardianVpnService.Mode.FIREWALL ->
                    "LIVE SMART FIREWALL\n\nNo blocked packets have reached Guardian yet. Open a blocked app to generate activity."
                GuardianVpnService.Mode.LOCKDOWN ->
                    "LIVE LOCK DOWN\n\nNo dropped packets recorded yet."
                GuardianVpnService.Mode.OFF ->
                    "No blocked traffic recorded yet. Start Smart Firewall or Lock Down to collect local drop statistics."
            }
            return
        }

        val heading = when (mode) {
            GuardianVpnService.Mode.FIREWALL -> "LIVE SMART FIREWALL"
            GuardianVpnService.Mode.LOCKDOWN -> "LIVE LOCK DOWN"
            GuardianVpnService.Mode.OFF -> "LAST FIREWALL SESSION"
        }

        val lastActivity = if (snapshot.lastActivityAt > 0) {
            DateFormat.getTimeInstance(DateFormat.MEDIUM)
                .format(Date(snapshot.lastActivityAt))
        } else {
            "—"
        }

        val recentText = if (snapshot.recent.isEmpty()) {
            "No destination metadata parsed yet."
        } else {
            snapshot.recent.take(6).joinToString("\n") { drop ->
                val time = DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(drop.timestamp))
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
            append("\n\nIP/port metadata only. Guardian is not decrypting payloads or claiming exact per-app ownership of each packet in this view.")
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
                privacyView.text = "${snapshot.appsReviewed} apps reviewed · ${snapshot.reviewCount} worth a closer look\n\n$top"
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
                "FIREWALL ACTIVE\n\n${FirewallRuleStore.blockedCount(this)} selected app(s) are blocked while other apps remain online."
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
        firewallButton.isEnabled = mode != GuardianVpnService.Mode.LOCKDOWN
        refreshFirewallSummary()
        refreshTrafficActivity()
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
