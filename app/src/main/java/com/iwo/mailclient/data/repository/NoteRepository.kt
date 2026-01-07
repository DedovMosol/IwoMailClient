package com.iwo.mailclient.data.repository

import android.content.Context
import com.iwo.mailclient.data.database.*
import com.iwo.mailclient.eas.EasResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с заметками Exchange
 */
class NoteRepository(context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val noteDao = database.noteDao()
    private val accountRepo = AccountRepository(context)
    
    // === Получение заметок ===
    
    fun getNotes(accountId: Long): Flow<List<NoteEntity>> {
        return noteDao.getNotesByAccount(accountId)
    }
    
    suspend fun getNotesList(accountId: Long): List<NoteEntity> {
        return noteDao.getNotesByAccountList(accountId)
    }
    
    fun getNote(id: String): Flow<NoteEntity?> {
        return noteDao.getNoteFlow(id)
    }
    
    suspend fun getNoteById(id: String): NoteEntity? {
        return noteDao.getNote(id)
    }
    
    fun getNotesCount(accountId: Long): Flow<Int> {
        return noteDao.getNotesCount(accountId)
    }
    
    // === Поиск ===
    
    suspend fun searchNotes(accountId: Long, query: String): List<NoteEntity> {
        if (query.isBlank()) return getNotesList(accountId)
        return noteDao.searchNotes(accountId, query)
    }
    
    // === Создание/Редактирование/Удаление ===
    
    /**
     * Создание заметки на сервере и в локальной БД
     */
    suspend fun createNote(
        accountId: Long,
        subject: String,
        body: String
    ): EasResult<NoteEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Заметки поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.createNote(subject, body)
                
                when (result) {
                    is EasResult.Success -> {
                        val serverId = result.data
                        
                        // Если serverId похож на clientId (UUID без дефисов), 
                        // значит сервер не вернул реальный ID — нужна синхронизация
                        val isClientId = serverId.length == 32 && !serverId.contains(":")
                        
                        if (isClientId) {
                            // Сервер не вернул реальный ID — синхронизируем
                            syncNotes(accountId)
                            
                            // Ищем созданную заметку
                            val createdNote = noteDao.getNotesByAccountList(accountId)
                                .find { it.subject == subject }
                            
                            if (createdNote != null) {
                                EasResult.Success(createdNote)
                            } else {
                                // Заметка не найдена — создаём локально
                                val note = NoteEntity(
                                    id = "${accountId}_${serverId}",
                                    accountId = accountId,
                                    serverId = serverId,
                                    subject = subject,
                                    body = body,
                                    categories = "",
                                    lastModified = System.currentTimeMillis()
                                )
                                noteDao.insert(note)
                                EasResult.Success(note)
                            }
                        } else {
                            // Сервер вернул реальный ID — сохраняем сразу
                            val note = NoteEntity(
                                id = "${accountId}_${serverId}",
                                accountId = accountId,
                                serverId = serverId,
                                subject = subject,
                                body = body,
                                categories = "",
                                lastModified = System.currentTimeMillis()
                            )
                            noteDao.insert(note)
                            EasResult.Success(note)
                        }
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка создания заметки")
            }
        }
    }
    
    /**
     * Обновление заметки
     */
    suspend fun updateNote(
        note: NoteEntity,
        subject: String,
        body: String
    ): EasResult<NoteEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(note.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Заметки поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(note.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.updateNote(note.serverId, subject, body)
                
                when (result) {
                    is EasResult.Success -> {
                        val updatedNote = note.copy(
                            subject = subject,
                            body = body,
                            lastModified = System.currentTimeMillis()
                        )
                        noteDao.update(updatedNote)
                        EasResult.Success(updatedNote)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка обновления заметки")
            }
        }
    }
    
    /**
     * Удаление заметки
     */
    suspend fun deleteNote(note: NoteEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(note.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Заметки поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(note.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.deleteNote(note.serverId)
                
                when (result) {
                    is EasResult.Success -> {
                        noteDao.delete(note.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка удаления заметки")
            }
        }
    }
    
    // === Синхронизация ===
    
    /**
     * Синхронизация заметок с Exchange сервера
     */
    suspend fun syncNotes(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                // Только для Exchange аккаунтов
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Заметки поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.syncNotes()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverNotes = result.data
                        
                        // Получаем существующие заметки
                        val existingNotes = noteDao.getNotesByAccountList(accountId)
                        val existingServerIds = existingNotes.map { it.serverId }.toSet()
                        
                        // Определяем какие заметки удалены на сервере
                        val serverIds = serverNotes.map { it.serverId }.toSet()
                        val deletedServerIds = existingServerIds - serverIds
                        
                        // Удаляем только те, которых нет на сервере
                        for (serverId in deletedServerIds) {
                            val noteId = "${accountId}_${serverId}"
                            noteDao.delete(noteId)
                        }
                        
                        // Добавляем/обновляем заметки с сервера
                        val noteEntities = serverNotes.map { note ->
                            NoteEntity(
                                id = "${accountId}_${note.serverId}",
                                accountId = accountId,
                                serverId = note.serverId,
                                subject = note.subject,
                                body = note.body,
                                categories = note.categories.joinToString(","),
                                lastModified = note.lastModified
                            )
                        }
                        
                        if (noteEntities.isNotEmpty()) {
                            // INSERT OR REPLACE — обновляет существующие
                            noteDao.insertAll(noteEntities)
                        }
                        
                        EasResult.Success(noteEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка синхронизации заметок")
            }
        }
    }
}
