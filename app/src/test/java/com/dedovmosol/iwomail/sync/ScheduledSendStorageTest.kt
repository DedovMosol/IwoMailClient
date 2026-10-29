package com.dedovmosol.iwomail.sync

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.workDataOf
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.dedovmosol.iwomail.ui.screens.ScheduledEmailWorker
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Хранение запланированных писем и записей очереди: что записано, то и отправится. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledSendStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mail = OutgoingMail(
        to = "a@corp.example", cc = "c@corp.example", bcc = "b@corp.example", subject = "Тема",
        body = "<p>Привет</p>",
        attachments = listOf(Triple("отчёт 1.pdf", "application/pdf", byteArrayOf(1, 2, 3)), Triple("a.png", "image/png", byteArrayOf(9))),
        importance = 2, requestReadReceipt = true, requestDeliveryReceipt = true,
        forwardFolderId = "5", forwardItemId = "5:12"
    )

    private val job = ScheduledSend.Job(
        accountId = 7L, mail = mail, sendAt = 1_800_000_000_000L, clientId = "cid-1", draftId = "7_d1", draftStamp = 42L
    )

    /** ByteArray сравнивается по ссылке — сравниваем содержимое отдельно. */
    private fun assertSameMail(actual: OutgoingMail, expected: OutgoingMail) {
        assertThat(actual.copy(attachments = emptyList())).isEqualTo(expected.copy(attachments = emptyList()))
        assertThat(actual.attachments.map { Triple(it.first, it.second, it.third.toList()) })
            .isEqualTo(expected.attachments.map { Triple(it.first, it.second, it.third.toList()) })
    }

    @Test
    fun `written job reads back whole`() {
        val dir = File(tmp.root, "1_abc")
        ScheduledSend.write(dir, job)

        val read = ScheduledSend.read(dir)!!

        assertSameMail(read.mail, mail)
        assertThat(read.copy(mail = mail)).isEqualTo(job)
    }

    @Test
    fun `job without optional fields reads back with defaults`() {
        val dir = File(tmp.root, "2_abc")
        val plain = ScheduledSend.Job(7L, OutgoingMail("a@corp.example", "", "", "S", "B"), 1L, "cid-2")
        ScheduledSend.write(dir, plain)

        assertThat(ScheduledSend.read(dir)).isEqualTo(plain)
        assertThat(File(dir, "manifest.json").exists()).isFalse()
    }

    @Test
    fun `directory without mail_json is not a job - unfinished, sent or cancelled`() {
        val dir = File(tmp.root, "3_abc").apply { mkdirs() }
        File(dir, ScheduledSend.BODY_FILE).writeText("x")
        assertThat(ScheduledSend.read(dir)).isNull()
        assertThat(ScheduledSend.read(File(tmp.root, "missing"))).isNull()
    }

    @Test
    fun `corrupted mail_json is not a job`() {
        val dir = File(tmp.root, "4_abc").apply { mkdirs() }
        File(dir, "mail.json").writeText("{not json")
        assertThat(ScheduledSend.read(dir)).isNull()
    }

    @Test
    fun `alarm extra outside the payload root is rejected`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun dir(name: String?) = ScheduledSend.dirFromAlarm(context, Intent().putExtra("payload", name))

        assertThat(dir("1_abc")).isEqualTo(File(File(context.filesDir, ScheduledSend.ROOT_DIR), "1_abc"))
        assertThat(dir("../databases")).isNull()
        assertThat(dir("a/b")).isNull()
        assertThat(dir("")).isNull()
        assertThat(dir(null)).isNull()
    }

    @Test
    fun `work enqueued by the previous version is still sent`() {
        val dir = File(tmp.root, "5_abc")
        ScheduledSend.write(dir, job)
        File(dir, "mail.json").delete() // прежняя версия: поля — в inputData, в каталоге — тело и вложения
        val data = workDataOf(
            "accountId" to 7L, "to" to "a@corp.example", "cc" to "", "bcc" to "b@corp.example", "subject" to "S",
            "importance" to 0, "requestReadReceipt" to true, "clientId" to "cid-old", "draftId" to "7_d1", "draftStamp" to 42L
        )

        val legacy = ScheduledEmailWorker.legacyJob(data, dir)!!

        assertThat(legacy.accountId).isEqualTo(7L)
        assertThat(legacy.clientId).isEqualTo("cid-old")
        assertThat(legacy.draftId).isEqualTo("7_d1")
        assertThat(legacy.draftStamp).isEqualTo(42L)
        assertThat(legacy.mail.body).isEqualTo(mail.body)
        assertThat(legacy.mail.attachments.map { it.first }).containsExactly("отчёт 1.pdf", "a.png").inOrder()
        assertThat(legacy.mail.importance).isEqualTo(0)
        assertThat(legacy.mail.requestReadReceipt).isTrue()
        assertThat(legacy.mail.bcc).isEqualTo("b@corp.example")
    }

    @Test
    fun `very old work keeps the body in inputData`() {
        val legacy = ScheduledEmailWorker.legacyJob(workDataOf("accountId" to 1L, "to" to "a@b.cd", "body" to "B"), null)!!
        assertThat(legacy.mail.body).isEqualTo("B")
        assertThat(legacy.clientId).isNotEmpty()
        assertThat(ScheduledEmailWorker.legacyJob(workDataOf("accountId" to 1L), null)).isNull()
    }

    @Test
    fun `sent notification names the first recipient of any field`() {
        assertThat(ScheduledEmailWorker.firstRecipient(OutgoingMail("a@x.y, b@x.y", "", "", "S", "B"))).isEqualTo("a@x.y")
        assertThat(ScheduledEmailWorker.firstRecipient(OutgoingMail("", "", " h@x.y;", "S", "B"))).isEqualTo("h@x.y")
        assertThat(ScheduledEmailWorker.firstRecipient(OutgoingMail("", "", "", "S", "B"))).isEmpty()
    }

    @Test
    fun `outbox entry keeps every send option`() {
        val entry = JSONObject()
            .put("to", "a@x.y").put("cc", "c@x.y").put("bcc", "b@x.y").put("subject", "S").put("body", "B")
            .put("importance", 2).put("requestReadReceipt", true).put("requestDeliveryReceipt", true)
            .put("forwardFolderId", "5").put("forwardItemId", "5:12")

        assertThat(OutboxWorker.mailOf(entry)).isEqualTo(
            OutgoingMail("a@x.y", "c@x.y", "b@x.y", "S", "B", importance = 2, requestReadReceipt = true,
                requestDeliveryReceipt = true, forwardFolderId = "5", forwardItemId = "5:12")
        )
    }

    @Test
    fun `outbox entry of the previous version gets the old defaults`() {
        val entry = JSONObject().put("to", "a@x.y").put("subject", "S").put("body", "B")

        assertThat(OutboxWorker.mailOf(entry)).isEqualTo(OutgoingMail("a@x.y", "", "", "S", "B"))
    }
}
