package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.runtime.Immutable
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.eas.FolderType
import kotlin.math.abs

/** Действие свайпа с учётом папки: удалять из «Удалённых» и Черновиков свайпом нельзя, флаг — там, где есть звёздочка. */
internal fun SwipeAction.availableIn(deleteAllowed: Boolean, flagAllowed: Boolean): SwipeAction = when (this) {
    SwipeAction.DELETE -> if (deleteAllowed) this else SwipeAction.NONE
    SwipeAction.FLAG -> if (flagAllowed) this else SwipeAction.NONE
    else -> this
}

/**
 * Свайпы строки зависят от папки самого письма, а не только от открытого списка: в «Избранном» и «Сегодня» лежат письма
 * и из «Удалённых», и из Черновиков, а там удаление окончательное и идёт только через диалог подтверждения.
 */
@Immutable
internal class SwipeRules private constructor(
    private val start: SwipeAction,
    private val end: SwipeAction,
    private val noDeleteFolderIds: Set<String>,
    private val noFlagFolderIds: Set<String>
) {
    fun start(folderId: String): SwipeAction = start.availableIn(folderId !in noDeleteFolderIds, folderId !in noFlagFolderIds)

    fun end(folderId: String): SwipeAction = end.availableIn(folderId !in noDeleteFolderIds, folderId !in noFlagFolderIds)

    companion object {
        val NONE = SwipeRules(SwipeAction.NONE, SwipeAction.NONE, emptySet(), emptySet())

        private val NO_DELETE_TYPES = setOf(FolderType.DELETED_ITEMS, FolderType.DRAFTS)
        private val NO_FLAG_TYPES = setOf(FolderType.DELETED_ITEMS, FolderType.DRAFTS, FolderType.JUNK_EMAIL)

        fun of(start: SwipeAction, end: SwipeAction, folders: List<FolderEntity>): SwipeRules {
            if (start == SwipeAction.NONE && end == SwipeAction.NONE) return NONE
            return SwipeRules(
                start = start,
                end = end,
                noDeleteFolderIds = folders.filter { it.type in NO_DELETE_TYPES }.mapTo(HashSet()) { it.id },
                noFlagFolderIds = folders.filter { it.type in NO_FLAG_TYPES }.mapTo(HashSet()) { it.id }
            )
        }
    }
}

/** Доля ширины строки, которую нужно протащить, чтобы свайп сработал. */
internal const val SWIPE_COMMIT_FRACTION = 0.4f

/**
 * Свайп засчитывается только по пройденному расстоянию. SwipeToDismissBox принимает решение и по скорости (порог
 * 125 dp/с), поэтому быстрый косой жест при прокрутке списка «досылал» строку и случайно удалял или отмечал письмо.
 */
internal fun isSwipeCommitted(offset: Float, width: Int): Boolean = width > 0 && abs(offset) >= width * SWIPE_COMMIT_FRACTION
