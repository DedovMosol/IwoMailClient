package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import com.dedovmosol.iwomail.ui.screens.compose.AttachmentInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * Юнит-тесты пред-проверки бюджета вложений письма (N-2 + CS-1/CS-2): суммирование известных размеров
 * ДО чтения байт, включая inline data:URL-картинки в HTML-теле.
 *
 * `totalAttachmentBytes` / `inlineImageBytes` / `composeAttachmentBudgetBytes` / `MAX_TOTAL_ATTACHMENT_BYTES`
 * объявлены `internal` в `ComposeScreen.kt`, что позволяет протестировать чистую логику без Robolectric.
 * Пока `size > 0`, `totalAttachmentBytes` не обращается к `ContentResolver`, поэтому mock-контекст не
 * задействуется. `inlineImageBytes` — чистая функция над строкой (контекст не нужен).
 */
class ComposeAttachmentSizeTest {

    // Не используется, пока у всех вложений size > 0 (ветка ContentResolver не выполняется).
    private val context = mockk<Context>()

    private fun att(size: Long): AttachmentInfo =
        AttachmentInfo(uri = mockk(), name = "file", size = size, mimeType = "application/octet-stream")

    // ---- totalAttachmentBytes (N-2) ----

    @Test
    fun `sums known attachment sizes without touching ContentResolver`() {
        val total = totalAttachmentBytes(context, listOf(att(3_000_000L), att(5_000_000L)))
        assertThat(total).isEqualTo(8_000_000L)
    }

    @Test
    fun `empty list totals zero`() {
        assertThat(totalAttachmentBytes(context, emptyList())).isEqualTo(0L)
    }

    @Test
    fun `total just under limit is not exceeded, just over is`() {
        val underLimit = totalAttachmentBytes(context, listOf(att(MAX_TOTAL_ATTACHMENT_BYTES)))
        val overLimit = totalAttachmentBytes(context, listOf(att(MAX_TOTAL_ATTACHMENT_BYTES), att(1L)))
        assertThat(underLimit > MAX_TOTAL_ATTACHMENT_BYTES).isFalse()
        assertThat(overLimit > MAX_TOTAL_ATTACHMENT_BYTES).isTrue()
    }

    @Test
    fun `limit constant equals 10 MB (matches attachmentLimitExceeded string)`() {
        assertThat(MAX_TOTAL_ATTACHMENT_BYTES).isEqualTo(10L * 1024 * 1024)
    }

    // ---- inlineImageBytes (CS-2) ----

    @Test
    fun `body without data url images totals zero`() {
        assertThat(inlineImageBytes("<p>hello</p>")).isEqualTo(0L)
        // Обычный (не data:) src не должен учитываться.
        assertThat(inlineImageBytes("""<img src="https://example.com/pic.png">""")).isEqualTo(0L)
    }

    @Test
    fun `single inline image estimates base64 decoded size as len times 3 div 4`() {
        // base64 "AAAA" (4 символа) → 3 байта; "AAAABBBB" (8) → 6 байт.
        assertThat(inlineImageBytes("""<img src="data:image/png;base64,AAAA">""")).isEqualTo(3L)
        assertThat(inlineImageBytes("""<img src="data:image/png;base64,AAAABBBB">""")).isEqualTo(6L)
    }

    @Test
    fun `multiple inline images are summed`() {
        val body = """<img src="data:image/png;base64,AAAA"><img src="data:image/jpeg;base64,AAAABBBB">"""
        assertThat(inlineImageBytes(body)).isEqualTo(9L) // 3 + 6
    }

    // ---- composeAttachmentBudgetBytes (CS-1/CS-2) ----

    @Test
    fun `budget combines file attachments and inline images`() {
        val body = """<img src="data:image/png;base64,AAAA">""" // 3 байта
        val total = composeAttachmentBudgetBytes(context, listOf(att(1_000L)), body)
        assertThat(total).isEqualTo(1_003L)
    }

    @Test
    fun `files under limit but files plus inline exceed limit`() {
        // Файлы почти до лимита + маленькая inline-картинка → суммарно превышает (CS-2:
        // раньше inline не учитывались и обходили лимит при отправке).
        val body = """<img src="data:image/png;base64,AAAA">""" // 3 байта
        val filesUnder = att(MAX_TOTAL_ATTACHMENT_BYTES - 2L)
        val budget = composeAttachmentBudgetBytes(context, listOf(filesUnder), body)
        assertThat(totalAttachmentBytes(context, listOf(filesUnder)) > MAX_TOTAL_ATTACHMENT_BYTES).isFalse()
        assertThat(budget > MAX_TOTAL_ATTACHMENT_BYTES).isTrue()
    }
}
