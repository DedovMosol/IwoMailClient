package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Адрес сервера из поля настройки: хост, явный порт, IPv6; базовый URL запросов и TCP-проба. */
class ServerAddressTest {

    @Test
    fun `explicit port in the address wins over the account port`() {
        assertThat(ServerAddress.parse("10.0.0.5:8443", 443)).isEqualTo(ServerAddress("10.0.0.5", 8443))
        assertThat(EasClient.normalizeUrl("10.0.0.5:8443", 443, true)).isEqualTo("https://10.0.0.5:8443")
        assertThat(EasClient.normalizeUrl("mail.corp.local", 443, true)).isEqualTo("https://mail.corp.local:443")
    }

    @Test
    fun `scheme, path, query and user info are dropped`() {
        assertThat(ServerAddress.parse("https://mail.corp.local/Microsoft-Server-ActiveSync?x=1", 443))
            .isEqualTo(ServerAddress("mail.corp.local", 443))
        assertThat(ServerAddress.parse("  HTTPS://user@Mail.Corp.Local:444/owa  ", 443))
            .isEqualTo(ServerAddress("Mail.Corp.Local", 444))
    }

    @Test
    fun `IPv6 literals`() {
        assertThat(ServerAddress.parse("[fe80::1]:8443", 443)).isEqualTo(ServerAddress("fe80::1", 8443))
        assertThat(ServerAddress.parse("[fe80::1]", 443)).isEqualTo(ServerAddress("fe80::1", 443))
        assertThat(ServerAddress.parse("fe80::1", 443)).isEqualTo(ServerAddress("fe80::1", 443))
        assertThat(EasClient.normalizeUrl("[fe80::1]:8443", 443, true)).isEqualTo("https://[fe80::1]:8443")
    }

    @Test
    fun `invalid port keeps the account port, empty host is no address`() {
        assertThat(ServerAddress.parse("mail.corp:abc", 443)).isEqualTo(ServerAddress("mail.corp", 443))
        assertThat(ServerAddress.parse("mail.corp:0", 443)).isEqualTo(ServerAddress("mail.corp", 443))
        assertThat(ServerAddress.parse("mail.corp:", 443)).isEqualTo(ServerAddress("mail.corp", 443))
        assertThat(ServerAddress.parse("https://", 443)).isNull()
        assertThat(ServerAddress.parse(null, 443)).isNull()
        assertThat(ServerAddress.parse("[fe80::1", 443)).isNull()
    }

    @Test
    fun `same server ignores scheme, path and case but not the port`() {
        assertThat(ServerAddress.isSameServer("https://MAIL.corp/owa", "mail.corp", 443)).isTrue()
        assertThat(ServerAddress.isSameServer("mail.corp:443", "mail.corp", 443)).isTrue()
        assertThat(ServerAddress.isSameServer("mail.corp:8443", "mail.corp", 443)).isFalse()
        assertThat(ServerAddress.isSameServer("", "mail.corp", 443)).isFalse()
        assertThat(ServerAddress.key("HTTPS://Mail.Corp/x", 443)).isEqualTo("mail.corp:443")
        assertThat(ServerAddress.key(null, 443)).isNull()
    }

    @Test
    fun `probe connects to the port written in the address`() = runBlocking<Unit> {
        // Очередь на несколько соединений: пробы не принимаются сервером и остаются в backlog.
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            // Порт аккаунта — заведомо закрытый 1: проба обязана пойти на порт из адреса.
            assertThat(EasClient.probeHost("127.0.0.1:${server.localPort}", 1)).isTrue()
            assertThat(EasClient.probeHost("127.0.0.1", server.localPort)).isTrue()
        }
        assertThat(EasClient.probeHost("", 443)).isFalse()
    }
}
