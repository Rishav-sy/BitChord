package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.UpdateChannel

/**
 * BitChord ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the configured repo's "latest release" (Official, Fork, or Both)
 * and compares its tag against the running build.
 *
 * The update itself is also handled here: the release's `.apk` asset is
 * downloaded into the app's cache and handed to the system package installer,
 * so the whole round trip stays inside the app instead of bouncing out to a
 * browser.
 */
object AppUpdateChecker {

    const val OFFICIAL_REPO = "kushagrasinghx/BitChord"

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
        val channel: String = "Official",
        val repo: String = OFFICIAL_REPO,
    )

    sealed interface CheckStatus {
        data object Idle : CheckStatus
        data object Checking : CheckStatus
        data class UpToDate(val version: String, val channel: String) : CheckStatus
        data class Available(val info: UpdateInfo) : CheckStatus
        data class Failed(val message: String) : CheckStatus
    }

    private const val CACHE_SUBDIR = "updates"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    private val _checkStatus = MutableStateFlow<CheckStatus>(CheckStatus.Idle)
    val checkStatus = _checkStatus.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    suspend fun check() = withContext(Dispatchers.IO) {
        val update = resolveUpdate()
        if (update != null) {
            _available.value = update
        }
    }

    suspend fun checkManual(): CheckStatus = withContext(Dispatchers.IO) {
        _checkStatus.value = CheckStatus.Checking
        try {
            val update = resolveUpdate()
            if (update != null) {
                _available.value = update
                val status = CheckStatus.Available(update)
                _checkStatus.value = status
                status
            } else {
                val channel = AppSettings.updateChannel.value.label
                val status = CheckStatus.UpToDate(BuildConfig.VERSION_NAME, channel)
                _checkStatus.value = status
                status
            }
        } catch (e: Exception) {
            val status = CheckStatus.Failed(e.message ?: "Failed to check for updates")
            _checkStatus.value = status
            status
        }
    }

    private suspend fun resolveUpdate(): UpdateInfo? {
        val channel = AppSettings.updateChannel.value
        val customRepo = AppSettings.customUpdateRepo.value.ifBlank { "Rishav-sy/BitChord" }

        return when (channel) {
            UpdateChannel.OFFICIAL -> fetchRelease(OFFICIAL_REPO, "Official")
            UpdateChannel.FORK -> fetchRelease(customRepo, "My Fork")
            UpdateChannel.BOTH -> {
                val official = fetchRelease(OFFICIAL_REPO, "Official")
                val fork = fetchRelease(customRepo, "My Fork")
                when {
                    official == null -> fork
                    fork == null -> official
                    isNewer(fork.version, official.version) -> fork
                    else -> official
                }
            }
        }
    }

    private suspend fun fetchRelease(repo: String, channelName: String): UpdateInfo? = runCatching {
        val token = if (repo != OFFICIAL_REPO) AppSettings.githubUpdateToken.value.trim() else ""
        val url = "https://api.github.com/repos/$repo/releases/latest"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "BitChord-Android")
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val responseBody = Http.client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        } ?: return null

        val release = json.parseToJsonElement(responseBody) as? JsonObject ?: return null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val htmlUrl = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val apkUrl = apkAssetUrl(release, isPrivateWithToken = token.isNotBlank())
        val notes = release["body"]?.jsonPrimitive?.contentOrNull
        val latest = tag.removePrefix("v")
        if (isNewer(latest, BuildConfig.VERSION_NAME)) {
            UpdateInfo(
                version = latest,
                releaseUrl = htmlUrl,
                apkUrl = apkUrl,
                notes = notes,
                channel = channelName,
                repo = repo,
            )
        } else null
    }.getOrNull()

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * The release usually carries exactly one `.apk`; take its direct download
     * URL. A release without one (source-only draft, renamed asset) leaves
     * [UpdateInfo.apkUrl] null and the UI falls back to opening the releases
     * page as before.
     */
    private fun apkAssetUrl(release: JsonObject, isPrivateWithToken: Boolean = false): String? = runCatching {
        val asset = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { item ->
                item["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true &&
                    item["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            } ?: return null

        if (isPrivateWithToken) {
            asset["url"]?.jsonPrimitive?.contentOrNull ?: asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
        } else {
            asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "bitchord-${info.version}.apk")

            val requestBuilder = Request.Builder().url(url)
            if (info.repo != OFFICIAL_REPO && AppSettings.githubUpdateToken.value.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${AppSettings.githubUpdateToken.value.trim()}")
                requestBuilder.header("Accept", "application/octet-stream")
            }
            val request = requestBuilder.build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

    /** Numeric, dot-separated comparison — "1.10" outranks "1.9". */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
