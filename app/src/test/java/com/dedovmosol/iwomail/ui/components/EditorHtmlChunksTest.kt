package com.dedovmosol.iwomail.ui.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
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
    fun `setHtmlAsync sanitizes and chunks a large body off the caller`() = kotlinx.coroutines.runBlocking {
        val controller = RichTextEditorController()
        val big = "<p>" + "x".repeat(EDITOR_HTML_CHUNK_CHARS * 2) + "</p><script>steal()</script>"

        controller.setHtmlAsync(big)

        assertThat(controller.editorHtmlChunkCount()).isEqualTo(3)
        assertThat(controller.latestHtml).doesNotContain("<script")
        assertThat(controller.isContentUnavailable).isFalse()
    }

    @Test
    fun `a newer setHtml wins over a slower async one`() = kotlinx.coroutines.runBlocking {
        val controller = RichTextEditorController()
        val slow = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            controller.setHtmlAsync("<p>old</p>")
        }
        controller.setHtml("<p>new</p>")
        slow.join()

        assertThat(controller.latestHtml).isEqualTo("<p>new</p>")
        assertThat(controller.editorHtmlChunk(0)).isEqualTo("<p>new</p>")
    }

    @Test
    fun `editor text survives saved state through a file when large`() {
        val dir = java.nio.file.Files.createTempDirectory("editor-state").toFile()
        try {
            val saver = RichTextEditorController.saver(com.dedovmosol.iwomail.ui.utils.createLargeStringSaver(dir, "editor_html"))
            val controller = RichTextEditorController().apply { latestHtml = "<p>" + "y".repeat(300_000) + "</p>" }
            val scope = androidx.compose.runtime.saveable.SaverScope { true }

            val saved = with(saver) { scope.save(controller) }!!
            // В Bundle — только путь к файлу, не 300 000 символов
            assertThat((saved["latestHtml"] as String).length).isLessThan(500)

            val restored = saver.restore(saved)!!
            assertThat(restored.latestHtml).isEqualTo(controller.latestHtml)
        } finally {
            dir.deleteRecursively()
        }
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
