package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.RICH_TEXT_EDITOR_FLUSH_TIMEOUT_MS
import com.dedovmosol.iwomail.util.HtmlRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume


/**
 * Получить текст интервала синхронизации контактов
 */
fun getContactsSyncIntervalText(days: Int, isRu: Boolean): String {
    return when (days) {
        0 -> NotificationStrings.getNever(isRu)
        1 -> NotificationStrings.getDaily(isRu)
        7 -> NotificationStrings.getWeekly(isRu)
        14 -> NotificationStrings.getEveryTwoWeeks(isRu)
        30 -> NotificationStrings.getMonthly(isRu)
        else -> NotificationStrings.getEveryNDays(days, isRu)
    }
}

private val SIGNATURE_BLOCK_END = Regex("</(?:p|div|li|tr)>", RegexOption.IGNORE_CASE)
private val SIGNATURE_TD_END = Regex("</td>", RegexOption.IGNORE_CASE)
private val SIGNATURE_SPACES = Regex("[ \\t]+")
private val SIGNATURE_NEWLINES = Regex("\\n{2,}")

private fun stripHtmlForPreview(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(HtmlRegex.BR, "\n")
        .replace(SIGNATURE_BLOCK_END, "\n")
        .replace(SIGNATURE_TD_END, "\t")
        .replace(HtmlRegex.STYLE, "")
        .replace(HtmlRegex.SCRIPT, "")
        .replace(HtmlRegex.COMMENT, "")
        .replace(HtmlRegex.TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(SIGNATURE_SPACES, " ")
        .replace(SIGNATURE_NEWLINES, "\n")
        .trim()
}

/**
 * Диалог управления подписями аккаунта
 */
@Composable
fun SignaturesManagementDialog(
    isRu: Boolean,
    accountId: Long,
    signatures: List<com.dedovmosol.iwomail.data.database.SignatureEntity>,
    onSignaturesChanged: (List<com.dedovmosol.iwomail.data.database.SignatureEntity>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val database = remember { com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context) }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingSignatureId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingSignature = editingSignatureId?.let { id -> signatures.find { it.id == id } }
    var signatureToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val signatureToDelete = signatureToDeleteId?.let { id -> signatures.find { it.id == id } }

    // Диалог добавления/редактирования подписи
    if (showAddDialog || editingSignature != null) {
        // Первая подпись всегда default
        val isFirstSignature = signatures.isEmpty() && editingSignature == null
        // Если редактируем единственную подпись — она тоже всегда default
        val isOnlySignature = signatures.size == 1 && editingSignature != null
        val forceDefault = isFirstSignature || isOnlySignature

        SignatureEditDialog(
            isRu = isRu,
            signature = editingSignature,
            forceDefault = forceDefault,
            currentIsDefault = editingSignature?.isDefault ?: false,
            onSave = { name, text, isDefault, isHtml ->
                scope.launch(Dispatchers.IO) {
                    // Если ставим новый default — сбрасываем старый
                    val actualIsDefault = if (forceDefault) true else isDefault
                    if (actualIsDefault) {
                        database.signatureDao().clearDefaultForAccount(accountId)
                    }

                    val currentEditing = editingSignature
                    if (currentEditing != null) {
                        // Редактирование — update
                        val updated = currentEditing.copy(
                            name = name,
                            text = text,
                            isDefault = actualIsDefault,
                            isHtml = isHtml
                        )
                        database.signatureDao().update(updated)
                    } else {
                        // Создание новой
                        val newSignature = com.dedovmosol.iwomail.data.database.SignatureEntity(
                            accountId = accountId,
                            name = name,
                            text = text,
                            isDefault = actualIsDefault,
                            sortOrder = signatures.size,
                            isHtml = isHtml
                        )
                        database.signatureDao().insert(newSignature)
                    }
                    val updatedSignatures = database.signatureDao().getSignaturesByAccountList(accountId)
                    withContext(Dispatchers.Main) {
                        onSignaturesChanged(updatedSignatures)
                    }
                }
                showAddDialog = false
                editingSignatureId = null
            },
            onDismiss = {
                showAddDialog = false
                editingSignatureId = null
            }
        )
    }

    // Диалог подтверждения удаления
    signatureToDelete?.let { signature ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { signatureToDeleteId = null },
            icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteSignatureTitle) },
            text = { Text(signature.name) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        scope.launch {
                            val wasDefault = signature.isDefault
                            database.signatureDao().delete(signature.id)

                            // Если удалили default подпись — назначить следующую по createdAt
                            if (wasDefault) {
                                val remaining = database.signatureDao().getSignaturesByAccountList(accountId)
                                if (remaining.isNotEmpty()) {
                                    // Сортируем по createdAt и берём первую
                                    val nextDefault = remaining.minByOrNull { it.createdAt }
                                    nextDefault?.let {
                                        database.signatureDao().setDefault(it.id, accountId)
                                    }
                                }
                            }

                            onSignaturesChanged(database.signatureDao().getSignaturesByAccountList(accountId))
                        }
                        signatureToDeleteId = null
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { signatureToDeleteId = null },
                    text = Strings.cancel
                )
            }
        )
    }

    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.signaturesTitle) },
        text = {
            Column {
                if (signatures.isEmpty()) {
                    Text(
                        Strings.noSignaturesHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    signatures.forEach { signature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingSignatureId = signature.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        signature.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (signature.isDefault) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (signature.isDefault) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            Strings.defaultLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                val preview = if (signature.isHtml) {
                                    stripHtmlForPreview(signature.text)
                                } else {
                                    signature.text
                                }
                                val oneLinePreview = preview.replace("\n", " ").trim()
                                val truncatedPreview = if (oneLinePreview.length > 40) {
                                    oneLinePreview.take(40) + "..."
                                } else {
                                    oneLinePreview
                                }
                                Text(
                                    truncatedPreview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { signatureToDeleteId = signature.id }) {
                                Icon(
                                    AppIcons.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }

                // Кнопка добавления (максимум 5 подписей)
                if (signatures.size < 5) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(AppIcons.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Strings.addSignatureAction)
                    }
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.close
            )
        }
    )
}

/**
 * Диалог редактирования подписи
 */
@Composable
private fun SignatureEditDialog(
    isRu: Boolean,
    signature: com.dedovmosol.iwomail.data.database.SignatureEntity?,
    forceDefault: Boolean = false,
    currentIsDefault: Boolean = false,
    onSave: (name: String, text: String, isDefault: Boolean, isHtml: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(signature?.name ?: "") }
    var text by rememberSaveable { mutableStateOf(signature?.text ?: "") }
    var isDefault by rememberSaveable { mutableStateOf(if (forceDefault) true else (signature?.isDefault ?: false)) }
    var isHtml by rememberSaveable { mutableStateOf(signature?.isHtml ?: false) }
    val richTextController = com.dedovmosol.iwomail.ui.components.rememberRichTextEditorController()
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    var isSaving by remember { mutableStateOf(false) }

    fun saveSignature() {
        if (isSaving) return
        isSaving = true
        if (isHtml) {
            scope.launch {
                var flushId: Long? = null
                val flushedHtml = withTimeoutOrNull(RICH_TEXT_EDITOR_FLUSH_TIMEOUT_MS) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        flushId = richTextController.flushHtml { html ->
                            if (continuation.isActive) continuation.resume(html)
                        }
                        if (flushId == null && continuation.isActive) {
                            continuation.resume(null)
                        }
                        continuation.invokeOnCancellation {
                            flushId?.let { richTextController.cancelPendingFlush(it) }
                        }
                    }
                }
                if (flushedHtml == null) {
                    flushId?.let { richTextController.cancelPendingFlush(it) }
                }
                onSave(name, flushedHtml ?: text, isDefault, true)
            }
        } else {
            onSave(name, text, isDefault, false)
        }
    }

    // Функция конвертации plain text → HTML
    fun plainToHtml(plain: String): String {
        return plain
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }

    // Функция конвертации HTML → plain text (простая)
    fun htmlToPlain(html: String): String {
        return html
            .replace(HtmlRegex.BR, "\n")
            .replace(HtmlRegex.TAG, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signature != null) Strings.editTitle else Strings.newSignatureTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Strings.nameLabel) },
                    placeholder = { Text(Strings.namePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Переключатель HTML форматирования
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Конвертируем текст при переключении режима
                            text = if (isHtml) {
                                // HTML → plain text
                                htmlToPlain(text)
                            } else {
                                // plain text → HTML
                                plainToHtml(text)
                            }
                            isHtml = !isHtml
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isHtml,
                        onCheckedChange = { newValue ->
                            // Конвертируем текст при переключении режима
                            text = if (isHtml && !newValue) {
                                // HTML → plain text
                                htmlToPlain(text)
                            } else if (!isHtml && newValue) {
                                // plain text → HTML
                                plainToHtml(text)
                            } else {
                                text
                            }
                            isHtml = newValue
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isRu) "HTML форматирование" else "HTML formatting")
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isHtml) {
                    // HTML редактор
                    val isDarkTheme = !MaterialTheme.colorScheme.background.luminance().let { it > 0.5f }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 200.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column {
                            com.dedovmosol.iwomail.ui.components.RichTextToolbar(
                                controller = richTextController,
                                onInsertImage = { }, // Изображения в подписи не поддерживаем
                                modifier = Modifier.fillMaxWidth(),
                                showInsertImage = false
                            )
                            HorizontalDivider()
                            com.dedovmosol.iwomail.ui.components.RichTextEditor(
                                initialHtml = text,
                                onHtmlChanged = { text = it },
                                controller = richTextController,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp),
                                placeholder = if (isRu) "Текст подписи" else "Signature text",
                                minHeight = 80,
                                isDarkTheme = isDarkTheme
                            )
                        }
                    }
                } else {
                    // Plain text редактор
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(Strings.signatureTextLabel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        maxLines = 6
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Галочка "По умолчанию" — нельзя снять если это единственная/первая подпись или уже default
                val canToggleDefault = !forceDefault && !currentIsDefault
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (canToggleDefault) Modifier.clickable { isDefault = !isDefault } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isDefault,
                        onCheckedChange = if (canToggleDefault) { { isDefault = it } } else null,
                        enabled = canToggleDefault
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        Strings.defaultCheckbox,
                        color = if (canToggleDefault) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = { saveSignature() },
                text = Strings.save,
                enabled = !isSaving && name.isNotBlank() && text.isNotBlank()
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel
            )
        }
    )
}

