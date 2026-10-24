package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.eas.EasAttendee
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Решения правки встречи в [CalendarRepository]: кто организатор, кого отправить, когда рассылать.
 *
 * Регрессия префилла участников: поле диалога теперь содержит прежних участников, и без этих правил
 * правка участника уходила бы как правка организатора (EAS MeetingStatus=1, EWS SendToAllAndSaveCopy),
 * правка напоминания рассылала бы письма всем, а имена и ответы участников стирались бы в JSON.
 * Robolectric нужен для org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarMeetingEditTest {

    private val existingJson =
        """[{"email":"Anna@corp.local","name":"Anna","status":3},{"email":"boris@corp.local","name":"Boris","status":4}]"""

    private fun event(meetingStatus: Int, attendees: String = existingJson) = CalendarEventEntity(
        id = "1_ev", accountId = 1, serverId = "1:5", subject = "Planning",
        startTime = 1_000L, endTime = 2_000L, attendees = attendees,
        isMeeting = (meetingStatus and 1) != 0, meetingStatus = meetingStatus
    )

    // ───────────── кто организатор (MS-ASCAL MeetingStatus, бит R) ─────────────

    @Test
    fun `organizer is decided by the server MeetingStatus R bit`() {
        assertThat(CalendarRepository.isUserOrganizer(event(meetingStatus = 0, attendees = ""))).isTrue() // встреча ли вообще
        assertThat(CalendarRepository.isUserOrganizer(event(meetingStatus = 1))).isTrue()
        assertThat(CalendarRepository.isUserOrganizer(event(meetingStatus = 5))).isTrue()  // отменена организатором
        assertThat(CalendarRepository.isUserOrganizer(event(meetingStatus = 3))).isFalse() // получена от другого
        assertThat(CalendarRepository.isUserOrganizer(event(meetingStatus = 7))).isFalse() // отменена, получена
        assertThat(CalendarRepository.isUserOrganizer(event(meetingStatus = 11))).isFalse() // «то же, что 3»
    }

    // ───────────── разбор и слияние участников ─────────────

    @Test
    fun `parseAttendees skips entries without email instead of dropping the whole list`() {
        val json = """[{"name":"no email"},{"email":" a@corp.local ","name":"A","status":2},{"email":""}]"""

        val parsed = CalendarRepository.parseAttendees(json)

        assertThat(parsed).containsExactly(Attendee("a@corp.local", "A", 2))
        assertThat(CalendarRepository.parseAttendees("not json")).isEmpty()
    }

    @Test
    fun `merge keeps names and responses of existing attendees and adds new ones as unknown`() {
        val merged = CalendarRepository.mergeAttendees(
            existingJson, listOf("anna@corp.local", "new@corp.local", "Boris@corp.local", "NEW@corp.local", " ")
        )

        assertThat(merged).containsExactly(
            EasAttendee("Anna@corp.local", "Anna", 3),
            EasAttendee("new@corp.local", "", 0),
            EasAttendee("boris@corp.local", "Boris", 4)
        ).inOrder()
    }

    @Test
    fun `added attendees are only addresses missing from the event`() {
        val added = CalendarRepository.addedAttendees(existingJson, listOf("ANNA@corp.local", "new@corp.local", "new@CORP.local"))

        assertThat(added).containsExactly("new@corp.local")
    }

    // ───────────── что уходит на сервер ─────────────

    @Test
    fun `organizer editing only the reminder sends attendees back without mailing anyone`() {
        val edit = CalendarRepository.buildMeetingEdit(
            event(meetingStatus = 1), listOf("Anna@corp.local", "boris@corp.local"), meetingChanged = false
        )

        assertThat(edit.isOrganizer).isTrue()
        assertThat(edit.attendees.map { it.status }).containsExactly(3, 4).inOrder()
        assertThat(edit.sendUpdates).isFalse()
        assertThat(edit.newAttendees).isEmpty()
    }

    @Test
    fun `organizer changing the meeting mails an update`() {
        val edit = CalendarRepository.buildMeetingEdit(
            event(meetingStatus = 1), listOf("Anna@corp.local", "boris@corp.local"), meetingChanged = true
        )

        assertThat(edit.sendUpdates).isTrue()
    }

    @Test
    fun `organizer adding an attendee invites only the new address`() {
        val edit = CalendarRepository.buildMeetingEdit(
            event(meetingStatus = 1), listOf("Anna@corp.local", "boris@corp.local", "new@corp.local"), meetingChanged = false
        )

        assertThat(edit.sendUpdates).isTrue()
        assertThat(edit.newAttendees).containsExactly("new@corp.local")
    }

    @Test
    fun `organizer turning an appointment into a meeting sends invitations`() {
        val edit = CalendarRepository.buildMeetingEdit(
            event(meetingStatus = 0, attendees = ""), listOf("new@corp.local"), meetingChanged = false
        )

        assertThat(edit.isOrganizer).isTrue()
        assertThat(edit.attendees).containsExactly(EasAttendee("new@corp.local", "", 0))
        assertThat(edit.sendUpdates).isTrue()
    }

    @Test
    fun `attendee edit ignores typed attendees, keeps the server list and mails nobody`() {
        val received = event(meetingStatus = 3)

        val edit = CalendarRepository.buildMeetingEdit(received, listOf("intruder@corp.local"), meetingChanged = true)

        assertThat(edit.isOrganizer).isFalse()
        assertThat(edit.meetingStatus).isEqualTo(3)
        assertThat(edit.attendees).containsExactly(
            EasAttendee("Anna@corp.local", "Anna", 3),
            EasAttendee("boris@corp.local", "Boris", 4)
        ).inOrder()
        assertThat(edit.sendUpdates).isFalse()
        assertThat(edit.newAttendees).isEmpty()
    }
}
