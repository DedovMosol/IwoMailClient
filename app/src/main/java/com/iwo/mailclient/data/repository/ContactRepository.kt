package com.iwo.mailclient.data.repository

import android.content.Context
import com.iwo.mailclient.data.database.*
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.eas.GalContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Репозиторий для работы с контактами
 */
class ContactRepository(context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val contactDao = database.contactDao()
    private val groupDao = database.contactGroupDao()
    private val accountRepo = AccountRepository(context)
    
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
    
    suspend fun searchForAutocomplete(accountId: Long, query: String): List<ContactEntity> {
        if (query.isBlank()) return emptyList()
        return contactDao.searchForAutocomplete(accountId, query)
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска. "*" или пустая строка вернёт все контакты
     * @param maxResults Максимальное количество результатов
     */
    suspend fun searchGAL(accountId: Long, query: String, maxResults: Int = 100): EasResult<List<GalContact>> {
        return withContext(Dispatchers.IO) {
            val easClient = accountRepo.createEasClient(accountId)
                ?: return@withContext EasResult.Error("Не удалось создать клиент")
            
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
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                // Получаем контакты с сервера
                val result = easClient.syncContacts()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverContacts = result.data
                        
                        // Удаляем старые Exchange контакты для этого аккаунта
                        contactDao.deleteExchangeContacts(accountId)
                        
                        // Добавляем новые
                        val contactEntities = serverContacts.map { galContact ->
                            ContactEntity(
                                id = "${accountId}_exchange_${galContact.email.hashCode()}",
                                accountId = accountId,
                                serverId = galContact.email, // Используем email как serverId
                                displayName = galContact.displayName.ifBlank { 
                                    "${galContact.firstName} ${galContact.lastName}".trim().ifBlank { galContact.email }
                                },
                                firstName = galContact.firstName,
                                lastName = galContact.lastName,
                                email = galContact.email,
                                phone = galContact.phone,
                                mobilePhone = galContact.mobilePhone,
                                company = galContact.company,
                                department = galContact.department,
                                jobTitle = galContact.jobTitle,
                                source = ContactSource.EXCHANGE
                            )
                        }
                        
                        if (contactEntities.isNotEmpty()) {
                            contactDao.insertAll(contactEntities)
                        }
                        
                        EasResult.Success(contactEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка синхронизации контактов")
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
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val allContacts = mutableListOf<GalContact>()
                val seenEmails = mutableSetOf<String>()
                
                // Сначала пробуем "*" (работает на новых серверах)
                when (val result = easClient.searchGAL("*", 500)) {
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
                    val letters = ('a'..'z').toList() + ('а'..'я').toList()
                    for (letter in letters) {
                        try {
                            when (val result = easClient.searchGAL(letter.toString(), 100)) {
                                is EasResult.Success -> {
                                    result.data.forEach { contact ->
                                        val emailLower = contact.email.lowercase()
                                        if (emailLower !in seenEmails && emailLower.isNotBlank()) {
                                            seenEmails.add(emailLower)
                                            allContacts.add(contact)
                                        }
                                    }
                                }
                                is EasResult.Error -> { /* продолжаем */ }
                            }
                        } catch (e: Exception) { /* игнорируем */ }
                    }
                }
                
                if (allContacts.isEmpty()) {
                    return@withContext EasResult.Success(0)
                }
                
                // Удаляем старые Exchange контакты
                contactDao.deleteExchangeContacts(accountId)
                
                // Сохраняем новые
                val contactEntities = allContacts.mapNotNull { galContact ->
                    if (galContact.email.isBlank()) return@mapNotNull null
                    ContactEntity(
                        id = "${accountId}_gal_${galContact.email.lowercase().hashCode()}",
                        accountId = accountId,
                        serverId = galContact.email,
                        displayName = galContact.displayName.ifBlank { 
                            "${galContact.firstName} ${galContact.lastName}".trim().ifBlank { galContact.email }
                        },
                        firstName = galContact.firstName,
                        lastName = galContact.lastName,
                        email = galContact.email,
                        phone = galContact.phone,
                        mobilePhone = galContact.mobilePhone,
                        company = galContact.company,
                        department = galContact.department,
                        jobTitle = galContact.jobTitle,
                        source = ContactSource.EXCHANGE
                    )
                }
                
                if (contactEntities.isNotEmpty()) {
                    contactDao.insertAll(contactEntities)
                }
                
                EasResult.Success(contactEntities.size)
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка синхронизации GAL")
            }
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
        val id = "${accountId}_${UUID.randomUUID()}"
        val contact = ContactEntity(
            id = id,
            accountId = accountId,
            displayName = displayName.ifBlank { "$firstName $lastName".trim().ifBlank { email } },
            firstName = firstName,
            lastName = lastName,
            email = email,
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
        return contactDao.deleteByIds(ids)
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
    
    suspend fun createGroup(accountId: Long, name: String, color: Int = 0xFF1976D2.toInt()): ContactGroupEntity {
        val id = UUID.randomUUID().toString()
        val group = ContactGroupEntity(
            id = id,
            accountId = accountId,
            name = name,
            color = color
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
        if (email.isBlank()) return
        
        val existing = contactDao.findByEmail(accountId, email)
        if (existing != null) {
            contactDao.incrementUseCount(existing.id)
        } else {
            // Добавляем как локальный контакт
            addContact(
                accountId = accountId,
                displayName = displayName.ifBlank { email.substringBefore("@") },
                email = email
            )
        }
    }
    
    // === Импорт/Экспорт ===
    
    /**
     * Экспорт контактов в vCard формат
     */
    suspend fun exportToVCard(contacts: List<ContactEntity>): String {
        return buildString {
            contacts.forEach { contact ->
                append("BEGIN:VCARD\r\n")
                append("VERSION:3.0\r\n")
                append("FN:${escapeVCard(contact.displayName)}\r\n")
                if (contact.lastName.isNotBlank() || contact.firstName.isNotBlank()) {
                    append("N:${escapeVCard(contact.lastName)};${escapeVCard(contact.firstName)};;;\r\n")
                }
                if (contact.email.isNotBlank()) {
                    append("EMAIL:${contact.email}\r\n")
                }
                if (contact.email2.isNotBlank()) {
                    append("EMAIL:${contact.email2}\r\n")
                }
                if (contact.phone.isNotBlank()) {
                    append("TEL;TYPE=HOME:${contact.phone}\r\n")
                }
                if (contact.mobilePhone.isNotBlank()) {
                    append("TEL;TYPE=CELL:${contact.mobilePhone}\r\n")
                }
                if (contact.workPhone.isNotBlank()) {
                    append("TEL;TYPE=WORK:${contact.workPhone}\r\n")
                }
                if (contact.company.isNotBlank()) {
                    append("ORG:${escapeVCard(contact.company)}\r\n")
                }
                if (contact.jobTitle.isNotBlank()) {
                    append("TITLE:${escapeVCard(contact.jobTitle)}\r\n")
                }
                if (contact.notes.isNotBlank()) {
                    append("NOTE:${escapeVCard(contact.notes)}\r\n")
                }
                append("END:VCARD\r\n")
            }
        }
    }
    
    /**
     * Экспорт GAL контактов в vCard формат
     */
    fun exportGalToVCard(contacts: List<GalContact>): String {
        return buildString {
            contacts.forEach { contact ->
                append("BEGIN:VCARD\r\n")
                append("VERSION:3.0\r\n")
                append("FN:${escapeVCard(contact.displayName)}\r\n")
                if (contact.lastName.isNotBlank() || contact.firstName.isNotBlank()) {
                    append("N:${escapeVCard(contact.lastName)};${escapeVCard(contact.firstName)};;;\r\n")
                }
                if (contact.email.isNotBlank()) {
                    append("EMAIL:${contact.email}\r\n")
                }
                if (contact.phone.isNotBlank()) {
                    append("TEL;TYPE=WORK:${contact.phone}\r\n")
                }
                if (contact.mobilePhone.isNotBlank()) {
                    append("TEL;TYPE=CELL:${contact.mobilePhone}\r\n")
                }
                if (contact.company.isNotBlank()) {
                    append("ORG:${escapeVCard(contact.company)}\r\n")
                }
                if (contact.jobTitle.isNotBlank()) {
                    append("TITLE:${escapeVCard(contact.jobTitle)}\r\n")
                }
                append("END:VCARD\r\n")
            }
        }
    }
    
    /**
     * Экспорт контактов в CSV формат
     */
    suspend fun exportToCSV(contacts: List<ContactEntity>): String {
        return buildString {
            // Заголовок
            append("DisplayName,FirstName,LastName,Email,Email2,Phone,MobilePhone,WorkPhone,Company,Department,JobTitle,Notes\r\n")
            // Данные
            contacts.forEach { contact ->
                append("${escapeCSV(contact.displayName)},")
                append("${escapeCSV(contact.firstName)},")
                append("${escapeCSV(contact.lastName)},")
                append("${escapeCSV(contact.email)},")
                append("${escapeCSV(contact.email2)},")
                append("${escapeCSV(contact.phone)},")
                append("${escapeCSV(contact.mobilePhone)},")
                append("${escapeCSV(contact.workPhone)},")
                append("${escapeCSV(contact.company)},")
                append("${escapeCSV(contact.department)},")
                append("${escapeCSV(contact.jobTitle)},")
                append("${escapeCSV(contact.notes)}\r\n")
            }
        }
    }
    
    /**
     * Экспорт GAL контактов в CSV формат
     */
    fun exportGalToCSV(contacts: List<GalContact>): String {
        return buildString {
            append("DisplayName,FirstName,LastName,Email,Phone,MobilePhone,Company,Department,JobTitle\r\n")
            contacts.forEach { contact ->
                append("${escapeCSV(contact.displayName)},")
                append("${escapeCSV(contact.firstName)},")
                append("${escapeCSV(contact.lastName)},")
                append("${escapeCSV(contact.email)},")
                append("${escapeCSV(contact.phone)},")
                append("${escapeCSV(contact.mobilePhone)},")
                append("${escapeCSV(contact.company)},")
                append("${escapeCSV(contact.department)},")
                append("${escapeCSV(contact.jobTitle)}\r\n")
            }
        }
    }
    
    /**
     * Импорт контактов из vCard
     */
    suspend fun importFromVCard(accountId: Long, vCardData: String): Int {
        var imported = 0
        val vcards = vCardData.split("END:VCARD")
        
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
        
        // Парсим данные
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
        val pattern = "(?m)^$field[^:]*:(.*)$".toRegex()
        return pattern.find(vcard)?.groupValues?.get(1)?.trim()
            ?.replace("\\n", "\n")
            ?.replace("\\,", ",")
            ?.replace("\\;", ";")
            ?.replace("\\\\", "\\")
    }
    
    private fun extractAllVCardFields(vcard: String, field: String): List<Pair<String, String>> {
        val pattern = "(?m)^($field[^:]*):(.*)$".toRegex()
        return pattern.findAll(vcard).map { 
            it.groupValues[1] to it.groupValues[2].trim()
        }.toList()
    }
    
    private fun parseCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString())
        
        return result
    }
}
