/*
 * Pixel Notes — update checker
 * Checks this app's GitHub releases for a newer build, downloads the APK and hands it to
 * Android's installer. GPL-3.0 License
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object PixelNotesUpdater {
    const val REPO = "3gfvgFWESAs44545/Pixel-Notes"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases"

    data class Release(
        val version: String,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
    )

    sealed interface CheckResult {
        data class UpdateAvailable(
            val release: Release,
        ) : CheckResult

        data class UpToDate(
            val latestVersion: String,
        ) : CheckResult

        data class Failed(
            val message: String,
        ) : CheckResult
    }

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** True for dev builds ("0.7-dev"), which are published as GitHub pre-releases. */
    val isDevBuild: Boolean get() = currentVersion.contains("-dev")

    /** "0.7-dev" -> "0.7 (dev)" for display. */
    fun displayVersion(version: String): String = version.replace("-dev", " (dev)")

    /**
     * Looks for a newer build. Stable only by default; with [includeDev], dev builds
     * (pre-releases) count too and the newest of either kind wins.
     */
    suspend fun check(includeDev: Boolean = false): CheckResult =
        withContext(Dispatchers.IO) {
            try {
                val url = if (includeDev) "$RELEASES_API?per_page=15" else LATEST_API
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext CheckResult.Failed("GitHub answered HTTP ${response.code}")
                    }
                    val body = response.body.string()
                    val candidates =
                        if (includeDev) {
                            val array = JSONArray(body)
                            (0 until array.length()).map { array.getJSONObject(it) }.filterNot { it.optBoolean("draft") }
                        } else {
                            listOf(JSONObject(body))
                        }
                    val releases = candidates.mapNotNull(::toRelease)
                    val latest =
                        releases.reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }
                            ?: return@withContext CheckResult.Failed("The latest release has no APK yet")
                    if (isNewer(latest.version, currentVersion)) {
                        CheckResult.UpdateAvailable(latest)
                    } else {
                        CheckResult.UpToDate(latest.version)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CheckResult.Failed(e.message ?: "Couldn't reach GitHub")
            }
        }

    private fun toRelease(json: JSONObject): Release? {
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty()) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return Release(version, asset.optString("browser_download_url"), asset.optLong("size"), json.optString("body"))
            }
        }
        return null
    }

    /**
     * Compares versions such as 0.12, 0.9 and 0.7-dev number by number (the build number is
     * unique, so a dev and a stable build never share one).
     */
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** True when Android lets this app open the installer (the "Install unknown apps" switch). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Downloads the APK, reporting progress from 0 to 1, and returns the file. */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "pixel_notes_update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "PixelNotes-${release.version}.apk")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    withContext(Dispatchers.Main) { onProgress(percent / 100f) }
                                }
                            }
                        }
                    }
                }
            }
            file
        }

    /** Opens Android's installer for a downloaded APK. Android asks the user to confirm. */
    fun install(
        context: Context,
        apk: File,
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apk)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
