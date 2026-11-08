package com.dedovmosol.iwomail.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorHtmlChunksTest {

    @Test
    fun `empty html is one empty chunk`() {
        assertThat(htmlToEditorChunks("").toList()).containsExactly("")
    }

    @Test
    fun `short html stays a single chunk`() {
        val html = "<p>Hello   Outlook</p>"
        assertThat(htmlToEditorChunks(html, chunkChars = 10_000).toList()).containsExactly(html)
    }

    @Test
    fun `long html is split without losing characters including line separators`() {
        val html = "a".repeat(50) + "  " + "b".repeat(50)
        val chunks = htmlToEditorChunks(html, chunkChars = 40)
        assertThat(chunks.joinToString("")).isEqualTo(html)
        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks.all { it.length <= 40 }).isTrue()
    }

    @Test
    fun `setHtml sanitizes then stores chunks readable from binder-style getters`() {
        val controller = RichTextEditorController()
        controller.setHtml("<p>ok</p><script>steal()</script>")
        val rebuilt = (0 until controller.editorHtmlChunkCount()).joinToString("") {
            controller.editorHtmlChunk(it)
        }
        assertThat(rebuilt).contains("<p>ok</p>")
        assertThat(rebuilt).doesNotContain("<script")
        assertThat(controller.latestHtml).isEqualTo(rebuilt)
    }

    @Test
    fun `out of range chunk is empty`() {
        val controller = RichTextEditorController()
        controller.setHtml("x")
        assertThat(controller.editorHtmlChunk(-1)).isEmpty()
        assertThat(controller.editorHtmlChunk(99)).isEmpty()
    }

    @Test
    fun `insertImage stores mime and base64 chunks without a JS literal`() {
        val controller = RichTextEditorController()
        controller.insertImage("QUJDRA==", "image/png; charset=x")
        assertThat(controller.editorImageMime()).isEqualTo("image/png")
        val rebuilt = (0 until controller.editorImageChunkCount()).joinToString("") {
            controller.editorImageChunk(it)
        }
        assertThat(rebuilt).isEqualTo("QUJDRA==")
    }

    @Test
    fun `setHtml bumps generation so a stale JS read can retry`() {
        val controller = RichTextEditorController()
        val before = controller.editorHtmlGeneration()
        controller.setHtml("<p>a</p>")
        val afterFirst = controller.editorHtmlGeneration()
        controller.setHtml("<p>b</p>")
        assertThat(afterFirst).isGreaterThan(before)
        assertThat(controller.editorHtmlGeneration()).isGreaterThan(afterFirst)
        assertThat(controller.editorHtmlChunk(0)).contains("<p>b</p>")
    }
}
