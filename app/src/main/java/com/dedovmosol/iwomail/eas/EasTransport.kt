package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.util.Base64

/**
 * EAS/EWS HTTP transport layer.
 *
 * Encapsulates:
 *  - WBXML-encoded EAS command execution (with HTTP 449 → Provision retry)
 *  - EWS SOAP execution (Basic → NTLM fallback)
 *  - Provision two-phase flow (MS-ASPROV 3.1.5.1)
 *
 * Thread-safety:
 *  - [policyKey]: @Volatile, writes protected by [provisionMutex]
 *  - [easVersion]: @Volatile, written only by EasVersionDetector
 *  - [provisionMutex]: Kotlin Mutex (NON-reentrant);
 *    sendDeviceSettings() runs AFTER withLock to avoid deadlock on 449 retry.
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class EasTransport(
    val client: OkHttpClient,
    val wbxmlParser: WbxmlParser,
    val normalizedServerUrl: String,
    private val username: String,
    private val password: String,
    private val domain: String,
    val deviceId: String,
    val deviceType: String,
    private val ntlmAuth: NtlmAuthenticator,
    val ewsClient: EwsClient,
    private val certificatePath: String? = null,
    initialPolicyKey: String? = null
) {
    @Volatile var policyKey: String? = initialPolicyKey
    @Volatile var easVersion: String = "12.1"

    private val provisionMutex = Mutex()
    @Volatile private var insideSendDeviceSettings = false
    private val provisioning by lazy { EasProvisioning(deviceId, easVersion) }

    val ewsUrl: String by lazy {
        normalizedServerUrl
            .replace("/Microsoft-Server-ActiveSync", "")
            .replace("/default.eas", "")
            .trimEnd('/') + "/EWS/Exchange.asmx"
    }

    // ========================= EAS Transport =========================

    fun getAuthHeader(): String {
        val credentials = if (domain.isNotEmpty()) {
            "$domain\\$username:$password"
        } else {
            "$username:$password"
        }
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }

    fun buildUrl(command: String): String {
        val baseUrl = normalizedServerUrl.trimEnd('/')
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            android.util.Log.e("EasTransport", "Invalid baseUrl (no scheme): $baseUrl, normalizing...")
            val fixedUrl = "https://$baseUrl"
            return buildUrlInternal(fixedUrl, command)
        }
        return buildUrlInternal(baseUrl, command)
    }

    private fun buildUrlInternal(baseUrl: String, command: String): String {
        val userParam = if (domain.isNotEmpty()) {
            java.net.URLEncoder.encode("$domain\\$username", "UTF-8")
        } else {
            java.net.URLEncoder.encode(username, "UTF-8")
        }
        return "$baseUrl/Microsoft-Server-ActiveSync/default.eas?" +
            "Cmd=$command&" +
            "User=$userParam&" +
            "DeviceId=$deviceId&" +
            "DeviceType=$deviceType"
    }

    /**
     * Core EAS command executor.
     * Handles WBXML encode/decode, PolicyKey header, and HTTP 449 → Provision retry.
     *
     * MS-ASPROV: When the server returns 449, the client MUST Provision, then retry.
     * Guard `command != "Provision"` prevents recursive Provision calls.
     */
    suspend fun <T> executeEasCommand(
        command: String,
        xml: String,
        skipPolicyKey: Boolean = false,
        parser: (String) -> T
    ): EasResult<T> = withContext(Dispatchers.IO) {
        try {
            val wbxml = wbxmlParser.generate(xml)

            val requestBuilder = Request.Builder()
                .url(buildUrl(command))
                .post(wbxml.toRequestBody(CONTENT_TYPE_WBXML.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", CONTENT_TYPE_WBXML)
                .header("User-Agent", "Android/12-EAS-2.0")

            if (!skipPolicyKey) {
                policyKey?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
            }

            val request = requestBuilder.build()
            val response = executeRequest(request)

            response.use { resp ->
                if (resp.code == 449 && command != "Provision") {
                    when (val provResult = provision()) {
                        is EasResult.Success -> {
                            val retryRequestBuilder = Request.Builder()
                                .url(buildUrl(command))
                                .post(wbxml.toRequestBody(CONTENT_TYPE_WBXML.toMediaType()))
                                .header("Authorization", getAuthHeader())
                                .header("MS-ASProtocolVersion", easVersion)
                                .header("Content-Type", CONTENT_TYPE_WBXML)
                                .header("User-Agent", "Android/12-EAS-2.0")

                            policyKey?.let { key ->
                                retryRequestBuilder.header("X-MS-PolicyKey", key)
                            }

                            val retryResponse = executeRequest(retryRequestBuilder.build())

                            retryResponse.use { retryResp ->
                                if (retryResp.isSuccessful) {
                                    val responseBody = retryResp.body?.bytes()
                                    if (responseBody != null && responseBody.isNotEmpty()) {
                                        val responseXml = wbxmlParser.parse(responseBody)
                                        return@withContext EasResult.Success(parser(responseXml))
                                    } else if (command == "Sync") {
                                        try {
                                            return@withContext EasResult.Success(parser(EMPTY_SYNC_RESPONSE))
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            return@withContext EasResult.Error("Пустой ответ от сервера после retry (Sync)")
                                        }
                                    } else {
                                        return@withContext EasResult.Error("Пустой ответ от сервера после retry ($command)")
                                    }
                                } else {
                                    return@withContext EasResult.Error("Provision phase retry failed: HTTP ${retryResp.code} (EAS: $easVersion)")
                                }
                            }
                        }
                        is EasResult.Error -> {
                            return@withContext provResult
                        }
                    }
                }

                if (resp.isSuccessful) {
                    val responseBody = resp.body?.bytes()
                    if (responseBody != null && responseBody.isNotEmpty()) {
                        val responseXml = wbxmlParser.parse(responseBody)
                        EasResult.Success(parser(responseXml))
                    } else if (command == "Sync") {
                        try {
                            EasResult.Success(parser(EMPTY_SYNC_RESPONSE))
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            EasResult.Error("Пустой ответ от сервера (Sync)")
                        }
                    } else {
                        EasResult.Error("Пустой ответ от сервера ($command)")
                    }
                } else if (resp.code == 401) {
                    resp.body?.close()
                    EasResult.Error("UNAUTHORIZED")
                } else if (resp.code == 403) {
                    resp.body?.close()
                    EasResult.Error("ACCESS_DENIED")
                } else if (resp.code == 449) {
                    resp.body?.close()
                    policyKey = null
                    EasResult.Error("PROVISION_REQUIRED")
                } else {
                    val errorBody = resp.body?.string() ?: ""
                    EasResult.Error("HTTP ${resp.code}: ${resp.message}")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val certInfo = if (certificatePath != null) " [cert: $certificatePath]" else ""
            EasResult.Error("Ошибка: ${e.javaClass.simpleName}: ${e.message}$certInfo")
        }
    }

    suspend fun executeRequest(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
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

    // ========================= Provision =========================

    /**
     * Two-phase Provision (MS-ASPROV 3.1.5.1).
     *
     * Phase 1: request policies → get temporary PolicyKey
     * Phase 2: acknowledge policies → get final PolicyKey
     *
     * [provisionMutex] ensures single concurrent Provision.
     * [sendDeviceSettings] runs AFTER mutex release to avoid deadlock
     * (Settings→449→provision would re-enter the mutex otherwise).
     */
    suspend fun provision(): EasResult<String> {
        val result = provisionMutex.withLock {
            val savedPolicyKey = policyKey
            policyKey = null

            val xml1 = provisioning.buildPhase1Request()
            var tempPolicyKey: String? = null
            var phase1Response: EasProvisioning.ProvisionResponse? = null

            val result1 = executeEasCommand("Provision", xml1) { responseXml ->
                phase1Response = provisioning.parseResponse(responseXml)
                tempPolicyKey = phase1Response?.policyKey
                tempPolicyKey
            }

            when (result1) {
                is EasResult.Success -> {
                    val response = phase1Response ?: return run {
                        policyKey = savedPolicyKey
                        EasResult.Error("Failed to parse Provision response")
                    }
                    if (response.policyStatus == 2) {
                        policyKey = EasProvisioning.NO_POLICY_KEY
                        return EasResult.Success(EasProvisioning.NO_POLICY_KEY)
                    }
                    val error = provisioning.validatePhase1(response)
                    if (error != null) {
                        policyKey = savedPolicyKey
                        return EasResult.Error(error)
                    }
                }
                is EasResult.Error -> {
                    policyKey = savedPolicyKey
                    return EasResult.Error("Provision phase 1 failed: ${result1.message} (EAS: $easVersion)")
                }
            }

            val phase1Key = tempPolicyKey
                ?: return run {
                    policyKey = savedPolicyKey
                    EasResult.Error("Provision phase 1 did not return PolicyKey (EAS: $easVersion)")
                }
            val xml2 = provisioning.buildPhase2Request(phase1Key)
            var finalKey: String? = null
            var phase2Response: EasProvisioning.ProvisionResponse? = null

            val result2 = executeEasCommand("Provision", xml2) { responseXml ->
                phase2Response = provisioning.parseResponse(responseXml)
                finalKey = phase2Response?.policyKey
                finalKey
            }

            when (result2) {
                is EasResult.Success -> {
                    val response = phase2Response ?: return run {
                        policyKey = savedPolicyKey
                        EasResult.Error("Failed to parse Provision phase 2 response")
                    }
                    val error = provisioning.validatePhase2(response, tempPolicyKey)
                    if (error != null) {
                        policyKey = savedPolicyKey
                        return EasResult.Error(error)
                    }
                    policyKey = finalKey ?: tempPolicyKey
                }
                is EasResult.Error -> {
                    policyKey = savedPolicyKey
                    return EasResult.Error("Provision phase 2 failed: ${result2.message} (EAS: $easVersion)")
                }
            }

            val resultKey = finalKey ?: tempPolicyKey
            if (resultKey != null) {
                EasResult.Success(resultKey)
            } else {
                EasResult.Error("Provision failed: no policy key received (EAS: $easVersion)")
            }
        }

        if (result is EasResult.Success && !insideSendDeviceSettings) {
            insideSendDeviceSettings = true
            try {
                sendDeviceSettings()
            } finally {
                insideSendDeviceSettings = false
            }
        }
        return result
    }

    private suspend fun sendDeviceSettings(): EasResult<Unit> {
        val xml = provisioning.buildSettingsRequest()
        return executeEasCommand("Settings", xml) { }
    }

    // ========================= EWS Transport =========================

    suspend fun performNtlmHandshake(ewsUrl: String, soapRequest: String, action: String): String? {
        return ewsClient.performNtlmHandshake(soapRequest, action)
    }

    suspend fun executeNtlmRequest(ewsUrl: String, soapRequest: String, authHeader: String, action: String): String? {
        return ewsClient.executeNtlmRequest(soapRequest, authHeader, action)
    }

    suspend fun tryBasicAuthEws(ewsUrl: String, soapRequest: String, action: String): String? {
        return ewsClient.tryBasicAuth(soapRequest, action)
    }

    /**
     * Basic Auth → NTLM fallback for EWS SOAP requests.
     * DRY: single auth entry point instead of inline repetitions.
     */
    suspend fun executeEwsWithAuth(ewsUrl: String, soapRequest: String, operation: String): String? {
        var response = tryBasicAuthEws(ewsUrl, soapRequest, operation)
        if (response == null) {
            val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, operation) ?: return null
            response = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, operation)
        }
        return response
    }

    fun buildEwsSoapRequest(bodyContent: String): String {
        return """${EasXmlTemplates.SOAP_ENVELOPE_START}
  ${EasXmlTemplates.SOAP_HEADER_2007}
  <soap:Body>
$bodyContent
  </soap:Body>
${EasXmlTemplates.SOAP_ENVELOPE_END}"""
    }

    fun extractEwsError(xml: String): String {
        val messageText = EWS_MESSAGE_TEXT_REGEX.find(xml)?.groupValues?.get(1)
        val responseCode = EWS_RESPONSE_CODE_REGEX.find(xml)?.groupValues?.get(1)
        return messageText ?: responseCode ?: "Unknown error"
    }

    companion object {
        internal const val CONTENT_TYPE_WBXML = "application/vnd.ms-sync.wbxml"
        internal const val CONTENT_TYPE_XML = "text/xml; charset=utf-8"
        private const val EMPTY_SYNC_RESPONSE =
            """<?xml version="1.0" encoding="UTF-8"?><Sync><Collections><Collection><Status>1</Status></Collection></Collections></Sync>"""
        private val EWS_MESSAGE_TEXT_REGEX get() = EasPatterns.EWS_MESSAGE_TEXT
        private val EWS_RESPONSE_CODE_REGEX get() = EasPatterns.EWS_RESPONSE_CODE
    }
}
