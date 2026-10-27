package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.util.extractDataUrlImages
import com.dedovmosol.iwomail.util.imageFileExtension

/**
 * Черновик → то, что уходит на сервер (DRY: экран письма и загрузка локальных черновиков).
 *
 * Outlook рендерит HTML через Word, data: URL не показывает: картинки тела уходят как `cid:` +
 * inline-вложения с Content-ID (RFC 2392).
 */
internal object DraftUpload {

    /**
     * Предел тела черновика с data:-картинками в БД (символы ≈ байты: base64 — ASCII). Строка крупнее окна
     * курсора Android (2 МБ) не читается вовсе (SQLiteBlobTooBigException): черновик не открыть, а
     * синхронизация папки падает. Крупнее — хранится тело с `cid:`, картинки — файлами вложений.
     */
    const val MAX_LOCAL_BODY_CHARS = 1_000_000

    /** data:-картинки [html] → `cid:` + inline-вложения; [knownCids] (data URL → Content-ID) — уже загруженные. */
    fun inlineImages(
        html: String,
        stamp: Long,
        knownCids: Map<String, String> = emptyMap()
    ): Pair<String, List<DraftAttachmentData>> {
        val (body, images) = extractDataUrlImages(html, knownCids) { index -> "img${index}_$stamp" }
        return body to images.mapIndexed { i, image ->
            DraftAttachmentData(
                name = "image_${i + 1}${imageFileExtension(image.mimeType)}",
                mimeType = image.mimeType,
                data = image.bytes,
                isInline = true,
                contentId = image.contentId
            )
        }
    }

    /**
     * Локальный черновик для загрузки на сервер: тело с картинками-cid и все сохранённые вложения.
     * Inline-вложение, на которое тело не ссылается (его картинка ушла из data: URL с новым cid),
     * не дублируется; вложение с Content-ID, на которое тело ссылается, остаётся inline.
     * [read] — байты сохранённого вложения (null — файла нет, вложение пропускается).
     */
    fun prepare(
        body: String,
        stored: List<AttachmentEntity>,
        stamp: Long,
        read: (AttachmentEntity) -> ByteArray?
    ): Pair<String, List<DraftAttachmentData>> {
        val (serverBody, inline) = inlineImages(body, stamp)
        val files = stored.mapNotNull { att ->
            val cid = att.contentId?.removeSurrounding("<", ">")?.takeIf { it.isNotBlank() }
            val referenced = cid != null && serverBody.contains("cid:$cid", ignoreCase = true)
            if (att.isInline && !referenced) return@mapNotNull null
            val data = read(att) ?: return@mapNotNull null
            DraftAttachmentData(
                name = att.displayName,
                mimeType = att.contentType,
                data = data,
                isInline = referenced,
                contentId = cid?.takeIf { referenced }
            )
        }
        return serverBody to (inline + files)
    }
}
