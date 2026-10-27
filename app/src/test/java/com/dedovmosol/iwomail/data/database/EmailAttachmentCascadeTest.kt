package com.dedovmosol.iwomail.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DATA-3: мина REPLACE+CASCADE для писем с вложениями.
 *
 * `EmailDao.insert()` объявлен с `OnConflictStrategy.REPLACE` → при конфликте по PK это
 * DELETE + INSERT. DELETE триггерит `ForeignKey(onDelete = CASCADE)` у `AttachmentEntity` и
 * сносит ВСЕ вложения письма. Проект обходит это, обновляя черновики через UPDATE-in-place
 * (`updateDraftFields`), а не повторным `insert()`.
 *
 * Эти тесты ЗАКРЕПЛЯЮТ оба факта (регрессионный предохранитель):
 *  1) безопасный путь `updateDraftFields` СОХРАНЯЕТ вложения;
 *  2) «опасный» повторный `insert()` существующего письма их УДАЛЯЕТ (документируем мину —
 *     чтобы никто не завёл новый путь прямого insert() по письму с вложениями).
 *
 * Room включает FK-ограничения по умолчанию (поэтому засеваем всю родительскую цепочку
 * attachments → emails → folders → accounts), что и позволяет каскаду сработать.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric 4.12.2 поддерживает API ≤ 34 (targetSdk 36 ему неизвестен)
class EmailAttachmentCascadeTest {

    private lateinit var db: MailDatabase
    private lateinit var emailDao: EmailDao
    private lateinit var attachmentDao: AttachmentDao
    private var accountId: Long = 0

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        emailDao = db.emailDao()
        attachmentDao = db.attachmentDao()
        // FK-цепочка attachments → emails → folders → accounts: засеваем родителей,
        // иначе INSERT письма упадёт с SQLITE_CONSTRAINT_FOREIGNKEY (FK у Room включён).
        runBlocking {
            accountId = db.accountDao().insert(
                AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me")
            )
            db.folderDao().insert(
                FolderEntity(
                    id = FOLDER_ID, accountId = accountId, serverId = "drafts",
                    displayName = "Drafts", parentId = "", type = 0
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(id: String) = EmailEntity(
        id = id,
        accountId = accountId,
        folderId = FOLDER_ID,
        serverId = "local_draft_$id",
        from = "me@corp.local",
        to = "to@corp.local",
        subject = "subject",
        body = "<p>body</p>",
        dateReceived = 1_000L,
        read = true
    )

    private fun attachmentFor(emailId: String) = AttachmentEntity(
        emailId = emailId,
        fileReference = "1:1",
        displayName = "report.pdf",
        contentType = "application/pdf"
    )

    @Test
    fun updateDraftFields_preservesAttachments() = runBlocking {
        emailDao.insert(draft("1_d"))
        attachmentDao.insertAll(listOf(attachmentFor("1_d")))
        assertThat(attachmentDao.getAttachmentsList("1_d")).hasSize(1)

        // Безопасный путь: UPDATE-in-place НЕ трогает таблицу вложений.
        emailDao.updateDraftFields(
            id = "1_d",
            to = "to2@corp.local",
            cc = "",
            subject = "subject-2",
            body = "<p>body-2</p>",
            preview = "body-2",
            dateReceived = 2_000L,
            fromEmail = "me@corp.local",
            fromName = "Me",
            bcc = "hidden@corp.local",
            importance = 2
        )

        val attachments = attachmentDao.getAttachmentsList("1_d")
        assertThat(attachments).hasSize(1)
        assertThat(attachments.first().displayName).isEqualTo("report.pdf")
        // Поля письма действительно обновились — и скрытые копии с важностью (EAS до 16.0 их не хранит).
        val updated = emailDao.getEmail("1_d")
        assertThat(updated?.subject).isEqualTo("subject-2")
        assertThat(updated?.bcc).isEqualTo("hidden@corp.local")
        assertThat(updated?.importance).isEqualTo(2)
    }

    @Test
    fun replaceInsertOfExistingEmail_cascadeDeletesAttachments_documentsHazard() = runBlocking {
        emailDao.insert(draft("2_d"))
        attachmentDao.insertAll(listOf(attachmentFor("2_d")))
        assertThat(attachmentDao.getAttachmentsList("2_d")).hasSize(1)

        // Опасный путь: повторный insert() того же письма = DELETE+INSERT (REPLACE) →
        // CASCADE удаляет вложения. Этот тест фиксирует мину (почему используем updateDraftFields).
        emailDao.insert(draft("2_d"))

        assertThat(attachmentDao.getAttachmentsList("2_d")).isEmpty()
    }

    private companion object {
        const val FOLDER_ID = "drafts"
    }
}
