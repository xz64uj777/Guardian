package com.guardianlayer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.guardianlayer.app.data.TrackerActivityStore
import com.guardianlayer.app.firewall.FirewallApp
import com.guardianlayer.app.firewall.FirewallRuleStore
import com.guardianlayer.app.firewall.GuardianVpnService
import com.guardianlayer.app.firewall.LauncherAppCatalog
import com.guardianlayer.app.firewall.TrackerShieldRuleStore
import java.text.NumberFormat
import java.util.concurrent.TimeUnit

class ProtectionAppsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_FIREWALL = "firewall"
        const val MODE_SHIELD = "shield"
    }

    private val page = Color.rgb(11, 12, 17)
    private val surface = Color.rgb(24, 26, 34)
    private val raised = Color.rgb(31, 33, 43)
    private val primary = Color.rgb(246, 246, 250)
    private val secondary = Color.rgb(177, 181, 193)
    private val violet = Color.rgb(166, 151, 255)
    private val green = Color.rgb(116, 213, 155)
    private val amber = Color.rgb(241, 190, 92)

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var apps: List<FirewallApp> = emptyList()
    private var query = ""
    private var selectedOnly = false
    private var loading = true
    private var preferredMode: String = MODE_FIREWALL
    private var expandedPackageName: String? = null
    private var baselineDecisions = 0L
    private var baselineBlocked = 0L
    private var baselineStartedAt = 0L
    private var frozenAt = 0L
    private var frozenDecisions = 0L
    private var frozenBlocked = 0L
    private var frozenReportText: String? = null
    private var pendingExactWatchPackage: String? = null
    private var expandedPanelHost: LinearLayout? = null
    private val liveHandler = Handler(Looper.getMainLooper())
    private val liveRefresh = object : Runnable {
        override fun run() {
            if (isFinishing || !::list.isInitialized || loading) return
            val expanded = expandedPackageName
            val shouldRefresh = expanded != null ||
                GuardianVpnService.currentMode() == GuardianVpnService.Mode.TRACKER_SHIELD
            if (!shouldRefresh) return

            if (expanded != null) {
                refreshExpandedPanel()
            } else {
                renderApps()
            }
            liveHandler.postDelayed(this, if (expanded != null) 2_000L else 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferredMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FIREWALL
        query = savedInstanceState?.getString("query").orEmpty()
        selectedOnly = savedInstanceState?.getBoolean("selectedOnly") ?: false
        expandedPackageName = savedInstanceState?.getString("expandedPackageName")
        baselineDecisions = savedInstanceState?.getLong("baselineDecisions") ?: 0L
        baselineBlocked = savedInstanceState?.getLong("baselineBlocked") ?: 0L
        baselineStartedAt = savedInstanceState?.getLong("baselineStartedAt") ?: 0L
        frozenAt = savedInstanceState?.getLong("frozenAt") ?: 0L
        frozenDecisions = savedInstanceState?.getLong("frozenDecisions") ?: 0L
        frozenBlocked = savedInstanceState?.getLong("frozenBlocked") ?: 0L
        frozenReportText = savedInstanceState?.getString("frozenReportText")
        pendingExactWatchPackage = savedInstanceState?.getString("pendingExactWatchPackage")
        title = "Guardian Protection Apps"
        setContentView(buildUi())
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::summary.isInitialized) refreshSummary()
        if (::list.isInitialized && !loading) renderApps()
        restartLiveRefresh()
    }

    override fun onPause() {
        liveHandler.removeCallbacks(liveRefresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        outState.putBoolean("selectedOnly", selectedOnly)
        outState.putString("expandedPackageName", expandedPackageName)
        outState.putLong("baselineDecisions", baselineDecisions)
        outState.putLong("baselineBlocked", baselineBlocked)
        outState.putLong("baselineStartedAt", baselineStartedAt)
        outState.putLong("frozenAt", frozenAt)
        outState.putLong("frozenDecisions", frozenDecisions)
        outState.putLong("frozenBlocked", frozenBlocked)
        outState.putString("frozenReportText", frozenReportText)
        outState.putString("pendingExactWatchPackage", pendingExactWatchPackage)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi(): ScrollView = ScrollView(this).apply {
        setBackgroundColor(page)
        isFillViewport = true
        addView(LinearLayout(this@ProtectionAppsActivity).apply {
            orientation = LinearLayout.VERTICAL
            val side = if (resources.configuration.screenWidthDp >= 600) dp(48) else dp(18)
            setPadding(side, dp(22), side, dp(34))
            addView(text("PROTECTION APPS", 28f, primary, Typeface.BOLD))
            addView(text("Choose what Guardian should block or shield, then expand an app to watch the traffic Guardian can attribute to it.", 14f, secondary, Typeface.NORMAL).top(dp(4)))

            summary = text("", 14f, primary, Typeface.BOLD).apply {
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background = rounded(surface, 17f)
            }
            addView(summary.top(dp(20)))

            addView(text("BLOCK cuts an app's network access. SHIELD keeps it online and filters visible tracker DNS. ALLOW removes either saved rule. Exact app traffic history is shown only when Guardian can attribute it confidently.", 12f, secondary, Typeface.NORMAL).top(dp(10)))

            addView(EditText(this@ProtectionAppsActivity).apply {
                hint = "Search app name or package"
                contentDescription = "Search protection apps"
                setTextColor(primary)
                setHintTextColor(secondary)
                setSingleLine(true)
                setText(query)
                doAfterTextChanged {
                    query = it?.toString().orEmpty()
                    if (::list.isInitialized) renderApps()
                }
            }.top(dp(12)))
            addView(MaterialButton(this@ProtectionAppsActivity).apply {
                text = if (selectedOnly) "SHOW ALL APPS" else "SHOW SELECTED APPS"
                setOnClickListener {
                    selectedOnly = !selectedOnly
                    text = if (selectedOnly) "SHOW ALL APPS" else "SHOW SELECTED APPS"
                    renderApps()
                }
            }.top(dp(6)))

            list = LinearLayout(this@ProtectionAppsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(list.top(dp(18)))

            addView(MaterialButton(this@ProtectionAppsActivity).apply {
                text = "DONE"
                minHeight = dp(48)
                setOnClickListener { finish() }
            }.top(dp(18)))
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun loadApps() {
        renderApps()
        val context = applicationContext
        Thread {
            val result = runCatching { LauncherAppCatalog(context).load() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                apps = result.getOrDefault(emptyList())
                if (expandedPackageName != null && baselineStartedAt == 0L) resetLiveWindow(expandedPackageName!!)
                renderApps()
                if (result.isFailure) {
                    list.removeAllViews()
                    list.addView(text("Could not load apps. Reopen this screen to retry; your saved rules are unchanged.", 14f, secondary, Typeface.NORMAL))
                }
                refreshSummary()
                restartLiveRefresh()
            }
        }.start()
    }

    private fun restartLiveRefresh() {
        liveHandler.removeCallbacks(liveRefresh)
        if (loading) return
        if (expandedPackageName != null || GuardianVpnService.currentMode() == GuardianVpnService.Mode.TRACKER_SHIELD) {
            liveHandler.postDelayed(liveRefresh, if (expandedPackageName != null) 2_000L else 5_000L)
        }
    }

    private fun isExactLive(packageName: String): Boolean {
        if (GuardianVpnService.currentMode() != GuardianVpnService.Mode.TRACKER_SHIELD) return false
        val active = GuardianVpnService.activeRulePackages()
        return active.size == 1 && packageName in active
    }

    private fun isSharedShieldSession(packageName: String): Boolean {
        if (GuardianVpnService.currentMode() != GuardianVpnService.Mode.TRACKER_SHIELD) return false
        val active = GuardianVpnService.activeRulePackages()
        return active.size > 1 && packageName in active
    }

    private fun requestExactWatch(packageName: String) {
        TrackerShieldRuleStore.replaceProtected(this, setOf(packageName))
        FirewallRuleStore.setBlocked(this, packageName, false)
        expandedPackageName = packageName
        resetLiveWindow(packageName)

        when (GuardianVpnService.currentMode()) {
            GuardianVpnService.Mode.TRACKER_SHIELD -> {
                startTrackerShield()
                Toast.makeText(this, "Exact watch switching to this app", Toast.LENGTH_SHORT).show()
            }
            GuardianVpnService.Mode.OFF -> {
                val prepare = VpnService.prepare(this)
                if (prepare != null) {
                    pendingExactWatchPackage = packageName
                    @Suppress("DEPRECATION")
                    startActivityForResult(prepare, 902)
                } else {
                    startTrackerShield()
                }
            }
            else -> {
                Toast.makeText(
                    this,
                    "Exact watch saved. Stop the current protection mode, then start Tracker Shield.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        refreshSummary()
        renderApps()
        restartLiveRefresh()
    }

    private fun startTrackerShield() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, GuardianVpnService::class.java).setAction(GuardianVpnService.ACTION_TRACKER_SHIELD)
        )
        liveHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                renderApps()
                refreshSummary()
                restartLiveRefresh()
            }
        }, 350L)
    }

    @Deprecated("Deprecated in Android API; retained for VpnService consent compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 902) {
            val packageName = pendingExactWatchPackage
            pendingExactWatchPackage = null
            if (resultCode == RESULT_OK && packageName != null) {
                TrackerShieldRuleStore.replaceProtected(this, setOf(packageName))
                startTrackerShield()
            }
        }
    }

    private fun resetLiveWindow(packageName: String) {
        val profile = TrackerActivityStore.profile(this, packageName)
        baselineDecisions = profile?.cumulativeDecisions ?: 0L
        baselineBlocked = profile?.blockedDecisions ?: 0L
        baselineStartedAt = System.currentTimeMillis()
        frozenAt = 0L
        frozenDecisions = 0L
        frozenBlocked = 0L
        frozenReportText = null
    }

    private fun freezeLiveWindow(app: FirewallApp) {
        val profile = TrackerActivityStore.profile(this, app.packageName) ?: return
        frozenAt = System.currentTimeMillis()
        frozenDecisions = profile.cumulativeDecisions
        frozenBlocked = profile.blockedDecisions
        frozenReportText = buildLiveReport(app, profile)
        refreshExpandedPanel()
        restartLiveRefresh()
    }

    private fun renderApps() {
        expandedPanelHost = null
        list.removeAllViews()
        if (loading) {
            list.addView(text("Loading apps…", 14f, secondary, Typeface.NORMAL))
            return
        }
        val visible = apps.filter { app ->
            (query.isBlank() || app.label.contains(query.trim(), true) || app.packageName.contains(query.trim(), true)) &&
                (!selectedOnly || FirewallRuleStore.isBlocked(this, app.packageName) || TrackerShieldRuleStore.isProtected(this, app.packageName))
        }
        if (visible.isEmpty()) {
            list.addView(text("No apps match this view. Try another search or show all apps.", 14f, secondary, Typeface.NORMAL))
            return
        }
        val columns = if (expandedPackageName == null && resources.configuration.screenWidthDp >= 600 && resources.configuration.fontScale <= 1.3f) 2 else 1
        visible.chunked(columns).forEach { group ->
            val band = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEachIndexed { index, app ->
                band.addView(appRow(app), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
            if (columns == 2 && group.size == 1) {
                band.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
            }
            list.addView(band.top(dp(7)))
        }
    }

    private fun appRow(app: FirewallApp): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = rounded(surface, 16f)
        }
        val name = text(app.label, 16f, primary, Typeface.BOLD)
        val pkg = text(app.packageName, 10f, secondary, Typeface.NORMAL).top(dp(2))
        val profile = TrackerActivityStore.profile(this, app.packageName)
        val statusLine = when {
            isExactLive(app.packageName) -> {
                val recent = TrackerActivityStore.recentWindow(
                    this,
                    app.packageName,
                    System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)
                )
                if (recent.decisions > 0L) {
                    "ACTIVE NOW  •  ${recent.decisions} DNS  •  ${recent.blockedDecisions} blocked"
                } else {
                    "QUIET NOW  •  exact live watch active"
                }
            }
            isSharedShieldSession(app.packageName) ->
                "SHARED SHIELD SESSION  •  exact per-app live unavailable"
            profile != null ->
                "HISTORY  •  last ${age(profile.lastSeenAt)}  •  ${profile.blockedDecisions} blocked total"
            else -> null
        }
        val activityStatus = statusLine?.let {
            text(
                it,
                11f,
                when {
                    isExactLive(app.packageName) -> green
                    isSharedShieldSession(app.packageName) -> amber
                    else -> secondary
                },
                if (isExactLive(app.packageName)) Typeface.BOLD else Typeface.NORMAL
            ).top(dp(5))
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val block = MaterialButton(this).apply { minWidth = 0; minimumWidth = 0; textSize = 11f }
        val shield = MaterialButton(this).apply { minWidth = 0; minimumWidth = 0; textSize = 11f }
        val details = MaterialButton(this).apply {
            minWidth = 0; minimumWidth = 0; textSize = 11f
            text = if (expandedPackageName == app.packageName) "HIDE LIVE ACTIVITY" else "VIEW LIVE ACTIVITY"
        }

        fun refreshButtons() {
            val blocked = FirewallRuleStore.isBlocked(this, app.packageName)
            val shielded = TrackerShieldRuleStore.isProtected(this, app.packageName)
            block.text = if (blocked) "BLOCK SELECTED" else "BLOCK"
            shield.text = if (shielded) "SHIELD SELECTED" else "SHIELD"
            block.alpha = if (blocked) 1f else 0.72f
            shield.alpha = if (shielded) 1f else 0.72f
        }

        block.setOnClickListener {
            val next = !FirewallRuleStore.isBlocked(this, app.packageName)
            FirewallRuleStore.setBlocked(this, app.packageName, next)
            if (next) TrackerShieldRuleStore.setProtected(this, app.packageName, false)
            refreshButtons(); refreshSummary(); if (selectedOnly) renderApps()
        }
        shield.setOnClickListener {
            val next = !TrackerShieldRuleStore.isProtected(this, app.packageName)
            TrackerShieldRuleStore.setProtected(this, app.packageName, next)
            if (next) FirewallRuleStore.setBlocked(this, app.packageName, false)
            refreshButtons(); refreshSummary(); if (selectedOnly) renderApps()
        }
        details.setOnClickListener {
            if (expandedPackageName == app.packageName) {
                expandedPackageName = null
                baselineDecisions = 0L
                baselineBlocked = 0L
                baselineStartedAt = 0L
                frozenAt = 0L
                frozenDecisions = 0L
                frozenBlocked = 0L
                frozenReportText = null
            } else {
                expandedPackageName = app.packageName
                resetLiveWindow(app.packageName)
            }
            renderApps()
            restartLiveRefresh()
        }

        buttons.addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(shield, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        row.addView(name)
        row.addView(pkg)
        activityStatus?.let { row.addView(it) }
        row.addView(buttons.top(dp(9)))
        row.addView(details.top(dp(4)))
        if (expandedPackageName == app.packageName) {
            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(activityPanel(app))
            }
            expandedPanelHost = host
            row.addView(host.top(dp(8)))
        }
        refreshButtons()
        return row
    }

    private fun refreshExpandedPanel() {
        val packageName = expandedPackageName ?: return
        val host = expandedPanelHost ?: run {
            renderApps()
            return
        }
        val app = apps.firstOrNull { it.packageName == packageName } ?: return
        host.removeAllViews()
        host.addView(activityPanel(app))
        refreshSummary()
    }

    private fun openAppForTest(app: FirewallApp) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Guardian could not open ${app.label}", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(launchIntent)
    }

    private fun buildLiveReport(
        app: FirewallApp,
        profile: TrackerActivityStore.AppProfile
    ): String {
        val number = NumberFormat.getIntegerInstance()
        val rightNow = TrackerActivityStore.recentWindow(
            this,
            app.packageName,
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)
        )
        val deltaDecisions = (profile.cumulativeDecisions - baselineDecisions).coerceAtLeast(0L)
        val deltaBlocked = (profile.blockedDecisions - baselineBlocked).coerceAtLeast(0L)
        val deltaAllowed = (deltaDecisions - deltaBlocked).coerceAtLeast(0L)
        val activeRules = GuardianVpnService.activeRulePackages()
        val exactState = if (
            GuardianVpnService.currentMode() == GuardianVpnService.Mode.TRACKER_SHIELD &&
            activeRules.size == 1 &&
            app.packageName in activeRules
        ) "EXACT LIVE" else "NOT EXACT LIVE"

        val recent = profile.recentDecisions
            .filter { baselineStartedAt <= 0L || it.lastSeenAt >= baselineStartedAt }
            .take(8)
            .joinToString("\n") { decision ->
                val state = if (decision.blocked) "BLOCKED" else "ALLOWED"
                val detail = listOfNotNull(decision.category, decision.provider)
                    .distinct()
                    .joinToString(" · ")
                "$state  ${decision.domain}  ×${decision.count}" +
                    if (detail.isBlank()) "" else "  [$detail]"
            }

        return buildString {
            appendLine("GUARDIAN APP ACTIVITY REPORT")
            appendLine(app.label)
            appendLine(app.packageName)
            appendLine()
            appendLine("Status: $exactState")
            appendLine(
                "Right now (last 60 sec): ${number.format(rightNow.decisions)} DNS · " +
                    "${number.format(rightNow.blockedDecisions)} blocked · " +
                    "${number.format(rightNow.allowedDecisions)} allowed"
            )
            appendLine(
                "Live test window: +${number.format(deltaDecisions)} DNS · +" +
                    "${number.format(deltaBlocked)} blocked · +" +
                    "${number.format(deltaAllowed)} allowed · started ${age(baselineStartedAt)}"
            )
            appendLine(
                "Retained history: ${number.format(profile.cumulativeDecisions)} DNS · " +
                    "${number.format(profile.blockedDecisions)} blocked · " +
                    "${number.format(profile.allowedDecisions)} allowed · " +
                    "${profile.uniqueTrackerDomains} tracker domain(s)"
            )
            if (profile.providers.isNotEmpty()) {
                appendLine()
                appendLine("Top tracker companies:")
                profile.providers.take(4).forEach {
                    appendLine("- ${it.name}: ${number.format(it.count)} blocked")
                }
            }
            if (recent.isNotBlank()) {
                appendLine()
                appendLine("Recent test evidence:")
                appendLine(recent)
            }
            appendLine()
            append("Guardian reports DNS activity it can attribute confidently. Tracker activity is a privacy signal, not proof of malware.")
        }.trim()
    }

    private fun copyLiveReport(app: FirewallApp, profile: TrackerActivityStore.AppProfile) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Guardian app activity report",
                frozenReportText ?: buildLiveReport(app, profile)
            )
        )
        Toast.makeText(this, "Guardian report copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLiveReport(app: FirewallApp, profile: TrackerActivityStore.AppProfile) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Guardian app activity report: ${app.label}")
            putExtra(Intent.EXTRA_TEXT, frozenReportText ?: buildLiveReport(app, profile))
        }
        startActivity(Intent.createChooser(shareIntent, "Share Guardian report"))
    }

    private fun activityPanel(app: FirewallApp): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(raised, 14f)
        }
        val profile = TrackerActivityStore.profile(this, app.packageName)
        val liveMode = isExactLive(app.packageName)
        val sharedShieldSession = isSharedShieldSession(app.packageName)
        panel.addView(text(if (liveMode) "APP ACTIVITY  •  LIVE" else "APP ACTIVITY", 12f, if (liveMode) green else violet, Typeface.BOLD))
        panel.addView(text(
            when {
                liveMode -> "Updating every 2 seconds while this panel is open. The live test window keeps counting while you switch to the app and come back."
                sharedShieldSession -> "Tracker Shield is protecting multiple apps. Guardian will not claim exact per-app live attribution in this shared session."
                else -> "Live exact attribution requires Tracker Shield to be running with only this app in the active shield session."
            },
            11f,
            secondary,
            Typeface.NORMAL
        ).top(dp(3)))

        if (profile == null || profile.cumulativeDecisions <= 0L) {
            panel.addView(text("No exact traffic history is available for ${app.label} yet. Guardian records per-app DNS history only when attribution is confident; an empty history does not mean the app made no network connections.", 13f, secondary, Typeface.NORMAL).top(dp(7)))
            panel.addView(text("Try running Tracker Shield with this app selected, use the app normally, then keep this panel open.", 12f, primary, Typeface.BOLD).top(dp(8)))
        } else {
            val number = NumberFormat.getIntegerInstance()
            val now = System.currentTimeMillis()
            val rightNow = TrackerActivityStore.recentWindow(
                this,
                app.packageName,
                now - TimeUnit.MINUTES.toMillis(1)
            )
            val rightNowColor = when {
                !liveMode -> secondary
                rightNow.blockedDecisions > 0L -> amber
                rightNow.decisions > 0L -> green
                else -> secondary
            }
            val rightNowState = when {
                !liveMode && sharedShieldSession ->
                    "NOT EXACT LIVE  •  This is a shared Tracker Shield session. Use one active shielded app for exact per-app RIGHT NOW activity."
                !liveMode ->
                    "NOT LIVE  •  Exact per-app monitoring is not active for this app right now. Last exact activity ${age(profile.lastSeenAt)}."
                rightNow.decisions == 0L ->
                    "QUIET  •  No exact-attribution DNS activity in the last 60 seconds. Last app activity ${age(profile.lastSeenAt)}."
                rightNow.blockedDecisions == 0L -> {
                    val domains = rightNow.topDomains.joinToString(", ") { it.name }
                    "ACTIVE  •  ${number.format(rightNow.decisions)} DNS  •  0 blocked  •  ${number.format(rightNow.allowedDecisions)} allowed" +
                        if (domains.isBlank()) "" else "\nMost active: $domains"
                }
                else -> {
                    val providers = rightNow.blockedProviders.joinToString(", ") { it.name }
                    "ACTIVE  •  ${number.format(rightNow.decisions)} DNS  •  ${number.format(rightNow.blockedDecisions)} blocked  •  ${number.format(rightNow.allowedDecisions)} allowed" +
                        if (providers.isBlank()) "\nGuardian is blocking tracker traffic now." else "\nBlocking now: $providers"
                }
            }
            panel.addView(text("RIGHT NOW  •  LAST 60 SEC", 11f, rightNowColor, Typeface.BOLD).top(dp(9)))
            panel.addView(text(rightNowState, 12f, primary, Typeface.BOLD).top(dp(3)))
            panel.addView(text(
                "This short window is separate from the longer live test below and only uses traffic Guardian can attribute confidently to this app.",
                11f,
                secondary,
                Typeface.NORMAL
            ).top(dp(3)))

            val testDecisions = if (frozenAt > 0L) frozenDecisions else profile.cumulativeDecisions
            val testBlocked = if (frozenAt > 0L) frozenBlocked else profile.blockedDecisions
            val deltaDecisions = (testDecisions - baselineDecisions).coerceAtLeast(0L)
            val deltaBlocked = (testBlocked - baselineBlocked).coerceAtLeast(0L)
            val deltaAllowed = (deltaDecisions - deltaBlocked).coerceAtLeast(0L)
            val liveWindowColor = when {
                deltaBlocked > 0L -> amber
                deltaDecisions > 0L -> green
                else -> secondary
            }
            val newRows = profile.recentDecisions.filter {
                baselineStartedAt > 0L &&
                    it.lastSeenAt >= baselineStartedAt &&
                    (frozenAt <= 0L || it.lastSeenAt <= frozenAt)
            }
            val newBlockedRows = newRows.filter { it.blocked }
            val newAllowedRows = newRows.filterNot { it.blocked }
            val liveWindowSummary = when {
                deltaDecisions == 0L -> "No new exact-attribution DNS decisions yet. Open ${app.label}, use it for a moment, then return here."
                deltaBlocked == 0L -> "New traffic was seen, with no new tracker blocks in this test window."
                deltaBlocked * 4L < deltaDecisions -> "Some new tracker traffic was blocked, while most new DNS decisions were allowed."
                else -> "Tracker activity was elevated in this test window. Review the NEW rows and companies below; this is a privacy signal, not proof of malware."
            }
            val changedExplanation = when {
                deltaDecisions == 0L -> "Guardian is ready to compare the next activity against this baseline."
                deltaBlocked == 0L -> {
                    val examples = newAllowedRows.map { it.domain }.distinct().take(3)
                    if (examples.isEmpty()) {
                        "Guardian saw new allowed DNS activity and no newly blocked trackers."
                    } else {
                        "Guardian saw only allowed service traffic in this window. Recent examples: ${examples.joinToString(", ")}."
                    }
                }
                else -> {
                    val providers = newBlockedRows.mapNotNull { it.provider }.distinct().take(3)
                    val providerText = if (providers.isEmpty()) "classified tracker traffic" else providers.joinToString(", ")
                    "Guardian blocked ${number.format(deltaBlocked)} new tracker request(s) in this window. New blocked traffic included ${providerText}. This is a privacy event, not a malware finding."
                }
            }
            panel.addView(text(if (frozenAt > 0L) "TEST RESULT  •  FROZEN" else "LIVE TEST WINDOW", 11f, liveWindowColor, Typeface.BOLD).top(dp(9)))
            panel.addView(text("+${number.format(deltaDecisions)} DNS  •  +${number.format(deltaBlocked)} blocked  •  +${number.format(deltaAllowed)} allowed\nStarted ${age(baselineStartedAt)}${if (frozenAt > 0L) "  •  frozen " + age(frozenAt) else ""}", 12f, primary, Typeface.BOLD).top(dp(3)))
            panel.addView(text(liveWindowSummary, 12f, secondary, Typeface.NORMAL).top(dp(4)))
            panel.addView(text("WHAT CHANGED?", 11f, violet, Typeface.BOLD).top(dp(9)))
            panel.addView(text(changedExplanation, 12f, primary, Typeface.NORMAL).top(dp(3)))
            panel.addView(MaterialButton(this).apply {
                text = if (frozenAt > 0L) "START NEW TEST" else "END TEST / FREEZE RESULT"
                minHeight = dp(40)
                setOnClickListener {
                    if (frozenAt > 0L) {
                        resetLiveWindow(app.packageName)
                        refreshExpandedPanel()
                        restartLiveRefresh()
                    } else {
                        freezeLiveWindow(app)
                    }
                }
            }.top(dp(7)))
            if (frozenAt <= 0L) {
                panel.addView(MaterialButton(this).apply {
                    text = "RESET LIVE COUNTER"
                    minHeight = dp(40)
                    setOnClickListener {
                        resetLiveWindow(app.packageName)
                        refreshExpandedPanel()
                        restartLiveRefresh()
                    }
                }.top(dp(4)))
            } else {
                panel.addView(text(
                    "This test result is locked. New traffic can continue, but it will not change the frozen test totals or copied/shared report.",
                    11f,
                    secondary,
                    Typeface.NORMAL
                ).top(dp(4)))
            }

            val status = when {
                profile.blockedDecisions == 0L -> "Nothing in Guardian's retained exact history was classified as a tracker. That is reassuring, but it is not a malware verdict."
                profile.blockedDecisions * 4L < profile.cumulativeDecisions -> "Some tracker activity was seen, but most attributed DNS requests were allowed. This is common in ad-supported or analytics-enabled apps."
                else -> "Guardian has repeatedly blocked classified tracker requests from this app. Review the companies and domains below; frequent tracking is a privacy concern, not proof of malware."
            }
            panel.addView(text("${number.format(profile.cumulativeDecisions)} DNS decisions  •  ${number.format(profile.blockedDecisions)} blocked  •  ${number.format(profile.allowedDecisions)} allowed\n${profile.uniqueTrackerDomains} tracker domain(s) seen  •  last activity ${age(profile.lastSeenAt)}", 13f, primary, Typeface.BOLD).top(dp(10)))
            panel.addView(text("SHOULD I CARE?", 11f, if (profile.blockedDecisions > 0L) amber else green, Typeface.BOLD).top(dp(11)))
            panel.addView(text(status, 13f, secondary, Typeface.NORMAL).top(dp(4)))

            if (profile.providers.isNotEmpty()) {
                panel.addView(text("TRACKER COMPANIES", 11f, violet, Typeface.BOLD).top(dp(12)))
                profile.providers.take(4).forEach { provider ->
                    panel.addView(text("• ${provider.name} — ${number.format(provider.count)} blocked request(s)", 12f, primary, Typeface.NORMAL).top(dp(3)))
                }
            }

            if (profile.recentDecisions.isNotEmpty()) {
                panel.addView(text("RECENT DNS ACTIVITY", 11f, violet, Typeface.BOLD).top(dp(12)))
                profile.recentDecisions.take(8).forEach { decision ->
                    val state = if (decision.blocked) "BLOCKED" else "ALLOWED"
                    val isNew = baselineStartedAt > 0L && decision.lastSeenAt >= baselineStartedAt
                    val detail = listOfNotNull(decision.category, decision.provider).distinct().joinToString(" · ")
                    val purpose = domainPurpose(decision.domain, decision.blocked, decision.category)
                    val suffix = listOf(detail, purpose).filter { it.isNotBlank() }.joinToString("\n  ")
                    panel.addView(text("${if (isNew) "NEW  " else ""}$state  ${decision.domain}  ×${decision.count}${if (suffix.isBlank()) "" else "\n  $suffix"}", 12f, if (isNew || decision.blocked) primary else secondary, if (isNew || decision.blocked) Typeface.BOLD else Typeface.NORMAL).top(dp(5)))
                }
            }
        }

        if (!liveMode) {
            panel.addView(MaterialButton(this).apply {
                text = "WATCH ONLY THIS APP"
                minHeight = dp(44)
                setOnClickListener { requestExactWatch(app.packageName) }
            }.top(dp(12)))
            panel.addView(text(
                when {
                    sharedShieldSession -> "This will replace the shared Tracker Shield selection with only ${app.label} so Guardian can attribute its DNS activity exactly."
                    GuardianVpnService.currentMode() == GuardianVpnService.Mode.OFF -> "This will make ${app.label} the only shielded app and start Tracker Shield after Android VPN permission if needed."
                    else -> "This saves ${app.label} as the only shielded app. Guardian will not replace Firewall or Lock Down automatically."
                },
                11f,
                secondary,
                Typeface.NORMAL
            ).top(dp(4)))
        }

        if (liveMode && packageManager.getLaunchIntentForPackage(app.packageName) != null) {
            panel.addView(MaterialButton(this).apply {
                text = "OPEN APP TO TEST"
                minHeight = dp(44)
                setOnClickListener { openAppForTest(app) }
            }.top(dp(12)))
            panel.addView(text(
                "Guardian keeps the exact-watch session running while you use the app. Return here to review RIGHT NOW and WHAT CHANGED.",
                11f,
                secondary,
                Typeface.NORMAL
            ).top(dp(4)))
        }

        if (profile != null && profile.cumulativeDecisions > 0L) {
            val reportActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            reportActions.addView(MaterialButton(this).apply {
                text = "COPY LIVE REPORT"
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { copyLiveReport(app, profile) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            reportActions.addView(MaterialButton(this).apply {
                text = "SHARE"
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { shareLiveReport(app, profile) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            })
            panel.addView(reportActions.top(dp(12)))
            panel.addView(text(
                if (frozenAt > 0L)
                    "The copied/shared report is locked to the frozen test result."
                else
                    "The report includes the current 60-second state, this live test window, retained totals, tracker companies, and recent evidence.",
                11f,
                secondary,
                Typeface.NORMAL
            ).top(dp(4)))
        }

        val allow = MaterialButton(this).apply {
            text = "ALLOW NORMAL TRAFFIC"
            minHeight = dp(44)
            setOnClickListener {
                FirewallRuleStore.setBlocked(this@ProtectionAppsActivity, app.packageName, false)
                TrackerShieldRuleStore.setProtected(this@ProtectionAppsActivity, app.packageName, false)
                refreshSummary(); renderApps(); restartLiveRefresh()
            }
        }
        panel.addView(allow.top(dp(12)))
        panel.addView(text("Rule changes are saved here. If Guardian protection is already running, return Home and use APPLY TO CURRENT MODE when Guardian shows it.", 11f, secondary, Typeface.NORMAL).top(dp(6)))
        return panel
    }

    private fun domainPurpose(domain: String, blocked: Boolean, category: String?): String = when {
        blocked -> category?.let { "Guardian classified this as ${it.lowercase()} traffic." } ?: "Guardian classified this domain as tracker traffic."
        domain.contains("payments", true) -> "Likely payment/account service traffic."
        domain.contains("chat", true) || domain.contains("messenger", true) -> "Likely messaging service traffic."
        domain.contains("video", true) || domain.contains("cdn", true) || domain.contains("fbcdn", true) -> "Likely content delivery/media traffic."
        domain.contains("graph", true) -> "Likely app/API service traffic."
        else -> "Allowed service traffic; Guardian has not classified this domain as a tracker."
    }

    private fun age(timestamp: Long): String {
        if (timestamp <= 0L) return "unknown"
        val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        return when {
            elapsed < TimeUnit.MINUTES.toMillis(1) -> "just now"
            elapsed < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(elapsed)}m ago"
            elapsed < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(elapsed)}h ago"
            else -> "${TimeUnit.MILLISECONDS.toDays(elapsed)}d ago"
        }
    }

    private fun refreshSummary() {
        val blocked = FirewallRuleStore.blockedCount(this)
        val shielded = TrackerShieldRuleStore.protectedCount(this)
        val mode = GuardianVpnService.currentMode()
        val note = when (mode) {
            GuardianVpnService.Mode.FIREWALL, GuardianVpnService.Mode.TRACKER_SHIELD -> "Saved selection only. Return Home and use APPLY TO CURRENT MODE if shown; changes do not alter the running VPN until applied."
            GuardianVpnService.Mode.LOCKDOWN -> "Lock Down remains active. Changing selections will not restore the network."
            else -> "Selections saved. Return Home to start a protection mode."
        }
        summary.text = "SMART FIREWALL  $blocked app(s)\nTRACKER SHIELD  $shielded app(s)\n\n$note"
        summary.setTextColor(if ((preferredMode == MODE_FIREWALL && blocked > 0) || (preferredMode == MODE_SHIELD && shielded > 0)) green else primary)
    }

    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setTypeface(typeface, style); setLineSpacing(0f, 1.08f)
    }

    private fun rounded(fill: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun <T : android.view.View> T.top(px: Int): T {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams)?.apply { topMargin = px }
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px }
        return this
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}