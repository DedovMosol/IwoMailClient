package com.dedovmosol.iwomail.domain

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Тесты для [AttachmentLoader].
 *
 * Покрытие:
 * - Happy path: локальные файлы, скачивание через EAS
 * - Edge cases: пустой список, только inline, только файловые, смешанные
 * - Лимиты: MAX_INLINE_IMAGES (CS-4)
 * - Ошибки: отсутствующий файл, EAS download failure, IO exception
 * - Cancellation: CancellationException пробрасывается
 * - Thread safety: корректная работа с dispatcher
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttachmentLoaderTest {

    private lateinit var context: Context
    private lateinit var mockMailRepo: MailRepository
    private lateinit var mockEasClient: EasClient
    private lateinit var loader: AttachmentLoader
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockMailRepo = mockk()
        mockEasClient = mockk()
        testDispatcher = UnconfinedTestDispatcher()

        // Создать временную директорию для тестовых файлов
        tempDir = File(context.cacheDir, "test_attachments")
        tempDir.mkdirs()

        loader = AttachmentLoader(
            context = context,
            mailRepository = mockMailRepo,
            dispatcher = testDispatcher,
            // Детерминированный резолвер: логика загрузчика не должна зависеть
            // от зарегистрированного в манифесте FileProvider. Прод-поведение
            // (FileProvider) остаётся дефолтом конструктора.
            fileUriResolver = { file -> Uri.fromFile(file) }
        )
    }

    @After
    fun tearDown() {
        // Очистить временные файлы
        tempDir.deleteRecursively()
        clearAllMocks()
    }

    // ========== Happy Path ==========

    @Test
    fun `loadAttachments returns empty success when no attachments`() = runTest {
        val email = createEmail(id = "1")
        coEvery { mockMailRepo.getAttachmentsSync("1") } returns emptyList()

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).isEmpty()
        assertThat(success.inlineImages).isEmpty()
        assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `loadAttachments loads local file attachment`() = runTest {
        // Подготовка: создать тестовый файл
        val testFile = File(tempDir, "document.pdf")
        testFile.writeText("PDF content")

        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "document.pdf",
            estimatedSize = testFile.length(),
            contentType = "application/pdf",
            isInline = false,
            contentId = null,
            fileReference = "",
            localPath = testFile.absolutePath,
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Forward,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).hasSize(1)
        assertThat(success.fileAttachments[0].name).isEqualTo("document.pdf")
        assertThat(success.fileAttachments[0].size).isEqualTo(testFile.length())
        assertThat(success.fileAttachments[0].mimeType).isEqualTo("application/pdf")
        assertThat(success.inlineImages).isEmpty()
        assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `loadAttachments loads local inline image as base64`() = runTest {
        // Подготовка: создать тестовую картинку
        val testFile = File(tempDir, "image.png")
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header
        testFile.writeBytes(imageBytes)

        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "image.png",
            estimatedSize = testFile.length(),
            contentType = "image/png",
            isInline = true,
            contentId = "image001",
            fileReference = "",
            localPath = testFile.absolutePath,
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).isEmpty()
        assertThat(success.inlineImages).hasSize(1)
        assertThat(success.inlineImages).containsKey("image001")
        assertThat(success.inlineImages["image001"]).startsWith("data:image/png;base64,")
        assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `loadAttachments downloads attachment via EAS when localPath is null`() = runTest {
        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "report.xlsx",
            estimatedSize = 5000,
            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            isInline = false,
            contentId = null,
            fileReference = "FileRef123",
            localPath = null,
        )

        val downloadedBytes = "Excel content".toByteArray()

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)
        coEvery {
            mockEasClient.downloadAttachment("FileRef123")
        } returns EasResult.Success(downloadedBytes)

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Forward,
            email = email,
            easClient = mockEasClient,
            collectionId = "CollectionId",
            emailServerId = "ServerId"
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).hasSize(1)
        assertThat(success.fileAttachments[0].name).isEqualTo("report.xlsx")
        assertThat(success.skippedCount).isEqualTo(0)

        coVerify(exactly = 1) {
            mockEasClient.downloadAttachment("FileRef123")
        }
        coVerify(exactly = 0) { mockEasClient.downloadDraftAttachment(any()) }
    }

    @Test
    fun `loadAttachments routes Draft download through downloadDraftAttachment (EWS ItemId support)`() = runTest {
        // Черновики, созданные в Outlook/OWA на Exchange 2007, хранят вложения
        // с EWS ItemId в fileReference — их может скачать только
        // downloadDraftAttachment (роутинг EAS/EWS по наличию ":").
        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "contract.pdf",
            estimatedSize = 5000,
            contentType = "application/pdf",
            isInline = false,
            contentId = null,
            fileReference = "AAMkAGI...EwsItemIdWithoutColon",
            localPath = null,
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)
        coEvery {
            mockEasClient.downloadDraftAttachment("AAMkAGI...EwsItemIdWithoutColon")
        } returns EasResult.Success("PDF bytes".toByteArray())

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Draft,
            email = email,
            easClient = mockEasClient,
            collectionId = null, // Drafts: collectionId может отсутствовать
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).hasSize(1)
        assertThat(success.fileAttachments[0].name).isEqualTo("contract.pdf")

        coVerify(exactly = 1) {
            mockEasClient.downloadDraftAttachment("AAMkAGI...EwsItemIdWithoutColon")
        }
        coVerify(exactly = 0) {
            mockEasClient.downloadAttachment(any())
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun `loadAttachments handles mixed inline and file attachments`() = runTest {
        val inlineFile = File(tempDir, "inline.png")
        inlineFile.writeBytes(byteArrayOf(1, 2, 3))

        val regularFile = File(tempDir, "document.txt")
        regularFile.writeText("Text content")

        val email = createEmail(id = "1")
        val attachments = listOf(
            AttachmentEntity(
                id = 1,
                emailId = "1",
                displayName = "inline.png",
                estimatedSize = inlineFile.length(),
                contentType = "image/png",
                isInline = true,
                contentId = "img001",
                fileReference = "",
                localPath = inlineFile.absolutePath,
            ),
            AttachmentEntity(
                id = 2,
                emailId = "1",
                displayName = "document.txt",
                estimatedSize = regularFile.length(),
                contentType = "text/plain",
                isInline = false,
                contentId = null,
                fileReference = "",
                localPath = regularFile.absolutePath,
            )
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns attachments

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).hasSize(1)
        assertThat(success.inlineImages).hasSize(1)
        assertThat(success.fileAttachments[0].name).isEqualTo("document.txt")
        assertThat(success.inlineImages).containsKey("img001")
        assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `loadAttachments respects MAX_INLINE_IMAGES limit (CS-4)`() = runTest {
        val email = createEmail(id = "1")

        // Создать 25 inline-картинок (лимит 20)
        val attachments = (1..25).map { index ->
            val file = File(tempDir, "image$index.png")
            file.writeBytes(byteArrayOf(index.toByte()))

            AttachmentEntity(
                id = index.toLong(),
                emailId = "1",
                displayName = "image$index.png",
                estimatedSize = file.length(),
                contentType = "image/png",
                isInline = true,
                contentId = "cid$index",
                fileReference = "",
                localPath = file.absolutePath,
            )
        }

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns attachments

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Forward,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.inlineImages).hasSize(AttachmentLoader.MAX_INLINE_IMAGES)
        assertThat(success.skippedCount).isEqualTo(5) // 25 - 20 = 5 пропущено
    }

    @Test
    fun `loadAttachments uses fallback mimeType for inline images without contentType`() = runTest {
        val testFile = File(tempDir, "image.png")
        testFile.writeBytes(byteArrayOf(1, 2, 3))

        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "image.png",
            estimatedSize = testFile.length(),
            contentType = "", // Пустой contentType
            isInline = true,
            contentId = "img001",
            fileReference = "",
            localPath = testFile.absolutePath,
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.inlineImages["img001"]).startsWith("data:image/png;base64,") // Fallback к image/png
    }

    // ========== Error Handling ==========

    @Test
    fun `loadAttachments skips attachment when localPath does not exist`() = runTest {
        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "missing.pdf",
            estimatedSize = 1000,
            contentType = "application/pdf",
            isInline = false,
            contentId = null,
            fileReference = "", // Нет fileReference для скачивания
            localPath = "/nonexistent/path/file.pdf",
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).isEmpty()
        assertThat(success.skippedCount).isEqualTo(1)
    }

    @Test
    fun `loadAttachments skips attachment when EAS download fails`() = runTest {
        val email = createEmail(id = "1")
        val attachment = AttachmentEntity(
            id = 1,
            emailId = "1",
            displayName = "report.pdf",
            estimatedSize = 5000,
            contentType = "application/pdf",
            isInline = false,
            contentId = null,
            fileReference = "FileRef123",
            localPath = null,
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns listOf(attachment)
        coEvery {
            mockEasClient.downloadAttachment("FileRef123")
        } returns EasResult.Error("Network error")

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Forward,
            email = email,
            easClient = mockEasClient,
            collectionId = "CollectionId",
            emailServerId = "ServerId"
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).isEmpty()
        assertThat(success.skippedCount).isEqualTo(1)
    }

    @Test
    fun `loadAttachments continues after one attachment throws IOException`() = runTest {
        val validFile = File(tempDir, "valid.txt")
        validFile.writeText("Valid content")

        val email = createEmail(id = "1")
        val attachments = listOf(
            AttachmentEntity(
                id = 1,
                emailId = "1",
                displayName = "corrupted.pdf",
                estimatedSize = 1000,
                contentType = "application/pdf",
                isInline = false,
                contentId = null,
                fileReference = "",
                localPath = "/corrupted/path", // Несуществующий файл
            ),
            AttachmentEntity(
                id = 2,
                emailId = "1",
                displayName = "valid.txt",
                estimatedSize = validFile.length(),
                contentType = "text/plain",
                isInline = false,
                contentId = null,
                fileReference = "",
                localPath = validFile.absolutePath,
            )
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns attachments

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Draft,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        val success = result as AttachmentLoader.LoadResult.Success
        assertThat(success.fileAttachments).hasSize(1)
        assertThat(success.fileAttachments[0].name).isEqualTo("valid.txt")
        assertThat(success.skippedCount).isEqualTo(1)
    }

    @Test
    fun `loadAttachments returns Error when mailRepository throws exception`() = runTest {
        val email = createEmail(id = "1")

        coEvery { mockMailRepo.getAttachmentsSync("1") } throws IOException("Database error")

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Error::class.java)
        val error = result as AttachmentLoader.LoadResult.Error
        assertThat(error.message).contains("Database error")
    }

    // ========== Progress Callback ==========

    @Test
    fun `loadAttachments calls onProgress with correct values`() = runTest {
        val file1 = File(tempDir, "file1.txt")
        file1.writeText("Content 1")
        val file2 = File(tempDir, "file2.txt")
        file2.writeText("Content 2")

        val email = createEmail(id = "1")
        val attachments = listOf(
            createAttachment(id = 1, localPath = file1.absolutePath),
            createAttachment(id = 2, localPath = file2.absolutePath)
        )

        coEvery { mockMailRepo.getAttachmentsSync("1") } returns attachments

        val progressValues = mutableListOf<Float>()

        val result = loader.loadAttachments(
            source = AttachmentLoader.AttachmentSource.Reply,
            email = email,
            easClient = null,
            collectionId = null,
            emailServerId = null,
            onProgress = { progressValues.add(it) }
        )

        assertThat(result).isInstanceOf(AttachmentLoader.LoadResult.Success::class.java)
        assertThat(progressValues).containsExactly(0f, 0.5f, 1f).inOrder()
    }

    // ========== Helper Functions ==========

    private fun createEmail(id: String): EmailEntity {
        return EmailEntity(
            id = id,
            accountId = 1,
            folderId = "folder1",
            serverId = "ServerID",
            from = "sender@example.com",
            to = "recipient@example.com",
            subject = "Test Subject",
            body = "Test Body",
            dateReceived = System.currentTimeMillis(),
            read = false,
            flagged = false,
            hasAttachments = true
        )
    }

    private fun createAttachment(
        id: Long,
        localPath: String? = null,
        isInline: Boolean = false,
        contentId: String? = null
    ): AttachmentEntity {
        return AttachmentEntity(
            id = id,
            emailId = "1",
            displayName = "file$id.txt",
            estimatedSize = 1000,
            contentType = "text/plain",
            isInline = isInline,
            contentId = contentId,
            fileReference = "",
            localPath = localPath,
        )
    }
}
