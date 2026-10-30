package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.flag
import com.dedovmosol.iwomail.data.database.withFlag
import com.dedovmosol.iwomail.eas.MailFlag
import com.dedovmosol.iwomail.sync.FlagReminderReceiver
import com.dedovmosol.iwomail.ui.screens.emaillist.availableIn
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MailPreferencesTest {

    @Test
    fun `undo delay is one of the offered values`() {
        assertThat(UndoSend.sanitize(null)).isEqualTo(UndoSend.DEFAULT_SECONDS)
        assertThat(UndoSend.sanitize(7)).isEqualTo(UndoSend.DEFAULT_SECONDS)
        assertThat(UndoSend.sanitize(-1)).isEqualTo(UndoSend.DEFAULT_SECONDS)
        for (seconds in UndoSend.OPTIONS) assertThat(UndoSend.sanitize(seconds)).isEqualTo(seconds)
        assertThat(UndoSend.OPTIONS).contains(0)
    }

    @Test
    fun `swipe action from stored name with a default`() {
        assertThat(SwipeAction.fromName("FLAG", SwipeAction.NONE)).isEqualTo(SwipeAction.FLAG)
        assertThat(SwipeAction.fromName("ARCHIVE", SwipeAction.DELETE)).isEqualTo(SwipeAction.DELETE)
        assertThat(SwipeAction.fromName(null, SwipeAction.READ)).isEqualTo(SwipeAction.READ)
    }

    @Test
    fun `swipe action depends on the folder`() {
        assertThat(SwipeAction.DELETE.availableIn(deleteAllowed = false, flagAllowed = true)).isEqualTo(SwipeAction.NONE)
        assertThat(SwipeAction.FLAG.availableIn(deleteAllowed = true, flagAllowed = false)).isEqualTo(SwipeAction.NONE)
        assertThat(SwipeAction.READ.availableIn(deleteAllowed = false, flagAllowed = false)).isEqualTo(SwipeAction.READ)
        assertThat(SwipeAction.DELETE.availableIn(deleteAllowed = true, flagAllowed = true)).isEqualTo(SwipeAction.DELETE)
    }

    private val email = EmailEntity(
        id = "1_m", accountId = 1L, folderId = "1_2", serverId = "m", from = "a@b.c", to = "", subject = "S",
        body = "", dateReceived = 0L, read = true
    )

    @Test
    fun `flag maps to the email columns and back`() {
        val active = MailFlag(MailFlag.STATUS_ACTIVE, 1L, 2L, 3L)
        val withActive = email.withFlag(active)
        assertThat(withActive.flagged).isTrue()
        assertThat(withActive.flagCompleted).isFalse()
        assertThat(withActive.flag).isEqualTo(active)

        val completed = withActive.withFlag(MailFlag(MailFlag.STATUS_COMPLETE, 1L, 2L, 3L))
        assertThat(completed.flagged).isFalse()
        assertThat(completed.flagCompleted).isTrue()
        assertThat(completed.flagDueDate).isNull()
        assertThat(completed.flagReminder).isNull()
        assertThat(completed.flag).isEqualTo(MailFlag(MailFlag.STATUS_COMPLETE))

        assertThat(completed.withFlag(MailFlag.NONE).flag).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `reminder fires only for the time it was set for`() {
        assertThat(FlagReminderReceiver.isDue(1_000_000L, 1_000_000L)).isTrue()
        assertThat(FlagReminderReceiver.isDue(1_000_000L, 1_030_000L)).isTrue()
        // Перенесли в Outlook на час — старый будильник не напоминает
        assertThat(FlagReminderReceiver.isDue(1_000_000L + 3_600_000L, 1_000_000L)).isFalse()
        // Напоминание сняли
        assertThat(FlagReminderReceiver.isDue(null, 1_000_000L)).isFalse()
    }
}
