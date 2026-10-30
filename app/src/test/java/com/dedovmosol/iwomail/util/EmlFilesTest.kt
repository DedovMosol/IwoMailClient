package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmlFilesTest {

    @Test
    fun `subject becomes a safe file name`() {
        assertThat(EmlFiles.fileName("Отчёт: Q1/Q2 <итог>?")).isEqualTo("Отчёт_ Q1_Q2 _итог__.eml")
        assertThat(EmlFiles.fileName("  Re:\tпривет\n ")).isEqualTo("Re_ привет.eml")
    }

    @Test
    fun `empty or dots-only subject gets a default name`() {
        assertThat(EmlFiles.fileName("")).isEqualTo("email.eml")
        assertThat(EmlFiles.fileName(" ... ")).isEqualTo("email.eml")
    }

    @Test
    fun `document is written with truncation when the provider supports it`() {
        val modes = mutableListOf<String>()
        val out = java.io.ByteArrayOutputStream()

        val ok = EmlFiles.write(byteArrayOf(1, 2)) { mode -> modes += mode; out }

        assertThat(ok).isTrue()
        assertThat(modes).containsExactly("wt")
        assertThat(out.toByteArray().toList()).containsExactly(1.toByte(), 2.toByte()).inOrder()
    }

    @Test
    fun `provider without truncate mode gets plain write`() {
        // Облачные поставщики SAF отвечают на "wt" FileNotFoundException или IllegalArgumentException
        for (failure in listOf(java.io.FileNotFoundException("wt"), IllegalArgumentException("Bad mode"))) {
            val modes = mutableListOf<String>()
            val out = java.io.ByteArrayOutputStream()

            val ok = EmlFiles.write(byteArrayOf(7)) { mode ->
                modes += mode
                if (mode == "wt") throw failure else out
            }

            assertThat(ok).isTrue()
            assertThat(modes).containsExactly("wt", "w").inOrder()
            assertThat(out.toByteArray().toList()).containsExactly(7.toByte())
        }
    }

    @Test
    fun `provider that opens nothing reports failure`() {
        assertThat(EmlFiles.write(byteArrayOf(1)) { null }).isFalse()
    }

    @Test
    fun `an emoji on the cut is not split in half`() {
        val name = EmlFiles.fileName("a".repeat(99) + "\uD83D\uDE00" + "tail")

        assertThat(name).isEqualTo("a".repeat(99) + ".eml")
    }

    @Test
    fun `long subject is cut`() {
        val name = EmlFiles.fileName("a".repeat(500))

        assertThat(name).hasLength(100 + ".eml".length)
    }
}
