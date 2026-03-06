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
        private val ADD_PATTERN = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val CHANGE_PATTERN = "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val APPLICATION_DATA_PATTERN = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val NOTES_CATEGORIES_PATTERN = "<notes:Categories>(.*?)</notes:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_ITEM_PATTERN = "<t:(?:PostItem|Message)[^>]*>(.*?)</t:(?:PostItem|Message)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val RESULT_PATTERN = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val PROPERTIES_PATTERN = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_T_PATTERN = "<t:Body[^>]*>(.*?)</t:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_PATTERN = "<Body[^>]*>(.*?)</Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_M_PATTERN = "<m:Body[^>]*>(.*?)</m:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_AIRSYNC_DATA = "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_DATA = "<Body>.*?<Data>(.*?)</Data>.*?</Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_NOTES_DATA = "<notes:Body>.*?<Data>(.*?)</Data>.*?</notes:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY_NOTES = "<notes:Body>(.*?)</notes:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
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
        val performNtlmHandshake: suspend (String, String, String) -> String?,
        val executeNtlmRequest: suspend (String, String, String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String, String) -> String?,
        val getEwsUrl: () -> String,
        val findEwsNoteItemId: suspend (String, String, String, Boolean) -> String?
    )
    
    // Кэш ID папки заметок
    @Volatile private var cachedNotesFolderId: String? = null

    @Volatile var lastSyncWasIncrementalNoChanges: Boolean = false

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
     */
    suspend fun syncNotes(): EasResult<List<EasNote>> {
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
    
    private suspend fun syncNotesStandard(folders: List<EasFolder>): EasResult<List<EasNote>> {
        lastSyncWasIncrementalNoChanges = false

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
                            lastSyncWasIncrementalNoChanges = true
                            if (BuildConfig.DEBUG) android.util.Log.d("EasNotesService",
                                "syncNotesStandard: No changes detected (probe), skipping full sync")
                            return EasResult.Success(emptyList())
                        }
                    }
                    status == "3" || status == "12" -> {
                        syncKeyCache.remove(notesFolderId)
                    }
                }
            }
        }
        
        val allNotes = mutableListOf<EasNote>()
        
        // Активные заметки (полная синхронизация)
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
                val status = deps.extractValue(responseXml, "Status")
                if (status == "3" || status == "12") {
                    syncKeyCache.remove(folderId)
                    throw InvalidSyncKeyException(status)
                }

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
                    if (result.message.contains("InvalidSyncKey")) {
                        syncKeyCache.remove(folderId)
                    }
                    if (allNotes.isNotEmpty()) break
                    return EasResult.Error(result.message)
                }
            }
        }

        if (syncKey != "0") {
            syncKeyCache[folderId] = syncKey
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
        val notesFolderId = cachedNotesFolderId ?: deps.getNotesFolderId()
            ?: return EasResult.Error("Папка Notes не найдена")

        for (attempt in 0..1) {
            val syncKey = getValidSyncKey(notesFolderId)
            if (syncKey == null) {
                if (attempt < 1) continue
                return EasResult.Error("Не удалось получить SyncKey")
            }

            val deleteXml = EasXmlTemplates.syncDelete(syncKey, notesFolderId, serverId, deletesAsMoves = true)

            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                when (status) {
                    "1" -> {
                        val newKey = deps.extractValue(responseXml, "SyncKey")
                        if (newKey != null) syncKeyCache[notesFolderId] = newKey
                        true
                    }
                    "8" -> true
                    "3", "12" -> {
                        syncKeyCache.remove(notesFolderId)
                        throw InvalidSyncKeyException(status ?: "3")
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
    
    private suspend fun deleteNotePermanentlyEas(serverId: String): EasResult<Boolean> {
        val deletedItemsFolderId = deps.getDeletedItemsFolderId()
            ?: return EasResult.Error("Папка Deleted Items не найдена")

        for (attempt in 0..1) {
            val syncKey = getValidSyncKey(deletedItemsFolderId)
            if (syncKey == null) {
                if (attempt < 1) continue
                return EasResult.Error("Не удалось получить SyncKey")
            }

            val deleteXml = EasXmlTemplates.syncDelete(syncKey, deletedItemsFolderId, serverId, deletesAsMoves = false)

            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                when (status) {
                    "1" -> {
                        val newKey = deps.extractValue(responseXml, "SyncKey")
                        if (newKey != null) syncKeyCache[deletedItemsFolderId] = newKey
                        true
                    }
                    "8" -> true
                    "3", "12" -> {
                        syncKeyCache.remove(deletedItemsFolderId)
                        throw InvalidSyncKeyException(status ?: "3")
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
    
    private suspend fun executeEwsWithAuth(ewsUrl: String, soapRequest: String, operation: String): String? {
        var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, operation)
        if (responseXml == null) {
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, operation) ?: return null
            responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, operation)
        }
        return responseXml
    }
    
    private suspend fun syncNotesEws(): EasResult<List<EasNote>> = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: START - syncing ACTIVE notes")
            val ewsUrl = deps.getEwsUrl()
            
            // === 1. Синхронизация АКТИВНЫХ заметок из папки "notes" ===
            val findRequest = EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem("notes"))
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: FindItem (notes) request length=${findRequest.length}")
            
            val responseXml = executeEwsWithAuth(ewsUrl, findRequest, "FindItem")
            if (responseXml == null) {
                android.util.Log.e("NOT", "syncNotesEws: Auth FAILED for notes")
                return@withContext EasResult.Success(emptyList())
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Response length=${responseXml.length}")
            val activeNotes = parseEwsNotesResponse(responseXml).toMutableList()
            val activeWithBodies = fillNoteBodiesEws(ewsUrl, activeNotes).toMutableList()
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Parsed ${activeNotes.size} ACTIVE notes")
            
            // === 2. Синхронизация УДАЛЁННЫХ заметок из папки "deleteditems" ===
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: START - syncing DELETED notes")
            val findDeletedRequest = EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem("deleteditems"))
            
            val deletedResponseXml = executeEwsWithAuth(ewsUrl, findDeletedRequest, "FindItem")
            if (deletedResponseXml != null) {
                val deletedNotesRaw = parseEwsNotesResponse(deletedResponseXml)
                val deletedNotes = fillNoteBodiesEws(ewsUrl, deletedNotesRaw)
                    .map { it.copy(isDeleted = true) }
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Parsed ${deletedNotes.size} DELETED notes")
                activeWithBodies.addAll(deletedNotes)
            } else {
                android.util.Log.e("NOT", "syncNotesEws: Auth FAILED for deleteditems")
            }
            
            // === 3. Вывод всех заметок ===
            activeWithBodies.forEach { note ->
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: Note serverId=${note.serverId.take(20)}..., bodyLen=${note.body.length}, isDeleted=${note.isDeleted}")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "syncNotesEws: TOTAL ${activeWithBodies.size} notes (active + deleted)")
            EasResult.Success(activeWithBodies)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("NOT", "syncNotesEws: EXCEPTION ${e.message}", e)
            EasResult.Success(emptyList())
        }
    }
    
    private suspend fun createNoteEws(subject: String, body: String): EasResult<String> = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: START subjectLen=${subject.length}")
        try {
            val ewsUrl = deps.getEwsUrl()
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "createNoteEws: ewsUrl=$ewsUrl")
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
            
            val responseXml = executeEwsWithAuth(ewsUrl, soapRequest, "CreateItem")
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
                // КРИТИЧНО: Проверяем ОБА условия
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                                responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
                if (hasSuccess && hasNoError) {
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
            val ewsUrl = deps.getEwsUrl()
            
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                val foundId = deps.findEwsNoteItemId(ewsUrl, serverId, subject, false)
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
            
            val getItemResponse = executeEwsWithAuth(ewsUrl, getItemRequest, "GetItem")
            if (getItemResponse == null) {
                return@withContext EasResult.Error("Аутентификация не удалась")
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
            
            val responseXml = executeEwsWithAuth(ewsUrl, soapRequest, "UpdateItem")
            if (responseXml == null) {
                return@withContext EasResult.Error("Аутентификация не удалась")
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
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                deps.findEwsNoteItemId(ewsUrl, serverId, subject, false) ?: serverId
            }
            
            val soapRequest = EasXmlTemplates.ewsSoapRequest(
                EasXmlTemplates.ewsDeleteItem(ewsItemId, deleteType)
            )
            
            val responseXml = executeEwsWithAuth(ewsUrl, soapRequest, "DeleteItem")
                ?: return EasResult.Error("Аутентификация не удалась")
            
            // КРИТИЧНО: Для delete проверяем Success+NoError ИЛИ ErrorItemNotFound (уже удалено)
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")
            val success = (hasSuccess && hasNoError) || hasNotFound
            
            EasResult.Success(success)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка EWS: ${e.message}")
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
            val ewsUrl = deps.getEwsUrl()
            val ewsItemIds = mutableListOf<String>()

            for (sid in serverIds) {
                val ewsItemId = if (sid.length >= 50 && !sid.contains(":")) {
                    sid
                } else {
                    val searchDeleted = (deleteType == "HardDelete")
                    val subj = subjects[sid] ?: ""
                    deps.findEwsNoteItemId(ewsUrl, sid, subj, searchDeleted) ?: sid
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

            val responseXml = executeEwsWithAuth(ewsUrl, soapRequest, "DeleteItem")
                ?: return@withContext EasResult.Error("Аутентификация не удалась")

            if (BuildConfig.DEBUG) android.util.Log.d("EasNotesService",
                "deleteNotesBatchEws: ${ewsItemIds.size} items, deleteType=$deleteType, response len=${responseXml.length}")

            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("NoError")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")

            if ((hasSuccess && hasNoError) || hasNotFound) {
                EasResult.Success(ewsItemIds.size)
            } else {
                val errorCode = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>"
                    .toRegex(RegexOption.DOT_MATCHES_ALL).find(responseXml)?.groupValues?.get(1)?.trim()
                EasResult.Error("EWS batch DeleteItem notes: $errorCode")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка batch EWS notes: ${e.message}")
        }
    }
    
    private suspend fun restoreNoteEws(serverId: String, subject: String = ""): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: START serverId=$serverId")
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: Using serverId as EWS ItemId")
                serverId
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "restoreNoteEws: Finding EWS ItemId for EAS serverId (searching in deleteditems)")
                deps.findEwsNoteItemId(ewsUrl, serverId, subject, true) ?: run {
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
            val responseXml = executeEwsWithAuth(ewsUrl, soapRequest, "MoveItem")
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
                // КРИТИЧНО: Проверяем ОБА условия
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                                responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
                if (hasSuccess && hasNoError) {
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
    
    private fun parseNotesResponse(xml: String, legacy: Boolean = false): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        val patterns = listOf(ADD_PATTERN, CHANGE_PATTERN)
        
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
        
        val dataXml = APPLICATION_DATA_PATTERN.find(itemXml)?.groupValues?.get(1) ?: return null
        
        val subject = XmlValueExtractor.extractNote(dataXml, "Subject") ?: ""
        val body = extractNoteBody(dataXml).ifEmpty {
            XmlUtils.unescape(deps.extractValue(dataXml, "notes:Body")
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
        
        if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Searching for <t:PostItem> or <t:Message>")
        EWS_ITEM_PATTERN.findAll(xml).forEach { match ->
            val itemXml = match.groupValues[1]
            
            val itemId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: run {
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: No ItemId found, skipping")
                return@forEach
            }
            
            // Извлекаем значения с разными namespace
            val subject = XmlValueExtractor.extractEws(itemXml, "Subject") ?: ""
            val bodyRaw = extractEwsBody(itemXml)
                ?: XmlValueExtractor.extractEws(itemXml, "Body")
                ?: ""
            val itemClass = XmlValueExtractor.extractEws(itemXml, "ItemClass") ?: ""
            
            if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Found item: itemId=${itemId.take(20)}..., bodyLen=${bodyRaw.length}, itemClass='$itemClass'")
            
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
                if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Skipped (not StickyNote): itemClass='$itemClass'")
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("NOT", "parseEwsNotesResponse: Returning ${notes.size} notes")
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

        val responseXml = executeEwsWithAuth(ewsUrl, getItemRequest, "GetItem") ?: return emptyMap()

        val result = mutableMapOf<String, String>()
        EWS_ITEM_PATTERN.findAll(responseXml).forEach { match ->
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
        val bodyPatterns = listOf(BODY_T_PATTERN, BODY_PATTERN, BODY_M_PATTERN)
        for (regex in bodyPatterns) {
            val match = regex.find(xml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return stripHtmlIfNeeded(XmlUtils.unescape(match.groupValues[1].trim()))
            }
        }
        return null
    }
    
    private fun extractNoteBody(xml: String): String {
        val bodyPatterns = listOf(BODY_AIRSYNC_DATA, BODY_DATA, BODY_NOTES_DATA, BODY_NOTES)
        for (regex in bodyPatterns) {
            val match = regex.find(xml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return stripHtmlIfNeeded(XmlUtils.unescape(match.groupValues[1].trim()))
            }
        }
        return ""
    }

    private fun stripHtmlIfNeeded(text: String): String =
        com.dedovmosol.iwomail.util.stripHtmlIfNeeded(text)

    private fun parseNotesSearchResponse(xml: String): List<EasNote> {
        val notes = mutableListOf<EasNote>()

        RESULT_PATTERN.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]

            val serverId = deps.extractValue(resultXml, "LongId")
                ?: deps.extractValue(resultXml, "ServerId")
                ?: return@forEach

            val propertiesMatch = PROPERTIES_PATTERN.find(resultXml) ?: return@forEach
            val propsXml = propertiesMatch.groupValues[1]

            val messageClass = deps.extractValue(propsXml, "MessageClass") ?: ""
            if (messageClass.isNotEmpty() && !messageClass.contains("StickyNote", ignoreCase = true)) {
                return@forEach
            }

            val subject = XmlUtils.unescape(deps.extractValue(propsXml, "Subject") ?: "")
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
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(notesFolderId)}</CollectionId>
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

                val responseXml = executeEwsWithAuth(ewsUrl, findRequest, "FindItem")
                    ?: return@withContext EasResult.Success(emptyList())

                val notes = parseEwsNotesResponse(responseXml).map { it.copy(isDeleted = true) }
                EasResult.Success(notes)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Success(emptyList())
            }
        }
    }

    private fun isNoteItem(itemXml: String): Boolean {
        val dataXml = APPLICATION_DATA_PATTERN.find(itemXml)?.groupValues?.get(1) ?: itemXml
        val messageClass = XmlValueExtractor.extractNote(dataXml, "MessageClass")
            ?: deps.extractValue(dataXml, "MessageClass")
            ?: ""
        return messageClass.isBlank() || messageClass.contains("StickyNote", ignoreCase = true)
    }
    
    private fun extractNoteCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesMatch = NOTES_CATEGORIES_PATTERN.find(xml)
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
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(cleanDate)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            android.util.Log.w("EasNotesService", "Failed to parse note date: $dateStr", e)
            System.currentTimeMillis()
        }
    }
}
