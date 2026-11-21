package com.dedovmosol.iwomail.eas

import android.util.Base64
import com.dedovmosol.iwomail.network.ConnectionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.Response
import java.io.InputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Централизованный клиент для Exchange Web Services (EWS)
 * 
 * Используется для:
 * - Exchange 2007 (который не поддерживает EAS)
 * - Fallback для Exchange 2010+ когда EAS не работает
 * 
 * Поддерживает:
 * - NTLM аутентификация
 * - Basic аутентификация
 * - Кэширование NTLM токенов
 */
class EwsClient(
    private val ewsUrl: String,
    private val username: String,
    private val password: String,
    private val domain: String,
    private val httpClient: OkHttpClient,
    private val ntlmAuth: NtlmAuthenticator
) {
    private val ntlmLock = Mutex()
    @Volatile var serverVersion: String = VERSION_2007
        private set

    // EWS POSTs may be mutations. A lost response, 408, 503, or redirect must not
    // cause OkHttp to deliver the same SOAP operation again.
    private val basicClient by lazy {
        httpClient.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .build()
    }

    // NTLM authenticates the TCP connection. Use a private HTTP/1.1 pool and hold
    // the coroutine mutex through response consumption, including streaming, so
    // another operation cannot borrow the Type1 connection before Type3.
    private val ntlmClient by lazy {
        basicClient.newBuilder()
            .connectionPool(ConnectionPool(1, 60, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    suspend fun executeRequest(soapBody: String, action: String): EwsResult<String> =
        when (val result = executeEnvelope(buildSoapRequest(soapBody), action)) {
            is EasResult.Success -> EwsResult.Success(result.data)
            is EasResult.Error -> EwsResult.Error(result.message)
        }

    internal suspend fun executeEnvelope(soapRequest: String, action: String): EasResult<String> =
        withAuthenticatedResponse(soapRequest, action) { response, _ ->
            val xml = response.body?.string()
            when {
                xml.isNullOrBlank() -> requestError(action, "empty response")
                action == "CreateItem" && (!isCompleteEnvelope(xml) || !CREATE_ITEM_RESULT.containsMatchIn(xml)) ->
                    requestError(action, "missing or incomplete CreateItem response")
                else -> EasResult.Success(xml)
            }
        }

    suspend fun <T> executeStreaming(
        soapRequest: String,
        action: String,
        read: (InputStream, checkActive: () -> Unit) -> T
    ): EasResult<T> = withAuthenticatedResponse(soapRequest, action) { response, checkActive ->
        val body = response.body ?: return@withAuthenticatedResponse requestError(action, "empty response")
        EasResult.Success(body.byteStream().use { read(it, checkActive) })
    }

    private data class AuthChallenge(val type2: ByteArray?, val offersBasic: Boolean)

    private suspend fun <T> withAuthenticatedResponse(
        soapRequest: String,
        action: String,
        read: (Response, checkActive: () -> Unit) -> EasResult<T>
    ): EasResult<T> = withContext(Dispatchers.IO) {
        try {
            val body = soapRequest.toRequestBody(CONTENT_TYPE_XML.toMediaType())
            fun post(auth: String, ntlm: Boolean = true): Request = Request.Builder()
                .url(ewsUrl)
                .post(body.withoutReplay())
                .header("Authorization", auth)
                .header("SOAPAction", "\"$SOAP_ACTION_PREFIX$action\"")
                .apply { if (ntlm) header("Connection", "keep-alive") }
                .build()

            fun finish(response: Response, checkActive: () -> Unit): EasResult<T> = when (response.code) {
                200, 500 -> read(response, checkActive) // EWS may return a SOAP Fault as HTTP 500.
                // These statuses definitively rejected the SOAP operation. In particular, an
                // unavailable EWS endpoint on Exchange 2007 can still allow the EAS fallback.
                401, 403, 404, 405 -> requestError(action, "HTTP ${response.code}", mayHaveApplied = false)
                else -> requestError(action, "HTTP ${response.code}")
            }

            // A null result here means *only* that a 401 explicitly offered Basic.
            val ntlmResult = ntlmLock.withLock {
                var challenge: AuthChallenge? = null
                val first = ntlmClient.newCall(post(ntlmAuth.createType1AuthHeader())).readCancellable { resp, check ->
                    if (resp.code != 401) return@readCancellable finish(resp, check)
                    val type2 = resp.headers.values("WWW-Authenticate").firstNotNullOfOrNull { header ->
                        NTLM_CHALLENGE.find(header)?.value?.let(ntlmAuth::parseType2FromHeader)
                    }
                    challenge = AuthChallenge(type2, resp.offersBasic())
                    if (type2 == null && !resp.offersBasic()) {
                        return@readCancellable requestError(action, "HTTP 401 without an authentication challenge", mayHaveApplied = false)
                    }
                    if (type2 != null) {
                        // Drain the challenge without buffering so Type3 can reuse the connection.
                        resp.body?.byteStream()?.let { stream ->
                            val buffer = ByteArray(8192)
                            while (stream.read(buffer) != -1) check()
                        }
                    }
                    null
                }
                if (first != null) return@withLock first
                val auth = checkNotNull(challenge)
                val type2 = auth.type2
                if (type2 == null) {
                    if (auth.offersBasic) null else requestError(action, "HTTP 401", mayHaveApplied = false)
                } else {
                    ntlmClient.newCall(post(ntlmAuth.createType3AuthHeader(type2))).readCancellable { resp, check ->
                        if (resp.code == 401 && resp.offersBasic()) null else finish(resp, check)
                    }
                }
            }
            ntlmResult ?: basicClient.newCall(post(getBasicAuthHeader(), ntlm = false)).readCancellable(::finish)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            coroutineContext.ensureActive()
            requestError(
                action, ConnectionFailure.describe(e),
                mayHaveApplied = e !is ConnectException && e !is UnknownHostException && e !is SSLHandshakeException
            )
        }
    }

    private fun Response.offersBasic(): Boolean = challenges().any { it.scheme.equals("Basic", ignoreCase = true) }

    // Register cancellation before entering blocking HTTP I/O and keep it installed until
    // the body is consumed/closed. Suspending only until headers would leave reads blocked.
    private suspend fun <T> Call.readCancellable(read: (Response, () -> Unit) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                val value = execute().use { response -> read(response) { continuation.context.ensureActive() } }
                continuation.resumeWith(Result.success(value))
            } catch (e: Exception) {
                continuation.resumeWith(Result.failure(e))
            }
        }

    private fun requestError(action: String, detail: String, mayHaveApplied: Boolean = true): EasResult.Error =
        EasResult.Error(if (action == "CreateItem" && mayHaveApplied) "$EWS_CREATE_OUTCOME_UNKNOWN: $detail" else "EWS $action: $detail")

    // ==================== Высокоуровневые EWS операции ====================
    
    /**
     * FindItem - поиск элементов в папке
     */
    suspend fun findItems(
        folderId: String,
        isDistinguished: Boolean = false,
        maxEntries: Int = 200,
        baseShape: String = "AllProperties"
    ): EwsResult<String> {
        val folderIdXml = if (isDistinguished) {
            """<t:DistinguishedFolderId Id="${XmlUtils.escape(folderId)}"/>"""
        } else {
            """<t:FolderId Id="${XmlUtils.escape(folderId)}"/>"""
        }
        
        val body = """
            <m:FindItem Traversal="Shallow">
                <m:ItemShape>
                    <t:BaseShape>${XmlUtils.escape(baseShape)}</t:BaseShape>
                </m:ItemShape>
                <m:IndexedPageItemView MaxEntriesReturned="$maxEntries" Offset="0" BasePoint="Beginning"/>
                <m:ParentFolderIds>
                    $folderIdXml
                </m:ParentFolderIds>
            </m:FindItem>
        """.trimIndent()
        
        return executeRequest(body, "FindItem")
    }
    
    /**
     * GetItem - получение элемента по ID
     */
    suspend fun getItem(
        itemId: String,
        bodyType: String = "Text",
        baseShape: String = "AllProperties"
    ): EwsResult<String> {
        val body = """
            <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
                <ItemShape>
                    <t:BaseShape>${XmlUtils.escape(baseShape)}</t:BaseShape>
                    <t:BodyType>${XmlUtils.escape(bodyType)}</t:BodyType>
                </ItemShape>
                <ItemIds>
                    <t:ItemId Id="${XmlUtils.escape(itemId)}"/>
                </ItemIds>
            </GetItem>
        """.trimIndent()
        
        return executeRequest(body, "GetItem")
    }
    
    /**
     * GetItem для нескольких элементов
     */
    suspend fun getItems(
        itemIds: List<String>,
        bodyType: String = "Text",
        baseShape: String = "AllProperties"
    ): EwsResult<String> {
        val itemIdsXml = itemIds.joinToString("\n") { 
            """<t:ItemId Id="${XmlUtils.escape(it)}"/>""" 
        }
        
        val body = """
            <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
                <ItemShape>
                    <t:BaseShape>${XmlUtils.escape(baseShape)}</t:BaseShape>
                    <t:BodyType>${XmlUtils.escape(bodyType)}</t:BodyType>
                </ItemShape>
                <ItemIds>
                    $itemIdsXml
                </ItemIds>
            </GetItem>
        """.trimIndent()
        
        return executeRequest(body, "GetItem")
    }
    
    /**
     * CreateItem - создание элемента
     */
    suspend fun createItem(
        itemXml: String,
        folderId: String? = null,
        isDistinguishedFolder: Boolean = true,
        messageDisposition: String = "SaveOnly"
    ): EwsResult<String> {
        val folderXml = if (folderId != null) {
            val safeFolderId = XmlUtils.escape(folderId)
            val folderIdXml = if (isDistinguishedFolder) {
                """<t:DistinguishedFolderId Id="$safeFolderId"/>"""
            } else {
                """<t:FolderId Id="$safeFolderId"/>"""
            }
            """<m:SavedItemFolderId>$folderIdXml</m:SavedItemFolderId>"""
        } else ""
        
        val body = """
            <m:CreateItem MessageDisposition="${XmlUtils.escape(messageDisposition)}">
                $folderXml
                <m:Items>
                    $itemXml
                </m:Items>
            </m:CreateItem>
        """.trimIndent()
        
        return executeRequest(body, "CreateItem")
    }
    
    /**
     * DeleteItem - удаление элемента
     */
    suspend fun deleteItem(
        itemId: String,
        deleteType: String = "MoveToDeletedItems",
        sendMeetingCancellations: String? = null
    ): EwsResult<String> {
        val meetingAttr = if (sendMeetingCancellations != null) {
            """ SendMeetingCancellations="${XmlUtils.escape(sendMeetingCancellations)}""""
        } else ""

        val body = """
            <m:DeleteItem DeleteType="${XmlUtils.escape(deleteType)}"$meetingAttr>
                <m:ItemIds>
                    <t:ItemId Id="${XmlUtils.escape(itemId)}"/>
                </m:ItemIds>
            </m:DeleteItem>
        """.trimIndent()
        
        return executeRequest(body, "DeleteItem")
    }
    
    /**
     * UpdateItem - обновление элемента
     */
    suspend fun updateItem(
        itemId: String,
        changeKey: String? = null,
        updates: String,
        conflictResolution: String = "AutoResolve",
        messageDisposition: String = "SaveOnly"
    ): EwsResult<String> {
        val changeKeyAttr = if (changeKey != null) {
            """ ChangeKey="${XmlUtils.escape(changeKey)}""""
        } else ""
        
        val body = """
            <m:UpdateItem MessageDisposition="${XmlUtils.escape(messageDisposition)}" ConflictResolution="${XmlUtils.escape(conflictResolution)}">
                <m:ItemChanges>
                    <t:ItemChange>
                        <t:ItemId Id="${XmlUtils.escape(itemId)}"$changeKeyAttr/>
                        <t:Updates>
                            $updates
                        </t:Updates>
                    </t:ItemChange>
                </m:ItemChanges>
            </m:UpdateItem>
        """.trimIndent()
        
        return executeRequest(body, "UpdateItem")
    }
    
    /**
     * MoveItem - перемещение элемента в другую папку
     */
    suspend fun moveItem(
        itemId: String,
        destinationFolderId: String,
        isDistinguishedFolder: Boolean = true
    ): EwsResult<String> {
        val safeDestId = XmlUtils.escape(destinationFolderId)
        val folderIdXml = if (isDistinguishedFolder) {
            """<t:DistinguishedFolderId Id="$safeDestId"/>"""
        } else {
            """<t:FolderId Id="$safeDestId"/>"""
        }
        
        val body = """
            <m:MoveItem>
                <m:ToFolderId>
                    $folderIdXml
                </m:ToFolderId>
                <m:ItemIds>
                    <t:ItemId Id="${XmlUtils.escape(itemId)}"/>
                </m:ItemIds>
            </m:MoveItem>
        """.trimIndent()
        
        return executeRequest(body, "MoveItem")
    }
    
    // ==================== Вспомогательные методы ====================
    
    /**
     * Формирует полный SOAP конверт
     */
    fun buildSoapRequest(body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="$serverVersion"/>
    </soap:Header>
    <soap:Body>
        $body
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }
    
    /**
     * Возвращает Basic Auth header
     */
    private fun getBasicAuthHeader(): String {
        val credentials = if (domain.isNotEmpty()) {
            "$domain\\$username:$password"
        } else {
            "$username:$password"
        }
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
    
    companion object {
        private const val CONTENT_TYPE_XML = "text/xml; charset=utf-8"
        private const val SOAP_ACTION_PREFIX = "http://schemas.microsoft.com/exchange/services/2006/messages/"
        const val VERSION_2007 = "Exchange2007_SP1"
        internal const val EWS_CREATE_OUTCOME_UNKNOWN = "EWS_CREATE_OUTCOME_UNKNOWN"
        private val NTLM_CHALLENGE = Regex("""(?i)\bNTLM[ \t]+[A-Za-z0-9+/]+={0,2}(?=\s|,|$)""")
        private val CREATE_ITEM_RESULT = Regex("""<(?:[\w.-]+:)?CreateItemResponseMessage\b[^>]*\bResponseClass\s*=\s*["'](?:Success|Error|Warning)["']""")

        fun isEwsSuccess(xml: String): Boolean =
            xml.contains("ResponseClass=\"Success\"") &&
                (xml.contains("<ResponseCode>NoError</ResponseCode>") ||
                    xml.contains("<m:ResponseCode>NoError</m:ResponseCode>"))

        fun isEwsSuccessOrNotFound(xml: String): Boolean =
            isEwsSuccess(xml) || xml.contains("ErrorItemNotFound")

        private val ENVELOPE_END = Regex("</(?:[A-Za-z_][\\w.-]*:)?Envelope\\s*>")

        /**
         * SOAP-конверт дошёл целиком: закрывающий тег Envelope на месте. Обрыв без ошибки
         * HTTP-уровня (соединение закрыто без длины) иначе выглядит успешным ответом:
         * `ResponseClass`, `ResponseCode` и атрибуты `RootFolder` стоят в начале, обрезан хвост.
         */
        fun isCompleteEnvelope(xml: String): Boolean = ENVELOPE_END.containsMatchIn(xml)

        /**
         * Ответ FindItem/FindFolder успешен, цел и содержит последний элемент выборки. MS: при
         * `RootFolder@IncludesLastItemInRange="true"` дальше листать не нужно. Без этого
         * признака «первые N элементов» нельзя считать всей папкой.
         */
        fun isEwsLastPage(xml: String): Boolean =
            isEwsSuccess(xml) && isCompleteEnvelope(xml) && xml.contains("IncludesLastItemInRange=\"true\"")
    }
    
    /**
     * Извлекает ItemId из ответа CreateItem
     */
    fun extractItemId(responseXml: String): String? {
        val pattern = """<t:ItemId Id="([^"]+)"""".toRegex()
        return pattern.find(responseXml)?.groupValues?.get(1)
    }
    
    /**
     * Извлекает ItemId с ChangeKey из ответа
     */
    fun extractItemIdWithChangeKey(responseXml: String): Pair<String, String>? {
        val pattern = """<t:ItemId Id="([^"]+)"\s+ChangeKey="([^"]+)"""".toRegex()
        val match = pattern.find(responseXml) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }
    
    /**
     * Извлекает сообщение об ошибке из ответа
     */
    fun extractErrorMessage(responseXml: String): String {
        val messagePattern = """<m:MessageText>(.*?)</m:MessageText>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val responseCodePattern = """<m:ResponseCode>(.*?)</m:ResponseCode>""".toRegex()
        
        val messageText = messagePattern.find(responseXml)?.groupValues?.get(1)
        val responseCode = responseCodePattern.find(responseXml)?.groupValues?.get(1)
        
        return messageText ?: responseCode ?: "Unknown EWS error"
    }
    
}

/**
 * Результат EWS операции
 */
sealed class EwsResult<out T> {
    data class Success<T>(val data: T) : EwsResult<T>()
    data class Error(val message: String) : EwsResult<Nothing>()
    
    fun <R> map(transform: (T) -> R): EwsResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    fun getOrElse(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Error -> default
    }
}
