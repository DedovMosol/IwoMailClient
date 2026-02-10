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
    
    /**
     * Проверяет, является ли папка системной (нельзя удалять/переименовывать)
     */
    fun isSystemFolder(type: Int): Boolean {
        return type in listOf(INBOX, DRAFTS, DELETED_ITEMS, SENT_ITEMS, OUTBOX, CALENDAR, CONTACTS, NOTES, TASKS, JUNK_EMAIL)
    }
}
