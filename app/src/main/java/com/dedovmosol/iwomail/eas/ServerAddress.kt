package com.dedovmosol.iwomail.eas

/**
 * Адрес сервера Exchange из поля настройки (основного или резервного): `host`, `host:port`,
 * `https://host/путь`, `[IPv6]:port`, IPv6 без скобок. Схема, путь, запрос и userinfo отбрасываются.
 *
 * Порт — явный из адреса, иначе порт аккаунта. Раньше порт из адреса отбрасывался: `10.0.0.5:8443` в
 * поле резервного сервера (своего поля порта у него нет) уходил на порт основного, а подсказка поля
 * основного «IP:порт» не работала. IPv6 в квадратных скобках ломался на первом «:».
 */
internal data class ServerAddress(val host: String, val port: Int) {

    /** Хост для URL: IPv6 — в квадратных скобках (RFC 3986 §3.2.2). */
    val urlHost: String get() = if (host.contains(':')) "[$host]" else host

    /** `host:port` для показа пользователю. */
    val display: String get() = "$urlHost:$port"

    fun baseUrl(useHttps: Boolean): String = "${if (useHttps) "https" else "http"}://$urlHost:$port"

    /** Тот же сервер: имя хоста без учёта регистра (DNS) и тот же порт. */
    fun sameServerAs(other: ServerAddress): Boolean = port == other.port && host.equals(other.host, ignoreCase = true)

    companion object {
        /**
         * null — в адресе нет хоста. Некорректный порт (`host:abc`, `host:0`) заменяется портом по
         * умолчанию — как и раньше, чтобы сохранённые адреса не перестали работать.
         */
        fun parse(raw: String?, defaultPort: Int): ServerAddress? {
            var rest = raw?.trim().orEmpty()
            val schemeEnd = rest.indexOf("://")
            if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
            rest = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').trim()
            if (rest.isEmpty()) return null
            val (host, portText) = when {
                rest.startsWith('[') -> {
                    val end = rest.indexOf(']')
                    if (end < 0) return null
                    rest.substring(1, end) to rest.substring(end + 1).removePrefix(":")
                }
                rest.count { it == ':' } == 1 -> rest.substringBefore(':') to rest.substringAfter(':')
                else -> rest to "" // без порта либо IPv6 без скобок
            }
            val cleanHost = host.trim()
            if (cleanHost.isEmpty()) return null
            val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
            return ServerAddress(cleanHost, port)
        }

        /** Один и тот же сервер с точностью до схемы, пути и регистра (например, резервный = основной). */
        fun isSameServer(a: String?, b: String?, defaultPort: Int): Boolean {
            val first = parse(a, defaultPort) ?: return false
            val second = parse(b, defaultPort) ?: return false
            return first.sameServerAs(second)
        }

        /** Ключ сервера для сравнения «адрес сменился ли» (null — адреса нет). */
        fun key(raw: String?, defaultPort: Int): String? =
            parse(raw, defaultPort)?.let { "${it.host.lowercase()}:${it.port}" }
    }
}
