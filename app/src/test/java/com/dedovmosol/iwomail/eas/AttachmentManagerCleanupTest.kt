package com.dedovmosol.iwomail.eas

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Юнит-тест возрастной очистки temp-каталогов вложений (CS-11).
 *
 * Тестируем публичный `AttachmentManager.cleanupOldAttachments` через РЕАЛЬНУЮ временную ФС
 * (`TemporaryFolder`) + mock `Context.filesDir` — без Robolectric (обычный File I/O в JVM).
 * Проверяем, что очистка распространяется на все temp-каталоги (attachments + reply/forward/draft),
 * удаляет файлы старше порога (7 дней) и НЕ трогает свежие (защита активной сессии написания).
 */
class AttachmentManagerCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val eightDaysMs = 8L * 24 * 60 * 60 * 1000

    private fun fileIn(dirName: String, fileName: String, ageMs: Long): File {
        val dir = File(tempFolder.root, dirName).apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(ByteArray(10))
        f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    @Test
    fun `removes files older than 7 days across all temp dirs and keeps recent`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root

        val oldCache = fileIn("attachments", "old.dat", eightDaysMs)
        val oldReply = fileIn("reply_attachments", "old.dat", eightDaysMs)
        val oldForward = fileIn("forward_attachments", "old.dat", eightDaysMs)
        val oldDraft = fileIn("draft_attachments", "old.dat", eightDaysMs)
        val recent = fileIn("draft_attachments", "recent.dat", 0L)

        AttachmentManager.cleanupOldAttachments(context)

        assertThat(oldCache.exists()).isFalse()
        assertThat(oldReply.exists()).isFalse()
        assertThat(oldForward.exists()).isFalse()
        assertThat(oldDraft.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
    }

    @Test
    fun `missing dirs are a safe no-op`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        // Каталоги не создаём — вызов не должен бросать исключений.
        AttachmentManager.cleanupOldAttachments(context)
    }
}
