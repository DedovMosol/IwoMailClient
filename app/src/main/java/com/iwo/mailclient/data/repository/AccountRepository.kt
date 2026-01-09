package com.iwo.mailclient.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.SyncMode
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.eas.EasClient
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.imap.ImapClient
import com.iwo.mailclient.pop3.Pop3Client
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
        syncMode: SyncMode = SyncMode.PUSH,
        certificatePath: String? = null
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
                    val client = EasClient(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        domain = domain,
                        acceptAllCerts = acceptAllCerts,
                        port = incomingPort,
                        useHttps = useSSL,
                        deviceIdSuffix = email, // Используем email для стабильного deviceId
                        certificatePath = certificatePath
                    )
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
                    certificatePath = certificatePath
                )
                
                val accountId = accountDao.insert(account)
                savePassword(accountId, password)
                
                // Делаем новый аккаунт активным
                accountDao.setActiveAccount(accountId)
                
                // Запускаем немедленную синхронизацию для нового аккаунта
                com.iwo.mailclient.sync.SyncWorker.syncNow(context)
                
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
                    com.iwo.mailclient.sync.PushService.start(context)
                }
                
                EasResult.Success(accountId)
            }
            is EasResult.Error -> EasResult.Error(connectionResult.message)
        }
    }
    
    suspend fun updateAccount(account: AccountEntity, password: String? = null) {
        // Очищаем кэшированный клиент чтобы при следующем запросе создался новый с актуальными настройками
        clearEasClientCache(account.id)
        accountDao.update(account)
        password?.let { savePassword(account.id, it) }
    }
    
    suspend fun deleteAccount(accountId: Long) {
        // Очищаем кэшированный клиент
        clearEasClientCache(accountId)
        
        // Очищаем кэш PushService (heartbeat и EasClient)
        com.iwo.mailclient.sync.PushService.clearAccountCache(context, accountId)
        
        // Очищаем кэш папок UI
        com.iwo.mailclient.ui.FoldersCache.clearAccount(accountId)
        
        // Сбрасываем состояние синхронизации для этого аккаунта
        com.iwo.mailclient.ui.InitialSyncController.resetAccount(accountId)
        
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
        
        // Удаляем сертификат если был
        val account = accountDao.getAccount(accountId)
        account?.certificatePath?.let { certPath ->
            try {
                File(certPath).delete()
            } catch (_: Exception) {}
        }
        
        // Удаляем аккаунт (каскадно удалятся папки, письма, вложения, контакты)
        accountDao.delete(accountId)
        deletePassword(accountId)
        
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
     * Создаёт или возвращает кэшированный EAS клиент для указанного аккаунта
     * Кэширование предотвращает создание множества OkHttpClient и утечки памяти
     */
    suspend fun createEasClient(accountId: Long): EasClient? {
        // Проверяем кэш
        easClientCache[accountId]?.let { return it }
        
        val account = accountDao.getAccount(accountId) ?: return null
        val password = getPassword(accountId) ?: return null
        
        val client = EasClient(
            serverUrl = account.serverUrl,
            username = account.username,
            password = password,
            domain = account.domain,
            acceptAllCerts = account.acceptAllCerts,
            port = account.incomingPort,
            useHttps = account.useSSL,
            deviceIdSuffix = account.email, // Используем email для стабильного deviceId
            initialPolicyKey = account.policyKey, // Передаём сохранённый PolicyKey
            certificatePath = account.certificatePath
        )
        
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
        // Очищаем кэш клиента чтобы использовался новый сертификат
        clearEasClientCache(accountId)
        accountDao.updateCertificatePath(accountId, certificatePath)
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
}

