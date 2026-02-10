package com.dedovmosol.iwomail.data.repository

/**
 * Константы сообщений об ошибках для репозиториев
 * Централизация для DRY и упрощения локализации в будущем
 */
object RepositoryErrors {
    const val ACCOUNT_NOT_FOUND = "Аккаунт не найден"
    const val CLIENT_CREATE_FAILED = "Не удалось создать клиент"
    
    // Exchange-only features
    const val TASKS_EXCHANGE_ONLY = "Задачи поддерживаются только для Exchange"
    const val NOTES_EXCHANGE_ONLY = "Заметки поддерживаются только для Exchange"
    const val CALENDAR_EXCHANGE_ONLY = "Календарь поддерживается только для Exchange"
    const val CONTACTS_EXCHANGE_ONLY = "Контакты поддерживаются только для Exchange"
    const val MEETING_RESPONSE_EXCHANGE_ONLY = "Ответ на приглашения поддерживается только для Exchange"
    
    // Operation errors
    const val TASK_CREATE_ERROR = "Ошибка создания задачи"
    const val TASK_UPDATE_ERROR = "Ошибка обновления задачи"
    const val TASK_DELETE_ERROR = "Ошибка удаления задачи"
    const val TASK_RESTORE_ERROR = "Ошибка восстановления задачи"
    const val TASK_PERMANENT_DELETE_ERROR = "Ошибка окончательного удаления задачи"
    const val TASK_TRASH_EMPTY_ERROR = "Ошибка очистки корзины задач"
    const val TASK_SYNC_ERROR = "Ошибка синхронизации задач"
    
    const val NOTE_CREATE_ERROR = "Ошибка создания заметки"
    const val NOTE_UPDATE_ERROR = "Ошибка обновления заметки"
    const val NOTE_DELETE_ERROR = "Ошибка удаления заметки"
    const val NOTE_RESTORE_ERROR = "Ошибка восстановления заметки"
    const val NOTE_SYNC_ERROR = "Ошибка синхронизации заметок"
    
    const val EVENT_CREATE_ERROR = "Ошибка создания события"
    const val EVENT_UPDATE_ERROR = "Ошибка обновления события"
    const val EVENT_DELETE_ERROR = "Ошибка удаления события"
    const val EVENT_NOT_FOUND = "Событие не найдено"
    const val CALENDAR_SYNC_ERROR = "Ошибка синхронизации календаря"
    const val MEETING_RESPONSE_ERROR = "Ошибка ответа на приглашение"
    const val MEETING_INVITE_ERROR = "Ошибка отправки приглашений"
    const val ATTENDEE_UPDATE_ERROR = "Ошибка обновления статуса"
    
    const val CONTACT_SYNC_ERROR = "Ошибка синхронизации контактов"
    const val GAL_SYNC_ERROR = "Ошибка синхронизации GAL"
}
