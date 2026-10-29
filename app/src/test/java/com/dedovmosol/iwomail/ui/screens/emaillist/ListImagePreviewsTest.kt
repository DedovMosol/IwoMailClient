package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

/** Миниатюры в списке: скачанная картинка — сразу; черновику недостающую небольшую — скачать, один раз. */
class ListImagePreviewsTest {

    private val rows = mutableListOf<AttachmentEntity>()
    private val existing = mutableSetOf<String>()
    private val downloads = mutableListOf<Pair<Long, Long>>()
    private var downloadResult: (AttachmentEntity) -> String? = { "/files/draft_attachments/${it.displayName}" }

    private val previews = ListImagePreviews(
        attachmentsOf = { ids -> rows.filter { it.emailId in ids } },
        downloadToFile = { accountId, row ->
            downloads += accountId to row.id
            downloadResult(row)
        },
        fileExists = { it in existing }
    )

    private fun row(
        id: Long,
        name: String,
        emailId: String = "1_5:1",
        type: String = "application/octet-stream", // так EAS: типа у вложения нет
        path: String? = null,
        ref: String = "5%3a1%3a$id",
        size: Long = 50_000
    ) = AttachmentEntity(
        id = id, emailId = emailId, fileReference = ref, displayName = name, contentType = type,
        estimatedSize = size, localPath = path, downloaded = path != null
    ).also { rows += it }

    @Test
    fun `downloaded picture is used at once, a missing file is not`() = runBlocking<Unit> {
        row(1, "report.pdf", path = "/files/report.pdf").also { existing += "/files/report.pdf" }
        row(2, "image001.png", path = "/files/gone.png") // файл удалён
        row(3, "image002.png", path = "/files/image002.png").also { existing += "/files/image002.png" }

        val found = previews.find(listOf("1_5:1"), download = true)

        assertThat(found.local).containsExactly("1_5:1", ListPreview("/files/image002.png", imageCount = 2))
        assertThat(found.toDownload).isEmpty()
    }

    @Test
    fun `server draft picture without a type is downloaded once, small and with a reference only`() = runBlocking<Unit> {
        row(1, "huge.jpg", size = ListImagePreviews.MAX_DOWNLOAD_BYTES + 1)
        row(2, "unknown-size.png", size = 0)
        row(3, "no-ref.png", ref = "")
        val picture = row(4, "image001.png")

        val found = previews.find(listOf("1_5:1"), download = true)
        val pending = found.toDownload.getValue("1_5:1")

        assertThat(previews.download(1L, pending)).isEqualTo(ListPreview("/files/draft_attachments/image001.png", 4))
        // Повторно — не качаем (ни при прокрутке, ни после ошибки)
        assertThat(previews.download(1L, pending)).isNull()
        assertThat(previews.find(listOf("1_5:1"), download = true).toDownload).isEmpty()
        assertThat(downloads).containsExactly(1L to 4L)
    }

    @Test
    fun `nothing is downloaded when downloads are off`() = runBlocking<Unit> {
        row(1, "image001.png")

        assertThat(previews.find(listOf("1_5:1"), download = false).toDownload).isEmpty()
    }

    @Test
    fun `at most two downloads run at the same time`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        val limited = ListImagePreviews(
            attachmentsOf = { emptyList() },
            downloadToFile = { _, row ->
                running++
                maxRunning = maxOf(maxRunning, running)
                release.await()
                running--
                row.displayName
            },
            fileExists = { false }
        )
        val jobs = (1L..5L).map { id -> async { limited.download(1L, ListImagePreviews.Pending(row(id, "p$id.png", emailId = "1_5:$id"), 1))?.path } }
        repeat(10) { yield() }
        assertThat(maxRunning).isEqualTo(2)

        release.complete(Unit)
        assertThat(jobs.awaitAll()).containsExactly("p1.png", "p2.png", "p3.png", "p4.png", "p5.png")
    }
}