// ─── Блокировка приложения: диалог пароля (установка / смена / отключение) ───

/** Какой режим диалога пароля блокировки открыт. */
internal enum class LockDialogType { SetPassword, ChangePassword, Disable }

/**
 * Единый диалог пароля блокировки приложения (цель релиза «пароль + дактилоскопия»).
 *
 * Режимы (комбинируются флагами, без дублирования трёх экранов — DRY):
 *  - [requireCurrentPassword] = текущий пароль обязателен и проверяется ДО коммита;
 *  - [requireNewPassword] = новый пароль + подтверждение, длина ≥
 *    [com.dedovmosol.iwomail.data.security.AppLockManager.MIN_PASSWORD_LENGTH].
 *
 * Производительность: проверка текущего пароля и [onSubmit] (PBKDF2) идут на
 * [Dispatchers.IO]. [onSuccess] вызывается уже на главном потоке.
 */
@Composable
internal fun LockPasswordDialog(
    title: String,
    requireCurrentPassword: Boolean,
    requireNewPassword: Boolean,
    hint: String? = null,
    onCancel: () -> Unit,
    onSubmit: (currentPassword: String, newPassword: String) -> Boolean,
    onSuccess: () -> Unit
) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    // Строки — захватываем из композабельного контекста в замыкания:
    // @Composable-геттеры нельзя вызывать из несоставного submit().
    val tooShortMsg = Strings.appLockTooShort
    val mismatchMsg = Strings.appLockMismatch
    val wrongPasswordMsg = Strings.appLockWrongPassword
    val saveRejectedMsg = Strings.failClosedTitle
    // БЕЗОПАСНОСТЬ: пароли хранятся только в памяти композабля (remember), а НЕ в
    // rememberSaveable — сериализация секрета в instance-state недопустима. При
    // смерти процесса/конфигурации диалог закроется и пользователь введёт заново —
    // это правильный безопасный компромисс.
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    // Попыток до паузы и остаток паузы после неверного текущего пароля (null — неверных попыток не было)
    var attemptState by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val attemptText = attemptState?.let { (left, ms) -> Strings.appLockAttemptState(left, ms) }
    val lockedOut = (attemptState?.second ?: 0L) > 0L
    if (requireCurrentPassword) {
        // Пауза могла начаться на экране блокировки или в прошлом открытии диалога — показать её сразу
        LaunchedEffect(Unit) {
            val ms = withContext(Dispatchers.IO) { com.dedovmosol.iwomail.data.security.AppLockManager.lockoutRemainingMs() }
            if (ms > 0L) attemptState = 0 to ms
        }
        // Обратный отсчёт паузы; по окончании сообщение убирается
        LaunchedEffect(lockedOut) {
            if (!lockedOut) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(minOf(1_000L, attemptState?.second ?: 0L).coerceAtLeast(1L))
                val ms = withContext(Dispatchers.IO) { com.dedovmosol.iwomail.data.security.AppLockManager.lockoutRemainingMs() }
                if (ms <= 0L) break
                attemptState = 0 to ms
            }
            attemptState = null
            error = null
        }
    }

    fun submit() {
        if (isChecking || lockedOut) return
        error = null
        attemptState = null

        // Валидация нового пароля — дешёвые проверки до тяжёлого хеширования.
        if (requireNewPassword) {
            if (newPassword.length < com.dedovmosol.iwomail.data.security.AppLockManager.MIN_PASSWORD_LENGTH) {
                error = tooShortMsg
                return
            }
            if (newPassword != confirmPassword) {
                error = mismatchMsg
                return
            }
        }

        isChecking = true
        scope.launch {
            // Текущий пароль: тяжёлая проверка PBKDF2 строго на IO-диспатчере.
            if (requireCurrentPassword) {
                // NAV-01: тот же счётчик попыток, что на экране блокировки — смена или отключение
                // пароля не становится обходным путём для перебора
                val attempt = withContext(Dispatchers.IO) {
                    com.dedovmosol.iwomail.data.security.AppLockManager.attempt(currentPassword, unlockOnSuccess = false)
                }
                when (attempt) {
                    com.dedovmosol.iwomail.data.security.AppLockManager.Attempt.Success -> attemptState = null
                    is com.dedovmosol.iwomail.data.security.AppLockManager.Attempt.Wrong -> {
                        isChecking = false
                        attemptState = attempt.attemptsBeforeLockout to attempt.lockoutMs
                        if (attempt.lockoutMs > 0L) currentPassword = ""
                        error = wrongPasswordMsg
                        return@launch
                    }
                    is com.dedovmosol.iwomail.data.security.AppLockManager.Attempt.LockedOut -> {
                        isChecking = false
                        attemptState = 0 to attempt.remainingMs
                        error = wrongPasswordMsg
                        return@launch
                    }
                }
            }
            val committed = withContext(Dispatchers.IO) {
                onSubmit(currentPassword, newPassword)
            }
            isChecking = false
            if (committed) {
                onSuccess()
            } else {
                error = saveRejectedMsg
            }
        }
    }

    com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(AppIcons.Lock, null) },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodyMedium)
                }
                if (requireCurrentPassword) {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            if (error != null) error = null
                            if (!lockedOut) attemptState = null
                        },
                        label = { Text(Strings.appLockCurrentPassword) },
                        singleLine = true,
                        enabled = !lockedOut,
                        isError = attemptState != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (requireNewPassword) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            if (error != null) error = null
                        },
                        label = { Text(Strings.appLockNewPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            if (error != null) error = null
                        },
                        label = { Text(Strings.appLockRepeatPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Неверный текущий пароль — с числом попыток до паузы или обратным отсчётом паузы (NAV-01)
                val message = attemptText ?: error
                if (message != null) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = { submit() },
                text = Strings.save,
                enabled = !isChecking && !lockedOut &&
                    (!requireCurrentPassword || currentPassword.isNotEmpty()) &&
                    (!requireNewPassword || (newPassword.isNotEmpty() && confirmPassword.isNotEmpty()))
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onCancel,
                text = Strings.cancel
            )
        }
    )
}
