package com.dedovmosol.iwomail.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.NotificationStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Worker для фоновой синхронизации почты
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val mailRepo = MailRepository(applicationContext)
    private val settingsRepo = SettingsRepository.getInstance(applicationContext)
    private val database = MailDatabase.getInstance(applicationContext)
    
    private data class NewEmailInfo(
        val id: String,
        val senderName: String?,
        val senderEmail: String?,
        val subject: String?,
        val dateReceived: Long = 0L
    )
    
    /**
     * Получить список показанных уведомлений
     */
    private fun getShownNotifications(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("push_notifications", Context.MODE_PRIVATE)
        return prefs.getStringSet("shown_notifications", emptySet()) ?: emptySet()
    }
    
    /**
     * Пометить уведомления как показанные
     */
    private fun markNotificationsAsShown(context: Context, notificationKeys: List<String>) {
        val prefs = context.getSharedPreferences("push_notifications", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("shown_notifications", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.addAll(notificationKeys)
        
        // Очищаем старые (сохраняем только последние 500)
        if (current.size > 500) {
            val toKeep = current.toList().takeLast(500).toSet()
            prefs.edit().putStringSet("shown_notifications", toKeep).apply()
        } else {
            prefs.edit().putStringSet("shown_notifications", current).apply()
        }
    }
    
    override suspend fun doWork(): Result {
        return try {
            doWorkInternal()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("SyncWorker", "CRITICAL ERROR in doWork", e)
            cancelSyncNotification()
            Result.failure()
        }
    }
    
    private suspend fun doWorkInternal(): Result {
        val isManualSync = inputData.getBoolean("manual_sync", false)
        
        // Foreground только для ручной синхронизации — автоматическая работает тихо в фоне
        // (экономия батареи: foreground повышает приоритет CPU)
        if (isManualSync) {
            try {
                setForeground(createForegroundInfo())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Игнорируем ошибку - продолжаем работу в фоне
            }
        }
        
        // Debounce: пропускаем если синхронизация была менее 30 сек назад (только для автоматической)
        if (!isManualSync) {
            val lastSync = settingsRepo.getLastSyncTimeSync()
            if (System.currentTimeMillis() - lastSync < 30_000) {
                cancelSyncNotification()
                return Result.success()
            }
        }
        
        val accounts = database.accountDao().getAllAccountsList()
        if (accounts.isEmpty()) return Result.success()
        
        // КРИТИЧНО: Проверяем и запускаем PushService если нужно
        // Это дополнительная защита на случай если сервис был остановлен системой
        checkAndStartPushService(accounts)
        
        var lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
        // При первом запуске (lastNotificationCheck = 0) не показываем уведомления для старых писем
        val isFirstRun = lastNotificationCheck == 0L
        if (isFirstRun) {
            lastNotificationCheck = System.currentTimeMillis() - 60_000 // Только письма за последнюю минуту
        }
        
        // Собираем новые письма по аккаунтам
        val newEmailsByAccount = mutableMapOf<Long, MutableList<NewEmailInfo>>()
        var hasErrors = false
        
        // Общий бюджет времени на email sync всех аккаунтов.
        // Автоматический sync: 480с (оставляем ~2 мин на контакты/календарь/задачи/cleanup из 10 мин WorkManager).
        // Ручной sync (foreground): 900с — нет жёсткого лимита WorkManager, но разумный предел.
        val totalEmailSyncBudgetMs = if (isManualSync) 900_000L else 480_000L
        val emailSyncStartTime = System.currentTimeMillis()
        
        val activeAccounts = accounts.filter {
            !com.dedovmosol.iwomail.ui.InitialSyncController.isSyncingAccount(it.id)
        }
        // Бюджет делится поровну между активными аккаунтами (минимум 60с на аккаунт)
        val perAccountBudgetMs = if (activeAccounts.isNotEmpty()) {
            maxOf(60_000L, totalEmailSyncBudgetMs / activeAccounts.size)
        } else {
            totalEmailSyncBudgetMs
        }
        
        for (account in activeAccounts) {
            val accountStartTime = System.currentTimeMillis()
            
            // Проверяем общий бюджет
            if (accountStartTime - emailSyncStartTime > totalEmailSyncBudgetMs) {
                android.util.Log.w("SyncWorker", "Email sync budget exhausted before account ${account.id}, skipping remaining accounts")
                hasErrors = true
                break
            }
            
            // Синхронизируем папки (игнорируем ошибки - пробуем синхронизировать письма в любом случае)
            when (mailRepo.syncFolders(account.id)) {
                is EasResult.Error -> hasErrors = true
                is EasResult.Success -> { }
            }
            
            val allFolders = database.folderDao().getFoldersByAccountList(account.id)
            // КРИТИЧНО: Синхронизируем ВСЕ пользовательские папки (type 1, USER_CREATED=12).
            // Без этого письма, перемещённые в пользовательские папки через MoveItems,
            // не будут видны пользователю.
            //
            // Стратегия: ВСЕ папки получают быстрый инкрементальный sync (~1-3 сек/папка).
            // Тяжёлый full resync (до 280с/папка) — только для первых N несинхронизированных.
            // Остальные несинхронизированные подтянутся в следующих циклах (каждые 15 мин).
            val systemFolders = allFolders.filter { it.type in FolderType.SYNC_MAIN_TYPES }
            val userFolders = allFolders.filter { it.type in FolderType.SYNC_USER_TYPES }
            val foldersToSync = (systemFolders + userFolders)
                .sortedBy { if (it.type == FolderType.INBOX) 0 else 1 }
            
            // Считаем сколько папок ещё не синхронизированы (syncKey == "0") — им нужен full resync
            val unsyncedUserFolderIds = userFolders
                .filter { it.syncKey == "0" }
                .take(FolderType.MAX_FULL_RESYNC_USER_FOLDERS)
                .map { it.id }
                .toSet()
            
            for (folder in foldersToSync) {
                val now = System.currentTimeMillis()
                // Проверяем per-account бюджет И общий бюджет
                if (now - accountStartTime > perAccountBudgetMs) {
                    android.util.Log.w("SyncWorker", "Per-account budget exhausted (${perAccountBudgetMs/1000}s) for account ${account.id}, " +
                        "remaining folders will sync in next cycle")
                    hasErrors = true
                    break
                }
                if (now - emailSyncStartTime > totalEmailSyncBudgetMs) {
                    android.util.Log.w("SyncWorker", "Total email sync budget exhausted (${totalEmailSyncBudgetMs/1000}s), " +
                        "remaining will sync in next cycle")
                    hasErrors = true
                    break
                }
                
                // Системные папки всегда получают full resync при ошибке
                // Пользовательские: full resync только если в лимите или уже синхронизированы
                val allowFull = folder.type in FolderType.SYNC_MAIN_TYPES
                    || folder.syncKey != "0"
                    || folder.id in unsyncedUserFolderIds
                val success = syncFolderWithRetry(
                    mailRepo, account.id, folder.id,
                    allowFullResync = allowFull, tag = "SyncWorker"
                )
                if (!success) hasErrors = true
            }
            
            // КРИТИЧНО: getNewEmailsForNotification не зависит от статуса прочитанности
            val newEmailEntities = database.emailDao().getNewEmailsForNotification(account.id, lastNotificationCheck)
            
            // Фильтруем уже показанные уведомления
            val shownNotifications = getShownNotifications(applicationContext)
            val filteredEmails = newEmailEntities.filter { email ->
                val notifKey = "${account.id}_${email.id}"
                !shownNotifications.contains(notifKey)
            }
            
            if (filteredEmails.isNotEmpty()) {
                val accountEmails = newEmailsByAccount.getOrPut(account.id) { mutableListOf() }
                for (email in filteredEmails) {
                    accountEmails.add(NewEmailInfo(email.id, email.fromName, email.from, email.subject, email.dateReceived))
                }
            }
            
        }
        
        settingsRepo.setLastSyncTime(System.currentTimeMillis())
        
        // Показываем уведомления для каждого аккаунта отдельно
        if (newEmailsByAccount.isNotEmpty()) {
            // КРИТИЧНО: Предзагружаем тело писем ДО показа уведомлений
            // Это позволяет пользователю сразу видеть текст при переходе по уведомлению
            for ((accountId, emails) in newEmailsByAccount) {
                for (emailInfo in emails) {
                    try {
                        // Загружаем тело только если оно пустое
                        val email = database.emailDao().getEmail(emailInfo.id)
                        if (email != null && email.body.isEmpty()) {
                            mailRepo.loadEmailBody(emailInfo.id)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("SyncWorker", "Failed to preload body for ${emailInfo.id}", e)
                        // Продолжаем с другими письмами
                    }
                }
            }
            
            val notificationsEnabled = settingsRepo.notificationsEnabled.first()
            if (notificationsEnabled) {
                // Shared Mutex с PushService: атомарный re-filter+show+mark
                // предотвращает дубликаты при одновременном sync
                PushService.notificationMutex.withLock {
                    for ((accountId, emails) in newEmailsByAccount) {
                        // Re-filter: PushService мог показать уведомления пока мы загружали тела
                        val shownNow = getShownNotifications(applicationContext)
                        val filtered = emails.filter { e ->
                            !shownNow.contains("${accountId}_${e.id}")
                        }
                        if (filtered.isEmpty()) continue
                        val account = accounts.find { it.id == accountId } ?: continue
                        showNotification(filtered, account.email, accountId)
                        markNotificationsAsShown(applicationContext, filtered.map { "${accountId}_${it.id}" })
                    }
                }
            }
        }
        
        settingsRepo.setLastNotificationCheckTime(System.currentTimeMillis())
        
        // Автоочистка папок (фиксируем успех только при полном успешном проходе)
        val cleanupSucceeded = performAutoTrashCleanup(accounts)
        if (!cleanupSucceeded) {
            hasErrors = true
        }
        
        // DRY: передаём уже загруженный список аккаунтов вместо повторных DB-запросов
        // Синхронизация контактов GAL (для Exchange аккаунтов)
        syncGalContacts(accounts)
        
        // Синхронизация заметок (для Exchange аккаунтов)
        syncNotes(accounts)
        
        // Синхронизация календаря (для Exchange аккаунтов)
        syncCalendar(accounts)
        
        // Синхронизация задач (для Exchange аккаунтов)
        syncTasks(accounts)
        
        // Обновляем виджет
        com.dedovmosol.iwomail.widget.updateMailWidget(applicationContext)
        
        // Показываем уведомление о завершении при ручной синхронизации
        if (isManualSync) {
            showSyncCompleteNotification()
        }
        
        // Перепланируем с учётом ночного режима (интервал может измениться)
        scheduleWithNightMode(applicationContext)
        
        // Явно убираем уведомление о синхронизации
        cancelSyncNotification()
        
        return Result.success()
    }
    
    /**
     * Проверяет нужен ли PushService и запускает его если он не работает
     */
    private fun checkAndStartPushService(accounts: List<com.dedovmosol.iwomail.data.database.AccountEntity>) {
        try {
            val needsPushService = accounts.any { 
                it.accountType == AccountType.EXCHANGE.name &&
                it.syncMode == SyncMode.PUSH.name
            }
            
            if (needsPushService) {
                // Проверяем статус через SharedPreferences
                val prefs = applicationContext.getSharedPreferences("push_service", android.content.Context.MODE_PRIVATE)
                val lastUpdate = prefs.getLong("last_update", 0)
                val serviceRunning = (System.currentTimeMillis() - lastUpdate) < 420_000 // heartbeat каждые 5 мин + запас
                
                if (!serviceRunning) {
                    android.util.Log.i("SyncWorker", "PushService not running - starting it")
                    PushService.start(applicationContext)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SyncWorker", "Failed to check PushService status", e)
        }
    }
    
    /**
     * Явно удаляет foreground уведомление о синхронизации
     */
    private fun cancelSyncNotification() {
        try {
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.cancel(SYNC_NOTIFICATION_ID)
        } catch (_: Exception) { }
    }
    
    /**
     * Создаёт ForegroundInfo для отображения уведомления о синхронизации
     */
    private fun createForegroundInfo(): ForegroundInfo {
        val isRussian = settingsRepo.getLanguageSync() == "ru"
        
        val notification = NotificationCompat.Builder(applicationContext, MailApplication.CHANNEL_SYNC_STATUS)
            .setSmallIcon(com.dedovmosol.iwomail.R.drawable.ic_sync)
            .setContentTitle(if (isRussian) "Синхронизация" else "Syncing")
            .setContentText(if (isRussian) "Синхронизация почты..." else "Syncing mail...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SYNC_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SYNC_NOTIFICATION_ID, notification)
        }
    }
    
    private fun showSyncCompleteNotification() {
        val isRussian = settingsRepo.getLanguageSync() == "ru"
        
        val notification = NotificationCompat.Builder(applicationContext, MailApplication.CHANNEL_SYNC_STATUS)
            .setSmallIcon(com.dedovmosol.iwomail.R.drawable.ic_sync)
            .setContentTitle(if (isRussian) "Синхронизация завершена" else "Sync complete")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.notify(SYNC_COMPLETE_NOTIFICATION_ID, notification)
    }
    
    private suspend fun showNotification(newEmails: List<NewEmailInfo>, accountEmail: String, accountId: Long) {
        val count = newEmails.size
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        
        val languageCode = settingsRepo.language.first()
        val isRussian = languageCode == "ru"
        
        val latestEmail = newEmails.maxByOrNull { it.dateReceived }
        val senderName = latestEmail?.senderName?.takeIf { it.isNotBlank() } 
            ?: latestEmail?.senderEmail?.substringBefore("@")
        val subject = latestEmail?.subject?.takeIf { it.isNotBlank() }
        
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, accountId)
            if (count == 1 && latestEmail != null) {
                putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, latestEmail.id)
            } else {
                putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
            }
        }
        
        // КРИТИЧНО: Используем уникальный requestCode для каждого уведомления
        // Иначе на заблокированном экране extras теряются из-за FLAG_UPDATE_CURRENT
        val uniqueRequestCode = if (count == 1 && latestEmail != null) {
            latestEmail.id.hashCode()
        } else {
            accountId.toInt() + 10000
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, uniqueRequestCode, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        
        val builder = NotificationCompat.Builder(applicationContext, MailApplication.CHANNEL_NEW_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getNewMailTitle(count, senderName, isRussian))
            .setContentText(NotificationStrings.getNewMailText(count, subject, isRussian))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSubText(accountEmail)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
        
        if (count > 1) {
            val senders = newEmails.mapNotNull { email ->
                email.senderName?.takeIf { it.isNotBlank() } 
                    ?: email.senderEmail?.substringBefore("@")
            }
            if (senders.isNotEmpty()) {
                builder.setStyle(NotificationCompat.BigTextStyle()
                    .bigText(NotificationStrings.getNewMailBigText(senders, isRussian)))
            }
        } else if (count == 1 && !subject.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(subject))
        }
        
        // Кнопка «Прочитано» — помечает письма как прочитанные на сервере
        val notificationId = 3000 + accountId.toInt()
        val markReadIntent = Intent(applicationContext, MailNotificationActionReceiver::class.java).apply {
            action = MailNotificationActionReceiver.ACTION_MARK_READ
            putExtra(MailNotificationActionReceiver.EXTRA_ACCOUNT_ID, accountId)
            putExtra(MailNotificationActionReceiver.EXTRA_EMAIL_IDS, newEmails.map { it.id }.toTypedArray())
            putExtra(MailNotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            MailNotificationActionReceiver.requestCodeForAccount(accountId),
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(com.dedovmosol.iwomail.R.drawable.ic_check, applicationContext.getString(com.dedovmosol.iwomail.R.string.notification_mark_read), markReadPendingIntent)
        
        // Уникальный ID для каждого аккаунта — уведомления не перезаписывают друг друга
        notificationManager.notify(notificationId, builder.build())
        
        // Воспроизводим звук получения письма
        com.dedovmosol.iwomail.util.SoundPlayer.playReceiveSound(applicationContext)
    }
    
    /**
     * Автоматическая очистка папок по ИНТЕРВАЛУ:
     * значение N в настройках означает "чистить раз в N дней".
     *
     * Поведение:
     * - Корзина/спам: полная очистка папки при наступлении интервала.
     * - Локальные черновики: полная очистка при наступлении интервала.
     */
    private suspend fun performAutoTrashCleanup(accounts: List<com.dedovmosol.iwomail.data.database.AccountEntity>): Boolean {
        return try {
            var hasCleanupErrors = false
            val now = System.currentTimeMillis()

            for (account in accounts) {
                // Очистка корзины: раз в N дней, начисто
                if (account.autoCleanupTrashDays > 0) {
                    val intervalMs = account.autoCleanupTrashDays * ONE_DAY_MS
                    val lastRun = settingsRepo.getLastAutoCleanupTrashTime(account.id)
                    if (now - lastRun >= intervalMs) {
                        val trashFolders = database.folderDao().getFoldersByAccountList(account.id)
                            .filter { it.type == FolderType.DELETED_ITEMS }

                        var folderFailed = false
                        for (trashFolder in trashFolders) {
                            val allEmails = database.emailDao().getEmailsByFolderList(trashFolder.id)
                            if (allEmails.isNotEmpty()) {
                                val emailIds = allEmails.map { it.id }
                                val result = mailRepo.deleteEmailsPermanently(emailIds)
                                if (result is EasResult.Error) {
                                    folderFailed = true
                                    hasCleanupErrors = true
                                    android.util.Log.w("SyncWorker", "Auto cleanup trash failed for account ${account.id}: ${result.message}")
                                }
                            }
                        }

                        if (!folderFailed) {
                            settingsRepo.setLastAutoCleanupTrashTime(account.id, now)
                        }
                    }
                }

                // Очистка локальных черновиков: раз в N дней, начисто
                if (account.autoCleanupDraftsDays > 0) {
                    val intervalMs = account.autoCleanupDraftsDays * ONE_DAY_MS
                    val lastRun = settingsRepo.getLastAutoCleanupDraftsTime(account.id)
                    if (now - lastRun >= intervalMs) {
                        val localDrafts = database.emailDao().getLocalDraftEmails(account.id)
                        var draftsFailed = false
                        for (draft in localDrafts) {
                            try {
                                // Удаляем вложения
                                database.attachmentDao().deleteByEmail(draft.id)
                                // Удаляем черновик
                                database.emailDao().delete(draft.id)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                draftsFailed = true
                                hasCleanupErrors = true
                                android.util.Log.w("SyncWorker", "Auto cleanup local draft failed for ${draft.id}", e)
                            }
                        }
                        if (!draftsFailed) {
                            settingsRepo.setLastAutoCleanupDraftsTime(account.id, now)
                        }
                    }
                }

                // Очистка спама: раз в N дней, начисто
                if (account.autoCleanupSpamDays > 0) {
                    val intervalMs = account.autoCleanupSpamDays * ONE_DAY_MS
                    val lastRun = settingsRepo.getLastAutoCleanupSpamTime(account.id)
                    if (now - lastRun >= intervalMs) {
                        val spamFolders = database.folderDao().getFoldersByAccountList(account.id)
                            .filter { it.type == FolderType.JUNK_EMAIL }

                        var spamFailed = false
                        for (spamFolder in spamFolders) {
                            val allEmails = database.emailDao().getEmailsByFolderList(spamFolder.id)
                            if (allEmails.isNotEmpty()) {
                                val emailIds = allEmails.map { it.id }
                                val result = mailRepo.deleteEmailsPermanently(emailIds)
                                if (result is EasResult.Error) {
                                    spamFailed = true
                                    hasCleanupErrors = true
                                    android.util.Log.w("SyncWorker", "Auto cleanup spam failed for account ${account.id}: ${result.message}")
                                }
                            }
                        }

                        if (!spamFailed) {
                            settingsRepo.setLastAutoCleanupSpamTime(account.id, now)
                        }
                    }
                }
            }

            if (hasCleanupErrors) {
                false
            } else {
                // Глобальный маркер поддерживаем для обратной совместимости/мониторинга
                settingsRepo.setLastTrashCleanupTime(System.currentTimeMillis())
                true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("SyncWorker", "Auto cleanup failed with exception", e)
            false
        }
    }
    
    /**
     * Синхронизация контактов из GAL для Exchange аккаунтов
     * Загружает контакты и сохраняет в локальную БД
     */
    private suspend fun syncGalContacts(accounts: List<com.dedovmosol.iwomail.data.database.AccountEntity>) {
        val contactRepo = com.dedovmosol.iwomail.data.repository.ContactRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации контактов (0 = отключено)
            if (account.contactsSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastContactsSyncTimeSync(account.id)
            val intervalMs = account.contactsSyncIntervalDays * ONE_DAY_MS
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            try {
                contactRepo.syncExchangeContacts(account.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            try {
                val result = contactRepo.syncGalContactsToDb(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastContactsSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }
    
    /**
     * Синхронизация заметок для Exchange аккаунтов
     */
    private suspend fun syncNotes(accounts: List<com.dedovmosol.iwomail.data.database.AccountEntity>) {
        val noteRepo = com.dedovmosol.iwomail.data.repository.NoteRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации заметок (0 = отключено)
            if (account.notesSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastNotesSyncTimeSync(account.id)
            val intervalMs = account.notesSyncIntervalDays * ONE_DAY_MS
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем заметки
            try {
                val result = noteRepo.syncNotes(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastNotesSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Игнорируем ошибки синхронизации заметок
            }
        }
    }
    
    /**
     * Синхронизация календаря для Exchange аккаунтов
     */
    private suspend fun syncCalendar(accounts: List<com.dedovmosol.iwomail.data.database.AccountEntity>) {
        val calendarRepo = com.dedovmosol.iwomail.data.repository.CalendarRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации календаря (0 = отключено)
            if (account.calendarSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastCalendarSyncTimeSync(account.id)
            val intervalMs = account.calendarSyncIntervalDays * ONE_DAY_MS
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем календарь
            try {
                val result = calendarRepo.syncCalendar(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastCalendarSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Игнорируем ошибки синхронизации календаря
            }
        }
    }
    
    /**
     * Синхронизация задач для Exchange аккаунтов
     */
    private suspend fun syncTasks(accounts: List<com.dedovmosol.iwomail.data.database.AccountEntity>) {
        val taskRepo = com.dedovmosol.iwomail.data.repository.TaskRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации задач (0 = отключено)
            if (account.tasksSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastTasksSyncTimeSync(account.id)
            val intervalMs = account.tasksSyncIntervalDays * ONE_DAY_MS
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем задачи
            try {
                val result = taskRepo.syncTasks(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastTasksSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Игнорируем ошибки синхронизации задач
            }
        }
    }
    
    companion object {
        private const val WORK_NAME = "mail_sync"
        private const val NIGHT_MODE_INTERVAL = 60L // 60 минут ночью
        private const val SYNC_COMPLETE_NOTIFICATION_ID = 9999
        private const val SYNC_NOTIFICATION_ID = 9998 // ID для foreground уведомления
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L // 24 часа в миллисекундах
        
        fun schedule(context: Context, intervalMinutes: Long = 15, wifiOnly: Boolean = false) {
            if (intervalMinutes <= 0) {
                cancel(context)
                return
            }
            
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
        
        /**
         * Планирует синхронизацию с учётом ночного режима и настроек аккаунтов
         */
        /**
         * Получает минимальный интервал синхронизации из всех аккаунтов с режимом SCHEDULED
         */
        suspend fun getMinSyncInterval(context: Context): Int {
            val database = MailDatabase.getInstance(context)
            
            // Получаем все аккаунты
            val accounts = database.accountDao().getAllAccountsList()
            
            // Фильтруем аккаунты которым нужна периодическая синхронизация
            val scheduledAccounts = accounts.filter { account ->
                val isExchange = account.accountType == AccountType.EXCHANGE.name
                val isPush = account.syncMode == SyncMode.PUSH.name
                !isExchange || !isPush // Не Exchange, или Exchange но не Push
            }
            
            // Берём минимальный интервал или 15 по умолчанию
            return scheduledAccounts.minOfOrNull { it.syncIntervalMinutes } ?: 15
        }

        /**
         * Минимальный интервал синхронизации среди ВСЕХ аккаунтов (включая PUSH).
         * Используется для fallback-синхронизации через AlarmManager.
         */
        suspend fun getMinSyncIntervalIncludingPush(context: Context): Int {
            val database = MailDatabase.getInstance(context)

            val accounts = database.accountDao().getAllAccountsList()

            val intervals = accounts.mapNotNull { account ->
                account.syncIntervalMinutes.takeIf { it > 0 }
            }

            return intervals.minOrNull() ?: 15
        }
        
        /**
         * Проверяет, является ли текущее время ночным (23:00-7:00)
         */
        private fun isNightTime(): Boolean {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return hour >= 23 || hour < 7
        }
        
        /**
         * Проверяет, активен ли режим экономии батареи Android
         */
        private fun isBatterySaverActive(context: Context): Boolean {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            return powerManager?.isPowerSaveMode == true
        }
        
        suspend fun scheduleWithNightMode(context: Context) {
            val settingsRepo = SettingsRepository.getInstance(context)
            val database = MailDatabase.getInstance(context)
            
            // Получаем все аккаунты
            val accounts = database.accountDao().getAllAccountsList()
            
            // Фильтруем аккаунты которым нужна периодическая синхронизация
            // (не Exchange с Push, или Exchange с SCHEDULED)
            val scheduledAccounts = accounts.filter { account ->
                val isExchange = account.accountType == AccountType.EXCHANGE.name
                val isPush = account.syncMode == SyncMode.PUSH.name
                !isExchange || !isPush // Не Exchange, или Exchange но не Push
            }
            
            if (scheduledAccounts.isEmpty()) {
                // Все аккаунты используют Push — отменяем периодическую синхронизацию
                cancel(context)
                return
            }
            
            val isNight = isNightTime()
            val batterySaverActive = isBatterySaverActive(context)
            
            // Вычисляем эффективный интервал для каждого аккаунта с учётом его per-account настроек
            val effectiveIntervals = scheduledAccounts.map { account ->
                val baseInterval = account.syncIntervalMinutes
                
                // Per-account: проверяем нужно ли применять ограничения Battery Saver
                val shouldApplyBatterySaver = batterySaverActive && !account.ignoreBatterySaver
                
                // Per-account: проверяем нужно ли применять ночной режим
                val shouldApplyNightMode = account.nightModeEnabled && isNight
                
                when {
                    // Battery Saver имеет приоритет (если не игнорируется для этого аккаунта)
                    shouldApplyBatterySaver && baseInterval > 0 -> maxOf(NIGHT_MODE_INTERVAL.toInt(), baseInterval)
                    // Затем ночной режим (если включён для этого аккаунта)
                    shouldApplyNightMode && baseInterval > 0 -> maxOf(NIGHT_MODE_INTERVAL.toInt(), baseInterval)
                    else -> baseInterval
                }
            }
            
            // Берём минимальный эффективный интервал
            val effectiveInterval = effectiveIntervals.minOrNull() ?: 15
            
            val wifiOnly = settingsRepo.syncOnWifiOnly.first()
            
            if (effectiveInterval <= 0) {
                cancel(context)
                return
            }
            
            schedule(context, effectiveInterval.toLong(), wifiOnly)
            PushService.scheduleSyncAlarm(context, effectiveInterval)
        }
        
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
        
        private const val MANUAL_SYNC_WORK = "manual_sync_work"
        
        fun syncNow(context: Context, wifiOnly: Boolean = false) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.Data.Builder().putBoolean("manual_sync", true).build())
                .build()
            
            // KEEP: если ручная синхронизация уже запущена — не ставим ещё одну в очередь
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_SYNC_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
