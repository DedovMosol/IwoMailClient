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
                        
                        // Удаляем старые заметки для этого аккаунта
                        noteDao.deleteByAccount(accountId)
                        
                        // Добавляем новые
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
