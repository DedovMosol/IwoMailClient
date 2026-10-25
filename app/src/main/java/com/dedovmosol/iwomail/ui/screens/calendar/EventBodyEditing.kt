package com.dedovmosol.iwomail.ui.screens.calendar

import com.dedovmosol.iwomail.eas.CalendarAttachmentService
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.ui.screens.compose.extractInlineImageAttachments
import com.dedovmosol.iwomail.util.escapeHtml
import com.dedovmosol.iwomail.util.htmlVisibleText
import com.dedovmosol.iwomail.util.looksLikeHtmlEmailContent
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.util.referencedContentIds
import com.dedovmosol.iwomail.util.replaceInlineCids

/**
 * Тело события в rich-редакторе и обратно (SoC: чистая логика без UI, DRY с письмами).
 *
 * В редактор: картинки `cid:` подставляются data URL из вложений события (WebView редактора не
 * загружает cid:), plain text — экранированным HTML с `<br>`. Из редактора: data URL возвращаются к
 * своему cid (вложение уже на сервере), новые становятся inline-вложениями с ContentId, а inline-
 * вложения, на которые тело больше не ссылается, уходят на удаление.
 */
internal object EventBodyEditing {

    /** HTML для редактора и обратное отображение data URL → Content-ID подставленных картинок. */
    class EditorBody(val html: String, val knownCids: Map<String, String>)

    /** Серверная форма тела после правки. */
    class Encoded(
        val body: String,
        val newInlineImages: List<DraftAttachmentData>,
        val removedInlineRefs: List<String>
    )

    fun forEditor(body: String, inlineImages: Map<String, String>): EditorBody {
        if (body.isBlank()) return EditorBody("", emptyMap())
        if (!looksLikeHtmlEmailContent(body)) {
            return EditorBody(body.replace("\r\n", "\n").escapeHtml().replace("\n", "<br>"), emptyMap())
        }
        val knownCids = inlineImages.entries.associate { (cid, dataUrl) -> dataUrl to cid }
        return EditorBody(replaceInlineCids(body, inlineImages), knownCids)
    }

    /**
     * @param editedHtml HTML из редактора (innerHTML).
     * @param originalBody серверное тело до правки (с `cid:`).
     * @param attachmentsJson вложения события — для поиска inline-вложений, убранных из тела.
     */
    fun encode(editedHtml: String, editor: EditorBody, originalBody: String, attachmentsJson: String): Encoded {
        val (withCids, newImages) = extractInlineImageAttachments(editedHtml, editor.knownCids)
        val isEmpty = htmlVisibleText(withCids).isBlank() && !withCids.contains("<img", ignoreCase = true)
        // Вывод редактора — всегда HTML: голый текст из contenteditable несёт сущности (&nbsp;),
        // и как plain text (Type 1) они дошли бы до Outlook буквально.
        val body = when {
            isEmpty -> ""
            looksLikeHtmlEmailContent(withCids) -> withCids
            else -> "<div>$withCids</div>"
        }
        val stillReferenced = referencedContentIds(body)
        val wasReferenced = referencedContentIds(originalBody)
        val removed = CalendarAttachmentService.parseAttachmentRefs(attachmentsJson)
            .filter { ref ->
                val cid = normalizeContentId(ref.contentId)
                cid.isNotEmpty() && cid in wasReferenced && cid !in stillReferenced
            }
            .map { it.fileReference }
        return Encoded(body, newImages, removed)
    }
}
