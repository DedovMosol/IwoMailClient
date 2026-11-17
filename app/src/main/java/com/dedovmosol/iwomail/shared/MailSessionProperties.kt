package com.dedovmosol.iwomail.shared

import java.util.Properties

/**
 * Свойства сессии JavaMail для входящей почты (IMAP, POP3).
 *
 * Пароль никогда не уходит открытым текстом (UI2-01): без SSL/TLS соединение обязано перейти на TLS командой
 * STARTTLS (RFC 3501 §6.2.1, RFC 2595) до входа — `starttls.required`; сервер без STARTTLS получает отказ, а не
 * пароль. Имя сервера в сертификате проверяется всегда (UI2-02): в JavaMail 1.6 `ssl.checkserveridentity`
 * по умолчанию false, и любой доверенный сертификат на чужое имя принимался бы.
 *
 * «Принимать все сертификаты» — явный выбор пользователя: доверие любому сертификату и без проверки имени
 * (отдельный риск ONB-08), но шифрование и STARTTLS остаются обязательными.
 *
 * Оба префикса (`mail.imap.*` и `mail.imaps.*`): JavaMail читает свойства по имени протокола.
 */
object MailSessionProperties {

    enum class Protocol(val plain: String, val ssl: String) {
        IMAP("imap", "imaps"),
        POP3("pop3", "pop3s")
    }

    private const val TIMEOUT_MS = "15000"

    /** От новых к старым: TLS 1.0 — для Exchange 2007 (Windows Server 2003 знает только его, проверено на сервере). */
    private val WANTED_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")

    /**
     * Exchange 2007 на Windows Server 2003 (192.0.2.10: порты 443, 993, 995) принимает только 3DES. На Android
     * этот набор поддерживается с API 9, но по умолчанию включён лишь на API 9-19 (таблица в справке
     * javax.net.ssl.SSLSocket), поэтому без явного включения IMAP/POP3 с TLS к такому серверу не подключится.
     * Набор добавляется в КОНЕЦ списка: современный сервер выберет сильный шифр. EAS делает то же
     * (OkHttp COMPATIBLE_TLS, HttpClientProvider). Два имени — у провайдеров разные (JSSE «SSL_», BoringSSL «TLS_»).
     */
    private val LEGACY_CIPHERS = listOf("TLS_RSA_WITH_3DES_EDE_CBC_SHA", "SSL_RSA_WITH_3DES_EDE_CBC_SHA")

    /** Протоколы и шифры для `mail.*.ssl.protocols`/`ssl.ciphersuites`: только поддерживаемые провайдером (иначе JavaMail упадёт на setEnabled*). */
    internal data class TlsSettings(val protocols: String, val cipherSuites: String)

    /**
     * [also] — сокеты других фабрик, которыми JavaMail может открыть соединение: значения должны поддерживать ВСЕ
     * (с «Принимать все» — новый контекст «TLS», без — SSLSocketFactory.getDefault(); на API 26-28 это может быть
     * системный провайдер без TLS 1.3, а неизвестное имя в setEnabledCipherSuites — IllegalArgumentException).
     */
    internal fun tlsSettings(context: javax.net.ssl.SSLContext, also: List<javax.net.ssl.SSLSocket> = emptyList()): TlsSettings {
        val supported = context.supportedSSLParameters
        val protocolSets = listOf(supported.protocols.toSet()) + also.map { it.supportedProtocols.toSet() }
        val cipherSets = listOf(supported.cipherSuites.toSet()) + also.map { it.supportedCipherSuites.toSet() }
        val protocols = WANTED_PROTOCOLS.filter { p -> protocolSets.all { p in it } }
        val ciphers = (context.defaultSSLParameters.cipherSuites.toList() + LEGACY_CIPHERS)
            .distinct()
            .filter { c -> cipherSets.all { c in it } }
        return TlsSettings(protocols.joinToString(" "), ciphers.joinToString(" "))
    }

    /**
     * По провайдеру по умолчанию — им JavaMail и открывает TLS (и при `ssl.trust`, и без). Не кэшируется: провайдер
     * мог смениться (Conscrypt ставится первым при старте приложения). null — провайдер недоступен.
     */
    private fun defaultTls(acceptAllCerts: Boolean): TlsSettings? = try {
        // JavaMail открывает TLS разными фабриками: с «Принимать все» (ssl.trust) — MailSSLSocketFactory на новом
        // контексте «TLS», без — SSLSocketFactory.getDefault() (запоминается при первом вызове, мог быть создан до
        // установки Conscrypt). Значения проверяются по той фабрике, которой и откроется соединение.
        val fresh = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) }
        if (acceptAllCerts) {
            tlsSettings(fresh)
        } else {
            (javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket).use { tlsSettings(fresh, listOf(it)) }
        }
    } catch (e: java.security.GeneralSecurityException) {
        null
    } catch (e: java.io.IOException) {
        null
    } catch (e: ClassCastException) {
        null
    }

    fun storeProtocol(protocol: Protocol, useSsl: Boolean): String = if (useSsl) protocol.ssl else protocol.plain

    fun build(protocol: Protocol, host: String, port: Int, useSsl: Boolean, acceptAllCerts: Boolean): Properties =
        Properties().apply {
            put("mail.store.protocol", storeProtocol(protocol, useSsl))
            val tls = defaultTls(acceptAllCerts)
            for (p in listOf(protocol.plain, protocol.ssl)) {
                put("mail.$p.host", host)
                put("mail.$p.port", port.toString())
                put("mail.$p.ssl.enable", useSsl.toString())
                put("mail.$p.starttls.enable", (!useSsl).toString())
                put("mail.$p.starttls.required", (!useSsl).toString())
                put("mail.$p.ssl.checkserveridentity", (!acceptAllCerts).toString())
                if (acceptAllCerts) put("mail.$p.ssl.trust", "*")
                if (tls != null) {
                    if (tls.protocols.isNotEmpty()) put("mail.$p.ssl.protocols", tls.protocols)
                    if (tls.cipherSuites.isNotEmpty()) put("mail.$p.ssl.ciphersuites", tls.cipherSuites)
                }
                put("mail.$p.connectiontimeout", TIMEOUT_MS)
                put("mail.$p.timeout", TIMEOUT_MS)
            }
        }
}
