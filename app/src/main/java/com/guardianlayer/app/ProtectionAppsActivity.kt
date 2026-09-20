package com.guardianlayer.app

import android.graphics.Color
import android.graphics.Typeface
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
import androidx.appcompat.app.AppCompatActivity
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
    private val liveHandler = Handler(Looper.getMainLooper())
    private val liveRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && expandedPackageName != null && ::list.isInitialized && !loading) {
                renderApps()
                liveHandler.postDelayed(this, 2_000L)
            }
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
        if (expandedPackageName != null && !loading) liveHandler.postDelayed(liveRefresh, 2_000L)
    }

    private fun resetLiveWindow(packageName: String) {
        val profile = TrackerActivityStore.profile(this, packageName)
        baselineDecisions = profile?.cumulativeDecisions ?: 0L
        baselineBlocked = profile?.blockedDecisions ?: 0L
        baselineStartedAt = System.currentTimeMillis()
    }

    private fun renderApps() {
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
            } else {
                expandedPackageName = app.packageName
                resetLiveWindow(app.packageName)
            }
            renderApps()
            restartLiveRefresh()
        }

        buttons.addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(shield, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        row.addView(name); row.addView(pkg); row.addView(buttons.top(dp(9))); row.addView(details.top(dp(4)))
        if (expandedPackageName == app.packageName) row.addView(activityPanel(app).top(dp(8)))
        refreshButtons()
        return row
    }

    private fun activityPanel(app: FirewallApp): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(raised, 14f)
        }
        val profile = TrackerActivityStore.profile(this, app.packageName)
        val liveMode = GuardianVpnService.currentMode() == GuardianVpnService.Mode.TRACKER_SHIELD &&
            TrackerShieldRuleStore.isProtected(this, app.packageName)
        panel.addView(text(if (liveMode) "APP ACTIVITY  •  LIVE" else "APP ACTIVITY", 12f, if (liveMode) green else violet, Typeface.BOLD))
        panel.addView(text(
            if (liveMode) "Updating every 2 seconds while this panel is open. The live test window keeps counting while you switch to the app and come back." else "Live exact attribution requires Tracker Shield to be running with this app selected.",
            11f,
            secondary,
            Typeface.NORMAL
        ).top(dp(3)))

        if (profile == null || profile.cumulativeDecisions <= 0L) {
            panel.addView(text("No exact traffic history is available for ${app.label} yet. Guardian records per-app DNS history only when attribution is confident; an empty history does not mean the app made no network connections.", 13f, secondary, Typeface.NORMAL).top(dp(7)))
            panel.addView(text("Try running Tracker Shield with this app selected, use the app normally, then keep this panel open.", 12f, primary, Typeface.BOLD).top(dp(8)))
        } else {
            val number = NumberFormat.getIntegerInstance()
            val deltaDecisions = (profile.cumulativeDecisions - baselineDecisions).coerceAtLeast(0L)
            val deltaBlocked = (profile.blockedDecisions - baselineBlocked).coerceAtLeast(0L)
            val deltaAllowed = (deltaDecisions - deltaBlocked).coerceAtLeast(0L)
            val liveWindowColor = when {
                deltaBlocked > 0L -> amber
                deltaDecisions > 0L -> green
                else -> secondary
            }
            val newRows = profile.recentDecisions.filter { baselineStartedAt > 0L && it.lastSeenAt >= baselineStartedAt }
            val newBlockedRows = newRows.filter { it.blocked }
            val newAllowedRows = newRows.filterNot { it.blocked }
            val liveWindowSummary = when {
                deltaDecisions == 0L -> "No new exact-attribution DNS decisions yet. Open ${app.label}, use it for a moment, then return here."
                deltaBlocked == 0L -> "New traffic was seen, with no new tracker blocks in this test window."
                deltaBlocked * 4L < deltaDecisions -> "Some new tracker traffic was blocked, while most new DNS decisions were allowed."
                else -> "This test window is tracker-heavy. Review the NEW rows and companies below; this is a privacy signal, not proof of malware."
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
            panel.addView(text("LIVE TEST WINDOW", 11f, liveWindowColor, Typeface.BOLD).top(dp(9)))
            panel.addView(text("+${number.format(deltaDecisions)} DNS  •  +${number.format(deltaBlocked)} blocked  •  +${number.format(deltaAllowed)} allowed\nStarted ${age(baselineStartedAt)}", 12f, primary, Typeface.BOLD).top(dp(3)))
            panel.addView(text(liveWindowSummary, 12f, secondary, Typeface.NORMAL).top(dp(4)))
            panel.addView(text("WHAT CHANGED?", 11f, violet, Typeface.BOLD).top(dp(9)))
            panel.addView(text(changedExplanation, 12f, primary, Typeface.NORMAL).top(dp(3)))
            panel.addView(MaterialButton(this).apply {
                text = "RESET LIVE COUNTER"
                minHeight = dp(40)
                setOnClickListener {
                    resetLiveWindow(app.packageName)
                    renderApps()
                    restartLiveRefresh()
                }
            }.top(dp(7)))

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