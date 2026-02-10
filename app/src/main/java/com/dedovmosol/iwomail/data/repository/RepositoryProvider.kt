package com.dedovmosol.iwomail.data.repository

import android.content.Context

/**
 * Singleton провайдер репозиториев для ленивой инициализации
 * Предотвращает создание множества экземпляров репозиториев при навигации между экранами
 */
object RepositoryProvider {
    
    @Volatile
    private var accountRepo: AccountRepository? = null
    
    @Volatile
    private var mailRepo: MailRepository? = null
    
    @Volatile
    private var contactRepo: ContactRepository? = null
    
    @Volatile
    private var noteRepo: NoteRepository? = null
    
    @Volatile
    private var calendarRepo: CalendarRepository? = null
    
    @Volatile
    private var taskRepo: TaskRepository? = null
    
    fun getAccountRepository(context: Context): AccountRepository {
        return accountRepo ?: synchronized(this) {
            accountRepo ?: AccountRepository(context.applicationContext).also { accountRepo = it }
        }
    }
    
    fun getMailRepository(context: Context): MailRepository {
        return mailRepo ?: synchronized(this) {
            mailRepo ?: MailRepository(context.applicationContext).also { mailRepo = it }
        }
    }
    
    fun getContactRepository(context: Context): ContactRepository {
        return contactRepo ?: synchronized(this) {
            contactRepo ?: ContactRepository(context.applicationContext).also { contactRepo = it }
        }
    }
    
    fun getNoteRepository(context: Context): NoteRepository {
        return noteRepo ?: synchronized(this) {
            noteRepo ?: NoteRepository(context.applicationContext).also { noteRepo = it }
        }
    }
    
    fun getCalendarRepository(context: Context): CalendarRepository {
        return calendarRepo ?: synchronized(this) {
            calendarRepo ?: CalendarRepository(context.applicationContext).also { calendarRepo = it }
        }
    }
    
    fun getTaskRepository(context: Context): TaskRepository {
        return taskRepo ?: synchronized(this) {
            taskRepo ?: TaskRepository(context.applicationContext).also { taskRepo = it }
        }
    }
    
    fun getSettingsRepository(context: Context): SettingsRepository {
        return SettingsRepository.getInstance(context.applicationContext)
    }
    
    /**
     * Очистка всех кэшированных репозиториев
     * Вызывается при выходе из приложения или смене пользователя
     */
    fun clear() {
        synchronized(this) {
            accountRepo = null
            mailRepo = null
            contactRepo = null
            noteRepo = null
            calendarRepo = null
            taskRepo = null
        }
    }
}
