package com.dedovmosol.iwomail.data.database

import com.dedovmosol.iwomail.eas.MailFlag

/** Флаг письма в БД — одно соответствие полей для синхронизации и правки (DRY). */
val EmailEntity.flag: MailFlag
    get() = when {
        flagged -> MailFlag(MailFlag.STATUS_ACTIVE, flagStartDate, flagDueDate, flagReminder)
        flagCompleted -> MailFlag(MailFlag.STATUS_COMPLETE)
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

suspend fun EmailDao.saveFlag(id: String, flag: MailFlag) {
    val active = flag.active
    updateFlag(
        id = id,
        flagged = active,
        completed = flag.completed,
        startDate = flag.startDate.takeIf { active },
        dueDate = flag.dueDate.takeIf { active },
        reminder = flag.reminderTime.takeIf { active }
    )
}
