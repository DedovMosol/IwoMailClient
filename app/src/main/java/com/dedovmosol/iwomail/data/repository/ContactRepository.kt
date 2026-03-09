package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.GalContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.UUID

// Кэш для vCard regex паттернов
private val vcardFieldCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
private val vcardAllFieldsCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

private val BRACKET_EMAIL_RE = Regex("<([^>]+@[^>]+)>")
private val SIMPLE_EMAIL_RE = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

/** Exchange 2007 SP1 EAS may return Email1Address as `"Name" <user@domain>` */
private fun extractCleanEmail(raw: String): String {
    if (raw.isBlank()) return ""
    BRACKET_EMAIL_RE.find(raw)?.groupValues?.get(1)?.let { return it.trim() }
    SIMPLE_EMAIL_RE.find(raw)?.value?.let { return it }
    return raw.trim()
}

/** SHA-256 хеш строки (первые 16 символов hex) — без коллизий hashCode */
private fun stableHash(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
}

/**
 * Репозиторий для работы с контактами
 */
class ContactRepository(context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val contactDao = database.contactDao()
    private val groupDao = database.contactGroupDao()
    private val accountRepo = RepositoryProvider.getAccountRepository(context)
    
    // === Получение контактов ===
    
    fun getContacts(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getContactsByAccount(accountId)
    }
    
    /**
     * Получить только локальные контакты
     */
    fun getLocalContacts(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getLocalContacts(accountId)
    }
    
    /**
     * Получить только Exchange контакты (синхронизированные с сервера)
     */
    fun getExchangeContacts(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getExchangeContacts(accountId)
    }
    
    /**
     * Контакты из папки Contacts на Exchange (личные контакты пользователя)
     */
    fun getExchangeFolderContacts(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getExchangeFolderContacts(accountId)
    }
    
    /**
     * Контакты из GAL (глобальная адресная книга организации)
     */
    fun getGalContacts(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getGalContacts(accountId)
    }
    
    suspend fun getContactsList(accountId: Long): List<ContactEntity> {
        return contactDao.getContactsByAccountList(accountId)
    }
    
    fun getContact(id: String): Flow<ContactEntity?> {
        return contactDao.getContactFlow(id)
    }
    
    suspend fun getContactById(id: String): ContactEntity? {
        return contactDao.getContact(id)
    }
    
    // === Поиск ===
    
    suspend fun searchLocalContacts(accountId: Long, query: String): List<ContactEntity> {
        if (query.isBlank()) return emptyList()
        return contactDao.searchContacts(accountId, query)
    }
    
    suspend fun searchForAutocomplete(accountId: Long, query: String, ownEmail: String, limit: Int = 10): List<ContactEntity> {
        if (query.isBlank()) return emptyList()
        return contactDao.searchForAutocomplete(accountId, query, ownEmail, limit)
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска. "*" или пустая строка вернёт все контакты
     * @param maxResults Максимальное количество результатов
     */
    suspend fun searchGAL(accountId: Long, query: String, maxResults: Int = 100): EasResult<List<GalContact>> {
        return withContext(Dispatchers.IO) {
            val easClient = accountRepo.createEasClient(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
            
            easClient.searchGAL(query, maxResults)
        }
    }
    
    /**
     * Синхронизация контактов с Exchange сервера
     * Загружает контакты из папки Contacts на сервере
     */
    suspend fun syncExchangeContacts(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                val result = easClient.syncContacts()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverContacts = result.data

                        if (easClient.contactsIncrementalNoChanges) {
                            val existingCount = contactDao.getExchangeContactsList(accountId)
                                .count { it.id.startsWith("${accountId}_exchange_") }
                            return@withContext EasResult.Success(existingCount)
                        }
                        
                        val existingContacts = contactDao.getExchangeContactsList(accountId)
                            .filter { it.id.startsWith("${accountId}_exchange_") }
                        val existingServerIds = existingContacts.mapNotNull { it.serverId }.toSet()
                        
                        val serverServerIds = serverContacts
                            .map { it.easServerId.ifEmpty { it.email } }
                            .filter { it.isNotEmpty() }
                            .toSet()
                        val deletedServerIds = existingServerIds - serverServerIds
                        
                        for (sid in deletedServerIds) {
                            val contactId = "${accountId}_exchange_${stableHash(sid)}"
                            contactDao.deleteById(contactId)
                        }
                        
                        val contactEntities = serverContacts.map { galContact ->
                            val cleanedEmail = extractCleanEmail(galContact.email)
                            val contactKey = galContact.easServerId.ifEmpty { cleanedEmail }
                            ContactEntity(
                                id = "${accountId}_exchange_${stableHash(contactKey)}",
                                accountId = accountId,
                                serverId = contactKey,
                                displayName = galContact.displayName.ifBlank { 
                                    "${galContact.firstName} ${galContact.lastName}".trim().ifBlank { cleanedEmail }
                                },
                                firstName = galContact.firstName,
                                lastName = galContact.lastName,
                                email = cleanedEmail,
                                phone = galContact.phone,
                                mobilePhone = galContact.mobilePhone,
                                company = galContact.company,
                                department = galContact.department,
                                jobTitle = galContact.jobTitle,
                                source = ContactSource.EXCHANGE
                            )
                        }
                        
                        if (contactEntities.isNotEmpty()) {
                            for (chunk in contactEntities.chunked(500)) {
                                contactDao.insertAll(chunk)
                            }
                        }
                        
                        EasResult.Success(contactEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.CONTACT_SYNC_ERROR)
            }
        }
    }
    
    /**
     * Синхронизация контактов из GAL в локальную БД
     * Загружает все контакты из глобальной адресной книги и сохраняет как EXCHANGE контакты
     */
    suspend fun syncGalContactsToDb(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем email текущего аккаунта для фильтрации "себя"
                val account = accountRepo.getAccount(accountId)
                val ownEmail = extractCleanEmail(account?.email ?: "").lowercase()
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                val allContacts = mutableListOf<GalContact>()
                val seenEmails = mutableSetOf<String>()
                
                // Сначала пробуем "*" (работает на новых серверах)
                when (val result = easClient.searchGAL("*", 2000)) {
                    is EasResult.Success -> {
                        if (result.data.isNotEmpty()) {
                            allContacts.addAll(result.data)
                            result.data.forEach { seenEmails.add(it.email.lowercase()) }
                        }
                    }
                    is EasResult.Error -> { /* попробуем по буквам */ }
                }
                
                // Если "*" не дал результатов — загружаем по буквам
                if (allContacts.isEmpty()) {
                    val latinLetters = ('a'..'z').toList()
                    val cyrillicLetters = ('а'..'я').toList()
                    for (alphabet in listOf(latinLetters, cyrillicLetters)) {
                        var consecutiveEmpty = 0
                        for (letter in alphabet) {
                            try {
                                when (val result = easClient.searchGAL(letter.toString(), 100)) {
                                    is EasResult.Success -> {
                                        var addedInBatch = 0
                                        result.data.forEach { contact ->
                                            val emailLower = contact.email.lowercase()
                                            if (emailLower !in seenEmails && emailLower.isNotBlank()) {
                                                seenEmails.add(emailLower)
                                                allContacts.add(contact)
                                                addedInBatch++
                                            }
                                        }
                                        consecutiveEmpty = if (addedInBatch == 0) consecutiveEmpty + 1 else 0
                                    }
                                    is EasResult.Error -> { consecutiveEmpty++ }
                                }
                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; consecutiveEmpty++ }
                            if (consecutiveEmpty >= 10) break
                        }
                    }
                }
                
                if (allContacts.isEmpty()) {
                    return@withContext EasResult.Success(0)
                }
                
                // Получаем существующие GAL контакты (только с _gal_ prefix, не _exchange_)
                val existingContacts = contactDao.getExchangeContactsList(accountId)
                    .filter { it.id.startsWith("${accountId}_gal_") }
                val existingEmails = existingContacts.map { it.email.lowercase() }.toSet()
                
                // Определяем какие контакты удалены на сервере
                val serverEmails = allContacts.map { it.email.lowercase() }.toSet()
                val deletedEmails = existingEmails - serverEmails
                
                // Удаляем только те, которых нет на сервере
                for (email in deletedEmails) {
                    val contactId = "${accountId}_gal_${stableHash(email)}"
                    contactDao.deleteById(contactId)
                }
                
                // Удаляем себя из БД если уже был сохранён ранее
                if (ownEmail.isNotBlank()) {
                    val selfContactId = "${accountId}_gal_${stableHash(ownEmail)}"
                    contactDao.deleteById(selfContactId)
                }
                
                // Добавляем/обновляем контакты с сервера (исключая себя)
                val ownDisplayName = account?.displayName?.lowercase() ?: ""
                val contactEntities = allContacts.mapNotNull { galContact ->
                    val cleanedGalEmail = extractCleanEmail(galContact.email)
                    if (cleanedGalEmail.isBlank()) return@mapNotNull null
                    val emailLower = cleanedGalEmail.lowercase()
                    if (ownEmail.isNotBlank() && emailLower == ownEmail) return@mapNotNull null
                    if (ownDisplayName.isNotBlank() && galContact.displayName.lowercase() == ownDisplayName) return@mapNotNull null
                    ContactEntity(
                        id = "${accountId}_gal_${stableHash(cleanedGalEmail)}",
                        accountId = accountId,
                        serverId = cleanedGalEmail,
                        displayName = galContact.displayName.ifBlank { 
                            "${galContact.firstName} ${galContact.lastName}".trim().ifBlank { cleanedGalEmail }
                        },
                        firstName = galContact.firstName,
                        lastName = galContact.lastName,
                        email = cleanedGalEmail,
                        phone = galContact.phone,
                        mobilePhone = galContact.mobilePhone,
                        company = galContact.company,
                        department = galContact.department,
                        jobTitle = galContact.jobTitle,
                        source = ContactSource.EXCHANGE
                    )
                }
                
                if (contactEntities.isNotEmpty()) {
                    for (chunk in contactEntities.chunked(500)) {
                        contactDao.insertAll(chunk)
                    }
                }
                
                // КРИТИЧНО: Возвращаем количество БЕЗ "себя" (как показывается в UI)
                val countWithoutSelf = if (ownEmail.isNotBlank()) {
                    contactEntities.count { it.email.lowercase() != ownEmail }
                } else {
                    contactEntities.size
                }
                
                EasResult.Success(countWithoutSelf)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.GAL_SYNC_ERROR)
            }
        }
    }
    
    // === Проверка дубликатов ===
    
    suspend fun findLocalDuplicate(accountId: Long, email: String): ContactEntity? {
        val cleaned = extractCleanEmail(email)
        if (cleaned.isBlank()) return null
        return withContext(Dispatchers.IO) {
            contactDao.findLocalByEmail(accountId, cleaned)
        }
    }
    
    // === Добавление/редактирование ===
    
    suspend fun addContact(
        accountId: Long,
        displayName: String,
        email: String,
        firstName: String = "",
        lastName: String = "",
        phone: String = "",
        mobilePhone: String = "",
        workPhone: String = "",
        company: String = "",
        department: String = "",
        jobTitle: String = "",
        notes: String = ""
    ): ContactEntity {
        val cleanedEmail = extractCleanEmail(email)
        val id = "${accountId}_${UUID.randomUUID()}"
        val contact = ContactEntity(
            id = id,
            accountId = accountId,
            displayName = displayName.ifBlank { "$firstName $lastName".trim().ifBlank { cleanedEmail } },
            firstName = firstName,
            lastName = lastName,
            email = cleanedEmail,
            phone = phone,
            mobilePhone = mobilePhone,
            workPhone = workPhone,
            company = company,
            department = department,
            jobTitle = jobTitle,
            notes = notes,
            source = ContactSource.LOCAL
        )
        contactDao.insert(contact)
        return contact
    }
    
    suspend fun updateContact(contact: ContactEntity) {
        contactDao.update(contact.copy(updatedAt = System.currentTimeMillis()))
    }
    
    suspend fun deleteContact(id: String) {
        val contact = contactDao.getContact(id)
        if (contact != null) {
            contactDao.delete(contact)
        }
    }
    
    suspend fun deleteContacts(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        // SQLite имеет лимит на количество параметров в IN clause (~999)
        // Разбиваем на батчи по 500
        var totalDeleted = 0
        ids.chunked(500).forEach { batch ->
            totalDeleted += contactDao.deleteByIds(batch)
        }
        return totalDeleted
    }
    
    // === Группы контактов ===
    
    fun getGroups(accountId: Long): Flow<List<ContactGroupEntity>> {
        return groupDao.getGroupsByAccount(accountId)
    }
    
    suspend fun getGroupsList(accountId: Long): List<ContactGroupEntity> {
        return groupDao.getGroupsByAccountList(accountId)
    }
    
    fun getContactsByGroup(accountId: Long, groupId: String): Flow<List<ContactEntity>> {
        return contactDao.getContactsByGroup(accountId, groupId)
    }
    
    fun getContactsWithoutGroup(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getContactsWithoutGroup(accountId)
    }
    
    fun getGroupContactCount(groupId: String): Flow<Int> {
        return groupDao.getContactCountFlow(groupId)
    }
    
    suspend fun createGroup(accountId: Long, name: String, color: Int? = null): ContactGroupEntity {
        val id = UUID.randomUUID().toString()
        
        // Палитра цветов для групп (12 ярких цветов Material Design)
        val groupColors = listOf(
            0xFF1976D2.toInt(), // Blue
            0xFFD32F2F.toInt(), // Red
            0xFF388E3C.toInt(), // Green
            0xFFF57C00.toInt(), // Orange
            0xFF7B1FA2.toInt(), // Purple
            0xFF0097A7.toInt(), // Cyan
            0xFFC2185B.toInt(), // Pink
            0xFF5D4037.toInt(), // Brown
            0xFF303F9F.toInt(), // Indigo
            0xFF00796B.toInt(), // Teal
            0xFFFBC02D.toInt(), // Yellow
            0xFF455A64.toInt()  // Blue Grey
        )
        
        // Автоматический выбор цвета: берём следующий цвет из палитры
        val selectedColor = color ?: run {
            val existingGroups = groupDao.getGroupsByAccountList(accountId)
            val usedColors = existingGroups.map { it.color }.toSet()
            // Находим первый неиспользованный цвет, или берём по индексу
            groupColors.firstOrNull { it !in usedColors } 
                ?: groupColors[existingGroups.size % groupColors.size]
        }
        
        val group = ContactGroupEntity(
            id = id,
            accountId = accountId,
            name = name,
            color = selectedColor
        )
        groupDao.insert(group)
        return group
    }
    
    suspend fun renameGroup(groupId: String, newName: String) {
        groupDao.rename(groupId, newName)
    }
    
    suspend fun updateGroupColor(groupId: String, color: Int) {
        groupDao.updateColor(groupId, color)
    }
    
    suspend fun deleteGroup(groupId: String) {
        // Сначала убираем все контакты из группы
        contactDao.removeAllFromGroup(groupId)
        // Затем удаляем группу
        groupDao.deleteById(groupId)
    }
    
    suspend fun moveContactToGroup(contactId: String, groupId: String?) {
        contactDao.moveToGroup(contactId, groupId)
    }
    
    // === Избранные ===
    
    fun getFavoriteContacts(accountId: Long): Flow<List<ContactEntity>> {
        return contactDao.getFavoriteContacts(accountId)
    }
    
    suspend fun getFavoriteContactsList(accountId: Long): List<ContactEntity> {
        return contactDao.getFavoriteContactsList(accountId)
    }
    
    suspend fun toggleFavorite(contactId: String) {
        val contact = contactDao.getContact(contactId) ?: return
        contactDao.setFavorite(contactId, !contact.isFavorite)
    }
    
    suspend fun setFavorite(contactId: String, isFavorite: Boolean) {
        contactDao.setFavorite(contactId, isFavorite)
    }
    
    // === Автодополнение ===
    
    /**
     * Увеличивает счётчик использования контакта (для сортировки в автодополнении)
     */
    suspend fun incrementUseCount(contactId: String) {
        contactDao.incrementUseCount(contactId)
    }
    
    /**
     * Добавляет контакт из email если его ещё нет, или увеличивает счётчик
     */
    suspend fun trackEmailUsage(accountId: Long, email: String, displayName: String) {
        val cleaned = extractCleanEmail(email)
        if (cleaned.isBlank()) return
        
        val existing = contactDao.findByEmail(accountId, cleaned)
        if (existing != null) {
            contactDao.incrementUseCount(existing.id)
        } else {
            addContact(
                accountId = accountId,
                displayName = displayName.ifBlank { cleaned.substringBefore("@") },
                email = cleaned
            )
        }
    }
    
    // === Импорт/Экспорт ===
    
    suspend fun exportToVCard(contacts: List<ContactEntity>): String = buildString {
        contacts.forEach { c ->
            appendVCardEntry(
                c.displayName, c.firstName, c.lastName,
                listOf(c.email, c.email2),
                listOf("HOME" to c.phone, "CELL" to c.mobilePhone, "WORK" to c.workPhone),
                c.company, c.jobTitle, c.notes
            )
        }
    }

    fun exportGalToVCard(contacts: List<GalContact>): String = buildString {
        contacts.forEach { c ->
            appendVCardEntry(
                c.displayName, c.firstName, c.lastName,
                listOf(c.email),
                listOf("WORK" to c.phone, "CELL" to c.mobilePhone),
                c.company, c.jobTitle
            )
        }
    }

    suspend fun exportToCSV(contacts: List<ContactEntity>): String = buildCsvExport(
        "DisplayName,FirstName,LastName,Email,Email2,Phone,MobilePhone,WorkPhone,Company,Department,JobTitle,Notes",
        contacts.map { listOf(it.displayName, it.firstName, it.lastName, it.email, it.email2, it.phone, it.mobilePhone, it.workPhone, it.company, it.department, it.jobTitle, it.notes) }
    )

    fun exportGalToCSV(contacts: List<GalContact>): String = buildCsvExport(
        "DisplayName,FirstName,LastName,Email,Phone,MobilePhone,Company,Department,JobTitle",
        contacts.map { listOf(it.displayName, it.firstName, it.lastName, it.email, it.phone, it.mobilePhone, it.company, it.department, it.jobTitle) }
    )

    private fun StringBuilder.appendVCardEntry(
        displayName: String, firstName: String, lastName: String,
        emails: List<String>, phones: List<Pair<String, String>>,
        company: String, jobTitle: String, notes: String = ""
    ) {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        append("FN:${escapeVCard(displayName)}\r\n")
        if (lastName.isNotBlank() || firstName.isNotBlank()) {
            append("N:${escapeVCard(lastName)};${escapeVCard(firstName)};;;\r\n")
        }
        emails.forEach { email -> if (email.isNotBlank()) append("EMAIL:$email\r\n") }
        phones.forEach { (type, number) -> if (number.isNotBlank()) append("TEL;TYPE=$type:$number\r\n") }
        if (company.isNotBlank()) append("ORG:${escapeVCard(company)}\r\n")
        if (jobTitle.isNotBlank()) append("TITLE:${escapeVCard(jobTitle)}\r\n")
        if (notes.isNotBlank()) append("NOTE:${escapeVCard(notes)}\r\n")
        append("END:VCARD\r\n")
    }

    private fun buildCsvExport(header: String, rows: List<List<String>>): String = buildString {
        append(header).append("\r\n")
        rows.forEach { fields ->
            append(fields.joinToString(",") { escapeCSV(it) }).append("\r\n")
        }
    }
    
    /**
     * Импорт контактов из vCard
     */
    suspend fun importFromVCard(accountId: Long, vCardData: String): Int {
        var imported = 0
        val vcards = vCardData.split("END:VCARD")
        
        database.withTransaction {
        for (vcard in vcards) {
            if (!vcard.contains("BEGIN:VCARD")) continue
            
            val displayName = extractVCardField(vcard, "FN") ?: continue
            val email = extractVCardField(vcard, "EMAIL") ?: ""
            
            if (displayName.isBlank() && email.isBlank()) continue
            
            // Парсим имя
            val nameParts = extractVCardField(vcard, "N")?.split(";") ?: emptyList()
            val lastName = nameParts.getOrNull(0) ?: ""
            val firstName = nameParts.getOrNull(1) ?: ""
            
            // Парсим телефоны
            val phones = extractAllVCardFields(vcard, "TEL")
            val mobilePhone = phones.find { it.first.contains("CELL", true) }?.second ?: ""
            val workPhone = phones.find { it.first.contains("WORK", true) }?.second ?: ""
            val homePhone = phones.find { it.first.contains("HOME", true) }?.second 
                ?: phones.firstOrNull { !it.first.contains("CELL", true) && !it.first.contains("WORK", true) }?.second 
                ?: ""
            
            addContact(
                accountId = accountId,
                displayName = displayName,
                email = email,
                firstName = firstName,
                lastName = lastName,
                phone = homePhone,
                mobilePhone = mobilePhone,
                workPhone = workPhone,
                company = extractVCardField(vcard, "ORG") ?: "",
                jobTitle = extractVCardField(vcard, "TITLE") ?: "",
                notes = extractVCardField(vcard, "NOTE") ?: ""
            )
            imported++
        }
        }
        
        return imported
    }
    
    /**
     * Импорт контактов из CSV
     */
    suspend fun importFromCSV(accountId: Long, csvData: String): Int {
        var imported = 0
        val lines = csvData.lines()
        if (lines.size < 2) return 0
        
        // Парсим заголовок
        val header = parseCSVLine(lines[0])
        val displayNameIdx = header.indexOfFirst { it.equals("DisplayName", true) || it.equals("Name", true) }
        val firstNameIdx = header.indexOfFirst { it.equals("FirstName", true) || it.equals("First Name", true) }
        val lastNameIdx = header.indexOfFirst { it.equals("LastName", true) || it.equals("Last Name", true) }
        val emailIdx = header.indexOfFirst { it.equals("Email", true) || it.equals("E-mail Address", true) }
        val phoneIdx = header.indexOfFirst { it.equals("Phone", true) || it.equals("Home Phone", true) }
        val mobileIdx = header.indexOfFirst { it.equals("MobilePhone", true) || it.equals("Mobile Phone", true) }
        val workPhoneIdx = header.indexOfFirst { it.equals("WorkPhone", true) || it.equals("Business Phone", true) }
        val companyIdx = header.indexOfFirst { it.equals("Company", true) || it.equals("Organization", true) }
        val jobTitleIdx = header.indexOfFirst { it.equals("JobTitle", true) || it.equals("Job Title", true) }
        
        database.withTransaction {
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            
            val values = parseCSVLine(line)
            
            val displayName = values.getOrNull(displayNameIdx) ?: ""
            val email = values.getOrNull(emailIdx) ?: ""
            
            if (displayName.isBlank() && email.isBlank()) continue
            
            addContact(
                accountId = accountId,
                displayName = displayName,
                email = email,
                firstName = values.getOrNull(firstNameIdx) ?: "",
                lastName = values.getOrNull(lastNameIdx) ?: "",
                phone = values.getOrNull(phoneIdx) ?: "",
                mobilePhone = values.getOrNull(mobileIdx) ?: "",
                workPhone = values.getOrNull(workPhoneIdx) ?: "",
                company = values.getOrNull(companyIdx) ?: "",
                jobTitle = values.getOrNull(jobTitleIdx) ?: ""
            )
            imported++
        }
        }
        
        return imported
    }
    
    // === Вспомогательные методы ===
    
    private fun escapeVCard(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
    }
    
    private fun escapeCSV(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
    
    private fun extractVCardField(vcard: String, field: String): String? {
        val pattern = vcardFieldCache.getOrPut(field) {
            "(?m)^$field[^:]*:(.*)$".toRegex()
        }
        return pattern.find(vcard)?.groupValues?.get(1)?.trim()
            ?.replace("\\n", "\n")
            ?.replace("\\,", ",")
            ?.replace("\\;", ";")
            ?.replace("\\\\", "\\")
    }
    
    private fun extractAllVCardFields(vcard: String, field: String): List<Pair<String, String>> {
        val pattern = vcardAllFieldsCache.getOrPut(field) {
            "(?m)^($field[^:]*):(.*)$".toRegex()
        }
        return pattern.findAll(vcard).map { 
            it.groupValues[1] to it.groupValues[2].trim()
        }.toList()
    }
    
    private fun parseCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i += 2
                    continue
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
            i++
        }
        result.add(current.toString())
        
        return result
    }
}
