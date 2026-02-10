package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.imap.ImapClient
import com.dedovmosol.iwomail.pop3.Pop3Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Репозиторий для управления аккаунтами Exchange
 * Single Responsibility: только работа с аккаунтами
 */
class AccountRepository(private val context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val accountDao = database.accountDao()
    private val attachmentDao = database.attachmentDao()
    
    // Кэш EAS клиентов для предотвращения утечек памяти
    // Каждый EasClient создаёт OkHttpClient с connection pool
    private val easClientCache = ConcurrentHashMap<Long, EasClient>()
    
    private val securePrefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                "secure_passwords",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback на обычные SharedPreferences если шифрование не работает
            context.getSharedPreferences("passwords_fallback", Context.MODE_PRIVATE)
        }
    }
    
    val accounts: Flow<List<AccountEntity>> = accountDao.getAllAccounts()
    val activeAccount: Flow<AccountEntity?> = accountDao.getActiveAccount()
    
    suspend fun getActiveAccountSync(): AccountEntity? = accountDao.getActiveAccountSync()
    
    suspend fun getAccount(id: Long): AccountEntity? = accountDao.getAccount(id)
    
    suspend fun getAccountCount(): Int = accountDao.getCount()
    
    /**
     * Добавляет новый аккаунт после проверки подключения
     */
    suspend fun addAccount(
        email: String,
        displayName: String,
        serverUrl: String,
        username: String,
        password: String,
        domain: String,
        acceptAllCerts: Boolean,
        color: Int,
        accountType: AccountType = AccountType.EXCHANGE,
        incomingPort: Int = 993,
        outgoingServer: String = "",
        outgoingPort: Int = 587,
        useSSL: Boolean = true,
        syncMode: SyncMode = SyncMode.SCHEDULED,
        certificatePath: String? = null,
        clientCertificatePath: String? = null,
        clientCertificatePassword: String? = null
    ): EasResult<Long> {
        // Проверяем что аккаунт с таким email ещё не добавлен
        val existingAccounts = accountDao.getAllAccountsList()
        if (existingAccounts.any { it.email.equals(email, ignoreCase = true) }) {
            return EasResult.Error("ACCOUNT_EXISTS")
        }
        
        // Проверяем наличие интернета
        if (!isNetworkAvailable()) {
            return EasResult.Error("NO_INTERNET")
        }
        
        // Проверяем подключение в зависимости от типа
        val connectionResult: EasResult<Unit> = try {
            when (accountType) {
                AccountType.EXCHANGE -> {
                    val client = try {
                        EasClient(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            domain = domain,
                            acceptAllCerts = acceptAllCerts,
                            port = incomingPort,
                            useHttps = useSSL,
                            deviceIdSuffix = email, // Используем email для стабильного deviceId
                            certificatePath = certificatePath,
                            clientCertificatePath = clientCertificatePath,
                            clientCertificatePassword = clientCertificatePassword
                        )
                    } catch (e: IllegalArgumentException) {
                        // Клиентский сертификат указан без пароля
                        return EasResult.Error(e.message ?: "CLIENT_CERT_PASSWORD_REQUIRED")
                    }
                    when (val result = client.testConnection()) {
                        is EasResult.Success -> EasResult.Success(Unit)
                        is EasResult.Error -> EasResult.Error(result.message)
                    }
                }
                AccountType.IMAP -> {
                    try {
                        val tempAccount = AccountEntity(
                            email = email,
                            displayName = displayName,
                            serverUrl = serverUrl,
                            username = username,
                            acceptAllCerts = acceptAllCerts,
                            accountType = accountType.name,
                            incomingPort = incomingPort,
                            useSSL = useSSL
                        )
                        val client = ImapClient(tempAccount, password)
                        client.connect().fold(
                            onSuccess = { 
                                client.disconnect()
                                EasResult.Success(Unit) 
                            },
                            onFailure = { e -> 
                                EasResult.Error(formatConnectionError(e, "IMAP")) 
                            }
                        )
                    } catch (e: Exception) {
                        EasResult.Error(formatConnectionError(e, "IMAP"))
                    }
                }
                AccountType.POP3 -> {
                    try {
                        val tempAccount = AccountEntity(
                            email = email,
                            displayName = displayName,
                            serverUrl = serverUrl,
                            username = username,
                            acceptAllCerts = acceptAllCerts,
                            accountType = accountType.name,
                            incomingPort = incomingPort,
                            useSSL = useSSL
                        )
                        val client = Pop3Client(tempAccount, password)
                        client.connect().fold(
                            onSuccess = { 
                                client.disconnect()
                                EasResult.Success(Unit) 
                            },
                            onFailure = { e -> 
                                EasResult.Error(formatConnectionError(e, "POP3")) 
                            }
                        )
                    } catch (e: Exception) {
                        EasResult.Error(formatConnectionError(e, "POP3"))
                    }
                }
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка подключения: ${e.message ?: "Неизвестная ошибка"}")
        }
        
        return when (connectionResult) {
            is EasResult.Success -> {
                val account = AccountEntity(
                    email = email,
                    displayName = displayName,
                    serverUrl = serverUrl,
                    username = username,
                    domain = domain,
                    acceptAllCerts = acceptAllCerts,
                    color = color,
                    isActive = accountDao.getCount() == 0,
                    accountType = accountType.name,
                    incomingPort = incomingPort,
                    outgoingServer = outgoingServer,
                    outgoingPort = outgoingPort,
                    useSSL = useSSL,
                    syncMode = syncMode.name,
                    certificatePath = certificatePath,
                    clientCertificatePath = clientCertificatePath
                )
                
                val accountId = accountDao.insert(account)
                savePassword(accountId, password)
                
                // Сохраняем пароль клиентского сертификата если есть
                if (clientCertificatePassword != null) {
                    saveClientCertPassword(accountId, clientCertificatePassword)
                }
                
                // Делаем новый аккаунт активным
                accountDao.setActiveAccount(accountId)
                
                // Запускаем немедленную синхронизацию для нового аккаунта
                com.dedovmosol.iwomail.sync.SyncWorker.syncNow(context)
                
                // Для Exchange аккаунтов запускаем синхронизацию календаря и задач в фоне
                if (accountType == AccountType.EXCHANGE) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val calendarRepo = CalendarRepository(context)
                            calendarRepo.syncCalendar(accountId)
                        } catch (_: Exception) { }
                        try {
                            val taskRepo = TaskRepository(context)
                            taskRepo.syncTasks(accountId)
                        } catch (_: Exception) { }
                    }
                }
                
                // Запускаем PushService только для Exchange аккаунтов с режимом PUSH
                if (accountType == AccountType.EXCHANGE && syncMode == SyncMode.PUSH) {
                    com.dedovmosol.iwomail.sync.PushService.start(context)
                }
                
                EasResult.Success(accountId)
            }
            is EasResult.Error -> EasResult.Error(connectionResult.message)
        }
    }
    
    suspend fun updateAccount(account: AccountEntity, password: String? = null) {
        // Получаем старый аккаунт для очистки кэша сертификатов
        val oldAccount = accountDao.getAccount(account.id)
        
        // Очищаем кэш HTTP клиентов если изменились пути к сертификатам
        if (oldAccount != null) {
            // Очищаем кеш для старого серверного сертификата
            if (oldAccount.certificatePath != account.certificatePath) {
                oldAccount.certificatePath?.let { 
                    com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(it)
                }
                // Также очищаем кеш для нового пути (если он есть)
                account.certificatePath?.let {
                    com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(it)
                }
            }
            // Очищаем кеш для старого клиентского сертификата
            if (oldAccount.clientCertificatePath != account.clientCertificatePath) {
                oldAccount.clientCertificatePath?.let { 
                    com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(it)
                }
                // Также очищаем кеш для нового пути (если он есть)
                account.clientCertificatePath?.let {
                    com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(it)
                }
            }
        }
        
        // Очищаем кэшированный клиент чтобы при следующем запросе создался новый с актуальными настройками
        clearEasClientCache(account.id)
        accountDao.update(account)
        password?.let { savePassword(account.id, it) }
    }
    
    suspend fun deleteAccount(accountId: Long) {
        // Очищаем кэшированный клиент
        clearEasClientCache(accountId)
        
        // Очищаем кэш PushService (heartbeat и EasClient)
        com.dedovmosol.iwomail.sync.PushService.clearAccountCache(context, accountId)
        
        // Очищаем кэш папок UI
        com.dedovmosol.iwomail.ui.FoldersCache.clearAccount(accountId)
        
        // Сбрасываем состояние синхронизации для этого аккаунта
        com.dedovmosol.iwomail.ui.InitialSyncController.resetAccount(accountId)
        
        // Очищаем флаг первой синхронизации
        val settingsRepo = SettingsRepository.getInstance(context)
        settingsRepo.resetInitialSyncFlag(accountId)
        
        // Удаляем файлы вложений с диска (до каскадного удаления из БД)
        withContext(Dispatchers.IO) {
            try {
                val localPaths = attachmentDao.getLocalPathsByAccount(accountId)
                localPaths.forEach { path ->
                    try {
                        File(path).delete()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        
        // Удаляем сертификаты если были
        val account = accountDao.getAccount(accountId)
        account?.certificatePath?.let { certPath ->
            try {
                File(certPath).delete()
                // Очищаем кэш HttpClientProvider для этого сертификата
                com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(certPath)
            } catch (_: Exception) {}
        }
        account?.clientCertificatePath?.let { clientCertPath ->
            try {
                File(clientCertPath).delete()
                // Очищаем кэш HttpClientProvider для клиентского сертификата
                com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(clientCertPath)
            } catch (_: Exception) {}
        }
        
        // Удаляем аккаунт (каскадно удалятся папки, письма, вложения, контакты)
        accountDao.delete(accountId)
        deletePassword(accountId)
        deleteClientCertPassword(accountId)
        
        // Если удалили активный аккаунт, активируем первый доступный
        if (accountDao.getActiveAccountSync() == null) {
            accountDao.getAllAccountsList().firstOrNull()?.let {
                accountDao.setActiveAccount(it.id)
            }
        }
    }
    
    suspend fun setActiveAccount(accountId: Long) {
        accountDao.setActiveAccount(accountId)
    }
    
    fun getPassword(accountId: Long): String? {
        return securePrefs.getString("password_$accountId", null)
    }
    
    private fun savePassword(accountId: Long, password: String) {
        securePrefs.edit().putString("password_$accountId", password).apply()
    }
    
    private fun deletePassword(accountId: Long) {
        securePrefs.edit().remove("password_$accountId").apply()
    }
    
    /**
     * Сохранение пароля клиентского сертификата
     */
    private fun saveClientCertPassword(accountId: Long, password: String) {
        securePrefs.edit()
            .putString("client_cert_password_$accountId", password)
            .apply()
    }
    
    /**
     * Получение пароля клиентского сертификата
     */
    private fun getClientCertPassword(accountId: Long): String? {
        return securePrefs.getString("client_cert_password_$accountId", null)
    }
    
    /**
     * Удаление пароля клиентского сертификата (при удалении аккаунта)
     */
    private fun deleteClientCertPassword(accountId: Long) {
        securePrefs.edit()
            .remove("client_cert_password_$accountId")
            .apply()
    }
    
    /**
     * Создаёт или возвращает кэшированный EAS клиент для указанного аккаунта
     * Кэширование предотвращает создание множества OkHttpClient и утечки памяти
     */
    suspend fun createEasClient(accountId: Long): EasClient? {
        // Проверяем кэш
        easClientCache[accountId]?.let { return it }
        
        val account = accountDao.getAccount(accountId) ?: return null
        val password = getPassword(accountId) ?: return null
        val clientCertPassword = getClientCertPassword(accountId)
        
        // Получаем информацию о привязанном сертификате для Certificate Pinning
        val pinnedCertInfo = if (account.pinnedCertificateHash != null && 
                                  account.certificatePinningEnabled) {
            com.dedovmosol.iwomail.network.HttpClientProvider.CertificateInfo(
                hash = account.pinnedCertificateHash,
                cn = account.pinnedCertificateCN ?: "Unknown",
                organization = account.pinnedCertificateOrg ?: "Unknown",
                validFrom = account.pinnedCertificateValidFrom ?: 0L,
                validTo = account.pinnedCertificateValidTo ?: 0L
            )
        } else {
            null
        }
        
        val client = try {
            EasClient(
                serverUrl = account.serverUrl,
                username = account.username,
                password = password,
                domain = account.domain,
                acceptAllCerts = account.acceptAllCerts,
                port = account.incomingPort,
                useHttps = account.useSSL,
                deviceIdSuffix = account.email, // Используем email для стабильного deviceId
                initialPolicyKey = account.policyKey, // Передаём сохранённый PolicyKey
                certificatePath = account.certificatePath,
                clientCertificatePath = account.clientCertificatePath,
                clientCertificatePassword = clientCertPassword,
                pinnedCertInfo = pinnedCertInfo,  // Передаём информацию для Certificate Pinning
                accountId = accountId  // Передаём ID для отслеживания изменений
            )
        } catch (e: IllegalArgumentException) {
            // Клиентский сертификат указан без пароля
            android.util.Log.e("AccountRepository", "Failed to create EasClient: ${e.message}")
            return null
        }
        
        // Сохраняем в кэш
        easClientCache[accountId] = client
        return client
    }
    
    /**
     * Очищает кэшированный клиент для аккаунта (при удалении или изменении настроек)
     */
    fun clearEasClientCache(accountId: Long) {
        easClientCache.remove(accountId)
    }
    
    /**
     * Очищает весь кэш клиентов
     */
    fun clearAllEasClientCache() {
        easClientCache.clear()
    }
    
    /**
     * Сохраняет PolicyKey для аккаунта
     */
    suspend fun savePolicyKey(accountId: Long, policyKey: String?) {
        accountDao.updatePolicyKey(accountId, policyKey)
    }
    
    /**
     * Обновляет режим синхронизации для аккаунта
     */
    suspend fun updateSyncMode(accountId: Long, syncMode: SyncMode) {
        accountDao.updateSyncMode(accountId, syncMode.name)
    }
    
    /**
     * Обновляет интервал синхронизации для аккаунта
     */
    suspend fun updateSyncInterval(accountId: Long, intervalMinutes: Int) {
        accountDao.updateSyncInterval(accountId, intervalMinutes)
    }
    
    /**
     * Обновляет подпись для аккаунта
     */
    suspend fun updateSignature(accountId: Long, signature: String) {
        accountDao.updateSignature(accountId, signature)
    }
    
    /**
     * Обновляет путь к сертификату для аккаунта
     */
    suspend fun updateCertificatePath(accountId: Long, certificatePath: String?) {
        // Получаем старый путь для очистки кэша
        val account = accountDao.getAccount(accountId)
        account?.certificatePath?.let { oldPath ->
            com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(oldPath)
        }
        // Очищаем кэш клиента чтобы использовался новый сертификат
        clearEasClientCache(accountId)
        accountDao.updateCertificatePath(accountId, certificatePath)
    }
    
    /**
     * Обновляет путь к клиентскому сертификату для аккаунта
     */
    suspend fun updateClientCertificatePath(accountId: Long, clientCertificatePath: String?) {
        // Получаем старый путь для очистки кэша
        val account = accountDao.getAccount(accountId)
        account?.clientCertificatePath?.let { oldPath ->
            com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(oldPath)
        }
        // Очищаем кэш клиента чтобы использовался новый клиентский сертификат
        clearEasClientCache(accountId)
        accountDao.updateClientCertificatePath(accountId, clientCertificatePath)
    }
    
    /**
     * Обновляет пароль клиентского сертификата для аккаунта
     */
    suspend fun updateClientCertificatePassword(accountId: Long, password: String?) {
        // Получаем аккаунт для очистки кэша HTTP клиента
        val account = accountDao.getAccount(accountId)
        account?.clientCertificatePath?.let { certPath ->
            com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(certPath)
        }
        
        // Очищаем кэш клиента чтобы использовался новый пароль
        clearEasClientCache(accountId)
        if (password != null) {
            saveClientCertPassword(accountId, password)
        } else {
            deleteClientCertPassword(accountId)
        }
    }
    
    /**
     * Обновляет настройки автоочистки корзины для аккаунта
     */
    suspend fun updateAutoCleanupTrashDays(accountId: Long, days: Int) {
        accountDao.updateAutoCleanupTrashDays(accountId, days)
    }
    
    /**
     * Обновляет настройки автоочистки черновиков для аккаунта
     */
    suspend fun updateAutoCleanupDraftsDays(accountId: Long, days: Int) {
        accountDao.updateAutoCleanupDraftsDays(accountId, days)
    }
    
    /**
     * Обновляет настройки автоочистки спама для аккаунта
     */
    suspend fun updateAutoCleanupSpamDays(accountId: Long, days: Int) {
        accountDao.updateAutoCleanupSpamDays(accountId, days)
    }
    
    /**
     * Обновляет интервал синхронизации контактов для аккаунта
     */
    suspend fun updateContactsSyncInterval(accountId: Long, days: Int) {
        accountDao.updateContactsSyncInterval(accountId, days)
    }
    
    /**
     * Обновляет ключ синхронизации контактов для аккаунта
     */
    suspend fun updateContactsSyncKey(accountId: Long, syncKey: String) {
        accountDao.updateContactsSyncKey(accountId, syncKey)
    }
    
    /**
     * Обновляет интервал синхронизации заметок для аккаунта
     */
    suspend fun updateNotesSyncInterval(accountId: Long, days: Int) {
        accountDao.updateNotesSyncInterval(accountId, days)
    }
    
    /**
     * Обновляет ключ синхронизации заметок для аккаунта
     */
    suspend fun updateNotesSyncKey(accountId: Long, syncKey: String) {
        accountDao.updateNotesSyncKey(accountId, syncKey)
    }
    
    /**
     * Обновляет интервал синхронизации календаря для аккаунта
     */
    suspend fun updateCalendarSyncInterval(accountId: Long, days: Int) {
        accountDao.updateCalendarSyncInterval(accountId, days)
    }
    
    /**
     * Обновляет ключ синхронизации календаря для аккаунта
     */
    suspend fun updateCalendarSyncKey(accountId: Long, syncKey: String) {
        accountDao.updateCalendarSyncKey(accountId, syncKey)
    }
    
    /**
     * Обновляет интервал синхронизации задач для аккаунта
     */
    suspend fun updateTasksSyncInterval(accountId: Long, days: Int) {
        accountDao.updateTasksSyncInterval(accountId, days)
    }
    
    /**
     * Обновляет ключ синхронизации задач для аккаунта
     */
    suspend fun updateTasksSyncKey(accountId: Long, syncKey: String) {
        accountDao.updateTasksSyncKey(accountId, syncKey)
    }
    
    /**
     * Обновляет настройку ночного режима для аккаунта
     */
    suspend fun updateNightModeEnabled(accountId: Long, enabled: Boolean) {
        accountDao.updateNightModeEnabled(accountId, enabled)
    }
    
    /**
     * Обновляет настройку игнорирования экономии батареи для аккаунта
     */
    suspend fun updateIgnoreBatterySaver(accountId: Long, ignore: Boolean) {
        accountDao.updateIgnoreBatterySaver(accountId, ignore)
    }
    
    /**
     * Создаёт IMAP клиент для указанного аккаунта
     */
    suspend fun createImapClient(accountId: Long): ImapClient? {
        val account = accountDao.getAccount(accountId) ?: return null
        val password = getPassword(accountId) ?: return null
        return ImapClient(account, password)
    }
    
    /**
     * Создаёт POP3 клиент для указанного аккаунта
     */
    suspend fun createPop3Client(accountId: Long): Pop3Client? {
        val account = accountDao.getAccount(accountId) ?: return null
        val password = getPassword(accountId) ?: return null
        return Pop3Client(account, password)
    }
    
    suspend fun createClientForActiveAccount(): EasClient? {
        val account = accountDao.getActiveAccountSync() ?: return null
        return createEasClient(account.id)
    }
    
    /**
     * Форматирует ошибку подключения в понятное сообщение
     */
    private fun formatConnectionError(e: Throwable, protocol: String): String {
        val message = e.message ?: "Неизвестная ошибка"
        return when {
            message.contains("Authentication failed", ignoreCase = true) ||
            message.contains("AUTHENTICATIONFAILED", ignoreCase = true) -> 
                "Неверный логин или пароль"
            message.contains("Connection refused", ignoreCase = true) -> 
                "Сервер недоступен. Проверьте адрес и порт."
            message.contains("Connection timed out", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) -> 
                "Превышено время ожидания. Проверьте подключение к сети."
            message.contains("UnknownHostException", ignoreCase = true) ||
            message.contains("Unable to resolve host", ignoreCase = true) -> 
                "Сервер не найден. Проверьте адрес."
            message.contains("SSL", ignoreCase = true) ||
            message.contains("certificate", ignoreCase = true) -> 
                "Ошибка SSL. Попробуйте включить 'Принимать все сертификаты'."
            message.contains("STARTTLS", ignoreCase = true) -> 
                "Сервер требует STARTTLS. Попробуйте другой порт."
            else -> "Ошибка $protocol: $message"
        }
    }
    
    /**
     * Проверяет наличие активного интернет-соединения
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    /**
     * Выполняет операцию с EasClient, инкапсулируя бойлерплейт-проверки
     * @param accountId ID аккаунта
     * @param exchangeOnlyError Сообщение об ошибке если аккаунт не Exchange
     * @param operation Операция для выполнения с EasClient
     */
    suspend inline fun <T> withEasClient(
        accountId: Long,
        exchangeOnlyError: String = RepositoryErrors.NOTES_EXCHANGE_ONLY,
        crossinline operation: suspend (EasClient) -> EasResult<T>
    ): EasResult<T> {
        return withContext(Dispatchers.IO) {
            val account = getAccount(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
            
            if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                return@withContext EasResult.Error(exchangeOnlyError)
            }
            
            val easClient = createEasClient(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
            
            operation(easClient)
        }
    }
    
    // ========== Certificate Pinning Methods ==========
    
    /**
     * Привязывает сертификат сервера к аккаунту (Certificate Pinning)
     * Читает сертификат из файла и сохраняет его хэш
     */
    suspend fun pinCertificate(accountId: Long): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val account = getAccount(accountId) 
                    ?: return@withContext EasResult.Error("Account not found")
                
                if (account.certificatePath == null) {
                    return@withContext EasResult.Error("No certificate configured")
                }
                
                // Читаем сертификат из файла (он уже был сохранён при авторизации)
                val certFile = java.io.File(account.certificatePath)
                if (!certFile.exists()) {
                    return@withContext EasResult.Error("Certificate file not found")
                }
                
                val cert = try {
                    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
                    certFile.inputStream().use { inputStream ->
                        certFactory.generateCertificate(inputStream) as java.security.cert.X509Certificate
                    }
                } catch (e: Exception) {
                    return@withContext EasResult.Error("Failed to read certificate: ${e.message}")
                }
                
                val certInfo = com.dedovmosol.iwomail.network.HttpClientProvider.extractCertificateInfo(cert)
                
                // Сохраняем всю информацию о сертификате
                accountDao.updatePinnedCertificate(
                    accountId = accountId,
                    hash = certInfo.hash,
                    cn = certInfo.cn,
                    org = certInfo.organization,
                    validFrom = certInfo.validFrom,
                    validTo = certInfo.validTo
                )
                accountDao.updateCertificatePinningFailCount(accountId, 0)
                com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.clearChange(accountId)
                clearEasClientCache(accountId)
                
                EasResult.Success(certInfo.hash)
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Обновляет хэш привязанного сертификата
     * При передаче null отключает Certificate Pinning
     */
    suspend fun updatePinnedCertHash(accountId: Long, hash: String?) {
        if (hash == null) {
            // При отключении защиты очищаем всю информацию
            accountDao.updatePinnedCertificate(accountId, null, null, null, null, null)
            accountDao.updateCertificatePinningFailCount(accountId, 0)
            com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.clearChange(accountId)
        } else {
            accountDao.updatePinnedCertHash(accountId, hash)
        }
        // Очищаем кэш клиента чтобы использовались новые настройки
        clearEasClientCache(accountId)
    }
    
    /**
     * Увеличивает счётчик ошибок проверки сертификата
     */
    suspend fun incrementCertificatePinningFailCount(accountId: Long) {
        val account = getAccount(accountId) ?: return
        accountDao.updateCertificatePinningFailCount(accountId, account.certificatePinningFailCount + 1)
    }
    
    /**
     * Обновляет информацию о привязанном сертификате
     */
    suspend fun updatePinnedCertificate(
        accountId: Long,
        hash: String?,
        cn: String?,
        org: String?,
        validFrom: Long?,
        validTo: Long?
    ) {
        accountDao.updatePinnedCertificate(accountId, hash, cn, org, validFrom, validTo)
        clearEasClientCache(accountId)
    }
    
    /**
     * Обновляет счётчик ошибок проверки сертификата
     */
    suspend fun updateCertificatePinningFailCount(accountId: Long, count: Int) {
        accountDao.updateCertificatePinningFailCount(accountId, count)
    }
    
    /**
     * Включает или отключает Certificate Pinning для аккаунта
     */
    suspend fun updateCertificatePinningEnabled(accountId: Long, enabled: Boolean) {
        accountDao.updateCertificatePinningEnabled(accountId, enabled)
        // Очищаем кэш клиента чтобы использовались новые настройки
        clearEasClientCache(accountId)
    }
}

