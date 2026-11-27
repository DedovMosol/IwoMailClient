package com.dedovmosol.iwomail.domain

/**
 * Как часто синхронизировать контакты, календарь, заметки и задачи аккаунта Exchange (значение в днях в
 * `AccountEntity.*SyncIntervalDays`): [NEVER], [WITH_MAIL] или число дней.
 *
 * [WITH_MAIL] — при каждой синхронизации почты (SyncWorker; у аккаунтов Push — его запасной будильник с интервалом
 * синхронизации аккаунта), то есть так часто, как задано в «Интервале синхронизации». Новая колонка не нужна:
 * отрицательное значение в той же колонке, прежние значения (0, 1, 7, 14, 30) не меняют смысла.
 */
object PimSyncInterval {
    const val WITH_MAIL = -1
    const val NEVER = 0
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Варианты в настройках: сначала «Вместе с основной», затем «Никогда» и дни. */
    val OPTIONS = listOf(WITH_MAIL, NEVER, 1, 7, 14, 30)

    /** Пора ли синхронизировать: [lastSyncAt] — время прошлой успешной синхронизации (0 — не было). */
    fun isDue(intervalDays: Int, lastSyncAt: Long, now: Long): Boolean = when {
        intervalDays == WITH_MAIL -> true
        intervalDays <= NEVER -> false
        // Часы перевели назад — прошлая синхронизация «в будущем»: считаем, что пора, иначе ждали бы до той даты
        lastSyncAt > now -> true
        else -> now - lastSyncAt >= intervalDays * DAY_MS
    }

    /**
     * Глобальная адресная книга (GAL) — полная выгрузка: при [WITH_MAIL] не чаще раза в сутки, а не каждые 15 минут;
     * личные контакты Exchange при этом идут с каждой синхронизацией.
     */
    fun isGalDue(intervalDays: Int, lastSyncAt: Long, now: Long): Boolean =
        if (intervalDays == WITH_MAIL) isDue(1, lastSyncAt, now) else isDue(intervalDays, lastSyncAt, now)
}
