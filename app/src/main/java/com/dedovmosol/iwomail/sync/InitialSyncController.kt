package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import kotlinx.coroutines.*

/**
 * Orchestrates initial and manual sync on app launch.
 *
 * Reactive state ([isSyncing], [syncDone], [noNetwork]) is exposed via
 * Compose [mutableStateOf] so UI recomposes automatically.
 *
 * Thread-safe: all mutable collections are concurrent.
 */
object InitialSyncController {
    private val syncingAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val syncedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val syncJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    @Volatile private var syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val syncAttempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private const val MAX_SYNC_ATTEMPTS = 3

    var isSyncing by mutableStateOf(false)
        private set
    var syncDone by mutableStateOf(false)
        private set
    var noNetwork by mutableStateOf(false)
        private set

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isSyncingAccount(accountId: Long): Boolean = accountId in syncingAccounts
    fun isSyncedAccount(accountId: Long): Boolean = accountId in syncedAccounts

    private fun updateState() {
        isSyncing = syncingAccounts.isNotEmpty()
        syncDone = syncedAccounts.isNotEmpty()
    }

    fun startSyncIfNeeded(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) { @Suppress("NAME_SHADOWING") val context = context.applicationContext
        if (accountId in syncedAccounts) return
        if (accountId in syncingAccounts) return
        if (syncJobs[accountId]?.isActive == true) return

        val attempts = syncAttempts.getOrDefault(accountId, 0)
        if (attempts >= MAX_SYNC_ATTEMPTS) {
            syncedAccounts.add(accountId)
            updateState()
            return
        }

        if (!isNetworkAvailable(context)) {
            noNetwork = true
            return
        }
        noNetwork = false

        syncingAccounts.add(accountId)
        syncAttempts[accountId] = attempts + 1
        updateState()

        syncJobs.entries.removeIf { !it.value.isActive }

        if (!syncScope.isActive) {
            syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }

        syncJobs[accountId] = syncScope.launch {
            try {
                val isFirstSync = withContext(Dispatchers.IO) {
                    !settingsRepo.isInitialSyncCompleted(accountId)
                }

                val dbWasRecreated = com.dedovmosol.iwomail.data.database.MailDatabase.wasDestructivelyMigrated
                if (dbWasRecreated) {
                    com.dedovmosol.iwomail.data.database.MailDatabase.clearDestructiveMigrationFlag()
                }

                val appUpdated = withContext(Dispatchers.IO) {
                    try {
                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        val currentInstallTime = packageInfo.lastUpdateTime
                        val storedInstallTime = settingsRepo.getLastInstallTime()
                        val wasReinstalled = storedInstallTime != 0L && storedInstallTime != currentInstallTime
                        if (storedInstallTime != currentInstallTime) {
                            settingsRepo.setLastInstallTime(currentInstallTime)
                        }
                        wasReinstalled
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        false
                    }
                }

                if (isFirstSync || dbWasRecreated || appUpdated) {
                    delay(100)
                    performFullSync(context, accountId, mailRepo, settingsRepo)
                } else {
                    syncedAccounts.add(accountId)
                    updateState()
                    performBackgroundSync(context, accountId, mailRepo, settingsRepo)
                }
            } catch (e: CancellationException) {
                updateState()
                throw e
            } catch (_: Exception) {
                syncedAccounts.add(accountId)
                updateState()
            } finally {
                syncingAccounts.remove(accountId)
                syncJobs.remove(accountId)
                updateState()
            }
        }
    }

