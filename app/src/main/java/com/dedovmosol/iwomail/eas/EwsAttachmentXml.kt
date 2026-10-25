package com.dedovmosol.iwomail.eas

import android.util.Base64

/**
 * `t:FileAttachment` для EWS CreateAttachment — общий для черновиков писем и событий календаря (DRY).
 *
 * Порядок дочерних элементов строго по XSD (AttachmentType → FileAttachmentType): Name, ContentType,
 * ContentId, …, IsInline, …, Content. IsInline появился в Exchange 2010: с RequestServerVersion
 * Exchange2007_SP1 элемент даёт ошибку схемы, и inline-картинку тогда обозначает только ContentId,
 * на который ссылается `cid:` в HTML-теле.
 */
internal object EwsAttachmentXml {

    /** RequestServerVersion для CreateAttachment: IsInline допустим с Exchange 2010 (EAS 14+). */
    fun requestVersion(easMajorVersion: Int): String = if (easMajorVersion >= 14) "Exchange2010" else "Exchange2007_SP1"

    fun supportsIsInline(exchangeVersion: String): Boolean = !exchangeVersion.contains("2007")

    /** ContentId без угловых скобок и префикса `cid:` — так на него ссылается HTML (RFC 2392). */
    fun normalizedContentId(att: DraftAttachmentData): String? {
        val raw = att.contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleaned = raw.removePrefix("<").removeSuffix(">")
        return (if (cleaned.startsWith("cid:", ignoreCase = true)) cleaned.substring(4) else cleaned).takeIf { it.isNotBlank() }
    }

    fun appendFileAttachment(
        sb: StringBuilder,
        att: DraftAttachmentData,
        exchangeVersion: String,
        escapeXml: (String) -> String
    ) {
        sb.append("<t:FileAttachment>")
        sb.append("<t:Name>").append(escapeXml(att.name)).append("</t:Name>")
        sb.append("<t:ContentType>").append(escapeXml(att.mimeType)).append("</t:ContentType>")
        normalizedContentId(att)?.let { sb.append("<t:ContentId>").append(escapeXml(it)).append("</t:ContentId>") }
        if (att.isInline && supportsIsInline(exchangeVersion)) sb.append("<t:IsInline>true</t:IsInline>")
        sb.append("<t:Content>").append(Base64.encodeToString(att.data, Base64.NO_WRAP)).append("</t:Content>")
        sb.append("</t:FileAttachment>")
    }
}
