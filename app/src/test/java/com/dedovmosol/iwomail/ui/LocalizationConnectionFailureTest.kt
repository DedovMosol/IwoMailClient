package com.dedovmosol.iwomail.ui

import com.dedovmosol.iwomail.network.ConnectionFailure
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Сетевые и Provision-ошибки на языке интерфейса; технический текст сохраняется для копирования. */
class LocalizationConnectionFailureTest {

    private val raw = "Provision phase 1 failed: [NET:TLS_VERSION] SSLHandshakeException: Read error: … " +
        "OPENSSL_internal:UNSUPPORTED_PROTOCOL (EAS: 12.1)"

    @Test
    fun `network cause beats the provisioning step and is explained in Russian`() {
        val text = NotificationStrings.localizeError(raw, isRussian = true)

        assertThat(text).startsWith("Сервер не поддерживает версию TLS приложения")
        assertThat(text).contains("Технические подробности:")
        assertThat(text).endsWith(raw)
        assertThat(text).doesNotContain("Ошибка согласования политики безопасности")
    }

    @Test
    fun `english and repeated localization is stable`() {
        val en = NotificationStrings.localizeError(raw, isRussian = false)
        assertThat(en).startsWith("The server does not support the app's TLS version")
        assertThat(en).contains("Technical details:")

        val ru = NotificationStrings.localizeError(raw, isRussian = true)
        // Setup показывает уже локализованный текст из экрана проверки — второй проход не дублирует.
        assertThat(NotificationStrings.localizeError(ru, isRussian = true)).isEqualTo(ru)
    }

    @Test
    fun `every cause has its own title in both languages`() {
        val cyrillic = Regex("[А-Яа-яЁё]")
        val ru = ConnectionFailure.entries.map { NotificationStrings.connectionFailureTitle(it.tag, isRussian = true) }
        val en = ConnectionFailure.entries.map { NotificationStrings.connectionFailureTitle(it.tag, isRussian = false) }

        assertThat(ru).containsNoDuplicates()
        assertThat(en).containsNoDuplicates()
        assertThat(ru.all { it != null && cyrillic.containsMatchIn(it) }).isTrue()
        assertThat(en.all { it != null && !cyrillic.containsMatchIn(it) }).isTrue()
        assertThat(NotificationStrings.connectionFailureTitle("HTTP 500", isRussian = true)).isNull()
    }

    @Test
    fun `provision status codes are explained`() {
        val text = NotificationStrings.localizeError(
            "Provision phase 1 failed: Client cannot fully comply with policy (Status: 139, EAS: 12.1)", isRussian = true
        )
        assertThat(text).startsWith("Ошибка согласования политики безопасности")
        assertThat(text).contains("Устройство не может выполнить все требования политики безопасности")

        val policy = NotificationStrings.localizeError(
            "Provision phase 2 failed: Wrong policy key (Policy Status: 5, EAS: 14.1)", isRussian = true
        )
        assertThat(policy).contains("Ключ политики устарел")
    }

    @Test
    fun `transport status codes are localized`() {
        assertThat(NotificationStrings.localizeError("UNAUTHORIZED", isRussian = true)).startsWith("Неверный логин или пароль (401)")
        assertThat(NotificationStrings.localizeError("ACCESS_DENIED", isRussian = true)).startsWith("Доступ запрещён (403)")
        assertThat(NotificationStrings.localizeError("PROVISION_REQUIRED", isRussian = false)).contains("(449)")
    }
}
