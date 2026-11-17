package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** MAIL-25: поиск ссылок в теле письма — линейный, совпадения прежние (кроме локальной части длиннее 64 по RFC 5321). */
class BodyLinkRegexTest {

    private val legacy = Regex(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)" + "|" +
            "(\\b[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}\\b)" + "|" +
            "(\\+?\\d[\\d\\s\\-()]{6,}\\d)"
    )

    private fun links(re: Regex, s: String) = re.findAll(s).map { it.range to it.value }.toList()

    @Test fun `same links as before on random short texts`() {
        val parts = listOf("a", "B", "1", ".", "-", "_", "%", "+", "@", " ", "ru", "com", "x.", "иван", "\n", ",",
            "https://", "http://e.x/p?q=1", "+7 ", "(495)", "123", "mail")
        val r = Random(7)
        repeat(100_000) {
            val s = buildString { repeat(1 + r.nextInt(20)) { append(parts[r.nextInt(parts.size)]) } }
            assertThat(links(BODY_LINK_REGEX, s)).isEqualTo(links(legacy, s))
        }
    }

    @Test fun `ordinary text keeps addresses, urls and phones`() {
        val text = "Пишите на ivan.petrov@corp.example.ru или https://corp.example/a?b=1, звоните +7 (495) 123-45-67."
        assertThat(BODY_LINK_REGEX.findAll(text).map { it.value }.toList()).containsExactly(
            "ivan.petrov@corp.example.ru", "https://corp.example/a?b=1,", "+7 (495) 123-45-67"
        ).inOrder()
    }

    @Test fun `text without at-sign is scanned in linear time`() {
        for (poison in listOf("a.".repeat(500_000), "a-".repeat(500_000), "x@" + "a-".repeat(200_000))) {
            val t = System.nanoTime()
            BODY_LINK_REGEX.findAll(poison).count()
            assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(3_000L)
        }
    }

    @Test fun `local part longer than 64 characters is not an address (RFC 5321)`() {
        assertThat(BODY_LINK_REGEX.find("a".repeat(70) + "@example.com")?.value).isNull()
        assertThat(BODY_LINK_REGEX.find("a".repeat(64) + "@example.com")?.value).isEqualTo("a".repeat(64) + "@example.com")
    }
}
