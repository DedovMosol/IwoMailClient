package com.dedovmosol.iwomail.util

/**
 * Inline-картинки HTML-тел (`<img src="cid:…">` ↔ data URL) — общая логика писем и календаря (DRY).
 *
 * Content-ID приходит в разных видах: с угловыми скобками (`<image001.png@01D9…>` — заголовок
 * MIME/EWS) и без них (ссылка `cid:` в HTML, RFC 2392). Сравнение всегда по нормализованному виду.
 */

/** Токен ссылки `cid:` до кавычки/пробела/`>`/`)` (последнее — `url(cid:…)` в CSS). */
private val CID_TOKEN_REGEX = Regex("cid:([^\"'\\s>)<]+)")

/** Content-ID без угловых скобок и пробелов по краям. */
fun normalizeContentId(contentId: String): String = contentId.trim().removeSurrounding("<", ">").trim()

/** Нормализованные Content-ID, на которые ссылается HTML (`cid:` в src/href/CSS). */
fun referencedContentIds(html: String): Set<String> {
    if (!html.contains("cid:")) return emptySet()
    return CID_TOKEN_REGEX.findAll(html).map { normalizeContentId(decodeCidToken(it.groupValues[1])) }.toSet()
}

/**
 * Заменяет ссылки `cid:` на значения из [inlineImages] (обычно data URL). Ключи — Content-ID в любом
 * виде. Замена потокенная: `cid:img1` не задевает `cid:img10` (прежняя посимвольная замена задевала).
 * Ссылки без найденной картинки остаются как есть.
 */
fun replaceInlineCids(html: String, inlineImages: Map<String, String>): String {
    if (inlineImages.isEmpty() || !html.contains("cid:")) return html
    val byId = HashMap<String, String>(inlineImages.size * 2)
    for ((cid, value) in inlineImages) byId.putIfAbsent(normalizeContentId(cid), value)
    return CID_TOKEN_REGEX.replace(html) { match ->
        val token = match.groupValues[1]
        byId[normalizeContentId(token)] ?: byId[normalizeContentId(decodeCidToken(token))] ?: match.value
    }
}

/** Картинка тела (data URL), ставшая inline-вложением с [contentId]. */
class InlineDataImage(val contentId: String, val mimeType: String, val bytes: ByteArray)

/** `src="data:<mime>;base64,<data>"` — так картинки сериализует contenteditable-редактор. */
private val DATA_URL_SRC_REGEX = Regex("""src\s*=\s*"data:([^;"]+);base64,([^"]+)"""")

/**
 * Обратная к [replaceInlineCids]: `src="data:…"` → `src="cid:…"` перед отправкой HTML-тела.
 *
 * data URL из [knownCids] (data URL → Content-ID уже существующего вложения) возвращаются к своему
 * cid без повторной загрузки. Остальные декодируются в [InlineDataImage] с Content-ID от
 * [newContentId] (номер с 1); одинаковая картинка, вставленная дважды, — одно вложение.
 * Битый base64 остаётся в теле как есть.
 */
fun extractDataUrlImages(
    html: String,
    knownCids: Map<String, String> = emptyMap(),
    newContentId: (index: Int) -> String
): Pair<String, List<InlineDataImage>> {
    if (!html.contains("data:")) return html to emptyList()
    val images = mutableListOf<InlineDataImage>()
    val newCidByDataUrl = HashMap<String, String>()
    val body = DATA_URL_SRC_REGEX.replace(html) { match ->
        val mimeType = match.groupValues[1].trim()
        val base64 = match.groupValues[2]
        val dataUrl = "data:$mimeType;base64,$base64"
        val cid = knownCids[dataUrl] ?: newCidByDataUrl[dataUrl] ?: run {
            val bytes = try {
                java.util.Base64.getMimeDecoder().decode(base64)
            } catch (_: IllegalArgumentException) {
                return@replace match.value
            }
            if (bytes.isEmpty()) return@replace match.value
            val created = newContentId(images.size + 1)
            images += InlineDataImage(created, mimeType, bytes)
            newCidByDataUrl[dataUrl] = created
            created
        }
        "src=\"cid:${normalizeContentId(cid)}\""
    }
    return body to images
}

/** Расширение файла inline-картинки по MIME (имя вложения видно в Outlook). */
fun imageFileExtension(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg", "image/jpg" -> ".jpg"
    "image/gif" -> ".gif"
    "image/bmp" -> ".bmp"
    "image/webp" -> ".webp"
    else -> ".png"
}

/** RFC 2392: в URL `cid:` спецсимволы Content-ID могут быть %-кодированы. */
private fun decodeCidToken(token: String): String =
    if (!token.contains('%')) token
    else try { java.net.URLDecoder.decode(token, "UTF-8") } catch (_: Exception) { token }

/**
 * MIME-тип картинки по сигнатуре байтов (вложения календаря не несут Content-Type в метаданных синка).
 * Только сигнатура: у всех поддерживаемых форматов она фиксирована, а расширение имени не гарантирует
 * содержимое. null — не картинка: такие вложения inline не подставляются.
 */
fun detectImageMimeType(bytes: ByteArray): String? {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "image/gif"
        at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
        at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
        else -> null
    }
}
