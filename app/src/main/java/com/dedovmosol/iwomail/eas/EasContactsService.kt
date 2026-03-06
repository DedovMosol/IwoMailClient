package com.dedovmosol.iwomail.eas

/**
 * Сервис для работы с контактами Exchange (EAS/GAL)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 * 
 * Отвечает за:
 * - Синхронизацию контактов из папки Contacts
 * - Поиск в глобальной адресной книге (GAL)
 */
class EasContactsService internal constructor(
    private val executeCommand: suspend (String, String, (String) -> List<GalContact>) -> EasResult<List<GalContact>>,
    private val folderSync: suspend (String) -> EasResult<FolderSyncResponse>,
    private val extractValue: (String, String) -> String?,
    private val getEasVersion: () -> String
) {

    private val syncKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private val ADD_PATTERN = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val APPLICATION_DATA_PATTERN = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val RESULT_PATTERN = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val PROPERTIES_PATTERN = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BRACKET_EMAIL = Regex("<([^>]+@[^>]+)>")
        private val SIMPLE_EMAIL = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

        /** Exchange 2007 SP1 может вернуть Email1Address в RFC 2822 формате
         *  `"Display Name" <user@domain>`. Извлекает чистый email-адрес. */
        fun extractEmailOnly(raw: String): String {
            if (raw.isBlank()) return ""
            BRACKET_EMAIL.find(raw)?.groupValues?.get(1)?.let { return it.trim() }
            SIMPLE_EMAIL.find(raw)?.value?.let { return it }
            return raw.trim()
        }
    }

    @Volatile var lastSyncWasIncrementalNoChanges: Boolean = false

    /**
     * Синхронизация контактов из папки Contacts на сервере Exchange
     * Возвращает список контактов
     */
    suspend fun syncContacts(): EasResult<List<GalContact>> {
        lastSyncWasIncrementalNoChanges = false

        val foldersResult = folderSync("0")
        val contactsFolderId = when (foldersResult) {
            is EasResult.Success -> {
                foldersResult.data.folders.find { it.type == 9 }?.serverId
            }
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        if (contactsFolderId == null) {
            return EasResult.Error("Папка контактов не найдена")
        }

        val cachedKey = syncKeyCache[contactsFolderId]
        if (cachedKey != null) {
            val probeXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${XmlUtils.escape(cachedKey)}</SyncKey>
            <CollectionId>${XmlUtils.escape(contactsFolderId)}</CollectionId>
            <GetChanges>1</GetChanges>
            <WindowSize>1</WindowSize>
        </Collection>
    </Collections>
</Sync>""".trimIndent()

            var probeStatus: String? = null
            var probeNewKey: String? = null
            var probeHasChanges = false

            val probeResult = executeCommand("Sync", probeXml) { responseXml ->
                probeStatus = extractValue(responseXml, "Status")
                probeNewKey = extractValue(responseXml, "SyncKey")
                probeHasChanges = responseXml.contains("<Add>") ||
                        responseXml.contains("<Change>") ||
                        responseXml.contains("<Delete>") ||
                        responseXml.contains("<SoftDelete>") ||
                        responseXml.contains("<MoreAvailable")
                emptyList()
            }

            if (probeResult is EasResult.Success) {
                when {
                    (probeStatus == "1" || probeStatus == null) && !probeHasChanges -> {
                        if (probeNewKey != null) syncKeyCache[contactsFolderId] = probeNewKey!!
                        lastSyncWasIncrementalNoChanges = true
                        return EasResult.Success(emptyList())
                    }
                    probeStatus == "3" || probeStatus == "12" -> {
                        syncKeyCache.remove(contactsFolderId)
                    }
                }
            }
        }
        
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>${XmlUtils.escape(contactsFolderId)}</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        var syncKey = "0"
        val initialResult = executeCommand("Sync", initialXml) { responseXml ->
            val key = extractValue(responseXml, "SyncKey") ?: "0"
            syncKey = key
            emptyList()
        }
        
        when (initialResult) {
            is EasResult.Success -> { /* syncKey уже установлен */ }
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        val easVersion = getEasVersion()
        val allContacts = mutableListOf<GalContact>()
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 10
        
        while (moreAvailable && iterations < maxIterations) {
            iterations++
            val syncXml = buildContactsSyncXml(syncKey, contactsFolderId, easVersion)
            
            val batchResult = executeCommand("Sync", syncXml) { responseXml ->
                val status = extractValue(responseXml, "Status")
                if (status == "3" || status == "12") {
                    syncKeyCache.remove(contactsFolderId)
                    throw Exception("InvalidSyncKey: Status=$status")
                }

                val newKey = extractValue(responseXml, "SyncKey")
                if (newKey != null && newKey != syncKey) syncKey = newKey
                moreAvailable = responseXml.contains("<MoreAvailable", ignoreCase = true)
                parseContactsSyncResponse(responseXml)
            }
            
            when (batchResult) {
                is EasResult.Success -> {
                    allContacts.addAll(batchResult.data)
                    if (batchResult.data.isEmpty()) moreAvailable = false
                }
                is EasResult.Error -> {
                    if (batchResult.message.contains("InvalidSyncKey")) {
                        syncKeyCache.remove(contactsFolderId)
                    }
                    if (allContacts.isNotEmpty()) break
                    return EasResult.Error(batchResult.message)
                }
            }
        }

        if (syncKey != "0") {
            syncKeyCache[contactsFolderId] = syncKey
        }
        
        return EasResult.Success(allContacts)
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска (имя или email). Пустая строка или "*" вернёт все контакты
     * @param maxResults Максимальное количество результатов (по умолчанию 2000)
     */
    suspend fun searchGAL(query: String, maxResults: Int = 2000): EasResult<List<GalContact>> {
        val searchQuery = if (query.isBlank() || query == "*") "*" else XmlUtils.escape(query)
        
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Search xmlns="Search" xmlns:gal="Gal">
    <Store>
        <Name>GAL</Name>
        <Query>$searchQuery</Query>
        <Options>
            <Range>0-${maxResults - 1}</Range>
        </Options>
    </Store>
</Search>""".trimIndent()
        
        return executeCommand("Search", xml) { responseXml ->
            parseGalSearchResponse(responseXml)
        }
    }
    
    // ==================== Private helpers ====================
    
    private fun buildContactsSyncXml(syncKey: String, contactsFolderId: String, easVersion: String): String {
        val safeKey = XmlUtils.escape(syncKey)
        val safeFolderId = XmlUtils.escape(contactsFolderId)
        return if (easVersion.startsWith("2.")) {
            // EAS 2.5 (Exchange 2003)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$safeKey</SyncKey>
                            <CollectionId>$safeFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>500</WindowSize>
                            <Options>
                                <Truncation>7</Truncation>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else {
            // EAS 12.x+ (Exchange 2007+)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                    <Collections>
                        <Collection>
                            <SyncKey>$safeKey</SyncKey>
                            <CollectionId>$safeFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>500</WindowSize>
                            <Options>
                                <airsyncbase:BodyPreference>
                                    <airsyncbase:Type>1</airsyncbase:Type>
                                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                                </airsyncbase:BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        }
    }
    
    private fun parseContactsSyncResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        ADD_PATTERN.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            val easServerId = extractContactValue(addXml, "ServerId") ?: ""

            val dataMatch = APPLICATION_DATA_PATTERN.find(addXml)
            if (dataMatch != null) {
                val dataXml = dataMatch.groupValues[1]

                val displayName = extractContactValue(dataXml, "FileAs")
                    ?: extractContactValue(dataXml, "DisplayName")
                    ?: ""
                val rawEmail = extractContactValue(dataXml, "Email1Address") ?: ""
                val email = extractEmailOnly(rawEmail)

                if (displayName.isNotEmpty() || email.isNotEmpty()) {
                    contacts.add(GalContact(
                        displayName = displayName,
                        email = email,
                        firstName = extractContactValue(dataXml, "FirstName") ?: "",
                        lastName = extractContactValue(dataXml, "LastName") ?: "",
                        company = extractContactValue(dataXml, "CompanyName") ?: "",
                        department = extractContactValue(dataXml, "Department") ?: "",
                        jobTitle = extractContactValue(dataXml, "JobTitle") ?: "",
                        phone = extractContactValue(dataXml, "BusinessPhoneNumber")
                            ?: extractContactValue(dataXml, "HomePhoneNumber") ?: "",
                        mobilePhone = extractContactValue(dataXml, "MobilePhoneNumber") ?: "",
                        easServerId = easServerId
                    ))
                }
            }
        }
        
        return contacts
    }
    
    private fun extractContactValue(xml: String, tag: String): String? {
        return XmlValueExtractor.extractContact(xml, tag)
    }
    
    private fun parseGalSearchResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 0
        if (status != 1) {
            return emptyList()
        }
        
        RESULT_PATTERN.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            val propsMatch = PROPERTIES_PATTERN.find(resultXml)
            if (propsMatch != null) {
                val propsXml = propsMatch.groupValues[1]
                
                val displayName = extractGalValue(propsXml, "DisplayName") ?: ""
                val rawGalEmail = extractGalValue(propsXml, "EmailAddress") ?: ""
                val email = extractEmailOnly(rawGalEmail)
                
                if (displayName.isNotEmpty() || email.isNotEmpty()) {
                    contacts.add(GalContact(
                        displayName = displayName,
                        email = email,
                        firstName = extractGalValue(propsXml, "FirstName") ?: "",
                        lastName = extractGalValue(propsXml, "LastName") ?: "",
                        company = extractGalValue(propsXml, "Company") ?: "",
                        department = extractGalValue(propsXml, "Office") ?: "",
                        jobTitle = extractGalValue(propsXml, "Title") ?: "",
                        phone = extractGalValue(propsXml, "Phone") ?: "",
                        mobilePhone = extractGalValue(propsXml, "MobilePhone") ?: "",
                        alias = extractGalValue(propsXml, "Alias") ?: ""
                    ))
                }
            }
        }
        
        return contacts
    }
    
    private fun extractGalValue(xml: String, tag: String): String? {
        return XmlValueExtractor.extractGal(xml, tag)
    }
}
