package com.guardianlayer.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Local review of a user-selected APK. Heuristic scores are not malware verdicts. */
object GuardianApkScanner {
    data class Report(
        val fileName: String, val packageName: String, val appLabel: String,
        val versionName: String?, val sizeBytes: Long, val sha256: String,
        val signerSha256: String?, val requestedPermissions: List<String>,
        val score: Int, val knownThreat: Boolean, val classification: String, val reasons: List<String>
    )

    private const val MAX_APK_BYTES = 512L * 1024L * 1024L
    private val decoyExtensions = setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","rtf","jpg","jpeg","png","gif","webp","heic","svg","mp3","wav","m4a","mp4","mov","avi","mkv","zip","rar","7z","tar","gz")
    private val bidiControls = setOf('\u202A','\u202B','\u202C','\u202D','\u202E','\u2066','\u2067','\u2068','\u2069')

    fun scan(context: Context, uri: Uri): Report {
        val name = displayName(context, uri) ?: "selected.apk"
        val temp = File.createTempFile("guardian_selected_", ".apk", context.cacheDir)
        try {
            val (size, hash) = copyAndHash(context, uri, temp)
            val pm = context.packageManager
            val flags = PackageManager.GET_PERMISSIONS or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else {
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(temp.absolutePath, flags)
                ?: throw IllegalArgumentException("Android could not read this as an APK file.")
            val app = info.applicationInfo ?: throw IllegalArgumentException("APK application metadata is missing.")
            app.sourceDir = temp.absolutePath
            app.publicSourceDir = temp.absolutePath
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(info.packageName)
            val permissions = info.requestedPermissions.orEmpty().toSet()
            val reasons = mutableListOf<String>()
            var score = 0
            fun signal(points: Int, reason: String) { score += points; reasons += reason }
            val install = "android.permission.REQUEST_INSTALL_PACKAGES" in permissions
            val overlay = "android.permission.SYSTEM_ALERT_WINDOW" in permissions
            val readSms = "android.permission.READ_SMS" in permissions
            val sendSms = "android.permission.SEND_SMS" in permissions
            if (install) signal(24, "Can request installation of other packages")
            if (overlay) signal(18, "Requests draw-over-other-apps capability")
            if (readSms) signal(12, "Requests access to SMS content")
            if (sendSms) signal(16, "Requests ability to send SMS messages")
            if ("android.permission.RECEIVE_SMS" in permissions) signal(5, "Requests SMS receive capability")
            if (permissions.any { it == "android.permission.READ_CALL_LOG" || it == "android.permission.WRITE_CALL_LOG" }) signal(9, "Requests call-log access")
            if ("android.permission.RECORD_AUDIO" in permissions) signal(4, "Requests microphone access")
            if ("android.permission.CAMERA" in permissions) signal(3, "Requests camera access")
            if (permissions.any { it == "android.permission.ACCESS_FINE_LOCATION" || it == "android.permission.ACCESS_COARSE_LOCATION" }) signal(3, "Requests location access")
            if (app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) signal(12, "Application is marked debuggable")
            if (install && overlay) signal(20, "Combined signal: package installation plus overlay capability")
            if (readSms && sendSms) signal(15, "Combined signal: reads and sends SMS")
            val normalized = name.trim().lowercase()
            val parts = normalized.split('.')
            if (name.any { it in bidiControls }) signal(45, "Filename contains bidirectional text controls that can disguise its visible extension")
            if (normalized.endsWith(".apk") && parts.size >= 3 && parts[parts.lastIndex - 1] in decoyExtensions) {
                signal(35, "Potentially deceptive double extension: " + parts[parts.lastIndex - 1] + ".apk")
            } else if (!normalized.endsWith(".apk")) {
                signal(if (parts.lastOrNull() in decoyExtensions) 45 else 25, "APK content does not use the normal .apk filename extension")
            }
            val known = hash in loadThreatHashes(context)
            if (known) reasons.add(0, "SHA-256 exactly matches Guardian's bundled offline hash list")
            val finalScore = if (known) 100 else score.coerceIn(0, 100)
            val classification = when {
                known -> "KNOWN HASH MATCH — urgent review"
                finalScore >= 75 -> "High-priority review"
                finalScore >= 55 -> "Elevated review"
                finalScore >= 35 -> "Review recommended"
                else -> "No elevated local signal"
            }
            return Report(name, info.packageName, label, info.versionName, size, hash, signerSha256(info), permissions.sorted(), finalScore, known, classification, reasons.distinct())
        } finally { temp.delete() }
    }

    private fun copyAndHash(context: Context, uri: Uri, destination: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Guardian could not open the selected file.")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_APK_BYTES) { "The selected APK exceeds Guardian's 512 MB review limit." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        require(total > 0L) { "The selected file is empty." }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadThreatHashes(context: Context): Set<String> = runCatching {
        context.assets.open("threat_hashes_sha256.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.matches(Regex("^[0-9a-f]{64}$")) }.toSet()
        }
    }.getOrDefault(emptySet())

    private fun signerSha256(info: PackageInfo): String? {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull() ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()
        } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(":") { "%02X".format(it) }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()
}
