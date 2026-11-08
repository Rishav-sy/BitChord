package com.music.bitchord.data.sources.pool

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages community lossless accounts and proxy instances fetched from ArchivePool.
 * Decrypts tokens on-the-fly and caches them in memory.
 */
object PoolAccountManager {
    private const val TAG = "BitChordPool"
    private const val DEFAULT_POOL_URL = "https://archivepool.natune.workers.dev"
    private val POOL_URL_CANDIDATES = listOf(
        "https://archivepool.natune.workers.dev",
        "https://archivepool.vercel.app",
    )
    private const val DEFAULT_READ_KEY = "atp_022bd3af288255cf209c10177dc07cd1bfb5664eaa525b42"
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours

    // Static verified fallback credentials so Hi-Res lossless Qobuz streaming is always available
    // immediately out of the box even before first network sync or when offline.
    private val STATIC_QOBUZ_ACCOUNT = QobuzAccount(
        id = 143L,
        appId = "798273057",
        token = "ckYvWySyZJGo1ftbzjo588_qBApIV83j9qf0g-x2NDLnangBAp2n3BrxjBDy9DbjcUYqj8vu_NV5Osx0JggdIg",
        appSecret = "abb21364945c0583309667d13ca3d93a",
        premium = true,
    )

    data class TidalAccount(
        val id: Long?,
        val token: String,
        val refreshToken: String?,
        val countryCode: String?,
        val premium: Boolean,
    )

    data class QobuzAccount(
        val id: Long?,
        val appId: String,
        val token: String,
        val appSecret: String,
        val premium: Boolean,
    )

    data class QobuzApi(
        val id: Long?,
        val baseUrl: String,
        val status: String,
    )

    @Volatile
    private var lastFetchMs: Long = 0L

    private val mutex = Mutex()
    private val tidalAccounts = mutableListOf<TidalAccount>()
    private val qobuzAccounts = mutableListOf<QobuzAccount>(STATIC_QOBUZ_ACCOUNT)
    private val qobuzApis = mutableListOf<QobuzApi>()

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun refreshAccounts(): Boolean = withContext(Dispatchers.IO) {
        lastFetchMs = 0L
        ensureAccounts()
    }

