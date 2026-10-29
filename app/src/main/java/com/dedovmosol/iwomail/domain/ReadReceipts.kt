package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.eas.FolderType

/**
 * Отчёты о прочтении входящих писем на Exchange 2007–2013.
 *
 * Отчёт отправляет сам Exchange, когда письмо с запросом становится прочитанным: через EAS (Sync Change
 * Read) и через EWS (IsRead). Поэтому клиент не собирает своё MDN (оно дублировало бы серверное), а
 * спрашивает до пометки прочитанным: «отправить» — пометить, «нет» — сначала SuppressReadReceipt.
 * `EmailEntity.mdnSent` — пользователь уже ответил.
 */
object ReadReceipts {

    /** Отказаться не удалось — письмо оставлено непрочитанным, иначе сервер отправил бы отчёт. */
    const val NOT_DECLINED = "READ_RECEIPT_NOT_DECLINED"

    /** Свои письма и «Нежелательная почта» — из неё Exchange отчётов не отправляет (ErrorReadReceiptNotPending). */
    private val NO_PROMPT_FOLDERS = setOf(
        FolderType.DRAFTS, FolderType.SENT_ITEMS, FolderType.OUTBOX, FolderType.JUNK_EMAIL
    )

    /**
     * Адрес, на который просят отчёт, если о нём надо спросить; null — не надо. Только непрочитанное
     * письмо: у прочитанного сервер запрос уже обработал (отчёт ушёл или подавлен).
     */
    fun pendingRequest(email: EmailEntity, folderType: Int?): String? {
        if (email.read || email.mdnSent || folderType in NO_PROMPT_FOLDERS) return null
        return email.mdnRequestedBy?.trim()?.takeIf { it.isNotEmpty() }
    }
}
