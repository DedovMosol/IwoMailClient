package com.dedovmosol.iwomail.eas

/**
 * Типы папок Exchange ActiveSync
 * Принцип KISS: константы вместо magic numbers
 * 
 * Источник: MS-ASCMD Section 2.2.3.170.2
 */
object FolderType {
    const val INBOX = 2           // Входящие
    const val DRAFTS = 3          // Черновики
    const val DELETED_ITEMS = 4   // Удаленные
    const val SENT_ITEMS = 5      // Отправленные
    const val OUTBOX = 6          // Исходящие
    const val TASKS = 7           // Задачи
    const val CALENDAR = 8        // Календарь
    const val CONTACTS = 9        // Контакты
    const val NOTES = 10          // Заметки
    const val JUNK_EMAIL = 11     // Спам/Junk
    const val USER_CREATED = 12   // Пользовательская папка
    
    /** Системные почтовые папки для фоновой синхронизации (SyncWorker/PushService) */
    val SYNC_MAIN_TYPES = listOf(INBOX, DRAFTS, DELETED_ITEMS, SENT_ITEMS, OUTBOX, JUNK_EMAIL)
    
    /** Типы пользовательских папок (EAS type 1 = generic, 12 = user-created mail) */
    val SYNC_USER_TYPES = listOf(1, USER_CREATED)
    
    /** Все типы для push-мониторинга (Ping) и фоновой синхронизации */
    val PUSH_TYPES = SYNC_MAIN_TYPES + SYNC_USER_TYPES
    
    /** Лимит пользовательских папок для ПОЛНОГО resync за один цикл.
     *  Инкрементальный sync выполняется для ВСЕХ папок (быстрый, ~1-3 сек).
     *  Full resync (до 280с/папка) — только для первых N несинхронизированных.
     *  Остальные подтянутся в следующих циклах (каждые 15 мин). */
    const val MAX_FULL_RESYNC_USER_FOLDERS = 10
    
    /**
     * Проверяет, является ли папка системной (нельзя удалять/переименовывать)
     */
    fun isSystemFolder(type: Int): Boolean {
        return type in listOf(INBOX, DRAFTS, DELETED_ITEMS, SENT_ITEMS, OUTBOX, CALENDAR, CONTACTS, NOTES, TASKS, JUNK_EMAIL)
    }
}
