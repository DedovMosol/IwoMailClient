package com.dedovmosol.iwomail.network

import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.security.auth.x500.X500Principal

/**
 * RFC 6125 hostname verifier для режима импортированного серверного сертификата (L-5).
 *
 * Почему не готовые варианты (всё проверено эмпирически, см. `HttpClientProviderL5Test`):
 *
 * 1. Внутренний верификатор OkHttp (`okhttp3.internal.tls.*`) — нестабильный внутренний
 *    класс, не часть публичного контракта библиотеки.
 * 2. Дефолтный верификатор билдера OkHttp — по результатам тестов с реальными
 *    X.509-сертификатами НЕ откатывается на CN, когда в сертификате нет SAN. Самоподписанные
 *    сертификаты Exchange 2007 SP1/SP2 часто выпускаются только с `CN=хост` без SAN —
 *    все такие подключения ошибочно отклонялись бы.
 * 3. Стандартный `javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()` в окружении
 *    без инициализированного https-обработчика является заглушкой, отклоняющей ВСЕ имена
 *    (проверено на JDK 23: даже точное совпадение `CN=хост` даёт `false`).
 *
 * Правила проверки (публичные JCA-апи, без внутренних классов):
 *
 * - имя-литерал IP сверяется только с `iPAddress`-записями SAN, точное совпадение
 *   (сравнение дополнительно нормализуется через [InetAddress] для IPv6-форм),
 *   иначе имя сверяется с dnsName-записями;
 * - если в сертификате есть `dNSName`-записи — используется ТОЛЬКО SAN
 *   (RFC 6125 §6.4.4: при наличии SAN CN не рассматривается);
 * - если `dNSName`-записей нет — откат на CN (RFC 6125 §6.4.4, разрешение для
 *   совместимости со старыми удостоверяющими центрами; якорем доверия здесь служит
 *   сам импортированный пользователем сертификат, а сверка имени защищает от подмены
 *   хоста — именно это закрывает MITM-окно из находки L-5);
 * - wildcard допускается только целиком в левом ярлыке (`*.example.com`) — частичные
 *   шаблоны (`f*o.example.com`) и wildcard вне левого ярлыка отклоняются
 *   (консервативная трактовка RFC 6125 §6.4.3);
 * - сравнение без учёта регистра (RFC 4343), завершающая точка FQDN игнорируется.
 *
 * Поведение покрыто поведенческими тестами с реальными сертификатами
 * (`app/src/test/resources/certs/`).
 */
object Rfc6125HostnameVerifier : HostnameVerifier {

    private const val SAN_TYPE_DNS = 2
    private const val SAN_TYPE_IP = 7

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val cert = try {
            session.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            // SSLPeerUnverifiedException и пр. — сертификата нет, имя сверить нельзя.
            null
        } ?: return false
        return matchesHostname(hostname, cert)
    }

    /**
     * Сверяет ожидаемое имя хоста с именами сертификата.
     * Публичный вход для модульных тестов без TLS-сессии.
     */
    fun matchesHostname(hostname: String, cert: X509Certificate): Boolean {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return false

        val dnsNames = mutableListOf<String>()
        val ipNames = mutableListOf<String>()
        try {
            cert.subjectAlternativeNames?.forEach { entry ->
                if (entry.size < 2) return@forEach
                when (entry[0] as? Int) {
                    SAN_TYPE_DNS -> (entry[1] as? String)
                        ?.lowercase()?.trimEnd('.')
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { dnsNames += it }
                    SAN_TYPE_IP -> (entry[1] as? String)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { ipNames += it }
                }
            }
        } catch (_: Exception) {
            // Некорректный SAN-экстеншен трактуем как отсутствие имён (fail-closed).
        }

        return when {
            looksLikeIpAddress(host) -> ipNames.any { ipMatches(host, it) }
            dnsNames.isNotEmpty() -> dnsNames.any { dnsMatches(host, it) }
            else -> {
                // Нет SAN dnsName: откат на CN (см. KDoc — совместимость с
                // самоподписанными сертификатами Exchange 2007 без SAN).
                val cn = extractCommonName(cert)?.lowercase()?.trimEnd('.')
                cn != null && dnsMatches(host, cn)
            }
        }
    }

    /** DNS-имя против шаблона; `*` разрешён только целиком в левом ярлыке. */
    private fun dnsMatches(host: String, template: String): Boolean {
        if (!template.contains('*')) return host == template
        if (!template.startsWith("*.")) return false
        val suffix = template.substring(1) // ".example.com"
        if (!host.endsWith(suffix)) return false
        val leftmost = host.removeSuffix(suffix)
        // Ярлык непуст и не содержит точек — иначе покрывались бы вложенные поддомены.
        return leftmost.isNotEmpty() && !leftmost.contains('.')
    }

    /** Точное совпадение IP; [InetAddress] нормализует IPv6-формы. */
    private fun ipMatches(host: String, sanIp: String): Boolean {
        if (host.equals(sanIp, ignoreCase = true)) return true
        return try {
            InetAddress.getByName(host) == InetAddress.getByName(sanIp)
        } catch (_: Exception) {
            false
        }
    }

    /** Строгая, но достаточная эвристика: только цифры/точки (IPv4) либо содержит `:` (IPv6). */
    private fun looksLikeIpAddress(name: String): Boolean =
        name.contains(':') || (name.isNotEmpty() && name.all { it.isDigit() || it == '.' })

    /** CN субъекта через общий парсер RFC 2253 (DRY с [HttpClientProvider.extractCertificateInfo]). */
    private fun extractCommonName(cert: X509Certificate): String? {
        val dn = cert.subjectX500Principal.getName(X500Principal.RFC2253)
        return findDnComponentValue(parseRfc2253Components(dn), "CN")
    }
}

/**
 * Разбирает DN в формате RFC 2253 на пары ключ=значение с учётом экранированных символов.
 * Общая функция для [HttpClientProvider.extractCertificateInfo] и [Rfc6125HostnameVerifier] (DRY).
 */
internal fun parseRfc2253Components(subjectDN: String): List<Pair<String, String>> {
    val components = mutableListOf<Pair<String, String>>()
    var currentKey = ""
    val currentValue = StringBuilder()
    var inValue = false
    var escaped = false

    for (char in subjectDN) {
        when {
            escaped -> {
                currentValue.append(char)
                escaped = false
            }
            char == '\\' -> escaped = true
            char == '=' && !inValue -> {
                currentKey = currentValue.toString().trim()
                currentValue.clear()
                inValue = true
            }
            char == ',' -> {
                if (currentKey.isNotEmpty()) {
                    components.add(currentKey to currentValue.toString().trim())
                }
                currentKey = ""
                currentValue.clear()
                inValue = false
            }
            else -> currentValue.append(char)
        }
    }

    if (currentKey.isNotEmpty()) {
        components.add(currentKey to currentValue.toString().trim())
    }
    return components
}

/** Ищет значение компонента DN по ключу (без учёта регистра); при повторах берёт последнее. */
internal fun findDnComponentValue(components: List<Pair<String, String>>, key: String): String? =
    components.lastOrNull { it.first.equals(key, ignoreCase = true) }?.second
