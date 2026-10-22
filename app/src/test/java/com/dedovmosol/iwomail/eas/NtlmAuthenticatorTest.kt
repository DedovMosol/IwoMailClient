package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * NET-4: соответствие NTLMv2 NTOWFv2 спецификации MS-NLMP + регрессия регистра домена.
 *
 * Эталонный вектор [MS-NLMP] 4.2.4.1.1 (NTOWFv2):
 *   User="User", Domain="Domain", Passwd="Password"
 *   => 0c 86 8a 40 3b fd 7a 93 a3 00 1e f2 2e f0 2e 3f
 * https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-nlmp/7795bd0e-fd5e-43ec-bd9c-994704d8ee26
 *
 * По спецификации в верхний регистр переводится ТОЛЬКО username; домен (UserDom) — как есть.
 * До фикса NET-4 код поднимал регистр и домена → неверный ключ HMAC и провал EWS-NTLM
 * на серверах со строчным/смешанным регистром домена.
 *
 * Прим.: JDK/Android не имеют провайдера "MD4", поэтому createNtlmHash уходит в hand-rolled
 * md4Hash — этот тест транзитивно проверяет и его против эталонного вектора.
 */
class NtlmAuthenticatorTest {

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private val auth = NtlmAuthenticator(domain = "Domain", username = "User", password = "Password")

    private val reference = "0c868a403bfd7a93a3001ef22ef02e3f"

    @Test
    fun ntowfv2_matchesMsNlmpReferenceVector() {
        val ntlmHash = auth.createNtlmHash("Password")
        val ntowfv2 = auth.createNtlmv2Hash(ntlmHash, "User", "Domain")
        assertThat(hex(ntowfv2)).isEqualTo(reference)
    }

    @Test
    fun username_isUppercased_caseInsensitive() {
        val h = auth.createNtlmHash("Password")
        // "user" и "USER" дают один NTOWFv2 (username поднимается в верхний регистр).
        assertThat(hex(auth.createNtlmv2Hash(h, "user", "Domain")))
            .isEqualTo(hex(auth.createNtlmv2Hash(h, "USER", "Domain")))
    }

    @Test
    fun domain_caseIsPreserved_net4Regression() {
        val h = auth.createNtlmHash("Password")
        // Домен чувствителен к регистру: эталон совпадает только с "Domain" как есть,
        // а "DOMAIN" даёт другой хеш (это и ломал старый код).
        assertThat(hex(auth.createNtlmv2Hash(h, "User", "Domain"))).isEqualTo(reference)
        assertThat(hex(auth.createNtlmv2Hash(h, "User", "DOMAIN"))).isNotEqualTo(reference)
    }

    /**
     * NET-4 (wiring): реальное сообщение AUTHENTICATE (Type 3) должно записывать DomainName
     * КАК ЕСТЬ — байт-в-байт тем же доменом, что уходит в NTOWFv2 (createNtlmv2Hash).
     *
     * По [MS-NLMP] 3.2.5.1.2 сервер/DC пересчитывает ключ по UserName/DomainName ИЗ сообщения,
     * поэтому расхождение регистра между полем сообщения и хешем ломает NTLM. Этот тест строит
     * настоящее сообщение через createType3Message и парсит security buffer домена, а не
     * проверяет извлечённый хелпер в отрыве от сборки сообщения.
     */
    @Test
    fun type3Message_writesDomainFieldAsIs_net4Wiring() {
        val a = NtlmAuthenticator(domain = "CorpDom", username = "Alice", password = "Secret1")
        val type2 = ByteArray(32) // минимально валидный CHALLENGE: serverChallenge = байты 24..31
        val msg = a.createType3Message(type2)

        // Domain security buffer в Type 3: len(2)@28, offset(4)@32; полезная нагрузка в UTF-16LE.
        val domLen = le16(msg, 28)
        val domOff = le32(msg, 32)
        val domainInMessage = String(msg.copyOfRange(domOff, domOff + domLen), Charsets.UTF_16LE)

        // Совпадает с доменом конструктора КАК ЕСТЬ — тем же, что идёт в NTOWFv2 → клиент
        // самосогласован, DC получит тот же HMAC-ключ.
        assertThat(domainInMessage).isEqualTo("CorpDom")
        // Регрессия: старый код писал бы "CORPDOM" (uppercase) и расходился с as-is доменом хеша.
        assertThat(domainInMessage).isNotEqualTo("CORPDOM")
    }

    /**
     * NET-4 (wiring): сообщение NEGOTIATE (Type 1) тоже пишет домен КАК ЕСТЬ — для
     * единообразия регистра с Type 3 (само поле сервером игнорируется без OEM_DOMAIN_SUPPLIED).
     */
    @Test
    fun type1Message_writesDomainFieldAsIs_net4Wiring() {
        val a = NtlmAuthenticator(domain = "CorpDom", username = "Alice", password = "Secret1")
        val msg = a.createType1Message()

        // Domain security buffer в Type 1: len(2)@16, offset(4)@20; полезная нагрузка в OEM/US-ASCII.
        val domLen = le16(msg, 16)
        val domOff = le32(msg, 20)
        val domainInMessage = String(msg.copyOfRange(domOff, domOff + domLen), Charsets.US_ASCII)

        assertThat(domainInMessage).isEqualTo("CorpDom")
        assertThat(domainInMessage).isNotEqualTo("CORPDOM")
    }
}
