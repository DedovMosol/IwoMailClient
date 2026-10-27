package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.security.PasswordStorage
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.imap.ImapClient
import com.dedovmosol.iwomail.pop3.Pop3Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Репозиторий для управления аккаунтами Exchange
 * Single Responsibility: только работа с аккаунтами
 *
 * L-7 fix: Migrated from inline ObfuscatedSharedPreferences to PasswordStorage.
 */
class AccountRepository(private val context: Context) : com.dedovmosol.iwomail.sync.EndpointFailover {

    private val database = MailDatabase.getInstance(context)
    private val accountDao = database.accountDao()
    private val attachmentDao = database.attachmentDao()

    // L-7: Centralized password storage with security telemetry
    private val passwordStorage = PasswordStorage.getInstance(context)

    // Клиенты EAS по аккаунтам (каждый держит OkHttpClient с пулом соединений) и выбор сервера Dual-URL.
    private val easClients = EndpointClientCache(object : EndpointClientCache.Backend<EasClient> {
        override suspend fun endpoints(accountId: Long) = accountDao.getAccount(accountId)?.let {
            EndpointClientCache.Endpoints(it.serverUrl, it.alternateServerUrl, it.incomingPort)
        }

        override suspend fun create(accountId: Long, useAlternate: Boolean): EasClient? {
            val account = accountDao.getAccount(accountId) ?: return null
            val password = getPassword(accountId) ?: return null
            return buildEasClient(account, useAlternate, password)
        }

        override suspend fun isReachable(serverUrl: String, port: Int) = EasClient.probeHost(serverUrl, port)

        // Только INTERNET, без VALIDATED: Exchange в сети организации без выхода в интернет (Android
        // помечает её «без доступа к интернету») — штатный сценарий Dual-URL; доступность решает проба.
        override fun hasNetwork() = com.dedovmosol.iwomail.network.NetworkMonitor.isNetworkAvailable(context)
    })
    
    val accounts: Flow<List<AccountEntity>> = accountDao.getAllAccounts()
    val activeAccount: Flow<AccountEntity?> = accountDao.getActiveAccount()
    
    suspend fun getActiveAccountSync(): AccountEntity? = accountDao.getActiveAccountSync()

    suspend fun getAccount(id: Long): AccountEntity? = accountDao.getAccount(id)

    suspend fun getAccountCount(): Int = accountDao.getCount()

    /**
     * Check if password storage is using insecure fallback.
     * L-7: Expose security status to UI for warning banner.
     */
    fun isUsingInsecurePasswordStorage(): Boolean = passwordStorage.isUsingInsecureStorage()

    /**
     * Get reason for insecure password storage fallback.
     * L-7: For detailed security warnings.
     */
    fun getInsecurePasswordStorageReason(): String? = passwordStorage.getInsecureStorageReason()

    suspend fun getResolvedProfileRelativePath(accountId: Long): String? {
        val accounts = accountDao.getAllAccountsList()
        val account = accounts.firstOrNull { it.id == accountId } ?: return null
        return ProfilePathResolver.getProfileRelativePath(accounts, account.id, account.displayName)
    }

