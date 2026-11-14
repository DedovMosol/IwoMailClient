package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.withEasRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с заметками Exchange
 */
class NoteRepository internal constructor(
    database: MailDatabase,
    private val accountRepo: AccountRepository,
    private val offline: OfflineWrites = OfflineWrites.ALWAYS_ONLINE
) {

    constructor(context: Context) : this(
        MailDatabase.getInstance(context),
        RepositoryProvider.getAccountRepository(context),
        OfflineWrites.of(context)
    )

    private val noteDao = database.noteDao()

    /** Правки заметок без связи, ждущие отправки ([PendingPimChangeEntity]). */
    private val pendingDao = database.pendingPimChangeDao()

    /** Правка заметки ждёт отправки на сервер (сделана без связи). */
    suspend fun hasPendingChange(noteId: String): Boolean = pendingDao.get(noteId) != null

    /** Правка без связи: на устройстве — сразу, на сервер — когда появится связь. */
    private suspend fun keepNoteUpdate(note: NoteEntity, updatedNote: NoteEntity): NoteEntity {
        noteDao.update(updatedNote)
        pendingDao.markUpdate(note.id, note.accountId, PimKind.NOTE, note.subject)
        offline.schedule()
        return updatedNote
    }

    /** Перенос в корзину без связи: на устройстве — сразу, на сервер — когда появится связь. */
    private suspend fun keepNoteTrash(note: NoteEntity) {
        markNoteAsDeleted(note.id)
        pendingDao.markTrash(note.id, note.accountId, PimKind.NOTE, note.subject)
        offline.schedule()
    }

    private suspend fun keepNoteCreate(accountId: Long, subject: String, body: String): NoteEntity {
        val token = "local_note_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val note = NoteEntity(
            id = "${accountId}_$token",
            accountId = accountId,
            serverId = token,
            subject = subject,
            body = body,
            categories = "",
            lastModified = System.currentTimeMillis()
        )
        noteDao.insert(note)
        pendingDao.markCreate(note.id, accountId, PimKind.NOTE, subject)
        offline.schedule()
        return note
    }

    /** Без связи или за правкой этой заметки, ждущей отправки, — в очередь: порядок правок на сервере сохраняется. */
    private suspend fun mustQueue(noteId: String): Boolean = offline.offline() || pendingDao.get(noteId) != null

    /**
     * Перенос в корзину ещё не отправлен — на сервере заметка не в корзине: восстанавливаем только на устройстве.
     * @return true — восстановлено так.
     */
    private suspend fun restoreUnsentTrash(note: NoteEntity): Boolean {
        if (!pendingDao.cancelTrash(note.id)) return false
        noteDao.restore(note.id)
        return true
    }

    /**
     * Отправка правки из очереди: текущее состояние заметки — тем же путём (EAS 14+ или EWS на Exchange 2007), что и
     * правка со связью. Сервер отказал — запись снимается, и синхронизация вернёт его версию; связи нет — остаётся.
     */
    internal suspend fun sendPendingNote(change: PendingPimChangeEntity, resync: Boolean = true): PimSendOutcome {
        val note = noteDao.getNote(change.itemId) ?: run {
            pendingDao.delete(change.itemId) // заметки уже нет (удалена на сервере) — отправлять нечего
            return PimSendOutcome.DONE
        }
        if (offline.offline()) return PimSendOutcome.KEPT
        val client = accountRepo.createEasClient(note.accountId) ?: return PimSendOutcome.KEPT
        var working = note
        if (change.pendingCreate) {
            val created = client.createNote(note.subject, note.body)
            when (created) {
                is EasResult.Error -> {
                    if (offline.canDefer(created.message)) return PimSendOutcome.KEPT
                    android.util.Log.w("NoteRepository", "Pending note create rejected: ${created.message}")
                    pendingDao.delete(note.id)
                    return PimSendOutcome.DONE
                }
                is EasResult.Success -> {
                    val newId = "${note.accountId}_${created.data}"
                    val renamed = note.copy(id = newId, serverId = created.data)
                    noteDao.delete(note.id)
                    noteDao.insert(renamed)
                    pendingDao.rename(note.id, newId)
                    pendingDao.clearCreate(newId, change.version)
                    working = renamed
                }
            }
        }
        if (change.pendingUpdate) {
            val result = client.updateNote(working.serverId, working.subject, working.body)
            if (result is EasResult.Error) {
                if (offline.canDefer(result.message)) return PimSendOutcome.KEPT
                android.util.Log.w("NoteRepository", "Pending note update rejected: ${result.message}")
            }
            pendingDao.clearUpdate(working.id, change.version)
        }
        if (change.pendingTrash) {
            val first = client.deleteNote(working.serverId)
            val result = if (first is EasResult.Error && !offline.canDefer(first.message)) {
                // Сервер мог выдать заметке новый ServerId (UpdateItem на Exchange 2007) — как и без очереди
                // (deleteNote), синхронизация и поиск по содержимому среди активных
                syncNotes(note.accountId, skipRecentDeleteCheck = true)
                val fresh = noteDao.getAllNotesByAccountList(note.accountId)
                    .find { it.id != working.id && !it.isDeleted && noteContentMatches(it.subject, it.body, working.subject, working.body) }
                if (fresh != null) client.deleteNote(fresh.serverId) else first
            } else {
                first
            }
            if (result is EasResult.Error) {
                if (offline.canDefer(result.message)) return PimSendOutcome.KEPT
                android.util.Log.w("NoteRepository", "Pending note trash rejected: ${result.message}")
            }
            pendingDao.clearTrash(working.id, change.version)
        }
        // Сервер мог выдать заметке новый ServerId (перенос в Deleted Items, UpdateItem на Exchange 2007):
        // синхронизация сопоставит её с локальной записью.
        if (resync) syncNotes(working.accountId, skipRecentDeleteCheck = true)
        return PimSendOutcome.DONE
    }

    /** Синхронизация после отправки очереди: один раз на аккаунт, а не на каждую заметку. */
    internal suspend fun syncAfterPendingSent(accountId: Long) {
        syncNotes(accountId, skipRecentDeleteCheck = true)
    }

    /** Отправить правки заметок аккаунта, ждущие отправки. true — не осталось ни одной. */
    private suspend fun flushPending(accountId: Long): Boolean = PendingPimChanges(
        pendingDao,
        send = { sendPendingNote(it, resync = false) },
        afterSent = { account, _ -> syncAfterPendingSent(account) }
    ).flush(accountId, PimKind.NOTE)

    /**
     * Перед окончательным удалением: перенос в корзину, ещё не отправленный, — сначала на сервер. Иначе удаление по
     * ServerId корзины не нашло бы заметку (она ещё в «Заметках»), и синхронизация вернула бы её.
     *
     * После переноса сервер выдаёт заметке новый ServerId, и синхронизация пересоздаёт запись с новым id — заметки
     * находятся заново: по id, иначе по содержимому среди удалённых (как в [deleteNote]).
     * @return заметки с актуальными ServerId; null — связи нет, отправить не удалось.
     */
    private suspend fun sendTrashFirst(accountId: Long, notes: List<NoteEntity>): List<NoteEntity>? {
        if (notes.none { pendingDao.get(it.id) != null }) return notes
        if (!flushPending(accountId)) return null
        val current = noteDao.getAllNotesByAccountList(accountId)
        return notes.mapNotNull { note ->
            current.find { it.id == note.id }
                ?: current.find { it.isDeleted && noteContentMatches(it.subject, it.body, note.subject, note.body) }
        }
    }

    companion object {
        private val syncLocks = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
        private fun getSyncMutex(accountId: Long) = syncLocks.computeIfAbsent(accountId) { kotlinx.coroutines.sync.Mutex() }
    }

    private data class NoteContentKey(
        val subject: String,
        val body: String,
        val categories: String
    )

    private fun normalizedNotePart(value: String): String {
        return value.trim()
    }

    private fun noteContentMatches(
        subject: String,
        body: String,
        otherSubject: String,
        otherBody: String
    ): Boolean {
        return normalizedNotePart(subject) == normalizedNotePart(otherSubject) &&
            normalizedNotePart(body) == normalizedNotePart(otherBody)
    }

    private fun noteContentKey(subject: String, body: String, categories: String): NoteContentKey {
        return NoteContentKey(
            subject = normalizedNotePart(subject),
            body = normalizedNotePart(body),
            categories = normalizedNotePart(categories)
        )
    }

    private suspend fun markNoteAsDeleted(id: String) {
        noteDao.markAsDeleted(id, System.currentTimeMillis())
    }

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
        if (restoreUnsentTrash(note)) return EasResult.Success(true)
        return accountRepo.withEasClient(note.accountId, RepositoryErrors.NOTES_EXCHANGE_ONLY) { easClient ->
            try {
                android.util.Log.d("NotesDebug", "=== RESTORE NOTE START: subject='${note.subject.take(30)}', serverId=${note.serverId}")
                android.util.Log.d("NotesDebug", "Calling easClient.restoreNote...")
                // Восстанавливаем на сервере (перемещаем из Deleted Items в Notes)
                val result = withEasRetry { easClient.restoreNote(note.serverId) }

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
                        // Если сервер не вернул реальный ID — ищем в БД после синхронизации или создаём локально

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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        if (serverIds.isEmpty()) return EasResult.Success(0)
        return accountRepo.withEasClient(accountId, RepositoryErrors.NOTES_EXCHANGE_ONLY) { easClient ->
            // Корзина, ещё не отправленная на сервер, — сначала очередь; ServerId после неё другие — корзина
            // перечитывается (очищается вся корзина, как и просили).
            val targetIds = if (pendingDao.getItemIds(accountId, PimKind.NOTE).isEmpty()) {
                serverIds
            } else {
                if (!flushPending(accountId)) {
                    return@withEasClient EasResult.Error(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
                }
                noteDao.getDeletedNotesByAccountList(accountId).map { it.serverId }
            }
            var deletedCount = 0
            var failedCount = 0
            val total = targetIds.size

            for (serverId in targetIds) {
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
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return noteDao.searchNotes(accountId, escaped)
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
        return accountRepo.withEasClient(accountId, RepositoryErrors.NOTES_EXCHANGE_ONLY) { easClient ->
            try {
                val existingNotes = noteDao.getNotesByAccountList(accountId)
                val duplicate = existingNotes.find { existing ->
                    noteContentMatches(existing.subject, existing.body, subject, body)
                }
                if (duplicate != null) {
                    android.util.Log.w("NoteRepository",
                        "createNote: Duplicate detected (subject=$subject), returning existing")
                    return@withEasClient EasResult.Success(duplicate)
                }
                if (offline.offline()) {
                    return@withEasClient EasResult.Success(keepNoteCreate(accountId, subject, body))
                }

                val result = withEasRetry { easClient.createNote(subject, body) }

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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        return accountRepo.withEasClient(note.accountId, RepositoryErrors.NOTES_EXCHANGE_ONLY) { easClient ->
            try {
                val updatedNote = note.copy(subject = subject, body = body, lastModified = System.currentTimeMillis())
                if (mustQueue(note.id)) return@withEasClient EasResult.Success(keepNoteUpdate(note, updatedNote))

                val result = withEasRetry { easClient.updateNote(note.serverId, subject, body) }
                if (result is EasResult.Error && offline.canDefer(result.message)) {
                    return@withEasClient EasResult.Success(keepNoteUpdate(note, updatedNote))
                }

                when (result) {
                    is EasResult.Success -> {
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_UPDATE_ERROR)
            }
        }
    }

    /**
     * Удаление заметки на сервере
     * Заметка удаляется с сервера, при следующей синхронизации попадёт в Deleted Items
     */
    suspend fun deleteNote(note: NoteEntity): EasResult<Boolean> {
        return accountRepo.withEasClient(note.accountId, RepositoryErrors.NOTES_EXCHANGE_ONLY) { easClient ->
            try {
                if (mustQueue(note.id)) {
                    keepNoteTrash(note)
                    return@withEasClient EasResult.Success(true)
                }
                var updatedNote = note
                var result = easClient.deleteNote(note.serverId)
                if (result is EasResult.Error && offline.canDefer(result.message)) {
                    keepNoteTrash(note)
                    return@withEasClient EasResult.Success(true)
                }

                if (result is EasResult.Error) {
                    val syncResult = syncNotes(note.accountId, skipRecentDeleteCheck = true)
                    if (syncResult is EasResult.Success) {
                        val allNotes = noteDao.getAllNotesByAccountList(note.accountId)
                        val found = allNotes.find {
                            !it.isDeleted && noteContentMatches(it.subject, it.body, note.subject, note.body)
                        } ?: allNotes.find {
                            noteContentMatches(it.subject, it.body, note.subject, note.body) && it.serverId != note.serverId
                        }
                        if (found != null && found.serverId != note.serverId) {
                            updatedNote = found
                            result = easClient.deleteNote(found.serverId)
                        }
                    }
                }

                when (result) {
                    is EasResult.Success -> {
                        if (result.data != true) {
                            return@withEasClient EasResult.Error(RepositoryErrors.NOTE_DELETE_ERROR)
                        }

                        markNoteAsDeleted(updatedNote.id)

                        kotlinx.coroutines.delay(2000)
                        syncNotes(note.accountId, skipRecentDeleteCheck = true)

                        EasResult.Success(true)
                    }
                    is EasResult.Error -> {
                        if (result.message.contains("NOTE_NOT_FOUND") ||
                            result.message.contains("не найдена", ignoreCase = true) ||
                            result.message.contains("not found", ignoreCase = true)) {
                            markNoteAsDeleted(updatedNote.id)
                            EasResult.Success(true)
                        } else {
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        return accountRepo.withEasClient(note.accountId, RepositoryErrors.NOTES_EXCHANGE_ONLY) { easClient ->
            try {
                val target = sendTrashFirst(note.accountId, listOf(note))?.singleOrNull()
                    ?: return@withEasClient EasResult.Error(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
                val result = withEasRetry { easClient.deleteNotePermanently(target.serverId) }

                when (result) {
                    is EasResult.Success -> {
                        noteDao.delete(target.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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

            val byAccount = notes.groupBy { it.accountId }
            var totalDeleted = 0
            var totalFailed = 0
            val total = notes.size

            for ((accountId, accountNotes) in byAccount) {
                val account = accountRepo.getAccount(accountId)
                if (account == null || AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
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

                // Без связи или за правками, ждущими отправки, — в очередь (порядок правок на сервере сохраняется)
                val isOffline = offline.offline()
                val (queued, toSend) = accountNotes.partition { isOffline || pendingDao.get(it.id) != null }
                for (note in queued) keepNoteTrash(note)
                totalDeleted += queued.size
                onProgress(totalDeleted, total)
                if (toSend.isEmpty()) continue

                val batchResult = easClient.deleteNotesBatch(toSend.map { it.serverId })
                val batchOk = batchResult is EasResult.Success && batchResult.data > 0

                if (batchOk) {
                    for (note in toSend) {
                        markNoteAsDeleted(note.id)
                    }
                    totalDeleted += (batchResult as EasResult.Success).data
                    onProgress(totalDeleted, total)
                } else if (batchResult is EasResult.Error && offline.canDefer(batchResult.message)) {
                    for (note in toSend) keepNoteTrash(note)
                    totalDeleted += toSend.size
                    onProgress(totalDeleted, total)
                    continue
                } else {
                    val errMsg = (batchResult as? EasResult.Error)?.message ?: "batch returned 0"
                    android.util.Log.w("NotesDebug",
                        "deleteNotesWithProgress: batch failed ($errMsg), falling back to sequential")
                    for (note in toSend) {
                        val result = easClient.deleteNote(note.serverId)
                        when {
                            result is EasResult.Success -> {
                                markNoteAsDeleted(note.id)
                                totalDeleted++
                            }
                            result is EasResult.Error && offline.canDefer(result.message) -> {
                                keepNoteTrash(note)
                                totalDeleted++
                            }
                            else -> totalFailed++
                        }
                        onProgress(totalDeleted, total)
                    }
                }

                android.util.Log.d("NotesDebug", "Syncing after batch delete to update trash...")
                kotlinx.coroutines.delay(2000)
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
                if (account == null || AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
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
                    if (restoreUnsentTrash(note)) {
                        totalRestored++
                        onProgress(totalRestored, total)
                        continue
                    }
                    android.util.Log.d("NotesDebug", "=== RESTORE (batch): subject='${note.subject.take(30)}', serverId=${note.serverId}, isDeleted=${note.isDeleted}")
                    val result = easClient.restoreNote(note.serverId)
                    when (result) {
                        is EasResult.Success -> {
                            val newServerId = result.data
                            android.util.Log.d("NotesDebug", "Server returned newServerId: $newServerId (old was ${note.serverId})")
                            if (newServerId != note.serverId) {
                                android.util.Log.d("NotesDebug", "ServerId changed - deleting old locally, creating new")
                                // КРИТИЧНО: Сервер переместил заметку (новый serverId), удаляем старую запись из локальной БД и на сервере
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
                if (account == null || AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
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

                val targets = sendTrashFirst(accountId, accountNotes)
                if (targets == null) {
                    totalFailed += accountNotes.size
                    onProgress(totalDeleted, total)
                    continue
                }
                val batchResult = easClient.deleteNotesPermanentlyBatch(targets.map { it.serverId })
                val batchOk = batchResult is EasResult.Success && batchResult.data > 0

                if (batchOk) {
                    for (note in targets) {
                        noteDao.delete(note.id)
                    }
                    totalDeleted += (batchResult as EasResult.Success).data
                    onProgress(totalDeleted, total)
                } else {
                    val errMsg = (batchResult as? EasResult.Error)?.message ?: "batch returned 0"
                    android.util.Log.w("NotesDebug",
                        "deleteNotesPermanentlyWithProgress: batch failed ($errMsg), falling back to sequential")
                    for (note in targets) {
                        val result = easClient.deleteNotePermanently(note.serverId)
                        when (result) {
                            is EasResult.Success -> {
                                noteDao.delete(note.id)
                                totalDeleted++
                            }
                            is EasResult.Error -> totalFailed++
                        }
                        onProgress(totalDeleted, total)
                    }
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
    /**
     * Локальный сброс заметок + полный ресинк (ср. CalendarRepository.resetAndResync). Стираем все
     * локальные заметки аккаунта и перезагружаем с сервера. Сервер не трогаем. Кэш EAS-клиента
     * сбрасываем ОБЯЗАТЕЛЬНО: у заметок есть проба по кэшированному SyncKey («нет изменений → полный
     * синк пропускается»), иначе после стирания БД осталась бы пустой. [syncNotes] сам берёт
     * sync-мьютекс (ожидает), поэтому wipe — в отдельной критической секции, затем ресинк.
     */
    suspend fun resetAndResync(accountId: Long): EasResult<Int> {
        // Сброс стёр бы правки, сделанные без связи и ещё не отправленные: сначала — очередь
        if (!flushPending(accountId)) return EasResult.Error(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
        getSyncMutex(accountId).withLock {
            withContext(Dispatchers.IO) {
                noteDao.deleteByAccount(accountId)
            }
            accountRepo.clearEasClientCache(accountId)
        }
        return syncNotes(accountId, skipRecentDeleteCheck = true)
    }

    suspend fun syncNotes(accountId: Long, skipRecentDeleteCheck: Boolean = false): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            getSyncMutex(accountId).withLock {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)

                // Только для Exchange аккаунтов
                if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.NOTES_EXCHANGE_ONLY)
                }

                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)

                val result = easClient.syncNotes()

                when (result) {
                    is EasResult.Success -> {
                        val snapshot = result.data

                        if (snapshot.isUnchanged) {
                            return@withContext EasResult.Success(
                                noteDao.getAllNotesByAccountList(accountId).size
                            )
                        }

                        val serverNotes = snapshot.notes
                        // Заметки, существование которых сервер подтвердил без тела (EWS GetItem):
                        // их не трогаем — ни upsert'ом (затёр бы тело пустым), ни удалением.
                        val unresolvedServerIds = snapshot.unresolvedServerIds

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
                        val missingServerIds = existingActiveServerIds - activeServerIds - unresolvedServerIds

                        // Помечаем их как удалённые локально (не полностью удаляем) — ТОЛЬКО по полному
                        // снимку папки Notes: частичная выдача (обрыв, ошибка, лимит страниц) не доказывает,
                        // что заметки нет на сервере.
                        if (snapshot.isActiveAuthoritative) {
                            for (serverId in missingServerIds) {
                                val noteId = "${accountId}_${serverId}"
                                markNoteAsDeleted(noteId)
                            }
                        } else if (missingServerIds.isNotEmpty()) {
                            android.util.Log.w("NoteRepository", "syncNotes: active snapshot incomplete, keeping ${missingServerIds.size} local notes absent from it")
                        }

                        // Добавляем/обновляем АКТИВНЫЕ заметки с сервера
                        // КРИТИЧНО: НЕ перезаписываем записи, изменённые локально менее 10 сек назад
                        // Это защита от race condition когда sync получает устаревшие данные после update
                        val syncTime = System.currentTimeMillis()
                        val RECENT_EDIT_THRESHOLD = 10_000L // 10 секунд

                        // Phase 1: Collect duplicate IDs to delete.
                        // Дубль = локальная копия с другим serverId (сервер сменил id при перемещении).
                        // Вывод «старого id больше нет» верен только при полном снимке: в неполном
                        // отсутствовать может и отдельная заметка с тем же текстом.
                        // Правки без связи, ещё не отправленные: сервер их не видел — запись не перетираем, не считаем
                        // дублем и не возвращаем из корзины. Удаление на сервере по-прежнему сильнее.
                        val pendingIds = pendingDao.getItemIds(accountId, PimKind.NOTE).toHashSet()

                        val activeDuplicateIdsToDelete = mutableSetOf<String>()
                        if (snapshot.isActiveAuthoritative) {
                            for (note in activeServerNotes) {
                                val duplicate = existingNotes.find {
                                    it.id !in pendingIds &&
                                    it.serverId != note.serverId &&
                                        it.serverId !in unresolvedServerIds &&
                                        noteContentMatches(it.subject, it.body, note.subject, note.body)
                                }
                                if (duplicate != null) {
                                    if (duplicate.isDeleted && !skipRecentDeleteCheck) {
                                        val timeSinceDelete = syncTime - duplicate.lastModified
                                        if (timeSinceDelete < 30_000) continue
                                    }
                                    activeDuplicateIdsToDelete.add(duplicate.id)
                                }
                            }
                        }
                        // Phase 2: Delete duplicates
                        for (dupId in activeDuplicateIdsToDelete) {
                            noteDao.delete(dupId)
                        }
                        // Reload after Phase 1-2 mutations to avoid stale data in Phase 3
                        val freshExistingNotes = noteDao.getAllNotesByAccountList(accountId)
                        // Phase 3: Map to entities
                        val activeNoteEntities = activeServerNotes.mapNotNull { note ->
                            val noteId = "${accountId}_${note.serverId}"
                            if (noteId in pendingIds) return@mapNotNull null
                            val existingNote = freshExistingNotes.find { it.id == noteId }

                            if (existingNote != null) {
                                val timeSinceLocalEdit = syncTime - existingNote.lastModified

                                if (!skipRecentDeleteCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                    if (noteContentMatches(existingNote.subject, existingNote.body, note.subject, note.body)) {
                                        return@mapNotNull null
                                    }
                                    return@mapNotNull null
                                }

                                if (skipRecentDeleteCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                    val dataChanged = !noteContentMatches(existingNote.subject, existingNote.body, note.subject, note.body)
                                    val serverIdChanged = existingNote.serverId != note.serverId

                                    if (!dataChanged && !serverIdChanged) {
                                        return@mapNotNull null
                                    }
                                }
                            }

                            val duplicate = freshExistingNotes.find {
                                it.serverId != note.serverId &&
                                    noteContentMatches(it.subject, it.body, note.subject, note.body)
                            }
                            if (duplicate != null && duplicate.isDeleted && !skipRecentDeleteCheck) {
                                val timeSinceDelete = syncTime - duplicate.lastModified
                                if (timeSinceDelete < 30_000) {
                                    return@mapNotNull null
                                }
                            }

                            NoteEntity(
                                id = noteId,
                                accountId = accountId,
                                serverId = note.serverId,
                                subject = note.subject,
                                body = note.body,
                                categories = note.categories.joinToString(","),
                                lastModified = existingNote?.lastModified ?: note.lastModified,
                                isDeleted = false
                            )
                        }

                        // Добавляем/обновляем удалённые заметки с сервера (из Deleted Items)
                        val deletedServerNotes = serverNotes.filter { it.isDeleted }.distinctBy { it.serverId }
                        val deletedServerIds = deletedServerNotes.map { it.serverId }.toSet()

                        // Удалённые локально, которых уже нет НИ в активных НИ в Deleted Items на сервере
                        // (заметка была окончательно удалена на сервере, либо удалена другим клиентом)
                        // НЕ удаляем заметки, которые всё ещё есть в активных на сервере (ожидают перемещения в корзину)
                        // ТАКЖЕ НЕ удаляем свежеудалённые заметки (< 30 сек) - даём серверу время обработать
                        // И только если ОБЕ выдачи полны: окно Deleted Items без пагинации часто неполно,
                        // тогда отсутствие в нём ничего не доказывает и корзину не трогаем.
                        val missingDeletedServerIds = (existingDeletedServerIds - deletedServerIds - activeServerIds - unresolvedServerIds)
                            .filter { !it.startsWith("local_note_") }
                        if (snapshot.isActiveAuthoritative && snapshot.isDeletedAuthoritative) {
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
                        } else if (missingDeletedServerIds.isNotEmpty()) {
                            android.util.Log.w("NoteRepository", "syncNotes: snapshot incomplete, keeping ${missingDeletedServerIds.size} notes in local trash")
                        }

                        val freshNotesForDeletedPhase = noteDao.getAllNotesByAccountList(accountId)
                        val deletedDuplicateIdsToDelete = mutableSetOf<String>()
                        for (note in deletedServerNotes) {
                            val serverNoteKey = noteContentKey(
                                subject = note.subject,
                                body = note.body,
                                categories = note.categories.joinToString(",")
                            )
                            val duplicate = freshNotesForDeletedPhase.find {
                                it.isDeleted &&
                                    it.id !in pendingIds &&
                                    it.serverId != note.serverId &&
                                    it.serverId !in deletedServerIds &&
                                    it.serverId !in unresolvedServerIds &&
                                    noteContentKey(it.subject, it.body, it.categories) == serverNoteKey
                            }
                            if (duplicate != null) {
                                android.util.Log.d("NOT", "syncNotes: Found duplicate note (isDeleted=${duplicate.isDeleted}) serverId=${duplicate.serverId}, replacing with new serverId=${note.serverId}")
                                deletedDuplicateIdsToDelete.add(duplicate.id)
                            }
                        }
                        // Phase 2: Delete duplicates
                        for (dupId in deletedDuplicateIdsToDelete) {
                            noteDao.delete(dupId)
                        }
                        val freshDeletedExisting = noteDao.getAllNotesByAccountList(accountId)
                        // Phase 3: Map to entities
                        val deletedNoteEntities = deletedServerNotes.map { note ->
                            val noteId = "${accountId}_${note.serverId}"
                            val existingNote = freshDeletedExisting.find { it.id == noteId }

                            NoteEntity(
                                id = noteId,
                                accountId = accountId,
                                serverId = note.serverId,
                                subject = note.subject,
                                body = note.body,
                                categories = note.categories.joinToString(","),
                                lastModified = existingNote?.lastModified ?: note.lastModified,
                                isDeleted = true
                            )
                        }

                        val allNoteEntities = activeNoteEntities + deletedNoteEntities

                        if (allNoteEntities.isNotEmpty()) {
                            for (chunk in allNoteEntities.chunked(500)) {
                                noteDao.insertAll(chunk)
                            }
                        }

                        EasResult.Success(serverNotes.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.NOTE_SYNC_ERROR)
            }
            } // end withLock
        }
    }
}
