package com.dedovmosol.iwomail.ui.screens.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Описание события: сервер → rich-редактор → сервер (cid ↔ data URL, inline-вложения). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventBodyEditingTest {

    private val pngB64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9))
    private val dataUrl = "data:image/png;base64,$pngB64"
    private val serverBody = """<div>Agenda</div><img src="cid:logo@x">"""
    private val attachmentsJson =
        """[{"name":"logo.png","fileReference":"5:1:0","size":5,"isInline":true,"contentId":"<logo@x>"},""" +
            """{"name":"doc.pdf","fileReference":"5:1:1","size":10,"isInline":false,"contentId":""}]"""

    @Test
    fun `plain text body is escaped with line breaks`() {
        val editor = EventBodyEditing.forEditor("1 < 2\r\nnext", emptyMap())
        assertThat(editor.html).isEqualTo("1 &lt; 2<br>next")
        assertThat(editor.knownCids).isEmpty()
    }

    @Test
    fun `html body gets data URLs and unchanged round trip restores the cid`() {
        val editor = EventBodyEditing.forEditor(serverBody, mapOf("<logo@x>" to dataUrl))
        assertThat(editor.html).isEqualTo("""<div>Agenda</div><img src="$dataUrl">""")

        val encoded = EventBodyEditing.encode(editor.html, editor, serverBody, attachmentsJson)

        assertThat(encoded.body).isEqualTo(serverBody)
        assertThat(encoded.newInlineImages).isEmpty()
        assertThat(encoded.removedInlineRefs).isEmpty()
    }

    @Test
    fun `image removed from the body deletes only its inline attachment`() {
        val editor = EventBodyEditing.forEditor(serverBody, mapOf("<logo@x>" to dataUrl))

        val encoded = EventBodyEditing.encode("<div>Agenda</div>", editor, serverBody, attachmentsJson)

        assertThat(encoded.removedInlineRefs).containsExactly("5:1:0")
    }

    @Test
    fun `new image becomes an inline attachment with a content id`() {
        val editor = EventBodyEditing.forEditor("", emptyMap())

        val encoded = EventBodyEditing.encode("""<div>x</div><img src="$dataUrl">""", editor, "", "")

        val image = encoded.newInlineImages.single()
        assertThat(image.isInline).isTrue()
        assertThat(image.name).isEqualTo("image_1.png")
        assertThat(encoded.body).isEqualTo("""<div>x</div><img src="cid:${image.contentId}">""")
    }

    @Test
    fun `empty editor gives an empty body and bare text is wrapped in a div`() {
        val editor = EventBodyEditing.forEditor("", emptyMap())
        assertThat(EventBodyEditing.encode("<div><br></div>", editor, "", "").body).isEmpty()
        assertThat(EventBodyEditing.encode("Tom &amp; Jerry", editor, "", "").body).isEqualTo("<div>Tom &amp; Jerry</div>")
    }
}