    suspend fun getResolvedCalendarRelativePath(accountId: Long): String? {
        val accounts = accountDao.getAllAccountsList()
        val account = accounts.firstOrNull { it.id == accountId } ?: return null
        return ProfilePathResolver.getCalendarRelativePath(accounts, account.id, account.displayName)
    }
    
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
        clientCertificatePassword: String? = null,
        alternateServerUrl: String? = null,
        // Подключение уже проверено (экран проверки Exchange сделал FolderSync через основной или
        // резервный адрес): повторная проверка стоила бы ещё одного таймаута недоступного основного.
        alreadyVerified: Boolean = false
    ): EasResult<Long> {
        // Проверяем что аккаунт с таким email ещё не добавлен
        val existingAccounts = accountDao.getAllAccountsList()
        if (existingAccounts.any { it.email.equals(email, ignoreCase = true) }) {
            return EasResult.Error("ACCOUNT_EXISTS")
        }
        
        // Есть ли сеть вообще. Не «интернет» (VALIDATED): Exchange в сети организации без выхода в
        // интернет — штатный случай, его доступность покажет сама проверка подключения.
        if (!alreadyVerified && !com.dedovmosol.iwomail.network.NetworkMonitor.isNetworkAvailable(context)) {
            return EasResult.Error("NO_INTERNET")
        }
        
        // Проверяем подключение в зависимости от типа
        val connectionResult: EasResult<Unit> = if (alreadyVerified) EasResult.Success(Unit) else try {
            when (accountType) {
                AccountType.EXCHANGE -> {
                    val primaryResult = testExchangeServer(
                        serverUrl, username, password, domain, acceptAllCerts,
                        incomingPort, useSSL, email, certificatePath,
                        clientCertificatePath, clientCertificatePassword
                    )
                    if (primaryResult is EasResult.Success) {
                        primaryResult
                    } else if (!alternateServerUrl.isNullOrBlank()
                        && com.dedovmosol.iwomail.data.model.isConnectionLevelError(
                            (primaryResult as EasResult.Error).message)
                    ) {
                        val altResult = testExchangeServer(
                            alternateServerUrl, username, password, domain, acceptAllCerts,
                            incomingPort, useSSL, email, certificatePath,
                            clientCertificatePath, clientCertificatePassword
                        )
                        com.dedovmosol.iwomail.data.model.preferredDualUrlResult(primaryResult, altResult)
                    } else {
                        primaryResult
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        EasResult.Error(formatConnectionError(e, "POP3"))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка подключения: ${e.message ?: "Неизвестная ошибка"}")
        }
        
        return when (connectionResult) {
            is EasResult.Success -> {
                // Глобальный дефолт режима черновиков (из онбординга или настроек)
                val defaultDraftMode = SettingsRepository.getInstance(context).getDefaultDraftModeSync()
                
                val account = AccountEntity(
                    email = email,
                    displayName = displayName,
                    serverUrl = serverUrl,
                    alternateServerUrl = alternateServerUrl,
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
                    clientCertificatePath = clientCertificatePath,
                    draftMode = defaultDraftMode
                )
                
                val accountId = accountDao.insert(account)
                savePassword(accountId, password)
                
                // Сохраняем пароль клиентского сертификата если есть
                if (clientCertificatePassword != null) {
                    saveClientCertPassword(accountId, clientCertificatePassword)
                }
                
                // Делаем новый аккаунт активным
                accountDao.setActiveAccount(accountId)

                // Если добавление аккаунта создало коллизию имён папок, переносим
                // существующие файлы затронутых аккаунтов в уникальные подпапки.
                reconcileProfileFolderChanges(
                    beforeAccounts = existingAccounts,
                    afterAccounts = accountDao.getAllAccountsList()
                )
                
                // Планируем периодическую синхронизацию (без немедленного запуска).
                // InitialSyncController.startSyncIfNeeded() (VerificationScreen) обеспечит
                // первичную синхронизацию нового аккаунта. SyncWorker.syncNow() НЕ вызывается,
                // чтобы избежать дублирующих конкурентных запросов к Exchange 2007 SP1.
                com.dedovmosol.iwomail.sync.SyncWorker.scheduleWithNightMode(context)
                
                // Запускаем PushService только для Exchange аккаунтов с режимом PUSH
                if (accountType == AccountType.EXCHANGE && syncMode == SyncMode.PUSH) {
                    com.dedovmosol.iwomail.sync.PushService.start(context)
                }
                
                EasResult.Success(accountId)
            }
            is EasResult.Error -> EasResult.Error(connectionResult.message)
        }
    }
    
    suspend fun updateAccount(account: AccountEntity, password: String? = null): EasResult<Unit> {
        // Получаем старый аккаунт для очистки кэша сертификатов
        val oldAccount = accountDao.getAccount(account.id)
            ?: return EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
        val beforeAccounts = accountDao.getAllAccountsList()
        
        // Очищаем кэш HTTP клиентов если изменились пути к сертификатам
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
        
        // Пин резервного сервера относится к прежнему адресу: другой сервер подтверждается заново.
        val alternateMoved = com.dedovmosol.iwomail.eas.ServerAddress.key(oldAccount.alternateServerUrl, oldAccount.incomingPort) !=
            com.dedovmosol.iwomail.eas.ServerAddress.key(account.alternateServerUrl, account.incomingPort)
        val toSave = if (alternateMoved) CertificatePins.withoutAlternatePin(account) else account
        // Сначала запись, потом сброс кэша и health: иначе параллельный createEasClient успевал
        // закэшировать клиент из СТАРОЙ строки, а запросы к старому серверу снова наполняли health
        // (клиент, который собирается во время сброса, в кэш уже не попадёт — EndpointClientCache).
        accountDao.update(toSave)
        password?.let { savePassword(account.id, it) }
        // Предупреждение о смене сертификата сервера, которого в настройках больше нет, снимается
        // (на оставшемся сервере несовпадение пина обнаружится заново при следующем подключении).
        val detector = com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector
        detector.getChange(account.id)?.let { change ->
            if (CertificatePins.endpointOf(toSave, change.hostname) == null) detector.clearChange(account.id)
        }
        clearEasClientCache(account.id)
        AccountServerHealthRepository.resetHealth(account.id)
        
        reconcileProfileFolderChanges(
            beforeAccounts = beforeAccounts,
            afterAccounts = accountDao.getAllAccountsList()
        )
        return EasResult.Success(Unit)
    }
    
    suspend fun deleteAccount(accountId: Long) {
        // Очищаем кэшированный клиент и выбор сервера
        easClients.forget(accountId)
        
        // Очищаем кэш PushService (heartbeat и EasClient)
        com.dedovmosol.iwomail.sync.PushService.clearAccountCache(context, accountId)
        
        // Очищаем кэш папок UI
        com.dedovmosol.iwomail.data.repository.FoldersCache.clearAccount(accountId)
        
        // Сбрасываем состояние синхронизации для этого аккаунта
        com.dedovmosol.iwomail.sync.InitialSyncController.resetAccount(accountId)
        
        // Очищаем данные мониторинга здоровья сервера
        AccountServerHealthRepository.clearAccount(accountId)
        
        // Очищаем флаг первой синхронизации
        val settingsRepo = SettingsRepository.getInstance(context)
        settingsRepo.resetInitialSyncFlag(accountId)
        settingsRepo.resetLastNotificationCheckTime(accountId)
        val beforeAccounts = accountDao.getAllAccountsList()
        
        // Собираем пути файлов ДО удаления из БД (каскад уничтожит записи)
        val localPaths = withContext(Dispatchers.IO) {
            try { attachmentDao.getLocalPathsByAccount(accountId) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
        }
        val account = accountDao.getAccount(accountId)
        val certPath = account?.certificatePath
        val clientCertPath = account?.clientCertificatePath
        val deletedProfileFolderName = account?.let {
            ProfilePathResolver.resolveProfileFolderName(beforeAccounts, it.id, it.displayName)
        }
        
        // Сначала удаляем из БД (каскадно удалятся папки, письма, вложения, контакты)
        accountDao.delete(accountId)
        deletePassword(accountId)
        deleteClientCertPassword(accountId)
        val afterAccounts = accountDao.getAllAccountsList()
        
        // Только после успешного удаления из БД — чистим файлы с диска
        withContext(Dispatchers.IO) {
            localPaths.forEach { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
            certPath?.let { p ->
                try {
                    File(p).delete()
                    com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(p)
                } catch (_: Exception) {}
            }
            clientCertPath?.let { p ->
                try {
                    File(p).delete()
                    com.dedovmosol.iwomail.network.HttpClientProvider.clearCertificateCache(p)
                } catch (_: Exception) {}
            }
            
            // Удаляем подпапку профиля из Downloads (вложения писем + календаря)
            deletedProfileFolderName?.let { ProfilePathResolver.deleteProfileFolderByName(context, it) }
        }

        reconcileProfileFolderChanges(
            beforeAccounts = beforeAccounts,
            afterAccounts = afterAccounts
        )
        
        // Если удалили активный аккаунт, активируем первый доступный
        if (accountDao.getActiveAccountSync() == null) {
            accountDao.getAllAccountsList().firstOrNull()?.let {
                accountDao.setActiveAccount(it.id)
            }
        }
        
        // Обновляем виджет (покажет NoAccountView если аккаунтов не осталось)
        com.dedovmosol.iwomail.widget.updateMailWidget(context)
    }
    
    private suspend fun testExchangeServer(
        url: String, username: String, password: String, domain: String,
        acceptAllCerts: Boolean, port: Int, useSSL: Boolean, email: String,
        certificatePath: String?, clientCertificatePath: String?,
        clientCertificatePassword: String?
    ): EasResult<Unit> {
        val client = try {
            EasClient(
                serverUrl = url, username = username, password = password,
                domain = domain, acceptAllCerts = acceptAllCerts, port = port,
                useHttps = useSSL, deviceIdSuffix = email,
                certificatePath = certificatePath,
                clientCertificatePath = clientCertificatePath,
                clientCertificatePassword = clientCertificatePassword
            )
        } catch (e: IllegalArgumentException) {
            return EasResult.Error(e.message ?: "CLIENT_CERT_PASSWORD_REQUIRED")
        }
        return when (val result = client.testConnection()) {
            is EasResult.Success -> EasResult.Success(Unit)
            is EasResult.Error -> EasResult.Error(result.message)
        }
    }

    suspend fun setActiveAccount(accountId: Long) {
        accountDao.setActiveAccount(accountId)
    }

    /**
     * Подписи аккаунта (для экрана создания письма). Обёртка над DAO для
     * presentation-слоя (Этап 3, CS-16).
     */
    suspend fun getSignaturesForAccount(accountId: Long): List<com.dedovmosol.iwomail.data.database.SignatureEntity> {
        return database.signatureDao().getSignaturesByAccountList(accountId)
    }

    suspend fun getPassword(accountId: Long): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        passwordStorage.getPassword(accountId)
    }

    private fun savePassword(accountId: Long, password: String) {
        try {
            passwordStorage.savePassword(accountId, password)
        } catch (e: SecurityException) {
            // L-7: Fail-closed mode enabled and using insecure storage
            android.util.Log.e("AccountRepo", "Cannot save password in fail-closed mode", e)
            throw e
        }
    }

    private fun deletePassword(accountId: Long) {
        passwordStorage.deletePassword(accountId)
    }

    /**
     * Сохранение пароля клиентского сертификата
     */
    private fun saveClientCertPassword(accountId: Long, password: String) {
        try {
            passwordStorage.saveClientCertPassword(accountId, password)
        } catch (e: SecurityException) {
            // L-7: Fail-closed mode enabled and using insecure storage
            android.util.Log.e("AccountRepo", "Cannot save client cert password in fail-closed mode", e)
            throw e
        }
    }

    /**
     * Получение пароля клиентского сертификата
     */
    private fun getClientCertPassword(accountId: Long): String? {
        return passwordStorage.getClientCertPassword(accountId)
    }

    /**
     * Удаление пароля клиентского сертификата (при удалении аккаунта)
     */
    private fun deleteClientCertPassword(accountId: Long) {
        passwordStorage.deleteClientCertPassword(accountId)
    }
    
    /**
     * EAS-клиент аккаунта для текущего сервера (основного или резервного) — из кэша или новый.
     * Кэш и выбор сервера — [EndpointClientCache] (один OkHttp-клиент на аккаунт, без утечек).
     */
    suspend fun createEasClient(accountId: Long): EasClient? = easClients.get(accountId)

    /** EasClient аккаунта для основного или резервного сервера: адрес и пин сертификата — свои у каждого. */
    private fun buildEasClient(account: AccountEntity, useAlternate: Boolean, password: String): EasClient? {
        val serverUrl = if (useAlternate) {
            account.alternateServerUrl?.takeIf { it.isNotBlank() } ?: return null
        } else {
            account.serverUrl
        }
        return try {
            EasClient(
                serverUrl = serverUrl,
                username = account.username,
                password = password,
                domain = account.domain,
                acceptAllCerts = account.acceptAllCerts,
                port = account.incomingPort,
                useHttps = account.useSSL,
                deviceIdSuffix = account.email,
                initialPolicyKey = account.policyKey,
                certificatePath = account.certificatePath,
                clientCertificatePath = account.clientCertificatePath,
                clientCertificatePassword = getClientCertPassword(account.id),
                pinnedCertInfo = CertificatePins.forEndpoint(account, useAlternate),
                accountId = account.id
            )
        } catch (e: IllegalArgumentException) {
            // Клиентский сертификат указан без пароля или не загружается
            android.util.Log.e("AccountRepository", "Failed to create EasClient: ${e.message}")
            null
        }
    }

    /** Dual-URL: аккаунт сейчас работает через резервный сервер. */
    override fun isUsingFallback(accountId: Long): Boolean = easClients.isUsingFallback(accountId)

    override suspend fun isCurrentEndpointReachable(accountId: Long): Boolean =
        easClients.isCurrentEndpointReachable(accountId)

    /**
     * Dual-URL: переключает аккаунт на ДРУГОЙ сервер — с основного на резервный или, если сбой
     * случился уже на резервном, обратно на основной. Вызывать только при обрыве связи
     * ([com.dedovmosol.iwomail.data.model.isConnectionLevelError]), не при ошибках авторизации.
     * Перед переключением — TCP-проба целевого сервера (без учётных данных): недоступный сервер не
     * тратит попытку синхронизации и не копит неудачные входы в AD. Подробности — [EndpointClientCache].
     */
    override suspend fun switchToOtherEndpoint(accountId: Long, fromFallback: Boolean): Boolean {
        val switched = easClients.switchToOtherEndpoint(accountId, fromFallback)
        if (switched && easClients.isUsingFallback(accountId) != fromFallback) {
            android.util.Log.i(
                "AccountRepository",
                "Dual-URL: account $accountId switched to ${if (fromFallback) "primary" else "alternate"} server"
            )
        }
        return switched
    }

    /**
     * Сброс клиента и выбора сервера (сменились адреса или учётные данные): следующий запрос пойдёт на
     * основной. Отметка инвалидации заставит PushService взять новый клиент.
     */
    fun clearEasClientCache(accountId: Long) = easClients.reset(accountId)

    /** Сменились сертификат или пин: новый клиент для ТОГО ЖЕ сервера (резервный остаётся резервным). */
    private fun refreshEasClient(accountId: Long) = easClients.refresh(accountId)

    fun getLastClientInvalidation(accountId: Long): Long = easClients.lastInvalidation(accountId)

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
        // Сначала запись, потом новый клиент: собранный в промежутке взял бы старый сертификат
        accountDao.updateCertificatePath(accountId, certificatePath)
        refreshEasClient(accountId)
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
        // Сначала запись, потом новый клиент: собранный в промежутке взял бы старый сертификат
        accountDao.updateClientCertificatePath(accountId, clientCertificatePath)
        refreshEasClient(accountId)
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
        
        if (password != null) {
            saveClientCertPassword(accountId, password)
        } else {
            deleteClientCertPassword(accountId)
        }
        // Новый клиент — после сохранения пароля (собранный раньше взял бы старый)
        refreshEasClient(accountId)
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
                
                // Сохраняем всю информацию о сертификате и гарантируем что pinning включён
                accountDao.updatePinnedCertificate(
                    accountId = accountId,
                    hash = certInfo.hash,
                    cn = certInfo.cn,
                    org = certInfo.organization,
                    validFrom = certInfo.validFrom,
                    validTo = certInfo.validTo
                )
                accountDao.updateCertificatePinningEnabled(accountId, true)
                accountDao.updateCertificatePinningFailCount(accountId, 0)
                com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.clearChange(accountId)
                refreshEasClient(accountId)
                
                EasResult.Success(certInfo.hash)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            // При отключении защиты очищаем всю информацию — у обоих серверов
            accountDao.updatePinnedCertificate(accountId, null, null, null, null, null)
            accountDao.updateAlternatePinnedCertificate(accountId, null, null, null, null, null)
            accountDao.updateCertificatePinningFailCount(accountId, 0)
            com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.clearChange(accountId)
        } else {
            accountDao.updatePinnedCertHash(accountId, hash)
        }
        // Новый клиент с новыми настройками для того же сервера
        refreshEasClient(accountId)
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
        refreshEasClient(accountId)
    }

    /**
     * Пользователь принял новый сертификат сервера (предупреждение о смене сертификата). Сохраняется в
     * пин ТОГО сервера, где он обнаружен: резервный не перезаписывает пин основного и наоборот —
     * иначе при разных сертификатах предупреждение появлялось бы при каждом переключении.
     */
    suspend fun acceptChangedCertificate(
        accountId: Long,
        change: com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.CertificateChange
    ) {
        val account = accountDao.getAccount(accountId) ?: return
        val cert = change.newCert
        when (CertificatePins.endpointOf(account, change.hostname)) {
            CertificatePins.Endpoint.ALTERNATE ->
                accountDao.updateAlternatePinnedCertificate(accountId, cert.hash, cert.cn, cert.organization, cert.validFrom, cert.validTo)
            CertificatePins.Endpoint.PRIMARY ->
                accountDao.updatePinnedCertificate(accountId, cert.hash, cert.cn, cert.organization, cert.validFrom, cert.validTo)
            // Сервера с таким адресом в настройках уже нет: пинить нечего (иначе сертификат прежнего
            // сервера стал бы пином основного), предупреждение просто снимается.
            null -> Unit
        }
        accountDao.updateCertificatePinningFailCount(accountId, 0)
        com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.clearChange(accountId)
        // Тот же сервер с новым пином: принятый на резервном сертификат сразу работает на резервном.
        refreshEasClient(accountId)
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
        // Новый клиент с новыми настройками для того же сервера
        refreshEasClient(accountId)
    }
    
    suspend fun updateDraftMode(accountId: Long, mode: String) {
        accountDao.updateDraftMode(accountId, mode)
    }

    override suspend fun hasAlternateUrl(accountId: Long): Boolean {
        return accountDao.getAccount(accountId)?.alternateServerUrl?.isNotBlank() == true
    }

    private suspend fun reconcileProfileFolderChanges(
        beforeAccounts: List<AccountEntity>,
        afterAccounts: List<AccountEntity>
    ) {
        val beforeFolders = beforeAccounts.associate { account ->
            account.id to ProfilePathResolver.resolveProfileFolderName(
                accounts = beforeAccounts,
                accountId = account.id,
                displayName = account.displayName
            )
        }
        val afterFolders = afterAccounts.associate { account ->
            account.id to ProfilePathResolver.resolveProfileFolderName(
                accounts = afterAccounts,
                accountId = account.id,
                displayName = account.displayName
            )
        }

        val renames = buildList {
            afterFolders.forEach { (accountId, newFolderName) ->
                val oldFolderName = beforeFolders[accountId] ?: return@forEach
                if (oldFolderName != newFolderName) {
                    add(Triple(accountId, oldFolderName, newFolderName))
                }
            }
        }
        if (renames.isEmpty()) return

        withContext(Dispatchers.IO) {
            val oldNames = renames.map { it.second }.toSet()
            val newNames = renames.map { it.third }.toSet()
            val hasConflict = oldNames.intersect(newNames).isNotEmpty()

            if (!hasConflict) {
                renames.forEach { (_, old, new) ->
                    ProfilePathResolver.renameProfileFolder(context, old, new)
                }
            } else {
                val temps = renames.associate { (id, _, _) ->
                    id to ".reconcile_tmp_$id"
                }
                renames.forEach { (id, old, _) ->
                    ProfilePathResolver.renameProfileFolder(context, old, temps.getValue(id))
                }
                renames.forEach { (id, _, new) ->
                    ProfilePathResolver.renameProfileFolder(context, temps.getValue(id), new)
                }
            }
        }
    }
}
