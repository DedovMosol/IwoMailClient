package com.dedovmosol.iwomail.ui.screens.compose

import android.net.Uri

data class EmailSuggestion(
    val email: String,
    val name: String,
    val source: SuggestionSource,
    val groupEmails: List<String> = emptyList(),
    val groupName: String = "",
    val groupColor: Int = 0
)

enum class SuggestionSource {
    CONTACT,
    HISTORY,
    GAL,
    GROUP
}

/**
 * Поле получателей (для адресных мутаций в [ComposeViewModel]).
 */
enum class RecipientField {
    To, Cc, Bcc
}

/**
 * Выбор группы контактов (контакт-пикер / подсказки).
 * @param name Имя группы
 * @param emails Email-адреса участников группы
 * @param color Цвет группы (для подкраски токена [GroupName])
 */
data class GroupSelection(
    val name: String,
    val emails: List<String>,
    val color: Int
)

/**
 * Отложенное добавление получателей, содержащих дубликаты (перенесено из локального
 * состояния диалога ComposeScreen в VM, Этап 3 / CS-16). Пользователь подтверждает
 * добавление через [ComposeViewModel.confirmDuplicateAddition].
 *
 * @param duplicateEmail Первый найденный дубликат (для текста предупреждения)
 * @param duplicateFieldName Поле, где найден дубликат ("To"/"Cc"/"Bcc"), или null
 * @param targetField Поле, в которое выполняется добавление
 * @param emails Обычные email-адреса для добавления при подтверждении
 * @param groupName Имя группы (для групповой подсказки)
 * @param groupEmails Адреса группы (для групповой подсказки)
 * @param groupColor Цвет группы (для групповой подсказки)
 * @param groups Список групп из контакт-пикера (применяются при подтверждении)
 * @param replaceLastToken true = заменить частичный ввод (поток подсказок),
 *   false = дописать в конец (поток контакт-пикера)
 */
data class PendingDuplicateAddition(
    val duplicateEmail: String,
    val duplicateFieldName: String?,
    val targetField: RecipientField,
    val emails: List<String> = emptyList(),
    val groupName: String? = null,
    val groupEmails: List<String> = emptyList(),
    val groupColor: Int = 0,
    val groups: List<GroupSelection> = emptyList(),
    val replaceLastToken: Boolean = false
)

enum class ImageQuality(val maxSize: Int, val jpegQuality: Int, val labelRu: String, val labelEn: String) {
    SMALL(800, 70, "Маленькое (~100 КБ)", "Small (~100 KB)"),
    MEDIUM(1024, 85, "Среднее (~300 КБ)", "Medium (~300 KB)"),
    LARGE(1600, 90, "Большое (~600 КБ)", "Large (~600 KB)"),
    ORIGINAL(4096, 95, "Оригинал (макс. качество)", "Original (max quality)")
}

data class AttachmentInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
) {
    fun toSaveableString(): String = "${uri}|||${name}|||${size}|||${mimeType}"

    companion object {
        fun fromSaveableString(s: String): AttachmentInfo? {
            val parts = s.split("|||")
            if (parts.size != 4) return null
            return try {
                AttachmentInfo(
                    uri = Uri.parse(parts[0]),
                    name = parts[1],
                    size = parts[2].toLong(),
                    mimeType = parts[3]
                )
            } catch (_: Exception) { null }
        }
    }
}
