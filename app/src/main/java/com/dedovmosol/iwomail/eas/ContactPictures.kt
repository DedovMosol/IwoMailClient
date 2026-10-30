package com.dedovmosol.iwomail.eas

/**
 * Фото контактов из глобальной адресной книги — EAS ResolveRecipients с Options/Picture ([MS-ASCMD] 2.2.1.15). Элементы
 * Picture, MaxSize, MaxPictures и Data есть только в протоколе 14.1 (Exchange 2010 SP1 и новее): на Exchange 2007 и 2010
 * без SP1 запрос не отправляется вовсе — ни лишнего трафика, ни ошибок протокола.
 */
object ContactPictures {

    /** Наибольший размер фото, который разрешает протокол (MaxSize ≤ 100 КБ). */
    const val MAX_SIZE_BYTES = 102_400

    /** Адресов в одном запросе (схема допускает 100; 20 фото по ≤100 КБ — ответ до 2 МБ). */
    const val BATCH_SIZE = 20

    private const val STATUS_OK = 1
    private const val PICTURE_OK = 1
    private const val PICTURE_LIMIT_REACHED = 175

    /** Итог запроса фото для набора адресов. */
    sealed interface Lookup {
        /** Сервер не поддерживает фото (протокол ниже 14.1) — спрашивать этот аккаунт больше не нужно. */
        data object Unsupported : Lookup
        /** Временная ошибка (нет связи, сервер занят) — спросить позже. */
        data class Failed(val reason: String) : Lookup
        /** По адресу: байты фото или null — фото нет. Адресов, о которых сервер не ответил, в словаре нет. */
        data class Found(val photos: Map<String, ByteArray?>) : Lookup
    }

    /** Протокол [easVersion] (согласованный с сервером) поддерживает фото: 14.1 и новее. */
    fun supported(easVersion: String): Boolean {
        val major = easVersion.substringBefore('.').toIntOrNull() ?: return false
        val minor = easVersion.substringAfter('.', "0").toIntOrNull() ?: 0
        return major > 14 || (major == 14 && minor >= 1)
    }

    /**
     * Фото по адресам (до [BATCH_SIZE]). [version] — согласованная с сервером версия протокола (при необходимости —
     * после определения); ниже 14.1 — [Lookup.Unsupported] без запроса. [execute] — команда ResolveRecipients с
     * разбором ответа через [parse].
     */
    suspend fun lookup(
        addresses: List<String>,
        version: suspend () -> EasResult<String>,
        execute: suspend (request: String) -> EasResult<Map<String, ByteArray?>?>
    ): Lookup {
        if (addresses.isEmpty()) return Lookup.Found(emptyMap())
        val easVersion = when (val detected = version()) {
            is EasResult.Success -> detected.data
            is EasResult.Error -> return Lookup.Failed(detected.message)
        }
        if (!supported(easVersion)) return Lookup.Unsupported
        return when (val result = execute(request(addresses.take(BATCH_SIZE), XmlUtils::escape))) {
            is EasResult.Success -> result.data?.let { Lookup.Found(it) } ?: Lookup.Failed("ResolveRecipients status")
            is EasResult.Error -> Lookup.Failed(result.message)
        }
    }

    /** Запрос ResolveRecipients: по одному To на адрес, одно точное совпадение, одно фото на адрес. */
    fun request(addresses: List<String>, escape: (String) -> String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><ResolveRecipients xmlns="ResolveRecipients">""")
        for (address in addresses) append("<To>").append(escape(address)).append("</To>")
        append("<Options><MaxAmbiguousRecipients>1</MaxAmbiguousRecipients>")
        append("<Picture><MaxSize>").append(MAX_SIZE_BYTES).append("</MaxSize><MaxPictures>1</MaxPictures></Picture>")
        append("</Options></ResolveRecipients>")
    }

    /**
     * Ответ ResolveRecipients → фото по запрошенному адресу (в нижнем регистре). null — общий Status ответа не 1 (ошибка
     * протокола или сервера). Нет фото (Status 173, 174, адрес не найден или неоднозначен) — null в словаре; лимит
     * MaxPictures (175) — адреса нет в словаре: спросим снова.
     */
    fun parse(xml: String): Map<String, ByteArray?>? {
        val status = XmlUtils.extractTagValue(xml.substringBefore("<Response"), "Status")?.trim()?.toIntOrNull()
        if (status != STATUS_OK) return null
        val result = HashMap<String, ByteArray?>()
        for (response in XmlUtils.extractTopLevelBlocks(xml, "Response")) {
            val to = XmlUtils.extractTagValue(response, "To")?.let(XmlUtils::unescape)?.trim()?.lowercase() ?: continue
            if (to.isEmpty()) continue
            val head = response.substring(0, RECIPIENT_START.find(response)?.range?.first ?: response.length)
            val responseStatus = XmlUtils.extractTagValue(head, "Status")?.trim()?.toIntOrNull()
            val recipients = XmlUtils.extractTopLevelBlocks(response, "Recipient")
            // Совпадение по адресу; иначе — единственный найденный (отправитель писал с дополнительного адреса ящика)
            val recipient = recipients.firstOrNull { address(it) == to }
                ?: recipients.singleOrNull()?.takeIf { responseStatus == STATUS_OK }
            if (recipient == null) {
                result[to] = null
                continue
            }
            val picture = XmlUtils.extractTagValue(recipient, "Picture")
            val pictureStatus = picture?.let { XmlUtils.extractTagValue(it, "Status")?.trim()?.toIntOrNull() }
            when {
                pictureStatus == PICTURE_LIMIT_REACHED -> Unit
                pictureStatus == PICTURE_OK -> result[to] = photoBytes(picture)
                else -> result[to] = null
            }
        }
        return result
    }

    private fun address(recipient: String): String? =
        XmlUtils.extractTagValue(recipient, "EmailAddress")?.let(XmlUtils::unescape)?.trim()?.lowercase()

    /** Data — base64 (разбор WBXML кодирует так и OPAQUE); пусто, не base64 или больше лимита — фото нет. */
    private fun photoBytes(picture: String): ByteArray? {
        val data = XmlUtils.extractTagValue(picture, "Data")?.filterNot(Char::isWhitespace)?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = try {
            java.util.Base64.getDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_SIZE_BYTES }
    }

    private val RECIPIENT_START = Regex("""<Recipient[\s>/]""")
}
