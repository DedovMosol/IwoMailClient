package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.dedovmosol.iwomail.util.containsContentId
import com.dedovmosol.iwomail.util.normalizeContentId

/**
 * Вложение показано в теле письма (картинка по ссылке cid:) — тогда его нет в списке файлов.
 *
 * Синхронизация помечает inline всё, у чего есть Content-ID (Exchange 2007 не ставит IsInline
 * inline-картинкам), но Outlook и Apple Mail ставят Content-ID и обычным файлам. Поэтому, когда тело
 * разобрано ([referencedIds] — его ссылки cid:), решает тело: нет ссылки — это файл, он в списке. Есть
 * ссылка, но картинка не подставилась (крупная, не скачалась) — тоже в списке: иначе вложение недоступно.
 * Пока тело не разобрано — прежний признак [isInline].
 */
internal fun isShownInBody(
    contentId: String?,
    displayName: String,
    isInline: Boolean,
    referencedIds: Set<String>?,
    inlineImages: Map<String, String>,
    inlineImagesLoading: Boolean
): Boolean {
    if (referencedIds == null) return isInline
    val referencedByName = containsContentId(referencedIds, displayName)
    if (!containsContentId(referencedIds, contentId) && !referencedByName) return false
    if (inlineImagesLoading) return true
    val shown = inlineImages.keys.mapTo(HashSet()) { normalizeContentId(it) }
    return containsContentId(shown, contentId) || (referencedByName && containsContentId(shown, displayName))
}
