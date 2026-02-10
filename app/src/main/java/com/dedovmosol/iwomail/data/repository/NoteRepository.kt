package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с заметками Exchange
 */
class NoteRepository(private val context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val noteDao = database.noteDao()
    private val accountRepo = RepositoryProvider.getAccountRepository(context)
    
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
    
    // === Удалённые заметки (корзина) ===
    
    fun getDeletedNotes(accountId: Long): Flow<List<NoteEntity>> {
        return noteDao.getDeletedNotesByAccount(accountId)
    }
    
    fun getDeletedNotesCount(accountId: Long): Flow<Int> {
        return noteDao.getDeletedNotesCount(accountId)
    }
    
    suspend fun restoreNote(note: NoteEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("NotesDebug", "=== RESTORE NOTE START: subject='${note.subject.take(30)}', serverId=${note.serverId}")
                
                val account = accountRepo.getAccount(note.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(note.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                android.util.Log.d("NotesDebug", "Calling easClient.restoreNote...")
                // Восстанавливаем на сервере (перемещаем из Deleted Items в Notes)
                var result = easClient.restoreNote(note.serverId)
                
                // Retry при ошибке
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    kotlinx.coroutines.delay(1000)
                    result = easClient.restoreNote(note.serverId)
                }
                
                when (result) {
                    is EasResult.Success -> {
                        val newServerId = result.data
                        android.util.Log.d("NotesDebug", "Server returned newServerId: $newServerId (old was ${note.serverId})")
                        
                        // КРИТИЧНО: Удаляем старую запись ВСЕГДА
                        // При восстановлении сервер ВСЕГДА даёт новый serverId
                        android.util.Log.d("NotesDebug", "Deleting old note from DB: id=${note.id}")
                        noteDao.delete(note.id)
                        
                        // Обновляем локальную БД
                        if (newServerId != note.serverId && newServerId.isNotBlank()) {
                            android.util.Log.d("NotesDebug", "Creating new note in DB with newServerId")
                            // Сервер вернул новый serverId - создаём запись
                            val restoredNote = note.copy(
                                id = "${note.accountId}_$newServerId",
                                serverId = newServerId,
                                isDeleted = false
                            )
                            noteDao.insert(restoredNote)
                        } else {
                            android.util.Log.d("NotesDebug", "Server didn't return new ID - syncNotes will create proper entry")
                        }
                        // Если сервер не вернул новый ID - syncNotes создаст правильную запись

                        // КРИТИЧНО: Синхронизация получит актуальный serverId с сервера
                        // Это гарантирует что БД будет содержать правильный serverId
                        android.util.Log.d("NotesDebug", "Waiting 500ms before sync...")
                        kotlinx.coroutines.delay(2000) // Даём серверу время обработать MoveItem
                        android.util.Log.d("NotesDebug", "Syncing notes after restore...")
                        when (val syncResult = syncNotes(note.accountId, skipRecentDeleteCheck = true)) {
                            is EasResult.Success -> {
                                android.util.Log.d("NotesDebug", "=== RESTORE NOTE FINISH: SUCCESS")
                                EasResult.Success(true)
                            }
                            is EasResult.Error -> {
                                // Даже если sync не удался - восстановление на сервере прошло
                                android.util.Log.w("NotesDebug", "Sync failed but restore succeeded: ${syncResult.message}")
                                android.util.Log.d("NotesDebug", "=== RESTORE NOTE FINISH: SUCCESS (sync failed)")
                                EasResult.Success(true)
                            }
                        }
                    }
                    is EasResult.Error -> {
                        android.util.Log.e("NotesDebug", "=== RESTORE NOTE FINISH: FAILED - ${result.message}")
                        result
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NotesDebug", "=== RESTORE NOTE FINISH: EXCEPTION - ${e.message}")
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_RESTORE_ERROR)
            }
        }
    }
    
    suspend fun emptyNotesTrash(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            val deletedNotes = noteDao.getDeletedNotesByAccountList(accountId)
            if (deletedNotes.isEmpty()) return@withContext EasResult.Success(0)
            val serverIds = deletedNotes.map { it.serverId }
            emptyNotesTrashWithProgress(accountId, serverIds) { _, _ -> }
        }
    }

    suspend fun emptyNotesTrashWithProgress(
        accountId: Long,
        serverIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (serverIds.isEmpty()) return@withContext EasResult.Success(0)

            val account = accountRepo.getAccount(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)

            if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
            }

            val easClient = accountRepo.createEasClient(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)

            var deletedCount = 0
            var failedCount = 0
            val total = serverIds.size

            for (serverId in serverIds) {
                val result = easClient.deleteNotePermanently(serverId)
                when (result) {
                    is EasResult.Success -> {
                        noteDao.delete("${accountId}_$serverId")
                        deletedCount++
                    }
                    is EasResult.Error -> {
                        failedCount++
                    }
                }
                onProgress(deletedCount, total)
            }

            if (failedCount > 0) {
                EasResult.Error("Удалено: $deletedCount, ошибок: $failedCount")
            } else {
                EasResult.Success(deletedCount)
            }
        }
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
                // ЗАЩИТА ОТ ДУБЛИРОВАНИЯ: если идентичная заметка уже существует — возвращаем её
                val existingNotes = noteDao.getNotesByAccountList(accountId)
                val duplicate = existingNotes.find { existing ->
                    existing.subject == subject &&
                    existing.body == body
                }
                if (duplicate != null) {
                    android.util.Log.w("NoteRepository", 
                        "createNote: Duplicate detected (subject=$subject), returning existing")
                    return@withContext EasResult.Success(duplicate)
                }
                
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                var result = easClient.createNote(subject, body)
                
                // Retry при ошибке Status или конфликте
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    // Ждём и повторяем попытку
                    kotlinx.coroutines.delay(1000)
                    result = easClient.createNote(subject, body)
                }
                
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
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_CREATE_ERROR)
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
                if (account == null) {
                    return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                }
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(note.accountId)
                if (easClient == null) {
                    return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                }
                
                var result = easClient.updateNote(note.serverId, subject, body)
                
                // Retry при ошибке Status или конфликте
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    kotlinx.coroutines.delay(1000)
                    result = easClient.updateNote(note.serverId, subject, body)
                }
                
                when (result) {
                    is EasResult.Success -> {
                        val newLastModified = System.currentTimeMillis()
                        val updatedNote = note.copy(
                            subject = subject,
                            body = body,
                            lastModified = newLastModified
                        )
                        
                        noteDao.update(updatedNote)
                        
                        // КРИТИЧНО: Синхронизируем заметки для получения актуального serverId с сервера
                        // Это необходимо для последующего удаления (EWS DeleteItem требует ПОЛНЫЙ ItemId)
                        // Exchange 2007 SP1 требует 2 секунды для обработки UpdateItem
                        kotlinx.coroutines.delay(2000)
                        syncNotes(note.accountId, skipRecentDeleteCheck = true)
                        
                        EasResult.Success(updatedNote)
                    }
                    is EasResult.Error -> result
                }

            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_UPDATE_ERROR)
            }
        }
    }
    
    /**
     * Удаление заметки на сервере
     * Заметка удаляется с сервера, при следующей синхронизации попадёт в Deleted Items
     */
    suspend fun deleteNote(note: NoteEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("NotesDebug", "=== DELETE NOTE START: subject='${note.subject.take(30)}', serverId=${note.serverId}, isDeleted=${note.isDeleted}")
                
                val account = accountRepo.getAccount(note.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(note.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // КРИТИЧНО: Перед удалением синхронизируем для получения актуального serverId
                // Это необходимо если заметка была отредактирована/восстановлена и serverId изменился
                // ВАЖНО: skipRecentDeleteCheck=true чтобы обойти защиту от race condition
                android.util.Log.d("NotesDebug", "Syncing notes before delete...")
                val syncResult = syncNotes(note.accountId, skipRecentDeleteCheck = true)
                if (syncResult is EasResult.Error) {
                    android.util.Log.w("NotesDebug", "Sync failed: ${syncResult.message}")
                } else {
                    android.util.Log.d("NotesDebug", "Sync successful")
                }
                
                // Получаем обновлённую заметку из БД
                // ВАЖНО: Ищем по subject/body, т.к. id мог измениться после восстановления
                val allNotes = noteDao.getAllNotesByAccountList(note.accountId)
                android.util.Log.d("NotesDebug", "Total notes in DB: ${allNotes.size}")
                allNotes.forEach { n ->
                    android.util.Log.d("NotesDebug", "  - serverId=${n.serverId}, isDeleted=${n.isDeleted}, subject='${n.subject.take(20)}'")
                }
                
                // Сначала ищем среди активных (не удалённых) заметок
                var updatedNote = allNotes.find { 
                    !it.isDeleted && 
                    it.subject == note.subject && 
                    it.body == note.body 
                }
                android.util.Log.d("NotesDebug", "Found in active notes? ${updatedNote != null}")
                
                // Если не нашли среди активных - ищем среди ВСЕХ (включая удалённые)
                // Это может случиться если syncNotes ещё не обновил статус
                if (updatedNote == null) {
                    updatedNote = allNotes.find { 
                        it.subject == note.subject && 
                        it.body == note.body &&
                        it.serverId != note.serverId // Ищем с НОВЫМ serverId
                    }
                    android.util.Log.d("NotesDebug", "Found with different serverId? ${updatedNote != null}")
                }
                
                // Если всё ещё не нашли - используем оригинальную заметку
                if (updatedNote == null) {
                    updatedNote = note
                    android.util.Log.d("NotesDebug", "Using original note")
                }
                
                android.util.Log.d("NotesDebug", "Will delete: serverId=${updatedNote.serverId} (original was ${note.serverId})")
                
                android.util.Log.d("NotesDebug", "Calling easClient.deleteNote...")
                val result = easClient.deleteNote(updatedNote.serverId)
                
                when (result) {
                    is EasResult.Success -> {
                        // КРИТИЧНО: Проверяем result.data — EWS может вернуть Success(false)
                        // если сервер не подтвердил удаление (ответ не содержит NoError)
                        if (result.data != true) {
                            android.util.Log.w("NotesDebug", "Server returned Success(false) - deletion not confirmed")
                            android.util.Log.e("NotesDebug", "=== DELETE NOTE FINISH: FAILED - server did not confirm deletion")
                            return@withContext EasResult.Error(RepositoryErrors.NOTE_DELETE_ERROR)
                        }
                        
                        android.util.Log.d("NotesDebug", "SUCCESS: Marked as deleted locally")
                        
                        // Помечаем как удалённую локально (на сервере в Deleted Items)
                        noteDao.markAsDeleted(updatedNote.id)
                        
                        // КРИТИЧНО: Удаляем ВСЕ дубли с таким же subject/body
                        // Это происходит если Exchange изменил serverId после updateNote
                        val duplicates = allNotes.filter { 
                            it.id != updatedNote.id && 
                            !it.isDeleted &&
                            it.subject == updatedNote.subject && 
                            it.body == updatedNote.body
                        }
                        if (duplicates.isNotEmpty()) {
                            android.util.Log.d("NotesDebug", "Found ${duplicates.size} duplicates, deleting them")
                            duplicates.forEach { duplicate ->
                                android.util.Log.d("NotesDebug", "  - Deleting duplicate serverId=${duplicate.serverId}")
                                noteDao.delete(duplicate.id)
                            }
                        }
                        
                        // КРИТИЧНО: Синхронизируем СРАЗУ после удаления с skipRecentDeleteCheck=true
                        // Это позволяет сразу восстановить заметку без ошибки EWS
                        // Exchange 2007 SP1 требует 2 секунды для обработки MoveItems
                        android.util.Log.d("NotesDebug", "Syncing after delete to update trash...")
                        kotlinx.coroutines.delay(2000) // Даём серверу 2 секунды обработать удаление
                        val syncAfterDeleteResult = syncNotes(note.accountId, skipRecentDeleteCheck = true)
                        when (syncAfterDeleteResult) {
                            is EasResult.Success -> android.util.Log.d("NotesDebug", "Sync after delete: SUCCESS")
                            is EasResult.Error -> android.util.Log.w("NotesDebug", "Sync after delete: FAILED - ${syncAfterDeleteResult.message}")
                        }
                        
                        android.util.Log.d("NotesDebug", "=== DELETE NOTE FINISH: SUCCESS")
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> {
                        android.util.Log.w("NotesDebug", "ERROR from server: ${result.message}")
                        // Если заметка не найдена на сервере после синхронизации
                        // - возможно она была удалена другим клиентом
                        // Помечаем локально как удалённую
                        if (result.message.contains("NOTE_NOT_FOUND") || 
                            result.message.contains("не найдена", ignoreCase = true) ||
                            result.message.contains("not found", ignoreCase = true)) {
                            android.util.Log.d("NotesDebug", "Note not found on server - marking as deleted locally")
                            noteDao.markAsDeleted(updatedNote.id)
                            android.util.Log.d("NotesDebug", "=== DELETE NOTE FINISH: SUCCESS (not found)")
                            EasResult.Success(true)
                        } else {
                            android.util.Log.e("NotesDebug", "=== DELETE NOTE FINISH: FAILED - ${result.message}")
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NotesDebug", "=== DELETE NOTE FINISH: EXCEPTION - ${e.message}")
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_DELETE_ERROR)
            }
        }
    }

    /**
     * Удаление нескольких заметок (перемещение в корзину)
     */
    suspend fun deleteNotes(notes: List<NoteEntity>): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (notes.isEmpty()) return@withContext EasResult.Success(0)
            deleteNotesWithProgress(notes) { _, _ -> }
        }
    }

    /**
     * Восстановление нескольких заметок из корзины
     */
    suspend fun restoreNotes(notes: List<NoteEntity>): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (notes.isEmpty()) return@withContext EasResult.Success(0)
            restoreNotesWithProgress(notes) { _, _ -> }
        }
    }

    /**
     * Окончательное удаление заметки из корзины
     */
    suspend fun deleteNotePermanently(note: NoteEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(note.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)

                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }

                val easClient = accountRepo.createEasClient(note.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)

                var result = easClient.deleteNotePermanently(note.serverId)
                
                // Retry при ошибке
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    kotlinx.coroutines.delay(1000)
                    result = easClient.deleteNotePermanently(note.serverId)
                }

                when (result) {
                    is EasResult.Success -> {
                        noteDao.delete(note.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_DELETE_ERROR)
            }
        }
    }

    /**
     * Окончательное удаление нескольких заметок
     */
    suspend fun deleteNotesPermanently(notes: List<NoteEntity>): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (notes.isEmpty()) return@withContext EasResult.Success(0)
            deleteNotesPermanentlyWithProgress(notes) { _, _ -> }
        }
    }

    /**
     * Удаление нескольких заметок (soft-delete)
     * Используем один EasClient для всего пакета, чтобы избежать проблем с SyncKey
     */
    suspend fun deleteNotesWithProgress(
        notes: List<NoteEntity>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (notes.isEmpty()) return@withContext EasResult.Success(0)
            
            // Группируем по accountId
            val byAccount = notes.groupBy { it.accountId }
            var totalDeleted = 0
            var totalFailed = 0
            val total = notes.size
            var processed = 0
            
            for ((accountId, accountNotes) in byAccount) {
                val account = accountRepo.getAccount(accountId)
                if (account == null || AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    totalFailed += accountNotes.size
                    processed += accountNotes.size
                    onProgress(totalDeleted, total)
                    continue
                }
                
                // Один клиент для всех заметок этого аккаунта
                val easClient = accountRepo.createEasClient(accountId)
                if (easClient == null) {
                    totalFailed += accountNotes.size
                    processed += accountNotes.size
                    onProgress(totalDeleted, total)
                    continue
                }
                
                for (note in accountNotes) {
                    android.util.Log.d("NotesDebug", "=== DELETE (batch): subject='${note.subject.take(30)}', serverId=${note.serverId}, isDeleted=${note.isDeleted}")
                    val result = easClient.deleteNote(note.serverId)
                    when (result) {
                        is EasResult.Success -> {
                            noteDao.markAsDeleted(note.id)
                            totalDeleted++
                            android.util.Log.d("NotesDebug", "<<< SUCCESS: marked as deleted")
                        }
                        is EasResult.Error -> {
                            totalFailed++
                            android.util.Log.w("NotesDebug", "<<< ERROR: ${result.message}")
                        }
                    }
                    processed++
                    onProgress(totalDeleted, total)
                }
                
                // КРИТИЧНО: Синхронизируем СРАЗУ после удаления с skipRecentDeleteCheck=true
                // Это позволяет сразу восстановить заметки без ошибки EWS
                // Exchange 2007 SP1 требует 2 секунды для обработки MoveItems
                android.util.Log.d("NotesDebug", "Syncing after batch delete to update trash...")
                kotlinx.coroutines.delay(2000) // Даём серверу 2 секунды обработать удаление
                val syncAfterDeleteResult = syncNotes(accountId, skipRecentDeleteCheck = true)
                when (syncAfterDeleteResult) {
                    is EasResult.Success -> android.util.Log.d("NotesDebug", "Sync after batch delete: SUCCESS")
                    is EasResult.Error -> android.util.Log.w("NotesDebug", "Sync after batch delete: FAILED - ${syncAfterDeleteResult.message}")
                }
            }

            if (totalFailed > 0) {
                EasResult.Error("Удалено: $totalDeleted, ошибок: $totalFailed")
            } else {
                EasResult.Success(totalDeleted)
            }
        }
    }

    /**
     * Восстановление нескольких заметок с прогрессом
     * Используем один EasClient для всего пакета
     */
    suspend fun restoreNotesWithProgress(
        notes: List<NoteEntity>,
        onProgress: (restored: Int, total: Int) -> Unit
    ): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (notes.isEmpty()) return@withContext EasResult.Success(0)
            
            val byAccount = notes.groupBy { it.accountId }
            var totalRestored = 0
            var totalFailed = 0
            val total = notes.size
            val accountsToSync = mutableSetOf<Long>()
            
            for ((accountId, accountNotes) in byAccount) {
                val account = accountRepo.getAccount(accountId)
                if (account == null || AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    totalFailed += accountNotes.size
                    onProgress(totalRestored, total)
                    continue
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                if (easClient == null) {
                    totalFailed += accountNotes.size
                    onProgress(totalRestored, total)
                    continue
                }
                
                for (note in accountNotes) {
                    android.util.Log.d("NotesDebug", "=== RESTORE (batch): subject='${note.subject.take(30)}', serverId=${note.serverId}, isDeleted=${note.isDeleted}")
                    val result = easClient.restoreNote(note.serverId)
                    when (result) {
                        is EasResult.Success -> {
                            val newServerId = result.data
                            android.util.Log.d("NotesDebug", "Server returned newServerId: $newServerId (old was ${note.serverId})")
                            if (newServerId != note.serverId) {
                                android.util.Log.d("NotesDebug", "ServerId changed - deleting old locally, creating new")
                                // КРИТИЧНО: Сервер создал новую копию, нужно удалить старую из корзины на сервере
                                android.util.Log.d("NotesDebug", "Hard-deleting old serverId from server trash: ${note.serverId}")
                                easClient.deleteNotePermanently(note.serverId) // Не проверяем результат
                                
                                noteDao.delete(note.id)
                                val restoredNote = note.copy(
                                    id = "${note.accountId}_$newServerId",
                                    serverId = newServerId,
                                    isDeleted = false
                                )
                                noteDao.insert(restoredNote)
                            } else {
                                android.util.Log.d("NotesDebug", "ServerId unchanged - calling noteDao.restore")
                                noteDao.restore(note.id)
                            }
                            totalRestored++
                            accountsToSync.add(accountId)
                            android.util.Log.d("NotesDebug", "<<< SUCCESS: restored")
                        }
                        is EasResult.Error -> {
                            totalFailed++
                            android.util.Log.w("NotesDebug", "<<< ERROR: ${result.message}")
                        }
                    }
                    onProgress(totalRestored, total)
                }
            }

            if (totalFailed > 0) {
                EasResult.Error("Восстановлено: $totalRestored, ошибок: $totalFailed")
            } else {
                for (accountId in accountsToSync) {
                    when (val syncResult = syncNotes(accountId, skipRecentDeleteCheck = true)) {
                        is EasResult.Success -> Unit
                        is EasResult.Error -> return@withContext EasResult.Error(syncResult.message)
                    }
                }
                EasResult.Success(totalRestored)
            }
        }
    }

    /**
     * Окончательное удаление нескольких заметок с прогрессом
     * Используем один EasClient для всего пакета
     */
    suspend fun deleteNotesPermanentlyWithProgress(
        notes: List<NoteEntity>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (notes.isEmpty()) return@withContext EasResult.Success(0)
            
            val byAccount = notes.groupBy { it.accountId }
            var totalDeleted = 0
            var totalFailed = 0
            val total = notes.size
            
            for ((accountId, accountNotes) in byAccount) {
                val account = accountRepo.getAccount(accountId)
                if (account == null || AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    totalFailed += accountNotes.size
                    onProgress(totalDeleted, total)
                    continue
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                if (easClient == null) {
                    totalFailed += accountNotes.size
                    onProgress(totalDeleted, total)
                    continue
                }
                
                for (note in accountNotes) {
                    val result = easClient.deleteNotePermanently(note.serverId)
                    when (result) {
                        is EasResult.Success -> {
                            noteDao.delete(note.id)
                            totalDeleted++
                        }
                        is EasResult.Error -> {
                            totalFailed++
                        }
                    }
                    onProgress(totalDeleted, total)
                }
            }

            if (totalFailed > 0) {
                EasResult.Error("Удалено: $totalDeleted, ошибок: $totalFailed")
            } else {
                EasResult.Success(totalDeleted)
            }
        }
    }
    
    // === Синхронизация ===
    
    /**
     * Синхронизация заметок с Exchange сервера
     */
    suspend fun syncNotes(accountId: Long, skipRecentDeleteCheck: Boolean = false): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                // Только для Exchange аккаунтов
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                val result = easClient.syncNotes()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverNotes = result.data
                        
                        // Получаем ВСЕ существующие заметки
                        val existingNotes = noteDao.getAllNotesByAccountList(accountId)
                        val existingActiveNotes = existingNotes.filter { !it.isDeleted }
                        val existingDeletedNotes = existingNotes.filter { it.isDeleted }
                        val existingActiveServerIds = existingActiveNotes.map { it.serverId }.toSet()
                        val existingDeletedServerIds = existingDeletedNotes.map { it.serverId }.toSet()
                        
                        // Заметки с сервера, которые НЕ помечены как удалённые
                        val activeServerNotes = serverNotes.filter { !it.isDeleted }
                        val activeServerIds = activeServerNotes.map { it.serverId }.toSet()
                        
                        // Заметки, которые были активными локально, но исчезли с сервера (удалены в Outlook)
                        val missingServerIds = existingActiveServerIds - activeServerIds
                        
                        // Помечаем их как удалённые локально (не полностью удаляем)
                        for (serverId in missingServerIds) {
                            val noteId = "${accountId}_${serverId}"
                            noteDao.markAsDeleted(noteId)
                        }
                        
                        // Добавляем/обновляем АКТИВНЫЕ заметки с сервера
                        // КРИТИЧНО: НЕ перезаписываем записи, изменённые локально менее 10 сек назад
                        // Это защита от race condition когда sync получает устаревшие данные после update
                        val syncTime = System.currentTimeMillis()
                        val RECENT_EDIT_THRESHOLD = 10_000L // 10 секунд
                        
                        val activeNoteEntities = activeServerNotes.mapNotNull { note ->
                            val noteId = "${accountId}_${note.serverId}"
                            val existingNote = existingNotes.find { it.id == noteId }
                            
                            // Защита от race condition - НЕ перезаписываем свежие локальные изменения
                            if (existingNote != null) {
                                val timeSinceLocalEdit = syncTime - existingNote.lastModified
                                
                                // СЛУЧАЙ 1: Обычная синхронизация (skipRecentDeleteCheck=false)
                                if (!skipRecentDeleteCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                    // Если данные идентичны - пропускаем (оптимизация)
                                    if (existingNote.subject == note.subject && existingNote.body == note.body) {
                                        return@mapNotNull null
                                    }
                                    return@mapNotNull null // Блок - защита от race condition
                                }
                                
                                // СЛУЧАЙ 2: Явная синхронизация после update/delete/restore (skipRecentDeleteCheck=true)
                                // КРИТИЧНО: Разрешаем обновление ТОЛЬКО если сервер вернул ИЗМЕНЁННЫЕ данные
                                // Это защищает от потери данных если сервер не успел обработать UpdateItem
                                if (skipRecentDeleteCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                    // Проверяем: изменились ли данные или serverId?
                                    val dataChanged = existingNote.subject != note.subject || existingNote.body != note.body
                                    val serverIdChanged = existingNote.serverId != note.serverId
                                    
                                    if (!dataChanged && !serverIdChanged) {
                                        // Данные и serverId идентичны - сервер вернул ТО ЖЕ что у нас локально
                                        // НЕ перезаписываем (это может быть устаревший ответ из-за задержки репликации)
                                        return@mapNotNull null
                                    }
                                    // Продолжаем - обновляем т.к. данные или serverId изменились
                                }
                            }
                            
                            // Удаляем дубликаты с другим serverId
                            val duplicate = existingNotes.find { 
                                it.serverId != note.serverId && 
                                it.subject == note.subject && 
                                it.body == note.body
                            }
                            if (duplicate != null) {
                                // Если дубликат удалён недавно (< 30 сек) - НЕ восстанавливаем
                                // Даём серверу время обработать удаление
                                // ИСКЛЮЧЕНИЕ: При явном восстановлении (skipRecentDeleteCheck=true) пропускаем проверку
                                if (duplicate.isDeleted && !skipRecentDeleteCheck) {
                                    val timeSinceDelete = syncTime - duplicate.lastModified
                                    if (timeSinceDelete < 30_000) {
                                        return@mapNotNull null
                                    }
                                }
                                
                                noteDao.delete(duplicate.id)
                            }
                            
                            NoteEntity(
                                id = noteId,
                                accountId = accountId,
                                serverId = note.serverId,
                                subject = note.subject,
                                body = note.body,
                                categories = note.categories.joinToString(","),
                                lastModified = existingNote?.lastModified ?: note.lastModified, // Сохраняем локальный timestamp
                                isDeleted = false
                            )
                        }
                        
                        // Добавляем/обновляем удалённые заметки с сервера (из Deleted Items)
                        val deletedServerNotes = serverNotes.filter { it.isDeleted }
                        val deletedServerIds = deletedServerNotes.map { it.serverId }.toSet()
                        
                        // Удалённые локально, которых уже нет НИ в активных НИ в Deleted Items на сервере
                        // (заметка была окончательно удалена на сервере, либо удалена другим клиентом)
                        // НЕ удаляем заметки, которые всё ещё есть в активных на сервере (ожидают перемещения в корзину)
                        // ТАКЖЕ НЕ удаляем свежеудалённые заметки (< 30 сек) - даём серверу время обработать
                        val missingDeletedServerIds = existingDeletedServerIds - deletedServerIds - activeServerIds
                        val currentTime = System.currentTimeMillis()
                        for (serverId in missingDeletedServerIds) {
                            val noteId = "${accountId}_${serverId}"
                            val note = existingDeletedNotes.find { it.serverId == serverId }
                            
                            // Проверяем: прошло ли достаточно времени с момента последнего изменения
                            val timeSinceModified = currentTime - (note?.lastModified ?: 0)
                            if (timeSinceModified < 30_000) {
                                // Заметка изменена менее 30 секунд назад - даём серверу время
                                continue
                            }
                            
                            noteDao.delete(noteId)
                        }
                        
                        val deletedNoteEntities = deletedServerNotes.mapNotNull { note ->
                            val noteId = "${accountId}_${note.serverId}"
                            val existingNote = existingNotes.find { it.id == noteId }
                            
                            // КРИТИЧНО: Проверяем дубликаты перед добавлением
                            // Если заметка с таким subject/body уже есть локально (активная или удалённая)
                            // с ДРУГИМ serverId - НЕ добавляем новую, а обновляем существующую
                            val duplicate = existingNotes.find { 
                                it.serverId != note.serverId && 
                                it.subject == note.subject && 
                                it.body == note.body
                            }
                            
                            if (duplicate != null) {
                                // КРИТИЧНО: Удаляем старую запись и добавляем новую с актуальным serverId
                                // Это НЕОБХОДИМО для корректной работы restore - нужен актуальный EWS ItemId
                                android.util.Log.d("NOT", "syncNotes: Found duplicate note (isDeleted=${duplicate.isDeleted}) serverId=${duplicate.serverId}, replacing with new serverId=${note.serverId}")
                                noteDao.delete(duplicate.id)
                            }
                            
                            NoteEntity(
                                id = noteId,
                                accountId = accountId,
                                serverId = note.serverId,
                                subject = note.subject,
                                body = note.body,
                                categories = note.categories.joinToString(","),
                                lastModified = existingNote?.lastModified ?: note.lastModified, // Сохраняем локальный timestamp для удалённых
                                isDeleted = true
                            )
                        }
                        
                        val allNoteEntities = activeNoteEntities + deletedNoteEntities
                        
                        if (allNoteEntities.isNotEmpty()) {
                            // INSERT OR REPLACE — обновляет существующие
                            noteDao.insertAll(allNoteEntities)
                        }
                        
                        EasResult.Success(serverNotes.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_SYNC_ERROR)
            }
        }
    }
}
