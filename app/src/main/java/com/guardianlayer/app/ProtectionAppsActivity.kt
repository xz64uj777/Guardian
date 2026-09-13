package com.guardianlayer.app

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
import com.guardianlayer.app.firewall.FirewallApp
import com.guardianlayer.app.firewall.FirewallRuleStore
import com.guardianlayer.app.firewall.LauncherAppCatalog
import com.guardianlayer.app.firewall.TrackerShieldRuleStore

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

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var preferredMode: String = MODE_FIREWALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferredMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FIREWALL
        title = "Guardian Protection Apps"
        setContentView(buildUi())
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::summary.isInitialized) refreshSummary()
    }

    private fun buildUi(): ScrollView {
        return ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(LinearLayout(this@ProtectionAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(22), dp(18), dp(34))
                addView(text("PROTECTION APPS", 28f, primary, Typeface.BOLD))
                addView(text("Choose what Guardian should block or shield.", 14f, secondary, Typeface.NORMAL).top(dp(4)))

                summary = text("", 14f, primary, Typeface.BOLD).apply {
                    setPadding(dp(16), dp(15), dp(16), dp(15))
                    background = rounded(surface, 17f)
                }
                addView(summary.top(dp(20)))

                addView(text("BLOCK cuts an app's network access. SHIELD keeps it online and filters visible tracker DNS. An app can use one mode at a time.", 12f, secondary, Typeface.NORMAL).top(dp(10)))

                list = LinearLayout(this@ProtectionAppsActivity).apply { orientation = LinearLayout.VERTICAL }
                addView(list.top(dp(18)))

                addView(MaterialButton(this@ProtectionAppsActivity).apply {
                    text = "DONE"
                    minHeight = dp(48)
                    setOnClickListener { finish() }
                }.top(dp(18)))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loadApps() {
        list.removeAllViews()
        list.addView(text("Loading apps…", 14f, secondary, Typeface.NORMAL))
        Thread {
            val apps = LauncherAppCatalog(this).load()
            runOnUiThread {
                list.removeAllViews()
                if (apps.isEmpty()) {
                    list.addView(text("No launcher apps were visible to Guardian.", 14f, secondary, Typeface.NORMAL))
                } else {
                    apps.forEachIndexed { index, app -> list.addView(appRow(app).top(if (index == 0) 0 else dp(7))) }
                }
                refreshSummary()
            }
        }.start()
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

        fun refreshButtons() {
            val blocked = FirewallRuleStore.isBlocked(this, app.packageName)
            val shielded = TrackerShieldRuleStore.isProtected(this, app.packageName)
            block.text = if (blocked) "BLOCKED" else "BLOCK"
            shield.text = if (shielded) "SHIELDED" else "SHIELD"
            block.alpha = if (blocked) 1f else 0.72f
            shield.alpha = if (shielded) 1f else 0.72f
        }

        block.setOnClickListener {
            val next = !FirewallRuleStore.isBlocked(this, app.packageName)
            FirewallRuleStore.setBlocked(this, app.packageName, next)
            if (next) TrackerShieldRuleStore.setProtected(this, app.packageName, false)
            refreshButtons(); refreshSummary()
        }
        shield.setOnClickListener {
            val next = !TrackerShieldRuleStore.isProtected(this, app.packageName)
            TrackerShieldRuleStore.setProtected(this, app.packageName, next)
            if (next) FirewallRuleStore.setBlocked(this, app.packageName, false)
            refreshButtons(); refreshSummary()
        }

        buttons.addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(shield, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        row.addView(name); row.addView(pkg); row.addView(buttons.top(dp(9)))
        refreshButtons()
        return row
    }

    private fun refreshSummary() {
        val blocked = FirewallRuleStore.blockedCount(this)
        val shielded = TrackerShieldRuleStore.protectedCount(this)
        summary.text = "SMART FIREWALL  $blocked app(s)\nTRACKER SHIELD  $shielded app(s)"
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
