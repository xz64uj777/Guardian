package com.guardianlayer.app

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.guardianlayer.app.security.GuardianApkScanner
import java.util.Locale

/** User-selected APK review. Files are inspected locally, never installed or executed. */
class ApkReviewActivity : AppCompatActivity() {
    private lateinit var result: TextView
    private lateinit var scanButton: MaterialButton
    private val background = Color.rgb(17, 19, 24)
    private val primary = Color.rgb(246, 246, 250)
    private val secondary = Color.rgb(177, 181, 193)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) review(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "APK Security Review"
        val scroll = ScrollView(this).apply { setBackgroundColor(background); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        root.addView(text("APK SECURITY REVIEW", 25f, primary, true))
        root.addView(text("Choose an APK to inspect it locally. Guardian calculates its SHA-256 hash, reads package metadata and requested permissions, and reviews selected risk combinations and the filename. The APK is not installed or executed.", 14f, secondary).apply { setPadding(0, dp(12), 0, dp(16)) })
        scanButton = MaterialButton(this).apply {
            text = "CHOOSE APK FILE"
            minHeight = dp(52)
            setOnClickListener { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) }
        }
        root.addView(scanButton)
        result = text("No APK reviewed yet.", 14f, primary).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(27, 30, 37))
        }
        root.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(text("A high score is a review priority, not proof of malware. A low score is not proof that an APK is safe. Known-threat matching only works when Guardian's bundled hash list contains a curated, verified feed.", 12f, secondary).apply { setPadding(0, dp(16), 0, 0) })
        setContentView(scroll)
    }

    private fun review(uri: Uri) {
        scanButton.isEnabled = false
        scanButton.text = "REVIEWING APK…"
        result.text = "Reviewing selected file locally…"
        Thread {
            val outcome = runCatching { GuardianApkScanner.scan(this, uri) }
            runOnUiThread {
                scanButton.isEnabled = true
                scanButton.text = "CHOOSE ANOTHER APK"
                val report = outcome.getOrNull()
                result.text = if (report == null) {
                    "Review could not be completed\n\n" + (outcome.exceptionOrNull()?.message ?: "Unknown scan error")
                } else buildString {
                    appendLine(report.classification)
                    appendLine("Review score: " + report.score + "/100")
                    appendLine()
                    appendLine("File: " + report.fileName)
                    appendLine("App: " + report.appLabel)
                    appendLine("Package: " + report.packageName)
                    appendLine("Version: " + (report.versionName ?: "Not reported"))
                    appendLine("Size: " + String.format(Locale.US, "%.2f MB", report.sizeBytes / (1024.0 * 1024.0)))
                    appendLine("SHA-256: " + report.sha256)
                    appendLine("Signer SHA-256: " + (report.signerSha256 ?: "Not available"))
                    appendLine("Requested permissions: " + report.requestedPermissions.size)
                    if (report.requestedPermissions.isNotEmpty()) {
                        appendLine()
                        appendLine("Permissions")
                        report.requestedPermissions.forEach { appendLine("• " + it.substringAfterLast('.')) }
                    }
                    appendLine()
                    appendLine("Evidence / reasons")
                    if (report.reasons.isEmpty()) appendLine("No elevated local heuristic signals found.")
                    else report.reasons.forEach { appendLine("• " + it) }
                }
            }
        }.start()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.08f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
