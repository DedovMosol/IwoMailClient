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
    fun `long subject is cut`() {
        val name = EmlFiles.fileName("a".repeat(500))

        assertThat(name).hasLength(100 + ".eml".length)
    }
}
