package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты кодирования Subject по RFC 2047 со сворачиванием (N-3).
 *
 * `chunkByUtf8Bytes` — чистая функция (без Android), проверяется исчерпывающе: границы кусков
 * по октетам UTF-8 и запрет разрыва много-октетных символов / суррогатных пар (RFC 2047 §5 —
 * encoded-word представляет целое число символов).
 *
 * `encodeMimeHeaderText` использует `android.util.Base64`; проверяем только СТРУКТУРУ вывода
 * (число encoded-word'ов, разделитель CRLF+SPACE) — она не зависит от содержимого Base64,
 * поэтому тест устойчив при `unitTests.isReturnDefaultValues = true`.
 */
class EasMimeSubjectEncodingTest {

    // ---- chunkByUtf8Bytes ----

    @Test
    fun `short ascii is a single chunk equal to input`() {
        assertThat(chunkByUtf8Bytes("Hello", 36)).containsExactly("Hello")
    }

    @Test
    fun `long ascii splits into byte-bounded chunks preserving content`() {
        val text = "A".repeat(100)
        val chunks = chunkByUtf8Bytes(text, 36)
        assertThat(chunks.joinToString("")).isEqualTo(text)
        chunks.forEach { assertThat(it.toByteArray(Charsets.UTF_8).size).isAtMost(36) }
        assertThat(chunks).hasSize(3) // 36 + 36 + 28
    }

    @Test
    fun `does not split a multi-byte character across chunks`() {
        val text = "абвг" // 4 кириллических символа × 2 байта = 8 байт
        val chunks = chunkByUtf8Bytes(text, 5)
        assertThat(chunks.joinToString("")).isEqualTo(text)
        chunks.forEach {
            assertThat(it.toByteArray(Charsets.UTF_8).size).isAtMost(5)
            // Кусок — валидная строка без «битых» символов (round-trip совпадает).
            assertThat(String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8)).isEqualTo(it)
        }
        assertThat(chunks).containsExactly("аб", "вг").inOrder()
    }

    @Test
    fun `does not split a surrogate-pair emoji`() {
        val text = "A😀B" // A + 😀(U+1F600, 4 байта, суррогатная пара) + B
        val chunks = chunkByUtf8Bytes(text, 4)
        assertThat(chunks.joinToString("")).isEqualTo(text)
        assertThat(chunks).containsExactly("A", "😀", "B").inOrder()
        chunks.forEach {
            assertThat(String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8)).isEqualTo(it)
        }
    }

    @Test
    fun `exact boundary fills one chunk`() {
        val text = "A".repeat(36)
        assertThat(chunkByUtf8Bytes(text, 36)).containsExactly(text)
    }

    @Test
    fun `empty string yields empty list`() {
        assertThat(chunkByUtf8Bytes("", 36)).isEmpty()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxBytes below the largest UTF-8 char is rejected`() {
        chunkByUtf8Bytes("x", 3)
    }

    // ---- encodeMimeHeaderText (структура) ----

    @Test
    fun `short subject is a single encoded-word without folding`() {
        val out = encodeMimeHeaderText("Hello")
        assertThat(out).doesNotContain("\r\n ")
        assertThat(out).startsWith("=?UTF-8?B?")
        assertThat(out).endsWith("?=")
    }

    @Test
    fun `long subject folds into multiple CRLF-space-separated encoded-words`() {
        val out = encodeMimeHeaderText("A".repeat(100)) // 100 байт → 3 куска
        assertThat(out).contains("\r\n ")
        val words = out.split("\r\n ")
        assertThat(words).hasSize(3)
        words.forEach {
            assertThat(it).startsWith("=?UTF-8?B?")
            assertThat(it).endsWith("?=")
        }
    }
}
