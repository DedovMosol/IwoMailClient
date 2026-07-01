package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import com.dedovmosol.iwomail.ui.screens.compose.AttachmentInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * Юнит-тесты пред-проверки размера вложений (N-2): суммирование известных размеров ДО чтения байт.
 *
 * `totalAttachmentBytes`/`MAX_TOTAL_ATTACHMENT_BYTES` объявлены `internal` в `ComposeScreen.kt`, что
 * позволяет протестировать чистую логику суммирования без Robolectric. Пока `size > 0`, функция не
 * обращается к `ContentResolver`, поэтому mock-контекст не задействуется.
 */
class ComposeAttachmentSizeTest {

    // Не используется, пока у всех вложений size > 0 (ветка ContentResolver не выполняется).
    private val context = mockk<Context>()

    private fun att(size: Long): AttachmentInfo =
        AttachmentInfo(uri = mockk(), name = "file", size = size, mimeType = "application/octet-stream")

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
}
