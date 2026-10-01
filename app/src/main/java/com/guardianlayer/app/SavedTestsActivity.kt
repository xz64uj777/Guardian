package com.guardianlayer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianlayer.app.data.GuardianTestSnapshotStore
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SavedTestsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    private val page = Color.rgb(11, 12, 17)
    private val raised = Color.rgb(31, 33, 43)
    private val primary = Color.rgb(246, 246, 250)
    private val secondary = Color.rgb(177, 181, 193)
    private val violet = Color.rgb(166, 151, 255)
    private val green = Color.rgb(116, 213, 155)
    private val amber = Color.rgb(241, 190, 92)

    private data class SessionMetrics(
        val durationMs: Long,
        val blockRate: Long,
        val dnsPerMinute: Double?,
        val blockedPerMinute: Double?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Guardian Saved Tests"
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildUi(): ScrollView = ScrollView(this).apply {
        setBackgroundColor(page)
        isFillViewport = true
        root = LinearLayout(this@SavedTestsActivity).apply {
            orientation = LinearLayout.VERTICAL
            val side = if (resources.configuration.screenWidthDp >= 600) dp(48) else dp(18)
            setPadding(side, dp(22), side, dp(36))
        }
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun render() {
        root.removeAllViews()
        val snapshots = GuardianTestSnapshotStore.snapshots(this)
        val grouped = snapshots.groupBy { it.packageName }
        val comparableApps = grouped.count { (_, tests) -> tests.size >= 2 }

        root.addView(text("SAVED TEST HISTORY", 27f, primary, Typeface.BOLD))
        root.addView(text(
            "Frozen Exact Watch results stay on this device so you can reopen, compare, copy, or share them later. New traffic cannot alter a saved report.",
            13f,
            secondary,
            Typeface.NORMAL
        ).top(dp(5)))
        root.addView(text("LOCAL • EXACT ATTRIBUTION", 11f, green, Typeface.BOLD).top(dp(10)))

        if (snapshots.isEmpty()) {
            root.addView(card().apply {
                addView(text("No saved tests yet.", 16f, primary, Typeface.BOLD))
                addView(text(
                    "Run Exact Watch, then choose END TEST / FREEZE RESULT. Guardian will save the frozen report here.",
                    12f,
                    secondary,
                    Typeface.NORMAL
                ).top(dp(5)))
            }.top(dp(18)))
            return
        }

        root.addView(card().apply {
            addView(text("SESSION COMPARISON", 11f, violet, Typeface.BOLD))
            addView(text(
                if (comparableApps == 0)
                    "Save a second Exact Watch for the same app to unlock session-to-session comparison."
                else
                    "$comparableApps app(s) have repeat tests that Guardian can compare.",
                15f,
                primary,
                Typeface.BOLD
            ).top(dp(5)))
            addView(text(
                "Guardian compares blocked share plus DNS and blocked-request rates per minute. This reduces distortion from different test lengths, but different app use and network conditions can still change the result.",
                11f,
                secondary,
                Typeface.NORMAL
            ).top(dp(6)))
        }.top(dp(18)))

        root.addView(text("${snapshots.size} saved test(s)", 13f, primary, Typeface.BOLD).top(dp(18)))
        snapshots.forEach { snapshot ->
            val previous = grouped[snapshot.packageName]
                .orEmpty()
                .filter { it.endedAt < snapshot.endedAt }
                .maxByOrNull { it.endedAt }
            root.addView(snapshotCard(snapshot, previous).top(dp(10)))
        }

        root.addView(MaterialButton(this).apply {
            text = "CLEAR SAVED TEST HISTORY"
            minHeight = dp(44)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SavedTestsActivity)
                    .setTitle("Clear saved tests?")
                    .setMessage("This deletes frozen test reports stored by Guardian. Cumulative Tracker Shield history is not affected.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CLEAR") { _, _ ->
                        GuardianTestSnapshotStore.clear(this@SavedTestsActivity)
                        render()
                    }
                    .show()
            }
        }.top(dp(18)))
    }

    private fun snapshotCard(
        snapshot: GuardianTestSnapshotStore.Snapshot,
        previous: GuardianTestSnapshotStore.Snapshot?
    ): LinearLayout {
        val number = NumberFormat.getIntegerInstance()
        val metrics = metrics(snapshot)

        return card().apply {
            addView(text(snapshot.appLabel, 17f, primary, Typeface.BOLD))
            addView(text(snapshot.packageName, 10f, secondary, Typeface.NORMAL).top(dp(2)))
            addView(text("${date(snapshot.endedAt)} • duration ${durationText(metrics.durationMs)}", 11f, secondary, Typeface.NORMAL).top(dp(6)))
            addView(text(
                "${number.format(snapshot.decisions)} DNS • ${number.format(snapshot.blocked)} blocked • ${number.format(snapshot.allowed)} allowed",
                13f,
                primary,
                Typeface.BOLD
            ).top(dp(7)))
            addView(text(
                if (snapshot.decisions == 0L) "No exact-attribution DNS decisions were observed during this saved test."
                else "${metrics.blockRate}% of observed DNS decisions were blocked as classified tracker traffic.",
                11f,
                if (snapshot.blocked > 0L) amber else green,
                Typeface.NORMAL
            ).top(dp(4)))

            if (previous != null) {
                val before = metrics(previous)
                addView(text("VS PREVIOUS TEST", 11f, violet, Typeface.BOLD).top(dp(10)))
                addView(text(comparisonSummary(metrics, before), 12f, primary, Typeface.NORMAL).top(dp(3)))
                addView(MaterialButton(this@SavedTestsActivity).apply {
                    text = "COMPARE TO PREVIOUS"
                    minHeight = dp(42)
                    setOnClickListener { showComparison(snapshot, previous) }
                }.top(dp(7)))
            } else {
                addView(text(
                    "First retained saved test for this app. The next saved test will unlock comparison.",
                    11f,
                    secondary,
                    Typeface.NORMAL
                ).top(dp(9)))
            }

            addView(MaterialButton(this@SavedTestsActivity).apply {
                text = "VIEW SAVED REPORT"
                minHeight = dp(42)
                setOnClickListener { showReport(snapshot) }
            }.top(dp(8)))
        }
    }

    private fun metrics(snapshot: GuardianTestSnapshotStore.Snapshot): SessionMetrics {
        val durationMs = (snapshot.endedAt - snapshot.startedAt).coerceAtLeast(0L)
        val blockRate = if (snapshot.decisions > 0L) {
            (snapshot.blocked * 100L) / snapshot.decisions
        } else 0L
        val minutes = durationMs.toDouble() / TimeUnit.MINUTES.toMillis(1).toDouble()
        val dnsPerMinute = if (minutes > 0.05) snapshot.decisions / minutes else null
        val blockedPerMinute = if (minutes > 0.05) snapshot.blocked / minutes else null
        return SessionMetrics(durationMs, blockRate, dnsPerMinute, blockedPerMinute)
    }

    private fun comparisonSummary(current: SessionMetrics, previous: SessionMetrics): String {
        val blockPoints = current.blockRate - previous.blockRate
        return buildString {
            append("Blocked share ${current.blockRate}% vs ${previous.blockRate}% (${signed(blockPoints.toDouble(), " pts", 0)})")
            if (current.blockedPerMinute != null && previous.blockedPerMinute != null) {
                append("\nBlocked/min ${rate(current.blockedPerMinute)} vs ${rate(previous.blockedPerMinute)} (${rateChange(current.blockedPerMinute, previous.blockedPerMinute)})")
            }
            if (current.dnsPerMinute != null && previous.dnsPerMinute != null) {
                append("\nDNS/min ${rate(current.dnsPerMinute)} vs ${rate(previous.dnsPerMinute)} (${rateChange(current.dnsPerMinute, previous.dnsPerMinute)})")
            }
        }
    }

    private fun buildComparisonText(
        current: GuardianTestSnapshotStore.Snapshot,
        previous: GuardianTestSnapshotStore.Snapshot
    ): String {
        val now = metrics(current)
        val before = metrics(previous)
        val number = NumberFormat.getIntegerInstance()

        fun session(label: String, snapshot: GuardianTestSnapshotStore.Snapshot, m: SessionMetrics): String =
            buildString {
                appendLine("$label — ${date(snapshot.endedAt)}")
                appendLine("Duration: ${durationText(m.durationMs)}")
                appendLine("DNS: ${number.format(snapshot.decisions)}")
                appendLine("Blocked: ${number.format(snapshot.blocked)}")
                appendLine("Allowed: ${number.format(snapshot.allowed)}")
                appendLine("Blocked share: ${m.blockRate}%")
                appendLine("DNS/min: ${rate(m.dnsPerMinute)}")
                append("Blocked/min: ${rate(m.blockedPerMinute)}")
            }

        return buildString {
            appendLine("GUARDIAN SAVED TEST COMPARISON")
            appendLine(current.appLabel)
            appendLine(current.packageName)
            appendLine()
            appendLine(session("CURRENT", current, now))
            appendLine()
            appendLine()
            appendLine(session("PREVIOUS", previous, before))
            appendLine()
            appendLine()
            appendLine("CHANGE")
            appendLine(comparisonSummary(now, before))
            appendLine()
            append("These are exact-attribution DNS observations from two separate sessions. Different app use, session length, network conditions, and remote service behavior can change the numbers. This is not a malware finding or risk score.")
        }.trim()
    }

    private fun showComparison(
        current: GuardianTestSnapshotStore.Snapshot,
        previous: GuardianTestSnapshotStore.Snapshot
    ) {
        val comparison = buildComparisonText(current, previous)
        MaterialAlertDialogBuilder(this)
            .setTitle("${current.appLabel} • comparison")
            .setMessage(comparison)
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("COPY") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Guardian saved test comparison", comparison))
                Toast.makeText(this, "Guardian comparison copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showReport(snapshot: GuardianTestSnapshotStore.Snapshot) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${snapshot.appLabel} • saved test")
            .setMessage(snapshot.report.ifBlank { "No report text was retained for this test." })
            .setNegativeButton("CLOSE", null)
            .setNeutralButton("SHARE") { _, _ -> share(snapshot) }
            .setPositiveButton("COPY") { _, _ -> copy(snapshot) }
            .show()
    }

    private fun copy(snapshot: GuardianTestSnapshotStore.Snapshot) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Guardian saved test", snapshot.report))
        Toast.makeText(this, "Saved Guardian report copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(snapshot: GuardianTestSnapshotStore.Snapshot) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Guardian saved test: ${snapshot.appLabel}")
            putExtra(Intent.EXTRA_TEXT, snapshot.report)
        }, "Share Guardian saved test"))
    }

    private fun rateChange(current: Double?, previous: Double?): String {
        if (current == null || previous == null) return "n/a"
        return signed(current - previous, "/min", 1)
    }

    private fun signed(value: Double, suffix: String, decimals: Int): String {
        val format = if (decimals == 0) "%.0f" else "%.1f"
        val rendered = String.format(Locale.US, format, value)
        val sign = if (value > 0.0) "+" else ""
        return "$sign$rendered$suffix"
    }

    private fun rate(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"

    private fun durationText(duration: Long): String = when {
        duration < TimeUnit.MINUTES.toMillis(1) -> "under 1m"
        duration < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(duration)}m"
        else -> "${TimeUnit.MILLISECONDS.toHours(duration)}h ${TimeUnit.MILLISECONDS.toMinutes(duration) % 60}m"
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(raised, 16f)
    }

    private fun date(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
        setLineSpacing(0f, 1.08f)
    }

    private fun rounded(fill: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun <T : android.view.View> T.top(px: Int): T {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams)?.apply { topMargin = px }
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px }
        return this
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
