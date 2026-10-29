package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult

/**
 * Письмо к отправке — одно описание для немедленной, отложенной и отправки из очереди (DRY): раньше у каждой были
 * свои поля, и отложенная пересылка теряла исходное письмо, а очередь — важность и отчёт о доставке.
 */
data class OutgoingMail(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    /** Имя, MIME-тип, байты. */
    val attachments: List<Triple<String, String, ByteArray>> = emptyList(),
    val importance: Int = 1,
    val requestReadReceipt: Boolean = false,
    val requestDeliveryReceipt: Boolean = false,
    /** Пересылка: папка и ServerId исходного письма (SmartForward — сервер добавит его тело и вложения). */
    val forwardFolderId: String? = null,
    val forwardItemId: String? = null
)

/**
 * Отправка письма. Пересылка — SmartForward (MS-ASCMD 2.2.1.19); сервер её не принял — обычной отправкой (без
 * вложений исходного письма: лучше доставить текст, чем не доставить ничего). [stableClientId] — один на все повторы
 * отправки (EAS 14+ не отправит письмо с тем же ClientId второй раз).
 */
suspend fun EasClient.send(mail: OutgoingMail, stableClientId: String? = null): EasResult<Boolean> {
    val folderId = mail.forwardFolderId
    val itemId = mail.forwardItemId
    if (folderId != null && itemId != null) {
        val forwarded = if (mail.attachments.isEmpty()) {
            smartForward(
                to = mail.to, subject = mail.subject, body = mail.body, cc = mail.cc, bcc = mail.bcc,
                collectionId = folderId, itemId = itemId, importance = mail.importance,
                requestReadReceipt = mail.requestReadReceipt, requestDeliveryReceipt = mail.requestDeliveryReceipt,
                stableClientId = stableClientId
            )
        } else {
            smartForwardWithAttachments(
                to = mail.to, subject = mail.subject, body = mail.body, cc = mail.cc, bcc = mail.bcc,
                attachments = mail.attachments, collectionId = folderId, itemId = itemId,
                requestReadReceipt = mail.requestReadReceipt, requestDeliveryReceipt = mail.requestDeliveryReceipt,
                importance = mail.importance, stableClientId = stableClientId
            )
        }
        if (forwarded is EasResult.Success) return forwarded
        val error = (forwarded as EasResult.Error).message
        // Обрыв связи: сервер мог пересылку и принять — обычная отправка следом продублировала бы письмо (на EAS 12.x
        // ClientId не защищает). Повтор той же пересылки — у вызывающего.
        if (com.dedovmosol.iwomail.network.ConnectionFailure.fromMessage(error)?.connectionLevel == true) return forwarded
        android.util.Log.w("OutgoingMail", "SmartForward failed: $error, falling back to SendMail")
    }
    return if (mail.attachments.isEmpty()) {
        sendMail(
            to = mail.to, subject = mail.subject, body = mail.body, cc = mail.cc, bcc = mail.bcc,
            importance = mail.importance, requestReadReceipt = mail.requestReadReceipt,
            requestDeliveryReceipt = mail.requestDeliveryReceipt, stableClientId = stableClientId
        )
    } else {
        sendMailWithAttachments(
            to = mail.to, subject = mail.subject, body = mail.body, cc = mail.cc, bcc = mail.bcc,
            attachments = mail.attachments, requestReadReceipt = mail.requestReadReceipt,
            requestDeliveryReceipt = mail.requestDeliveryReceipt, importance = mail.importance,
            stableClientId = stableClientId
        )
    }
}
