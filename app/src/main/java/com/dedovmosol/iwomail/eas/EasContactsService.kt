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
        // Sync контактов — один потоковый проход EasSyncStream; GAL Search — EasXmlParser.
        // Остаются ТОЛЬКО regex разбора email-СТРОКИ (не XML): RFC 2822 `"Name" <addr>` и простой адрес.
        private val ADD_ONLY = setOf("Add")
        private const val PICTURE_OPEN = "<Picture>"
        private const val PICTURE_CLOSE = "</Picture>"

        /** Листья ApplicationData контакта (MS-ASCNTC), которые читает [parseContactsSyncResponse]. */
        private val CONTACT_FIELDS = setOf(
            "FileAs", "DisplayName", "Email1Address", "FirstName", "LastName", "CompanyName",
            "Department", "JobTitle", "BusinessPhoneNumber", "HomePhoneNumber", "MobilePhoneNumber"
        )

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

    /**
     * Синхронизация контактов из папки Contacts на сервере Exchange.
     * Полнота снимка — в [ContactsSyncResult], как у задач/заметок (без @Volatile-флагов).
     */
    suspend fun syncContacts(): EasResult<ContactsSyncResult> {
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
                        // Изменений нет: снимок не авторитетен (ничего не удаляем), данные актуальны.
                        return EasResult.Success(ContactsSyncResult(emptyList(), isAuthoritative = false, isUnchanged = true))
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
            // Сервер не выдал SyncKey — снимок неполный, удаления запрещаем.
            return EasResult.Success(ContactsSyncResult(emptyList(), isAuthoritative = false))
        }
        
        val easVersion = getEasVersion()
        val allContacts = mutableListOf<GalContact>()
        var isAuthoritative = true
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
                    if (allContacts.isNotEmpty()) {
                        isAuthoritative = false
                        break
                    }
                    return EasResult.Error(batchResult.message)
                }
            }
        }

        // MoreAvailable ещё выставлен (упёрлись в лимит страниц) → выдача неполная.
        if (moreAvailable) {
            isAuthoritative = false
        }

        if (syncKey != "0") {
            syncKeyCache[contactsFolderId] = syncKey
        }
        
        return EasResult.Success(ContactsSyncResult(allContacts, isAuthoritative = isAuthoritative))
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска (имя или email). Пустая строка или "*" вернёт все контакты
     * @param maxResults Желаемое количество результатов; один GAL Search запрос в EAS возвращает не более 100
     */
    suspend fun searchGAL(query: String, maxResults: Int = 2000): EasResult<List<GalContact>> {
        val searchQuery = if (query.isBlank() || query == "*") "*" else XmlUtils.escape(query)
        val cappedResults = maxResults.coerceIn(1, 100)
        
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Search xmlns="Search" xmlns:gal="Gal">
    <Store>
        <Name>GAL</Name>
        <Query>$searchQuery</Query>
        <Options>
            <Range>0-${cappedResults - 1}</Range>
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
    
    /**
     * EAS Sync контактов (MS-ASCNTC) — один потоковый проход ([EasSyncStream]) по `Commands/Add`:
     * поля — прямые дети `ApplicationData`. Фото (`Picture`, base64) и тело пропускаются без
     * накопления текста. Оборванный или битый ответ — исключение (см. [EasSyncStream]).
     */
    internal fun parseContactsSyncResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        EasSyncStream.forEachCommand(withoutPictures(xml), ADD_ONLY) { _, p ->
            val item = EasSyncStream.readItem(p, CONTACT_FIELDS, withBody = false, withCategories = false)
                ?: return@forEachCommand
            val f = item.fields
            val displayName = f["FileAs"] ?: f["DisplayName"] ?: ""
            val email = extractEmailOnly(f["Email1Address"] ?: "")

            if (displayName.isNotEmpty() || email.isNotEmpty()) {
                contacts.add(GalContact(
                    displayName = displayName,
                    email = email,
                    firstName = f["FirstName"] ?: "",
                    lastName = f["LastName"] ?: "",
                    company = f["CompanyName"] ?: "",
                    department = f["Department"] ?: "",
                    jobTitle = f["JobTitle"] ?: "",
                    phone = f["BusinessPhoneNumber"] ?: f["HomePhoneNumber"] ?: "",
                    mobilePhone = f["MobilePhoneNumber"] ?: "",
                    easServerId = item.serverId ?: ""
                ))
            }
        }
        
        return contacts
    }

    /**
     * Вырезает элементы `<Picture>…</Picture>` (фото контакта, base64 до сотен КБ) до разбора.
     * Фото здесь не используется, а XmlPullParser материализует текст любого элемента, даже
     * пропускаемого: окно 100 контактов с фото 48K давало 17 МБ временных строк вместо 0,4 МБ.
     * В тексте декодированного WBXML `<` всегда экранирован, поэтому `<Picture>` — только тег.
     * Нет закрывающего тега (обрыв) — хвост остаётся как есть, и парсер честно сообщит об обрыве.
     */
    private fun withoutPictures(xml: String): String {
        var start = xml.indexOf(PICTURE_OPEN)
        if (start < 0) return xml
        // Без предвыделения xml.length: без фото результат в разы меньше исходника.
        val out = StringBuilder()
        var from = 0
        while (start >= 0) {
            val end = xml.indexOf(PICTURE_CLOSE, start + PICTURE_OPEN.length)
            if (end < 0) break
            out.append(xml, from, start)
            from = end + PICTURE_CLOSE.length
            start = xml.indexOf(PICTURE_OPEN, from)
        }
        return out.append(xml, from, xml.length).toString()
    }
    
    internal fun parseGalSearchResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        val status = EasXmlParser.extractValue(xml, "Status")?.toIntOrNull() ?: 0
        if (status != 1) {
            return emptyList()
        }

        EasXmlParser.forEachElement(xml, "Result") { resultXml ->
            // Свойства контакта GAL — внутри Properties (сохраняем прежнее скоупирование).
            val propsXml = EasXmlParser.collectElements(resultXml, "Properties").firstOrNull()
            if (propsXml != null) {
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
        return EasXmlParser.gal(xml, tag)
    }
}
