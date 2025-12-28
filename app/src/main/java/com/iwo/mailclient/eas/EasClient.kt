package com.iwo.mailclient.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.util.Base64

/**
 * Exchange ActiveSync клиент
 * Поддерживает EAS 12.0, 12.1, 14.0, 14.1 для Exchange 2007+
 */
class EasClient(
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val acceptAllCerts: Boolean = false,
    private val port: Int = 443,
    private val useHttps: Boolean = true,
    private val deviceIdSuffix: String = "", // Для стабильного deviceId и реального email
    initialPolicyKey: String? = null, // PolicyKey из предыдущей сессии
    private val certificatePath: String? = null // Путь к файлу сертификата сервера
) {
    private val wbxmlParser = WbxmlParser()
    private val client: OkHttpClient
    
    // Нормализуем URL - добавляем схему и порт
    private val normalizedServerUrl: String = normalizeUrl(serverUrl, port, useHttps)
    
    // Версия EAS - по умолчанию 12.1, но может быть изменена после OPTIONS
    private var easVersion = "12.1"
    // Приоритет версий (от новых к старым) - Exchange 2007+
    private val supportedVersions = listOf("14.1", "14.0", "12.1", "12.0")
    // Версии поддерживаемые сервером (заполняется после OPTIONS)
    private var serverSupportedVersions: List<String> = emptyList()
    // Флаг что версия уже определена
    private var versionDetected = false
    // DeviceId должен быть стабильным для одного аккаунта
    private val deviceId = generateStableDeviceId(username, deviceIdSuffix)
    // DeviceType - как у Huawei
    private val deviceType = "Android"
    
    // PolicyKey для авторизованных запросов после Provision
    var policyKey: String? = initialPolicyKey
        private set

    // Результат создания папки
    data class FolderCreateResult(val serverId: String, val newSyncKey: String)
    
    /**
     * Устанавливает PolicyKey (например, из сохранённого аккаунта)
     */
    fun setPolicyKey(key: String?) {
        policyKey = key
    }
    
    /**
     * Определяет поддерживаемую версию EAS через OPTIONS запрос
     * Возвращает список поддерживаемых версий и выбирает лучшую
     */
    suspend fun detectEasVersion(): EasResult<String> {
        if (versionDetected && serverSupportedVersions.isNotEmpty()) {
            return EasResult.Success(easVersion)
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$normalizedServerUrl/Microsoft-Server-ActiveSync"
                
                val request = Request.Builder()
                    .url(url)
                    .method("OPTIONS", null)
                    .header("Authorization", getAuthHeader())
                    .header("User-Agent", "Android/12-EAS-2.0")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.code == 200) {
                    val versionsHeader = response.header("MS-ASProtocolVersions") ?: ""
                    
                    if (versionsHeader.isNotEmpty()) {
                        serverSupportedVersions = versionsHeader.split(",").map { it.trim() }
                        val bestVersion = supportedVersions.firstOrNull { it in serverSupportedVersions }
                        
                        if (bestVersion != null) {
                            easVersion = bestVersion
                            versionDetected = true
                            EasResult.Success(easVersion)
                        } else {
                            easVersion = "12.1"
                            versionDetected = true
                            EasResult.Success(easVersion)
                        }
                    } else {
                        versionDetected = true
                        EasResult.Success(easVersion)
                    }
                } else if (response.code == 401) {
                    EasResult.Error("Ошибка авторизации (401)")
                } else {
                    versionDetected = true
                    EasResult.Success(easVersion)
                }
            } catch (_: Exception) {
                versionDetected = true
                EasResult.Success(easVersion)
            }
        }
    }
    
    /**
     * Возвращает информацию о сервере
     */
    fun getServerInfo(): Map<String, Any> {
        return mapOf(
            "easVersion" to easVersion,
            "serverVersions" to serverSupportedVersions,
            "versionDetected" to versionDetected
        )
    }
    
    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Разрешаем все TLS версии и cipher suites для совместимости
            .connectionSpecs(listOf(
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.CLEARTEXT
            ))
        
        // Настраиваем SSL/TLS
        try {
            if (acceptAllCerts) {
                // Принимаем все сертификаты (самоподписанные)
                builder.hostnameVerifier { _, _ -> true }
                builder.sslSocketFactory(createTrustAllSslSocketFactory(), createTrustAllManager())
            } else if (certificatePath != null) {
                // Используем указанный сертификат из файла
                val certTrustManager = createCertificateTrustManager(certificatePath)
                if (certTrustManager != null) {
                    val sslContext = try {
                        SSLContext.getInstance("TLS", "Conscrypt")
                    } catch (_: Exception) {
                        SSLContext.getInstance("TLS")
                    }
                    sslContext.init(null, arrayOf(certTrustManager), SecureRandom())
                    // Используем TlsSocketFactory для поддержки старых TLS версий
                    builder.sslSocketFactory(TlsSocketFactory(sslContext.socketFactory), certTrustManager)
                    // Для самоподписанных сертификатов hostname может не совпадать
                    builder.hostnameVerifier { _, _ -> true }
                } else {
                    // Сертификат не загрузился — используем системный TrustManager
                    android.util.Log.e("EasClient", "Failed to load certificate from: $certificatePath")
                    val systemTrustManager = createSystemTrustManager()
                    val sslContext = try {
                        SSLContext.getInstance("TLS", "Conscrypt")
                    } catch (_: Exception) {
                        SSLContext.getInstance("TLS")
                    }
                    sslContext.init(null, arrayOf(systemTrustManager), SecureRandom())
                    builder.sslSocketFactory(TlsSocketFactory(sslContext.socketFactory), systemTrustManager)
                }
            } else {
                // Используем системный TrustManager который учитывает:
                // 1. Системные сертификаты
                // 2. Пользовательские сертификаты (из network_security_config)
                val sslContext = try {
                    SSLContext.getInstance("TLS", "Conscrypt")
                } catch (_: Exception) {
                    SSLContext.getInstance("TLS")
                }
                
                // Создаём TrustManager который доверяет и системным, и пользовательским сертификатам
                val systemTrustManager = createSystemTrustManager()
                sslContext.init(null, arrayOf(systemTrustManager), SecureRandom())
                
                builder.sslSocketFactory(TlsSocketFactory(sslContext.socketFactory), systemTrustManager)
            }
        } catch (_: Exception) {
            // Если вся настройка SSL упала - OkHttp использует свои дефолты
        }
        
        client = builder.build()
    }
    
    companion object {
        /**
         * Нормализует URL сервера - добавляет схему и порт
         */
        fun normalizeUrl(url: String, port: Int = 443, useHttps: Boolean = true): String {
            var trimmed = url.trim()
            
            // Если URL пустой - возвращаем дефолт
            if (trimmed.isEmpty()) {
                return if (useHttps) "https://localhost:$port" else "http://localhost:$port"
            }
            
            // Убираем существующую схему если есть
            trimmed = trimmed.removePrefix("https://").removePrefix("http://")
            
            // Извлекаем только хост (без порта и пути)
            val hostOnly = trimmed
                .substringBefore("/")  // убираем путь
                .substringBefore(":")  // убираем порт если есть
            
            val scheme = if (useHttps) "https" else "http"
            return "$scheme://$hostOnly:$port"
        }
    }
    
    private fun generateStableDeviceId(username: String, suffix: String): String {
        // Генерируем СТАБИЛЬНЫЙ DeviceId на основе username
        // Это важно чтобы сервер не требовал Provision при каждом запросе
        val hash = (username + suffix).hashCode().toLong() and 0xFFFFFFFFL
        return "androidc${String.format("%010d", hash % 10000000000L)}"
    }
    
    @Suppress("unused")
    private fun generateDeviceId(): String {
        return "android${System.currentTimeMillis().toString(16)}"
    }
    
    private fun createTrustAllSslSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(createTrustAllManager())
        // Используем Conscrypt для поддержки старых TLS
        val sslContext = try {
            SSLContext.getInstance("TLS", "Conscrypt")
        } catch (e: Exception) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(null, trustAllCerts, SecureRandom())
        return TlsSocketFactory(sslContext.socketFactory)
    }
    
    /**
     * SSLSocketFactory с поддержкой TLS 1.0/1.1/1.2 для Exchange 2007
     * Также устанавливает SNI (Server Name Indication) для серверов которые его требуют
     */
    private class TlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        // Приоритет протоколов - от новых к старым, без SSLv3 (устарел и отключён)
        private val preferredProtocols = arrayOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
        
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        
        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket {
            return enableTls(delegate.createSocket(s, host, port, autoClose), host)
        }
        
        override fun createSocket(host: String, port: Int): java.net.Socket {
            return enableTls(delegate.createSocket(host, port), host)
        }
        
        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket {
            return enableTls(delegate.createSocket(host, port, localHost, localPort), host)
        }
        
        override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket {
            return enableTls(delegate.createSocket(host, port), host.hostName)
        }
        
        override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket {
            return enableTls(delegate.createSocket(address, port, localAddress, localPort), address.hostName)
        }
        
        private fun enableTls(socket: java.net.Socket, hostname: String?): java.net.Socket {
            if (socket is SSLSocket) {
                try {
                    // Устанавливаем SNI (Server Name Indication) - критично для многих серверов
                    if (!hostname.isNullOrEmpty()) {
                        try {
                            val sslParams = socket.sslParameters
                            sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(hostname))
                            socket.sslParameters = sslParams
                        } catch (_: Exception) {
                            // SNI не поддерживается
                        }
                    }
                    
                    // Получаем поддерживаемые протоколы
                    val supported = socket.supportedProtocols ?: emptyArray()
                    if (supported.isEmpty()) {
                        // Если нет поддерживаемых - оставляем как есть
                        return socket
                    }
                    
                    // Выбираем протоколы которые поддерживаются устройством
                    val toEnable = preferredProtocols.filter { it in supported }
                    if (toEnable.isNotEmpty()) {
                        socket.enabledProtocols = toEnable.toTypedArray()
                    }
                    
                    // Включаем все поддерживаемые cipher suites
                    val supportedCiphers = socket.supportedCipherSuites
                    if (supportedCiphers != null && supportedCiphers.isNotEmpty()) {
                        socket.enabledCipherSuites = supportedCiphers
                    }
                } catch (_: Exception) {
                    // Ошибка конфигурации сокета
                }
            }
            return socket
        }
    }
    
    private fun createTrustAllManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
    
    /**
     * Создаёт TrustManager который доверяет системным и пользовательским сертификатам
     * Пользовательские сертификаты - это те, что установлены через Настройки Android
     */
    private fun createSystemTrustManager(): X509TrustManager {
        // Получаем системный TrustManager
        val systemTmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        systemTmf.init(null as java.security.KeyStore?)
        val systemTm = systemTmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
        
        // Пробуем получить пользовательские сертификаты из Android KeyStore
        val userTm: X509TrustManager? = try {
            val userKeyStore = java.security.KeyStore.getInstance("AndroidCAStore")
            userKeyStore.load(null, null)
            val userTmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            userTmf.init(userKeyStore)
            userTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (_: Exception) {
            null
        }
        
        // Возвращаем комбинированный TrustManager
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                systemTm?.checkClientTrusted(chain, authType)
            }
            
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // Сначала пробуем системные сертификаты
                try {
                    systemTm?.checkServerTrusted(chain, authType)
                    return
                } catch (_: Exception) {}
                
                // Потом пробуем пользовательские (AndroidCAStore)
                try {
                    userTm?.checkServerTrusted(chain, authType)
                    return
                } catch (_: Exception) {}
                
                // Если оба не сработали - бросаем исключение
                throw java.security.cert.CertificateException("Trust anchor for certification path not found.")
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val issuers = mutableListOf<X509Certificate>()
                systemTm?.acceptedIssuers?.let { issuers.addAll(it) }
                userTm?.acceptedIssuers?.let { issuers.addAll(it) }
                return issuers.toTypedArray()
            }
        }
    }
    
    /**
     * Создаёт TrustManager который доверяет сертификату из указанного файла
     * Поддерживает форматы: PEM (.crt, .pem), DER (.cer, .der)
     */
    private fun createCertificateTrustManager(certPath: String): X509TrustManager? {
        return try {
            val certFile = java.io.File(certPath)
            if (!certFile.exists()) {
                return null
            }
            
            // Загружаем сертификат
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val certificate = certFile.inputStream().use { inputStream ->
                certFactory.generateCertificate(inputStream) as X509Certificate
            }
            
            // Создаём KeyStore с этим сертификатом
            val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("server_cert", certificate)
            
            // Создаём TrustManager на основе этого KeyStore
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(keyStore)
            
            // Получаем системный TrustManager для fallback
            val systemTmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            systemTmf.init(null as java.security.KeyStore?)
            val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            val certTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            
            // Возвращаем комбинированный TrustManager
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    systemTm?.checkClientTrusted(chain, authType)
                }
                
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // Для самоподписанных сертификатов — сравниваем напрямую
                    if (chain.isNotEmpty()) {
                        val serverCert = chain[0]
                        
                        // Сравниваем публичные ключи
                        if (serverCert.publicKey == certificate.publicKey) {
                            try {
                                serverCert.checkValidity()
                            } catch (_: Exception) {
                                // Сертификат истёк, но принимаем
                            }
                            return
                        }
                        
                        // Альтернативная проверка — сравниваем encoded форму
                        try {
                            if (serverCert.encoded.contentEquals(certificate.encoded)) {
                                return
                            }
                        } catch (_: Exception) {}
                        
                        // Проверяем всю цепочку
                        for (cert in chain) {
                            if (cert.publicKey == certificate.publicKey || 
                                cert.encoded.contentEquals(certificate.encoded)) {
                                return
                            }
                        }
                    }
                    
                    // Пробуем через TrustManager с нашим KeyStore
                    try {
                        certTm?.checkServerTrusted(chain, authType)
                        return
                    } catch (_: Exception) {}
                    
                    // Потом пробуем системные
                    try {
                        systemTm?.checkServerTrusted(chain, authType)
                        return
                    } catch (_: Exception) {}
                    
                    throw java.security.cert.CertificateException("Certificate not trusted")
                }
                
                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    val issuers = mutableListOf<X509Certificate>()
                    issuers.add(certificate)
                    certTm?.acceptedIssuers?.let { issuers.addAll(it) }
                    systemTm?.acceptedIssuers?.let { issuers.addAll(it) }
                    return issuers.toTypedArray()
                }
            }
        } catch (_: Exception) {
            null
        }
    }
    
    private fun getAuthHeader(): String {
        val credentials = if (domain.isNotEmpty()) {
            "$domain\\$username:$password"
        } else {
            "$username:$password"
        }
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun buildUrl(command: String): String {
        val baseUrl = normalizedServerUrl.trimEnd('/')
        // User в URL должен быть с доменом (URL-encoded)
        val userParam = if (domain.isNotEmpty()) {
            java.net.URLEncoder.encode("$domain\\$username", "UTF-8")
        } else {
            username
        }
        // Используем /default.eas как в Huawei
        return "$baseUrl/Microsoft-Server-ActiveSync/default.eas?" +
            "Cmd=$command&" +
            "User=$userParam&" +
            "DeviceId=$deviceId&" +
            "DeviceType=$deviceType"
    }
    
    /**
     * Проверка подключения к серверу и определение версии EAS
     */
    suspend fun testConnection(): EasResult<String> {
        val versionResult = detectEasVersion()
        
        return when (versionResult) {
            is EasResult.Success -> {
                val info = buildString {
                    append("Подключение успешно!\n")
                    append("Версия EAS: $easVersion\n")
                    if (serverSupportedVersions.isNotEmpty()) {
                        append("Сервер поддерживает: ${serverSupportedVersions.joinToString(", ")}\n")
                    }
                    append("DeviceId: $deviceId")
                }
                EasResult.Success(info)
            }
            is EasResult.Error -> versionResult
        }
    }
    
    /**
     * Выбирает лучшую версию EAS из поддерживаемых сервером
     */
    private fun selectBestVersion(serverVersions: String) {
        // Сервер возвращает версии через запятую: "2.0,2.1,2.5,12.0,12.1"
        val versions = serverVersions.split(",").map { it.trim() }
        serverSupportedVersions = versions
        
        // Выбираем лучшую из наших поддерживаемых версий
        for (version in supportedVersions) {
            if (versions.contains(version)) {
                easVersion = version
                versionDetected = true
                return
            }
        }
        
        // Если ничего не нашли, оставляем 12.1 (для Exchange 2007)
        easVersion = "12.1"
        versionDetected = true
    }

    /**
     * Provisioning - получение политик безопасности от сервера
     * Требуется для EAS 12.0+ перед FolderSync
     */
    suspend fun provision(): EasResult<String> {
        val savedPolicyKey = policyKey
        policyKey = null
        
        val xml1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Provision xmlns="Provision">
                <Policies>
                    <Policy>
                        <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
                    </Policy>
                </Policies>
            </Provision>
        """.trimIndent()
        
        val result1 = executeEasCommand("Provision", xml1) { responseXml ->
            extractValue(responseXml, "PolicyKey")
        }
        
        val tempPolicyKey = when (result1) {
            is EasResult.Success -> result1.data ?: run {
                policyKey = savedPolicyKey
                return EasResult.Error("PolicyKey not found")
            }
            is EasResult.Error -> {
                policyKey = savedPolicyKey
                return result1
            }
        }
        // PolicyKey передаётся ТОЛЬКО в теле, НЕ в заголовке (policyKey = null)
        // Status = 1 означает "политики приняты" (согласно AOSP)
        // ВАЖНО: Тег Status должен идти ПОСЛЕ PolicyKey (как в AOSP Serializer)
        val xml2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Provision xmlns="Provision">
                <Policies>
                    <Policy>
                        <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
                        <PolicyKey>$tempPolicyKey</PolicyKey>
                        <Status>1</Status>
                    </Policy>
                </Policies>
            </Provision>
        """.trimIndent()
        
        var finalKey: String? = null
        val result2 = executeEasCommand("Provision", xml2) { responseXml ->
            finalKey = extractValue(responseXml, "PolicyKey") ?: tempPolicyKey
            finalKey
        }
        
        when (result2) {
            is EasResult.Success -> {
                policyKey = finalKey
            }
            is EasResult.Error -> {
                policyKey = savedPolicyKey
                return result2
            }
        }
        
        sendDeviceSettings()
        return EasResult.Success(finalKey ?: tempPolicyKey)
    }
    
    /**
     * Отправляет информацию об устройстве (Settings command)
     * Требуется после Provision для Exchange 2007
     */
    private suspend fun sendDeviceSettings(): EasResult<Unit> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Settings xmlns="Settings">
                <DeviceInformation>
                    <Set>
                        <Model>Android</Model>
                        <IMEI>${deviceId.takeLast(8)}</IMEI>
                        <FriendlyName>Android Device</FriendlyName>
                        <OS>Android 12</OS>
                        <UserAgent>Android/12-EAS-2.0</UserAgent>
                    </Set>
                </DeviceInformation>
            </Settings>
        """.trimIndent()
        
        return executeEasCommand("Settings", xml) { }
    }
    
    /**
     * Получает одно письмо из папки для верификации email (без сохранения в БД)
     * @param folderId ID папки (Отправленные или Входящие)
     * @return EasEmail или null если папка пустая
     */
    suspend fun fetchOneEmailForVerification(folderId: String): EasResult<EasEmail?> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Шаг 1: Получаем начальный SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$folderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(null) // Не удалось получить SyncKey
        }
        
        // Шаг 2: Запрашиваем 1 письмо
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$folderId</CollectionId>
                        <DeletesAsMoves>1</DeletesAsMoves>
                        <GetChanges/>
                        <WindowSize>1</WindowSize>
                        <Options>
                            <FilterType>0</FilterType>
                            <BodyPreference xmlns="AirSyncBase">
                                <Type>1</Type>
                                <TruncationSize>0</TruncationSize>
                            </BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            val response = parseSyncResponse(responseXml)
            response.emails.firstOrNull()
        }
    }
    
    /**
     * Синхронизация папок (FolderSync)
     * Для Exchange 2007 сначала делаем Provision если нет PolicyKey
     */
    suspend fun folderSync(syncKey: String = "0"): EasResult<FolderSyncResponse> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        if (syncKey == "0" || policyKey == null) {
            if (syncKey == "0") {
                policyKey = null
            }
            
            when (val provResult = provision()) {
                is EasResult.Success -> { }
                is EasResult.Error -> {
                    return EasResult.Error("Provision failed: ${provResult.message}")
                }
            }
        }
        
        val result = doFolderSync(syncKey)
        
        if (result is EasResult.Success && result.data.status == 1) {
            return result
        }
        
        val status = if (result is EasResult.Success) result.data.status else -1
        return EasResult.Error("Не удалось синхронизировать папки. Status: $status. DeviceId: $deviceId")
    }
    
    private suspend fun doFolderSync(syncKey: String): EasResult<FolderSyncResponse> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderSync xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
            </FolderSync>
        """.trimIndent()
        
        val skipPolicyKey = (policyKey == null)
        
        return executeEasCommand("FolderSync", xml, skipPolicyKey = skipPolicyKey) { responseXml ->
            parseFolderSyncResponse(responseXml)
        }
    }
    
    /**
     * Создание новой папки (FolderCreate)
     */
    suspend fun createFolder(
        displayName: String,
        parentId: String = "0",
        folderType: Int = 12,
        syncKey: String
    ): EasResult<FolderCreateResult> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderCreate xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
                <ParentId>$parentId</ParentId>
                <DisplayName>$displayName</DisplayName>
                <Type>$folderType</Type>
            </FolderCreate>
        """.trimIndent()
        
        return executeEasCommand("FolderCreate", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val serverId = extractValue(responseXml, "ServerId") ?: ""
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            if (status == 1 && serverId.isNotEmpty()) {
                FolderCreateResult(serverId, newSyncKey)
            } else {
                val errorMsg = when (status) {
                    2 -> "Папка с таким именем уже существует"
                    3 -> "Системную папку нельзя создать"
                    5 -> "Родительская папка не найдена"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey, попробуйте синхронизировать папки"
                    10 -> "Неверный формат запроса"
                    11 -> "Неизвестная ошибка"
                    else -> "Ошибка создания папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Удаление папки с сервера (FolderDelete)
     */
    suspend fun deleteFolder(
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderDelete xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
                <ServerId>$serverId</ServerId>
            </FolderDelete>
        """.trimIndent()
        
        return executeEasCommand("FolderDelete", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    3 -> "Системную папку нельзя удалить"
                    4 -> "Папка не существует"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey"
                    else -> "Ошибка удаления папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Переименование папки на сервере (FolderUpdate)
     */
    suspend fun renameFolder(
        serverId: String,
        newDisplayName: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderUpdate xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
                <ServerId>$serverId</ServerId>
                <ParentId>0</ParentId>
                <DisplayName>$newDisplayName</DisplayName>
            </FolderUpdate>
        """.trimIndent()
        
        return executeEasCommand("FolderUpdate", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    2 -> "Папка с таким именем уже существует"
                    3 -> "Системную папку нельзя переименовать"
                    4 -> "Папка не существует"
                    5 -> "Родительская папка не существует"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey"
                    else -> "Ошибка переименования папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Перемещение писем между папками (MoveItems)
     */
    suspend fun moveItems(
        items: List<Pair<String, String>>,
        dstFolderId: String
    ): EasResult<Map<String, String>> {
        val movesXml = items.joinToString("") { (srcMsgId, srcFldId) ->
            "<Move><SrcMsgId>$srcMsgId</SrcMsgId><SrcFldId>$srcFldId</SrcFldId><DstFldId>$dstFolderId</DstFldId></Move>"
        }
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MoveItems xmlns="Move">
                $movesXml
            </MoveItems>
        """.trimIndent()
        
        return executeEasCommand("MoveItems", xml) { responseXml ->
            val results = mutableMapOf<String, String>()
            val responsePattern = "<Response>(.*?)</Response>".toRegex(RegexOption.DOT_MATCHES_ALL)
            responsePattern.findAll(responseXml).forEach { match ->
                val responseContent = match.groupValues[1]
                val srcMsgId = extractValue(responseContent, "SrcMsgId") ?: ""
                val status = extractValue(responseContent, "Status")?.toIntOrNull() ?: 0
                val dstMsgId = extractValue(responseContent, "DstMsgId") ?: ""
                
                when (status) {
                    1, 3 -> {
                        if (dstMsgId.isNotEmpty()) {
                            results[srcMsgId] = dstMsgId
                        }
                    }
                    else -> { }
                }
            }
            results
        }
    }
    
    /**
     * Синхронизация писем (Sync)
     */
    suspend fun sync(
        collectionId: String,
        syncKey: String = "0",
        windowSize: Int = 100
    ): EasResult<SyncResponse> {
        val xml = if (syncKey == "0") {
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$collectionId</CollectionId>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else {
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$collectionId</CollectionId>
                            <DeletesAsMoves>1</DeletesAsMoves>
                            <GetChanges/>
                            <WindowSize>$windowSize</WindowSize>
                            <Options>
                                <FilterType>0</FilterType>
                                <BodyPreference xmlns="AirSyncBase">
                                    <Type>2</Type>
                                    <TruncationSize>51200</TruncationSize>
                                </BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        }
        
        val result = executeEasCommand("Sync", xml) { responseXml ->
            parseSyncResponse(responseXml)
        }
        
        if (result is EasResult.Error && result.message.contains("Пустой ответ")) {
            return EasResult.Success(SyncResponse(
                syncKey = syncKey,
                status = 1,
                moreAvailable = false,
                emails = emptyList()
            ))
        }
        
        if (result is EasResult.Error && result.message.contains("449")) {
            when (val provResult = provision()) {
                is EasResult.Success -> {
                    val retryResult = executeEasCommand("Sync", xml) { responseXml ->
                        parseSyncResponse(responseXml)
                    }
                    if (retryResult is EasResult.Success) {
                        return retryResult
                    }
                }
                is EasResult.Error -> { }
            }
        }
        
        return result
    }
    
    /**
     * Отправка письма (SendMail)
     */
    suspend fun sendMail(
        to: String,
        subject: String,
        body: String,
        cc: String = "",
        importance: Int = 1,
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val mimeBytes = buildMimeMessageBytes(to, subject, body, cc, requestReadReceipt, requestDeliveryReceipt)
            val url = buildUrl("SendMail") + "&SaveInSent=T"
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val request = requestBuilder.build()
            val response = executeRequest(request)
            
            if (response.isSuccessful || response.code == 200) {
                EasResult.Success(true)
            } else {
                val errorBody = response.body?.string() ?: ""
                
                if (response.code == 449) {
                    when (val provResult = provision()) {
                        is EasResult.Success -> {
                            val retryRequest = Request.Builder()
                                .url(url)
                                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                                .header("Authorization", getAuthHeader())
                                .header("MS-ASProtocolVersion", easVersion)
                                .header("Content-Type", contentType)
                                .header("User-Agent", "Android/12-EAS-2.0")
                                .apply { policyKey?.let { header("X-MS-PolicyKey", it) } }
                                .build()
                            
                            val retryResponse = executeRequest(retryRequest)
                            if (retryResponse.isSuccessful) {
                                return@withContext EasResult.Success(true)
                            }
                        }
                        is EasResult.Error -> {}
                    }
                }
                
                EasResult.Error("Ошибка отправки: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки: ${e.message}")
        }
    }
    
    private fun buildMimeMessageBytes(to: String, subject: String, body: String, cc: String, requestReadReceipt: Boolean = false, requestDeliveryReceipt: Boolean = false): ByteArray {
        // Используем реальный email из deviceIdSuffix (передаётся account.email)
        // Fallback на username@domain если deviceIdSuffix не содержит @
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        // Генерируем Message-ID
        val messageId = "<${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        
        // Форматируем дату по RFC 2822
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        
        val sb = StringBuilder()
        sb.append("Date: $date\r\n")
        sb.append("From: $fromEmail\r\n")
        sb.append("To: $to\r\n")
        if (cc.isNotEmpty()) {
            sb.append("Cc: $cc\r\n")
        }
        sb.append("Message-ID: $messageId\r\n")
        // Кодируем тему в UTF-8 Base64 для поддержки кириллицы
        val encodedSubject = "=?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        sb.append("Subject: $encodedSubject\r\n")
        // Запрос отчёта о прочтении (MDN)
        if (requestReadReceipt) {
            sb.append("Disposition-Notification-To: $fromEmail\r\n")
            sb.append("Return-Receipt-To: $fromEmail\r\n")
            sb.append("X-Confirm-Reading-To: $fromEmail\r\n")
        }
        // Запрос отчёта о доставке (DSN)
        if (requestDeliveryReceipt) {
            sb.append("X-MS-Exchange-Organization-DeliveryReportRequested: true\r\n")
            sb.append("Return-Path: $fromEmail\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(body)
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Строит MIME сообщение с вложениями (multipart/mixed)
     */
    private fun buildMimeWithAttachments(
        to: String, 
        subject: String, 
        body: String, 
        cc: String,
        attachments: List<Triple<String, String, ByteArray>>, // name, mimeType, data
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false
    ): ByteArray {
        // Используем реальный email из deviceIdSuffix (передаётся account.email)
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        val messageId = "<${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        val boundary = "----=_Part_${System.currentTimeMillis()}_${System.nanoTime()}"
        
        val sb = StringBuilder()
        sb.append("Date: $date\r\n")
        sb.append("From: $fromEmail\r\n")
        sb.append("To: $to\r\n")
        if (cc.isNotEmpty()) {
            sb.append("Cc: $cc\r\n")
        }
        sb.append("Message-ID: $messageId\r\n")
        val encodedSubject = "=?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        sb.append("Subject: $encodedSubject\r\n")
        // Запрос отчёта о прочтении (MDN)
        if (requestReadReceipt) {
            sb.append("Disposition-Notification-To: $fromEmail\r\n")
            sb.append("Return-Receipt-To: $fromEmail\r\n")
            sb.append("X-Confirm-Reading-To: $fromEmail\r\n")
        }
        // Запрос отчёта о доставке (DSN)
        if (requestDeliveryReceipt) {
            sb.append("X-MS-Exchange-Organization-DeliveryReportRequested: true\r\n")
            sb.append("Return-Path: $fromEmail\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")
        
        // Текстовая часть
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(body)
        sb.append("\r\n")
        
        // Вложения
        for ((name, mimeType, data) in attachments) {
            sb.append("--$boundary\r\n")
            val encodedName = "=?UTF-8?B?${Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
            sb.append("Content-Type: $mimeType; name=\"$encodedName\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"$encodedName\"\r\n")
            sb.append("\r\n")
            sb.append(Base64.encodeToString(data, Base64.DEFAULT))
            sb.append("\r\n")
        }
        
        sb.append("--$boundary--\r\n")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Результат загрузки тела письма с MDN информацией
     */
    data class EmailBodyResult(
        val body: String,
        val mdnRequestedBy: String? = null // Email для отправки MDN
    )
    
    /**
     * Загрузка полного тела письма с парсингом MDN заголовка
     */
    suspend fun fetchEmailBodyWithMdn(collectionId: String, serverId: String): EasResult<EmailBodyResult> {
        // Сначала пробуем получить полный MIME (Type=4) для парсинга заголовков
        val mimeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                    <ServerId xmlns="AirSync">$serverId</ServerId>
                    <Options>
                        <BodyPreference xmlns="AirSyncBase">
                            <Type>4</Type>
                            <TruncationSize>1048576</TruncationSize>
                        </BodyPreference>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        val mimeResult = executeEasCommand("ItemOperations", mimeXml) { responseXml ->
            extractValue(responseXml, "Data") ?: ""
        }
        
        var mdnRequestedBy: String? = null
        var bodyContent = ""
        
        if (mimeResult is EasResult.Success && mimeResult.data.isNotEmpty()) {
            val mimeData = mimeResult.data
            
            // Парсим MDN заголовок из MIME
            mdnRequestedBy = parseMdnHeader(mimeData)
            
            // Извлекаем тело из MIME
            bodyContent = extractBodyFromMime(mimeData)
        }
        
        // Если MIME не сработал или тело пустое - fallback на обычный запрос
        if (bodyContent.isEmpty()) {
            val htmlXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ItemOperations xmlns="ItemOperations">
                    <Fetch>
                        <Store>Mailbox</Store>
                        <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                        <ServerId xmlns="AirSync">$serverId</ServerId>
                        <Options>
                            <BodyPreference xmlns="AirSyncBase">
                                <Type>2</Type>
                                <TruncationSize>512000</TruncationSize>
                            </BodyPreference>
                        </Options>
                    </Fetch>
                </ItemOperations>
            """.trimIndent()
            
            val htmlResult = executeEasCommand("ItemOperations", htmlXml) { responseXml ->
                extractValue(responseXml, "Data") 
                    ?: extractValue(responseXml, "Body")
                    ?: ""
            }
            
            if (htmlResult is EasResult.Success) {
                bodyContent = htmlResult.data
            } else if (htmlResult is EasResult.Error) {
                return EasResult.Error(htmlResult.message)
            }
        }
        
        return EasResult.Success(EmailBodyResult(bodyContent, mdnRequestedBy))
    }
    
    /**
     * Парсит заголовок Disposition-Notification-To из MIME данных
     */
    private fun parseMdnHeader(mimeData: String): String? {
        // Ищем заголовок в разных вариантах написания
        val patterns = listOf(
            "Disposition-Notification-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE),
            "Return-Receipt-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE),
            "X-Confirm-Reading-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(mimeData)
            if (match != null) {
                val email = match.groupValues[1].trim()
                // Извлекаем email если в формате "Name <email>"
                val emailMatch = "<([^>]+)>".toRegex().find(email)
                return emailMatch?.groupValues?.get(1) ?: email
            }
        }
        
        return null
    }
    
    /**
     * Извлекает тело письма из MIME данных
     */
    private fun extractBodyFromMime(mimeData: String): String {
        // Если это multipart - ищем text/html или text/plain часть
        if (mimeData.contains("Content-Type:", ignoreCase = true)) {
            // Ищем boundary
            val boundaryMatch = "boundary=\"?([^\"\\r\\n]+)\"?".toRegex(RegexOption.IGNORE_CASE).find(mimeData)
            
            if (boundaryMatch != null) {
                val boundary = boundaryMatch.groupValues[1]
                val parts = mimeData.split("--$boundary")
                
                // Ищем HTML часть
                for (part in parts) {
                    if (part.contains("Content-Type: text/html", ignoreCase = true) ||
                        part.contains("Content-Type:text/html", ignoreCase = true)) {
                        // Извлекаем контент после пустой строки
                        val contentStart = part.indexOf("\r\n\r\n")
                        if (contentStart != -1) {
                            var content = part.substring(contentStart + 4).trim()
                            // Убираем закрывающий boundary если есть
                            if (content.endsWith("--")) {
                                content = content.dropLast(2).trim()
                            }
                            // Декодируем если base64
                            if (part.contains("Content-Transfer-Encoding: base64", ignoreCase = true)) {
                                try {
                                    content = String(Base64.decode(content.replace("\r\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {}
                            }
                            // Декодируем quoted-printable
                            if (part.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                                content = decodeQuotedPrintable(content)
                            }
                            if (content.isNotBlank()) return content
                        }
                    }
                }
                
                // Fallback на text/plain
                for (part in parts) {
                    if (part.contains("Content-Type: text/plain", ignoreCase = true) ||
                        part.contains("Content-Type:text/plain", ignoreCase = true)) {
                        val contentStart = part.indexOf("\r\n\r\n")
                        if (contentStart != -1) {
                            var content = part.substring(contentStart + 4).trim()
                            if (content.endsWith("--")) {
                                content = content.dropLast(2).trim()
                            }
                            if (part.contains("Content-Transfer-Encoding: base64", ignoreCase = true)) {
                                try {
                                    content = String(Base64.decode(content.replace("\r\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {}
                            }
                            if (part.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                                content = decodeQuotedPrintable(content)
                            }
                            if (content.isNotBlank()) return content
                        }
                    }
                }
            }
            
            // Не multipart - просто извлекаем тело после заголовков
            val bodyStart = mimeData.indexOf("\r\n\r\n")
            if (bodyStart != -1) {
                return mimeData.substring(bodyStart + 4).trim()
            }
        }
        
        // Возвращаем как есть если не удалось распарсить
        return mimeData
    }
    
    /**
     * Декодирует quoted-printable
     */
    private fun decodeQuotedPrintable(input: String): String {
        val result = StringBuilder()
        var i = 0
        val text = input.replace("=\r\n", "").replace("=\n", "") // Soft line breaks
        
        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3)
                try {
                    val byte = hex.toInt(16).toByte()
                    result.append(byte.toInt().toChar())
                    i += 3
                    continue
                } catch (_: Exception) {}
            }
            result.append(c)
            i++
        }
        
        return result.toString()
    }
    
    /**
     * Загрузка полного тела письма (ленивая загрузка)
     * @deprecated Используйте fetchEmailBodyWithMdn для получения MDN информации
     */
    suspend fun fetchEmailBody(collectionId: String, serverId: String): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                    <ServerId xmlns="AirSync">$serverId</ServerId>
                    <Options>
                        <BodyPreference xmlns="AirSyncBase">
                            <Type>2</Type>
                            <TruncationSize>512000</TruncationSize>
                        </BodyPreference>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        return executeEasCommand("ItemOperations", xml) { responseXml ->
            val body = extractValue(responseXml, "Data") 
                ?: extractValue(responseXml, "Body")
                ?: ""
            body
        }
    }
    
    /**
     * Отправка письма с вложениями
     */
    suspend fun sendMailWithAttachments(
        to: String,
        subject: String,
        body: String,
        cc: String = "",
        attachments: List<Triple<String, String, ByteArray>>,
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        val totalSize = attachments.sumOf { it.third.size }
        val maxAttachmentSize = 7 * 1024 * 1024
        if (totalSize > maxAttachmentSize) {
            val sizeMB = totalSize / 1024 / 1024
            return@withContext EasResult.Error("Размер вложений ($sizeMB МБ) превышает лимит сервера (7 МБ)")
        }
        
        try {
            val mimeBytes = buildMimeWithAttachments(to, subject, body, cc, attachments, requestReadReceipt, requestDeliveryReceipt)
            
            val maxMimeSize = 10 * 1024 * 1024
            if (mimeBytes.size > maxMimeSize) {
                val sizeMB = mimeBytes.size / 1024 / 1024
                return@withContext EasResult.Error("Размер письма ($sizeMB МБ) превышает лимит сервера (10 МБ)")
            }
            
            val url = buildUrl("SendMail") + "&SaveInSent=T"
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val response = executeRequest(requestBuilder.build())
            
            if (response.isSuccessful || response.code == 200) {
                EasResult.Success(true)
            } else {
                val errorMsg = when (response.code) {
                    500 -> "Сервер отклонил письмо. Возможно, размер вложений превышает лимит сервера."
                    413 -> "Размер письма слишком большой для сервера"
                    else -> "Ошибка отправки: HTTP ${response.code}"
                }
                EasResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки: ${e.message}")
        }
    }
    
    /**
     * Сохранение черновика в папку Drafts через Sync Add
     * Использует формат Email namespace для Exchange 2007+
     * 
     * ВАЖНО: EAS официально НЕ поддерживает Sync Add для Email!
     * Эта функция оставлена для экспериментов - некоторые серверы могут принять.
     * В текущей версии приложения черновики сохраняются только локально.
     */
    @Suppress("unused")
    suspend fun saveDraft(
        draftsFolderId: String,
        syncKey: String,
        to: String,
        subject: String,
        body: String,
        cc: String = ""
    ): EasResult<String> {
        // Сначала получаем актуальный SyncKey если передан "0"
        var currentSyncKey = syncKey
        if (currentSyncKey == "0" || currentSyncKey.isEmpty()) {
            val initXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>0</SyncKey>
                            <CollectionId>$draftsFolderId</CollectionId>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
            
            val initResult = executeEasCommand("Sync", initXml) { responseXml ->
                extractValue(responseXml, "SyncKey") ?: "0"
            }
            
            when (initResult) {
                is EasResult.Success -> currentSyncKey = initResult.data
                is EasResult.Error -> return EasResult.Error("Не удалось получить SyncKey: ${initResult.message}")
            }
            
            if (currentSyncKey == "0") {
                return EasResult.Error("Не удалось инициализировать синхронизацию папки черновиков")
            }
        }
        
        val clientId = "draft_${System.currentTimeMillis()}"
        
        // Формируем дату в формате ISO 8601
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val dateReceived = dateFormat.format(java.util.Date())
        
        // Экранируем XML спецсимволы
        val escapedTo = escapeXml(to)
        val escapedCc = escapeXml(cc)
        val escapedSubject = escapeXml(subject)
        val escapedBody = escapeXml(body)
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:email="Email" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$currentSyncKey</SyncKey>
                        <CollectionId>$draftsFolderId</CollectionId>
                        <Commands>
                            <Add>
                                <ClientId>$clientId</ClientId>
                                <ApplicationData>
                                    <email:To>$escapedTo</email:To>
                                    <email:Cc>$escapedCc</email:Cc>
                                    <email:Subject>$escapedSubject</email:Subject>
                                    <email:DateReceived>$dateReceived</email:DateReceived>
                                    <email:Read>1</email:Read>
                                    <email:Flag/>
                                    <airsyncbase:Body>
                                        <airsyncbase:Type>1</airsyncbase:Type>
                                        <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                                    </airsyncbase:Body>
                                </ApplicationData>
                            </Add>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: currentSyncKey
            
            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    3 -> "Неверный SyncKey, попробуйте ещё раз"
                    4 -> "Ошибка протокола"
                    5 -> "Ошибка сервера"
                    6 -> "Ошибка клиента"
                    7 -> "Конфликт"
                    8 -> "Объект не найден"
                    9 -> "Нет места"
                    else -> "Ошибка сохранения черновика: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Экранирует XML спецсимволы
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    /**
     * Отправка отчёта о прочтении (MDN - Message Disposition Notification)
     * @param to email получателя отчёта (из заголовка Disposition-Notification-To)
     * @param originalSubject тема оригинального письма
     * @param originalMessageId Message-ID оригинального письма (опционально)
     */
    suspend fun sendMdn(
        to: String,
        originalSubject: String,
        originalMessageId: String? = null
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val mimeBytes = buildMdnMessage(to, originalSubject, originalMessageId)
            val url = buildUrl("SendMail") + "&SaveInSent=F" // Не сохраняем MDN в Отправленные
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val response = executeRequest(requestBuilder.build())
            
            if (response.isSuccessful || response.code == 200) {
                EasResult.Success(true)
            } else {
                EasResult.Error("Ошибка отправки MDN: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки MDN: ${e.message}")
        }
    }
    
    /**
     * Строит MDN сообщение (multipart/report)
     */
    private fun buildMdnMessage(to: String, originalSubject: String, originalMessageId: String?): ByteArray {
        // Используем реальный email из deviceIdSuffix (передаётся account.email)
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        val messageId = "<mdn.${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        val boundary = "----=_MDN_${System.currentTimeMillis()}"
        
        val sb = StringBuilder()
        sb.append("Date: $date\r\n")
        sb.append("From: $fromEmail\r\n")
        sb.append("To: $to\r\n")
        sb.append("Message-ID: $messageId\r\n")
        if (originalMessageId != null) {
            sb.append("In-Reply-To: $originalMessageId\r\n")
            sb.append("References: $originalMessageId\r\n")
        }
        val encodedSubject = "=?UTF-8?B?${Base64.encodeToString("Read: $originalSubject".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        sb.append("Subject: $encodedSubject\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/report; report-type=disposition-notification; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")
        
        // Часть 1: Человекочитаемое сообщение
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append("Ваше сообщение было прочитано.\r\n")
        sb.append("Your message has been read.\r\n")
        sb.append("\r\n")
        
        // Часть 2: MDN (machine-readable)
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: message/disposition-notification\r\n")
        sb.append("\r\n")
        sb.append("Reporting-UA: iwo Mail Client; Android\r\n")
        sb.append("Final-Recipient: rfc822;$fromEmail\r\n")
        if (originalMessageId != null) {
            sb.append("Original-Message-ID: $originalMessageId\r\n")
        }
        sb.append("Disposition: manual-action/MDN-sent-manually; displayed\r\n")
        sb.append("\r\n")
        
        sb.append("--$boundary--\r\n")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Скачивание вложения
     */
    suspend fun downloadAttachment(fileReference: String, collectionId: String? = null, serverId: String? = null): EasResult<ByteArray> {
        val itemOpsResult = downloadViaItemOperations(fileReference)
        if (itemOpsResult is EasResult.Success) {
            return itemOpsResult
        }
        
        if (collectionId != null && serverId != null) {
            val fetchResult = downloadViaItemOperationsFetchEmail(collectionId, serverId, fileReference)
            if (fetchResult is EasResult.Success) {
                return fetchResult
            }
        }
        
        val getAttResult = downloadViaGetAttachment(fileReference)
        if (getAttResult is EasResult.Success) {
            return getAttResult
        }
        
        // Если все методы не работают - возвращаем понятную ошибку
        return EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.\n\nВозможные причины:\n• Политика безопасности запрещает скачивание\n• Вложение удалено с сервера\n\nПопробуйте открыть письмо в веб-интерфейсе OWA.")
    }
    
    /**
     * Альтернативный метод - Fetch всего письма и извлечение вложения
     */
    private suspend fun downloadViaItemOperationsFetchEmail(
        collectionId: String, 
        serverId: String,
        fileReference: String
    ): EasResult<ByteArray> {
        // Извлекаем индекс вложения из FileReference (формат: FolderId:ItemId:AttachmentIndex)
        val parts = fileReference.replace("%3a", ":").replace("%3A", ":").split(":")
        val attachmentIndex = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                    <ServerId xmlns="AirSync">$serverId</ServerId>
                    <Options>
                        <BodyPreference xmlns="AirSyncBase">
                            <Type>4</Type>
                        </BodyPreference>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        val result = doItemOperationsFetch(xml)
        
        // Если получили данные, это может быть MIME письма с вложениями
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            // TODO: Парсить MIME и извлекать вложение по индексу
            // Пока возвращаем как есть
            return result
        }
        
        return result
    }
    
    private suspend fun downloadViaItemOperations(fileReference: String): EasResult<ByteArray> {
        // Пробуем оба варианта - декодированный и как есть
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }
        
        // Сначала пробуем с декодированным FileReference
        var result = tryItemOperations(decodedRef)
        if (result is EasResult.Success) {
            return result
        }
        
        // Если не сработало и FileReference был закодирован, пробуем как есть
        if (decodedRef != fileReference) {
            result = tryItemOperations(fileReference)
            if (result is EasResult.Success) {
                return result
            }
        }
        
        return result
    }
    
    private suspend fun tryItemOperations(fileRef: String): EasResult<ByteArray> {
        // Для Exchange 2007 EAS 12.1 формат FileReference: FolderId:ItemId:AttachmentIndex
        // Пробуем разные варианты XML структуры
        
        // Вариант 1: FileReference в namespace AirSyncBase (стандартный)
        val xml1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <FileReference xmlns="AirSyncBase">$fileRef</FileReference>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        var result = doItemOperationsFetch(xml1)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        // Вариант 2: FileReference без namespace (как в некоторых реализациях)
        val xml2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <FileReference>$fileRef</FileReference>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        result = doItemOperationsFetch(xml2)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        // Вариант 3: С Options для указания типа данных
        val xml3 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <FileReference xmlns="AirSyncBase">$fileRef</FileReference>
                    <Options>
                        <Range>0-999999999</Range>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        result = doItemOperationsFetch(xml3)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        return result
    }
    
    private suspend fun doItemOperationsFetch(xml: String): EasResult<ByteArray> {
        
        var result = executeEasCommand("ItemOperations", xml) { responseXml ->
            parseItemOperationsResponse(responseXml)
        }
        
        // Обработка HTTP 449
        if (result is EasResult.Error && result.message.contains("449")) {
            when (val provResult = provision()) {
                is EasResult.Success -> {
                    result = executeEasCommand("ItemOperations", xml) { responseXml ->
                        parseItemOperationsResponse(responseXml)
                    }
                }
                is EasResult.Error -> return EasResult.Error("Provision failed: ${provResult.message}")
            }
        }
        
        return when (result) {
            is EasResult.Success -> {
                val data = result.data
                if (data.isNotEmpty()) {
                    EasResult.Success(data)
                } else {
                    EasResult.Error("Вложение не найдено (Status=6)")
                }
            }
            is EasResult.Error -> EasResult.Error(result.message)
        }
    }
    
    private fun parseItemOperationsResponse(responseXml: String): ByteArray {
        // Проверяем общий статус
        val globalStatusPattern = "<ItemOperations>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val globalStatus = globalStatusPattern.find(responseXml)?.groupValues?.get(1)
        // Проверяем статус внутри Fetch
        val fetchStatusPattern = "<Fetch>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val fetchStatus = fetchStatusPattern.find(responseXml)?.groupValues?.get(1)
        // Status=1 - успех, Status=6 - не найдено
        if (fetchStatus != "1") {
            return ByteArray(0)
        }
        
        // Извлекаем данные - может быть в разных местах
        // Вариант 1: <Data>base64</Data>
        val dataPattern = "<Data>(.*?)</Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val dataMatch = dataPattern.find(responseXml)
        
        if (dataMatch != null) {
            val base64Data = dataMatch.groupValues[1].trim()
            try {
                return Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: Exception) {
            }
        }
        
        // Вариант 2: Данные могут быть в Properties/Data
        val propsDataPattern = "<Properties>.*?<Data>(.*?)</Data>.*?</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val propsMatch = propsDataPattern.find(responseXml)
        if (propsMatch != null) {
            val base64Data = propsMatch.groupValues[1].trim()
            try {
                return Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: Exception) {
            }
        }
        return ByteArray(0)
    }
    
    private suspend fun downloadViaGetAttachment(fileReference: String): EasResult<ByteArray> {
        // Пробуем оба варианта - закодированный и декодированный
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }
        
        // Сначала пробуем с декодированным (как есть в URL)
        val result1 = tryGetAttachment(decodedRef)
        if (result1 is EasResult.Success) return result1
        
        // Потом с закодированным
        if (decodedRef != fileReference) {
            val result2 = tryGetAttachment(fileReference)
            if (result2 is EasResult.Success) return result2
        }
        
        return EasResult.Error("GetAttachment не поддерживается сервером (HTTP 501)")
    }
    
    private suspend fun tryGetAttachment(attachmentName: String): EasResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$normalizedServerUrl/Microsoft-Server-ActiveSync?" +
                    "Cmd=GetAttachment&AttachmentName=$attachmentName&User=$username&DeviceId=$deviceId&DeviceType=$deviceType"
                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", getAuthHeader())
                    .header("MS-ASProtocolVersion", easVersion)
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                policyKey?.let { requestBuilder.header("X-MS-PolicyKey", it) }
                
                var response = executeRequest(requestBuilder.build())
                // Обработка 449
                if (response.code == 449) {
                    when (val provResult = provision()) {
                        is EasResult.Success -> {
                            val retryBuilder = Request.Builder()
                                .url(url)
                                .get()
                                .header("Authorization", getAuthHeader())
                                .header("MS-ASProtocolVersion", easVersion)
                                .header("User-Agent", "Android/12-EAS-2.0")
                            policyKey?.let { retryBuilder.header("X-MS-PolicyKey", it) }
                            response = executeRequest(retryBuilder.build())
                        }
                        is EasResult.Error -> return@withContext EasResult.Error("Provision failed")
                    }
                }
                
                if (!response.isSuccessful) {
                    return@withContext EasResult.Error("HTTP ${response.code}: ${response.message}")
                }
                
                val data = response.body?.bytes()
                if (data == null || data.isEmpty()) {
                    return@withContext EasResult.Error("Пустой ответ")
                }
                EasResult.Success(data)
                
            } catch (e: Exception) {
                EasResult.Error("Ошибка: ${e.message}")
            }
        }
    }

    
    /**
     * Direct Push - Ping запрос для получения уведомлений о новых письмах
     * Сервер держит соединение открытым до таймаута или до появления изменений
     * 
     * @param folderIds список ServerId папок для мониторинга
     * @param heartbeatInterval интервал в секундах (60-3540, рекомендуется 900)
     * @return PingResult со статусом и списком изменённых папок
     * 
     * Статусы:
     * 1 - Таймаут, изменений нет
     * 2 - Есть изменения в папках (нужно синхронизировать)
     * 3 - Ошибка синтаксиса
     * 4 - Отсутствует heartbeat
     * 5 - Heartbeat слишком большой/маленький
     * 6 - Слишком много папок
     * 7 - Папка не существует
     * 8 - Общая ошибка
     */
    suspend fun ping(
        folderIds: List<String>,
        heartbeatInterval: Int = 900 // 15 минут по умолчанию
    ): EasResult<PingResult> {
        if (folderIds.isEmpty()) {
            return EasResult.Error("Нет папок для мониторинга")
        }
        
        val foldersXml = folderIds.joinToString("\n") { folderId ->
            """                <Folder>
                    <Id>$folderId</Id>
                    <Class>Email</Class>
                </Folder>"""
        }
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Ping xmlns="Ping">
                <HeartbeatInterval>$heartbeatInterval</HeartbeatInterval>
                <Folders>
$foldersXml
                </Folders>
            </Ping>
        """.trimIndent()
        
        return withContext(Dispatchers.IO) {
            try {
                val url = buildUrl("Ping")
                val wbxmlBody = wbxmlParser.generate(xml)
                
                // Создаём клиент с увеличенным таймаутом для Ping
                val pingClient = client.newBuilder()
                    .readTimeout((heartbeatInterval + 60).toLong(), TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(wbxmlBody.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                    .header("Authorization", getAuthHeader())
                    .header("MS-ASProtocolVersion", easVersion)
                    .header("Content-Type", "application/vnd.ms-sync.wbxml")
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                policyKey?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
                
                val response = pingClient.newCall(requestBuilder.build()).execute()
                
                if (response.code == 449) {
                    return@withContext EasResult.Error("Требуется Provision (449)")
                }
                
                if (!response.isSuccessful) {
                    return@withContext EasResult.Error("HTTP ${response.code}: ${response.message}")
                }
                
                val responseBody = response.body?.bytes()
                
                if (responseBody == null || responseBody.isEmpty()) {
                    return@withContext EasResult.Success(PingResult(1, emptyList()))
                }
                
                val responseXml = wbxmlParser.parse(responseBody)
                val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
                val changedFolders = mutableListOf<String>()
                
                val folderPattern = "<Folder>(.*?)</Folder>".toRegex(RegexOption.DOT_MATCHES_ALL)
                folderPattern.findAll(responseXml).forEach { match ->
                    val folderId = extractValue(match.groupValues[1], "Id")
                    if (folderId != null) {
                        changedFolders.add(folderId)
                    }
                }
                
                EasResult.Success(PingResult(status, changedFolders))
                
            } catch (e: java.net.SocketTimeoutException) {
                EasResult.Success(PingResult(1, emptyList()))
            } catch (e: Exception) {
                EasResult.Error("Ошибка Ping: ${e.message}")
            }
        }
    }
    
    /**
     * Пометить письмо как прочитанное
     */
    suspend fun markAsRead(
        collectionId: String,
        serverId: String,
        syncKey: String,
        read: Boolean = true
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <Class>Email</Class>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$collectionId</CollectionId>
                        <Commands>
                            <Change>
                                <ServerId>$serverId</ServerId>
                                <ApplicationData>
                                    <Read xmlns="Email">${if (read) "1" else "0"}</Read>
                                </ApplicationData>
                            </Change>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            // Возвращаем новый SyncKey
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Удалить письмо (перемещает в корзину)
     */
    suspend fun deleteEmail(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <Class>Email</Class>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$collectionId</CollectionId>
                        <DeletesAsMoves>1</DeletesAsMoves>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Окончательно удалить письмо (без перемещения в корзину)
     */
    suspend fun deleteEmailPermanently(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$collectionId</CollectionId>
                        <DeletesAsMoves>0</DeletesAsMoves>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    private suspend fun <T> executeEasCommand(
        command: String,
        xml: String,
        skipPolicyKey: Boolean = false,
        parser: (String) -> T
    ): EasResult<T> = withContext(Dispatchers.IO) {
        try {
            val wbxml = wbxmlParser.generate(xml)
            
            val requestBuilder = Request.Builder()
                .url(buildUrl(command))
                .post(wbxml.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", "application/vnd.ms-sync.wbxml")
                .header("User-Agent", "Android/12-EAS-2.0")
            
            if (!skipPolicyKey) {
                policyKey?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
            }
            
            val request = requestBuilder.build()
            val response = executeRequest(request)
            
            if (response.code == 449) {
                response.body?.close()
                
                when (val provResult = provision()) {
                    is EasResult.Success -> {
                        val retryRequestBuilder = Request.Builder()
                            .url(buildUrl(command))
                            .post(wbxml.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                            .header("Authorization", getAuthHeader())
                            .header("MS-ASProtocolVersion", easVersion)
                            .header("Content-Type", "application/vnd.ms-sync.wbxml")
                            .header("User-Agent", "Android/12-EAS-2.0")
                        
                        policyKey?.let { key ->
                            retryRequestBuilder.header("X-MS-PolicyKey", key)
                        }
                        
                        val retryResponse = executeRequest(retryRequestBuilder.build())
                        
                        if (retryResponse.isSuccessful) {
                            val responseBody = retryResponse.body?.bytes()
                            if (responseBody != null && responseBody.isNotEmpty()) {
                                val responseXml = wbxmlParser.parse(responseBody)
                                return@withContext EasResult.Success(parser(responseXml))
                            } else {
                                return@withContext EasResult.Error("Пустой ответ от сервера после retry")
                            }
                        } else {
                            return@withContext EasResult.Error("HTTP ${retryResponse.code} после Provision")
                        }
                    }
                    is EasResult.Error -> {
                        return@withContext EasResult.Error("Provision failed: ${provResult.message}")
                    }
                }
            }
            
            if (response.isSuccessful) {
                val responseBody = response.body?.bytes()
                if (responseBody != null && responseBody.isNotEmpty()) {
                    val responseXml = wbxmlParser.parse(responseBody)
                    EasResult.Success(parser(responseXml))
                } else {
                    EasResult.Error("Пустой ответ от сервера")
                }
            } else {
                val errorBody = response.body?.string() ?: ""
                EasResult.Error("HTTP ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            // Добавляем информацию о сертификате для отладки SSL ошибок
            val certInfo = if (certificatePath != null) " [cert: $certificatePath]" else ""
            EasResult.Error("Ошибка: ${e.javaClass.simpleName}: ${e.message}$certInfo")
        }
    }
    
    private suspend fun executeRequest(request: Request): Response = suspendCoroutine { cont ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
    
    private fun parseFolderSyncResponse(xml: String): FolderSyncResponse {
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 1
        val syncKey = extractValue(xml, "SyncKey") ?: "0"
        val folders = mutableListOf<EasFolder>()
        
        // Если Status != 1, возвращаем ошибку
        if (status != 1) {
            return FolderSyncResponse(syncKey, folders, status)
        }
        
        // Папки могут быть внутри <Add><Folder>...</Folder></Add> или напрямую <Add>...</Add>
        // Сначала пробуем найти <Folder> внутри <Add>
        val folderPattern = "<Folder>(.*?)</Folder>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        // Проверяем есть ли теги <Folder>
        val hasFolderTags = folderPattern.containsMatchIn(xml)
        
        if (hasFolderTags) {
            // Старый формат: <Add><Folder>...</Folder></Add>
            folderPattern.findAll(xml).forEach { match ->
                val folderXml = match.groupValues[1]
                parseFolder(folderXml)?.let { folders.add(it) }
            }
        } else {
            // Новый формат: данные папки напрямую внутри <Add>
            addPattern.findAll(xml).forEach { match ->
                val folderXml = match.groupValues[1]
                parseFolder(folderXml)?.let { folders.add(it) }
            }
        }
        return FolderSyncResponse(syncKey, folders, status)
    }
    
    private fun parseFolder(folderXml: String): EasFolder? {
        val serverId = extractValue(folderXml, "ServerId") ?: return null
        val displayName = extractValue(folderXml, "DisplayName") ?: ""
        val parentId = extractValue(folderXml, "ParentId") ?: "0"
        val type = extractValue(folderXml, "Type")?.toIntOrNull() ?: 1
        
        return EasFolder(serverId, displayName, parentId, type)
    }
    
    private fun parseSyncResponse(xml: String): SyncResponse {
        val syncKey = extractValue(xml, "SyncKey") ?: "0"
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 1
        val emails = mutableListOf<EasEmail>()
        
        // Проверяем наличие MoreAvailable (пустой тег)
        val moreAvailable = xml.contains("<MoreAvailable/>") || xml.contains("<MoreAvailable>")
        
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val emailXml = match.groupValues[1]
            parseEmail(emailXml)?.let { emails.add(it) }
        }
        
        return SyncResponse(syncKey, status, emails, moreAvailable)
    }
    
    private fun parseEmail(xml: String): EasEmail? {
        val serverId = extractValue(xml, "ServerId") ?: return null
        
        // Парсим вложения
        val attachments = mutableListOf<EasAttachment>()
        val attachmentPattern = "<Attachment>(.*?)</Attachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        attachmentPattern.findAll(xml).forEach { match ->
            val attXml = match.groupValues[1]
            val fileRef = extractValue(attXml, "FileReference")
            val displayName = extractValue(attXml, "DisplayName")
            val contentId = extractValue(attXml, "ContentId")
            val isInline = extractValue(attXml, "IsInline") == "1"
            if (fileRef != null && displayName != null) {
                attachments.add(EasAttachment(
                    fileReference = fileRef,
                    displayName = displayName,
                    contentType = extractValue(attXml, "ContentType") ?: "application/octet-stream",
                    estimatedSize = extractValue(attXml, "EstimatedDataSize")?.toLongOrNull() ?: 0,
                    isInline = isInline,
                    contentId = contentId
                ))
            }
        }
        
        return EasEmail(
            serverId = serverId,
            from = extractValue(xml, "From") ?: "",
            to = extractValue(xml, "To") ?: "",
            cc = extractValue(xml, "Cc") ?: "",
            subject = extractValue(xml, "Subject") ?: "(без темы)",
            dateReceived = extractValue(xml, "DateReceived") ?: "",
            read = extractValue(xml, "Read") == "1",
            importance = extractValue(xml, "Importance")?.toIntOrNull() ?: 1,
            body = extractValue(xml, "Data") ?: "",
            bodyType = extractValue(xml, "Type")?.toIntOrNull() ?: 1,
            attachments = attachments
        )
    }
    
    private fun extractValue(xml: String, tag: String): String? {
        val pattern = "<$tag>(.*?)</$tag>".toRegex(RegexOption.DOT_MATCHES_ALL)
        return pattern.find(xml)?.groupValues?.get(1)
    }
    
    /**
     * Синхронизация контактов из папки Contacts на сервере Exchange
     * Возвращает список контактов
     */
    suspend fun syncContacts(): EasResult<List<GalContact>> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Сначала получаем список папок чтобы найти папку Contacts (type = 9)
        val foldersResult = folderSync("0")
        val contactsFolderId = when (foldersResult) {
            is EasResult.Success -> {
                foldersResult.data.folders.find { it.type == 9 }?.serverId
            }
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        if (contactsFolderId == null) {
            return EasResult.Error("Папка контактов не найдена")
        }
        
        // Шаг 1: Получаем начальный SyncKey для папки контактов
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$contactsFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем контакты
        // Для Exchange 2003 (EAS 2.5) используем Truncation вместо BodyPreference
        // Для Exchange 2007+ (EAS 12.x+) используем BodyPreference
        val syncXml = if (easVersion.startsWith("2.")) {
            // EAS 2.5 (Exchange 2003)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>100</WindowSize>
                            <Options>
                                <Truncation>7</Truncation>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else if (easVersion.startsWith("12")) {
            // EAS 12.x (Exchange 2007/2007 SP1/SP2)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>100</WindowSize>
                            <Options>
                                <airsyncbase:BodyPreference>
                                    <airsyncbase:Type>1</airsyncbase:Type>
                                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                                </airsyncbase:BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else {
            // EAS 14.x+ (Exchange 2010+)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>100</WindowSize>
                            <Options>
                                <airsyncbase:BodyPreference>
                                    <airsyncbase:Type>1</airsyncbase:Type>
                                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                                </airsyncbase:BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        }
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            parseContactsSyncResponse(responseXml)
        }
    }
    
    private fun parseContactsSyncResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        // Парсим Add элементы
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            
            // Извлекаем ApplicationData
            val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataPattern.find(addXml)
            if (dataMatch != null) {
                val dataXml = dataMatch.groupValues[1]
                
                val displayName = extractContactValue(dataXml, "FileAs") 
                    ?: extractContactValue(dataXml, "DisplayName")
                    ?: ""
                val email = extractContactValue(dataXml, "Email1Address") ?: ""
                
                if (displayName.isNotEmpty() || email.isNotEmpty()) {
                    contacts.add(GalContact(
                        displayName = displayName,
                        email = email,
                        firstName = extractContactValue(dataXml, "FirstName") ?: "",
                        lastName = extractContactValue(dataXml, "LastName") ?: "",
                        company = extractContactValue(dataXml, "CompanyName") ?: "",
                        department = extractContactValue(dataXml, "Department") ?: "",
                        jobTitle = extractContactValue(dataXml, "JobTitle") ?: "",
                        phone = extractContactValue(dataXml, "BusinessPhoneNumber") 
                            ?: extractContactValue(dataXml, "HomePhoneNumber") ?: "",
                        mobilePhone = extractContactValue(dataXml, "MobilePhoneNumber") ?: ""
                    ))
                }
            }
        }
        
        return contacts
    }
    
    private fun extractContactValue(xml: String, tag: String): String? {
        // Контакты используют namespace contacts:
        val patterns = listOf(
            "<contacts:$tag>(.*?)</contacts:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска (имя или email). Пустая строка или "*" вернёт все контакты
     * @param maxResults Максимальное количество результатов (по умолчанию 100)
     */
    suspend fun searchGAL(query: String, maxResults: Int = 100): EasResult<List<GalContact>> {
        // Для получения всех контактов используем "*" или пустой запрос
        val searchQuery = if (query.isBlank() || query == "*") "*" else query
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Search xmlns="Search" xmlns:gal="Gal">
                <Store>
                    <Name>GAL</Name>
                    <Query>$searchQuery</Query>
                    <Options>
                        <Range>0-${maxResults - 1}</Range>
                    </Options>
                </Store>
            </Search>
        """.trimIndent()
        
        return executeEasCommand("Search", xml) { responseXml ->
            parseGalSearchResponse(responseXml)
        }
    }
    
    private fun parseGalSearchResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        // Проверяем статус
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 0
        if (status != 1) {
            return emptyList()
        }
        
        // Парсим результаты
        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            // Извлекаем Properties
            val propsPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propsMatch = propsPattern.find(resultXml)
            if (propsMatch != null) {
                val propsXml = propsMatch.groupValues[1]
                
                val displayName = extractGalValue(propsXml, "DisplayName") ?: ""
                val email = extractGalValue(propsXml, "EmailAddress") ?: ""
                
                if (displayName.isNotEmpty() || email.isNotEmpty()) {
                    contacts.add(GalContact(
                        displayName = displayName,
                        email = email,
                        firstName = extractGalValue(propsXml, "FirstName") ?: "",
                        lastName = extractGalValue(propsXml, "LastName") ?: "",
                        company = extractGalValue(propsXml, "Company") ?: "",
                        department = extractGalValue(propsXml, "Office") ?: "", // Office часто содержит отдел
                        jobTitle = extractGalValue(propsXml, "Title") ?: "",
                        phone = extractGalValue(propsXml, "Phone") ?: "",
                        mobilePhone = extractGalValue(propsXml, "MobilePhone") ?: "",
                        alias = extractGalValue(propsXml, "Alias") ?: ""
                    ))
                }
            }
        }
        
        return contacts
    }
    
    private fun extractGalValue(xml: String, tag: String): String? {
        // GAL использует namespace gal:
        val patterns = listOf(
            "<gal:$tag>(.*?)</gal:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
}

sealed class EasResult<out T> {
    data class Success<T>(val data: T) : EasResult<T>()
    data class Error(val message: String) : EasResult<Nothing>()
}

data class FolderSyncResponse(
    val syncKey: String,
    val folders: List<EasFolder>,
    val status: Int = 1
)

data class EasFolder(
    val serverId: String,
    val displayName: String,
    val parentId: String,
    val type: Int
) {
    fun getFolderTypeName(): String = when (type) {
        1 -> "Пользовательская"
        2 -> "Входящие"
        3 -> "Черновики"
        4 -> "Удалённые"
        5 -> "Отправленные"
        6 -> "Исходящие"
        7 -> "Задачи"
        8 -> "Календарь"
        9 -> "Контакты"
        10 -> "Заметки"
        11 -> "Журнал"
        else -> "Другое"
    }
}

data class SyncResponse(
    val syncKey: String,
    val status: Int,
    val emails: List<EasEmail>,
    val moreAvailable: Boolean = false
)

data class EasEmail(
    val serverId: String,
    val from: String,
    val to: String,
    val cc: String = "",
    val subject: String,
    val dateReceived: String,
    val read: Boolean,
    val importance: Int,
    val body: String,
    val bodyType: Int = 1, // 1=text, 2=html
    val attachments: List<EasAttachment> = emptyList()
)

data class EasAttachment(
    val fileReference: String,
    val displayName: String,
    val contentType: String,
    val estimatedSize: Long = 0,
    val isInline: Boolean = false,
    val contentId: String? = null // Для inline изображений (cid:)
)

/**
 * Результат Ping запроса
 */
data class PingResult(
    val status: Int,
    val changedFolders: List<String> // ServerId папок с изменениями
)

/**
 * Контакт из глобальной адресной книги (GAL)
 */
data class GalContact(
    val displayName: String,
    val email: String,
    val firstName: String = "",
    val lastName: String = "",
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val phone: String = "",
    val mobilePhone: String = "",
    val alias: String = ""
)

