package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты `stripHeaderCrlf` (N-1) — защита от инъекции MIME/MDN-заголовков.
 *
 * Значение из недоверенного источника (адрес отправителя входящего письма, его Message-ID,
 * адреса получателей) попадает в заголовки через `appendMimeHeaders`, meeting-invite MIME и
 * `buildMdnMessage`. «Голый» CR/LF в теле заголовка (RFC 5322 §2.2) позволил бы внедрить
 * произвольный заголовок (`\r\nBcc: victim@…`). Хелпер вырезает CR/LF — единый источник (DRY)
 * для всех трёх путей сборки заголовков в пакете `eas`.
 *
 * `stripHeaderCrlf` — чистая строковая функция, тестируется в JVM без Android/Robolectric.
 */
class EasMimeHeaderSanitizeTest {

    @Test
    fun `neutralizes CRLF header injection payload`() {
        val payload = "a@b.com\r\nBcc: victim@evil.com"
        val safe = payload.stripHeaderCrlf()
        assertThat(safe).doesNotContain("\r")
        assertThat(safe).doesNotContain("\n")
        // Внедрённый заголовок больше не может стать отдельной строкой.
        assertThat(safe).isEqualTo("a@b.comBcc: victim@evil.com")
    }

    @Test
    fun `strips lone CR and lone LF`() {
        assertThat("a\rb".stripHeaderCrlf()).isEqualTo("ab")
        assertThat("a\nb".stripHeaderCrlf()).isEqualTo("ab")
        assertThat("a\r\nb".stripHeaderCrlf()).isEqualTo("ab")
    }

    @Test
    fun `leaves a normal address unchanged (and returns same instance)`() {
        val addr = "john.doe@example.com"
        // Быстрый путь без CR/LF возвращает исходную строку без аллокации.
        assertThat(addr.stripHeaderCrlf()).isSameInstanceAs(addr)
    }

    @Test
    fun `leaves comma-separated recipients and display names unchanged`() {
        val recipients = "\"Doe, John\" <john@x.com>, jane@y.com"
        assertThat(recipients.stripHeaderCrlf()).isEqualTo(recipients)
    }

    @Test
    fun `empty string stays empty`() {
        assertThat("".stripHeaderCrlf()).isEqualTo("")
    }
}
