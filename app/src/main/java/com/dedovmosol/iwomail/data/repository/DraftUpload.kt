package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.util.EmbeddedImages
import com.dedovmosol.iwomail.util.containsContentId
import com.dedovmosol.iwomail.util.extractDataUrlImages
import com.dedovmosol.iwomail.util.imageFileExtension
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.util.referencedContentIds

/** Роль вложения черновика (письма), открытого на редактирование. */
internal enum class DraftAttachmentRole {
    /** Картинка в тексте: тело ссылается на неё через cid:. */
    INLINE,
    /** Обычное вложение. */
    FILE
}

/**
 * Inline — только картинка, на которую ссылается тело (cid:, целиком и без учёта регистра). Вложение с
 * Content-ID без ссылки в теле (Outlook и Apple Mail ставят его и обычным файлам, синхронизация помечает такие
 * inline) — файл: иначе при пересохранении черновика оно пропало бы. Что картинка уже встроена в тело
 * data: URL, доказывают только её байты ([EmbeddedImages]) — без этого вложение не прячется.
 */
internal fun draftAttachmentRole(body: String, contentId: String?): DraftAttachmentRole =
    if (containsContentId(referencedContentIds(body), contentId)) DraftAttachmentRole.INLINE else DraftAttachmentRole.FILE

/**
 * Черновик → то, что уходит на сервер (DRY: экран письма, сохранение черновика и загрузка локальных).
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

    /** Сохранённое вложение черновика как часть для сервера: по ссылке тела [serverBody] — inline с его Content-ID, иначе файл. */
    fun part(attachment: AttachmentEntity, serverBody: String, data: ByteArray): DraftAttachmentData {
        val cid = attachment.contentId?.let(::normalizeContentId)?.takeIf { it.isNotEmpty() }
        val referenced = cid != null && containsContentId(referencedContentIds(serverBody), cid)
        return DraftAttachmentData(
            name = attachment.displayName,
            mimeType = attachment.contentType,
            data = data,
            isInline = referenced,
            contentId = cid?.takeIf { referenced }
        )
    }

    /**
     * Локальный черновик для загрузки на сервер: тело с картинками-cid и сохранённые вложения ([part]).
     * Помеченное inline вложение без ссылки в теле не дублируется, если это своя копия картинки тела (без ссылки
     * на сервер) или его байты совпали с картинкой тела; иначе это файл (Content-ID ставят и файлам). [read] —
     * байты сохранённого вложения (null — файла нет, вложение пропускается).
     */
    fun prepare(
        body: String,
        stored: List<AttachmentEntity>,
        stamp: Long,
        read: (AttachmentEntity) -> ByteArray?
    ): Pair<String, List<DraftAttachmentData>> {
        val (serverBody, inline) = inlineImages(body, stamp)
        val referenced = referencedContentIds(serverBody)
        val embedded = EmbeddedImages(body)
        val files = stored.mapNotNull { att ->
            val unreferencedInline = att.isInline && !containsContentId(referenced, att.contentId)
            if (unreferencedInline && att.fileReference.isBlank()) return@mapNotNull null
            val data = read(att) ?: return@mapNotNull null
            if (unreferencedInline && embedded.dataUrlOf(data) != null) return@mapNotNull null
            part(att, serverBody, data)
        }
        return serverBody to (inline + files)
    }

    /**
     * Сохранённые файлами картинки черновика на устройстве, на которые тело [body] больше не ссылается:
     * удалены в редакторе или уже встроены data: URL. Ещё не скачанные (со ссылкой на сервер) не входят.
     * Оставленные, при следующем открытии они стали бы обычными вложениями ([draftAttachmentRole]).
     */
    fun unreferencedInlineImages(stored: List<AttachmentEntity>, body: String): List<AttachmentEntity> {
        val referenced = referencedContentIds(body)
        return stored.filter { it.isInline && it.fileReference.isBlank() && !containsContentId(referenced, it.contentId) }
    }
}
