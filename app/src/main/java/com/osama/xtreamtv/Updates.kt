package com.osama.xtreamtv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from this app's GitHub releases (tag "build-N", N = versionCode).
 * Download the APK, then hand it to the system installer; the user confirms there.
 */
object Updates {

    private const val LATEST = "https://api.github.com/repos/osamaaabualwafa/xtream-tv-player/releases/latest"

    /** [version]: the name people see ("1.3.0"), read from the release title "Version 1.3.0 (build 31)". */
    data class Release(val build: Int, val apkUrl: String, val version: String?)

    /** What people are shown: the version name, never the build number. */
    private val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checked once per app session automatically; the Home button checks on demand. */
    var checkedThisSession = false

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    val version = Regex("""Version\s+([0-9][0-9A-Za-z.\-]*)""").find(json.optString("name"))?.groupValues?.get(1)
                    return@withContext Release(build, a.optString("browser_download_url"), version)
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Background check from Home: only speaks up when there is something newer. */
    fun checkQuietly(activity: Activity, scope: LifecycleCoroutineScope) {
        // Test builds (build 1) would always be offered the published release; only "Check for updates" asks.
        if (BuildConfig.DEBUG || checkedThisSession) return
        checkedThisSession = true
        scope.launch {
            val release = try { latest() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (release != null && release.build > currentBuild && !activity.isFinishing) offer(activity, scope, release)
        }
    }

    /** "Check for updates" button: always answers. */
    fun checkNow(activity: Activity, scope: LifecycleCoroutineScope) {
        showToast(activity, "Checking for updates…", Toast.LENGTH_SHORT)
        scope.launch {
            val release = try { latest() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            when {
                release == null -> {
                    Diag.log("update", "check failed")
                    showToast(activity, "Couldn't check for updates. Try again later.", Toast.LENGTH_LONG)
                }
                release.build > currentBuild -> {
                    Diag.log("update", "build ${release.build} available (this is build $currentBuild)")
                    offer(activity, scope, release)
                }
                else -> showToast(activity, "You have the latest version ($currentVersion)", Toast.LENGTH_LONG)
            }
        }
    }

    /** "Version 1.3.0 is ready (you have version 1.2.0)"; when the name didn't change, "An update to version 1.2.0 is ready." */
    private fun offerText(release: Release): String {
        val version = release.version
        return when {
            version == null -> "An update is ready (you have version $currentVersion)."
            version == currentVersion -> "An update to version $version is ready."
            else -> "Version $version is ready (you have version $currentVersion)."
        }
    }

    private fun offer(activity: Activity, scope: LifecycleCoroutineScope, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(offerText(release) + "\nYour playlists, favorites and progress are kept.")
            .setPositiveButton("Install") { _, _ -> download(activity, scope, release) }
            .setNegativeButton("Later", null)
            .show()
            // Highlight Install straight away, so OK on the remote installs.
            .getButton(AlertDialog.BUTTON_POSITIVE)?.let { it.post { it.requestFocus() } }
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, release: Release) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_progress, null)
        val bar = view.findViewById<ProgressBar>(R.id.dialog_progress)
        val label = view.findViewById<TextView>(R.id.dialog_progress_text)
        val dialog = AlertDialog.Builder(activity).setTitle("Downloading update").setView(view).setCancelable(false).show()
        scope.launch {
            try {
                val file = fetch(activity, release.apkUrl, release.build) { percent ->
                    bar.isIndeterminate = percent < 0
                    if (percent >= 0) bar.progress = percent
                    label.text = if (percent >= 0) "$percent%" else "Starting…"
                }
                dialog.dismiss()
                Diag.log("update", "build ${release.build} downloaded, installing")
                install(activity, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                Diag.log("update", "download of build ${release.build} failed: ${e.message ?: "network error"}")
                showToast(activity, "Download failed: ${e.message ?: "network error"}", Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * Downloads the APK of [build], kept under its build number: pressing Install again for the same
     * build reuses it (a TV whose installer didn't open the first time downloaded it all twice).
     */
    private suspend fun fetch(activity: Activity, url: String, build: Int, onProgress: (Int) -> Unit): File {
        val dir = if (Build.VERSION.SDK_INT >= 24) File(activity.cacheDir, "updates") else activity.getExternalFilesDir(null)!!
        dir.mkdirs()
        val target = File(dir, "xtream-tv-player-$build.apk")
        // Older builds' files (and the old single name) are no longer needed.
        dir.listFiles()?.filter { it.name.endsWith(".apk") && it.name != target.name }?.forEach { it.delete() }
        if (target.length() > 0) {
            Diag.log("update", "build $build already downloaded, opening the installer again")
            withContext(Dispatchers.Main) { onProgress(100) }
            return target
        }
        val partial = File(dir, target.name + ".part")
        withContext(Dispatchers.Main) { onProgress(-1) }
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLength.toLong()
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            // Only a complete download takes the build's name (a cut one is never reused).
            if (!partial.renameTo(target)) throw IOException("couldn't save the update")
        }
        return target
    }

    private fun install(activity: Activity, apk: File) {
        // Android 8+: the user must allow this app to install updates (asked once).
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            showToast(activity, "Allow \"Install unknown apps\" for Xtream TV, then press Back and choose Install again", Toast.LENGTH_LONG)
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            checkedThisSession = false
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        } else {
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
            Diag.log("update", "installer opened (${apk.length() / 1024 / 1024} MB)")
            installerOpenedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Diag.log("update", "installer didn't open: ${e.javaClass.simpleName}")
            showToast(activity, "The TV's installer didn't open. Press Install again.", Toast.LENGTH_LONG)
        }
    }

    /** When the installer was last opened (diagnostics: back in the app still on the old build). */
    @Volatile private var installerOpenedAt = 0L

    /** Called when the app comes back to the front: an installer that closed without installing. */
    fun noteReturn() {
        val at = installerOpenedAt
        if (at == 0L) return
        installerOpenedAt = 0L
        Diag.log("update", "back in the app ${(System.currentTimeMillis() - at) / 1000} s after opening the installer, still build $currentBuild (not installed)")
    }
}
