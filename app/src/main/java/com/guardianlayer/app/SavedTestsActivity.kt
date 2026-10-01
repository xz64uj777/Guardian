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
import java.util.concurrent.TimeUnit

class SavedTestsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    private val page = Color.rgb(11, 12, 17)
    private val raised = Color.rgb(31, 33, 43)
    private val primary = Color.rgb(246, 246, 250)
    private val secondary = Color.rgb(177, 181, 193)
    private val green = Color.rgb(116, 213, 155)
    private val amber = Color.rgb(241, 190, 92)

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

        root.addView(text("SAVED TEST HISTORY", 27f, primary, Typeface.BOLD))
        root.addView(text(
            "Frozen Exact Watch results stay on this device so you can reopen, copy, or share them later. New traffic cannot alter a saved report.",
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

        root.addView(text("${snapshots.size} saved test(s)", 13f, primary, Typeface.BOLD).top(dp(18)))
        snapshots.forEach { root.addView(snapshotCard(it).top(dp(10))) }

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

    private fun snapshotCard(snapshot: GuardianTestSnapshotStore.Snapshot): LinearLayout {
        val number = NumberFormat.getIntegerInstance()
        val blockRate = if (snapshot.decisions > 0L) (snapshot.blocked * 100L) / snapshot.decisions else 0L
        val duration = (snapshot.endedAt - snapshot.startedAt).coerceAtLeast(0L)
        val durationText = when {
            duration < TimeUnit.MINUTES.toMillis(1) -> "under 1m"
            duration < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(duration)}m"
            else -> "${TimeUnit.MILLISECONDS.toHours(duration)}h ${TimeUnit.MILLISECONDS.toMinutes(duration) % 60}m"
        }

        return card().apply {
            addView(text(snapshot.appLabel, 17f, primary, Typeface.BOLD))
            addView(text(snapshot.packageName, 10f, secondary, Typeface.NORMAL).top(dp(2)))
            addView(text("${date(snapshot.endedAt)} • duration $durationText", 11f, secondary, Typeface.NORMAL).top(dp(6)))
            addView(text(
                "${number.format(snapshot.decisions)} DNS • ${number.format(snapshot.blocked)} blocked • ${number.format(snapshot.allowed)} allowed",
                13f,
                primary,
                Typeface.BOLD
            ).top(dp(7)))
            addView(text(
                if (snapshot.decisions == 0L) "No exact-attribution DNS decisions were observed during this saved test."
                else "$blockRate% of observed DNS decisions were blocked as classified tracker traffic.",
                11f,
                if (snapshot.blocked > 0L) amber else green,
                Typeface.NORMAL
            ).top(dp(4)))
            addView(MaterialButton(this@SavedTestsActivity).apply {
                text = "VIEW SAVED REPORT"
                minHeight = dp(42)
                setOnClickListener { showReport(snapshot) }
            }.top(dp(8)))
        }
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
