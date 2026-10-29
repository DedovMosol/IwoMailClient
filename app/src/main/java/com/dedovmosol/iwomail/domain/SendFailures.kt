package com.dedovmosol.iwomail.domain

import android.content.Context
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
 * Письмо, которое не удалось отправить, — в Черновики (с вложениями и картинками в тексте), чтобы его можно было
 * исправить и отправить заново. true — сохранено.
 */
suspend fun saveUnsentAsDraft(context: Context, accountId: Long, mail: OutgoingMail): Boolean {
    val account = RepositoryProvider.getAccountRepository(context).getAccount(accountId) ?: return false
    val (serverBody, inline) = DraftUpload.inlineImages(mail.body, System.currentTimeMillis())
    val files = mail.attachments.map { (name, mimeType, bytes) -> DraftAttachmentData(name = name, mimeType = mimeType, data = bytes) }
    val attachments = files + inline
    return RepositoryProvider.getMailRepository(context).saveDraft(
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
    ) != null
}
