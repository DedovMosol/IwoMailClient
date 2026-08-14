package com.dedovmosol.iwomail.ui.screens.compose

/**
 * Одноразовые UI-события для экрана создания письма.
 * Передаются через Channel + receiveAsFlow (см. ARCHITECTURE.md §2.1).
 * ViewModel эмитит семантические события, локализация — в UI.
 */
sealed class ComposeEvent {
    // Успешные операции

    /** Письмо успешно отправлено */
    data object EmailSent : ComposeEvent()

    /** Письмо запланировано к отправке */
    data object EmailScheduled : ComposeEvent()

    /** Черновик сохранён */
    data object DraftSaved : ComposeEvent()

    // Ошибки

    /** Общая ошибка операции */
    data class Error(val message: String) : ComposeEvent()

    /** Превышен лимит размера вложений */
    data class AttachmentLimitExceeded(val limitMb: Int) : ComposeEvent()

    /** Ошибка валидации поля */
    data class ValidationError(val field: ValidationField) : ComposeEvent()

    // UI-эффекты

    /** Воспроизвести звук успешной отправки */
    data object PlaySendSound : ComposeEvent()

    /** Навигация назад */
    data object NavigateBack : ComposeEvent()
}

/**
 * Поля для валидации.
 */
enum class ValidationField {
    To,
    Cc,
    Bcc,
    NoRecipients,
    Subject,
    Attachments
}
