package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonTrack
import com.music.bitchord.data.sources.pool.PoolAccountManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "BitChord"
private const val QOBUZ_USER_AGENT = "BitChord/1.7.1"

class QobuzSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.QOBUZ
    override val displayName: String get() = config.label.ifBlank { SourceKind.QOBUZ.label }

    private val addonClient: AddonClient?
        get() = config.baseUrl.trim().takeIf { it.isNotBlank() }?.let { AddonClient(it) }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > 64
        },
    )

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        val client = addonClient
        if (client != null) {
            return@withContext client.manifest().fold(
                onSuccess = { SourceHealth.Ok("Qobuz · Connected") },
                onFailure = { SourceHealth.Unreachable(it.message ?: "Could not reach Qobuz server") },
            )
        }

        PoolAccountManager.ensureAccounts()
        val accounts = PoolAccountManager.getQobuzAccounts()
        val apis = PoolAccountManager.getQobuzApis()
        if (accounts.isNotEmpty() || apis.isNotEmpty()) {
            SourceHealth.Ok("Qobuz · Hi-Res Pool Connected (${accounts.size} accounts, ${apis.size} APIs)")
        } else {
            SourceHealth.Ok("Qobuz · Ready")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            TrackLog.d(TAG, "▶ Qobuz search() query=\"$query\" limit=$limit")

            // 1. If configured with server, search via addon protocol
            val client = addonClient
            if (client != null) {
                val tracks = client.search(query, AddonClient.TIER_LOSSLESS).getOrNull()
                if (!tracks.isNullOrEmpty()) {
                    TrackLog.d(TAG, "  ✓ Qobuz (server) returned ${tracks.size} tracks")
                    return@withContext tracks.take(limit).map { track ->
                        rows[track.id] = track
                        Song(
                            videoId = SourceRegistry.trackKey(config.id, track.id),
                            title = track.title,
                            artist = track.artist,
                            albumName = track.album.ifBlank { null },
                            thumbnailUrl = track.artwork,
                            durationText = track.durationSec?.let {
                                "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
                            },
                            sourceQuality = "LOSSLESS",
                        )
                    }
                }
            }

            // 2. Standalone direct Qobuz catalog search
            PoolAccountManager.ensureAccounts()
            val accounts = PoolAccountManager.getQobuzAccounts()
            val acc = accounts.firstOrNull() ?: return@withContext emptyList()

            try {
                val url = "https://www.qobuz.com/api.json/0.2/track/search"
                    .toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("query", query)
                    ?.addQueryParameter("limit", limit.coerceAtMost(15).toString())
                    ?.addQueryParameter("app_id", acc.appId)
                    ?.build() ?: return@withContext emptyList()

                val searchReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", QOBUZ_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("X-App-Id", acc.appId)
                    .header("X-User-Auth-Token", acc.token)
                    .build()

                okHttpClient.newCall(searchReq).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext emptyList()

                    val root = JSONObject(body)
                    val tracksObj = root.optJSONObject("tracks")
                    val items = tracksObj?.optJSONArray("items") ?: return@withContext emptyList()
                    val result = mutableListOf<Song>()

                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optLong("id", 0L).toString().takeIf { it != "0" } ?: continue
                        val title = item.optString("title")
                        val duration = item.optInt("duration", 0)
                        val artist = item.optJSONObject("performer")?.optString("name")
                            ?: item.optJSONObject("artist")?.optString("name")
                            ?: "Unknown Artist"
                        val albumObj = item.optJSONObject("album")
                        val albumName = albumObj?.optString("title")
                        val cover = albumObj?.optJSONObject("image")?.optString("large")
                            ?: albumObj?.optJSONObject("image")?.optString("small")

                        result.add(
                            Song(
                                videoId = SourceRegistry.trackKey(config.id, id),
                                title = title,
                                artist = artist,
                                albumName = albumName,
                                thumbnailUrl = cover,
                                durationText = if (duration > 0) "${duration / 60}:${"%02d".format(Locale.ROOT, duration % 60)}" else null,
                                sourceQuality = "LOSSLESS",
                            )
                        )
                    }
                    TrackLog.d(TAG, "  ✓ Qobuz returned ${result.size} tracks")
                    result
                }
            } catch (e: Exception) {
                TrackLog.w(TAG, "Qobuz search failed: ${e.message}")
                emptyList()
            }
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            TrackLog.d(TAG, "▶ Qobuz stream() trackId=$trackId request=$request")

            // 1. If configured with server, stream via addon protocol
            val client = addonClient
            if (client != null) {
                val streamResult = client.stream(trackId, AddonClient.TIER_LOSSLESS).getOrNull()
                val url = streamResult?.url?.ifBlank { null }
                    ?: rows[trackId]?.streamURL?.ifBlank { null }
                if (url != null) {
                    TrackLog.d(TAG, "  ✓ Qobuz server stream URL: ${url.take(80)}")
                    val sampleRateHz = streamResult?.sampleRateHz
                    val bitDepth = streamResult?.bits ?: 24
                    val kbps = streamResult?.kbps
                        ?: (if (sampleRateHz != null) sampleRateHz * bitDepth * 2 / 1000 else 2304)
                    return@withContext SourceStream(
                        url = url,
                        format = StreamFormat(
                            codec = streamResult?.statedCodec ?: "flac",
                            kbps = kbps,
                            sampleRateHz = sampleRateHz,
                            bitDepth = bitDepth,
                        ),
                    )
                }
            }

            // 2. Standalone direct Qobuz stream via MD5 signature
            PoolAccountManager.ensureAccounts()
            val accounts = PoolAccountManager.getQobuzAccounts()

            for (acc in accounts) {
                for (fmt in listOf(27, 7, 6)) {
                    val stream = resolveQobuzDirect(trackId, fmt, acc)
                    if (stream != null) {
                        TrackLog.d(TAG, "  ✓ Qobuz standalone stream resolved at ${stream.format.summary}")
                        return@withContext stream
                    }
                }
            }

            // 3. Fallback to Qobuz proxy APIs in pool
            val apis = PoolAccountManager.getQobuzApis()
            for (api in apis) {
                for (fmt in listOf(27, 7, 6)) {
                    val stream = resolveQobuzProxy(trackId, api.baseUrl, fmt)
                    if (stream != null) {
                        TrackLog.d(TAG, "  ✓ Qobuz proxy stream resolved from ${api.baseUrl} at ${stream.format.summary}")
                        return@withContext stream
                    }
                }
            }

            TrackLog.w(TAG, "  ✗ Qobuz had no stream URL for $trackId")
            null
        }

    private fun resolveQobuzDirect(trackId: String, formatId: Int, acc: PoolAccountManager.QobuzAccount): SourceStream? {
        return try {
            val ts = System.currentTimeMillis() / 1000L
            val sigRaw = "trackgetFileUrlformat_id${formatId}intentstreamtrack_id${trackId}${ts}${acc.appSecret}"
            val sig = md5(sigRaw)

            val url = "https://www.qobuz.com/api.json/0.2/track/getFileUrl"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("request_ts", ts.toString())
                ?.addQueryParameter("request_sig", sig)
                ?.addQueryParameter("track_id", trackId)
                ?.addQueryParameter("format_id", formatId.toString())
                ?.addQueryParameter("intent", "stream")
                ?.addQueryParameter("app_id", acc.appId)
                ?.build() ?: return null

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", QOBUZ_USER_AGENT)
                .header("Accept", "application/json")
                .header("X-App-Id", acc.appId)
                .header("X-User-Auth-Token", acc.token)
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null

                val json = JSONObject(body)
                val streamUrl = json.optString("url").takeIf { it.startsWith("http") } ?: return null
                if (json.optBoolean("sample", false)) return null

                val mime = json.optString("mime_type")
                val isFlac = mime.contains("flac", true) || streamUrl.contains(".flac", true)
                val sampleRateHz = if (formatId == 27) 192000 else if (formatId == 7) 96000 else 44100
                val bitDepth = if (formatId > 6) 24 else 16

                SourceStream(
                    url = streamUrl,
                    format = StreamFormat(
                        codec = if (isFlac) "flac" else "mp3",
                        kbps = if (formatId > 6) 2304 else if (isFlac) 1411 else 320,
                        sampleRateHz = sampleRateHz,
                        bitDepth = bitDepth,
                    ),
                    headers = mapOf("User-Agent" to QOBUZ_USER_AGENT),
                )
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "Qobuz direct resolve error: ${e.message}")
            null
        }
    }

    private fun resolveQobuzProxy(trackId: String, baseUrl: String, formatId: Int = 27): SourceStream? {
        return try {
            val url = "$baseUrl/api/download-music?track_id=$trackId&quality=$formatId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", QOBUZ_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null

                val json = JSONObject(body)
                val streamUrl = json.optString("url").takeIf { it.startsWith("http") }
                    ?: json.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") }
                    ?: return null

                val sampleRateHz = if (formatId == 27) 192000 else if (formatId == 7) 96000 else 44100
                val bitDepth = if (formatId > 6) 24 else 16
                SourceStream(
                    url = streamUrl,
                    format = StreamFormat(
                        codec = "flac",
                        kbps = if (bitDepth > 16) 2304 else 1411,
                        sampleRateHz = sampleRateHz,
                        bitDepth = bitDepth,
                    ),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
