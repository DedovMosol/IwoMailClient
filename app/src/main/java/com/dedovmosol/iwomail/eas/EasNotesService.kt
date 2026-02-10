package com.dedovmosol.iwomail.eas

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
        val performNtlmHandshake: suspend (String, String, String) -> String?,
        val executeNtlmRequest: suspend (String, String, String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String, String) -> String?,
        val getEwsUrl: () -> String,
        val findEwsNoteItemId: suspend (String, String, Boolean) -> String?
    )
    
    // Кэш ID папки заметок
    private var cachedNotesFolderId: String? = null
    
    /**
     * Синхронизация заметок
     * EAS 14.1+ — стандартный Notes sync
     * EAS 12.x — fallback через EWS
     */
    suspend fun syncNotes(): EasResult<List<EasNote>> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        android.util.Log.d("EasNotesService", "syncNotes: EAS version = ${deps.getEasVersion()}")
        
        val foldersResult = deps.folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            syncNotesStandard(folders)
        } else {
            syncNotesLegacy(folders)
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
    suspend fun deleteNote(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            deleteNoteEas(serverId)
        } else {
            deleteNoteEws(serverId)
        }
    }
    
    /**
     * Окончательное удаление заметки
     */
    suspend fun deleteNotePermanently(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            deleteNotePermanentlyEas(serverId)
        } else {
            deleteNotePermanentlyEws(serverId)
        }
    }
    
    /**
     * Восстановление заметки из корзины
     */
    suspend fun restoreNote(serverId: String): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            restoreNoteEas(serverId)
        } else {
            restoreNoteEws(serverId)
        }
    }
    
    // ==================== Private EAS methods ====================
    
    private suspend fun syncNotesStandard(folders: List<EasFolder>): EasResult<List<EasNote>> {
        val notesFolderId = folders.find { it.type == FolderType.NOTES }?.serverId
            ?: return EasResult.Error("Папка Notes (type=10) не найдена")
        
        cachedNotesFolderId = notesFolderId
        
        val allNotes = mutableListOf<EasNote>()
        
        // Активные заметки
        val activeNotes = syncNotesFromFolder(notesFolderId, isDeleted = false)
        if (activeNotes is EasResult.Success) {
            allNotes.addAll(activeNotes.data)
        }
        
        // Удалённые заметки из Deleted Items
        val deletedItemsFolderId = folders.find { it.type == FolderType.DELETED_ITEMS }?.serverId
        if (deletedItemsFolderId != null) {
            val deletedNotes = syncDeletedNotesFromFolder(deletedItemsFolderId)
            if (deletedNotes is EasResult.Success) {
                allNotes.addAll(deletedNotes.data)
            }
        }
        
        return EasResult.Success(allNotes)
    }

    /**
     * Legacy синхронизация заметок для EAS 12.x (Exchange 2007)
     * Exchange 2007 не поддерживает Sync для Notes, используем Search
     */
    private suspend fun syncNotesLegacy(folders: List<EasFolder>): EasResult<List<EasNote>> {
        val notesFolderId = folders.find {
            it.displayName.equals("Notes", ignoreCase = true) ||
                it.displayName.equals("Заметки", ignoreCase = true)
        }?.serverId

        if (notesFolderId == null) {
            return EasResult.Success(emptyList())
        }

        val searchXml = EasXmlTemplates.searchMailbox(notesFolderId)
        val searchResult = deps.executeEasCommand("Search", searchXml) { responseXml ->
            parseNotesSearchResponse(responseXml)
        }

        val activeNotes: List<EasNote> = if (searchResult is EasResult.Error ||
            (searchResult is EasResult.Success && searchResult.data.isEmpty())
        ) {
            val syncResult = syncNotesLegacySync(notesFolderId)
            if (syncResult is EasResult.Success && syncResult.data.isEmpty()) {
                return syncNotesEws()
            }
            (syncResult as? EasResult.Success)?.data ?: emptyList()
        } else {
            (searchResult as? EasResult.Success)?.data ?: emptyList()
        }

        val allNotes = activeNotes.toMutableList()
        val deletedNotes = syncDeletedNotesEws()
        if (deletedNotes is EasResult.Success) {
            allNotes.addAll(deletedNotes.data)
        }

        return EasResult.Success(allNotes)
    }
    
    private suspend fun syncNotesFromFolder(folderId: String, isDeleted: Boolean): EasResult<List<EasNote>> {
        val initialXml = EasXmlTemplates.syncInitial(folderId)
        
        var syncKey = "0"
        when (val result = deps.executeEasCommand("Sync", initialXml) { deps.extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> syncKey = result.data
            is EasResult.Error -> return EasResult.Error(result.message)
        }
        
        if (syncKey == "0") return EasResult.Success(emptyList())
        
        val allNotes = mutableListOf<EasNote>()
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 50 // До 5000 заметок (windowSize=100)
        
        while (moreAvailable && iterations < maxIterations) {
            iterations++
            val syncXml = EasXmlTemplates.syncWithBody(syncKey, folderId)
            
            val result = deps.executeEasCommand("Sync", syncXml) { responseXml ->
                val newSyncKey = deps.extractValue(responseXml, "SyncKey")
                if (newSyncKey != null) syncKey = newSyncKey
                
                val more = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                val notes = parseNotesResponse(responseXml)
                Pair(notes, more)
            }
            
            when (result) {
                is EasResult.Success -> {
                    val (notes, more) = result.data
                    val processed = if (isDeleted) notes.map { it.copy(isDeleted = true) } else notes
                    allNotes.addAll(processed)
                    moreAvailable = more
                    if (notes.isEmpty()) moreAvailable = false
                }
                is EasResult.Error -> {
                    if (allNotes.isNotEmpty()) break // Уже есть данные — возвращаем что есть
                    return EasResult.Error(result.message)
                }
            }
        }
        
        return EasResult.Success(allNotes)
    }
    
    private suspend fun syncDeletedNotesFromFolder(deletedItemsFolderId: String): EasResult<List<EasNote>> {
        val initialXml = EasXmlTemplates.syncInitial(deletedItemsFolderId)
        
        var syncKey = "0"
        when (val result = deps.executeEasCommand("Sync", initialXml) { deps.extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> syncKey = result.data
            is EasResult.Error -> return EasResult.Success(emptyList())
        }
        
        if (syncKey == "0") return EasResult.Success(emptyList())
        
        val syncXml = EasXmlTemplates.syncWithBody(syncKey, deletedItemsFolderId)
        
        return deps.executeEasCommand("Sync", syncXml) { responseXml ->
            parseNotesResponse(responseXml)
                .filter { it.messageClass == "IPM.StickyNote" || it.messageClass.isBlank() }
                .map { it.copy(isDeleted = true) }
        }
    }
    
    private suspend fun createNoteEas(subject: String, body: String): EasResult<String> {
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")
        
        cachedNotesFolderId = notesFolderId
        
        // Получаем SyncKey
        val syncKeyResult = deps.refreshSyncKey(notesFolderId, "0")
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return EasResult.Error(syncKeyResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        val createXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$notesFolderId</CollectionId>
            <Commands>
                <Add>
                    <ClientId>$clientId</ClientId>
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
        
        return deps.executeEasCommand("Sync", createXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "1") {
                deps.extractValue(responseXml, "ServerId") ?: clientId
            } else {
                throw Exception("Ошибка создания заметки: Status=$status")
            }
        }
    }
    
    private suspend fun updateNoteEas(serverId: String, subject: String, body: String): EasResult<Boolean> {
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")
        
        val syncKeyResult = deps.refreshSyncKey(notesFolderId, "0")
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return EasResult.Error(syncKeyResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        val updateXml = EasXmlTemplates.noteUpdate(syncKey, notesFolderId, serverId, escapedSubject, escapedBody)
        
        return deps.executeEasCommand("Sync", updateXml) { responseXml ->
            // Проверяем статус коллекции
            val collectionStatus = deps.extractValue(responseXml, "Status")
            if (collectionStatus != "1") {
                throw Exception("Collection Status=$collectionStatus")
            }
            
            // КРИТИЧНО: Проверяем статус конкретной операции Change
            // Согласно MS-ASCMD: "The server is not required to send an individual response
            // for every operation. The client only receives responses for failed changes."
            // Если <Responses><Change><Status> ЕСТЬ - проверяем его
            // Если НЕТ - считаем что SUCCESS
            
            if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                val changeStatusMatch = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
                    .find(responseXml)
                
                if (changeStatusMatch != null) {
                    val changeStatus = changeStatusMatch.groupValues[1]
                    when (changeStatus) {
                        "1" -> true // Success
                        "6" -> throw Exception("Change Status=6: Error in client/server conversion (invalid item)")
                        "7" -> throw Exception("Change Status=7: Conflict (server changes take precedence)")
                        "8" -> throw Exception("Change Status=8: Object not found on server")
                        else -> throw Exception("Change Status=$changeStatus")
                    }
                } else {
                    // <Change> есть, но <Status> нет - считаем SUCCESS
                    true
                }
            } else {
                // Нет <Responses><Change> - согласно MS-ASCMD считаем SUCCESS
                true
            }
        }
    }
    
    private suspend fun deleteNoteEas(serverId: String): EasResult<Boolean> {
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")
        
        // Retry логика: если SyncKey невалиден (Status=3), сбрасываем на "0" и повторяем
        var attempt = 0
        val maxAttempts = 2
        
        while (attempt < maxAttempts) {
            attempt++
            
            val syncKeyResult = deps.refreshSyncKey(notesFolderId, "0")
            val syncKey = when (syncKeyResult) {
                is EasResult.Success -> syncKeyResult.data
                is EasResult.Error -> {
                    if (attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNoteEas: refreshSyncKey failed (attempt $attempt), retrying...")
                        continue
                    }
                    return EasResult.Error(syncKeyResult.message)
                }
            }
            
            if (syncKey == "0") {
                if (attempt < maxAttempts) {
                    android.util.Log.w("EasNotesService", "deleteNoteEas: got SyncKey=0 (attempt $attempt), retrying...")
                    continue
                }
                return EasResult.Error("Не удалось получить SyncKey")
            }
            
            val deleteXml = EasXmlTemplates.syncDelete(syncKey, notesFolderId, serverId, deletesAsMoves = true)
            
            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                android.util.Log.d("EasNotesService", "deleteNoteEas: Status=$status (attempt $attempt)")
                
                when (status) {
                    "1" -> true  // Success
                    "8" -> true  // Object not found (already deleted)
                    "3" -> {     // Invalid SyncKey
                        if (attempt < maxAttempts) {
                            android.util.Log.w("EasNotesService", "deleteNoteEas: Invalid SyncKey (Status=3), will retry")
                            throw Exception("INVALID_SYNCKEY")
                        }
                        false
                    }
                    else -> false
                }
            }
            
            return when (result) {
                is EasResult.Success -> {
                    if (result.data) {
                        EasResult.Success(true)
                    } else if (attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNoteEas: Delete failed (attempt $attempt), retrying...")
                        continue
                    } else {
                        EasResult.Error("Не удалось удалить заметку после $maxAttempts попыток")
                    }
                }
                is EasResult.Error -> {
                    if (result.message.contains("INVALID_SYNCKEY") && attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNoteEas: INVALID_SYNCKEY (attempt $attempt), retrying...")
                        continue
                    }
                    if (attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNoteEas: Error (attempt $attempt): ${result.message}, retrying...")
                        continue
                    }
                    result
                }
            }
        }
        
        return EasResult.Error("Не удалось удалить заметку после $maxAttempts попыток")
    }
    
    private suspend fun deleteNotePermanentlyEas(serverId: String): EasResult<Boolean> {
        val deletedItemsFolderId = deps.getDeletedItemsFolderId()
            ?: return EasResult.Error("Папка Deleted Items не найдена")
        
        // Retry логика: если SyncKey невалиден (Status=3), сбрасываем на "0" и повторяем
        var attempt = 0
        val maxAttempts = 2
        
        while (attempt < maxAttempts) {
            attempt++
            
            val syncKeyResult = deps.refreshSyncKey(deletedItemsFolderId, "0")
            val syncKey = when (syncKeyResult) {
                is EasResult.Success -> syncKeyResult.data
                is EasResult.Error -> {
                    if (attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNotePermanentlyEas: refreshSyncKey failed (attempt $attempt), retrying...")
                        continue
                    }
                    return EasResult.Error(syncKeyResult.message)
                }
            }
            
            if (syncKey == "0") {
                if (attempt < maxAttempts) {
                    android.util.Log.w("EasNotesService", "deleteNotePermanentlyEas: got SyncKey=0 (attempt $attempt), retrying...")
                    continue
                }
                return EasResult.Error("Не удалось получить SyncKey")
            }
            
            val deleteXml = EasXmlTemplates.syncDelete(syncKey, deletedItemsFolderId, serverId, deletesAsMoves = false)
            
            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                android.util.Log.d("EasNotesService", "deleteNotePermanentlyEas: Status=$status (attempt $attempt)")
                
                when (status) {
                    "1" -> true  // Success
                    "8" -> true  // Object not found (already deleted)
                    "3" -> {     // Invalid SyncKey
                        if (attempt < maxAttempts) {
                            android.util.Log.w("EasNotesService", "deleteNotePermanentlyEas: Invalid SyncKey (Status=3), will retry")
                            throw Exception("INVALID_SYNCKEY")
                        }
                        false
                    }
                    else -> false
                }
            }
            
            return when (result) {
                is EasResult.Success -> {
                    if (result.data) {
                        EasResult.Success(true)
                    } else if (attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNotePermanentlyEas: Delete failed (attempt $attempt), retrying...")
                        continue
                    } else {
                        EasResult.Error("Не удалось удалить заметку после $maxAttempts попыток")
                    }
                }
                is EasResult.Error -> {
                    if (result.message.contains("INVALID_SYNCKEY") && attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNotePermanentlyEas: INVALID_SYNCKEY (attempt $attempt), retrying...")
                        continue
                    }
                    if (attempt < maxAttempts) {
                        android.util.Log.w("EasNotesService", "deleteNotePermanentlyEas: Error (attempt $attempt): ${result.message}, retrying...")
                        continue
                    }
                    result
                }
            }
        }
        
        return EasResult.Error("Не удалось удалить заметку после $maxAttempts попыток")
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
    
    private suspend fun syncNotesEws(): EasResult<List<EasNote>> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("NOT", "syncNotesEws: START - syncing ACTIVE notes")
            val ewsUrl = deps.getEwsUrl()
            
            // === 1. Синхронизация АКТИВНЫХ заметок из папки "notes" ===
            val findRequest = EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem("notes"))
            android.util.Log.d("NOT", "syncNotesEws: FindItem (notes) request length=${findRequest.length}")
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
            if (ntlmAuth == null) {
                android.util.Log.e("NOT", "syncNotesEws: NTLM FAILED for notes")
                return@withContext EasResult.Success(emptyList())
            }
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
            if (responseXml == null) {
                android.util.Log.e("NOT", "syncNotesEws: Response NULL for notes")
                return@withContext EasResult.Success(emptyList())
            }
            
            android.util.Log.d("NOT", "syncNotesEws: Response length=${responseXml.length}")
            val activeNotes = parseEwsNotesResponse(responseXml).toMutableList()
            val activeWithBodies = fillNoteBodiesEws(ewsUrl, activeNotes).toMutableList()
            android.util.Log.d("NOT", "syncNotesEws: Parsed ${activeNotes.size} ACTIVE notes")
            
            // === 2. Синхронизация УДАЛЁННЫХ заметок из папки "deleteditems" ===
            android.util.Log.d("NOT", "syncNotesEws: START - syncing DELETED notes")
            val findDeletedRequest = EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem("deleteditems"))
            
            val ntlmAuth2 = deps.performNtlmHandshake(ewsUrl, findDeletedRequest, "FindItem")
            if (ntlmAuth2 != null) {
                val deletedResponseXml = deps.executeNtlmRequest(ewsUrl, findDeletedRequest, ntlmAuth2, "FindItem")
                if (deletedResponseXml != null) {
                    val deletedNotesRaw = parseEwsNotesResponse(deletedResponseXml)
                    val deletedNotes = fillNoteBodiesEws(ewsUrl, deletedNotesRaw)
                        .map { it.copy(isDeleted = true) }
                    android.util.Log.d("NOT", "syncNotesEws: Parsed ${deletedNotes.size} DELETED notes")
                    activeWithBodies.addAll(deletedNotes)
                } else {
                    android.util.Log.e("NOT", "syncNotesEws: Response NULL for deleteditems")
                }
            } else {
                android.util.Log.e("NOT", "syncNotesEws: NTLM FAILED for deleteditems")
            }
            
            // === 3. Вывод всех заметок ===
            activeWithBodies.forEach { note ->
                android.util.Log.d("NOT", "syncNotesEws: Note: subject='${note.subject}', body='${note.body.take(50)}', serverId=${note.serverId.take(20)}..., isDeleted=${note.isDeleted}")
            }
            
            android.util.Log.d("NOT", "syncNotesEws: TOTAL ${activeWithBodies.size} notes (active + deleted)")
            EasResult.Success(activeWithBodies)
        } catch (e: Exception) {
            android.util.Log.e("NOT", "syncNotesEws: EXCEPTION ${e.message}", e)
            EasResult.Success(emptyList())
        }
    }
    
    private suspend fun createNoteEws(subject: String, body: String): EasResult<String> = withContext(Dispatchers.IO) {
        android.util.Log.d("NOT", "createNoteEws: START subject='$subject'")
        try {
            val ewsUrl = deps.getEwsUrl()
            android.util.Log.d("NOT", "createNoteEws: ewsUrl=$ewsUrl")
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
            android.util.Log.d("NOT", "createNoteEws: request length=${soapRequest.length}")
            
            // Сначала пробуем Basic Auth
            android.util.Log.d("NOT", "Trying Basic Auth first...")
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "CreateItem")
            
            // Если Basic не сработал - пробуем NTLM
            if (responseXml == null) {
                android.util.Log.d("NOT", "Basic failed, trying NTLM...")
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    android.util.Log.e("NOT", "createNoteEws: NTLM handshake FAILED")
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                android.util.Log.d("NOT", "createNoteEws: NTLM OK")
                
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                if (responseXml == null) {
                    android.util.Log.e("NOT", "createNoteEws: EWS request FAILED (null)")
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
            }
            
            android.util.Log.d("NOT", "createNoteEws: response length=${responseXml.length}, preview=${responseXml.take(500)}")
            
            // Проверка на ошибки схемы (как в оригинале)
            if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                android.util.Log.e("NOT", "createNoteEws: Schema ERROR in response")
                return@withContext EasResult.Error("Ошибка схемы EWS")
            }
            
            val itemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
            
            if (itemId != null) {
                android.util.Log.d("NOT", "createNoteEws: SUCCESS itemId=$itemId")
                EasResult.Success(itemId)
            } else {
                // КРИТИЧНО: Проверяем ОБА условия
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                                responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
                if (hasSuccess && hasNoError) {
                    val uuid = java.util.UUID.randomUUID().toString()
                    android.util.Log.d("NOT", "createNoteEws: SUCCESS (no itemId, generated=$uuid)")
                    EasResult.Success(uuid)
                } else {
                    android.util.Log.e("NOT", "createNoteEws: FAILED response=$responseXml")
                    EasResult.Error("Не удалось создать заметку через EWS")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NOT", "createNoteEws: EXCEPTION ${e.message}", e)
            EasResult.Error(e.message ?: "Ошибка создания заметки через EWS")
        }
    }
    
    private suspend fun updateNoteEws(serverId: String, subject: String, body: String): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                val foundId = deps.findEwsNoteItemId(ewsUrl, serverId, false)
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
            
            var getItemResponse = deps.tryBasicAuthEws(ewsUrl, getItemRequest, "GetItem")
            if (getItemResponse == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                getItemResponse = deps.executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem")
                if (getItemResponse == null) {
                    return@withContext EasResult.Error("Не удалось получить ChangeKey")
                }
            }
            
            // Извлекаем ChangeKey из ответа
            val changeKeyPattern = """<t:ItemId Id="[^"]+" ChangeKey="([^"]+)"""".toRegex()
            val changeKeyMatch = changeKeyPattern.find(getItemResponse)
            val changeKey = changeKeyMatch?.groupValues?.get(1) ?: ""
            
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
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "UpdateItem")
            
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
            }
            
            // КРИТИЧНО: Проверяем ОБА условия (ResponseClass="Success" И ResponseCode="NoError")
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val success = hasSuccess && hasNoError
            
            if (!success) {
                val errorCode = Regex("<ResponseCode>([^<]+)</ResponseCode>").find(responseXml)?.groupValues?.get(1)
                val errorMsg = Regex("MessageText>([^<]+)</").find(responseXml)?.groupValues?.get(1)
                return@withContext EasResult.Error(errorMsg ?: "Ошибка обновления заметки")
            }
            
            EasResult.Success(true)
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка обновления заметки через EWS")
        }
    }
    
    private suspend fun deleteNoteEws(serverId: String): EasResult<Boolean> = withContext(Dispatchers.IO) {
        deleteNoteEwsInternal(serverId, "MoveToDeletedItems")
    }
    
    private suspend fun deleteNotePermanentlyEws(serverId: String): EasResult<Boolean> = withContext(Dispatchers.IO) {
        deleteNoteEwsInternal(serverId, "HardDelete")
    }
    
    private suspend fun deleteNoteEwsInternal(serverId: String, deleteType: String): EasResult<Boolean> {
        return try {
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                deps.findEwsNoteItemId(ewsUrl, serverId, false) ?: serverId
            }
            
            val soapRequest = EasXmlTemplates.ewsSoapRequest(
                EasXmlTemplates.ewsDeleteItem(deps.escapeXml(ewsItemId), deleteType)
            )
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                ?: return EasResult.Error("NTLM аутентификация не удалась")
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                ?: return EasResult.Error("Не удалось выполнить запрос")
            
            // КРИТИЧНО: Для delete проверяем Success+NoError ИЛИ ErrorItemNotFound (уже удалено)
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")
            val success = (hasSuccess && hasNoError) || hasNotFound
            
            EasResult.Success(success)
        } catch (e: Exception) {
            EasResult.Error("Ошибка EWS: ${e.message}")
        }
    }
    
    private suspend fun restoreNoteEws(serverId: String): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("NOT", "restoreNoteEws: START serverId=$serverId")
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                android.util.Log.d("NOT", "restoreNoteEws: Using serverId as EWS ItemId")
                serverId
            } else {
                android.util.Log.d("NOT", "restoreNoteEws: Finding EWS ItemId for EAS serverId (searching in deleteditems)")
                // КРИТИЧНО: Для restore ищем в корзине (deleteditems), а не в notes!
                deps.findEwsNoteItemId(ewsUrl, serverId, true) ?: run {
                    android.util.Log.e("NOT", "restoreNoteEws: EWS ItemId NOT FOUND in notes or deleteditems")
                    return@withContext EasResult.Error("Заметка не найдена")
                }
            }
            android.util.Log.d("NOT", "restoreNoteEws: ewsItemId=${ewsItemId.take(50)}...")
            
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
            
            android.util.Log.d("NOT", "restoreNoteEws: MoveItem request length=${soapRequest.length}")
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "MoveItem")
            if (ntlmAuth == null) {
                android.util.Log.e("NOT", "restoreNoteEws: NTLM FAILED")
                return@withContext EasResult.Error("NTLM аутентификация не удалась")
            }
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "MoveItem")
            if (responseXml == null) {
                android.util.Log.e("NOT", "restoreNoteEws: Response NULL")
                return@withContext EasResult.Error("Не удалось выполнить запрос")
            }
            
            android.util.Log.d("NOT", "restoreNoteEws: Response length=${responseXml.length}, preview=${responseXml.take(300)}")
            
            val newItemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
            
            if (newItemId != null) {
                android.util.Log.d("NOT", "restoreNoteEws: SUCCESS, newItemId=${newItemId.take(50)}...")
                EasResult.Success(newItemId)
            } else {
                // КРИТИЧНО: Проверяем ОБА условия
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                                responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
                if (hasSuccess && hasNoError) {
                    android.util.Log.d("NOT", "restoreNoteEws: SUCCESS (no new ItemId, using original)")
                    EasResult.Success(serverId)
                } else {
                    android.util.Log.e("NOT", "restoreNoteEws: FAILED, response=$responseXml")
                    EasResult.Error("Не удалось восстановить заметку через EWS")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NOT", "restoreNoteEws: EXCEPTION ${e.message}", e)
            EasResult.Error(e.message ?: "Ошибка восстановления через EWS")
        }
    }
    
    // ==================== Parsing ====================
    
    private fun parseNotesResponse(xml: String, legacy: Boolean = false): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        val patterns = listOf(
            "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in patterns) {
            pattern.findAll(xml).forEach { match ->
                val itemXml = match.groupValues[1]
                if (legacy && !isNoteItem(itemXml)) {
                    return@forEach
                }
                val note = parseNoteFromXml(itemXml)
                if (note != null) notes.add(note)
            }
        }
        
        return notes
    }
    
    private fun parseNoteFromXml(itemXml: String): EasNote? {
        val serverId = deps.extractValue(itemXml, "ServerId") ?: return null
        
        val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val dataXml = dataPattern.find(itemXml)?.groupValues?.get(1) ?: return null
        
        val subject = XmlValueExtractor.extractNote(dataXml, "Subject") ?: ""
        val body = extractNoteBody(dataXml).ifEmpty {
            unescapeXml(deps.extractValue(dataXml, "notes:Body")
                ?: deps.extractValue(dataXml, "Data")
                ?: deps.extractValue(dataXml, "Body")
                ?: "")
        }
        val messageClass = XmlValueExtractor.extractNote(dataXml, "MessageClass") ?: "IPM.StickyNote"
        val lastModified = parseNoteDate(XmlValueExtractor.extractNote(dataXml, "LastModifiedDate"))
        val categories = extractNoteCategories(dataXml)

        if (subject.isEmpty() && body.isEmpty()) {
            return null
        }
        
        return EasNote(
            serverId = serverId,
            subject = subject.ifEmpty { "No subject" },
            body = body,
            messageClass = messageClass,
            lastModified = lastModified,
            categories = categories,
            isDeleted = false
        )
    }
    
    private fun parseEwsNotesResponse(xml: String): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        // Ищем PostItem или Message (Notes в EWS)
        val itemPattern = "<t:(?:PostItem|Message)[^>]*>(.*?)</t:(?:PostItem|Message)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        android.util.Log.d("NOT", "parseEwsNotesResponse: Searching for <t:PostItem> or <t:Message>")
        itemPattern.findAll(xml).forEach { match ->
            val itemXml = match.groupValues[1]
            
            val itemId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: run {
                android.util.Log.d("NOT", "parseEwsNotesResponse: No ItemId found, skipping")
                return@forEach
            }
            
            // Извлекаем значения с разными namespace
            val subject = XmlValueExtractor.extractEws(itemXml, "Subject") ?: ""
            val bodyRaw = extractEwsBody(itemXml)
                ?: XmlValueExtractor.extractEws(itemXml, "Body")
                ?: ""
            val itemClass = XmlValueExtractor.extractEws(itemXml, "ItemClass") ?: ""
            
            android.util.Log.d("NOT", "parseEwsNotesResponse: Found item: itemId=${itemId.take(20)}..., subject='$subject', bodyLen=${bodyRaw.length}, itemClass='$itemClass'")
            
            // Фильтруем только StickyNote (или пустой ItemClass для заметок без класса)
            if (itemClass.contains("StickyNote", ignoreCase = true) || itemClass.isBlank()) {
                notes.add(EasNote(
                    serverId = itemId,
                    subject = subject.ifEmpty { "No subject" },
                    body = bodyRaw,
                    messageClass = itemClass,
                    lastModified = System.currentTimeMillis(),
                    categories = emptyList(),
                    isDeleted = false
                ))
            } else {
                android.util.Log.d("NOT", "parseEwsNotesResponse: Skipped (not StickyNote): itemClass='$itemClass'")
            }
        }
        
        android.util.Log.d("NOT", "parseEwsNotesResponse: Returning ${notes.size} notes")
        return notes
    }

    private suspend fun fillNoteBodiesEws(ewsUrl: String, notes: List<EasNote>): List<EasNote> {
        if (notes.isEmpty()) return notes
        val bodies = getNoteBodiesEws(ewsUrl, notes.map { it.serverId })
        if (bodies.isEmpty()) return notes
        return notes.map { note ->
            val body = bodies[note.serverId]
            if (!body.isNullOrBlank()) {
                note.copy(body = body)
            } else {
                note
            }
        }
    }

    private suspend fun getNoteBodiesEws(ewsUrl: String, itemIds: List<String>): Map<String, String> {
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

        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem") ?: return emptyMap()
        val responseXml = deps.executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem") ?: return emptyMap()

        val result = mutableMapOf<String, String>()
        val itemPattern = "<t:(?:PostItem|Message)[^>]*>(.*?)</t:(?:PostItem|Message)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        itemPattern.findAll(responseXml).forEach { match ->
            val itemXml = match.groupValues[1]
            val itemId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: return@forEach
            val body = extractEwsBody(itemXml)
                ?: XmlValueExtractor.extractEws(itemXml, "Body")
                ?: ""
            if (body.isNotBlank()) {
                result[itemId] = body
            }
        }
        return result
    }

    private fun extractEwsBody(xml: String): String? {
        val bodyPatterns = listOf(
            "<t:Body[^>]*>(.*?)</t:Body>",
            "<Body[^>]*>(.*?)</Body>",
            "<m:Body[^>]*>(.*?)</m:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return unescapeXml(match.groupValues[1].trim())
            }
        }
        return null
    }
    
    private fun extractNoteBody(xml: String): String {
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<notes:Body>(.*?)</notes:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return unescapeXml(match.groupValues[1].trim())
            }
        }
        return ""
    }

    /**
     * Декодирует XML entities (&lt;, &gt;, &quot;, &amp;, &apos;)
     */
    private fun unescapeXml(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun parseNotesSearchResponse(xml: String): List<EasNote> {
        val notes = mutableListOf<EasNote>()

        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]

            val serverId = deps.extractValue(resultXml, "LongId")
                ?: deps.extractValue(resultXml, "ServerId")
                ?: return@forEach

            val propertiesPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propertiesMatch = propertiesPattern.find(resultXml) ?: return@forEach
            val propsXml = propertiesMatch.groupValues[1]

            val messageClass = deps.extractValue(propsXml, "MessageClass") ?: ""
            if (messageClass.isNotEmpty() && !messageClass.contains("StickyNote", ignoreCase = true)) {
                return@forEach
            }

            val subject = deps.extractValue(propsXml, "Subject") ?: ""
            val body = extractNoteBody(propsXml)
            val lastModified = parseNoteDate(deps.extractValue(propsXml, "DateReceived"))

            if (subject.isEmpty() && body.isEmpty()) return@forEach

            notes.add(
                EasNote(
                    serverId = serverId,
                    subject = subject.ifEmpty { "No subject" },
                    body = body,
                    categories = emptyList(),
                    lastModified = lastModified
                )
            )
        }

        return notes
    }

    private suspend fun syncNotesLegacySync(notesFolderId: String): EasResult<List<EasNote>> {
        val initialXml = EasXmlTemplates.syncInitial(notesFolderId)

        var syncKey = "0"
        when (val result = deps.executeEasCommand("Sync", initialXml) { deps.extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> syncKey = result.data
            is EasResult.Error -> return EasResult.Success(emptyList())
        }

        if (syncKey == "0") return EasResult.Success(emptyList())

        val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$notesFolderId</CollectionId>
            <GetChanges/>
        </Collection>
    </Collections>
</Sync>""".trimIndent()

        return deps.executeEasCommand("Sync", syncXml) { responseXml ->
            if (responseXml.contains("<Add>")) {
                parseNotesResponse(responseXml, legacy = true)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun syncDeletedNotesEws(): EasResult<List<EasNote>> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val findRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">
            <m:ItemShape>
                <t:BaseShape>AllProperties</t:BaseShape>
                <t:BodyType>Text</t:BodyType>
            </m:ItemShape>
            <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:ItemClass"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="IPM.StickyNote"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="deleteditems"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                    ?: return@withContext EasResult.Success(emptyList())

                val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
                    ?: return@withContext EasResult.Success(emptyList())

                val notes = parseEwsNotesResponse(responseXml).map { it.copy(isDeleted = true) }
                EasResult.Success(notes)
            } catch (e: Exception) {
                EasResult.Success(emptyList())
            }
        }
    }

    private fun isNoteItem(itemXml: String): Boolean {
        val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val dataXml = dataPattern.find(itemXml)?.groupValues?.get(1) ?: itemXml
        val messageClass = XmlValueExtractor.extractNote(dataXml, "MessageClass")
            ?: deps.extractValue(dataXml, "MessageClass")
            ?: ""
        return messageClass.isBlank() || messageClass.contains("StickyNote", ignoreCase = true)
    }
    
    private fun extractNoteCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesPattern = "<notes:Categories>(.*?)</notes:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val categoriesMatch = categoriesPattern.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            EasPatterns.NOTES_CATEGORY.findAll(categoriesXml).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }
    
    private fun parseNoteDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val cleanDate = dateStr.substringBefore("Z").substringBefore(".")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(cleanDate)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
