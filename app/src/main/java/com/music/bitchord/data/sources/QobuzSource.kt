package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

private const val TAG = "BitChord"
private const val LOCAL_BRIDGE_URL = "http://192.168.29.13:8766"

class QobuzSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.QOBUZ
    override val displayName: String get() = config.label.ifBlank { SourceKind.QOBUZ.label }

    private val effectiveBaseUrl: String
        get() = config.baseUrl.ifBlank { LOCAL_BRIDGE_URL }

    private val addonClient by lazy { AddonClient(effectiveBaseUrl) }

    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > 64
        },
    )

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isNotBlank()) {
            return@withContext addonClient.manifest().fold(
                onSuccess = { SourceHealth.Ok("Qobuz · Connected") },
                onFailure = { SourceHealth.Unreachable(it.message ?: "Could not reach Qobuz server") },
            )
        }
        val bridgeResult = addonClient.probeSearch()
        if (bridgeResult.isSuccess) {
            SourceHealth.Ok("Qobuz · Bridge active")
        } else {
            SourceHealth.Unreachable(bridgeResult.exceptionOrNull()?.message ?: "Local bridge unreachable (192.168.29.13:8766)")
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

            val tracks = addonClient.search(query, AddonClient.TIER_LOSSLESS).getOrNull()
            if (tracks.isNullOrEmpty()) return@withContext emptyList()

            TrackLog.d(TAG, "  ✓ Qobuz returned ${tracks.size} tracks")
            tracks.take(limit).map { track ->
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

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            TrackLog.d(TAG, "▶ Qobuz stream() trackId=$trackId request=$request")
            val streamResult = addonClient.stream(trackId, AddonClient.TIER_LOSSLESS).getOrNull()
            val url = streamResult?.url?.ifBlank { null }
                ?: rows[trackId]?.streamURL?.ifBlank { null }
            if (url != null) {
                TrackLog.d(TAG, "  ✓ Qobuz stream URL: ${url.take(80)}")
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
            TrackLog.w(TAG, "  ✗ Qobuz had no stream URL for $trackId")
            null
        }
}
