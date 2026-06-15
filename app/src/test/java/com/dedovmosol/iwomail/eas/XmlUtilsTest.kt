package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты для XmlUtils (escape/unescape + извлечение значений тегов).
 */
class XmlUtilsTest {

    // ===================== escape / unescape =====================

    @Test
    fun `escape encodes all xml special characters`() {
        assertThat(XmlUtils.escape("a<b>&\"'")).isEqualTo("a&lt;b&gt;&amp;&quot;&apos;")
    }

    @Test
    fun `unescape decodes all xml entities`() {
        assertThat(XmlUtils.unescape("a&lt;b&gt;&amp;&quot;&apos;")).isEqualTo("a<b>&\"'")
    }

    @Test
    fun `escape then unescape round-trips`() {
        val original = "a<b>&\"' end"
        assertThat(XmlUtils.unescape(XmlUtils.escape(original))).isEqualTo(original)
    }

    @Test
    fun `unescape decodes ampersand last to avoid double decoding`() {
        // &amp;lt; должно стать &lt;, а не < (амперсанд декодируется последним)
        assertThat(XmlUtils.unescape("&amp;lt;")).isEqualTo("&lt;")
    }

    // ===================== extractTagValue =====================

    @Test
    fun `extractTagValue returns simple content`() {
        assertThat(XmlUtils.extractTagValue("<a>hello</a>", "a")).isEqualTo("hello")
    }

    @Test
    fun `extractTagValue returns empty string for self-closing tag`() {
        assertThat(XmlUtils.extractTagValue("<a/>", "a")).isEqualTo("")
    }

    @Test
    fun `extractTagValue handles nested same-name tags by depth`() {
        assertThat(XmlUtils.extractTagValue("<a><a>inner</a></a>", "a"))
            .isEqualTo("<a>inner</a>")
    }

    @Test
    fun `extractTagValue matches tag ignoring namespace prefix`() {
        assertThat(XmlUtils.extractTagValue("<t:Subject>Hi</t:Subject>", "Subject"))
            .isEqualTo("Hi")
    }

    @Test
    fun `extractTagValue returns null when tag is absent`() {
        assertThat(XmlUtils.extractTagValue("<a>x</a>", "b")).isNull()
    }

    // ===================== extractTopLevelBlocks =====================

    @Test
    fun `extractTopLevelBlocks returns all sibling blocks`() {
        assertThat(XmlUtils.extractTopLevelBlocks("<i>1</i><i>2</i>", "i"))
            .containsExactly("1", "2").inOrder()
    }

    @Test
    fun `extractTopLevelBlocks treats nested tag as single top-level block`() {
        val blocks = XmlUtils.extractTopLevelBlocks("<i><i>x</i></i>", "i")
        assertThat(blocks).containsExactly("<i>x</i>")
    }

    @Test
    fun `extractTopLevelBlocks returns empty list when tag absent`() {
        assertThat(XmlUtils.extractTopLevelBlocks("<a>x</a>", "b")).isEmpty()
    }
}
