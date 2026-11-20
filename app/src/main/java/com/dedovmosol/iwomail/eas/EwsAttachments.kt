package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.util.normalizeContentId

/**
 * Вложения письма в EWS: запросы и выбор вложения, соответствующего EAS-вложению.
 *
 * EAS FileReference в EWS AttachmentId не переводится ([MS-ASCMD]), поэтому вложение ищется по имени.
 * Проверено на Exchange 2007 SP2 (output_EasProbe_att2.txt): EWS `t:Name` совпадает с EAS DisplayName,
 * `t:ContentId` — с EAS ContentId; `t:Size` и `t:IsInline` сервер 2007 не отдаёт (появились в 2010), а
 * EstimatedDataSize EAS отличается от размера файла на десятки байт — размер для сопоставления не годится.
 * Неоднозначное совпадение — отказ: чужой файл хуже ошибки.
 */
internal object EwsAttachments {

    data class FileAttachment(val id: String, val name: String, val contentId: String?, val contentType: String? = null)

    private val FILE_ATTACHMENT = Regex("<(?:t:)?FileAttachment>(.*?)</(?:t:)?FileAttachment>", RegexOption.DOT_MATCHES_ALL)
    private val ATTACHMENT_ID = Regex("<(?:t:)?AttachmentId\\b[^>]*\\bId=\"([^\"]+)\"")
    private val NAME = Regex("<(?:t:)?Name>(.*?)</(?:t:)?Name>", RegexOption.DOT_MATCHES_ALL)
    private val CONTENT_TYPE = Regex("<(?:t:)?ContentType>([^<]*)</(?:t:)?ContentType>")
    private val CONTENT_ID = Regex("<(?:t:)?ContentId>(.*?)</(?:t:)?ContentId>", RegexOption.DOT_MATCHES_ALL)

    private fun envelope(body: String) = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header><t:RequestServerVersion Version="Exchange2007_SP1"/></soap:Header>
    <soap:Body>$body</soap:Body>
</soap:Envelope>"""

    fun listRequest(itemId: String): String = envelope(
        "<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
            "<t:FieldURI FieldURI=\"item:Attachments\"/></t:AdditionalProperties></m:ItemShape>" +
            "<m:ItemIds><t:ItemId Id=\"${XmlUtils.escape(itemId)}\"/></m:ItemIds></m:GetItem>"
    )

    fun contentRequest(attachmentId: String): String = envelope(
        "<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"${XmlUtils.escape(attachmentId)}\"/></m:AttachmentIds></m:GetAttachment>"
    )

    /** Только файловые вложения: вложенное письмо (ItemAttachment) EAS отдаёт иначе, Content у него нет. */
    fun parse(getItemXml: String): List<FileAttachment> = FILE_ATTACHMENT.findAll(getItemXml).mapNotNull { m ->
        val xml = m.groupValues[1]
        val id = ATTACHMENT_ID.find(xml)?.groupValues?.get(1) ?: return@mapNotNull null
        FileAttachment(
            id = XmlUtils.unescape(id),
            name = XmlUtils.unescape(NAME.find(xml)?.groupValues?.get(1).orEmpty()),
            contentId = CONTENT_ID.find(xml)?.groupValues?.get(1)?.let(XmlUtils::unescape),
            contentType = CONTENT_TYPE.find(xml)?.groupValues?.get(1)?.let(XmlUtils::unescape)
        )
    }.toList()

    /** Unlike file download, auto-display requires an unambiguous Content-ID, not just a name. */
    fun matchInline(attachments: List<FileAttachment>, cid: String): FileAttachment? =
        attachments.singleOrNull { att -> att.contentId?.let { normalizeContentId(it).equals(cid, ignoreCase = true) } == true }
            ?.takeIf { att ->
                val mime = com.dedovmosol.iwomail.util.attachmentMimeType(att.contentType, att.name)
                mime.startsWith("image/", ignoreCase = true) || mime == "application/octet-stream"
            }

    /**
     * Вложение для EAS-вложения с [name] и [contentId]: единственное с тем же именем; несколько — уточнение
     * по Content-ID; без точного совпадения имени — без учёта регистра и пробелов по краям. Иначе null.
     */
    fun match(attachments: List<FileAttachment>, name: String, contentId: String?): FileAttachment? {
        fun narrow(candidates: List<FileAttachment>): FileAttachment? {
            candidates.singleOrNull()?.let { return it }
            if (candidates.isEmpty()) return null
            val wanted = contentId?.let { normalizeContentId(it) }?.takeIf { it.isNotEmpty() } ?: return null
            return candidates.filter { c -> c.contentId?.let { normalizeContentId(it) } == wanted }.singleOrNull()
        }
        if (name.isBlank()) return null
        val exact = attachments.filter { it.name == name }
        if (exact.isNotEmpty()) return narrow(exact)
        return narrow(attachments.filter { it.name.trim().equals(name.trim(), ignoreCase = true) })
    }
}
