package com.dedovmosol.iwomail.ui.screens.compose

import android.net.Uri
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.SignatureEntity

/**
 * Неизменяемое состояние экрана создания письма.
 * Следует MVVM-паттерну проекта (см. ARCHITECTURE.md §2.1).
 */
data class ComposeUiState(
    // Аккаунты и активный отправитель
    val accounts: List<AccountEntity> = emptyList(),
    val activeAccount: AccountEntity? = null,
    val selectedAccountId: Long? = null,

    // Подписи
    val signatures: List<SignatureEntity> = emptyList(),
    val selectedSignature: SignatureEntity? = null,

    // Поля письма
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val importance: Int = 0,

    // Вложения
    val attachments: List<AttachmentItem> = emptyList(),
    val attachmentSizeBytes: Long = 0,

    // Контакты/группы (для автодополнения)
    val groupMappings: Map<String, List<String>> = emptyMap(),
    val groupColors: Map<String, Int> = emptyMap(),
    val suggestions: List<EmailSuggestion> = emptyList(),

    // Флаги состояния
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isSavingDraft: Boolean = false,
    val isLoadingContacts: Boolean = false,

    // MDN flags
    val requestReadReceipt: Boolean = false,
    val requestDeliveryReceipt: Boolean = false,

    // Режим (новое/reply/forward/draft/mailto/share)
    val mode: ComposeMode = ComposeMode.New,
    val replyToEmail: EmailEntity? = null,
    val forwardEmail: EmailEntity? = null,
    val editingDraft: EmailEntity? = null,

    // Валидация
    val toValid: Boolean = true,
    val ccValid: Boolean = true,
    val bccValid: Boolean = true,
    val hasRecipients: Boolean = false,
)

/**
 * Режимы создания письма.
 */
enum class ComposeMode {
    /** Новое письмо */
    New,

    /** Ответ отправителю */
    Reply,

    /** Ответ всем */
    ReplyAll,

    /** Пересылка */
    Forward,

    /** Редактирование черновика */
    EditDraft,

    /** Mailto intent */
    Mailto,

    /** Share intent (вложение из другого приложения) */
    Share
}

/**
 * Элемент списка вложений в UI state.
 * Легковесная копия [AttachmentInfo] без mutable полей.
 */
data class AttachmentItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long
)
