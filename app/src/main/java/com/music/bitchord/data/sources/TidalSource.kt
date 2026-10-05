package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonTrack
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
private const val LOCAL_BRIDGE_URL = "http://127.0.0.1:8766"

class TidalSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TIDAL
    override val displayName: String get() = config.label.ifBlank { SourceKind.TIDAL.label }

    private val effectiveBaseUrl: String
        get() = config.baseUrl.ifBlank { LOCAL_BRIDGE_URL }

    private val addonClient by lazy { AddonClient(effectiveBaseUrl) }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > 64
        },
    )

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isNotBlank()) {
            return@withContext addonClient.manifest().fold(
                onSuccess = { SourceHealth.Ok("Tidal · Connected") },
                onFailure = { SourceHealth.Unreachable(it.message ?: "Could not reach Tidal server") },
            )
        }
        // Test local bridge or Tidal direct
        val bridgeOk = addonClient.probeSearch().isSuccess
        if (bridgeOk) {
            SourceHealth.Ok("Tidal · Bridge active")
        } else {
            SourceHealth.Ok("Tidal · Ready")
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

            // 1. If configured with server or local bridge is running, search via addon protocol
            val bridgeResults = addonClient.search(query, AddonClient.TIER_LOSSLESS).getOrNull()
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

            // 2. Direct Tidal search API fallback
            try {
                val url = "https://tidal.com/v1/search/tracks"
                    .toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("query", query)
                    ?.addQueryParameter("limit", limit.coerceAtMost(15).toString())
                    ?.addQueryParameter("countryCode", "IN")
                    ?.addQueryParameter("locale", "en_IN")
                    ?.addQueryParameter("deviceType", "BROWSER")
                    ?.build() ?: return@withContext emptyList()

                val request = Request.Builder()
                    .url(url)
                    .header("x-tidal-token", TIDAL_TOKEN)
                    .header("Accept", "application/json")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
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
                    TrackLog.d(TAG, "  ✓ Tidal (direct API) returned ${result.size} tracks")
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
            val streamResult = addonClient.stream(trackId, AddonClient.TIER_LOSSLESS).getOrNull()
            val url = streamResult?.url?.ifBlank { null }
                ?: rows[trackId]?.streamURL?.ifBlank { null }
            if (url != null) {
                TrackLog.d(TAG, "  ✓ Tidal stream URL: ${url.take(80)}")
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
            TrackLog.w(TAG, "  ✗ Tidal had no stream URL for $trackId")
            null
        }
}
