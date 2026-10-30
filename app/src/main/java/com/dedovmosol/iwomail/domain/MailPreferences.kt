package com.dedovmosol.iwomail.domain

/** Отмена отправки: сколько секунд письмо ждёт перед отправкой (0 — отправлять сразу, без отмены). */
object UndoSend {
    val OPTIONS = listOf(0, 5, 10, 20, 30)
    const val DEFAULT_SECONDS = 5

    /** Сохранённое значение вне списка (ручная правка, старая версия) — значение по умолчанию. */
    fun sanitize(seconds: Int?): Int = seconds?.takeIf { it in OPTIONS } ?: DEFAULT_SECONDS
}

/** Действие свайпа по письму в списке. */
enum class SwipeAction {
    NONE,
    /** В «Удалённые» (не окончательно). */
    DELETE,
    /** Прочитано / непрочитано. */
    READ,
    /** Флаг (избранное) — поставить или снять. */
    FLAG;

    companion object {
        /** Слева направо. */
        val DEFAULT_START = READ
        /** Справа налево. */
        val DEFAULT_END = DELETE

        fun fromName(name: String?, default: SwipeAction): SwipeAction = entries.firstOrNull { it.name == name } ?: default
    }
}
