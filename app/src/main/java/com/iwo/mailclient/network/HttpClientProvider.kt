package com.iwo.mailclient.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
    
    // Кэш клиентов с пользовательскими сертификатами (по пути к файлу)
    private val certificateClients = ConcurrentHashMap<String, OkHttpClient>()
    
    /**
     * Возвращает OkHttpClient с нужными настройками.
     * Использует derive() для создания клиента с общим connection pool.
     */
    fun getClient(
        acceptAllCerts: Boolean = false,
        connectTimeout: Long = 30,
        readTimeout: Long = 60,
        writeTimeout: Long = 60,
        certificatePath: String? = null
    ): OkHttpClient {
        // Если есть путь к сертификату — используем специальный клиент
        if (certificatePath != null) {
            return getCertificateClient(certificatePath, connectTimeout, readTimeout, writeTimeout)
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
     * Возвращает клиент с пользовательским сертификатом
     */
    private fun getCertificateClient(
        certificatePath: String,
        connectTimeout: Long,
        readTimeout: Long,
        writeTimeout: Long
    ): OkHttpClient {
        // Проверяем кэш
        val cacheKey = "$certificatePath:$connectTimeout:$readTimeout:$writeTimeout"
        certificateClients[cacheKey]?.let { return it }
        
        val client = createCertificateClient(certificatePath, connectTimeout, readTimeout, writeTimeout)
        certificateClients[cacheKey] = client
        return client
    }
    
    /**
     * Очищает кэш клиентов с сертификатами (при удалении аккаунта)
     */
    fun clearCertificateCache(certificatePath: String) {
        certificateClients.keys.filter { it.startsWith(certificatePath) }.forEach {
            certificateClients.remove(it)
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
        certificatePath: String,
        connectTimeout: Long,
        readTimeout: Long,
        writeTimeout: Long
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.CLEARTEXT
            ))
            .hostnameVerifier { _, _ -> true } // Для самоподписанных hostname может не совпадать
        
        try {
            val certTrustManager = createCertificateTrustManager(certificatePath)
            if (certTrustManager != null) {
                val sslContext = try {
                    SSLContext.getInstance("TLS", "Conscrypt")
                } catch (_: Exception) {
                    SSLContext.getInstance("TLS")
                }
                sslContext.init(null, arrayOf(certTrustManager), SecureRandom())
                builder.sslSocketFactory(TlsSocketFactory(sslContext.socketFactory), certTrustManager)
            }
        } catch (_: Exception) {
            // Используем дефолты OkHttp
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
}
