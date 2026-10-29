package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты для EasPatterns (предкомпилированные regex + кэш динамических паттернов).
 */
class EasPatternsTest {

    private fun Regex.firstGroup(input: String): String? =
        find(input)?.groupValues?.getOrNull(1)

    @Test
    fun `EWS_ITEM_ID extracts item id`() {
        assertThat(EasPatterns.EWS_ITEM_ID.firstGroup("<t:ItemId Id=\"abc123\"/>"))
            .isEqualTo("abc123")
    }

    @Test
    fun `EWS_SUBJECT extracts subject including newlines`() {
        assertThat(EasPatterns.EWS_SUBJECT.firstGroup("<t:Subject>Line1\nLine2</t:Subject>"))
            .isEqualTo("Line1\nLine2")
    }

    @Test
    fun `EWS_BODY extracts body ignoring attributes`() {
        assertThat(EasPatterns.EWS_BODY.firstGroup("<t:Body BodyType=\"Text\">content</t:Body>"))
            .isEqualTo("content")
    }

    @Test
    fun `EWS_EMAIL_ADDRESS extracts address`() {
        assertThat(EasPatterns.EWS_EMAIL_ADDRESS.firstGroup("<t:EmailAddress>a@b.com</t:EmailAddress>"))
            .isEqualTo("a@b.com")
    }

    @Test
    fun `EWS_RESPONSE_CODE extracts code`() {
        assertThat(EasPatterns.EWS_RESPONSE_CODE.firstGroup("<m:ResponseCode>NoError</m:ResponseCode>"))
            .isEqualTo("NoError")
    }

    @Test
    fun `MIME_MESSAGE_ID extracts bracketed id`() {
        assertThat(EasPatterns.MIME_MESSAGE_ID.firstGroup("Message-ID: <abc@host>"))
            .isEqualTo("<abc@host>")
    }

    @Test
    fun `BOUNDARY extracts quoted and unquoted boundary`() {
        assertThat(EasPatterns.BOUNDARY.firstGroup("boundary=\"abc123\"")).isEqualTo("abc123")
        assertThat(EasPatterns.BOUNDARY.firstGroup("boundary=abc123")).isEqualTo("abc123")
    }

    @Test
    fun `ITEM_OPS_GLOBAL_STATUS extracts status digit`() {
        assertThat(EasPatterns.ITEM_OPS_GLOBAL_STATUS.firstGroup("<ItemOperations><Status>1</Status>"))
            .isEqualTo("1")
    }

    @Test
    fun `ITEM_OPS_DATA extracts data block`() {
        assertThat(EasPatterns.ITEM_OPS_DATA.firstGroup("<Data>payload</Data>")).isEqualTo("payload")
    }

    // ===================== dynamic tag pattern cache =====================

    @Test
    fun `getTagPattern extracts tag value`() {
        assertThat(EasPatterns.getTagPattern("Foo").firstGroup("<Foo>bar</Foo>")).isEqualTo("bar")
    }

    @Test
    fun `getTagPattern returns cached instance for same tag`() {
        val first = EasPatterns.getTagPattern("CachedTag")
        val second = EasPatterns.getTagPattern("CachedTag")
        assertThat(first).isSameInstanceAs(second)
    }

    @Test
    fun `getTagPatternWithNs builds namespaced pattern and caches it`() {
        val pattern = EasPatterns.getTagPatternWithNs("notes", "Subject")
        assertThat(pattern.firstGroup("<notes:Subject>S</notes:Subject>")).isEqualTo("S")
        assertThat(EasPatterns.getTagPatternWithNs("notes", "Subject")).isSameInstanceAs(pattern)
    }
}
