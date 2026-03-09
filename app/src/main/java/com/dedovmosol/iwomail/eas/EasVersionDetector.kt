package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Detects EAS protocol version via HTTP OPTIONS (MS-ASHTTP 2.2.3).
 *
 * Exchange 2007 SP1 returns: MS-ASProtocolVersions: 2.5,12.0,12.1
 *   (MS-ASHTTP Appendix A, <11>: "does not return 14.0, 14.1, 16.0, 16.1")
 *   Protocol version field = 121 (Appendix A, <2>)
 *
 * Exchange 2010 returns: 2.5,12.0,12.1,14.0,14.1
 *
 * Negotiation: picks the highest common version from [supportedVersions].
 * For Exchange 2007 SP1 intersection = [12.1, 12.0], result = 12.1.
 *
 * Thread-safety:
 *  - [versionLock] guards compound read-check-write on version state.
 *  - @Volatile fields provide inter-thread visibility (JVM ACC_VOLATILE).
 *    IMPORTANT: @Volatile alone does NOT guarantee atomicity for composite ops;
 *    that is why synchronized(versionLock) is used inside detect() and isExchange2007().
 *
 * TTL: re-check every 24h — servers don't change version at runtime.
 */
class EasVersionDetector(
    private val transport: EasTransport,
    private val supportedVersions: List<String> = listOf("14.1", "14.0", "12.1", "12.0")
) {
    @Volatile var versionDetected: Boolean = false
        private set
    @Volatile var serverSupportedVersions: List<String> = emptyList()
        private set
    @Volatile private var versionDetectedAt: Long = 0L

    private val versionLock = Any()

    companion object {
        private const val VERSION_TTL_MS = 24 * 60 * 60 * 1000L
    }

    /**
     * Detect EAS version via OPTIONS.
     * On success, also updates [transport.easVersion].
     */
    suspend fun detect(): EasResult<String> {
        val now = System.currentTimeMillis()
        if (versionDetected && serverSupportedVersions.isNotEmpty()
            && (now - versionDetectedAt) < VERSION_TTL_MS) {
            return EasResult.Success(transport.easVersion)
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "${transport.normalizedServerUrl}/Microsoft-Server-ActiveSync"

                val request = Request.Builder()
                    .url(url)
                    .method("OPTIONS", null)
                    .header("Authorization", transport.getAuthHeader())
                    .header("User-Agent", "Android/12-EAS-2.0")
                    .build()

                val call = transport.client.newCall(request)
                val response = suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            if (cont.isActive) cont.resume(response)
                            else response.close()
                        }
                    })
                }

                response.use { resp ->
                    if (resp.code == 200) {
                        val versionsHeader = resp.header("MS-ASProtocolVersions") ?: ""

                        if (versionsHeader.isNotEmpty()) {
                            val versions = versionsHeader.split(",").map { it.trim() }
                            val bestVersion = supportedVersions.firstOrNull { it in versions }

                            synchronized(versionLock) {
                                serverSupportedVersions = versions
                                transport.easVersion = bestVersion ?: "12.1"
                                versionDetectedAt = System.currentTimeMillis()
                                versionDetected = true
                            }
                            EasResult.Success(transport.easVersion)
                        } else {
                            synchronized(versionLock) {
                                versionDetectedAt = System.currentTimeMillis()
                                versionDetected = true
                            }
                            EasResult.Success(transport.easVersion)
                        }
                    } else if (resp.code == 401) {
                        EasResult.Error("Ошибка авторизации (401)")
                    } else {
                        EasResult.Error("HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Network error")
            }
        }
    }

    /**
     * Returns true if the server is Exchange 2007 (only EAS 12.x, no 14.x or 16.x).
     */
    fun isExchange2007(): Boolean = synchronized(versionLock) {
        if (!versionDetected || serverSupportedVersions.isEmpty()) {
            return@synchronized transport.easVersion.startsWith("12.")
        }
        serverSupportedVersions.none { it.startsWith("14.") || it.startsWith("16.") }
    }

    fun getServerInfo(): Map<String, Any> {
        return mapOf(
            "easVersion" to transport.easVersion,
            "serverVersions" to serverSupportedVersions,
            "versionDetected" to versionDetected
        )
    }
}
