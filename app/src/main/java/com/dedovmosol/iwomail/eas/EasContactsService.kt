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
    
    /**
     * Синхронизация контактов из папки Contacts на сервере Exchange
     * Возвращает список контактов
     */
    suspend fun syncContacts(): EasResult<List<GalContact>> {
        // Сначала получаем список папок чтобы найти папку Contacts (type = 9)
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
        
        // Шаг 1: Получаем начальный SyncKey для папки контактов
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>$contactsFolderId</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        var syncKey = "0"
        @Suppress("UNCHECKED_CAST")
        val initialResult = executeCommand("Sync", initialXml) { responseXml ->
            val key = extractValue(responseXml, "SyncKey") ?: "0"
            // Возвращаем пустой список, но сохраняем SyncKey
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
        
        // Шаг 2: Запрашиваем контакты (с пагинацией до 2000 шт.)
        val easVersion = getEasVersion()
        val allContacts = mutableListOf<GalContact>()
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 10 // 500 * 10 = 5000 контактов макс.
        
        while (moreAvailable && iterations < maxIterations) {
            iterations++
            val syncXml = buildContactsSyncXml(syncKey, contactsFolderId, easVersion)
            
            val batchResult = executeCommand("Sync", syncXml) { responseXml ->
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
                    // Если хоть что-то получили — возвращаем это
                    if (allContacts.isNotEmpty()) break
                    return EasResult.Error(batchResult.message)
                }
            }
        }
        
        return EasResult.Success(allContacts)
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска (имя или email). Пустая строка или "*" вернёт все контакты
     * @param maxResults Максимальное количество результатов (по умолчанию 2000)
     */
    suspend fun searchGAL(query: String, maxResults: Int = 2000): EasResult<List<GalContact>> {
        val searchQuery = if (query.isBlank() || query == "*") "*" else query
        
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
        return if (easVersion.startsWith("2.")) {
            // EAS 2.5 (Exchange 2003)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
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
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
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
        
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            
            val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataPattern.find(addXml)
            if (dataMatch != null) {
                val dataXml = dataMatch.groupValues[1]
                
                val displayName = extractContactValue(dataXml, "FileAs") 
                    ?: extractContactValue(dataXml, "DisplayName")
                    ?: ""
                val email = extractContactValue(dataXml, "Email1Address") ?: ""
                
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
                        mobilePhone = extractContactValue(dataXml, "MobilePhoneNumber") ?: ""
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
        
        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            val propsPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propsMatch = propsPattern.find(resultXml)
            if (propsMatch != null) {
                val propsXml = propsMatch.groupValues[1]
                
                val displayName = extractGalValue(propsXml, "DisplayName") ?: ""
                val email = extractGalValue(propsXml, "EmailAddress") ?: ""
                
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
