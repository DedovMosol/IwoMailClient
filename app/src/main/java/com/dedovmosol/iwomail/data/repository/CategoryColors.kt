package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.CategoryColorEntity
import com.dedovmosol.iwomail.util.CalendarCategories
import com.dedovmosol.iwomail.util.OutlookCategoryPalette

/** Откуда взят цвет категории — для подписи на экране «Цвета категорий». */
enum class CategoryColorSource {
    /** Назначен пользователем в приложении (перекрывает всё). */
    USER,
    /** Из Master Category List ящика (Exchange 2010+). */
    SERVER,
    /** Список с сервера получен, но категории в нём нет — Outlook показывает такую без цвета. */
    NOT_IN_SERVER_LIST,
    /** Стандартная категория Outlook («Red Category», «Красная категория»…). */
    PRESET,
    /** Подобран по имени: сервер цвета не сообщил (Exchange 2007), назначения нет. */
    AUTO
}

/** Итоговый цвет категории: индекс палитры [MS-OXOCFG] (-1 — без цвета) и его источник. */
data class ResolvedCategoryColor(val color: Int, val source: CategoryColorSource)

/**
 * Цвета категорий аккаунта — неизменяемый снимок для UI.
 *
 * Приоритет: ручное назначение > цвет с сервера > (список с сервера есть, а категории в нём нет →
 * без цвета, как в Outlook) > стандартное имя Outlook > детерминированный автоподбор.
 */
class CategoryColors(entries: List<CategoryColorEntity>) {
    private val byKey: Map<String, CategoryColorEntity> = entries.associateBy { it.nameKey }

    /** Сервер прислал Master Category List (Exchange 2010+). */
    val hasServerList: Boolean = entries.any { it.serverColor != null }

    /** Имена категорий, известных по серверу или по ручным назначениям. */
    val storedNames: List<String> = entries.map { it.name }

    fun resolve(name: String): ResolvedCategoryColor {
        val entry = byKey[CalendarCategories.key(name)]
        entry?.userColor?.let { return ResolvedCategoryColor(OutlookCategoryPalette.normalize(it), CategoryColorSource.USER) }
        entry?.serverColor?.let { return ResolvedCategoryColor(OutlookCategoryPalette.normalize(it), CategoryColorSource.SERVER) }
        if (hasServerList) return ResolvedCategoryColor(OutlookCategoryPalette.NONE, CategoryColorSource.NOT_IN_SERVER_LIST)
        OutlookCategoryPalette.presetColorFor(name)?.let { return ResolvedCategoryColor(it, CategoryColorSource.PRESET) }
        return ResolvedCategoryColor(OutlookCategoryPalette.autoColorFor(name), CategoryColorSource.AUTO)
    }

    /** Есть ли ручное назначение (для кнопки «Сбросить»). */
    fun hasUserColor(name: String): Boolean = byKey[CalendarCategories.key(name)]?.userColor != null

    /**
     * Категории для экрана цветов: известные ([storedNames]) и встречающиеся в событиях ([usedInEvents]),
     * без повторов (регистр не важен), по алфавиту.
     */
    fun knownCategories(usedInEvents: Collection<String>): List<String> =
        (storedNames + usedInEvents)
            .filter { it.isNotBlank() }
            .distinctBy { CalendarCategories.key(it) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    companion object {
        val EMPTY = CategoryColors(emptyList())
    }
}
