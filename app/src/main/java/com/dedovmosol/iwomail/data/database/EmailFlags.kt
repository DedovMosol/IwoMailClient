package com.dedovmosol.iwomail.data.database

import com.dedovmosol.iwomail.eas.MailFlag

/** Флаг письма в БД — одно соответствие полей для синхронизации и правки (DRY). */
val EmailEntity.flag: MailFlag
    get() = mailFlagOf(flagged, flagCompleted, flagStartDate, flagDueDate, flagReminder)

/** Письмо для записи флага: адрес на сервере и флаг, без тела ([EmailDao.getFlagTarget]). */
data class EmailFlagTarget(
    val id: String,
    val accountId: Long,
    val folderId: String,
    val serverId: String,
    val flagged: Boolean,
    val flagCompleted: Boolean,
    val flagStartDate: Long?,
    val flagDueDate: Long?,
    val flagReminder: Long?
) {
    val flag: MailFlag get() = mailFlagOf(flagged, flagCompleted, flagStartDate, flagDueDate, flagReminder)
}

private fun mailFlagOf(flagged: Boolean, completed: Boolean, start: Long?, due: Long?, reminder: Long?): MailFlag = when {
    flagged -> MailFlag(MailFlag.STATUS_ACTIVE, start, due, reminder)
    completed -> MailFlag(MailFlag.STATUS_COMPLETE)
    else -> MailFlag.NONE
}

/** Флаг [flag] — в поля письма (у неактивного флага срока и напоминания нет). */
fun EmailEntity.withFlag(flag: MailFlag): EmailEntity {
    val active = flag.active
    return copy(
        flagged = active,
        flagCompleted = flag.completed,
        flagStartDate = flag.startDate.takeIf { active },
        flagDueDate = flag.dueDate.takeIf { active },
        flagReminder = flag.reminderTime.takeIf { active }
    )
}

/** Флаг, поставленный на устройстве. */
suspend fun EmailDao.saveFlag(id: String, flag: MailFlag) {
    val f = FlagFields(flag)
    updateFlag(id, f.flagged, f.completed, f.start, f.due, f.reminder)
}

/** Флаг из ответа синхронизации: не перетирает флаг, который на устройстве ещё ждёт отправки. */
suspend fun EmailDao.saveServerFlag(id: String, flag: MailFlag) {
    val f = FlagFields(flag)
    updateFlagFromServer(id, f.flagged, f.completed, f.start, f.due, f.reminder)
}

/** Поля флага в БД: у неактивного флага срока и напоминания нет. */
private class FlagFields(flag: MailFlag) {
    val flagged = flag.active
    val completed = flag.completed
    val start = flag.startDate.takeIf { flagged }
    val due = flag.dueDate.takeIf { flagged }
    val reminder = flag.reminderTime.takeIf { flagged }
}
