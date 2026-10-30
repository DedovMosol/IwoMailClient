package com.dedovmosol.iwomail.domain

import android.content.Context
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.DraftUpload
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.network.ConnectionFailure

/**
 * Ошибка отправки временная — повтор может помочь: нет связи или сервер перегружен (HTTP 502/503/504, 429). Остальное
 * (неверный адрес, письмо слишком большое, запрет политики, неверный пароль) повтор не исправит — повторять бесконечно,
 * не сообщив пользователю, значило бы потерять письмо незаметно.
 */
fun isRetryableSendError(message: String): Boolean {
    if (ConnectionFailure.fromMessage(message)?.connectionLevel == true) return true
    val http = HTTP_CODE.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return http == 429 || http in 502..504
}

private val HTTP_CODE = Regex("""HTTP (\d{3})""")

/**
 * Письмо, которое не удалось или передумали отправлять, — в Черновики (с вложениями и картинками в тексте), чтобы его
 * можно было исправить и отправить заново. id черновика (EmailEntity.id) или null — не сохранено.
 */
suspend fun saveUnsentAsDraft(context: Context, accountId: Long, mail: OutgoingMail): String? {
    val account = RepositoryProvider.getAccountRepository(context).getAccount(accountId) ?: return null
    val (serverBody, inline) = DraftUpload.inlineImages(mail.body, System.currentTimeMillis())
    val files = mail.attachments.map { (name, mimeType, bytes) -> DraftAttachmentData(name = name, mimeType = mimeType, data = bytes) }
    val attachments = files + inline
    val serverId = RepositoryProvider.getMailRepository(context).saveDraft(
        accountId = accountId,
        to = mail.to,
        cc = mail.cc,
        bcc = mail.bcc,
        subject = mail.subject,
        serverBody = serverBody,
        localBody = mail.body,
        fromEmail = account.email,
        fromName = account.displayName,
        hasAttachments = attachments.isNotEmpty(),
        attachmentFiles = attachments,
        importance = mail.importance
    ) ?: return null
    return "${accountId}_$serverId"
}

/**
 * Письмо — в Черновики вместо черновика [replacesDraftId], из которого его отправляли: прежняя версия (до последней
 * правки) удаляется, если новая сохранена — черновик один, а не два. id нового черновика или null.
 */
suspend fun returnToDrafts(context: Context, accountId: Long, mail: OutgoingMail, replacesDraftId: String?): String? {
    val draftId = saveUnsentAsDraft(context, accountId, mail) ?: return null
    replacesDraftId?.takeIf { it != draftId }?.let { deleteDraftQuietly(context, it) }
    return draftId
}

/**
 * Запланированное письмо, которое не уйдёт (отменили или не отправилось), — в Черновики. Черновик, из которого его
 * запланировали, на месте и с тех пор не менялся ([draftStamp]) — он и есть это письмо, копия не нужна. true — письмо
 * в Черновиках.
 */
suspend fun keepScheduledAsDraft(
    context: Context,
    accountId: Long,
    mail: OutgoingMail,
    draftId: String?,
    draftStamp: Long
): Boolean {
    val draft = draftId?.let { MailDatabase.getInstance(context).emailDao().getEmail(it) }
    if (draft != null && draft.dateReceived == draftStamp) return true
    return saveUnsentAsDraft(context, accountId, mail) != null
}

/** Удалить черновик [draftId] на сервере и в приложении; ошибки — в журнал (письмо уже отправлено или сохранено). */
suspend fun deleteDraftQuietly(context: Context, draftId: String) {
    try {
        val draft = MailDatabase.getInstance(context).emailDao().getEmail(draftId) ?: return
        RepositoryProvider.getMailRepository(context).deleteDraft(draft.accountId, draft.serverId)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("SendFailures", "Draft cleanup failed: ${e.message}")
    }
}
