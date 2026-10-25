package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Подстановка inline-картинок события: только вложения, на Content-ID которых ссылается тело
 * (Exchange 2007 не передаёт IsInline), лимиты как у писем, кэш на диске — повторное открытие без сети.
 * Robolectric — ради настоящего org.json (в android.jar это заглушки).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarInlineImagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun attachments(vararg items: Triple<String, String, Long>): String =
        items.joinToString(",", "[", "]") { (cid, ref, size) ->
            """{"name":"$cid.png","fileReference":"$ref","size":$size,"isInline":false,"contentId":"$cid","method":1}"""
        }

    @Test
    fun `only referenced attachments are loaded and served as data urls`() = runBlocking<Unit> {
        val downloads = mutableListOf<String>()
        val loader = CalendarInlineImages(tmp.root) { _, ref -> downloads += ref; EasResult.Success(png) }
        val json = attachments(Triple("<img1@x>", "REF1", 8L), Triple("other", "REF2", 8L))

        val map = loader.load(7L, """<p><img src="cid:img1@x"></p>""", json)

        assertThat(downloads).containsExactly("REF1")
        assertThat(map.keys).containsExactly("<img1@x>")
        assertThat(map.getValue("<img1@x>")).startsWith("data:image/png;base64,")
    }

    @Test
    fun `second load is served from disk cache without network`() = runBlocking<Unit> {
        var calls = 0
        val loader = CalendarInlineImages(tmp.root) { _, _ -> calls++; EasResult.Success(png) }
        val json = attachments(Triple("a", "REF-A", 8L))
        loader.load(1L, """<img src="cid:a">""", json)
        val second = loader.load(1L, """<img src="cid:a">""", json)
        assertThat(calls).isEqualTo(1)
        assertThat(second).hasSize(1)
    }

    @Test
    fun `oversized, failed and non-image attachments are skipped`() = runBlocking<Unit> {
        val loader = CalendarInlineImages(tmp.root) { _, ref ->
            when (ref) {
                "FAIL" -> EasResult.Error("boom")
                "PDF" -> EasResult.Success("%PDF-1.7".toByteArray())
                else -> EasResult.Success(png)
            }
        }
        val json = attachments(
            Triple("big", "BIG", CalendarInlineImages.MAX_IMAGE_BYTES + 1),
            Triple("fail", "FAIL", 8L),
            Triple("pdf", "PDF", 8L),
            Triple("ok", "OK", 8L)
        )
        val body = """<img src="cid:big"><img src="cid:fail"><img src="cid:pdf"><img src="cid:ok">"""
        assertThat(loader.load(1L, body, json).keys).containsExactly("ok")
    }

    @Test
    fun `body without cid needs no work`() = runBlocking<Unit> {
        val loader = CalendarInlineImages(tmp.root) { _, _ -> error("must not download") }
        assertThat(loader.load(1L, "<p>no images</p>", attachments(Triple("a", "R", 1L)))).isEmpty()
        assertThat(loader.load(1L, """<img src="cid:a">""", "")).isEmpty()
    }
}
