package com.dedovmosol.iwomail.eas

/**
 * EAS FolderSync / FolderCreate / FolderDelete / FolderUpdate service.
 *
 * MS-ASCMD 2.2.1.5 (FolderSync): SyncKey=0 → full hierarchy ("All folders MUST be returned").
 * MS-ASCMD 2.2.1.3/4/6 (FolderCreate/Delete/Update): require current SyncKey from last FolderSync.
 * MS-ASCMD 2.2.3.186.3 (Type codes): 1=Generic, 2=Inbox, 3=Drafts, 4=Deleted,
 *   5=Sent, 6=Outbox, 7=Tasks, 8=Calendar, 9=Contacts, 10=Notes, 11=Journal.
 *
 * Provision before first FolderSync (SyncKey=0) is MANDATORY (MS-ASPROV).
 *
 * Thread-safety:
 *  - [folderSyncCacheLock]: synchronized — one FolderSync at a time.
 *  - Cached folder IDs: @Volatile + lazy init via folderSync().
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class EasFolderSyncService(
    private val transport: EasTransport,
    private val versionDetector: EasVersionDetector
) {
    @Volatile private var cachedFolderSyncResult: FolderSyncResponse? = null
    @Volatile private var folderSyncCacheTimeMs: Long = 0L
    private val folderSyncCacheLock = Any()

    @Volatile private var cachedNotesFolderId: String? = null
    @Volatile private var cachedDeletedItemsFolderId: String? = null
    @Volatile private var cachedTasksFolderId: String? = null
    @Volatile private var cachedCalendarFolderId: String? = null
    @Volatile private var cachedDraftsFolderId: String? = null

    companion object {
        private const val FOLDER_SYNC_CACHE_TTL_MS = 120_000L
        private val FOLDER_REGEX get() = EasPatterns.FOLDER
    }

    suspend fun folderSync(syncKey: String = "0"): EasResult<FolderSyncResponse> {
        if (syncKey == "0") {
            synchronized(folderSyncCacheLock) {
                val cached = cachedFolderSyncResult
                if (cached != null && (System.currentTimeMillis() - folderSyncCacheTimeMs) < FOLDER_SYNC_CACHE_TTL_MS) {
                    return EasResult.Success(cached)
                }
                cachedFolderSyncResult = null
            }
        }

        if (!versionDetector.versionDetected) {
            versionDetector.detect()
        }

        if (syncKey == "0" || transport.policyKey == null) {
            if (syncKey == "0") {
                transport.policyKey = null
            }
            when (val provResult = transport.provision()) {
                is EasResult.Success -> { }
                is EasResult.Error -> return provResult
            }
        }

        val result = doFolderSync(syncKey)

        if (result is EasResult.Success && result.data.status == 1) {
            if (syncKey == "0") {
                synchronized(folderSyncCacheLock) {
                    cachedFolderSyncResult = result.data
                    folderSyncCacheTimeMs = System.currentTimeMillis()
                }
            }
            return result
        }

        val status = if (result is EasResult.Success) result.data.status else -1
        return EasResult.Error("Не удалось синхронизировать папки. Status: $status. DeviceId: ${transport.deviceId}")
    }

    private suspend fun doFolderSync(syncKey: String): EasResult<FolderSyncResponse> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FolderSync xmlns="FolderHierarchy">
    <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
</FolderSync>""".trimIndent()

        val skipPolicyKey = (transport.policyKey == null)
        return transport.executeEasCommand("FolderSync", xml, skipPolicyKey = skipPolicyKey) { responseXml ->
            parseFolderSyncResponse(responseXml)
        }
    }

    suspend fun createFolder(
        displayName: String,
        parentId: String = "0",
        folderType: Int = 12,
        syncKey: String
    ): EasResult<EasClient.FolderCreateResult> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FolderCreate xmlns="FolderHierarchy">
    <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
    <ParentId>${XmlUtils.escape(parentId)}</ParentId>
    <DisplayName>${XmlUtils.escape(displayName)}</DisplayName>
    <Type>$folderType</Type>
</FolderCreate>""".trimIndent()

        return transport.executeEasCommand("FolderCreate", xml) { responseXml ->
            val status = XmlUtils.extractTagValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val serverId = XmlUtils.extractTagValue(responseXml, "ServerId") ?: ""
            val newSyncKey = XmlUtils.extractTagValue(responseXml, "SyncKey") ?: syncKey

            if (status == 1 && serverId.isNotEmpty()) {
                EasClient.FolderCreateResult(serverId, newSyncKey)
            } else {
                val errorMsg = when (status) {
                    2 -> "Папка с таким именем уже существует"
                    3 -> "Системную папку нельзя создать"
                    5 -> "Родительская папка не найдена"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey, попробуйте синхронизировать папки"
                    10 -> "Неверный формат запроса"
                    11 -> "Неизвестная ошибка"
                    else -> "Ошибка создания папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }

    suspend fun deleteFolder(
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FolderDelete xmlns="FolderHierarchy">
    <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
    <ServerId>${XmlUtils.escape(serverId)}</ServerId>
</FolderDelete>""".trimIndent()

        return transport.executeEasCommand("FolderDelete", xml) { responseXml ->
            val status = XmlUtils.extractTagValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = XmlUtils.extractTagValue(responseXml, "SyncKey") ?: syncKey

            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    3 -> "Системную папку нельзя удалить"
                    4 -> "Папка не существует"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey"
                    else -> "Ошибка удаления папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }

    suspend fun renameFolder(
        serverId: String,
        newDisplayName: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FolderUpdate xmlns="FolderHierarchy">
    <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
    <ServerId>${XmlUtils.escape(serverId)}</ServerId>
    <ParentId>0</ParentId>
    <DisplayName>${XmlUtils.escape(newDisplayName)}</DisplayName>
</FolderUpdate>""".trimIndent()

        return transport.executeEasCommand("FolderUpdate", xml) { responseXml ->
            val status = XmlUtils.extractTagValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = XmlUtils.extractTagValue(responseXml, "SyncKey") ?: syncKey

            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    2 -> "Папка с таким именем уже существует"
                    3 -> "Системную папку нельзя переименовать"
                    4 -> "Папка не существует"
                    5 -> "Родительская папка не существует"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey"
                    else -> "Ошибка переименования папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }

    // ========================= Folder ID helpers =========================

    suspend fun getNotesFolderId(forceRefresh: Boolean = false): String? {
        if (forceRefresh) cachedNotesFolderId = null
        return cachedNotesFolderId ?: run {
            val foldersResult = folderSync("0")
            when (foldersResult) {
                is EasResult.Success -> {
                    val folderId = foldersResult.data.folders.find { it.type == FolderType.NOTES }?.serverId
                    if (folderId != null) cachedNotesFolderId = folderId
                    folderId
                }
                is EasResult.Error -> null
            }
        }
    }

    suspend fun getDeletedItemsFolderId(): String? {
        cachedDeletedItemsFolderId?.let { return it }
        val foldersResult = folderSync("0")
        return when (foldersResult) {
            is EasResult.Success -> {
                val folderId = foldersResult.data.folders.find { it.type == FolderType.DELETED_ITEMS }?.serverId
                if (folderId != null) cachedDeletedItemsFolderId = folderId
                folderId
            }
            is EasResult.Error -> null
        }
    }

    suspend fun getTasksFolderId(forceRefresh: Boolean = false): String? {
        if (forceRefresh) cachedTasksFolderId = null
        return cachedTasksFolderId ?: run {
            val foldersResult = folderSync("0")
            when (foldersResult) {
                is EasResult.Success -> {
                    val folderId = foldersResult.data.folders.find { it.type == 7 }?.serverId
                    if (folderId != null) cachedTasksFolderId = folderId
                    folderId
                }
                is EasResult.Error -> null
            }
        }
    }

    suspend fun getCalendarFolderId(): String? {
        cachedCalendarFolderId?.let { return it }
        val foldersResult = folderSync("0")
        return when (foldersResult) {
            is EasResult.Success -> {
                val folderId = foldersResult.data.folders.find { it.type == FolderType.CALENDAR }?.serverId
                if (folderId != null) cachedCalendarFolderId = folderId
                folderId
            }
            is EasResult.Error -> null
        }
    }

    suspend fun getDraftsFolderId(): String? {
        cachedDraftsFolderId?.let { return it }
        val foldersResult = folderSync("0")
        return when (foldersResult) {
            is EasResult.Success -> {
                val folderId = foldersResult.data.folders.find { it.type == FolderType.DRAFTS }?.serverId
                if (folderId != null) cachedDraftsFolderId = folderId
                folderId
            }
            is EasResult.Error -> null
        }
    }

    // ========================= Parsing =========================

    private fun parseFolderSyncResponse(xml: String): FolderSyncResponse {
        val status = XmlUtils.extractTagValue(xml, "Status")?.toIntOrNull() ?: 1
        val syncKey = XmlUtils.extractTagValue(xml, "SyncKey") ?: "0"
        val folders = mutableListOf<EasFolder>()
        val deletedFolderIds = mutableListOf<String>()

        if (status != 1) {
            return FolderSyncResponse(syncKey, folders, status, deletedFolderIds)
        }

        val hasFolderTags = FOLDER_REGEX.containsMatchIn(xml)
        if (hasFolderTags) {
            FOLDER_REGEX.findAll(xml).forEach { match ->
                val folderXml = match.groupValues[1]
                parseFolder(folderXml)?.let { folders.add(it) }
            }
        } else {
            for (block in XmlUtils.extractTopLevelBlocks(xml, "Add")) {
                parseFolder(block)?.let { folders.add(it) }
            }
        }

        for (block in XmlUtils.extractTopLevelBlocks(xml, "Delete")) {
            XmlUtils.extractTagValue(block, "ServerId")?.let { deletedFolderIds.add(it) }
        }

        return FolderSyncResponse(syncKey, folders, status, deletedFolderIds)
    }

    private fun parseFolder(folderXml: String): EasFolder? {
        val serverId = XmlUtils.extractTagValue(folderXml, "ServerId") ?: return null
        val displayName = XmlUtils.unescape(XmlUtils.extractTagValue(folderXml, "DisplayName") ?: "")
        val parentId = XmlUtils.extractTagValue(folderXml, "ParentId") ?: "0"
        val type = XmlUtils.extractTagValue(folderXml, "Type")?.toIntOrNull() ?: 1
        return EasFolder(serverId, displayName, parentId, type)
    }
}
