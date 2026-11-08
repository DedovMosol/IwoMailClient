package com.dedovmosol.iwomail.domain

/**
 * Бюджет inline-картинок в редакторе черновика: подписи/логотипы остаются в теле,
 * крупные фото не раздувают HTML (OOM / краш WebView) и уходят как обычные вложения.
 *
 * Лимит числа совпадает с [AttachmentLoader.MAX_INLINE_IMAGES] (Exchange 2007 может
 * прислать десятки cid:). Байты — декодированные, до base64.
 */
internal class EditorInlineBudget(
    private val maxCount: Int = AttachmentLoader.MAX_INLINE_IMAGES,
    private val maxBytesPerImage: Int = MAX_BYTES_PER_IMAGE,
    private val maxTotalBytes: Int = MAX_TOTAL_BYTES
) {
    var count: Int = 0
        private set
    var totalBytes: Int = 0
        private set

    /** true — картинку можно встроить data URL; слот уже занят. */
    fun offer(imageBytes: Int): Boolean {
        if (imageBytes <= 0 || imageBytes > maxBytesPerImage) return false
        if (count >= maxCount) return false
        if (totalBytes + imageBytes > maxTotalBytes) return false
        count++
        totalBytes += imageBytes
        return true
    }

    /**
     * Оставляет data URL, которые ещё влезают в бюджет (порядок карты — порядок вставки).
     * Для MIME/EWS fetch, где картинки уже закодированы.
     */
    fun filterDataUrls(images: Map<String, String>): Map<String, String> {
        if (images.isEmpty()) return images
        val out = LinkedHashMap<String, String>()
        for ((cid, url) in images) {
            if (offer(decodedDataUrlBytes(url))) out[cid] = url
        }
        return out
    }

    companion object {
        /** Одна картинка: подпись/иконка; фото с телефона обычно больше и идёт вложением. */
        const val MAX_BYTES_PER_IMAGE = 256 * 1024
        /** Суммарно в HTML редактора — с запасом под Binder-чанки WebView. */
        const val MAX_TOTAL_BYTES = 512 * 1024
    }
}

/** Оценка декодированного размера data URL без аллокации base64-подстроки. */
internal fun decodedDataUrlBytes(dataUrl: String): Int {
    val marker = "base64,"
    val at = dataUrl.indexOf(marker, ignoreCase = true)
    val b64Len = if (at < 0) dataUrl.length else dataUrl.length - (at + marker.length)
    if (b64Len <= 0) return 0
    return ((b64Len.toLong() * 3L) / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
