package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.DraftMode
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.DraftAttachmentRole
import com.dedovmosol.iwomail.data.repository.DraftUpload
import com.dedovmosol.iwomail.data.repository.draftAttachmentRole
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.eas.DraftIds
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.util.EmbeddedImages
import com.dedovmosol.iwomail.util.containsContentId
import com.dedovmosol.iwomail.util.extractDataUrlImages
import com.dedovmosol.iwomail.util.mergeRecipients
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.util.referencedContentIds

/**
 * Сохранение черновика Exchange — одно место для экрана письма и ComposeViewModel (DRY, SRP).
 *
 * - Изменены только текст, тема, получатели или важность — EWS UpdateItem: вложения на сервере не
 *   трогаются (в том числе те, что редактор не смог загрузить), черновик сохраняет свой id. Картинки тела,
 *   уже бывшие на сервере ([Content.knownImageCids]), уходят ссылками на свои cid.
 * - Изменены вложения (UpdateItem их не добавляет и не удаляет), id черновика не определить или
 *   черновика на сервере уже нет — новая версия создаётся, ПОТОМ удаляются прежние (по их id).
 * - Нет связи — правка сохраняется на устройстве и заменит серверный черновик при подключении
 *   (EmailEntity.replacesServerId); прежняя версия на сервере не удаляется, пока новая не загружена.
 * - Сервер отказал — прежняя версия остаётся, пользователь получает причину.
 * - Скрытые копии прежней версии не узнать (черновик из Outlook: EAS их не передаёт, EWS не ответил) —
 *   прежняя версия остаётся рядом с новой: удалить её значило бы потерять получателей.
 *
 * Вложения, которые редактор не загрузил, в новую версию переносятся (скачиваются перед созданием);
 * скачать нельзя при работающей связи — сохранение не выполняется: новая версия была бы без них.
 */
