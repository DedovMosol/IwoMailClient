package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.domain.ReadReceipts
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.AttachmentErrors
import com.dedovmosol.iwomail.eas.EasAttachment
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EwsAttachments
import com.dedovmosol.iwomail.eas.EwsMailFolder
import com.dedovmosol.iwomail.eas.isEasFileReference
import com.dedovmosol.iwomail.util.detectImageMimeType
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.eas.MailFlag
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.onSuccessResult
import com.dedovmosol.iwomail.util.stripHtml
import com.dedovmosol.iwomail.widget.updateMailWidget
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Сервис для операций с письмами (move, delete, markAsRead, отчёты о прочтении)
 * Single Responsibility: операции с письмами на сервере и локально
 */
class EmailOperationsService(
    private val context: Context,
    private val database: MailDatabase,
    private val folderDao: FolderDao,
    private val emailDao: EmailDao,
    private val attachmentDao: AttachmentDao,
    private val accountRepo: AccountRepository,
    private val emailSyncService: EmailSyncService,
    /** Флаги в процессе записи на сервер — общий с синхронизацией ([EmailSyncService]). */
    private val flagWrites: FlagWriteTracker = FlagWriteTracker.shared
) {

    private val moveItemsStatusRegex = Regex("status=(\\d+)")
    private val previewWhitespaceRegex = Regex("\\s+")

    /** SQLite ограничивает число параметров запроса (999 на Android 8): `IN (:ids)` — пачками. */
    private val SQL_IN_CHUNK = 500

    /** Правки писем без связи, ждущие отправки (флаг, «прочитано»). */
    private val pendingChanges get() = database.pendingEmailChangeDao()

    /** Тело из ItemOperations (TruncationSize = 1 МиБ) меньше этого — точно не обрезано. */
    private val EAS_COMPLETE_BODY_BYTES = 1_040_000

    private fun ewsFolderOf(folder: FolderEntity?): EwsMailFolder? {
        val f = folder ?: return null
        return EwsMailFolder.of(f.type, f.displayName)
    }

    /**
     * Пометить письмо как прочитанное/непрочитанное. Локально — сразу; на сервер — Sync Change ([sendReadToServer]).
     */
    suspend fun markAsRead(emailId: String, read: Boolean): EasResult<Boolean> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        val account = accountRepo.getAccount(email.accountId) ?: return EasResult.Error("Аккаунт не найден")

        if (AccountType.fromStored(account.accountType) == AccountType.EXCHANGE) {
            val client = accountRepo.createEasClient(email.accountId) ?: return EasResult.Error("Не удалось создать клиент")
            val folder = folderDao.getFolder(email.folderId) ?: return EasResult.Error("Папка не найдена")

            // Оптимистичное обновление — UI реагирует сразу
            emailDao.updateReadStatus(emailId, read)
            updateFolderCounts(email.folderId)
            updateMailWidget(context)

            // Убираем текущее account-уведомление, чтобы не висело уже прочитанное письмо
            if (read) {
                cancelNotificationForAccount(email.accountId)
            }

            // Один Sync-запрос на папку за раз: ключ — из БД под шлюзом (см. FolderSyncGate)
            return FolderSyncGate.withFolder(email.folderId) { sendReadToServer(client, folder, listOf(email), read) }
        } else {
            emailDao.updateReadStatus(emailId, read)
            // Сервера, которому отправлять, нет (IMAP/POP3 — локально)
            pendingChanges.clearRead(emailId)
            updateFolderCounts(email.folderId)
            updateMailWidget(context)
            if (read) {
                cancelNotificationForAccount(email.accountId)
            }
            return EasResult.Success(true)
        }
    }

    /**
     * Батч-пометка нескольких писем как прочитанных/непрочитанных.
     * Группирует по папкам и отправляет один Sync-запрос на папку.
     * Exchange 2007 SP1: один Sync с N <Change> элементов.
     */
    suspend fun markAsReadBatch(emailIds: List<String>, read: Boolean): EasResult<Boolean> {
        if (emailIds.isEmpty()) return EasResult.Success(true)

        val emails = emailIds.mapNotNull { emailDao.getEmail(it) }
        if (emails.isEmpty()) return EasResult.Error("Письма не найдены")

        // Оптимистичное обновление — UI реагирует сразу
        emails.forEach { emailDao.updateReadStatus(it.id, read) }
        val affectedFolders = emails.map { it.folderId }.distinct()
        affectedFolders.forEach { updateFolderCounts(it) }
        updateMailWidget(context)

        // Группируем по аккаунту → по папке
        val byAccount = emails.groupBy { it.accountId }
        var lastError: String? = null

        for ((accountId, accountEmails) in byAccount) {
            val account = accountRepo.getAccount(accountId) ?: continue

            if (account.accountType != AccountType.EXCHANGE.name) {
                // Не Exchange — уже помечено локально
                continue
            }

            val client = accountRepo.createEasClient(accountId) ?: continue

            for ((folderId, folderEmails) in accountEmails.groupBy { it.folderId }) {
                val folder = folderDao.getFolder(folderId) ?: continue
                val result = FolderSyncGate.withFolder(folderId) { sendReadToServer(client, folder, folderEmails, read) }
                if (result is EasResult.Error) lastError = result.message
            }
        }

        updateMailWidget(context)
        if (read) {
            byAccount.keys.forEach { cancelNotificationForAccount(it) }
        }
        return lastError?.let { EasResult.Error(it) } ?: EasResult.Success(true)
    }

    /**
     * Sync Change «прочитано» для [emails] одной папки — под её шлюзом; локально уже помечено. Ключ — свежий из БД
     * (пока ждали шлюз, его мог продвинуть цикл синхронизации); устаревший — один повтор с ключом, который успел
     * сохранить параллельный sync (самим делать GetChanges нельзя: ключ ушёл бы мимо необработанных изменений).
     *
     * - Сервер отказал в изменении ([isChangeRejected]) — откат.
     * - Нет связи, нет ключа, временный сбой сервера — остаётся локально и ждёт отправки ([PendingMailChanges]):
     *   прочитанное без связи письмо не становится снова непрочитанным.
     *
     * Одно письмо — [com.dedovmosol.iwomail.eas.EasClient.markAsRead] (с дублированием по EWS для Exchange 2007),
     * несколько — один Sync с N изменениями.
     */
    private suspend fun sendReadToServer(
        client: com.dedovmosol.iwomail.eas.EasClient,
        folder: FolderEntity,
        emails: List<EmailEntity>,
        read: Boolean
    ): EasResult<Boolean> {
        val storedKey = folderDao.getFolder(folder.id)?.syncKey ?: folder.syncKey
        val syncKey = usableSyncKey(storedKey)
        if (syncKey == null) {
            deferRead(emails)
            return EasResult.Success(true)
        }
        suspend fun send(key: String): EasResult<String> {
            val single = emails.singleOrNull()
            return if (single != null) {
                client.markAsRead(folder.serverId, single.serverId, key, read, single.internetMessageId)
            } else {
                client.markAsReadBatch(folder.serverId, emails.map { it.serverId }, key, read)
            }
        }
        var result = send(syncKey)
        if (result is EasResult.Error && isLikelyStaleSyncKeyError(result.message)) {
            getRetrySyncKeyFromDatabase(folder.id, syncKey)?.let { fresh -> result = send(fresh) }
        }
        return when (val outcome = result) {
            is EasResult.Success -> {
                folderDao.updateSyncKey(folder.id, outcome.data)
                emails.forEach { pendingChanges.clearRead(it.id) }
                EasResult.Success(true)
            }
            is EasResult.Error -> if (isChangeRejected(outcome.message)) {
                android.util.Log.w("EmailOps", "Server rejected read=$read for ${emails.size} email(s): ${outcome.message}")
                emails.forEach {
                    emailDao.updateReadStatus(it.id, !read)
                    pendingChanges.clearRead(it.id)
                }
                updateFolderCounts(folder.id)
                updateMailWidget(context)
                EasResult.Error(outcome.message)
            } else {
                android.util.Log.w("EmailOps", "Read state not sent, will retry: ${outcome.message}")
                deferRead(emails)
                EasResult.Success(true)
            }
        }
    }

    /** «Прочитано» осталось на устройстве — отправится, когда появится связь. */
    private suspend fun deferRead(emails: List<EmailEntity>) {
        emails.forEach { pendingChanges.markRead(it.id, it.accountId) }
        com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(context)
    }

    /**
     * Повторная отправка «прочитано» из очереди: текущее значение письма. Письма уже нет — запись очереди снимается.
     * Под шлюзом папки, как и обычная пометка.
     */
    suspend fun retryPendingRead(emailId: String): EasResult<Boolean> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Success(true).also { pendingChanges.clearRead(emailId) }
        return markAsRead(emailId, email.read)
    }

    /**
     * Загружает полное тело письма с сервера
     * @param forceReload - принудительная перезагрузка даже если body уже есть
     */
    suspend fun loadEmailBody(emailId: String, forceReload: Boolean = false): EasResult<String> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        val folder = folderDao.getFolder(email.folderId)
        val suspiciousCachedBody = !forceReload &&
            email.body.isNotEmpty() &&
            folder?.type != FolderType.DRAFTS &&
            !cachedBodyMatchesPreview(email.preview, email.body)

        if (email.body.isNotEmpty() && !forceReload && !suspiciousCachedBody) {
            return EasResult.Success(email.body)
        }
        if (suspiciousCachedBody) {
            android.util.Log.w(
                "BDY",
                "loadEmailBody: cached body looks stale for emailId=$emailId, forcing exact reload"
            )
        }

        val account = accountRepo.getAccount(email.accountId)
            ?: return EasResult.Error("Аккаунт не найден")

        if (AccountType.fromStored(account.accountType) == AccountType.EXCHANGE) {
            val client = accountRepo.createEasClient(email.accountId)
                ?: return EasResult.Error("Не удалось создать клиент")

            val folderServerId = email.folderId.substringAfter("_")

            return when (val result = FolderSyncGate.withFolder(email.folderId) {
                client.fetchEmailBodyWithMdn(
                    folderServerId,
                    email.serverId,
                    fetchMimeHeaders = folder?.type != FolderType.SENT_ITEMS &&
                        !com.dedovmosol.iwomail.eas.RssItems.isRss(email.messageClass)
                )
            }) {
                is EasResult.Success -> {
                    if (folder?.type != FolderType.DRAFTS && !bodyBelongsTo(email, result.data)) {
                        return itemMismatch(email)
                    }
                    var bodyContent = result.data.body
                    var resolvedMessageId = result.data.originalMessageId

                    // КРИТИЧНО: Для Exchange 2007 SP1 ItemOperations может вернуть пустое тело
                    // В этом случае используем EWS как fallback
                    if (bodyContent.isEmpty() && client.isExchange2007()) {
                        android.util.Log.d("BDY", "loadEmailBody: Empty body from ItemOperations, trying EWS fallback...")

                        // Получаем тип папки для EWS DistinguishedFolderId
                        val ewsFolder = ewsFolderOf(folder)
                        if (ewsFolder != null) {
                            // Сначала Message-ID самой записи: он описывает письмо, которое на экране
                            val ewsResult = client.fetchEmailBodyViaEws(
                                subject = email.subject,
                                folder = ewsFolder,
                                dateReceived = email.dateReceived,
                                internetMessageId = email.internetMessageId?.takeIf { it.isNotBlank() }
                                    ?: resolvedMessageId?.takeIf { it.isNotBlank() }
                            )
                            if (ewsResult is EasResult.Success && ewsResult.data.body.isNotEmpty()) {
                                android.util.Log.d("BDY", "loadEmailBody: EWS fallback SUCCESS, bodyLength=${ewsResult.data.body.length}")
                                bodyContent = ewsResult.data.body
                                if (resolvedMessageId.isNullOrBlank()) {
                                    resolvedMessageId = ewsResult.data.originalMessageId
                                }
                            }
                        }
                    }

                    val written = writeIfSameItem(email) {
                        // КРИТИЧНО: Не перезаписываем существующее тело пустым ответом!
                        // Сервер может вернуть пустое тело из-за временной ошибки,
                        // race condition с другими EAS командами, или ограничений ItemOperations.
                        if (bodyContent.isNotEmpty() || email.body.isEmpty()) {
                            emailDao.updateBody(emailId, bodyContent)
                        } else if (suspiciousCachedBody) {
                            // Заведомо подозрительное тело хуже пустого: не показываем чужой кэш.
                            emailDao.updateBody(emailId, "")
                            android.util.Log.w("BDY", "loadEmailBody: exact reload failed, dropping suspicious cached body")
                            bodyContent = ""
                        } else {
                            android.util.Log.w("BDY", "loadEmailBody: Server returned empty body, keeping existing (${email.body.length} chars)")
                            bodyContent = email.body
                        }

                        // КРИТИЧНО: Если bodyType=4 (MIME), но мы сохранили уже извлечённый HTML,
                        // обновляем bodyType на 2. Иначе при повторном открытии EmailDetailScreen
                        // будет пытаться парсить HTML как MIME → inline картинки не найдутся.
                        if (email.bodyType == 4 && bodyContent.isNotEmpty() &&
                            !bodyContent.contains("Content-Type:", ignoreCase = true)) {
                            emailDao.updateBodyType(emailId, 2)
                            android.util.Log.d("BDY", "loadEmailBody: Updated bodyType 4→2 (stored HTML, not MIME)")
                        }

                        if (!result.data.mdnRequestedBy.isNullOrBlank() && !email.mdnSent) {
                            emailDao.updateMdnRequestedBy(emailId, result.data.mdnRequestedBy)
                        }
                        result.data.listUnsubscribe?.let { emailDao.updateListUnsubscribe(emailId, it, result.data.listUnsubscribeOneClick) }

                        // Message-ID записи не перезаписывается: известный уже описывает это письмо
                        resolvedMessageId
                            ?.takeIf { it.isNotBlank() && email.internetMessageId.isNullOrBlank() }
                            ?.let { emailDao.updateInternetMessageId(emailId, it) }
                        result.data.inReplyTo
                            ?.takeIf { it.isNotBlank() && email.inReplyTo != it }
                            ?.let { emailDao.updateInReplyTo(emailId, it) }
                    }
                    // Пока тело грузилось, синхронизация переписала или убрала запись — она уже исправлена
                    if (!written) return itemMismatch(email, markStale = false)

                    android.util.Log.d("BDY", "loadEmailBody: SUCCESS, bodyLength=${bodyContent.length}")
                    EasResult.Success(bodyContent)
                }
                is EasResult.Error -> {
                    if (result.message == "OBJECT_NOT_FOUND") {
                        // [MS-ASCMD] ItemOperations Fetch Status=6 — объекта нет.
                        // На 2007 пустой Fetch / Status=8 (Range) сюда больше не попадают.
                        // EWS — только по Message-ID или узкому окну; чужое тело не пишем.
                        val folderForCheck = folder ?: folderDao.getFolder(email.folderId)

                        if (client.isExchange2007()) {
                            val ewsFolder = ewsFolderOf(folderForCheck)
                            if (ewsFolder != null) {
                                android.util.Log.d("BDY", "loadEmailBody: OBJECT_NOT_FOUND, trying EWS fallback")
                                val ewsResult = client.fetchEmailBodyViaEws(
                                    subject = email.subject,
                                    folder = ewsFolder,
                                    dateReceived = email.dateReceived,
                                    internetMessageId = email.internetMessageId
                                )
                                if (ewsResult is EasResult.Success && ewsResult.data.body.isNotEmpty()) {
                                    android.util.Log.d("BDY", "loadEmailBody: EWS fallback recovered body after OBJECT_NOT_FOUND, len=${ewsResult.data.body.length}")
                                    val bodyContent = ewsResult.data.body
                                    val written = writeIfSameItem(email) {
                                        emailDao.updateBody(emailId, bodyContent)
                                        if (email.bodyType == 4 && !bodyContent.contains("Content-Type:", ignoreCase = true)) {
                                            emailDao.updateBodyType(emailId, 2)
                                        }
                                        ewsResult.data.originalMessageId
                                            ?.takeIf { it.isNotBlank() && email.internetMessageId.isNullOrBlank() }
                                            ?.let { emailDao.updateInternetMessageId(emailId, it) }
                                        ewsResult.data.inReplyTo
                                            ?.takeIf { it.isNotBlank() && email.inReplyTo != it }
                                            ?.let { emailDao.updateInReplyTo(emailId, it) }
                                    }
                                    if (!written) return itemMismatch(email, markStale = false)
                                    return EasResult.Success(bodyContent)
                                }
                            }
                        }

                        // PROTO-08: письмо локально НЕ удаляем. Status 6 не значит «удалено»: пока идёт полная
                        // синхронизация папки (SyncKey=0), Exchange 2007 отвечает 6 на каждое письмо, которое ещё
                        // не пришло заново (проверено на сервере). Прежнее удаление с защитой от «воскрешения»
                        // на 5 минут прятало такое письмо до следующей полной синхронизации. Удалённое на сервере
                        // убирает синхронизация (Delete или сверка после полного прохода); её запускает экран.
                        android.util.Log.w("BDY", "loadEmailBody: OBJECT_NOT_FOUND for type=${folderForCheck?.type}, left to folder sync")
                    }
                    result
                }
            }
        }

        return EasResult.Error("Загрузка тела не поддерживается для этого типа аккаунта")
    }

    /** Что узнали о серверном черновике при открытии: тело для редактора и скрытые копии (null — нет новых данных). */
    data class DraftRefresh(
        val body: String?,
        val bcc: String?,
        /** Флажки «запросить отчёт» с сервера (EWS); null — без изменений. */
        val receipts: com.dedovmosol.iwomail.eas.ReceiptRequests? = null
    )

    /**
     * Серверный черновик перед редактированием: свежее тело и скрытые копии, по точному идентификатору.
     * Синхронизация могла сохранить тело обрезанным или устаревшим, а сохранение такого тела
     * перезаписало бы полное на сервере; скрытые копии EAS до 16.0 не передаёт вовсе.
     *
     * - EWS ItemId → GetItem (тело, Bcc, время изменения).
     * - EAS ServerId → ItemOperations Fetch; EWS GetItem — если тело не пришло (Exchange 2007 для
     *   черновиков отдаёт пустое), скрытые копии не известны или версии расходятся. EWS-id для EAS-id —
     *   только по однозначной теме (иначе можно открыть чужой черновик).
     *
     * Выбор тела — [DraftBodies.choose]; найденное записывается в БД. null — черновик не серверный или
     * сервер недоступен (остаётся сохранённое).
     */
    suspend fun refreshServerDraft(emailId: String): DraftRefresh? {
        val email = emailDao.getEmail(emailId) ?: return null
        val serverId = email.serverId
        if (serverId.isBlank() || com.dedovmosol.iwomail.eas.DraftIds.isLocal(serverId)) return null
        val client = accountRepo.createEasClient(email.accountId) ?: return null

        var details: com.dedovmosol.iwomail.eas.EwsDraftXml.DraftDetails? = null
        val serverBody: String?
        if (com.dedovmosol.iwomail.eas.DraftIds.isEwsItemId(serverId)) {
            details = (client.getDraftDetails(serverId) as? EasResult.Success)?.data
            serverBody = details?.body
        } else {
            // ItemOperations отдаёт тело не больше 1 МиБ (TruncationSize запроса): у самой границы оно, возможно,
            // обрезано — тогда только тело из EWS (без ограничения), иначе сохранённое. Обрезанное сервером
            // (Truncated, в том числе политикой ActiveSync) или текстовая замена HTML — тоже не тело черновика.
            val easBody = (client.fetchEmailBodyWithMdn(email.folderId.substringAfter("_"), serverId, fetchMimeHeaders = false)
                as? EasResult.Success)?.data?.takeIf { it.complete }?.body?.takeIf { it.isNotBlank() }
                ?.takeIf { it.toByteArray(Charsets.UTF_8).size < EAS_COMPLETE_BODY_BYTES }
            if (easBody == null || email.bcc == null || DraftBodies.differ(email.body, easBody)) {
                details = client.findUniqueDraftItemIdBySubject(email.subject)
                    ?.let { (client.getDraftDetails(it) as? EasResult.Success)?.data }
            }
            serverBody = details?.body?.takeIf { it.isNotBlank() } ?: easBody
        }

        val body = DraftBodies.choose(email.body, email.dateReceived, serverBody, details?.lastModified)
        // В БД — только помещающееся в строку (окно курсора 2 МБ), иначе черновик не прочитать вовсе.
        if (body != null && body.length <= DraftUpload.MAX_LOCAL_BODY_CHARS) emailDao.updateBody(emailId, body)
        // Скрытые копии с сервера: сохранённых нет — или на сервере их изменили позже (Outlook), иначе следующее
        // сохранение вернуло бы прежние.
        val bcc = details?.bcc?.takeIf {
            it != email.bcc && (email.bcc == null || DraftBodies.serverIsNewer(email.dateReceived, details.lastModified))
        }
        if (bcc != null) emailDao.updateBcc(emailId, bcc)
        // Флажки отчётов — по тому же правилу: свои не известны или на сервере их изменили позже (Outlook)
        val stored = com.dedovmosol.iwomail.eas.ReceiptRequests.of(email.requestReadReceipt, email.requestDeliveryReceipt)
        val receipts = details?.receipts?.takeIf {
            it != stored && (stored == null || DraftBodies.serverIsNewer(email.dateReceived, details.lastModified))
        }
        if (receipts != null) emailDao.updateReceiptRequests(emailId, receipts.read, receipts.delivery)
        return DraftRefresh(body, bcc, receipts)
    }

    /**
     * Отписка у письма, тело которого загружено раньше (до поддержки отписки или без заголовков): List-Unsubscribe ещё
     * не проверялся (null) — один запрос верхних заголовков MIME, ответ сохраняется ("" — отписки нет) и больше не
     * запрашивается. Ошибка связи — спросим при следующем открытии. Только там, где отписка предлагается.
     */
    suspend fun ensureListUnsubscribe(emailId: String) {
        val email = emailDao.getEmail(emailId) ?: return
        if (email.listUnsubscribe != null || email.serverId.isBlank() || com.dedovmosol.iwomail.eas.RssItems.isRss(email.messageClass)) return
        if (com.dedovmosol.iwomail.eas.DraftIds.isLocal(email.serverId) || com.dedovmosol.iwomail.eas.DraftIds.isEwsItemId(email.serverId)) return
        val folder = folderDao.getFolder(email.folderId) ?: return
        if (!com.dedovmosol.iwomail.eas.ListUnsubscribe.offeredIn(folder.type)) return
        val account = accountRepo.getAccount(email.accountId) ?: return
        if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) return
        val client = accountRepo.createEasClient(email.accountId) ?: return
        val headers = client.fetchTopLevelHeaders(folder.serverId, email.serverId) as? EasResult.Success ?: return
        val (value, oneClick) = com.dedovmosol.iwomail.eas.ListUnsubscribe.fromHeaders(headers.data) ?: ("" to false)
        writeIfSameItem(email) { emailDao.updateListUnsubscribe(emailId, value, oneClick) }
    }

    /**
     * Письмо целиком (RFC 5322, .eml) с сервера — для сохранения файлом. Черновик только на устройстве на сервере не
     * лежит — ошибка. Пустой массив — сервер письма не отдал.
     */
    suspend fun fetchMessageMime(emailId: String): EasResult<ByteArray> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        if (email.serverId.isBlank() || com.dedovmosol.iwomail.eas.DraftIds.isLocal(email.serverId)) {
            return EasResult.Error("Письмо ещё не сохранено на сервере")
        }
        val client = accountRepo.createEasClient(email.accountId)
            ?: return EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
        val folder = folderDao.getFolder(email.folderId)
        return client.fetchMessageMime(
            collectionId = folder?.serverId ?: email.folderId.substringAfter("_"),
            serverId = email.serverId,
            subject = email.subject,
            folder = ewsFolderOf(folder),
            dateReceived = email.dateReceived,
            internetMessageId = email.internetMessageId
        )
    }

    private fun cachedBodyMatchesPreview(preview: String, body: String): Boolean {
        val normalizedPreview = normalizePreviewText(preview)
        if (normalizedPreview.isBlank()) return true

        val normalizedBody = normalizePreviewText(stripHtml(body))
        if (normalizedBody.isBlank()) return false

        if (normalizedBody.startsWith(normalizedPreview)) return true

        val searchWindow = normalizedBody.take((normalizedPreview.length + 64).coerceAtLeast(160))
        return searchWindow.contains(normalizedPreview)
    }

    private fun normalizePreviewText(text: String): String = text
        .replace(previewWhitespaceRegex, " ")
        .trim()
        .lowercase(Locale.ROOT)

    /**
     * Синхронизирует вложения конкретного письма с сервером через ItemOperations.
     * Делегирует в EmailSyncService.reconcileAttachments (DRY) — единая
     * транзакционная логика для sync worker и UI.
     *
     * Exchange 2007 SP1 / EAS 12.1: Attachment metadata поддерживается
     * через ItemOperations Fetch (MS-ASAIRS 2.2.2.7).
     */
    suspend fun refreshAttachmentMetadata(emailId: String) {
        try {
            val email = emailDao.getEmail(emailId) ?: return
            val account = accountRepo.getAccount(email.accountId) ?: return
            if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) return

            val sid = email.serverId
            if (sid.startsWith("local_draft_") || sid.isBlank()) return
            if (sid.contains("=") && !sid.contains(":")) return

            val client = accountRepo.createEasClient(email.accountId) ?: return
            val folderServerId = email.folderId.substringAfter("_")

            val attResult = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                FolderSyncGate.withFolder(email.folderId) {
                    client.fetchAttachmentMetadata(folderServerId, sid)
                }
            } ?: run {
                android.util.Log.w("EmailOps", "refreshAttachmentMetadata timeout emailId=$emailId")
                return
            }
            if (attResult is EasResult.Success) {
                // Пока шёл запрос, синхронизация могла отдать id другому письму — вложения были бы чужими
                writeIfSameItem(email) { emailSyncService.reconcileAttachments(emailId, attResult.data) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EmailOps", "refreshAttachmentMetadata failed: ${e.message}")
        }
    }

    /**
     * Вложение письма в файл [destFile] потоком (PROTO-17, MAIL-04): уже скачанное — копия локального файла;
     * EWS AttachmentId (черновик, сохранённый через EWS) — EWS GetAttachment; иначе EAS, а при отказе EAS
     * (в том числе лимит ~10 МБ на Exchange 2007) — EWS по Message-ID или теме и времени письма.
     * Клиент — аккаунта письма, а не активного (письмо из поиска другого ящика). Ошибка — файла [destFile] нет.
     */
    suspend fun downloadAttachmentToFile(attachment: AttachmentEntity, destFile: java.io.File): EasResult<java.io.File> {
        attachment.localPath?.let { java.io.File(it) }?.takeIf { attachment.downloaded && it.isFile }?.let { local ->
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    local.inputStream().use { input -> destFile.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    EasResult.Success(destFile)
                } catch (e: Exception) {
                    destFile.delete()
                    if (e is CancellationException) throw e
                    EasResult.Error(com.dedovmosol.iwomail.eas.AttachmentErrors.of(e))
                }
            }
        }
        val email = emailDao.getEmail(attachment.emailId) ?: return EasResult.Error(com.dedovmosol.iwomail.eas.AttachmentErrors.NOT_FOUND)
        val account = accountRepo.getAccount(email.accountId) ?: return EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
        if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error(com.dedovmosol.iwomail.eas.AttachmentErrors.NOT_FOUND)
        }
        val client = accountRepo.createEasClient(email.accountId) ?: return EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
        val ref = attachment.fileReference
        val result = if (ref.isNotBlank() && !com.dedovmosol.iwomail.eas.isEasFileReference(ref)) {
            client.downloadEwsAttachmentToFile(ref, destFile)
        } else {
            val locator = ewsFolderOf(folderDao.getFolder(email.folderId))?.let { folder ->
                com.dedovmosol.iwomail.eas.EasClient.EwsMailLocator(email.subject, folder, email.dateReceived, email.internetMessageId)
            }
            client.downloadMailAttachmentToFile(ref, destFile, attachment.displayName, attachment.contentId, locator)
        }
        if (result is EasResult.Success) {
            client.policyKey?.takeIf { it != account.policyKey }?.let { accountRepo.savePolicyKey(account.id, it) }
        }
        return result
    }

    /** Resolve only requested CIDs on the message owner's account. No body or attachment cache writes. */
    internal suspend fun loadInlineAttachmentBytes(
        emailId: String,
        accountId: Long,
        cids: Set<String>,
        attachments: List<AttachmentEntity>,
        maxPerImage: Long,
        maxTotal: Long,
        onImage: (String, ByteArray) -> Unit
    ) {
        val email = emailDao.getInlineAttachmentTarget(emailId)?.takeIf { it.accountId == accountId } ?: return
        if (email.serverId.isBlank() || email.serverId.startsWith("local_draft_")) return
        val account = accountRepo.getAccount(accountId) ?: return
        if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) return
        val client = accountRepo.createEasClient(accountId) ?: return
        val folder = folderDao.getFolder(email.folderId)
        // Base64 ItemIds need not end in padding (e.g. when the input length is divisible by three).
        val nativeEwsId = email.serverId.takeIf(com.dedovmosol.iwomail.eas.DraftIds::isEwsItemId)
        val locator = ewsFolderOf(folder)?.let {
            EasClient.EwsMailLocator(email.subject, it, email.dateReceived, email.internetMessageId)
        }
        var refreshed: List<EasAttachment>? = null
        var ewsEntries: List<EwsAttachments.FileAttachment>? = null
        var remaining = maxTotal
        val local = attachments.filter { it.emailId == emailId }
        fun matches(id: String?, wanted: String) = id?.let { normalizeContentId(it).equals(wanted, ignoreCase = true) } == true
        fun isCandidate(type: String, estimate: Long) =
            (type.startsWith("image/", ignoreCase = true) || type == "application/octet-stream" || type.isBlank()) &&
                estimate <= maxPerImage
        suspend fun fetch(ref: String, limit: Long): EasResult<ByteArray> = when {
            ref.isBlank() -> EasResult.Error(AttachmentErrors.NOT_FOUND)
            isEasFileReference(ref) -> client.downloadSmallEasAttachment(ref, limit)
            else -> client.downloadSmallEwsAttachment(ref, limit)
        }
        fun EasResult<ByteArray>.imageOrNull(): ByteArray? =
            (this as? EasResult.Success)?.data?.takeIf { detectImageMimeType(it) != null }
        for (cid in cids.take(12)) {
            val limit = minOf(remaining, maxPerImage)
            if (limit <= 0) break
            val cached = local.singleOrNull { matches(it.contentId, cid) }
            // A known PDF/oversized image cannot become an inline image by changing protocols.
            if (cached != null && !isCandidate(cached.contentType, cached.estimatedSize)) continue
            // Positional EAS refs can still succeed after a renumbering, with unrelated bytes.
            // Only opaque EWS attachment IDs are safe to try without checking current metadata.
            val oldRef = cached?.fileReference.orEmpty().takeUnless(::isEasFileReference).orEmpty()
            val tried = fetch(oldRef, limit)
            // A stale ref can now point at a different (even oversized) file. Refresh metadata,
            // but do not retry the same ref; connection/auth failures still stop this candidate.
            if (tried is EasResult.Error && AttachmentErrors.isFinal(tried.message) &&
                tried.message != AttachmentErrors.TOO_LARGE_FOR_MEMORY) continue
            var image = tried.imageOrNull()
            if (image == null && nativeEwsId == null) {
                // Read fresh refs once, without reconciling Room or requesting MIME. Reject renumbered items.
                val entries = refreshed ?: FolderSyncGate.withFolder(email.folderId) {
                    val result = client.fetchInlineAttachmentMetadata(folder?.serverId ?: email.folderId.substringAfter('_'), email.serverId)
                    (result as? EasResult.Success)?.data?.takeIf { meta ->
                        meta.subject != null && meta.dateReceived != null && bodyBelongsTo(
                            email.subject, email.dateReceived, meta.subject, EmailSyncService.parseDate(meta.dateReceived)
                        )
                    }?.attachments.orEmpty()
                }.also { refreshed = it }
                val fresh = entries.singleOrNull { matches(it.contentId, cid) }
                if (fresh != null && !isCandidate(fresh.contentType, fresh.estimatedSize)) continue
                val ref = fresh?.fileReference?.takeIf { it.isNotBlank() && it != oldRef }
                if (ref != null) {
                    val retry = fetch(ref, limit)
                    if (retry is EasResult.Error && AttachmentErrors.isFinal(retry.message)) continue
                    image = retry.imageOrNull()
                }
            }
            if (image == null) {
                val entries = ewsEntries ?: client.listInlineAttachmentCandidates(nativeEwsId, locator).also { ewsEntries = it }
                val candidate = EwsAttachments.matchInline(entries, cid)
                if (candidate != null && candidate.id != oldRef) {
                    image = client.downloadSmallEwsAttachment(candidate.id, limit).imageOrNull()
                }
            }
            if (image != null) {
                if (emailDao.countSameItem(emailId, email.subject, email.from, email.dateReceived) == 0) return
                remaining -= image.size
                onImage(cid, image)
            }
        }
        client.policyKey?.takeIf { it != account.policyKey }?.let { accountRepo.savePolicyKey(accountId, it) }
    }

    /**
     * Предзагрузка тел последних N писем из Inbox
     */
    suspend fun prefetchEmailBodies(accountId: Long, count: Int = 7) {
        try {
            val inboxFolder = folderDao.getFolderByType(accountId, FolderType.INBOX) ?: return
            val emails = emailDao.getEmailsWithEmptyBody(inboxFolder.id, count)
            if (emails.isEmpty()) return

            val account = accountRepo.getAccount(accountId) ?: return
            if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) return

            val client = accountRepo.createEasClient(accountId) ?: return
            val folderServerId = inboxFolder.serverId

            for (email in emails) {
                try {
                    val emailId = email.id
                    val emailServerId = email.serverId
                    val result = FolderSyncGate.tryWithFolder(inboxFolder.id) {
                        client.fetchEmailBodyWithMdn(
                            folderServerId, emailServerId,
                            fetchMimeHeaders = !com.dedovmosol.iwomail.eas.RssItems.isRss(email.messageClass)
                        )
                    } ?: continue
                    val response = (result as? EasResult.Success)?.data ?: continue
                    if (!bodyBelongsTo(email, response)) {
                        // Под этим id на сервере другое письмо: не писать, полная синхронизация перепишет записи
                        itemMismatch(email)
                        break
                    }
                    writeIfSameItem(email) {
                        if (response.body.isNotBlank()) emailDao.updateBody(emailId, response.body)
                        if (!response.mdnRequestedBy.isNullOrBlank() && !email.mdnSent) {
                            emailDao.updateMdnRequestedBy(emailId, response.mdnRequestedBy)
                        }
                        response.listUnsubscribe?.let {
                            emailDao.updateListUnsubscribe(emailId, it, response.listUnsubscribeOneClick)
                        }
                        response.originalMessageId
                            ?.takeIf { it.isNotBlank() && email.internetMessageId.isNullOrBlank() }
                            ?.let { emailDao.updateInternetMessageId(emailId, it) }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e else Unit }
    }

    /**
     * Переключить флаг письма (звёздочка): снять или поставить без срока. Новое значение считается от флага в БД под
     * замком [FlagWriteTracker] — частые нажатия чередуются, а не повторяют одно и то же.
     */
    suspend fun toggleFlag(emailId: String): EasResult<Boolean> =
        writeFlag(emailId) { com.dedovmosol.iwomail.domain.FlagPresets.toggled(it) }

    /**
     * Флаг письма: отметка, срок, напоминание, «выполнено» или снять ([MailFlag]).
     */
    suspend fun setFlag(emailId: String, flag: MailFlag): EasResult<Boolean> = writeFlag(emailId) { flag }

    /**
     * Повторная отправка флага из очереди: текущий флаг письма, тем же путём, что и нажатие. Письма уже нет — запись
     * очереди снимается.
     */
    suspend fun retryPendingFlag(emailId: String): EasResult<Boolean> {
        if (emailDao.getFlagTarget(emailId) == null) {
            pendingChanges.clearFlag(emailId)
            return EasResult.Success(true)
        }
        return writeFlag(emailId) { it }
    }

    /**
     * Локально — сразу (оптимистично), на сервер — Sync Change; напоминания на устройстве перепланируются.
     *
     * - Нет связи, нет ключа, временный сбой — флаг остаётся локально и ждёт отправки ([PendingMailChanges]); звезда не
     *   мигает и не гаснет, результат — успех.
     * - Сервер отказал в изменении ([isChangeRejected]) — откат к флагу, подтверждённому сервером, и
     *   [RepositoryErrors.FLAG_REJECTED].
     * - Серверная часть не отменяется уходом с экрана: запрос, оборванный на полпути, оставил бы флаг на сервере и
     *   на устройстве неизвестно каким.
     *
     * Письмо читается лёгкой проекцией ([EmailDao.getFlagTarget]) — без тела, которое у письма с картинками — мегабайты.
     */
    private suspend fun writeFlag(emailId: String, target: (MailFlag) -> MailFlag): EasResult<Boolean> {
        val email = emailDao.getFlagTarget(emailId) ?: return EasResult.Error("Письмо не найдено")
        val account = accountRepo.getAccount(email.accountId) ?: return EasResult.Error("Аккаунт не найден")

        val started = flagWrites.start(
            emailId,
            read = { emailDao.getFlagTarget(emailId)?.flag },
            target = target,
            write = { emailDao.saveFlag(emailId, it) },
            unconfirmed = pendingChanges.get(emailId)?.flag == true
        ) ?: return EasResult.Error("Письмо не найдено")
        if (started.target.reminderTime != null || started.previous.reminderTime != null) {
            com.dedovmosol.iwomail.sync.RescheduleRemindersWorker.enqueue(context, replace = true)
        }

        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            var outcome: FlagWriteTracker.Outcome = FlagWriteTracker.Outcome.KeptLocal
            try {
                if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
                    outcome = FlagWriteTracker.Outcome.Applied(started.target)
                    return@withContext EasResult.Success(true)
                }
                val client = accountRepo.createEasClient(email.accountId)
                    ?: return@withContext EasResult.Success(true) // клиент не создан — остаётся локально
                val result = FolderSyncGate.withFolder(email.folderId) {
                    // Пока ждали шлюз, пришло новое нажатие — итог на сервер отправит оно
                    if (!flagWrites.isLatest(emailId, started.ticket)) null
                    else writeFlagToServer(client, email, started.target)
                } ?: return@withContext EasResult.Success(true)
                when (result) {
                    is EasResult.Success -> {
                        val applied = result.data.applied
                        val status = result.data.changeStatus
                        if (applied != null) {
                            outcome = FlagWriteTracker.Outcome.Applied(applied)
                            EasResult.Success(true)
                        } else if (status != null && !isRejectingChangeStatus(status)) {
                            android.util.Log.w("SetFlag", "Server does not know the item yet (Status=$status), kept locally")
                            EasResult.Success(true)
                        } else {
                            android.util.Log.w("SetFlag", "Server rejected flag: Status=${result.data.changeStatus}, rolling back")
                            outcome = FlagWriteTracker.Outcome.Rejected
                            EasResult.Error(RepositoryErrors.FLAG_REJECTED)
                        }
                    }
                    is EasResult.Error -> if (isChangeRejected(result.message)) {
                        android.util.Log.w("SetFlag", "Server rejected flag: ${result.message}, rolling back")
                        outcome = FlagWriteTracker.Outcome.Rejected
                        EasResult.Error(RepositoryErrors.FLAG_REJECTED)
                    } else {
                        android.util.Log.w("SetFlag", "Flag not sent, will retry: ${result.message}")
                        EasResult.Success(true)
                    }
                }
            } finally {
                flagWrites.finish(emailId, started, outcome) { flag, settled ->
                    if (flag != null) emailDao.saveFlag(emailId, flag)
                    if (settled == FlagWriteTracker.Outcome.KeptLocal) {
                        pendingChanges.markFlag(emailId, email.accountId)
                        com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(context)
                    } else {
                        pendingChanges.clearFlag(emailId)
                    }
                }
            }
        }
    }

    /**
     * Sync Change флага под шлюзом папки: ключ — свежий из БД, устаревший (параллельный sync уже сохранил новый) —
     * один повтор, как у [markAsRead]. Новый ключ сохраняется и при отказе в самом изменении. null — ключа нет.
     */
    private suspend fun writeFlagToServer(
        client: com.dedovmosol.iwomail.eas.EasClient,
        email: EmailFlagTarget,
        flag: MailFlag
    ): EasResult<com.dedovmosol.iwomail.eas.FlagWrite>? {
        val folder = folderDao.getFolder(email.folderId) ?: return null
        val syncKey = usableSyncKey(folder.syncKey) ?: return null
        var result = client.setFlag(folder.serverId, email.serverId, syncKey, flag)
        if (result is EasResult.Error && isLikelyStaleSyncKeyError(result.message)) {
            getRetrySyncKeyFromDatabase(email.folderId, syncKey)?.let { fresh ->
                result = client.setFlag(folder.serverId, email.serverId, fresh, flag)
            }
        }
        (result as? EasResult.Success)?.let { folderDao.updateSyncKey(email.folderId, it.data.syncKey) }
        return result
    }

    /**
     * Удалить письмо локально (с транзакцией для целостности данных)
     */
    suspend fun deleteEmail(emailId: String) {
        EmailSyncService.registerDeletedEmail(emailId, context)
        database.withTransaction {
            attachmentDao.deleteByEmail(emailId)
            emailDao.delete(emailId)
        }
    }

    /**
     * Собирает пары (emailServerId, srcFolderServerId) для MoveItems.
     * DRY: используется при первой попытке и при retry.
     */
    private suspend fun buildMoveItems(
        emailIds: List<String>,
        srcFolderServerIdByEmailId: Map<String, String> = emptyMap()
    ): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>() // (emailId, emailServerId, srcFolderServerId)
        for (emailId in emailIds) {
            val email = emailDao.getEmail(emailId) ?: continue
            val emailServerId = email.serverId
            val srcFolderServerId = if (emailServerId.contains(":")) {
                emailServerId.substringBefore(":")
            } else {
                srcFolderServerIdByEmailId[emailId]
                    ?: folderDao.getFolder(email.folderId)?.serverId
                    ?: email.folderId.substringAfter("_")
            }
            result.add(Triple(emailId, emailServerId, srcFolderServerId))
        }
        return result
    }

    /**
     * MS-ASCMD §2.2.3.166.8: На EAS 12.1 ServerIds НЕ стабильны после SyncKey=0 reset.
     * После sync emailIds могут указывать на мигрированные записи.
     * Для каждого снимка: если запись с его id существует — используем как есть.
     * Если нет — ищем по subject+from+dateReceived±2сек в исходной папке.
     * Id берутся из самих снимков: отдельный список id мог разойтись со снимками по индексу (письма, которого уже нет,
     * снимка нет) — и сопоставлялось бы не то письмо.
     * @param snapshots снимки писем ДО sync (для content matching)
     * @param sourceFolderId папка для поиска
     * @return актуальные emailIds (могут отличаться от входных)
     */
    private suspend fun resolveEmailIds(
        snapshots: List<EmailServerRef>,
        sourceFolderId: String
    ): List<String> {
        if (snapshots.isEmpty()) return emptyList()
        val resolved = mutableListOf<String>()
        // PERF: только id существующих (один запрос на пачку) и лёгкая проекция папки — без тел писем
        val existing = snapshots.map { it.id }.chunked(SQL_IN_CHUNK).flatMap { emailDao.getExistingIds(it) }.toHashSet()
        var folderDedup: List<EmailDedupInfo>? = null
        for (snapshot in snapshots) {
            val emailId = snapshot.id
            if (emailId in existing) {
                resolved.add(emailId)
                continue
            }
            // Запись мигрирована — ищем по содержимому (lazy load)
            if (folderDedup == null) {
                folderDedup = emailDao.getDedupInfoByFolder(sourceFolderId)
            }
            val candidate = folderDedup.firstOrNull { e ->
                e.subject == snapshot.subject &&
                e.from == snapshot.from &&
                e.to == snapshot.to &&
                kotlin.math.abs(e.dateReceived - snapshot.dateReceived) < 2000
            }
            if (candidate != null) {
                android.util.Log.d("EmailOps",
                    "resolveEmailIds: $emailId → ${candidate.id} (serverId migrated)")
                resolved.add(candidate.id)
            }
        }
        return resolved
    }

    /**
     * Перемещение писем в другую папку
     */
    suspend fun moveEmails(emailIds: List<String>, targetFolderId: String, updateOriginalFolder: Boolean = true): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)

        // Снимки до синхронизации (без тел): по ним [resolveEmailIds] находит письма, если ServerId сменился
        val allEmails = serverRefs(emailIds)
        val firstEmail = allEmails.firstOrNull { it.id == emailIds.first() }
            ?: return EasResult.Error("Email not found")
        if (!allEmails.all { it.accountId == firstEmail.accountId }) {
            return EasResult.Error("All emails must belong to the same account")
        }

        val account = accountRepo.getAccount(firstEmail.accountId)
            ?: return EasResult.Error("Account not found")

        if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Move is only supported for Exchange")
        }

        val targetFolder = folderDao.getFolder(targetFolderId)
            ?: return EasResult.Error("Target folder not found")

        val sourceFolderId = firstEmail.folderId
        val resolvedEmailIds = resolveEmailIds(allEmails, sourceFolderId)
        if (resolvedEmailIds.isEmpty()) return EasResult.Error("Emails not found")

        val srcFolderServerIdByEmailId = HashMap<String, String>(resolvedEmailIds.size)
        for (id in resolvedEmailIds) {
            val email = emailDao.getEmail(id) ?: continue
            val srcFolderId = if (email.folderId == targetFolderId) {
                email.originalFolderId ?: email.folderId
            } else {
                email.folderId
            }
            val src = if (email.serverId.contains(":")) email.serverId.substringBefore(":")
            else folderDao.getFolder(srcFolderId)?.serverId ?: srcFolderId.substringAfter("_")
            srcFolderServerIdByEmailId[id] = src
        }

        val triples = buildMoveItems(resolvedEmailIds, srcFolderServerIdByEmailId)
        if (triples.isEmpty()) return EasResult.Success(0)

        val items = triples.map { it.second to it.third }
        if (items.first().second == targetFolder.serverId) {
            val alreadyThere = resolvedEmailIds.all { emailDao.getEmail(it)?.folderId == targetFolderId }
            if (alreadyThere) {
                resolvedEmailIds.forEach { pendingChanges.clearMove(it) }
                return EasResult.Success(resolvedEmailIds.size)
            }
            return EasResult.Error("ALREADY_IN_FOLDER")
        }

        val originalFolderById = allEmails.associate { it.id to it.folderId }
        for (id in resolvedEmailIds) {
            val email = emailDao.getEmail(id) ?: continue
            if (email.folderId != targetFolderId) {
                emailDao.updateFolderId(id, targetFolderId)
                if (updateOriginalFolder) {
                    originalFolderById[id]?.let { emailDao.updateOriginalFolderId(id, it) }
                }
            }
            pendingChanges.markMove(id, firstEmail.accountId, targetFolderId)
        }
        originalFolderById.values.toSet().forEach { updateFolderCounts(it) }
        updateFolderCounts(targetFolderId)

        val client = accountRepo.createEasClient(firstEmail.accountId)
        if (client == null) {
            com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(context)
            return EasResult.Success(resolvedEmailIds.size)
        }

        var result: EasResult<Map<String, String>>
        try {
            result = client.moveItems(items, targetFolder.serverId)
        } catch (e: CancellationException) {
            com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(context)
            throw e
        } catch (e: Exception) {
            com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(context)
            return EasResult.Success(resolvedEmailIds.size)
        }

        // КРИТИЧНО: Если все MoveItems отклонены (status=1 — устаревшие serverId),
        // пересинхронизируем исходную папку и повторяем с актуальными serverId.
        // MS-ASCMD §2.2.3.177.10 Status=1: "Issue a Sync command for the SrcFldId
        // and reissue the MoveItems command request".
        if (result is EasResult.Error && moveItemsNeedsSrcSync(result.message)) {
            try {
                emailSyncService.syncEmails(firstEmail.accountId, sourceFolderId, forceFullSync = false)

                val retryIds = resolvedEmailIds.filter { emailDao.getEmail(it) != null }
                val freshTriples = buildMoveItems(retryIds, srcFolderServerIdByEmailId)
                if (freshTriples.isNotEmpty()) {
                    val freshItems = freshTriples.map { it.second to it.third }
                    result = client.moveItems(freshItems, targetFolder.serverId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }

        // FALLBACK: Если batch по-прежнему отклонён, пробуем по одному.
        // MS-ASCMD §2.2.3.177.10:
        //   status=1 (Invalid source) = письмо не существует на сервере → ghost, удаляем локально.
        //   status=2,4,5,7 = письмо СУЩЕСТВУЕТ, но move невозможен → НЕ удаляем.
        var ghostDeletedCount = 0
        if (result is EasResult.Error && moveItemsAllFailed(result.message)) {
            val allResults = mutableMapOf<String, String>() // srcServerId → dstServerId
            val ghostEmailIds = mutableListOf<String>()     // emailIds для локальной очистки (только status=1)
            val ghostFolderIds = mutableSetOf<String>()     // folderIds для обновления счётчиков
            val fallbackIds = resolvedEmailIds.filter { emailDao.getEmail(it) != null }
            val freshTriples = buildMoveItems(fallbackIds, srcFolderServerIdByEmailId)

            for ((emailId, serverId, srcFldId) in freshTriples) {
                val singleResult = client.moveItems(listOf(serverId to srcFldId), targetFolder.serverId)
                when (singleResult) {
                    is EasResult.Success -> {
                        if (singleResult.data.isNotEmpty()) {
                            allResults.putAll(singleResult.data)
                        }
                    }
                    is EasResult.Error -> {
                        // executeEasCommand оборачивает throw из parser в EasResult.Error
                        // Парсим status из "...MOVEITEMS_ALL_FAILED:status=N,..."
                        val failStatus = moveItemsStatusRegex.find(singleResult.message)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

                        if (failStatus == 1) {
                            // Status=1: Invalid source — письмо не существует на сервере.
                            // Удаляем ghost-запись локально.
                            val ghostEmail = emailDao.getEmail(emailId)
                            if (ghostEmail != null) ghostFolderIds.add(ghostEmail.folderId)
                            ghostEmailIds.add(emailId)
                        }
                        // Status=2,4,5,7: письмо существует, но move невозможен.
                        // НЕ удаляем — пользователь увидит ошибку и может повторить.
                    }
                }
            }

            // Очищаем ghost-записи локально.
            // НЕ вызываем registerDeletedEmail — status=1 означает лишь устаревший serverId,
            // само письмо может существовать на сервере с другим serverId.
            // Следующий sync вернёт актуальную версию.
            for (ghostId in ghostEmailIds) {
                attachmentDao.deleteByEmail(ghostId)
                emailDao.delete(ghostId)
            }
            ghostDeletedCount = ghostEmailIds.size

            result = if (allResults.isNotEmpty() || ghostEmailIds.isNotEmpty()) {
                EasResult.Success(allResults)
            } else {
                result
            }

            for (srcId in ghostFolderIds) {
                updateFolderCounts(srcId)
            }
        }

        return when (result) {
            is EasResult.Success -> {
                val movedMap = result.data
                var movedCount = 0

                for (emailId in resolvedEmailIds) {
                    val email = emailDao.getEmail(emailId) ?: continue
                    val newServerId = movedMap[email.serverId]
                    if (newServerId != null) {
                        val newEmailId = "${firstEmail.accountId}_$newServerId"
                        val newOriginalFolderId = if (updateOriginalFolder) targetFolderId else email.originalFolderId
                        val updatedEmail = email.copy(
                            id = newEmailId,
                            serverId = newServerId,
                            folderId = targetFolderId,
                            originalFolderId = newOriginalFolderId
                        )
                        EmailSyncService.registerDeletedEmail(emailId, context)
                        database.withTransaction {
                            val oldAttachments = attachmentDao.getAttachmentsList(emailId)
                            emailDao.delete(emailId)
                            emailDao.insert(updatedEmail)
                            if (oldAttachments.isNotEmpty()) {
                                attachmentDao.insertAll(oldAttachments.map {
                                    it.copy(id = 0, emailId = newEmailId)
                                })
                            }
                            pendingChanges.rename(emailId, newEmailId)
                            pendingChanges.clearMove(newEmailId)
                        }
                        movedCount++
                    } else {
                        pendingChanges.clearMove(emailId)
                    }
                }

                originalFolderById.values.toSet().forEach { updateFolderCounts(it) }
                updateFolderCounts(targetFolderId)
                EasResult.Success(movedCount + ghostDeletedCount)
            }
            is EasResult.Error -> {
                if (isOfflineMailError(result.message)) {
                    com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(context)
                    EasResult.Success(resolvedEmailIds.size)
                } else {
                    for (id in resolvedEmailIds) {
                        originalFolderById[id]?.let { emailDao.updateFolderId(id, it) }
                        pendingChanges.clearMove(id)
                    }
                    originalFolderById.values.toSet().forEach { updateFolderCounts(it) }
                    updateFolderCounts(targetFolderId)
                    result
                }
            }
        }
    }

    /**
     * Повтор переноса из очереди: письмо уже в целевой папке локально, SrcFldId — исходная папка на сервере.
     */
    suspend fun retryPendingMove(emailId: String): EasResult<Boolean> {
        val pending = pendingChanges.get(emailId)
        if (pending == null || !pending.move) return EasResult.Success(true)
        val email = emailDao.getEmail(emailId)
        if (email == null) {
            pendingChanges.clearMove(emailId)
            return EasResult.Success(true)
        }
        val targetId = pending.targetFolderId
        if (targetId.isBlank()) {
            pendingChanges.clearMove(emailId)
            return EasResult.Success(true)
        }
        val result = moveEmails(listOf(emailId), targetId, updateOriginalFolder = false)
        return when (result) {
            is EasResult.Success -> EasResult.Success(true)
            is EasResult.Error -> if (isOfflineMailError(result.message)) EasResult.Success(true) else result
        }
    }

    /**
     * Перемещение письма в спам
     */
    suspend fun moveToSpam(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)

        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")

        val spamFolder = folderDao.getFolderByType(firstEmail.accountId, FolderType.JUNK_EMAIL)
            ?: return EasResult.Error("Spam folder not found")

        return moveEmails(emailIds, spamFolder.id)
    }

    /**
     * Перемещение письма в корзину или окончательное удаление
     */
    suspend fun moveToTrash(
        emailIds: List<String>,
        deleteDraft: suspend (Long, String) -> EasResult<Boolean>
    ): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)

        return try {
            val firstEmail = emailDao.getEmail(emailIds.first())
                ?: return EasResult.Error("Email not found")

            val draftsFolder = folderDao.getFolderByType(firstEmail.accountId, FolderType.DRAFTS)
            val trashFolder = folderDao.getFolderByType(firstEmail.accountId, FolderType.DELETED_ITEMS)
                ?: return EasResult.Error("Trash folder not found")

            val drafts = mutableListOf<String>()
            val inTrash = mutableListOf<String>()
            val regularEmails = mutableListOf<String>()

            for (emailId in emailIds) {
                val email = emailDao.getEmail(emailId) ?: continue
                when {
                    draftsFolder != null && email.folderId == draftsFolder.id -> drafts.add(emailId)
                    email.folderId == trashFolder.id -> inTrash.add(emailId)
                    else -> regularEmails.add(emailId)
                }
            }

            var totalDeleted = 0

            for (emailId in drafts) {
                val email = emailDao.getEmail(emailId) ?: continue
                val result = deleteDraft(email.accountId, email.serverId)
                if (result is EasResult.Success) {
                    totalDeleted++
                } else {
                    regularEmails.add(emailId)
                    android.util.Log.w("EmailOps", "Draft server delete failed, moving to trash instead: ${email.serverId}")
                }
            }

            if (inTrash.isNotEmpty()) {
                val result = deleteEmailsPermanently(inTrash)
                if (result is EasResult.Success) {
                    totalDeleted += result.data
                }
            }

            if (regularEmails.isEmpty()) {
                return EasResult.Success(totalDeleted)
            }

            for (emailId in regularEmails) {
                val email = emailDao.getEmail(emailId) ?: continue
                emailDao.updateOriginalFolderId(emailId, email.folderId)
            }

            val moveResult = moveEmails(regularEmails, trashFolder.id, updateOriginalFolder = false)
            when (moveResult) {
                is EasResult.Success -> EasResult.Success(totalDeleted + moveResult.data)
                is EasResult.Error -> {
                    // Частичный успех: черновики/корзина удалены, но move не удался
                    if (totalDeleted > 0) EasResult.Success(totalDeleted)
                    else moveResult
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Delete error: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }

    /**
     * Восстановление письма из корзины
     */
    suspend fun restoreFromTrash(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)

        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")

        val targetFolderId = firstEmail.originalFolderId?.takeIf { it.isNotEmpty() }
            ?: folderDao.getFolderByType(firstEmail.accountId, FolderType.INBOX)?.id
            ?: return EasResult.Error("Target folder not found")

        return moveEmails(emailIds, targetFolderId)
    }

    /** Письма [ids] без тел, в порядке [ids] (отсутствующих в БД — нет в результате). */
    private suspend fun serverRefs(ids: List<String>): List<EmailServerRef> {
        val byId = ids.chunked(SQL_IN_CHUNK).flatMap { emailDao.getServerRefs(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * Синхронизация папки перед Delete (изменения сервера обрабатываются, ключ — актуальный) и письма [refs] — заново:
     * на EAS 12.1 после SyncKey=0 ServerId могут смениться (запись переезжает, [resolveEmailIds] находит её по
     * содержимому). Письма, которых больше нет (удалены на сервере), в результат не входят.
     */
    private suspend fun syncAndResolve(accountId: Long, folderId: String, refs: List<EmailServerRef>): List<EmailServerRef> {
        try {
            emailSyncService.syncEmails(accountId, folderId, forceFullSync = false)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EmailOps", "Sync before delete failed, deleting with the current key: ${e.message}")
        }
        return serverRefs(resolveEmailIds(refs, folderId))
    }

    /**
     * Пакетный EAS Delete под шлюзом папки ([FolderSyncGate]) с ключом из БД; новый ключ сохраняется (Delete идёт с
     * GetChanges=0 — изменения сервера не забираются). null — ключа нет (папка не синхронизирована).
     */
    private suspend fun deleteBatchUnderGate(
        client: com.dedovmosol.iwomail.eas.EasClient,
        folderId: String,
        folderServerId: String,
        emails: List<EmailServerRef>
    ): EasResult<String>? = FolderSyncGate.withFolder(folderId) {
        val syncKey = folderDao.getFolder(folderId)?.syncKey?.takeIf { it != "0" }
        syncKey?.let { key ->
            client.deleteEmailsPermanentlyBatch(folderServerId, emails.map { it.serverId }, key).also { result ->
                if (result is EasResult.Success) folderDao.updateSyncKey(folderId, result.data)
            }
        }
    }

    suspend fun deleteEmailsPermanently(emailIds: List<String>): EasResult<Int> {
        return deleteEmailsPermanentlyWithProgress(emailIds) { _, _ -> }
    }

    /**
     * Окончательное удаление писем с callback прогресса
     */
    suspend fun deleteEmailsPermanentlyWithProgress(
        emailIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)

        return try {
            val affectedFolderIds = mutableSetOf<String>()
            val total = emailIds.size
            var deletedCount = 0

            // Письма без тел (очистка корзины — сотни писем, тела с картинками не нужны)
            val emailInfos = serverRefs(emailIds)
            if (emailInfos.isEmpty()) {
                // Все письма уже удалены из БД (устаревшие ID)
                emailIds.forEach { attachmentDao.deleteByEmail(it) }
                onProgress(total, total)
                return EasResult.Success(total)
            }
            val accountId = emailInfos.first().accountId

            val account = accountRepo.getAccount(accountId)
                ?: return EasResult.Error("Account not found")

            if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
                val existing = emailInfos.map { it.id }.toSet()
                emailIds.forEach { emailId ->
                    attachmentDao.deleteByEmail(emailId)
                    if (emailId in existing) emailDao.delete(emailId)
                    deletedCount++
                    onProgress(deletedCount, total)
                }
                emailInfos.forEach { affectedFolderIds.add(it.folderId) }
                for (folderId in affectedFolderIds) {
                    updateFolderCounts(folderId)
                }
                return EasResult.Success(deletedCount)
            }

            val client = accountRepo.createEasClient(accountId)
                ?: return EasResult.Error("Failed to create client")

            // === BATCH DELETE: группируем письма по папкам и удаляем пачкой ===

            emailInfos.forEach { affectedFolderIds.add(it.folderId) }
            // Письма, которых уже нет в БД, считаются удалёнными
            deletedCount += emailIds.size - emailInfos.size
            emailIds.minus(emailInfos.map { it.id }.toSet()).forEach { attachmentDao.deleteByEmail(it) }
            onProgress(deletedCount, total)

            // Группируем по folderServerId для batch-операций
            val groupedByFolder = emailInfos.groupBy { info ->
                if (info.serverId.contains(":")) {
                    info.serverId.substringBefore(":")
                } else {
                    info.folderId.substringAfter("_")
                }
            }

            for ((folderServerId, groupEmails) in groupedByFolder) {
                val folderId = groupEmails.first().folderId

                // MS-ASCMD 2.2.3.186.4: перед Delete клиент получает изменения папки — обычной синхронизацией, которая
                // их обрабатывает. Прежние холостые Sync продвигали ключ мимо необработанных изменений: письма,
                // попавшие в папку из Outlook, и удаления там терялись до полной пересинхронизации.
                var emails = syncAndResolve(accountId, folderId, groupEmails)
                deletedCount += groupEmails.size - emails.size // удалены на сервере — синхронизация их уже убрала
                onProgress(deletedCount, total)
                if (emails.isEmpty()) continue

                var batchResult = deleteBatchUnderGate(client, folderId, folderServerId, emails)

                // Ключ устарел или пакет не применён — синхронизация восстановит ключ (Status 3 → новая цепочка, с
                // обработкой изменений), затем один повтор
                if (batchResult is EasResult.Error &&
                    (batchResult.message.contains("INVALID_SYNCKEY") || batchResult.message.contains("DELETE_NOT_APPLIED"))
                ) {
                    val before = emails.size
                    emails = syncAndResolve(accountId, folderId, emails)
                    deletedCount += before - emails.size
                    onProgress(deletedCount, total)
                    if (emails.isEmpty()) continue
                    batchResult = deleteBatchUnderGate(client, folderId, folderServerId, emails)
                }

                // Нет связи с сервером: поэлементный EWS-запрос на каждое письмо — это N таймаутов подряд (минуты
                // и батарея) без шанса на успех; ошибку покажет итог (DELETE_FAILED)
                val offline = (batchResult as? EasResult.Error)?.message
                    ?.let { com.dedovmosol.iwomail.network.ConnectionFailure.fromMessage(it)?.connectionLevel } == true

                if (batchResult is EasResult.Success) {
                    for (info in emails) {
                        EmailSyncService.registerDeletedEmail(info.id, context)
                        attachmentDao.deleteByEmail(info.id)
                        emailDao.delete(info.id)
                        deletedCount++
                        onProgress(deletedCount, total)
                    }
                } else if (!offline && (batchResult != null || client.isExchange2007())) {
                    // EAS не удалил (или ключа нет вовсе — только Exchange 2007): поэлементно через EWS
                    for (info in emails) {
                        val ewsResult = client.deleteEmailPermanentlyViaEWS(info.serverId, info.subject)
                        if (ewsResult is EasResult.Success) {
                            EmailSyncService.registerDeletedEmail(info.id, context)
                            attachmentDao.deleteByEmail(info.id)
                            emailDao.delete(info.id)
                            deletedCount++
                        }
                        onProgress(deletedCount, total)
                    }
                }
            }

            for (folderId in affectedFolderIds) {
                updateFolderCounts(folderId)
            }

            if (deletedCount == 0 && emailIds.isNotEmpty()) {
                EasResult.Error("DELETE_FAILED")
            } else {
                EasResult.Success(deletedCount)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Delete error: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }

    /** Адрес запроса отчёта о прочтении, о котором надо спросить до пометки прочитанным ([ReadReceipts.pendingRequest]). */
    suspend fun pendingReadReceipt(emailId: String): String? {
        val email = emailDao.getEmail(emailId) ?: return null
        return ReadReceipts.pendingRequest(email, folderDao.getFolder(email.folderId)?.type)
    }

    /**
     * Ответ на запрос отчёта о прочтении; письмо помечается прочитанным, отчёт (если согласились)
     * отправляет Exchange. Отказ — SuppressReadReceipt до пометки; не вышло — письмо остаётся
     * непрочитанным и возвращается [ReadReceipts.NOT_DECLINED].
     */
    suspend fun answerReadReceipt(emailId: String, send: Boolean): EasResult<Boolean> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        if (!send && !suppressReadReceipt(email)) return EasResult.Error(ReadReceipts.NOT_DECLINED)
        emailDao.updateMdnSent(emailId, true)
        return markAsRead(emailId, true)
    }

    private suspend fun suppressReadReceipt(email: EmailEntity): Boolean {
        val messageId = email.internetMessageId?.takeIf { it.isNotBlank() } ?: return false
        val folder = folderDao.getFolder(email.folderId) ?: return false
        val client = accountRepo.createEasClient(email.accountId) ?: return false
        return client.suppressReadReceipt(messageId, EwsMailFolder.of(folder.type, folder.displayName))
    }

    // === Вспомогательные методы ===

    /**
     * Отменяет account-уведомление о почте.
     * Нужен для кейса: пользователь открыл письмо из уведомления и прочитал его,
     * но notification со старым заголовком не должен оставаться в шторке.
     */
    private fun cancelNotificationForAccount(accountId: Long) {
        try {
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            val notificationId = com.dedovmosol.iwomail.sync.NotificationHelper.notificationIdForAccount(accountId)
            notificationManager?.cancel(notificationId)
        } catch (e: Exception) {
            android.util.Log.w("EmailOps", "cancelNotificationForAccount failed", e)
        }
    }

    /**
     * Ключ для Sync Change; null — ключа нет ("0": папка ещё не синхронизирована или ждёт полной синхронизации),
     * изменение остаётся локально и уйдёт после неё. Самим брать ключ через Sync(0) нельзя: это сбрасывает состояние
     * синхронизации на сервере. Exchange 2007 после сброса не знает прежних ServerId (Change получает Status 8),
     * а следующая выдача нумерует письма заново — проверено на сервере (LiveServerIdProbeTest).
     */
    private fun usableSyncKey(currentSyncKey: String): String? = currentSyncKey.takeIf { it != "0" && it.isNotBlank() }

    /** Ответ ItemOperations — о письме [email] (тема и дата из того же ответа, [bodyBelongsTo]). */
    private fun bodyBelongsTo(email: EmailEntity, response: com.dedovmosol.iwomail.eas.EmailBodyResult): Boolean =
        bodyBelongsTo(
            email.subject, email.dateReceived,
            response.itemSubject, response.itemDateReceived?.let { EmailSyncService.parseDate(it) } ?: 0L
        )

    /**
     * Тело не сохранено: под ServerId [email] сервер отдаёт другое письмо. [markStale] — папка помечается для полной
     * синхронизации ([EmailSyncService.markServerIdsStale]); не нужно, если запись уже переписала синхронизация.
     */
    private fun itemMismatch(email: EmailEntity, markStale: Boolean = true): EasResult<String> {
        android.util.Log.w("BDY", "Server item under ${email.serverId} is another message, body not saved (stale=$markStale)")
        if (markStale) EmailSyncService.markServerIdsStale(email.folderId)
        return EasResult.Error(RepositoryErrors.ITEM_MISMATCH)
    }

    /**
     * [write] — в транзакции и только если запись [email] всё ещё о том же письме: между загрузкой тела и записью
     * синхронизация могла отдать этот id другому письму ([EmailSyncService.sameServerItem]). false — не записано.
     */
    private suspend fun writeIfSameItem(email: EmailEntity, write: suspend () -> Unit): Boolean =
        database.withTransaction {
            if (emailDao.countSameItem(email.id, email.subject, email.from, email.dateReceived) == 0) {
                false
            } else {
                write()
                true
            }
        }

    private suspend fun getRetrySyncKeyFromDatabase(
        folderId: String,
        failedSyncKey: String
    ): String? {
        val latestSyncKey = folderDao.getFolder(folderId)?.syncKey ?: return null
        return latestSyncKey.takeIf { it.isNotBlank() && it != "0" && it != failedSyncKey }
    }

    private fun isLikelyStaleSyncKeyError(message: String): Boolean {
        return message.contains("INVALID_SYNCKEY", ignoreCase = true) ||
            message.contains("Status=3", ignoreCase = true)
    }

    /**
     * Обновляет счётчики папки с транзакцией для консистентности
     */
    private suspend fun updateFolderCounts(folderId: String) {
        database.withTransaction {
            val totalCount = emailDao.getCountByFolder(folderId)
            val unreadCount = emailDao.getUnreadCount(folderId)
            folderDao.updateCounts(folderId, unreadCount, totalCount)
        }
    }
}

private val CHANGE_STATUS = Regex("Change Status\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val COLLECTION_STATUS = Regex("Collection Status\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * [MS-ASCMD] Sync Status коллекции, при которых повтор того же изменения не поможет: 4 — ошибка протокола (запрос
 * неверен), 6 — ошибка преобразования данных, 7 — конфликт (побеждает сервер). 8 («объект не найден») — не отказ,
 * см. [isRejectingChangeStatus].
 */
private val REJECTING_COLLECTION_STATUSES = setOf(4, 6, 7)

/**
 * Сервер отказал в самом локальном изменении (флаг, «прочитано»): откат. Всё остальное — устаревший SyncKey (3),
 * изменилась иерархия папок (12), сбой сервера (5), HTTP-ошибки, нет связи — повод отправить изменение позже, а не
 * терять его.
 */
internal fun isChangeRejected(message: String): Boolean {
    CHANGE_STATUS.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { return isRejectingChangeStatus(it) }
    val status = COLLECTION_STATUS.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return status in REJECTING_COLLECTION_STATUSES
}

/**
 * Status изменения в ответе Sync. 8 («объект не найден») — не отказ: состояние синхронизации на сервере ещё не знает
 * этот ServerId (идёт полная синхронизация после SyncKey=0 — проверено на Exchange 2007: Change сразу после сброса
 * получает 8). Изменение ждёт и уйдёт позже; если письма на сервере действительно нет, его уберёт синхронизация.
 */
internal fun isRejectingChangeStatus(status: Int): Boolean = status != SYNC_STATUS_OBJECT_NOT_FOUND

private const val SYNC_STATUS_OBJECT_NOT_FOUND = 8

/**
 * Тело из ItemOperations — того письма, что в записи: тема и DateReceived элемента из того же ответа совпадают
 * с записью (тема без учёта регистра и пробелов по краям, дата — в пределах [com.dedovmosol.iwomail.eas.EwsMailMatch.WIDE_WINDOW_MS]).
 * Свойство, которое сервер не прислал (null или 0), не проверяется. Нет — Exchange 2007 после SyncKey=0 отдал этот
 * ServerId другому письму, и тело было бы чужим.
 */
internal fun bodyBelongsTo(localSubject: String, localDateReceived: Long, itemSubject: String?, itemDateReceived: Long): Boolean {
    if (itemSubject != null &&
        !com.dedovmosol.iwomail.eas.EwsMailMatch.subjectsEqual(comparableSubject(localSubject), comparableSubject(itemSubject))
    ) {
        return false
    }
    return itemDateReceived <= 0L || localDateReceived <= 1L ||
        kotlin.math.abs(itemDateReceived - localDateReceived) <= com.dedovmosol.iwomail.eas.EwsMailMatch.WIDE_WINDOW_MS
}

/** Sync подставляет "(No subject)" вместо пустой темы, ItemOperations присылает пустую. */
private fun comparableSubject(subject: String): String = subject.trim().takeUnless { it == "(No subject)" }.orEmpty()

internal fun isOfflineMailError(message: String): Boolean = message.contains("[NET:")
