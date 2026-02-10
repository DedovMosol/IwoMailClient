package com.dedovmosol.iwomail.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * Singleton провайдер OkHttpClient для предотвращения утечек памяти.
 * Каждый OkHttpClient создаёт connection pool и thread pool,
 * поэтому важно переиспользовать один экземпляр.
 */
object HttpClientProvider {
    
    // Базовый клиент с дефолтными настройками
    private val baseClient: OkHttpClient by lazy {
        createBaseClient()
    }
    
    // Клиент для доверия всем сертификатам (самоподписанные)
    private val trustAllClient: OkHttpClient by lazy {
        createTrustAllClient()
    }
    
    // Кэш клиентов с пользовательскими сертификатами (ограничен 50 записями)
    private val certificateClients = object : LinkedHashMap<String, OkHttpClient>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?): Boolean {
            return size > 50
        }
    }.let { Collections.synchronizedMap(it) }
    
    // Кэш KeyManager для клиентских сертификатов (ограничен 20 записями)
    private val keyManagerCache = object : LinkedHashMap<String, KeyManager?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyManager?>?): Boolean {
            return size > 20
        }
    }.let { Collections.synchronizedMap(it) }
    
    /**
     * Проверяет корректность клиентского сертификата и пароля
     */
    fun validateClientCertificate(certPath: String, password: String): Boolean {
        return try {
            val certFile = File(certPath)
            if (!certFile.exists()) return false
            if (password.isBlank()) return false
            val keyStore = java.security.KeyStore.getInstance("PKCS12")
            val passwordChars = password.toCharArray()
            try {
                certFile.inputStream().use { input ->
                    keyStore.load(input, passwordChars)
                }
            } finally {
                passwordChars.fill('\u0000')
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Возвращает OkHttpClient с нужными настройками.
     * Использует derive() для создания клиента с общим connection pool.
     */
    fun getClient(
        acceptAllCerts: Boolean = false,
        connectTimeout: Long = 30,
        readTimeout: Long = 60,
        writeTimeout: Long = 60,
        certificatePath: String? = null,
        clientCertificatePath: String? = null,
        clientCertificatePassword: String? = null,
        pinnedCertInfo: CertificateInfo? = null,
        accountId: Long? = null
    ): OkHttpClient {
        // Если есть сертификаты — используем специальный клиент
        if (certificatePath != null || clientCertificatePath != null) {
            return getCertificateClient(
                acceptAllCerts,
                certificatePath, 
                clientCertificatePath, 
                clientCertificatePassword,
                connectTimeout, 
                readTimeout, 
                writeTimeout,
                pinnedCertInfo,
                accountId
            )
        }
        
        val base = if (acceptAllCerts) trustAllClient else baseClient
        
        // Если таймауты совпадают с дефолтными — возвращаем базовый клиент
        if (connectTimeout == 30L && readTimeout == 60L && writeTimeout == 60L) {
            return base
        }
        
        // Создаём производный клиент с другими таймаутами
        // newBuilder() переиспользует connection pool и thread pool
        return base.newBuilder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Возвращает клиент для скачивания вложений (увеличенные таймауты)
     */
    fun getAttachmentClient(acceptAllCerts: Boolean = false): OkHttpClient {
        return getClient(
            acceptAllCerts = acceptAllCerts,
            connectTimeout = 120,
            readTimeout = 120,
            writeTimeout = 120
        )
    }
    
    /**
     * Возвращает клиент с пользовательскими сертификатами
     */
    private fun getCertificateClient(
        acceptAllCerts: Boolean,
        certificatePath: String?,
        clientCertificatePath: String?,
        clientCertificatePassword: String?,
        connectTimeout: Long,
        readTimeout: Long,
        writeTimeout: Long,
        pinnedCertInfo: CertificateInfo?,
        accountId: Long?
    ): OkHttpClient {
        // Создаем ключ кэша (включаем хэш пароля для корректного кэширования)
        val cacheKey = buildString {
            append(certificatePath ?: "none")
            append(":")
            append(clientCertificatePath ?: "none")
            append(":")
            append(if (acceptAllCerts) "trust_all" else "verify_host")
            append(":")
            // Используем более надежный хэш для предотвращения коллизий
            append(clientCertificatePassword?.let { 
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                    .take(16) // Первые 16 символов SHA-256
            } ?: "none")
            append(":")
            append(connectTimeout)
            append(":")
            append(readTimeout)
            append(":")
            append(writeTimeout)
            append(":")
            append(pinnedCertInfo?.hash ?: "none")
        }
        
        // Проверяем кэш с защитой от race condition
        // synchronized нужен т.к. getOrPut на synchronizedMap не атомарна
        return synchronized(certificateClients) {
            certificateClients.getOrPut(cacheKey) {
                createCertificateClient(
                    acceptAllCerts,
                    certificatePath,
                    clientCertificatePath,
                    clientCertificatePassword,
                    connectTimeout,
                    readTimeout,
                    writeTimeout,
                    pinnedCertInfo,
                    accountId
                )
            }
        }
    }
    
    /**
     * Очищает кэш клиентов с сертификатами (при удалении аккаунта)
     */
    fun clearCertificateCache(certificatePath: String) {
        synchronized(certificateClients) {
            // Очищаем записи содержащие этот путь (серверный или клиентский сертификат)
            val keysToRemove = certificateClients.keys.filter { it.contains(certificatePath) }
            keysToRemove.forEach { certificateClients.remove(it) }
        }
        
        synchronized(keyManagerCache) {
            // Очищаем также кэш KeyManager
            val keysToRemove = keyManagerCache.keys.filter { it.startsWith(certificatePath) }
            keysToRemove.forEach { keyManagerCache.remove(it) }
        }
    }
    
    /**
     * Очищает весь кэш клиентов с сертификатами
     */
    fun clearAllCertificateCache() {
        synchronized(certificateClients) {
            certificateClients.clear()
        }
        synchronized(keyManagerCache) {
            keyManagerCache.clear()
        }
    }
    
    /**
     * Создает KeyManager для клиентской аутентификации из PKCS12 файла
     * Кэширует результат для экономии энергии
     */
    private fun createClientKeyManager(
        certPath: String,
        password: String
    ): KeyManager? {
        // Создаем ключ кэша с надежным хэшем пароля
        val passwordHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16) // Первые 16 символов SHA-256
        val cacheKey = "$certPath:$passwordHash"
        
        // Проверяем кэш с защитой от race condition
        synchronized(keyManagerCache) {
            keyManagerCache[cacheKey]?.let { return it }
            
            // Создаем KeyManager только если его нет в кэше
            val keyManager = try {
                val certFile = File(certPath)
                if (!certFile.exists()) {
                    android.util.Log.e("HttpClientProvider", "Client certificate file not found: $certPath")
                    return null
                }
                if (password.isBlank()) {
                    android.util.Log.e("HttpClientProvider", "Client certificate password is empty")
                    return null
                }
                
                // Загружаем PKCS12 keystore
                val keyStore = java.security.KeyStore.getInstance("PKCS12")
                val passwordChars = password.toCharArray()
                try {
                    certFile.inputStream().use { input ->
                        try {
                            keyStore.load(input, passwordChars)
                        } catch (e: java.io.IOException) {
                            android.util.Log.e("HttpClientProvider", "Invalid client certificate password or corrupted file")
                            return null
                        }
                    }
                    
                    // Создаем KeyManagerFactory
                    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                        javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
                    )
                    kmf.init(keyStore, passwordChars)
                    
                    // Возвращаем первый KeyManager
                    kmf.keyManagers.firstOrNull()
                } finally {
                    // Очищаем пароль из памяти для безопасности
                    passwordChars.fill('\u0000')
                }
            } catch (e: Exception) {
                android.util.Log.e("HttpClientProvider", "Client cert error: ${e.message}", e)
                return null
            }
            
            // Сохраняем в кэш только успешный результат (если не null)
            if (keyManager != null) {
                keyManagerCache[cacheKey] = keyManager
                android.util.Log.d("HttpClientProvider", "Client certificate loaded successfully")
            }
            return keyManager
        }
    }
    
    private fun createBaseClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.CLEARTEXT
            ))
        
        // Настраиваем TLS с поддержкой старых версий
        try {
            val sslContext = try {
                SSLContext.getInstance("TLS", "Conscrypt")
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            
            val systemTrustManager = createSystemTrustManager()
            sslContext.init(null, arrayOf(systemTrustManager), SecureRandom())
            builder.sslSocketFactory(TlsSocketFactory(sslContext.socketFactory), systemTrustManager)
        } catch (_: Exception) {
            // Используем дефолты OkHttp
        }
        
        return builder.build()
    }
    
    private fun createTrustAllClient(): OkHttpClient {
        // newBuilder() наследует connection pool от baseClient
        val builder = baseClient.newBuilder()
            .hostnameVerifier { _, _ -> true }
        
        try {
            val trustAllManager = createTrustAllManager()
            val sslContext = try {
                SSLContext.getInstance("TLS", "Conscrypt")
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            sslContext.init(null, arrayOf(trustAllManager), SecureRandom())
            builder.sslSocketFactory(TlsSocketFactory(sslContext.socketFactory), trustAllManager)
        } catch (_: Exception) {
            // Используем дефолты OkHttp
        }
        
        return builder.build()
    }
    
    private fun createCertificateClient(
        acceptAllCerts: Boolean,
        certificatePath: String?,
        clientCertificatePath: String?,
        clientCertificatePassword: String?,
        connectTimeout: Long,
        readTimeout: Long,
        writeTimeout: Long,
        pinnedCertInfo: CertificateInfo?,
        accountId: Long?
    ): OkHttpClient {
        // ВАЖНО: newBuilder() наследует connection pool и thread pool от базового клиента.
        // При вытеснении из LRU-кэша пулы не утекают — они общие.
        val builder = baseClient.newBuilder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            // Hostname verification: отключаем только для самоподписанных или пользовательских сертификатов
            .apply {
                if (acceptAllCerts || certificatePath != null) {
                    hostnameVerifier { _, _ -> true }
                }
            }
        
        try {
            // TrustManager для серверного сертификата
            val baseTrustManager = if (certificatePath != null) {
                createCertificateTrustManager(certificatePath)
            } else {
                createSystemTrustManager()
            }
            
            // КРИТИЧНО: Оборачиваем TrustManager для Certificate Pinning
            // Pinning проверяется ПОСЛЕ стандартной валидации (hostname, CA, срок)
            val trustManager = if (pinnedCertInfo != null && accountId != null && baseTrustManager != null) {
                createPinningTrustManager(pinnedCertInfo, accountId, baseTrustManager)
            } else {
                baseTrustManager
            }
            
            // KeyManager для клиентского сертификата
            val keyManager = if (clientCertificatePath != null && clientCertificatePassword != null) {
                createClientKeyManager(clientCertificatePath, clientCertificatePassword)
            } else {
                null
            }
            
            if (clientCertificatePath != null && keyManager == null) {
                throw IllegalArgumentException("CLIENT_CERT_LOAD_FAILED")
            }
            
            // Инициализируем SSLContext
            val sslContext = try {
                SSLContext.getInstance("TLS", "Conscrypt")
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            
            sslContext.init(
                if (keyManager != null) arrayOf(keyManager) else null,  // KeyManager
                if (trustManager != null) arrayOf(trustManager) else null,  // TrustManager
                SecureRandom()
            )
            
            builder.sslSocketFactory(
                TlsSocketFactory(sslContext.socketFactory),
                trustManager ?: createSystemTrustManager()
            )
        } catch (e: Exception) {
            // КРИТИЧНО: CLIENT_CERT_LOAD_FAILED должен пробрасываться наверх —
            // иначе клиент создаётся без mTLS, а пользователь видит
            // непонятную ошибку "SSL handshake failed" вместо "Не удалось загрузить сертификат".
            if (e is IllegalArgumentException && e.message == "CLIENT_CERT_LOAD_FAILED") {
                throw e
            }
            android.util.Log.e("HttpClientProvider", "SSL setup error: ${e.message}")
        }
        
        return builder.build()
    }
    
    private fun createCertificateTrustManager(certPath: String): X509TrustManager? {
        return try {
            val certFile = File(certPath)
            if (!certFile.exists()) return null
            
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val certificate = certFile.inputStream().use { inputStream ->
                certFactory.generateCertificate(inputStream) as X509Certificate
            }
            
            val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("server_cert", certificate)
            
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            
            val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            systemTmf.init(null as java.security.KeyStore?)
            val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            val certTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    systemTm?.checkClientTrusted(chain, authType)
                }
                
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isNotEmpty()) {
                        val serverCert = chain[0]
                        if (serverCert.publicKey == certificate.publicKey) {
                            try { serverCert.checkValidity() } catch (_: Exception) {}
                            return
                        }
                        try {
                            if (serverCert.encoded.contentEquals(certificate.encoded)) return
                        } catch (_: Exception) {}
                        for (cert in chain) {
                            if (cert.publicKey == certificate.publicKey || cert.encoded.contentEquals(certificate.encoded)) return
                        }
                    }
                    try { certTm?.checkServerTrusted(chain, authType); return } catch (_: Exception) {}
                    try { systemTm?.checkServerTrusted(chain, authType); return } catch (_: Exception) {}
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
    
    private fun createTrustAllManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
    
    /**
     * Создает TrustManager с Certificate Pinning (Public Key Pinning)
     * КРИТИЧНО: Проверяет публичный ключ ПОСЛЕ стандартной валидации
     * Это позволяет работать с разными доменами (внутренний/внешний Exchange)
     */
    private fun createPinningTrustManager(
        pinnedCertInfo: CertificateInfo,
        accountId: Long,
        baseTrustManager: X509TrustManager
    ): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                baseTrustManager.checkClientTrusted(chain, authType)
            }
            
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // 1. СНАЧАЛА стандартная проверка (hostname, CA, срок действия)
                baseTrustManager.checkServerTrusted(chain, authType)
                
                // 2. ЗАТЕМ проверяем публичный ключ (независимо от hostname!)
                if (chain.isNotEmpty()) {
                    val serverCert = chain[0]
                    val currentHash = calculateCertHash(serverCert)
                    
                    if (currentHash != pinnedCertInfo.hash) {
                        // Публичный ключ изменился - уведомляем пользователя
                        val currentInfo = extractCertificateInfo(serverCert)
                        CertificateChangeDetector.onCertificateChanged(
                            accountId = accountId,
                            oldCert = pinnedCertInfo,
                            newCert = currentInfo,
                            hostname = "" // hostname не важен для public key pinning
                        )
                        
                        throw java.security.cert.CertificateException("Certificate pin mismatch: expected ${pinnedCertInfo.hash.take(16)}..., got ${currentHash.take(16)}...")
                    }
                }
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return baseTrustManager.acceptedIssuers
            }
        }
    }
    
    private fun createSystemTrustManager(): X509TrustManager {
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as java.security.KeyStore?)
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        
        val userTm: X509TrustManager? = try {
            val userKeyStore = java.security.KeyStore.getInstance("AndroidCAStore")
            userKeyStore.load(null, null)
            val userTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            userTmf.init(userKeyStore)
            userTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (_: Exception) {
            null
        }
        
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                systemTm?.checkClientTrusted(chain, authType)
            }
            
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm?.checkServerTrusted(chain, authType)
                    return
                } catch (_: Exception) {}
                
                try {
                    userTm?.checkServerTrusted(chain, authType)
                    return
                } catch (_: Exception) {}
                
                throw java.security.cert.CertificateException("Trust anchor not found")
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
     * TLS Socket Factory с поддержкой старых версий TLS для Exchange 2007
     */
    private class TlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
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
                    if (!hostname.isNullOrEmpty()) {
                        try {
                            val sslParams = socket.sslParameters
                            sslParams.serverNames = listOf(SNIHostName(hostname))
                            socket.sslParameters = sslParams
                        } catch (_: Exception) {}
                    }
                    
                    val supported = socket.supportedProtocols ?: emptyArray()
                    if (supported.isNotEmpty()) {
                        val toEnable = preferredProtocols.filter { it in supported }
                        if (toEnable.isNotEmpty()) {
                            socket.enabledProtocols = toEnable.toTypedArray()
                        }
                    }
                    
                    socket.supportedCipherSuites?.let {
                        if (it.isNotEmpty()) socket.enabledCipherSuites = it
                    }
                } catch (_: Exception) {}
            }
            return socket
        }
    }
    
    // ========== Certificate Pinning Utilities ==========
    
    /**
     * Вычисляет SHA-256 хэш сертификата для Certificate Pinning
     */
    fun calculateCertHash(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Извлекает информацию о сертификате (CN, организация, даты)
     * Использует RFC2253 формат для парсинга DN
     */
    fun extractCertificateInfo(cert: X509Certificate): CertificateInfo {
        // Используем RFC2253 формат для надёжного парсинга DN
        val subjectDN = cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
        
        var cn = "Unknown"
        var org = "Unknown"
        
        try {
            // Парсим DN вручную с учётом escaped символов
            val components = mutableListOf<Pair<String, String>>()
            var currentKey = ""
            var currentValue = StringBuilder()
            var inValue = false
            var escaped = false
            
            for (i in subjectDN.indices) {
                val char = subjectDN[i]
                
                when {
                    escaped -> {
                        currentValue.append(char)
                        escaped = false
                    }
                    char == '\\' -> {
                        escaped = true
                    }
                    char == '=' && !inValue -> {
                        currentKey = currentValue.toString().trim()
                        currentValue.clear()
                        inValue = true
                    }
                    char == ',' && !escaped -> {
                        if (currentKey.isNotEmpty()) {
                            components.add(currentKey to currentValue.toString().trim())
                        }
                        currentKey = ""
                        currentValue.clear()
                        inValue = false
                    }
                    else -> {
                        currentValue.append(char)
                    }
                }
            }
            
            // Добавляем последний компонент
            if (currentKey.isNotEmpty()) {
                components.add(currentKey to currentValue.toString().trim())
            }
            
            // Извлекаем CN и O
            for ((key, value) in components) {
                when (key.uppercase()) {
                    "CN" -> cn = value
                    "O" -> org = value
                }
            }
        } catch (e: Exception) {
            // Fallback на простой парсинг
            android.util.Log.w("HttpClientProvider", "Failed to parse DN, using fallback: ${e.message}")
            
            val components = subjectDN.split(",").map { it.trim() }
            
            cn = components
                .find { it.startsWith("CN=", ignoreCase = true) }
                ?.substringAfter("=")
                ?.trim() ?: "Unknown"
            
            org = components
                .find { it.startsWith("O=", ignoreCase = true) }
                ?.substringAfter("=")
                ?.trim() ?: "Unknown"
        }
        
        return CertificateInfo(
            hash = calculateCertHash(cert),
            cn = cn,
            organization = org,
            validFrom = cert.notBefore.time,
            validTo = cert.notAfter.time
        )
    }
    
    /**
     * Информация о сертификате для Certificate Pinning
     */
    data class CertificateInfo(
        val hash: String,
        val cn: String,
        val organization: String,
        val validFrom: Long,
        val validTo: Long
    )
    
    /**
     * Singleton для отслеживания изменений сертификатов
     * Используется для показа диалога пользователю при обнаружении нового сертификата
     */
    object CertificateChangeDetector {
        private val changes = ConcurrentHashMap<Long, CertificateChange>()
        
        data class CertificateChange(
            val oldCert: CertificateInfo,
            val newCert: CertificateInfo,
            val hostname: String,
            val timestamp: Long = System.currentTimeMillis()
        )
        
        fun onCertificateChanged(
            accountId: Long,
            oldCert: CertificateInfo,
            newCert: CertificateInfo,
            hostname: String
        ) {
            changes[accountId] = CertificateChange(oldCert, newCert, hostname)
            android.util.Log.w("CertificateChange", """
                ⚠️ Certificate mismatch detected!
                Account ID: $accountId
                Server: $hostname
                Expected hash: ${oldCert.hash}
                Current hash: ${newCert.hash}
                Current CN: ${newCert.cn}
            """.trimIndent())
        }
        
        fun getChange(accountId: Long): CertificateChange? = changes[accountId]
        
        fun clearChange(accountId: Long) {
            changes.remove(accountId)
        }
    }
}
