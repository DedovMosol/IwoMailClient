package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты self-heal для скачивания вложений в [EmailDetailActions].
 *
 * Проверяют фикс бага «иногда вложение не открывается/не скачивается — помогает обновление
 * письма»: на Exchange FileReference устаревает/бывает пустым, и теперь при первом отказе
 * ссылка обновляется ([MailRepository.refreshAttachmentMetadata]) и загрузка повторяется один раз.
 *
 * Все зависимости — MockK (конструкторная инъекция, DIP). EasClient подменяется через
 * [AccountRepository.createEasClient]. Без Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailDetailActionsAttachmentTest {

    private lateinit var mailRepo: MailRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var easClient: EasClient
    private lateinit var actions: EmailDetailActions

    private val account = mockk<AccountEntity>(relaxed = true)

    @Before
    fun setUp() {
        mailRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        easClient = mockk(relaxed = true)
        actions = EmailDetailActions(mailRepo, accountRepo, mockk(relaxed = true), mockk(relaxed = true))

        every { account.id } returns ACCOUNT_ID
        every { account.policyKey } returns null
        coEvery { accountRepo.getActiveAccountSync() } returns account
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns easClient
        every { easClient.policyKey } returns null
    }

    private fun attachment(ref: String) = AttachmentEntity(
        id = ATT_ID,
        emailId = EMAIL_ID,
        fileReference = ref,
        displayName = "doc.pdf",
        contentType = "application/pdf",
        estimatedSize = 1024L
    )

    @Test
    fun `stale FileReference is refreshed and retried, second attempt succeeds`() = runTest {
        val freshBytes = byteArrayOf(1, 2, 3)
        coEvery { easClient.downloadAttachment(STALE_REF) } returns
            EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.")
        coEvery { easClient.downloadAttachment(FRESH_REF) } returns EasResult.Success(freshBytes)
        // refresh обновляет fileReference в БД → getAttachmentsSync возвращает свежую ссылку.
        coEvery { mailRepo.getAttachmentsSync(EMAIL_ID) } returns listOf(attachment(FRESH_REF))

        val result = actions.fetchAttachmentBytes(attachment(STALE_REF))

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isEqualTo(freshBytes)
        coVerify(exactly = 1) { mailRepo.refreshAttachmentMetadata(EMAIL_ID) }
        coVerify(exactly = 1) { easClient.downloadAttachment(STALE_REF) }
        coVerify(exactly = 1) { easClient.downloadAttachment(FRESH_REF) }
    }

    @Test
    fun `no retry when refresh does not change the FileReference`() = runTest {
        coEvery { easClient.downloadAttachment(STALE_REF) } returns EasResult.Error("boom")
        // refresh вернул ТУ ЖЕ ссылку → повтор бессмыслен (вложение реально недоступно).
        coEvery { mailRepo.getAttachmentsSync(EMAIL_ID) } returns listOf(attachment(STALE_REF))

        val result = actions.fetchAttachmentBytes(attachment(STALE_REF))

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        coVerify(exactly = 1) { mailRepo.refreshAttachmentMetadata(EMAIL_ID) }
        coVerify(exactly = 1) { easClient.downloadAttachment(STALE_REF) }
    }

    @Test
    fun `no retry when attachment disappeared after refresh`() = runTest {
        coEvery { easClient.downloadAttachment(STALE_REF) } returns EasResult.Error("boom")
        // reconcile удалил вложение (нет на сервере) → матч не найден → null → без повтора.
        coEvery { mailRepo.getAttachmentsSync(EMAIL_ID) } returns emptyList()

        val result = actions.fetchAttachmentBytes(attachment(STALE_REF))

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        coVerify(exactly = 1) { easClient.downloadAttachment(STALE_REF) }
    }

    @Test
    fun `first attempt success does not trigger refresh`() = runTest {
        val bytes = byteArrayOf(9)
        coEvery { easClient.downloadAttachment(GOOD_REF) } returns EasResult.Success(bytes)

        val result = actions.fetchAttachmentBytes(attachment(GOOD_REF))

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        coVerify(exactly = 0) { mailRepo.refreshAttachmentMetadata(any()) }
        coVerify(exactly = 1) { easClient.downloadAttachment(GOOD_REF) }
    }

    @Test
    fun `empty FileReference is populated by refresh and retried`() = runTest {
        val bytes = byteArrayOf(7, 7)
        coEvery { easClient.downloadAttachment("") } returns EasResult.Error("Сервер не поддерживает")
        coEvery { easClient.downloadAttachment(FRESH_REF) } returns EasResult.Success(bytes)
        coEvery { mailRepo.getAttachmentsSync(EMAIL_ID) } returns listOf(attachment(FRESH_REF))

        val result = actions.fetchAttachmentBytes(attachment(""))

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isEqualTo(bytes)
        coVerify(exactly = 1) { easClient.downloadAttachment(FRESH_REF) }
    }

    @Test
    fun `local cached attachment is returned without any network call`() = runTest {
        val cached = attachment(GOOD_REF).copy(downloaded = true, localPath = "/nonexistent/path.pdf")
        // localPath не существует → fast-path не срабатывает, идём в сеть (проверяем, что это не крэшит).
        coEvery { easClient.downloadAttachment(GOOD_REF) } returns EasResult.Success(byteArrayOf(5))

        val result = actions.fetchAttachmentBytes(cached)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
    }

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val EMAIL_ID = "email-1"
        private const val ATT_ID = 42L
        private const val STALE_REF = "STALE_REF"
        private const val FRESH_REF = "FRESH_REF"
        private const val GOOD_REF = "GOOD_REF"
    }
}
