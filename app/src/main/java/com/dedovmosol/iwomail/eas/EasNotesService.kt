package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сервис для работы с заметками Exchange (EAS/EWS)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 * 
 * Отвечает за:
 * - Синхронизацию заметок (Notes)
 * - Создание, обновление, удаление заметок
 * - Восстановление удалённых заметок
 * - Работу с EWS для Exchange 2007
 */
class EasNotesService internal constructor(
    private val deps: NotesServiceDependencies
) {

    private val syncKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        // EAS Sync — один потоковый проход EasSyncStream. EWS-поля — на EasXmlParser.
        // Намеренно оставлен EWS_ITEM_PATTERN: один проход по <t:PostItem>|<t:Message> сохраняет
        // ДОКУМЕНТНЫЙ порядок элементов (два раздельных forEachElement дали бы все PostItem, затем все Message).
        private val EWS_ITEM_PATTERN = "<t:(?:PostItem|Message)[^>]*>(.*?)</t:(?:PostItem|Message)>".toRegex(RegexOption.DOT_MATCHES_ALL)

        /** Листья ApplicationData заметки (MS-ASNOTE), которые читает [parseNotesResponse]. */
        private val NOTE_FIELDS = setOf("Subject", "MessageClass", "LastModifiedDate")
    }
    
    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }
    
    /**
     * Зависимости для EasNotesService
     */
    class NotesServiceDependencies(
        val executeEasCommand: EasCommandExecutor,
        val folderSync: suspend (String) -> EasResult<FolderSyncResponse>,
        val refreshSyncKey: suspend (String, String) -> EasResult<String>,
        val extractValue: (String, String) -> String?,
        val escapeXml: (String) -> String,
        val getEasVersion: () -> String,
        val isVersionDetected: () -> Boolean,
        val detectEasVersion: suspend () -> EasResult<String>,
        val getNotesFolderId: suspend () -> String?,
        val getDeletedItemsFolderId: suspend () -> String?,
        val performNtlmHandshake: suspend () -> String?,
        val executeNtlmRequest: suspend (String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String) -> String?,
        val findEwsNoteItemId: suspend (String, Boolean) -> String?
    )
    
    // Кэш ID папки заметок
    @Volatile private var cachedNotesFolderId: String? = null

    private class InvalidSyncKeyException(status: String) : Exception("InvalidSyncKey: Status=$status")

    /**
     * Получает валидный SyncKey для папки: сначала из кэша, при ошибке — через "0".
     * Обновляет syncKeyCache при успехе, очищает при невалидном ключе.
     */
    private suspend fun getValidSyncKey(folderId: String): String? {
        val cachedKey = syncKeyCache[folderId]
        if (cachedKey != null) {
            val result = deps.refreshSyncKey(folderId, cachedKey)
            when (result) {
                is EasResult.Success -> {
                    val key = result.data
                    if (key != "0") {
                        syncKeyCache[folderId] = key
                        return key
                    }
                    syncKeyCache.remove(folderId)
                }
                is EasResult.Error -> {
                    syncKeyCache.remove(folderId)
                }
            }
        }
        val result = deps.refreshSyncKey(folderId, "0")
        return when (result) {
            is EasResult.Success -> {
                val key = result.data
                if (key != "0") {
                    syncKeyCache[folderId] = key
                    key
                } else null
            }
            is EasResult.Error -> null
        }
    }

    /**
     * Синхронизация заметок
     * EAS 14.1+ — стандартный Notes sync
     * EAS 12.x — fallback через EWS
     * Полнота снимка — в [NotesSyncResult]: удалять локальные заметки можно только по полной выдаче.
     */
    suspend fun syncNotes(): EasResult<NotesSyncResult> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("EasNotesService", "syncNotes: EAS version = ${deps.getEasVersion()}")
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            val foldersResult = deps.folderSync("0")
            val folders = when (foldersResult) {
                is EasResult.Success -> foldersResult.data.folders
                is EasResult.Error -> return EasResult.Error(foldersResult.message)
            }
            syncNotesStandard(folders)
        } else {
            syncNotesEws()
        }
    }
    
    /**
     * Создание заметки
     */
    suspend fun createNote(subject: String, body: String): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            createNoteEas(subject, body)
        } else {
            createNoteEws(subject, body)
        }
    }
    
    /**
     * Обновление заметки
     */
    suspend fun updateNote(serverId: String, subject: String, body: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            updateNoteEas(serverId, subject, body)
        } else {
            updateNoteEws(serverId, subject, body)
        }
    }
    
    /**
     * Удаление заметки (в корзину)
     */
    suspend fun deleteNote(serverId: String, subject: String = ""): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12

        return if (majorVersion >= 14) {
            deleteNoteEas(serverId)
        } else {
            deleteNoteEws(serverId, subject)
        }
    }
    
    /**
     * Окончательное удаление заметки
     */
    suspend fun deleteNotePermanently(serverId: String, subject: String = ""): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12

        return if (majorVersion >= 14) {
            deleteNotePermanentlyEas(serverId)
        } else {
            deleteNotePermanentlyEws(serverId, subject)
        }
    }
    
    /**
     * Batch-удаление заметок (в корзину). Один EWS DeleteItem на весь пакет.
     * @return количество успешно удалённых
     */
    suspend fun deleteNotesBatch(serverIds: List<String>): EasResult<Int> {
        if (serverIds.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        return if (majorVersion >= 14) {
            // EAS: по одному (нет batch в EAS Sync Delete)
            var deleted = 0
            for (sid in serverIds) {
                if (deleteNoteEas(sid) is EasResult.Success) deleted++
            }
            EasResult.Success(deleted)
        } else {
            deleteNotesBatchEws(serverIds, "MoveToDeletedItems")
        }
    }

    /**
     * Batch окончательное удаление заметок (HardDelete). Один EWS DeleteItem на весь пакет.
     */
    suspend fun deleteNotesPermanentlyBatch(serverIds: List<String>): EasResult<Int> {
        if (serverIds.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        return if (majorVersion >= 14) {
            var deleted = 0
            for (sid in serverIds) {
                if (deleteNotePermanentlyEas(sid) is EasResult.Success) deleted++
            }
            EasResult.Success(deleted)
        } else {
            deleteNotesBatchEws(serverIds, "HardDelete")
        }
    }

    /**
     * Восстановление заметки из корзины
     */
    suspend fun restoreNote(serverId: String, subject: String = ""): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12

        return if (majorVersion >= 14) {
            restoreNoteEas(serverId)
        } else {
            restoreNoteEws(serverId, subject)
        }
    }
    
    // ==================== Private EAS methods ====================
    
    private suspend fun syncNotesStandard(folders: List<EasFolder>): EasResult<NotesSyncResult> {
        val notesFolderId = folders.find { it.type == FolderType.NOTES }?.serverId
            ?: return EasResult.Error("Папка Notes (type=10) не найдена")
        
        cachedNotesFolderId = notesFolderId

        val cachedKey = syncKeyCache[notesFolderId]
        if (cachedKey != null) {
            val probeXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(cachedKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(notesFolderId)}</CollectionId>
            <GetChanges>1</GetChanges>
            <WindowSize>1</WindowSize>
        </Collection>
    </Collections>
</Sync>""".trimIndent()

            val probeResult = deps.executeEasCommand("Sync", probeXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                val newKey = deps.extractValue(responseXml, "SyncKey")
                Triple(status, newKey, responseXml)
            }

            if (probeResult is EasResult.Success) {
                val (status, newKey, responseXml) = probeResult.data
                when {
                    status == "1" || status == null -> {
                        val hasCommands = responseXml.contains("<Add>") ||
                                responseXml.contains("<Change>") ||
                                responseXml.contains("<Delete>") ||
                                responseXml.contains("<SoftDelete>") ||
                                responseXml.contains("<MoreAvailable")
                        if (!hasCommands) {
                            if (newKey != null) syncKeyCache[notesFolderId] = newKey
                            if (BuildConfig.DEBUG) android.util.Log.d("EasNotesService",
                                "syncNotesStandard: No changes detected (probe), skipping full sync")
                            return EasResult.Success(NotesSyncResult(
                                notes = emptyList(),
                                isActiveAuthoritative = false,
                                isDeletedAuthoritative = false,
                                isUnchanged = true
                            ))
                        }
                    }
                    status == "3" || status == "12" -> {
                        syncKeyCache.remove(notesFolderId)
                    }
                }
            }
        }
        
        // Активные заметки (полная синхронизация). Сбой основной папки — ошибка синка: раньше он
        // глотался, и пустой список помечал удалёнными ВСЕ локальные заметки.
        val active = when (val result = syncNotesFromFolder(notesFolderId)) {
            is EasResult.Success -> result.data
            is EasResult.Error -> return result
        }
        
        // Удалённые заметки из Deleted Items. Сбой здесь синк не валит: корзину просто не сверяем.
        val deletedItemsFolderId = folders.find { it.type == FolderType.DELETED_ITEMS }?.serverId
        val deleted = deletedItemsFolderId?.let { folderId ->
            when (val result = syncDeletedNotesFromFolder(folderId)) {
                is EasResult.Success -> result.data
                is EasResult.Error -> {
                    android.util.Log.w("EasNotesService", "syncNotesStandard: Deleted Items sync failed: ${result.message}")
                    null
                }
            }
        }
        
        return EasResult.Success(NotesSyncResult(
            notes = active.notes + deleted?.notes.orEmpty(),
            isActiveAuthoritative = active.isComplete,
            isDeletedAuthoritative = deleted?.isComplete == true
        ))
    }

    /** Выборка одной папки заметок; [isComplete] — сервер подтвердил конец выдачи. */
    private class FolderNotes(val notes: List<EasNote>, val isComplete: Boolean)

    /** Страница EAS Sync заметок. */
    private class NotesSyncPage(
        val status: String,
        val syncKey: String?,
        val notes: List<EasNote>,
        val moreAvailable: Boolean
    )

    private fun parseNotesSyncPage(responseXml: String): NotesSyncPage {
        // MS-ASCMD: Status коллекции обязателен; пустое тело ответа транспорт подменяет на Status=1.
        val status = deps.extractValue(responseXml, "Status") ?: "1"
        if (status != "1") return NotesSyncPage(status, syncKey = null, notes = emptyList(), moreAvailable = false)
        return NotesSyncPage(
            status = status,
            syncKey = deps.extractValue(responseXml, "SyncKey"),
            notes = parseNotesResponse(responseXml),
            moreAvailable = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
        )
    }

    private suspend fun syncNotesFromFolder(folderId: String): EasResult<FolderNotes> {
        val initialXml = EasXmlTemplates.syncInitial(folderId)
        
        var syncKey = when (val result = deps.executeEasCommand("Sync", initialXml) { deps.extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> result.data
            is EasResult.Error -> return result
        }
        if (syncKey == "0") return EasResult.Error("Sync заметок: сервер не выдал SyncKey")
        
        val allNotes = mutableListOf<EasNote>()
        // Папка выдана целиком, только если сервер сам сообщил конец выдачи (MoreAvailable исчерпан).
        var incompleteReason: String? = null
        var keyInvalidated = false
        var iterations = 0
        val maxIterations = 50 // До 5000 заметок (windowSize=100)
        
        while (true) {
            if (iterations >= maxIterations) {
                incompleteReason = "лимит $maxIterations страниц"
                break
            }
            iterations++
            val syncXml = EasXmlTemplates.syncWithBody(syncKey, folderId)
            
            val page = when (val result = deps.executeEasCommand("Sync", syncXml) { parseNotesSyncPage(it) }) {
                is EasResult.Success -> result.data
                is EasResult.Error -> {
                    incompleteReason = result.message
                    break
                }
            }
            if (page.status != "1") {
                // 3 — Invalid SyncKey, 12 — иерархия папок изменилась: ключ недействителен.
                if (page.status == "3" || page.status == "12") {
                    syncKeyCache.remove(folderId)
                    keyInvalidated = true
                }
                incompleteReason = "Status=${page.status}"
                break
            }
            
            page.syncKey?.let { syncKey = it }
            allNotes.addAll(page.notes)
            if (!page.moreAvailable) break
            // MoreAvailable без данных: сервер обещает продолжение, но не отдаёт его.
            if (page.notes.isEmpty()) {
                incompleteReason = "MoreAvailable без данных"
                break
            }
        }

        if (!keyInvalidated && syncKey != "0") {
            syncKeyCache[folderId] = syncKey
        }

        if (incompleteReason != null) {
            android.util.Log.w("EasNotesService",
                "syncNotesFromFolder: incomplete ($incompleteReason), ${allNotes.size} notes, deletions disabled")
            // Сбой до первой заметки — ошибка синка, а не «пустая папка».
            if (allNotes.isEmpty()) return EasResult.Error("Синхронизация заметок прервана: $incompleteReason")
        }
        return EasResult.Success(FolderNotes(allNotes, isComplete = incompleteReason == null))
    }
    
    private suspend fun syncDeletedNotesFromFolder(deletedItemsFolderId: String): EasResult<FolderNotes> {
        val initialXml = EasXmlTemplates.syncInitial(deletedItemsFolderId)
        
        val syncKey = when (val result = deps.executeEasCommand("Sync", initialXml) { deps.extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> result.data
            is EasResult.Error -> return result
        }
        if (syncKey == "0") return EasResult.Error("Sync Deleted Items: сервер не выдал SyncKey")
        
        // Одно окно (WindowSize=100) без пагинации: в Deleted Items обычно тысячи писем, листать их
        // ради заметок дорого. Окно полно, только если сервер не сообщил MoreAvailable.
        val syncXml = EasXmlTemplates.syncWithBody(syncKey, deletedItemsFolderId)
        val page = when (val result = deps.executeEasCommand("Sync", syncXml) { parseNotesSyncPage(it) }) {
            is EasResult.Success -> result.data
            is EasResult.Error -> return result
        }
        if (page.status != "1") return EasResult.Error("Sync Deleted Items: Status=${page.status}")
        
        val notes = page.notes
            .filter { it.messageClass == "IPM.StickyNote" || it.messageClass.isBlank() }
            .map { it.copy(isDeleted = true) }
        return EasResult.Success(FolderNotes(notes, isComplete = !page.moreAvailable))
    }
    
    private suspend fun createNoteEas(subject: String, body: String): EasResult<String> {
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")
        
        cachedNotesFolderId = notesFolderId

        for (attempt in 0..1) {
            val syncKey = getValidSyncKey(notesFolderId)
                ?: return EasResult.Error("Не удалось получить SyncKey")

            val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)

            val safeFolderId = deps.escapeXml(notesFolderId)
            val safeSyncKey = deps.escapeXml(syncKey)
            val createXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
    <Collections>
        <Collection>
            <SyncKey>$safeSyncKey</SyncKey>
            <CollectionId>$safeFolderId</CollectionId>
            <Commands>
                <Add>
                    <ClientId>${deps.escapeXml(clientId)}</ClientId>
                    <ApplicationData>
                        <notes:Subject>$escapedSubject</notes:Subject>
                        <airsyncbase:Body>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                        </airsyncbase:Body>
                        <notes:MessageClass>IPM.StickyNote</notes:MessageClass>
                    </ApplicationData>
                </Add>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()

            val result = deps.executeEasCommand("Sync", createXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                if (status == "1") {
                    val newKey = deps.extractValue(responseXml, "SyncKey")
                    if (newKey != null) syncKeyCache[notesFolderId] = newKey
                    deps.extractValue(responseXml, "ServerId") ?: clientId
                } else if (status == "3" || status == "12") {
                    syncKeyCache.remove(notesFolderId)
                    throw InvalidSyncKeyException(status)
                } else {
                    throw Exception("Ошибка создания заметки: Status=$status")
                }
            }

            when (result) {
                is EasResult.Success -> return result
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) continue
                    return result
                }
            }
        }
        return EasResult.Error("Не удалось создать заметку после retry")
    }
    
    private suspend fun updateNoteEas(serverId: String, subject: String, body: String): EasResult<Boolean> {
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")

        for (attempt in 0..1) {
            val syncKey = getValidSyncKey(notesFolderId)
                ?: return EasResult.Error("Не удалось получить SyncKey")

            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)

            val updateXml = EasXmlTemplates.noteUpdate(syncKey, notesFolderId, serverId, escapedSubject, escapedBody)

            val result = deps.executeEasCommand("Sync", updateXml) { responseXml ->
                val collectionStatus = deps.extractValue(responseXml, "Status")
                if (collectionStatus == "3" || collectionStatus == "12") {
                    syncKeyCache.remove(notesFolderId)
                    throw InvalidSyncKeyException(collectionStatus)
                }
                if (collectionStatus != "1") {
                    throw Exception("Collection Status=$collectionStatus")
                }

                val newKey = deps.extractValue(responseXml, "SyncKey")
                if (newKey != null) syncKeyCache[notesFolderId] = newKey

                if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                    val changeStatusMatch = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
                        .find(responseXml)

                    if (changeStatusMatch != null) {
                        val changeStatus = changeStatusMatch.groupValues[1]
                        when (changeStatus) {
                            "1" -> true
                            "6" -> throw Exception("Change Status=6: Error in client/server conversion (invalid item)")
                            "7" -> throw Exception("Change Status=7: Conflict (server changes take precedence)")
                            "8" -> throw Exception("Change Status=8: Object not found on server")
                            else -> throw Exception("Change Status=$changeStatus")
                        }
                    } else {
                        true
                    }
                } else {
                    true
                }
            }

            when (result) {
                is EasResult.Success -> return result
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) continue
                    return result
                }
            }
        }
        return EasResult.Error("Не удалось обновить заметку после retry")
    }
    
    private suspend fun deleteNoteEas(serverId: String): EasResult<Boolean> {
        val folderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")
        return deleteNoteEasImpl(serverId, folderId, deletesAsMoves = true)
    }

    private suspend fun deleteNotePermanentlyEas(serverId: String): EasResult<Boolean> {
        val folderId = deps.getDeletedItemsFolderId()
            ?: return EasResult.Error("Папка Deleted Items не найдена")
        return deleteNoteEasImpl(serverId, folderId, deletesAsMoves = false)
    }

    /**
     * MS-ASCMD 2.2.1.21 Sync — Delete command.
     * deletesAsMoves=true перемещает в Deleted Items; false — окончательное удаление.
     */
    private suspend fun deleteNoteEasImpl(
        serverId: String,
        folderId: String,
        deletesAsMoves: Boolean
    ): EasResult<Boolean> {
        for (attempt in 0..1) {
            val syncKey = getValidSyncKey(folderId)
            if (syncKey == null) {
                if (attempt < 1) continue
                return EasResult.Error("Не удалось получить SyncKey")
            }

            val deleteXml = EasXmlTemplates.syncDelete(syncKey, folderId, serverId, deletesAsMoves)

            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                when (status) {
                    "1" -> {
                        val newKey = deps.extractValue(responseXml, "SyncKey")
                        if (newKey != null) syncKeyCache[folderId] = newKey
                        true
                    }
                    "8" -> true
                    "3", "12" -> {
                        syncKeyCache.remove(folderId)
                        throw InvalidSyncKeyException(status)
                    }
                    else -> false
                }
            }

            when (result) {
                is EasResult.Success -> {
                    if (result.data) return EasResult.Success(true)
                    if (attempt < 1) continue
                    return EasResult.Error("Не удалось удалить заметку")
                }
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) continue
                    return result
                }
            }
        }

        return EasResult.Error("Не удалось удалить заметку после retry")
    }
    
    private suspend fun restoreNoteEas(serverId: String): EasResult<String> {
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")
        
        val deletedItemsFolderId = deps.getDeletedItemsFolderId()
            ?: return EasResult.Error("Папка Deleted Items не найдена")
        
        // MoveItems для перемещения из Deleted Items в Notes
        val moveXml = EasXmlTemplates.moveItems(
            listOf(serverId to deletedItemsFolderId),
            notesFolderId
        )
        
        return deps.executeEasCommand("MoveItems", moveXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "3") { // 3 = Success для MoveItems
                deps.extractValue(responseXml, "DstMsgId") ?: serverId
            } else {
                throw Exception("Ошибка восстановления: Status=$status")
            }
        }
    }
    
    // ==================== Private EWS methods ====================
    
    private suspend fun executeEwsWithAuth(soapRequest: String, operation: String): String? {
        var responseXml = deps.tryBasicAuthEws(soapRequest, operation)
        if (responseXml == null) {
            deps.performNtlmHandshake() ?: return null
            responseXml = deps.executeNtlmRequest(soapRequest, operation)
        }
        return responseXml
    }
    
    private suspend fun syncNotesEws(): EasResult<NotesSyncResult> = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: START - syncing ACTIVE notes")
            
            // === 1. Синхронизация АКТИВНЫХ заметок из папки "notes" ===
            val findRequest = EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem("notes"))
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: FindItem (notes) request length=${findRequest.length}")
            
            // Сбой основной папки — ошибка синка. Раньше он давал Success(emptyList()),
            // и репозиторий помечал удалёнными ВСЕ локальные заметки.
            val responseXml = executeEwsWithAuth(findRequest, "FindItem")
            if (responseXml == null) {
                android.util.Log.e("NOT", "syncNotesEws: Auth FAILED for notes")
                return@withContext EasResult.Error("EWS FindItem (notes): нет ответа сервера")
            }
            if (!EwsClient.isEwsSuccess(responseXml)) {
                val code = EasPatterns.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1) ?: "ошибка"
                android.util.Log.e("NOT", "syncNotesEws: FindItem (notes) failed: $code")
                return@withContext EasResult.Error("EWS FindItem (notes): $code")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Response length=${responseXml.length}")
            val active = fillNoteBodiesEws(parseEwsNotesResponse(responseXml))
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Parsed ${active.notes.size} ACTIVE notes")
            
            // === 2. Синхронизация УДАЛЁННЫХ заметок из папки "deleteditems" ===
            // Сбой здесь синк не валит: корзину просто не сверяем (isDeletedAuthoritative=false).
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: START - syncing DELETED notes")
            val findDeletedRequest = EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem("deleteditems"))
            
            val deletedResponseXml = executeEwsWithAuth(findDeletedRequest, "FindItem")
                ?.takeIf { EwsClient.isEwsSuccess(it) }
            val deleted = if (deletedResponseXml != null) {
                fillNoteBodiesEws(parseEwsNotesResponse(deletedResponseXml))
            } else {
                android.util.Log.e("NOT", "syncNotesEws: FindItem (deleteditems) failed")
                null
            }
            val deletedNotes = deleted?.notes.orEmpty().map { it.copy(isDeleted = true) }
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Parsed ${deletedNotes.size} DELETED notes")
            
            val allNotes = active.notes + deletedNotes
            allNotes.forEach { note ->
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Note serverId=${note.serverId.take(20)}..., bodyLen=${note.body.length}, isDeleted=${note.isDeleted}")
            }
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: TOTAL ${allNotes.size} notes (active + deleted)")
            
            // FindItem без пагинации (MaxEntriesReturned=1000): папка полна только при
            // IncludesLastItemInRange="true", иначе это лишь первая тысяча элементов.
            EasResult.Success(NotesSyncResult(
                notes = allNotes,
                isActiveAuthoritative = EwsClient.isEwsLastPage(responseXml),
                isDeletedAuthoritative = deletedResponseXml != null && EwsClient.isEwsLastPage(deletedResponseXml),
                unresolvedServerIds = active.unresolvedIds + deleted?.unresolvedIds.orEmpty()
            ))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("NOT", "syncNotesEws: EXCEPTION ${e.message}", e)
            EasResult.Error("EWS: ошибка синхронизации заметок: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }
    
    private suspend fun createNoteEws(subject: String, body: String): EasResult<String> = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: START subjectLen=${subject.length}")
        try {
            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)
            
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:CreateItem MessageDisposition="SaveOnly">
            <m:SavedItemFolderId>
                <t:DistinguishedFolderId Id="notes"/>
            </m:SavedItemFolderId>
            <m:Items>
                <t:Message>
                    <t:ItemClass>IPM.StickyNote</t:ItemClass>
                    <t:Subject>$escapedSubject</t:Subject>
                    <t:Body BodyType="Text">$escapedBody</t:Body>
                </t:Message>
            </m:Items>
        </m:CreateItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: request length=${soapRequest.length}")
            
            val responseXml = executeEwsWithAuth(soapRequest, "CreateItem")
            if (responseXml == null) {
                android.util.Log.e("NOT", "createNoteEws: Auth FAILED")
                return@withContext EasResult.Error("Аутентификация не удалась")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: response length=${responseXml.length}, preview=${responseXml.take(500)}")
            
            // Проверка на ошибки схемы (как в оригинале)
            if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                android.util.Log.e("NOT", "createNoteEws: Schema ERROR in response")
                return@withContext EasResult.Error("Ошибка схемы EWS")
            }
            
            val itemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
            
            if (itemId != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: SUCCESS itemId=$itemId")
                EasResult.Success(itemId)
            } else {
                if (EwsClient.isEwsSuccess(responseXml)) {
                    val uuid = java.util.UUID.randomUUID().toString()
                    if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: SUCCESS (no itemId, generated=$uuid)")
                    EasResult.Success(uuid)
                } else {
                    android.util.Log.e("NOT", "createNoteEws: FAILED response=$responseXml")
                    EasResult.Error("Не удалось создать заметку через EWS")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("NOT", "createNoteEws: EXCEPTION ${e.message}", e)
            EasResult.Error(e.message ?: "Ошибка создания заметки через EWS")
        }
    }
    
    private suspend fun updateNoteEws(serverId: String, subject: String, body: String): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                val foundId = deps.findEwsNoteItemId(subject, false)
                if (foundId == null) {
                    return@withContext EasResult.Error("Заметка не найдена")
                }
                foundId
            }
            
            // КРИТИЧНО: Получаем ChangeKey через GetItem перед UpdateItem
            
            val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
            <ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
            </ItemShape>
            <ItemIds>
                <t:ItemId Id="${deps.escapeXml(ewsItemId)}"/>
            </ItemIds>
        </GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            val getItemResponse = executeEwsWithAuth(getItemRequest, "GetItem")
            if (getItemResponse == null) {
                return@withContext EasResult.Error("Аутентификация не удалась")
            }
            
            // EWS-1: ChangeKey извлекается независимо от порядка атрибутов/префикса.
            val changeKey = EwsIds.parseChangeKey(getItemResponse) ?: ""
            
            val escapedItemId = deps.escapeXml(ewsItemId)
            val escapedChangeKey = deps.escapeXml(changeKey)
            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)
            
            // Добавляем ChangeKey в ItemId
            val changeKeyAttr = if (changeKey.isNotEmpty()) """ ChangeKey="$escapedChangeKey"""" else ""
            
            // КРИТИЧНО: StickyNote (IPM.StickyNote) требует PostItem, НЕ Message!
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AlwaysOverwrite">
            <m:ItemChanges>
                <t:ItemChange>
                    <t:ItemId Id="$escapedItemId"$changeKeyAttr/>
                    <t:Updates>
                        <t:SetItemField>
                            <t:FieldURI FieldURI="item:Subject"/>
                            <t:PostItem>
                                <t:Subject>$escapedSubject</t:Subject>
                            </t:PostItem>
                        </t:SetItemField>
                        <t:SetItemField>
                            <t:FieldURI FieldURI="item:Body"/>
                            <t:PostItem>
                                <t:Body BodyType="Text">$escapedBody</t:Body>
                            </t:PostItem>
                        </t:SetItemField>
                    </t:Updates>
                </t:ItemChange>
            </m:ItemChanges>
        </m:UpdateItem>
    </soap:Body>
</soap:Envelope>"""
            
            val responseXml = executeEwsWithAuth(soapRequest, "UpdateItem")
            if (responseXml == null) {
                return@withContext EasResult.Error("Аутентификация не удалась")
            }
            
            if (!EwsClient.isEwsSuccess(responseXml)) {
                val errorMsg = Regex("MessageText>([^<]+)</").find(responseXml)?.groupValues?.get(1)
                return@withContext EasResult.Error(errorMsg ?: "Ошибка обновления заметки")
            }
            
            EasResult.Success(true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: "Ошибка обновления заметки через EWS")
        }
    }
    
    private suspend fun deleteNoteEws(serverId: String, subject: String = ""): EasResult<Boolean> = withContext(Dispatchers.IO) {
        deleteNoteEwsInternal(serverId, "MoveToDeletedItems", subject)
    }

    private suspend fun deleteNotePermanentlyEws(serverId: String, subject: String = ""): EasResult<Boolean> = withContext(Dispatchers.IO) {
        deleteNoteEwsInternal(serverId, "HardDelete", subject)
    }
    
    private suspend fun deleteNoteEwsInternal(serverId: String, deleteType: String, subject: String = ""): EasResult<Boolean> {
        return try {
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                deps.findEwsNoteItemId(subject, false) ?: serverId
            }
            
            val soapRequest = EasXmlTemplates.ewsSoapRequest(
                EasXmlTemplates.ewsDeleteItem(ewsItemId, deleteType)
            )
            
            val responseXml = executeEwsWithAuth(soapRequest, "DeleteItem")
                ?: return EasResult.Error("Аутентификация не удалась")
            
            EasResult.Success(EwsClient.isEwsSuccessOrNotFound(responseXml))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка EWS: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }

    /**
     * Batch EWS DeleteItem для заметок — один запрос с несколькими ItemId.
     * Сначала резолвит EAS→EWS ItemId (если нужно), затем батч-удаляет.
     */
    private suspend fun deleteNotesBatchEws(
        serverIds: List<String>,
        deleteType: String,
        subjects: Map<String, String> = emptyMap()
    ): EasResult<Int> = withContext(Dispatchers.IO) {
        try {
            require(deleteType in listOf("HardDelete", "SoftDelete", "MoveToDeletedItems")) {
                "Invalid deleteType: $deleteType"
            }
            val ewsItemIds = mutableListOf<String>()

            for (sid in serverIds) {
                val ewsItemId = if (sid.length >= 50 && !sid.contains(":")) {
                    sid
                } else {
                    val searchDeleted = (deleteType == "HardDelete")
                    val subj = subjects[sid] ?: ""
                    deps.findEwsNoteItemId(subj, searchDeleted) ?: sid
                }
                ewsItemIds.add(ewsItemId)
            }

            if (ewsItemIds.isEmpty()) return@withContext EasResult.Success(0)

            val itemIdsXml = ewsItemIds.joinToString("\n") {
                """            <t:ItemId Id="${deps.escapeXml(it)}"/>"""
            }
            val soapBody = """
    <m:DeleteItem DeleteType="${deps.escapeXml(deleteType)}">
        <m:ItemIds>
$itemIdsXml
        </m:ItemIds>
    </m:DeleteItem>""".trimIndent()
            val soapRequest = EasXmlTemplates.ewsSoapRequest(soapBody)

            val responseXml = executeEwsWithAuth(soapRequest, "DeleteItem")
                ?: return@withContext EasResult.Error("Аутентификация не удалась")

            if (BuildConfig.DEBUG) android.util.Log.d("EasNotesService",
                "deleteNotesBatchEws: ${ewsItemIds.size} items, deleteType=$deleteType, response len=${responseXml.length}")

            if (EwsClient.isEwsSuccessOrNotFound(responseXml)) {
                EasResult.Success(ewsItemIds.size)
            } else {
                val errorCode = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>"
                    .toRegex(RegexOption.DOT_MATCHES_ALL).find(responseXml)?.groupValues?.get(1)?.trim()
                EasResult.Error("EWS batch DeleteItem notes: $errorCode")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка batch EWS notes: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }
    
    private suspend fun restoreNoteEws(serverId: String, subject: String = ""): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: START serverId=$serverId")
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: Using serverId as EWS ItemId")
                serverId
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: Finding EWS ItemId for EAS serverId (searching in deleteditems)")
                deps.findEwsNoteItemId(subject, true) ?: run {
                    android.util.Log.e("NOT", "restoreNoteEws: EWS ItemId NOT FOUND in notes or deleteditems")
                    return@withContext EasResult.Error("Заметка не найдена")
                }
            }
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: ewsItemId=${ewsItemId.take(50)}...")
            
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:MoveItem>
            <m:ToFolderId>
                <t:DistinguishedFolderId Id="notes"/>
            </m:ToFolderId>
            <m:ItemIds>
                <t:ItemId Id="${deps.escapeXml(ewsItemId)}"/>
            </m:ItemIds>
        </m:MoveItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: MoveItem request length=${soapRequest.length}")
            val responseXml = executeEwsWithAuth(soapRequest, "MoveItem")
            if (responseXml == null) {
                android.util.Log.e("NOT", "restoreNoteEws: Auth FAILED")
                return@withContext EasResult.Error("Аутентификация не удалась")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: Response length=${responseXml.length}, preview=${responseXml.take(300)}")
            
            val newItemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
            
            if (newItemId != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: SUCCESS, newItemId=${newItemId.take(50)}...")
                EasResult.Success(newItemId)
            } else {
                if (EwsClient.isEwsSuccess(responseXml)) {
                    if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: SUCCESS (no new ItemId, using original)")
                    EasResult.Success(serverId)
                } else {
                    android.util.Log.e("NOT", "restoreNoteEws: FAILED, response=$responseXml")
                    EasResult.Error("Не удалось восстановить заметку через EWS")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("NOT", "restoreNoteEws: EXCEPTION ${e.message}", e)
            EasResult.Error(e.message ?: "Ошибка восстановления через EWS")
        }
    }
    
    // ==================== Parsing ====================
    
    /**
     * EAS Sync заметок (MS-ASNOTE) — один потоковый проход ([EasSyncStream]): поля — прямые дети
     * `ApplicationData`, тело — `Body/Data`. Порядок как прежде: сначала все Add, затем все Change.
     * Оборванный или битый ответ — исключение (см. [EasSyncStream]).
     */
    internal fun parseNotesResponse(xml: String): List<EasNote> {
        val adds = mutableListOf<EasNote>()
        val changes = mutableListOf<EasNote>()
        EasSyncStream.forEachCommand(xml, EasSyncStream.ADD_CHANGE) { command, p ->
            val item = EasSyncStream.readItem(p, NOTE_FIELDS, withBody = true, withCategories = true)
            item?.let(::toNote)?.let { if (command == "Add") adds += it else changes += it }
        }
        return adds + changes
    }
    
    private fun toNote(item: EasSyncStream.SyncItem): EasNote? {
        val serverId = item.serverId ?: return null
        val subject = item.fields["Subject"] ?: ""
        // EAS 14.0+: тело в AirSyncBase Body/Data; без Data — собственный текст Body (EAS 2.5).
        val body = item.body?.let(::stripHtmlIfNeeded) ?: ""
        if (subject.isEmpty() && body.isEmpty()) {
            return null
        }
        
        return EasNote(
            serverId = serverId,
            subject = subject.ifEmpty { "No subject" },
            body = body,
            messageClass = item.fields["MessageClass"] ?: "IPM.StickyNote",
            lastModified = parseNoteDate(item.fields["LastModifiedDate"]),
            categories = item.categories,
            isDeleted = false
        )
    }
    
    internal fun parseEwsNotesResponse(xml: String): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Searching for <t:PostItem> or <t:Message>")
        EWS_ITEM_PATTERN.findAll(xml).forEach { match ->
            val itemXml = match.groupValues[1]
            
            val itemId = EasXmlParser.extractAttribute(itemXml, "ItemId", "Id", listOf("t")) ?: run {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: No ItemId found, skipping")
                return@forEach
            }
            
            // Извлекаем значения с разными namespace (t:/m:)
            val subject = EasXmlParser.ews(itemXml, "Subject") ?: ""
            val bodyRaw = extractEwsBody(itemXml)
                ?: EasXmlParser.ews(itemXml, "Body")
                ?: ""
            val itemClass = EasXmlParser.ews(itemXml, "ItemClass") ?: ""
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Found item: itemId=${itemId.take(20)}..., bodyLen=${bodyRaw.length}, itemClass='$itemClass'")
            
            // Фильтруем только StickyNote (или пустой ItemClass для заметок без класса)
            if (itemClass.contains("StickyNote", ignoreCase = true) || itemClass.isBlank()) {
                notes.add(EasNote(
                    serverId = itemId,
                    subject = subject.ifEmpty { "No subject" },
                    body = bodyRaw,
                    messageClass = itemClass,
                    lastModified = System.currentTimeMillis(),
                    // FindItem AllProperties отдаёт <t:Categories><t:String>…; раньше терялись (всегда []).
                    categories = extractEwsCategories(itemXml),
                    isDeleted = false
                ))
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Skipped (not StickyNote): itemClass='$itemClass'")
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Returning ${notes.size} notes")
        return notes
    }

    /**
     * EWS-категории заметки: `<t:Categories><t:String>…</t:String></t:Categories>` (как у задач по EWS).
     * Берётся первый блок Categories в элементе; пустые строки отбрасываются, сущности разворачиваются.
     */
    private fun extractEwsCategories(itemXml: String): List<String> {
        val block = EasXmlParser.collectElements(itemXml, "Categories", listOf("t")).firstOrNull()
            ?: return emptyList()
        return EasXmlParser.extractAll(block, "String", listOf("t"))
    }

    /** Заметки с телами из GetItem и ItemId тех, чьё тело получить не удалось. */
    private class ResolvedNotes(val notes: List<EasNote>, val unresolvedIds: Set<String>)

    /**
     * FindItem тел не отдаёт, тело приходит только из GetItem. Заметку, которой нет в ответе
     * GetItem, не возвращаем, а помечаем неразрешённой: иначе она ушла бы в БД с ПУСТЫМ телом
     * и затёрла бы локальное.
     */
    private suspend fun fillNoteBodiesEws(notes: List<EasNote>): ResolvedNotes {
        if (notes.isEmpty()) return ResolvedNotes(notes, emptySet())
        val bodies = getNoteBodiesEws(notes.map { it.serverId }).orEmpty()
        val resolved = ArrayList<EasNote>(notes.size)
        val unresolved = LinkedHashSet<String>()
        for (note in notes) {
            val body = bodies[note.serverId]
            when {
                body == null -> unresolved += note.serverId
                body.isNotBlank() -> resolved += note.copy(body = body)
                else -> resolved += note
            }
        }
        if (unresolved.isNotEmpty()) {
            android.util.Log.w("NOT", "fillNoteBodiesEws: ${unresolved.size} notes without GetItem body, kept as-is")
        }
        return ResolvedNotes(resolved, unresolved)
    }

    /** ItemId → тело (пустое, если у заметки его нет) для каждого элемента ответа; null — запрос не удался. */
    private suspend fun getNoteBodiesEws(itemIds: List<String>): Map<String, String>? {
        if (itemIds.isEmpty()) return emptyMap()
        val itemIdsXml = itemIds.joinToString("") { """<t:ItemId Id="${deps.escapeXml(it)}"/>""" }
        val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:GetItem>
            <m:ItemShape>
                <t:BaseShape>Default</t:BaseShape>
                <t:IncludeMimeContent>false</t:IncludeMimeContent>
                <t:BodyType>Text</t:BodyType>
            </m:ItemShape>
            <m:ItemIds>
                $itemIdsXml
            </m:ItemIds>
        </m:GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val responseXml = executeEwsWithAuth(getItemRequest, "GetItem") ?: return null
        if (!EwsClient.isCompleteEnvelope(responseXml)) return null

        val result = mutableMapOf<String, String>()
        EWS_ITEM_PATTERN.findAll(responseXml).forEach { match ->
            val itemXml = match.groupValues[1]
            val itemId = EasXmlParser.extractAttribute(itemXml, "ItemId", "Id", listOf("t")) ?: return@forEach
            result[itemId] = extractEwsBody(itemXml)
                ?: EasXmlParser.ews(itemXml, "Body")
                ?: ""
        }
        return result
    }

    private fun extractEwsBody(xml: String): String? {
        // EWS тело: <t:Body> | <Body> | <m:Body>. EasXmlParser.ews снимает экранирование и тримит;
        // readText возвращает null для пустого/пробельного содержимого (как прежняя isNotBlank-проверка).
        val raw = EasXmlParser.ews(xml, "Body") ?: return null
        return stripHtmlIfNeeded(raw)
    }
    
    private fun stripHtmlIfNeeded(text: String): String =
        com.dedovmosol.iwomail.util.stripHtmlIfNeeded(text)

    private fun parseNoteDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val cleanDate = dateStr.substringBefore("Z").substringBefore(".")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(cleanDate)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            android.util.Log.w("EasNotesService", "Failed to parse note date: $dateStr", e)
            System.currentTimeMillis()
        }
    }
}
