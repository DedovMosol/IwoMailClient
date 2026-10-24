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
    private val executeCommand: EasCommandExecutor,
    private val folderSync: suspend (String) -> EasResult<FolderSyncResponse>,
    private val extractValue: (String, String) -> String?,
    private val getEasVersion: () -> String
) {

    /** EAS-команда с типизированным разбором ответа — как у остальных EAS-сервисов. */
    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }

    private val syncKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "EasContactsService"
        private const val MAX_SYNC_PAGES = 10 // WindowSize=500 → до 5000 контактов
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
                foldersResult.data.folders.find { it.type == FolderType.CONTACTS }?.serverId
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

            val probeResult = executeCommand("Sync", probeXml) { responseXml ->
                val hasChanges = responseXml.contains("<Add>") ||
                        responseXml.contains("<Change>") ||
                        responseXml.contains("<Delete>") ||
                        responseXml.contains("<SoftDelete>") ||
                        responseXml.contains("<MoreAvailable")
                Triple(extractValue(responseXml, "Status"), extractValue(responseXml, "SyncKey"), hasChanges)
            }

            if (probeResult is EasResult.Success) {
                val (status, newKey, hasChanges) = probeResult.data
                when {
                    (status == "1" || status == null) && !hasChanges -> {
                        if (newKey != null) syncKeyCache[contactsFolderId] = newKey
                        // Изменений нет: снимок не авторитетен (ничего не удаляем), данные актуальны.
                        return EasResult.Success(ContactsSyncResult(emptyList(), isAuthoritative = false, isUnchanged = true))
                    }
                    status == "3" || status == "12" -> syncKeyCache.remove(contactsFolderId)
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
        
        var syncKey = when (val initial = executeCommand("Sync", initialXml) { extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> initial.data
            is EasResult.Error -> return initial
        }
        // Без ключа выдачи нет: это сбой, а не пустая папка (как у задач и заметок).
        if (syncKey == "0") return EasResult.Error("Sync контактов: сервер не выдал SyncKey")
        
        val easVersion = getEasVersion()
        val allContacts = mutableListOf<GalContact>()
        // Снимок полон, только если сервер сам сообщил конец выдачи: Status=1 на каждой странице
        // и MoreAvailable исчерпан. Любой досрочный выход записывает причину сюда. Раньше Status≠1
        // (кроме 3/12) и пустая страница с MoreAvailable сходили за конец выдачи, и репозиторий
        // удалял контакты, которых сервер просто не успел отдать.
        var incompleteReason: String? = null
        var keyInvalidated = false
        var iterations = 0
        
        while (true) {
            if (iterations >= MAX_SYNC_PAGES) {
                incompleteReason = "лимит $MAX_SYNC_PAGES страниц"
                break
            }
            iterations++
            val syncXml = buildContactsSyncXml(syncKey, contactsFolderId, easVersion)
            
            val page = when (val result = executeCommand("Sync", syncXml) { parseContactsSyncPage(it) }) {
                is EasResult.Success -> result.data
                is EasResult.Error -> {
                    incompleteReason = result.message
                    break
                }
            }
            if (page.status != "1") {
                // 3 — Invalid SyncKey, 12 — иерархия папок изменилась: ключ недействителен.
                if (page.status == "3" || page.status == "12") {
                    syncKeyCache.remove(contactsFolderId)
                    keyInvalidated = true
                }
                incompleteReason = "Status=${page.status}"
                break
            }

            page.syncKey?.let { syncKey = it }
            allContacts.addAll(page.contacts)
            if (!page.moreAvailable) break
            // MoreAvailable без данных: сервер обещает продолжение, но не отдаёт его.
            if (page.contacts.isEmpty()) {
                incompleteReason = "MoreAvailable без данных"
                break
            }
        }

        // Недействительный ключ не возвращаем в кэш: проба по нему — лишний запрос со Status=3.
        if (!keyInvalidated) syncKeyCache[contactsFolderId] = syncKey

        if (incompleteReason != null) {
            android.util.Log.w(TAG, "syncContacts: incomplete ($incompleteReason), ${allContacts.size} contacts, deletions disabled")
            // Сбой до первого контакта — ошибка синка, а не «пустая папка».
            if (allContacts.isEmpty()) return EasResult.Error("Синхронизация контактов прервана: $incompleteReason")
        }
        return EasResult.Success(ContactsSyncResult(allContacts, isAuthoritative = incompleteReason == null))
    }

    /** Страница EAS Sync контактов. */
    private class ContactsSyncPage(
        val status: String,
        val syncKey: String?,
        val contacts: List<GalContact>,
        val moreAvailable: Boolean
    )

    /**
     * Страница собирается только из целиком разобранного ответа: ключ страницы, которую не удалось
     * разобрать, «подтвердил» бы серверу недоставленные контакты, и проба по нему сказала бы
     * «изменений нет».
     */
    private fun parseContactsSyncPage(responseXml: String): ContactsSyncPage {
        // MS-ASCMD: Status коллекции обязателен; пустое тело ответа транспорт подменяет на Status=1.
        val status = extractValue(responseXml, "Status") ?: "1"
        if (status != "1") return ContactsSyncPage(status, syncKey = null, contacts = emptyList(), moreAvailable = false)
        return ContactsSyncPage(
            status = status,
            syncKey = extractValue(responseXml, "SyncKey"),
            contacts = parseContactsSyncResponse(responseXml),
            moreAvailable = responseXml.contains("<MoreAvailable")
        )
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
