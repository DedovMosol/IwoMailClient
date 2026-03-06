package com.dedovmosol.iwomail.eas

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val ntlmLock = Any()
    @Volatile var serverVersion: String = VERSION_2007
        private set
    
    // ==================== Низкоуровневые методы ====================
    
    /**
     * Выполняет EWS запрос с автоматическим выбором аутентификации
     * Сначала пробует NTLM, затем Basic
     */
    suspend fun executeRequest(soapBody: String, action: String): EwsResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val soapRequest = buildSoapRequest(soapBody)
                
                // 1. Пробуем NTLM (предпочтительно для Exchange)
                val ntlmResult = executeNtlm(soapRequest, action)
                if (ntlmResult != null) {
                    return@withContext EwsResult.Success(ntlmResult)
                }
                
                // 2. Fallback на Basic auth
                val basicResult = executeBasic(soapRequest, action)
                if (basicResult != null) {
                    return@withContext EwsResult.Success(basicResult)
                }
                
                EwsResult.Error("Не удалось выполнить EWS запрос: аутентификация не удалась")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EwsResult.Error("EWS ошибка: ${e.message}")
            }
        }
    }
    
    /**
     * Выполняет ПОЛНЫЙ NTLM цикл в одном методе:
     * Type1 (Negotiate) → Type2 (Challenge) → Type3 (Authenticate) + запрос
     * 
     * КРИТИЧНО: Все шаги должны идти в одном TCP соединении!
     * OkHttp connection pool с keep-alive должен это обеспечить.
     */
    suspend fun executeNtlmRequest(soapRequest: String, authHeader: String, action: String): String? {
        android.util.Log.d("NTM", "executeNtlmRequest START action=$action, url=$ewsUrl")
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            for (attempt in 0..1) {
                try {
                    val result = executeNtlmHandshake(soapRequest, action)
                    if (result != null || attempt > 0) return@withContext result
                    android.util.Log.w("NTM", "NTLM attempt $attempt returned null, retrying")
                } catch (e: Exception) {
                    lastException = e
                    android.util.Log.w("NTM", "NTLM attempt $attempt failed: ${e.message}")
                    if (attempt == 0) continue else break
                }
            }
            android.util.Log.e("NTM", "NTLM all attempts failed", lastException)
            null
        }
    }

    private fun executeNtlmHandshake(soapRequest: String, action: String): String? = synchronized(ntlmLock) {
        val request1 = Request.Builder()
            .url(ewsUrl)
            .post(soapRequest.toRequestBody(CONTENT_TYPE_XML.toMediaType()))
            .header("Authorization", ntlmAuth.createType1AuthHeader())
            .header("Content-Type", CONTENT_TYPE_XML)
            .header("SOAPAction", "\"$SOAP_ACTION_PREFIX$action\"")
            .header("Connection", "keep-alive")
            .build()
        
        val response1 = httpClient.newCall(request1).execute()
        
        if (response1.code != 401) {
            return@synchronized response1.use { if (it.isSuccessful) it.body?.string() else null }
        }
        val wwwAuth = response1.header("WWW-Authenticate")
        response1.body?.close()

        if (wwwAuth == null) return@synchronized null

        val type2Message = ntlmAuth.parseType2FromHeader(wwwAuth)
            ?: return@synchronized null
        val type3Header = ntlmAuth.createType3AuthHeader(type2Message)
        
        val request3 = Request.Builder()
            .url(ewsUrl)
            .post(soapRequest.toRequestBody(CONTENT_TYPE_XML.toMediaType()))
            .header("Authorization", type3Header)
            .header("Content-Type", CONTENT_TYPE_XML)
            .header("SOAPAction", "\"$SOAP_ACTION_PREFIX$action\"")
            .header("Connection", "keep-alive")
            .build()
        
        return@synchronized httpClient.newCall(request3).execute().use { response3 ->
            if (response3.isSuccessful) {
                response3.body?.string()
            } else {
                val errorBody = response3.body?.string()
                if (response3.code == 500 && errorBody != null && errorBody.contains("soap", ignoreCase = true)) {
                    errorBody
                } else {
                    null
                }
            }
        }
    }
    
    /**
     * Пробует Basic аутентификацию для EWS запроса
     */
    suspend fun tryBasicAuth(soapRequest: String, action: String): String? {
        android.util.Log.d("BAS", "tryBasicAuth: action=$action, soapLength=${soapRequest.length}")
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ewsUrl)
                    .post(soapRequest.toRequestBody(CONTENT_TYPE_XML.toMediaType()))
                    .header("Authorization", getBasicAuthHeader())
                    .header("Content-Type", CONTENT_TYPE_XML)
                    .header("SOAPAction", "\"$SOAP_ACTION_PREFIX$action\"")
                    .build()
                
                android.util.Log.d("BAS", "Executing request to $ewsUrl")
                httpClient.newCall(request).execute().use { response ->
                    android.util.Log.d("BAS", "Response: code=${response.code}, successful=${response.isSuccessful}")
                    
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        android.util.Log.d("BAS", "SUCCESS: body length=${body?.length ?: 0}")
                        body
                    } else {
                        val errorBody = response.body?.string()
                        android.util.Log.e("BAS", "FAILED: HTTP ${response.code}, bodyLen=${errorBody?.length ?: 0}")
                        null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BAS", "EXCEPTION: ${e.message}", e)
                null
            }
        }
    }
    
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
    
    /**
     * Выполняет NTLM handshake и возвращает маркер доступности NTLM.
     * Реальный 3-step handshake (Type1→Type2→Type3) выполняется внутри executeNtlmRequest,
     * т.к. все шаги ДОЛЖНЫ идти в одном TCP соединении.
     */
    suspend fun performNtlmHandshake(soapRequest: String, action: String): String? {
        return ""
    }

    /**
     * Выполняет запрос с NTLM аутентификацией
     */
    private suspend fun executeNtlm(soapRequest: String, action: String): String? {
        return executeNtlmRequest(soapRequest, "", action)
    }
    
    /**
     * Выполняет запрос с Basic аутентификацией
     */
    private suspend fun executeBasic(soapRequest: String, action: String): String? {
        return tryBasicAuth(soapRequest, action)
    }
    
    /**
     * Проверяет успешность ответа
     */
    fun isSuccessResponse(responseXml: String): Boolean {
        val hasSuccess = RESPONSE_SUCCESS_PATTERN.containsMatchIn(responseXml)
        val hasNoError = RESPONSE_NO_ERROR_PATTERN.containsMatchIn(responseXml)
        return hasSuccess && hasNoError
    }

    companion object {
        private const val CONTENT_TYPE_XML = "text/xml; charset=utf-8"
        private const val SOAP_ACTION_PREFIX = "http://schemas.microsoft.com/exchange/services/2006/messages/"
        const val VERSION_2007 = "Exchange2007_SP1"
        private val RESPONSE_SUCCESS_PATTERN = Regex("""<\w+[^>]+ResponseClass="Success"""")
        private val RESPONSE_NO_ERROR_PATTERN = Regex("""<(?:m:)?ResponseCode>NoError</(?:m:)?ResponseCode>""")
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