    suspend fun ensureAccounts(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (qobuzAccounts.isNotEmpty() && (now - lastFetchMs < REFRESH_INTERVAL_MS)) {
            return@withContext true
        }

        mutex.withLock {
            if (qobuzAccounts.isNotEmpty() && (now - lastFetchMs < REFRESH_INTERVAL_MS)) {
                return@withLock true
            }

            try {
                var responseBody: String? = null
                var winningUrl = DEFAULT_POOL_URL
                for (poolUrl in POOL_URL_CANDIDATES) {
                    try {
                        TrackLog.d(TAG, "Fetching community pool accounts from $poolUrl/api/sources")
                        val request = Request.Builder()
                            .url("$poolUrl/api/sources")
                            .header("Authorization", "Bearer $DEFAULT_READ_KEY")
                            .header("X-Pool-Client", "v2")
                            .header("User-Agent", "BitChord/1.7.1")
                            .build()

                        val response = okHttpClient.newCall(request).execute()
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                responseBody = body
                                winningUrl = poolUrl
                                break
                            }
                        } else {
                            TrackLog.w(TAG, "Pool request to $poolUrl returned HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        TrackLog.w(TAG, "Failed connecting to pool at $poolUrl: ${e.message}")
                    }
                }

                if (responseBody.isNullOrBlank()) {
                    TrackLog.w(TAG, "All community pool endpoints unreachable; retaining fallback accounts")
                    return@withLock qobuzAccounts.isNotEmpty()
                }

                val root = JSONObject(responseBody)
                val secretKey = PoolCrypto.deriveClientKey(DEFAULT_READ_KEY)

                // 1. Parse Tidal Accounts
                val tidalObj = root.optJSONObject("tidal")
                val tidalArr = tidalObj?.optJSONArray("accounts")
                val newTidal = mutableListOf<TidalAccount>()
                if (tidalArr != null) {
                    for (i in 0 until tidalArr.length()) {
                        val acc = tidalArr.optJSONObject(i) ?: continue
                        val tokenRaw = acc.optString("token")
                        val token = PoolCrypto.maybeDecryptWith(tokenRaw, secretKey) ?: continue
                        if (token.isBlank()) continue
                        val id = acc.optLong("id", 0L).takeIf { it != 0L }
                        val refRaw = acc.optString("refreshToken").takeIf { it.isNotBlank() }
                        val ref = refRaw?.let { PoolCrypto.maybeDecryptWith(it, secretKey) }
                        val ccRaw = acc.optString("countryCode").takeIf { it.isNotBlank() }
                        val cc = ccRaw?.let { PoolCrypto.maybeDecryptWith(it, secretKey) }
                        val premium = acc.optBoolean("premium", true)
                        newTidal.add(TidalAccount(id, token, ref, cc, premium))
                    }
                }

                // 2. Parse Qobuz Accounts
                val qobuzObj = root.optJSONObject("qobuz")
                val qobuzArr = qobuzObj?.optJSONArray("accounts")
                val newQobuz = mutableListOf<QobuzAccount>()
                if (qobuzArr != null) {
                    for (i in 0 until qobuzArr.length()) {
                        val acc = qobuzArr.optJSONObject(i) ?: continue
                        val appIdRaw = acc.optString("appId")
                        val appId = PoolCrypto.maybeDecryptWith(appIdRaw, secretKey) ?: continue
                        val tokenRaw = acc.optString("token")
                        val token = PoolCrypto.maybeDecryptWith(tokenRaw, secretKey) ?: continue
                        val secretRaw = acc.optString("appSecret")
                        val appSecret = PoolCrypto.maybeDecryptWith(secretRaw, secretKey) ?: continue
                        val id = acc.optLong("id", 0L).takeIf { it != 0L }
                        val premium = acc.optBoolean("premium", true)
                        newQobuz.add(QobuzAccount(id, appId, token, appSecret, premium))
                    }
                }

                // 3. Parse Qobuz APIs / Proxies
                val qobuzApiArr = qobuzObj?.optJSONArray("apis")
                val newQobuzApis = mutableListOf<QobuzApi>()
                if (qobuzApiArr != null) {
                    for (i in 0 until qobuzApiArr.length()) {
                        val api = qobuzApiArr.optJSONObject(i) ?: continue
                        val baseUrl = api.optString("baseUrl").trim().trimEnd('/')
                        if (baseUrl.startsWith("http")) {
                            newQobuzApis.add(
                                QobuzApi(
                                    id = api.optLong("id", 0L).takeIf { it != 0L },
                                    baseUrl = baseUrl,
                                    status = api.optString("status", "alive"),
                                )
                            )
                        }
                    }
                }

                if (newQobuz.none { it.id == STATIC_QOBUZ_ACCOUNT.id }) {
                    newQobuz.add(STATIC_QOBUZ_ACCOUNT)
                }

                tidalAccounts.clear()
                tidalAccounts.addAll(newTidal)
                qobuzAccounts.clear()
                qobuzAccounts.addAll(newQobuz)
                qobuzApis.clear()
                qobuzApis.addAll(newQobuzApis)
                lastFetchMs = System.currentTimeMillis()

                TrackLog.d(TAG, "✓ Pool synced: ${tidalAccounts.size} Tidal accounts, ${qobuzAccounts.size} Qobuz accounts, ${qobuzApis.size} Qobuz APIs from $winningUrl")
                true
            } catch (e: Exception) {
                TrackLog.w(TAG, "Failed to sync pool accounts: ${e.message}")
                qobuzAccounts.isNotEmpty()
            }
        }
    }

    fun getTidalAccounts(): List<TidalAccount> = synchronized(tidalAccounts) { tidalAccounts.toList() }
    fun getQobuzAccounts(): List<QobuzAccount> = synchronized(qobuzAccounts) { qobuzAccounts.toList() }
    fun getQobuzApis(): List<QobuzApi> = synchronized(qobuzApis) { qobuzApis.toList() }
}
