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
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    const val OFFICIAL_REPO = "Rishav-sy/BitChord"

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
        val channel: String = "Official",
        val repo: String = OFFICIAL_REPO,
    )

    data class RepoReleaseMeta(
        val repo: String,
        val isUpstream: Boolean,
        val tagName: String,
        val name: String?,
        val publishedAtIso: String?,
        val daysAgo: Long?,
        val releaseUrl: String,
        val apkUrl: String? = null,
        val notes: String? = null,
        val isDraft: Boolean = false,
        val isPrerelease: Boolean = false,
    )

    data class ReleaseComparison(
        val upstream: RepoReleaseMeta?,
        val fork: RepoReleaseMeta?,
        val upstreamError: String? = null,
        val forkError: String? = null,
        val isChecking: Boolean = false,
    ) {
        fun summary(): String {
            val uDays = upstream?.daysAgo
            val fDays = fork?.daysAgo
            return when {
                uDays != null && fDays != null -> {
                    when {
                        uDays < fDays -> "Upstream is ${fDays - uDays}d newer (${formatDays(uDays)} vs ${formatDays(fDays)})"
                        fDays < uDays -> "Fork is ${uDays - fDays}d newer (${formatDays(fDays)} vs ${formatDays(uDays)})"
                        else -> "Both released on the same day (${formatDays(uDays)})"
                    }
                }
                uDays != null -> "Upstream released ${formatDays(uDays)}"
                fDays != null -> "Fork released ${formatDays(fDays)}"
                upstreamError != null && forkError != null -> "Failed to load releases"
                upstreamError != null -> "Upstream: $upstreamError"
                forkError != null -> "Fork: $forkError"
                else -> "Tap to compare upstream & fork releases"
            }
        }

        companion object {
            fun formatDays(days: Long): String = when (days) {
                0L -> "today"
                1L -> "1 day ago"
                else -> "$days days ago"
            }
        }
    }

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

    private val _comparison = MutableStateFlow<ReleaseComparison?>(null)
    val comparison = _comparison.asStateFlow()

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
        refreshComparison()
    }

    suspend fun checkManual(): CheckStatus = withContext(Dispatchers.IO) {
        _checkStatus.value = CheckStatus.Checking
        try {
            val update = resolveUpdate()
            refreshComparison()
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

    private fun calculateDaysAgo(isoDate: String?): Long? {
        if (isoDate.isNullOrBlank()) return null
        return runCatching {
            val instant = Instant.parse(isoDate)
            val now = Instant.now()
            ChronoUnit.DAYS.between(instant, now).coerceAtLeast(0)
        }.getOrNull()
    }

    suspend fun fetchRepoRelease(repo: String, isUpstream: Boolean): RepoReleaseMeta? = withContext(Dispatchers.IO) {
        val token = if (!isUpstream) AppSettings.githubUpdateToken.value.trim() else ""
        val endpoints = listOf(
            "https://api.github.com/repos/$repo/releases/latest",
            "https://api.github.com/repos/$repo/releases?per_page=1"
        )
        for (url in endpoints) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "BitChord-Android")
            if (token.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            val responseBody = runCatching {
                Http.client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body.string()
                }
            }.getOrNull() ?: continue

            val releaseObj = runCatching {
                val element = json.parseToJsonElement(responseBody)
                if (element is JsonObject) element
                else (element as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            }.getOrNull() ?: continue

            val tag = releaseObj["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = releaseObj["name"]?.jsonPrimitive?.contentOrNull
            val htmlUrl = releaseObj["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$repo/releases"
            val publishedAt = releaseObj["published_at"]?.jsonPrimitive?.contentOrNull
                ?: releaseObj["created_at"]?.jsonPrimitive?.contentOrNull
            val apkUrl = apkAssetUrl(releaseObj, isPrivateWithToken = token.isNotBlank())
            val notes = releaseObj["body"]?.jsonPrimitive?.contentOrNull
            val isDraft = releaseObj["draft"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val isPrerelease = releaseObj["prerelease"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

            return@withContext RepoReleaseMeta(
                repo = repo,
                isUpstream = isUpstream,
                tagName = tag,
                name = name,
                publishedAtIso = publishedAt,
                daysAgo = calculateDaysAgo(publishedAt),
                releaseUrl = htmlUrl,
                apkUrl = apkUrl,
                notes = notes,
                isDraft = isDraft,
                isPrerelease = isPrerelease,
            )
        }
        null
    }

    suspend fun refreshComparison(): ReleaseComparison = withContext(Dispatchers.IO) {
        _comparison.value = _comparison.value?.copy(isChecking = true) ?: ReleaseComparison(null, null, isChecking = true)
        val customRepo = AppSettings.customUpdateRepo.value.ifBlank { OFFICIAL_REPO }

        var fMeta: RepoReleaseMeta? = null
        var fErr: String? = null
        try {
            fMeta = fetchRepoRelease(customRepo, isUpstream = false)
            if (fMeta == null) {
                fErr = if (AppSettings.githubUpdateToken.value.isBlank()) {
                    "Repo not found or private (add GitHub Token)"
                } else {
                    "No release found"
                }
            }
        } catch (e: Exception) {
            fErr = e.message ?: "Failed to fetch fork"
        }

        val comp = ReleaseComparison(
            upstream = null,
            fork = fMeta,
            upstreamError = null,
            forkError = fErr,
            isChecking = false,
        )
        _comparison.value = comp
        comp
    }

    private suspend fun resolveUpdate(): UpdateInfo? {
        val customRepo = AppSettings.customUpdateRepo.value.ifBlank { OFFICIAL_REPO }

        val forkMeta = fetchRepoRelease(customRepo, isUpstream = false)
            ?: fetchRepoRelease(OFFICIAL_REPO, isUpstream = false)

        return forkMeta?.takeIf { isNewer(it.tagName.removePrefix("v"), BuildConfig.VERSION_NAME) }?.let {
            UpdateInfo(
                version = it.tagName.removePrefix("v"),
                releaseUrl = it.releaseUrl,
                apkUrl = it.apkUrl,
                notes = it.notes,
                channel = "Release",
                repo = customRepo,
            )
        }
    }

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
     * One release carries the phone app and the TV app, each as a universal
     * APK plus per-ABI splits:
     *
     *     BitChord-v1.8.apk     BitChord-v1.8_arm64-v8a.apk     ...
     *     BitChordTV-v1.8.apk   BitChordTV-v1.8_arm64-v8a.apk   ...
     *
     * so this app's APKs are the ones named with its own
     * [BuildConfig.UPDATE_APK_PREFIX] — "BitChord-" never matches a TV file,
     * since the prefix ends at the hyphen. The universal build (no `_ABI`
     * suffix) is taken first, as it installs anywhere; failing that, the split
     * for this device's ABI. A release with neither leaves
     * [UpdateInfo.apkUrl] null and the UI falls back to opening the releases
     * page.
     */
    private fun apkAssetUrl(release: JsonObject, isPrivateWithToken: Boolean = false): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = if (isPrivateWithToken) {
                    asset["url"]?.jsonPrimitive?.contentOrNull ?: asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                } else {
                    asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                } ?: return@mapNotNull null

                val notTv = !name.contains("TV", ignoreCase = true) && !name.contains("wear", ignoreCase = true)
                val isApk = name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                val prefixMatches = BuildConfig.UPDATE_APK_PREFIX.isEmpty() ||
                    name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) ||
                    name.startsWith("app-", ignoreCase = true) ||
                    name.startsWith("bitchord", ignoreCase = true)

                if (isApk && notTv && prefixMatches) {
                    name.removeSuffix(".apk").removeSuffix(".APK") to url
                } else null
            }
            .orEmpty()

        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            apks.firstOrNull { (name, _) -> name.contains(abi, ignoreCase = true) }?.second
        }
            ?: apks.firstOrNull { (name, _) ->
                name.contains("universal", ignoreCase = true) ||
                    ('_' !in name && !name.contains("arm", ignoreCase = true) && !name.contains("x86", ignoreCase = true))
            }?.second
            ?: apks.firstOrNull()?.second
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
     * Some TV builds have no such settings screen; there the installer is
     * launched anyway and shows its own "not allowed" prompt with a way in.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return
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

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-beta2"). */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build (see the debug build type's
     * `versionNameSuffix` in app/build.gradle.kts) is treated as older than a
     * plain release of the same numbers, since the beta by definition predates
     * the tag it was testing toward. Without this, a beta and the release it
     * matches compare equal and testers never get nudged onto the real build.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