class DraftSaver(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Данные и действия, нужные сохранению (DIP: тесты — без Android, БД и сети). */
    interface Store {
        suspend fun draft(emailId: String): EmailEntity?
        /** Запись, на которую сменилась [emailId], пока черновик был открыт (загружен, перенесён синхронизацией). */
        fun successor(emailId: String): String?
        suspend fun attachments(emailId: String): List<AttachmentEntity>
        /** Байты сохранённого вложения: локальный файл или (при [allowNetwork]) сервер. null — недоступно. */
        suspend fun attachmentBytes(accountId: Long, attachment: AttachmentEntity, allowNetwork: Boolean): ByteArray?
        /** Новый черновик (на сервере; не вышло или [deviceOnly] — на устройстве). null — не сохранён. */
        suspend fun create(account: AccountEntity, draft: NewDraft, deviceOnly: Boolean): String?
        /**
         * UpdateItem серверного черновика (или правка локального) и его локальной копии. [bcc] = null — скрытые
         * копии не трогать; [appendBcc] — скрытые копии сервера не известны: [bcc] добавить к ним.
         */
        suspend fun update(
            account: AccountEntity,
            serverId: String,
            content: Content,
            bcc: String?,
            serverBody: String,
            appendBcc: Boolean = false
        ): EasResult<String>
        /** Удалить черновик на сервере и на устройстве; [excludeServerId] — новый черновик, его не трогать. */
        suspend fun delete(accountId: Long, serverId: String, excludeServerId: String?): EasResult<Boolean>
        /** Убрать черновик только на устройстве (сервер не трогается). */
        suspend fun removeLocally(emailId: String)
        /** Отметка «заменяет» ([DraftIds.replacedMarker]: id прежних версий; null — снять). */
        suspend fun markReplacing(emailId: String, marker: String?)
        suspend fun carryAttachments(rows: List<AttachmentEntity>, toEmailId: String)
        /** Скрытые копии серверного черновика по его id (EAS до 16.0 их не передаёт). null — не узнать. */
        suspend fun serverBcc(accountId: Long, serverId: String): String?
        /** Сервер аккаунта отвечает по TCP: нет — сохранение не удалось из-за связи. */
        suspend fun serverReachable(accountId: Long): Boolean
        suspend fun hasOtherDraftWithSubject(folderId: String, subject: String, exceptIds: Set<String>): Boolean
        suspend fun restore(email: EmailEntity, attachments: List<DraftAttachmentData>)
        /** Фоновая синхронизация не возвращает черновик с этим id (окно между созданием нового и удалением прежнего). */
        fun protect(emailId: String)
        fun unprotect(emailId: String)
        /** Без параллельной загрузки черновиков синхронизацией (иначе локальный черновик ушёл бы на сервер дважды). */
        suspend fun <T> exclusive(accountId: Long, block: suspend () -> T): T
        /** EWS-id единственного серверного черновика с темой [subject] (кроме [excludeServerId]); null — не однозначно. */
        suspend fun findServerDraftBySubject(accountId: Long, subject: String, excludeServerId: String?): String?
    }

    /** Состояние редактора. */
    data class Content(
        val to: String,
        val cc: String,
        val bcc: String,
        /** false — скрытые копии черновика не известны (черновик из Outlook): пустое поле их не стирает. */
        val bccKnown: Boolean,
        val subject: String,
        /** HTML редактора: картинки — data: URL. */
        val body: String,
        val importance: Int,
        val fromEmail: String,
        val fromName: String,
        /** Файловые вложения редактора. */
        val files: List<DraftAttachmentData>,
        /** Набор файловых вложений не менялся с открытия черновика. */
        val filesUnchanged: Boolean,
        /** data URL → Content-ID картинок тела, уже существующих на сервере (загружены при открытии). */
        val knownImageCids: Map<String, String> = emptyMap(),
        /** Вложения черновика, которые редактор не загрузил (id записей), — переносятся в новую версию. */
        val unloadedAttachmentIds: Set<Long> = emptySet()
    )

    /** Черновик для создания. */
    data class NewDraft(
        val to: String,
        val cc: String,
        /** null — скрытые копии не известны (на устройстве: их возьмут с заменяемой версии при загрузке). */
        val bcc: String?,
        val subject: String,
        /** HTML для сервера: картинки — cid:. */
        val serverBody: String,
        /** HTML локальной копии: картинки — data: URL. */
        val localBody: String,
        val importance: Int,
        val fromEmail: String,
        val fromName: String,
        val attachments: List<DraftAttachmentData>
    )

    sealed interface Result {
        /** Сохранён на сервере (в режиме «черновики на устройстве» — на устройстве). */
        data object Saved : Result
        /** Нет связи с сервером: сохранён на устройстве, загрузится при подключении. */
        data object SavedOffline : Result
        /** Сервер не принял новый черновик: он сохранён только на устройстве. */
        data object SavedOnDeviceOnly : Result
        /** Сохранён ([offline] — на устройстве), прежняя версия оставлена: её скрытые копии не узнать. */
        data class SavedKeepingPrevious(val offline: Boolean) : Result
        /** Не сохранён, прежняя версия цела. [reason] — текст ошибки, [ATTACHMENTS_UNAVAILABLE] или [SERVER_REJECTED]. */
        data class Failed(val reason: String?) : Result
    }

    /** Скрытые копии новой версии и прежние версии, которые удалять нельзя (их скрытые копии не узнать). */
    private data class BccPlan(val bcc: String?, val keep: Set<String>)

    suspend fun save(account: AccountEntity, existingId: String?, content: Content): Result =
        store.exclusive(account.id) { saveExclusive(account, existingId, content) }

    private suspend fun saveExclusive(account: AccountEntity, existingId: String?, content: Content): Result {
        val serverMode = account.draftMode != DraftMode.LOCAL.name
        // Запись могла смениться, пока черновик открыт (загружен на сервер, перенесена синхронизацией): правится
        // актуальная, а не создаётся второй черновик.
        val existing = existingId?.let { id -> store.draft(id) ?: store.successor(id)?.let { store.draft(it) } }
        // Отвечает ли сервер (TCP, до 3 с) — когда черновик пойдёт на сервер: без связи сохранение на
        // устройстве сразу, а не после таймаута подключения (до 30 с на адрес).
        val online = (serverMode || (existing != null && !DraftIds.isLocal(existing.serverId))) &&
            store.serverReachable(account.id)
        if (existing == null) return create(account, content, online)

        if (DraftIds.isLocal(existing.serverId)) {
            // Черновик на устройстве при связи — загружается на сервер сейчас, не дожидаясь синхронизации.
            if (serverMode && online) return replace(account, existing, content, online = true)
            // Правка на месте хранит тело как есть (картинки — data: URL); крупнее предела БД — заново: тело с cid:,
            // картинки — файлами вложений. Черновик, созданный без подтверждённого id, — тоже заново: синхронизация
            // его не загружает (он уже на сервере) и убирает, когда придёт серверная копия, — правка пропала бы.
            if (content.filesUnchanged && content.body.length <= DraftUpload.MAX_LOCAL_BODY_CHARS &&
                !DraftIds.isUnconfirmed(existing.serverId)
            ) {
                // Скрытые копии не известны (правка черновика из Outlook без связи): пустое поле их не стирает —
                // их возьмут с заменяемой версии при загрузке. Введённые вдобавок не объединить: заменяемые остаются.
                val keepPrevious = !content.bccKnown && content.bcc.isNotBlank() && existing.replacesServerId != null
                val bcc = if (content.bccKnown || content.bcc.isNotBlank()) content.bcc else null
                val updated = store.update(account, existing.serverId, content, bcc, content.body)
                if (updated is EasResult.Error) return Result.Failed(updated.message)
                if (keepPrevious) {
                    store.markReplacing(existing.id, null)
                    return Result.SavedKeepingPrevious(offline = serverMode)
                }
                return if (serverMode) Result.SavedOffline else Result.Saved
            }
            return replace(account, existing, content, online)
        }

        if (online && content.filesUnchanged) {
            // Картинки, уже бывшие на сервере, — ссылками на свои cid; новых быть не должно.
            val (serverBody, newImages) = extractDataUrlImages(content.body, content.knownImageCids) { "new$it" }
            val referenced = referencedContentIds(serverBody)
            val inlineUnchanged = newImages.isEmpty() &&
                content.knownImageCids.values.all { containsContentId(referenced, it) }
            if (inlineUnchanged) {
                // Скрытые копии черновика из Outlook (EAS их не передаёт) не известны: пустое поле их не трогает,
                // введённые — объединяются с серверными, а не узнать серверные — добавляются к ним.
                var bcc: String? = content.bcc
                var appendBcc = false
                if (!content.bccKnown) {
                    if (content.bcc.isBlank()) {
                        bcc = null
                    } else {
                        val serverBcc = store.serverBcc(account.id, existing.serverId)
                        if (serverBcc != null) bcc = mergeRecipients(content.bcc, serverBcc) else appendBcc = true
                    }
                }
                if (store.update(account, existing.serverId, content, bcc, serverBody, appendBcc) is EasResult.Success) {
                    return Result.Saved
                }
                // Не обновлён (id не определить, черновика на сервере нет) — сохраняем заново.
            }
        }
        return replace(account, existing, content, online)
    }

    private suspend fun create(account: AccountEntity, content: Content, online: Boolean): Result {
        val (serverBody, inline) = DraftUpload.inlineImages(content.body, clock())
        val id = store.create(account, newDraft(content, content.bcc, serverBody, content.files + inline), deviceOnly = !online)
            ?: return Result.Failed(null)
        if (!DraftIds.isLocal(id) || DraftIds.isUnconfirmed(id)) return Result.Saved
        // Связь была, а черновик остался на устройстве: сервер отказал — или связь пропала (повторная проба).
        return deviceResult(account, online && store.serverReachable(account.id))
    }

    private suspend fun replace(account: AccountEntity, old: EmailEntity, content: Content, online: Boolean): Result {
        val oldIsLocal = DraftIds.isLocal(old.serverId)
        val serverMode = account.draftMode != DraftMode.LOCAL.name

        // Вложения, которые редактор не загрузил, переносятся в новую версию (без связи — только с устройства:
        // попытка скачать заняла бы таймаут подключения на каждое).
        val restored = mutableListOf<DraftAttachmentData>()
        val missing = mutableListOf<AttachmentEntity>()
        if (content.unloadedAttachmentIds.isNotEmpty()) {
            val embedded = EmbeddedImages(content.body)
            for (row in store.attachments(old.id).filter { it.id in content.unloadedAttachmentIds }) {
                val bytes = store.attachmentBytes(account.id, row, allowNetwork = online)
                if (bytes == null) {
                    missing += row
                    continue
                }
                val inline = draftAttachmentRole(content.body, row.contentId) == DraftAttachmentRole.INLINE
                // Помеченная inline без ссылки, чья картинка уже в теле (те же байты), — не дублируем.
                if (!inline && row.isInline && embedded.dataUrlOf(bytes) != null) continue
                restored += DraftAttachmentData(
                    name = row.displayName,
                    mimeType = row.contentType,
                    data = bytes,
                    isInline = inline,
                    contentId = row.contentId?.let(::normalizeContentId)?.takeIf { inline }
                )
            }
        }

        // Прежние версии на сервере, которые заменяет новая: эта (если серверная) и не удалённые раньше.
        val superseded = (listOfNotNull(old.serverId.takeUnless { oldIsLocal }) + DraftIds.replacedIds(old.replacesServerId))
            .distinct()
        val plan = planBcc(account, content, superseded, online)
        val replaced = superseded.filter { it !in plan.keep }
        val keepsOld = old.serverId in plan.keep

        if (missing.isNotEmpty()) {
            // Со связью новая версия на сервере была бы без них, а прежняя — удалена.
            if (!serverMode || online) return Result.Failed(ATTACHMENTS_UNAVAILABLE)
            return keepOnDevice(account, old, content, plan, replaced, restored, missing)
        }

        if (!keepsOld) store.protect(old.id)
        val (serverBody, inline) = DraftUpload.inlineImages(content.body, clock())
        val attachments = content.files + inline + restored
        val newId = store.create(account, newDraft(content, plan.bcc, serverBody, attachments), deviceOnly = !online)
        if (newId == null) {
            store.unprotect(old.id)
            return Result.Failed(null)
        }
        val newEmailId = "${account.id}_$newId"
        val kept = plan.keep.isNotEmpty()

        if (DraftIds.isLocal(newId) && !DraftIds.isUnconfirmed(newId)) {
            return when {
                oldIsLocal -> {
                    // Локальный заменил локальный (на сервер не попал ни тот, ни другой).
                    store.markReplacing(newEmailId, DraftIds.replacedMarker(replaced))
                    store.removeLocally(old.id)
                    if (kept) Result.SavedKeepingPrevious(offline = serverMode) else deviceResult(account, online)
                }
                // Режим «черновики на устройстве»: серверный черновик заменён локальным; прежние версии удаляются
                // сейчас (есть связь) или синхронизацией (без связи — не ждём таймаутов).
                !serverMode -> {
                    val failed = if (online) {
                        replaced.filter { store.delete(account.id, it, excludeServerId = null) !is EasResult.Success }
                    } else {
                        replaced
                    }
                    store.markReplacing(newEmailId, DraftIds.replacedMarker(failed))
                    if (keepsOld) store.unprotect(old.id) else if (old.serverId in failed) store.removeLocally(old.id)
                    if (kept) Result.SavedKeepingPrevious(offline = false) else Result.Saved
                }
                // Сервер отвечает, но не принял: прежняя версия остаётся на сервере, копия на устройстве не нужна.
                online && store.serverReachable(account.id) -> {
                    store.delete(account.id, newId, excludeServerId = null)
                    store.unprotect(old.id)
                    Result.Failed(SERVER_REJECTED)
                }
                // Нет связи: копия на устройстве заменит серверный черновик при подключении.
                else -> {
                    store.markReplacing(newEmailId, DraftIds.replacedMarker(replaced))
                    if (keepsOld) store.unprotect(old.id) else store.removeLocally(old.id)
                    if (kept) Result.SavedKeepingPrevious(offline = true) else Result.SavedOffline
                }
            }
        }

        // Новая версия на сервере. Прежние — удаляются по id; не вышло — удалит синхронизация. Отметка ставится до
        // удалений и снимается по мере успеха. Прежняя версия без id (сервер подтвердил создание, id не вернул) —
        // по однозначной теме, кроме новой.
        val unconfirmedOld = if (DraftIds.isUnconfirmed(old.serverId)) {
            store.findServerDraftBySubject(account.id, old.subject, newId.takeUnless { DraftIds.isLocal(it) })
        } else {
            null
        }
        val pending = (replaced + listOfNotNull(unconfirmedOld)).distinct().toMutableList()
        if (pending.isNotEmpty()) store.markReplacing(newEmailId, DraftIds.replacedMarker(pending))
        for (serverId in pending.toList()) {
            if (store.delete(account.id, serverId, excludeServerId = newId) is EasResult.Success) pending -= serverId
        }
        store.markReplacing(newEmailId, DraftIds.replacedMarker(pending))
        if (oldIsLocal || old.serverId in pending) store.removeLocally(old.id)
        if (keepsOld) store.unprotect(old.id)

        // Запись новой версии должна остаться (её могла убрать фоновая синхронизация).
        if (store.draft(newEmailId) == null &&
            !store.hasOtherDraftWithSubject(old.folderId, content.subject, setOf(old.id))
        ) {
            val record = recordOf(account, old.folderId, newId, content, plan.bcc, attachments.isNotEmpty(), DraftIds.replacedMarker(pending))
            store.restore(record, attachments)
        }
        return if (kept) Result.SavedKeepingPrevious(offline = false) else Result.Saved
    }

    /**
     * Скрытые копии черновика из Outlook (EAS их не передаёт) — с прежних версий на сервере; чьи не узнать, те
     * версии остаются. Без связи на устройстве они остаются неизвестными (null) — их возьмут при загрузке; введённые
     * вдобавок к неизвестным не объединить — прежние версии остаются.
     */
    private suspend fun planBcc(account: AccountEntity, content: Content, superseded: List<String>, online: Boolean): BccPlan {
        if (content.bccKnown || superseded.isEmpty()) return BccPlan(content.bcc, emptySet())
        if (!online) {
            return if (content.bcc.isBlank()) BccPlan(null, emptySet()) else BccPlan(content.bcc, superseded.toSet())
        }
        var bcc = content.bcc
        val keep = HashSet<String>()
        for (serverId in superseded) {
            val serverBcc = store.serverBcc(account.id, serverId)
            if (serverBcc == null) keep += serverId else bcc = mergeRecipients(bcc, serverBcc)
        }
        return BccPlan(bcc, keep)
    }

    /** Без связи и с вложениями, которые не скачать: правка — на устройстве, вложения — ссылками на сервер. */
    private suspend fun keepOnDevice(
        account: AccountEntity,
        old: EmailEntity,
        content: Content,
        plan: BccPlan,
        replaced: List<String>,
        restored: List<DraftAttachmentData>,
        missing: List<AttachmentEntity>
    ): Result {
        val (serverBody, inline) = DraftUpload.inlineImages(content.body, clock())
        val newId = store.create(account, newDraft(content, plan.bcc, serverBody, content.files + inline + restored), deviceOnly = true)
            ?: return Result.Failed(null)
        val newEmailId = "${account.id}_$newId"
        store.carryAttachments(missing, newEmailId)
        store.markReplacing(newEmailId, DraftIds.replacedMarker(replaced))
        if (old.serverId !in plan.keep) {
            store.protect(old.id)
            store.removeLocally(old.id)
        }
        return if (plan.keep.isEmpty()) Result.SavedOffline else Result.SavedKeepingPrevious(offline = true)
    }

    private fun deviceResult(account: AccountEntity, online: Boolean): Result = when {
        account.draftMode == DraftMode.LOCAL.name -> Result.Saved
        online -> Result.SavedOnDeviceOnly
        else -> Result.SavedOffline
    }

    private fun newDraft(content: Content, bcc: String?, serverBody: String, attachments: List<DraftAttachmentData>) = NewDraft(
        to = content.to,
        cc = content.cc,
        bcc = bcc,
        subject = content.subject,
        serverBody = serverBody,
        localBody = content.body,
        importance = content.importance,
        fromEmail = content.fromEmail,
        fromName = content.fromName,
        attachments = attachments
    )

    private fun recordOf(
        account: AccountEntity,
        folderId: String,
        serverId: String,
        content: Content,
        bcc: String?,
        hasAttachments: Boolean,
        replaces: String?
    ) = EmailEntity(
        id = "${account.id}_$serverId",
        accountId = account.id,
        folderId = folderId,
        serverId = serverId,
        from = content.fromEmail,
        fromName = content.fromName,
        to = content.to,
        cc = content.cc,
        subject = content.subject,
        preview = com.dedovmosol.iwomail.util.stripHtml(content.body).take(150),
        body = if (content.body.length <= DraftUpload.MAX_LOCAL_BODY_CHARS) content.body else "",
        bodyType = 2,
        dateReceived = clock(),
        read = true,
        importance = content.importance,
        hasAttachments = hasAttachments,
        bcc = bcc,
        replacesServerId = replaces
    )

    companion object {
        /** Не все вложения черновика удалось загрузить, а связь есть: сохранение удалило бы их. */
        const val ATTACHMENTS_UNAVAILABLE = "DRAFT_ATTACHMENTS_UNAVAILABLE"

        /** Сервер не принял новую версию черновика (прежняя цела). */
        const val SERVER_REJECTED = "DRAFT_SERVER_REJECTED"
    }
}