    private suspend fun performFullSync(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        val syncResult = withTimeoutOrNull(900_000L) {
                    val folderSyncResult = withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    if (folderSyncResult is EasResult.Error) {
                        delay(3_000)
                        withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    }

                    delay(200)

                    val emailFolderTypes = listOf(
                        1, FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                        FolderType.SENT_ITEMS, FolderType.OUTBOX, FolderType.JUNK_EMAIL,
                        FolderType.USER_CREATED
                    )
                    val currentFolders = withContext(Dispatchers.IO) {
                        com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            .folderDao().getFoldersByAccountList(accountId)
                    }
                    val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }

                    val syncSemaphore = kotlinx.coroutines.sync.Semaphore(2)
                    val failedFolderIds = java.util.concurrent.ConcurrentLinkedQueue<String>()

                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            foldersToSync.map { folder ->
                                launch {
                                    syncSemaphore.acquire()
                                    try {
                                        val ok = syncFolderWithRetry(
                                            mailRepo, accountId, folder.id,
                                            incrementalTimeoutMs = 120_000L,
                                            fullResyncTimeoutMs = 480_000L,
                                            tag = "InitialSync"
                                        )
                                        if (!ok) failedFolderIds.add(folder.id)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        failedFolderIds.add(folder.id)
                                    } finally {
                                        syncSemaphore.release()
                                    }
                                }
                            }
                        }
                    }

                    if (failedFolderIds.isNotEmpty()) {
                        delay(5_000)
                        withContext(Dispatchers.IO) {
                            supervisorScope {
                                failedFolderIds.map { folderId ->
                                    launch {
                                        syncSemaphore.acquire()
                                        try {
                                            syncFolderWithRetry(
                                                mailRepo, accountId, folderId,
                                                incrementalTimeoutMs = 120_000L,
                                                fullResyncTimeoutMs = 480_000L,
                                                tag = "InitialSync-Retry"
                                            )
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                        } finally {
                                            syncSemaphore.release()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.IO) {
                        try {
                            mailRepo.prefetchEmailBodies(accountId, 7)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                    }

                    syncNonEmailData(context, accountId, includeGal = true)

                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    settingsRepo.setInitialSyncCompleted(accountId, true)
                    com.dedovmosol.iwomail.widget.updateMailWidget(context)

                    true
                }

                if (syncResult == true) {
                    syncedAccounts.add(accountId)
                    syncAttempts.remove(accountId)
                    updateState()
                }
    }

    private suspend fun performBackgroundSync(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        try {
            withTimeoutOrNull(420_000L) {
                withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }

                val mainFolderTypes = listOf(
                    FolderType.INBOX,
                    FolderType.SENT_ITEMS,
                    FolderType.DRAFTS
                )

                val currentFolders = withContext(Dispatchers.IO) {
                    com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                        .folderDao().getFoldersByAccountList(accountId)
                }
                val foldersToSync = currentFolders.filter { it.type in mainFolderTypes }

                withContext(Dispatchers.IO) {
                    supervisorScope {
                        foldersToSync.forEach { folder ->
                            launch {
                                try {
                                    withTimeoutOrNull(180_000L) {
                                        mailRepo.syncEmails(accountId, folder.id, forceFullSync = false)
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                }
                            }
                        }
                    }
                }

                syncNonEmailData(context, accountId, includeGal = false)

                settingsRepo.setLastSyncTime(System.currentTimeMillis())
                com.dedovmosol.iwomail.widget.updateMailWidget(context)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    fun reset() {
        syncScope.cancel()
        syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        syncJobs.clear()
        syncedAccounts.clear()
        syncingAccounts.clear()
        syncAttempts.clear()
        noNetwork = false
        updateState()
    }

    fun resetAccount(accountId: Long) {
        syncedAccounts.remove(accountId)
        syncAttempts.remove(accountId)
        syncJobs[accountId]?.cancel()
        syncJobs.remove(accountId)
        syncingAccounts.remove(accountId)
        noNetwork = false
        updateState()
    }

    private suspend fun syncNonEmailData(context: Context, accountId: Long, includeGal: Boolean = true) {
        withContext(Dispatchers.IO) {
            supervisorScope {
                launch {
                    try {
                        withTimeoutOrNull(120_000L) {
                            val contactRepo = com.dedovmosol.iwomail.data.repository.ContactRepository(context)
                            contactRepo.syncExchangeContacts(accountId)
                            if (includeGal) contactRepo.syncGalContactsToDb(accountId)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
                launch {
                    try {
                        withTimeoutOrNull(60_000L) {
                            val noteRepo = com.dedovmosol.iwomail.data.repository.NoteRepository(context)
                            noteRepo.syncNotes(accountId)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
                launch {
                    try {
                        withTimeoutOrNull(60_000L) {
                            val calendarRepo = com.dedovmosol.iwomail.data.repository.CalendarRepository(context)
                            calendarRepo.syncCalendar(accountId)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
                launch {
                    try {
                        withTimeoutOrNull(60_000L) {
                            val taskRepo = com.dedovmosol.iwomail.data.repository.TaskRepository(context)
                            taskRepo.syncTasks(accountId)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
            }
        }
    }

    fun manualSync(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository,
        onComplete: () -> Unit = {}
    ) {
        if (!isNetworkAvailable(context)) {
            noNetwork = true
            onComplete()
            return
        }
        noNetwork = false

        if (accountId in syncingAccounts || syncJobs[accountId]?.isActive == true) {
            onComplete()
            return
        }

        syncingAccounts.add(accountId)
        updateState()

        syncJobs[accountId] = syncScope.launch {
            try {
                withTimeoutOrNull(600_000L) {
                    val result = withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }

                    if (result is EasResult.Error) {
                        return@withTimeoutOrNull
                    }

                    val emailFolderTypes = listOf(
                        1, FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                        FolderType.SENT_ITEMS, FolderType.OUTBOX, FolderType.JUNK_EMAIL,
                        FolderType.USER_CREATED
                    )
                    val currentFolders = withContext(Dispatchers.IO) {
                        com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            .folderDao().getFoldersByAccountList(accountId)
                    }
                    val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }

                    val account = withContext(Dispatchers.IO) {
                        com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            .accountDao().getAccount(accountId)
                    }
                    val isExchange = account?.accountType == com.dedovmosol.iwomail.data.database.AccountType.EXCHANGE.name

                    val syncSemaphore = kotlinx.coroutines.sync.Semaphore(2)
                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            foldersToSync.map { folder ->
                                launch {
                                    syncSemaphore.acquire()
                                    try {
                                        val forceFullSync = isExchange && folder.type == FolderType.SENT_ITEMS
                                        val timeout = if (forceFullSync) 600_000L else 120_000L
                                        withTimeoutOrNull(timeout) {
                                            mailRepo.syncEmails(accountId, folder.id, forceFullSync = forceFullSync)
                                        }
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                    } finally {
                                        syncSemaphore.release()
                                    }
                                }
                            }.forEach { it.join() }
                        }
                    }

                    syncNonEmailData(context, accountId, includeGal = true)
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    com.dedovmosol.iwomail.widget.updateMailWidget(context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                syncingAccounts.remove(accountId)
                syncJobs.remove(accountId)
                updateState()
                onComplete()
            }
        }
    }
}
