package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Черновик, сохранённый приложением (запись по EWS ItemId), и его копия из EAS Sync — одна запись, а не два
 * черновика в списке.
 */
class DraftMigrationMatchTest {

    private val minute = 60_000L

    private fun record(id: String, subject: String, at: Long) = EmailEntity(
        id = "1_$id", accountId = 1L, folderId = "1_5", serverId = id, from = "", to = "",
        subject = subject, body = "", dateReceived = at, read = true
    )

    @Test
    fun `draft without subject is matched although EAS reports it as (No subject) and its body is empty`() {
        val app = record("AAMkAPP=", subject = "", at = 10 * minute)
        val eas = record("5:13", subject = "(No subject)", at = 10 * minute + 20_000)

        // Раньше: "" != "(No subject)" — и рядом с записью приложения появлялся второй черновик.
        assertThat(EmailSyncService.matchAppDraft(eas, listOf(app))).isEqualTo(app)
    }

    @Test
    fun `the closest of drafts with the same subject is matched`() {
        val older = record("AAMkOLD=", subject = "Отчёт", at = 1 * minute)
        val newer = record("AAMkNEW=", subject = "Отчёт", at = 30 * minute)
        val eas = record("5:14", subject = "Отчёт", at = 31 * minute)

        assertThat(EmailSyncService.matchAppDraft(eas, listOf(older, newer))).isEqualTo(newer)
    }

    @Test
    fun `drafts without subject far apart in time are not paired`() {
        // Иначе тела и вложения двух разных черновиков без темы поменялись бы местами.
        val app = record("AAMkAPP=", subject = "", at = 0L)
        val eas = record("5:15", subject = "(No subject)", at = 60 * minute)

        assertThat(EmailSyncService.matchAppDraft(eas, listOf(app))).isNull()
        // С темой время не обязательно: та же тема — тот же черновик, как и раньше.
        val withSubject = record("AAMkS=", subject = "План", at = 0L)
        assertThat(EmailSyncService.matchAppDraft(record("5:16", "План", 60 * minute), listOf(withSubject))).isEqualTo(withSubject)
    }

    @Test
    fun `other subject is never matched`() {
        val app = record("AAMkAPP=", subject = "План", at = 0L)
        assertThat(EmailSyncService.matchAppDraft(record("5:17", "Отчёт", 0L), listOf(app))).isNull()
    }
}
