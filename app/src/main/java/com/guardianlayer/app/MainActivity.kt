package com.guardianlayer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import com.guardianlayer.app.firewall.GuardianVpnService
import com.guardianlayer.app.privacy.InstalledAppRiskAnalyzer
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var privacyView: TextView
    private lateinit var lockdownButton: MaterialButton
    private lateinit var timeline: LinearLayout

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startLockdown()
        } else {
            GuardianEventStore.append(
                this,
                "INFO",
                "VPN permission not granted",
                "Guardian did not activate Lock Down."
            )
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(text("GUARDIAN", 30f, Color.WHITE, Typeface.BOLD))
        root.addView(text("Understand. Control. Protect.", 15f, Color.rgb(168, 173, 183), Typeface.NORMAL).withTop(dp(4)))

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

        val privacyButton = MaterialButton(this).apply {
            text = "RUN PRIVACY SNAPSHOT"
            textSize = 15f
            minHeight = dp(52)
            setOnClickListener { runPrivacySnapshot() }
        }
        root.addView(privacyButton.withTop(dp(10)))

        root.addView(text("PRIVACY SNAPSHOT", 13f, Color.rgb(168, 173, 183), Typeface.BOLD).withTop(dp(28)))
        privacyView = text("No snapshot yet. Guardian will review visible launcher apps and explain sensitive permissions that are currently granted.", 15f, Color.WHITE, Typeface.NORMAL).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(27, 30, 37), 16f)
        }
        root.addView(privacyView.withTop(dp(8)))

        root.addView(text("GUARDIAN TIMELINE", 13f, Color.rgb(168, 173, 183), Typeface.BOLD).withTop(dp(28)))
        timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(timeline.withTop(dp(8)))

        return scroll
    }

    private fun toggleLockdown() {
        if (GuardianStateStore.isLockdownActive(this)) {
            lockdownButton.isEnabled = false
            lockdownButton.text = "STOPPING LOCK DOWN…"

            // Send the service its explicit stop command instead of relying on
            // generic service destruction. This ensures the TUN descriptor is
            // closed before the foreground service exits.
            startService(
                Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_STOP)
            )

            Handler(Looper.getMainLooper()).postDelayed({
                lockdownButton.isEnabled = true
                refresh()
            }, 400)
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            startLockdown()
        }
    }

    private fun startLockdown() {
        val intent = Intent(this, GuardianVpnService::class.java)
            .setAction(GuardianVpnService.ACTION_LOCKDOWN)
        ContextCompat.startForegroundService(this, intent)
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 350)
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
        val active = GuardianStateStore.isLockdownActive(this)
        statusView.text = if (active) {
            "LOCK DOWN ACTIVE\n\nGuardian is blocking device network traffic. Guardian itself remains reachable so you can turn protection off."
        } else {
            "DEVICE ONLINE\n\nLock Down is off. Guardian is ready to isolate network traffic if you need it."
        }
        statusView.setTextColor(if (active) Color.rgb(255, 160, 160) else Color.WHITE)
        lockdownButton.text = if (active) "STOP LOCK DOWN" else "ACTIVATE LOCK DOWN"
        refreshTimeline()
    }

    private fun refreshTimeline() {
        timeline.removeAllViews()
        val events = GuardianEventStore.recent(this, 12)
        if (events.isEmpty()) {
            timeline.addView(text("Guardian events will appear here as the app observes or changes security state.", 14f, Color.rgb(168, 173, 183), Typeface.NORMAL))
            return
        }

        events.forEach { event ->
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.timestamp))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(Color.rgb(27, 30, 37), 14f)
                addView(text("${event.level} · $time", 11f, Color.rgb(168, 173, 183), Typeface.BOLD))
                addView(text(event.title, 16f, Color.WHITE, Typeface.BOLD).withTop(dp(4)))
                addView(text(event.detail, 14f, Color.rgb(220, 222, 228), Typeface.NORMAL).withTop(dp(4)))
            }
            timeline.addView(card.withTop(dp(8)))
        }
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

    private fun <T : android.view.View> T.withTop(top: Int): T {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
