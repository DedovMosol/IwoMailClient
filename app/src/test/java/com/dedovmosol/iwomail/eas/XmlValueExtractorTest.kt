package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты для XmlValueExtractor (унифицированное извлечение значений из XML).
 */
class XmlValueExtractorTest {

    // ===================== extract =====================

    @Test
    fun `extract returns tag value`() {
        assertThat(XmlValueExtractor.extract("<Subject>Hello</Subject>", "Subject"))
            .isEqualTo("Hello")
    }

    @Test
    fun `extract unescapes ampersand entity`() {
        assertThat(XmlValueExtractor.extract("<S>a &amp; b</S>", "S")).isEqualTo("a & b")
    }

    @Test
    fun `extract unescapes angle bracket entity`() {
        assertThat(XmlValueExtractor.extract("<S>1 &lt; 2</S>", "S")).isEqualTo("1 < 2")
    }

    @Test
    fun `extract trims surrounding whitespace`() {
        assertThat(XmlValueExtractor.extract("<S>  hi  </S>", "S")).isEqualTo("hi")
    }

    @Test
    fun `extract returns null when tag absent`() {
        assertThat(XmlValueExtractor.extract("<A>x</A>", "B")).isNull()
    }

    @Test
    fun `extract does not match namespaced tag`() {
        assertThat(XmlValueExtractor.extract("<notes:Subject>N</notes:Subject>", "Subject"))
            .isNull()
    }

    // ===================== extractWithNamespaces =====================

    @Test
    fun `extractWithNamespaces matches namespaced tag`() {
        assertThat(
            XmlValueExtractor.extractWithNamespaces(
                "<notes:Subject>N</notes:Subject>", "Subject", listOf("notes")
            )
        ).isEqualTo("N")
    }

    @Test
    fun `extractWithNamespaces falls back to bare tag`() {
        assertThat(
            XmlValueExtractor.extractWithNamespaces(
                "<Subject>X</Subject>", "Subject", listOf("notes")
            )
        ).isEqualTo("X")
    }

    // ===================== specialized helpers =====================

    @Test
    fun `extractEws matches t namespace`() {
        assertThat(XmlValueExtractor.extractEws("<t:Subject>E</t:Subject>", "Subject"))
            .isEqualTo("E")
    }

    @Test
    fun `extractNote matches notes namespace`() {
        assertThat(XmlValueExtractor.extractNote("<notes:Body>B</notes:Body>", "Body"))
            .isEqualTo("B")
    }

    // ===================== extractAttribute =====================

    @Test
    fun `extractAttribute reads attribute from self-closing namespaced element`() {
        assertThat(XmlValueExtractor.extractAttribute("<t:ItemId Id=\"abc123\"/>", "ItemId", "Id"))
            .isEqualTo("abc123")
    }

    @Test
    fun `extractAttribute reads attribute from bare element`() {
        assertThat(XmlValueExtractor.extractAttribute("<ItemId Id=\"x\">", "ItemId", "Id"))
            .isEqualTo("x")
    }

    @Test
    fun `extractAttribute returns null when attribute absent`() {
        assertThat(XmlValueExtractor.extractAttribute("<ItemId/>", "ItemId", "Id")).isNull()
    }
}
