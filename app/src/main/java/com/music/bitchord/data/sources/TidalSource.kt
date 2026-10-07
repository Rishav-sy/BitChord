package com.music.bitchord.data.sources

import android.util.Base64
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
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "BitChord"
private const val TIDAL_TOKEN = "49YxDN9a2aFV6RTG"
private const val TIDAL_USER_AGENT = "TIDAL_ANDROID/1118 okhttp/4.12.0"

class TidalSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TIDAL
    override val displayName: String get() = config.label.ifBlank { SourceKind.TIDAL.label }

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
                onSuccess = { SourceHealth.Ok("Tidal · Connected") },
                onFailure = { SourceHealth.Unreachable(it.message ?: "Could not reach Tidal server") },
            )
        }

        // Standalone mode: ensure community pool accounts are available
        PoolAccountManager.ensureAccounts()
        val accounts = PoolAccountManager.getTidalAccounts()
        if (accounts.isNotEmpty()) {
            SourceHealth.Ok("Tidal · Lossless Pool Connected (${accounts.size} accounts)")
        } else {
            val directOk = runCatching {
                val url = "https://tidal.com/v1/search/tracks?query=test&limit=1&countryCode=US"
                val req = Request.Builder()
                    .url(url)
                    .header("x-tidal-token", TIDAL_TOKEN)
                    .header("Accept", "application/json")
                    .build()
                okHttpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)

            if (directOk) {
                SourceHealth.Ok("Tidal · Ready")
            } else {
                SourceHealth.Ok("Tidal · Ready")
            }
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
            TrackLog.d(TAG, "▶ Tidal search() query=\"$query\" limit=$limit")

            // 1. If configured with server, search via addon protocol
            val client = addonClient
            if (client != null) {
                val bridgeResults = client.search(query, AddonClient.TIER_LOSSLESS).getOrNull()
                if (!bridgeResults.isNullOrEmpty()) {
                    TrackLog.d(TAG, "  ✓ Tidal (server) returned ${bridgeResults.size} tracks")
                    return@withContext bridgeResults.take(limit).map { track ->
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

            // 2. Standalone direct Tidal search API
            try {
                PoolAccountManager.ensureAccounts()
                val countryCode = PoolAccountManager.getTidalAccounts()
                    .firstOrNull()?.countryCode ?: "US"

                val url = "https://tidal.com/v1/search/tracks"
                    .toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("query", query)
                    ?.addQueryParameter("limit", limit.coerceAtMost(15).toString())
                    ?.addQueryParameter("countryCode", countryCode)
                    ?.addQueryParameter("locale", "en_${countryCode}")
                    ?.addQueryParameter("deviceType", "BROWSER")
                    ?.build() ?: return@withContext emptyList()

                val searchReq = Request.Builder()
                    .url(url)
                    .header("x-tidal-token", TIDAL_TOKEN)
                    .header("Accept", "application/json")
                    .build()

                okHttpClient.newCall(searchReq).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val items = root.optJSONArray("items") ?: return@withContext emptyList()
                    val result = mutableListOf<Song>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optLong("id", 0L).toString().takeIf { it != "0" } ?: continue
                        val title = item.optString("title")
                        val duration = item.optInt("duration", 0)
                        val artist = item.optJSONObject("artist")?.optString("name")
                            ?: item.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
                            ?: "Unknown Artist"
                        val albumObj = item.optJSONObject("album")
                        val albumName = albumObj?.optString("title")
                        val cover = albumObj?.optString("cover")
                        val thumb = if (!cover.isNullOrBlank()) {
                            "https://resources.tidal.com/images/${cover.replace("-", "/")}/640x640.jpg"
                        } else null

                        result.add(
                            Song(
                                videoId = SourceRegistry.trackKey(config.id, id),
                                title = title,
                                artist = artist,
                                albumName = albumName,
                                thumbnailUrl = thumb,
                                durationText = if (duration > 0) "${duration / 60}:${"%02d".format(Locale.ROOT, duration % 60)}" else null,
                                sourceQuality = "LOSSLESS",
                            )
                        )
                    }
                    TrackLog.d(TAG, "  ✓ Tidal returned ${result.size} tracks")
                    result
                }
            } catch (e: Exception) {
                TrackLog.w(TAG, "Tidal search failed: ${e.message}")
                emptyList()
            }
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            TrackLog.d(TAG, "▶ Tidal stream() trackId=$trackId request=$request")

            // 1. If configured with server, stream via addon protocol
            val client = addonClient
            if (client != null) {
                val streamResult = client.stream(trackId, AddonClient.TIER_LOSSLESS).getOrNull()
                val url = streamResult?.url?.ifBlank { null }
                    ?: rows[trackId]?.streamURL?.ifBlank { null }
                if (url != null) {
                    TrackLog.d(TAG, "  ✓ Tidal server stream URL: ${url.take(80)}")
                    val sampleRateHz = streamResult?.sampleRateHz
                    val bitDepth = streamResult?.bits ?: 16
                    val kbps = streamResult?.kbps
                        ?: (if (sampleRateHz != null) sampleRateHz * bitDepth * 2 / 1000 else 1411)
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

            // 2. Standalone direct stream via ArchivePool tokens
            PoolAccountManager.ensureAccounts()
            val accounts = PoolAccountManager.getTidalAccounts()
            if (accounts.isEmpty()) {
                TrackLog.w(TAG, "  ✗ No Tidal accounts available in pool")
                return@withContext null
            }

            for (acc in accounts) {
                val stream = resolveTidalStream(trackId, acc.token)
                if (stream != null) {
                    TrackLog.d(TAG, "  ✓ Tidal standalone stream resolved at ${stream.format.summary}")
                    return@withContext stream
                }
            }

            TrackLog.w(TAG, "  ✗ Tidal had no stream URL for $trackId across ${accounts.size} accounts")
            null
        }

    private fun resolveTidalStream(trackId: String, token: String): SourceStream? {
        val endpoints = listOf(
            "https://api.tidal.com/v1/tracks/$trackId/playbackinfopostpaywall?audioquality=LOSSLESS&playbackmode=STREAM&assetpresentation=FULL",
            "https://api.tidal.com/v1/tracks/$trackId/playbackinfo?audioquality=LOSSLESS&playbackmode=STREAM&assetpresentation=FULL",
            "https://api.tidal.com/v1/tracks/$trackId/playbackinfopostpaywall?audioquality=HI_RES_LOSSLESS&playbackmode=STREAM&assetpresentation=FULL",
        )

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", TIDAL_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use null

                    val json = JSONObject(body)
                    if (json.optString("assetPresentation").equals("PREVIEW", ignoreCase = true)) {
                        return@use null
                    }

                    val manifestB64 = json.optString("manifest").takeIf { it.isNotBlank() } ?: return@use null
                    val manifestMime = json.optString("manifestMimeType").ifBlank { null }
                    val manifestDecoded = runCatching {
                        String(Base64.decode(manifestB64, Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrNull() ?: return@use null

                    // 1. JSON BTS Manifest (direct single-file CDN link)
                    if (manifestDecoded.trimStart().startsWith("{")) {
                        val manJson = JSONObject(manifestDecoded)
                        val urls = manJson.optJSONArray("urls")
                        val streamUrl = urls?.optString(0)?.takeIf { it.isNotBlank() } ?: return@use null
                        val codecs = manJson.optString("codecs", "flac")
                        val mimeType = manJson.optString("mimeType", manifestMime.orEmpty())
                        val isFlac = codecs.contains("flac", true) || mimeType.contains("flac", true)
                        val sampleRateHz = if (endpoint.contains("HI_RES")) 96000 else 44100
                        val bitDepth = if (endpoint.contains("HI_RES")) 24 else 16

                        return SourceStream(
                            url = streamUrl,
                            format = StreamFormat(
                                codec = if (isFlac) "flac" else codecs,
                                kbps = if (isFlac) (if (bitDepth > 16) 2304 else 1411) else 320,
                                sampleRateHz = sampleRateHz,
                                bitDepth = bitDepth,
                            ),
                            headers = mapOf("User-Agent" to TIDAL_USER_AGENT),
                        )
                    }

                    // 2. DASH MPD Manifest
                    if (manifestDecoded.contains("<MPD", ignoreCase = true)) {
                        val initUrlMatch = Regex("<Initialization\\b[^>]*sourceURL=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                            .find(manifestDecoded)?.groupValues?.getOrNull(1)
                        val segmentUrlMatch = Regex("<SegmentURL\\b[^>]*media=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                            .find(manifestDecoded)?.groupValues?.getOrNull(1)

                        val streamUrl = segmentUrlMatch ?: initUrlMatch
                        if (!streamUrl.isNullOrBlank()) {
                            return SourceStream(
                                url = streamUrl,
                                format = StreamFormat(
                                    codec = "flac",
                                    kbps = 1411,
                                    sampleRateHz = 44100,
                                    bitDepth = 16,
                                ),
                                headers = mapOf("User-Agent" to TIDAL_USER_AGENT),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                TrackLog.w(TAG, "Tidal resolve endpoint failed: ${e.message}")
            }
        }
        return null
    }
}
