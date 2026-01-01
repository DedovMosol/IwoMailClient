package com.iwo.mailclient.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType

import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.work.*
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.ContactEntity
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.database.SignatureEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.ContactRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.eas.EasClient
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.LocalLanguage
import com.iwo.mailclient.ui.AppLanguage
import com.iwo.mailclient.ui.NotificationStrings
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.components.ContactPickerDialog
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

// Предкомпилированные regex для stripHtml (производительность)
private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val BLOCK_END_REGEX = Regex("</(?:p|div|li|tr)>", RegexOption.IGNORE_CASE)
private val TD_END_REGEX = Regex("</td>", RegexOption.IGNORE_CASE)
private val STYLE_REGEX = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SCRIPT_REGEX = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val TAG_REGEX = Regex("<[^>]+>")
private val SPACES_REGEX = Regex("[ \\t]+")
private val NEWLINES_REGEX = Regex("\\n{3,}")
private val SIGNATURE_REGEX = Regex("\n\n--\n.*", RegexOption.DOT_MATCHES_ALL)

/**
 * Подсказка для автодополнения email
 */
data class EmailSuggestion(
    val email: String,
    val name: String,
    val source: SuggestionSource
)

enum class SuggestionSource {
    CONTACT,    // Из локальных контактов
    HISTORY,    // Из истории писем
    GAL         // Из корпоративной книги
}

data class AttachmentInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    replyToEmailId: String? = null,
    forwardEmailId: String? = null,
    initialToEmail: String? = null,
    editDraftId: String? = null,
    onBackClick: () -> Unit,
    onSent: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    val currentLanguage = LocalLanguage.current
    val mailRepo = remember { MailRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }
    
    // Аккаунт отправителя
    var activeAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var allAccounts by remember { mutableStateOf<List<AccountEntity>>(emptyList()) }
    var showAccountPicker by remember { mutableStateOf(false) }
    
    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var isSending by rememberSaveable { mutableStateOf(false) }
    var showCcBcc by rememberSaveable { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<AttachmentInfo>>(emptyList()) }
    var requestReadReceipt by rememberSaveable { mutableStateOf(false) }
    var requestDeliveryReceipt by rememberSaveable { mutableStateOf(false) }
    var highPriority by rememberSaveable { mutableStateOf(false) }
    
    // Подписи
    var signatures by remember { mutableStateOf<List<SignatureEntity>>(emptyList()) }
    var selectedSignature by remember { mutableStateOf<SignatureEntity?>(null) }
    var showSignaturePicker by remember { mutableStateOf(false) }
    
    // FocusRequester для полей ввода
    val toFocusRequester = remember { FocusRequester() }
    val ccFocusRequester = remember { FocusRequester() }
    val bccFocusRequester = remember { FocusRequester() }
    val subjectFocusRequester = remember { FocusRequester() }
    val bodyFocusRequester = remember { FocusRequester() }
    
    // Сохранение фокуса при повороте экрана
    var focusedFieldIndex by rememberSaveable { mutableIntStateOf(-1) }
    
    // Восстановление фокуса после поворота
    LaunchedEffect(focusedFieldIndex) {
        if (focusedFieldIndex >= 0) {
            kotlinx.coroutines.delay(100)
            when (focusedFieldIndex) {
                0 -> toFocusRequester.requestFocus()
                1 -> ccFocusRequester.requestFocus()
                2 -> bccFocusRequester.requestFocus()
                3 -> subjectFocusRequester.requestFocus()
                4 -> bodyFocusRequester.requestFocus()
            }
        }
    }
    
    // Меню и диалоги
    var showMenu by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var isSavingDraft by remember { mutableStateOf(false) }
    
    // Диалог выбора контактов
    var showContactPicker by remember { mutableStateOf(false) }
    var contactPickerTarget by remember { mutableStateOf("to") } // "to", "cc", "bcc"
    
    // Автодополнение email
    val contactRepo = remember { ContactRepository(context) }
    var toSuggestions by remember { mutableStateOf<List<EmailSuggestion>>(emptyList()) }
    var showToSuggestions by remember { mutableStateOf(false) }
    var toFieldFocused by remember { mutableStateOf(false) }
    var suggestionSearchJob by remember { mutableStateOf<Job?>(null) }
    val focusManager = LocalFocusManager.current
    
    // Функция поиска подсказок
    fun searchSuggestions(query: String, accountId: Long) {
        suggestionSearchJob?.cancel()
        if (query.length < 2) {
            toSuggestions = emptyList()
            showToSuggestions = false
            return
        }
        
        suggestionSearchJob = scope.launch {
            val suggestions = mutableListOf<EmailSuggestion>()
            
            // 1. Поиск по локальным контактам (мгновенно)
            withContext(Dispatchers.IO) {
                val contacts = database.contactDao().searchForAutocomplete(accountId, query, 5)
                contacts.forEach { contact ->
                    suggestions.add(EmailSuggestion(
                        email = contact.email,
                        name = contact.displayName,
                        source = SuggestionSource.CONTACT
                    ))
                }
            }
            
            // 2. Поиск по истории писем (мгновенно)
            withContext(Dispatchers.IO) {
                val history = database.emailDao().searchEmailHistory(accountId, query, 5)
                history.forEach { result ->
                    // Не добавляем дубликаты
                    if (suggestions.none { it.email.equals(result.email, ignoreCase = true) }) {
                        suggestions.add(EmailSuggestion(
                            email = result.email,
                            name = result.name,
                            source = SuggestionSource.HISTORY
                        ))
                    }
                }
            }
            
            toSuggestions = suggestions.take(8)
            showToSuggestions = suggestions.isNotEmpty() && toFieldFocused
            
            // 3. Поиск по GAL с задержкой (если ≥3 символа)
            if (query.length >= 3) {
                delay(500) // Debounce
                try {
                    val client = accountRepo.createEasClient(accountId)
                    if (client != null) {
                        val galResult = withContext(Dispatchers.IO) {
                            client.searchGAL(query)
                        }
                        if (galResult is EasResult.Success) {
                            val galSuggestions = galResult.data.take(5).mapNotNull { gal ->
                                if (suggestions.none { it.email.equals(gal.email, ignoreCase = true) }) {
                                    EmailSuggestion(
                                        email = gal.email,
                                        name = gal.displayName,
                                        source = SuggestionSource.GAL
                                    )
                                } else null
                            }
                            toSuggestions = (suggestions + galSuggestions).take(10)
                            showToSuggestions = toSuggestions.isNotEmpty() && toFieldFocused
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }
    
    // Загружаем активный аккаунт и все аккаунты
    LaunchedEffect(Unit) {
        activeAccount = accountRepo.getActiveAccountSync()
        // Загружаем подписи для аккаунта
        activeAccount?.let { account ->
            signatures = database.signatureDao().getSignaturesByAccountList(account.id)
            // Выбираем подпись по умолчанию или первую
            selectedSignature = signatures.find { it.isDefault } ?: signatures.firstOrNull()
        }
        // Подставляем подпись для нового письма (если нет ответа/пересылки и body пустой)
        if (replyToEmailId == null && forwardEmailId == null && editDraftId == null && body.isEmpty()) {
            selectedSignature?.text?.takeIf { it.isNotBlank() }?.let { sig ->
                body = "\n\n--\n$sig"
            } ?: activeAccount?.signature?.takeIf { it.isNotBlank() }?.let { sig ->
                // Fallback на старую подпись из аккаунта
                body = "\n\n--\n$sig"
            }
        }
        // Подставляем email из контактов
        if (initialToEmail != null && to.isEmpty()) {
            to = initialToEmail
        }
        accountRepo.accounts.collect { allAccounts = it }
    }
    
    // Лаунчер для выбора файлов
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "file"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    attachments = attachments + AttachmentInfo(uri, name, size, mimeType)
                }
            }
        }
    }
    
    // Локализованные строки для цитирования (нужно получить до LaunchedEffect)
    val originalMessageStr = Strings.originalMessage
    val forwardedMessageStr = Strings.forwardedMessage
    val quoteFromStr = Strings.quoteFrom
    val quoteDateStr = Strings.quoteDate
    val quoteSubjectStr = Strings.quoteSubject
    val quoteToStr = Strings.quoteTo
    
    // Загружаем данные для ответа
    LaunchedEffect(replyToEmailId) {
        replyToEmailId?.let { emailId ->
            mailRepo.getEmailSync(emailId)?.let { email ->
                // Проверяем, из какой папки письмо
                val folder = withContext(Dispatchers.IO) {
                    database.folderDao().getFolder(email.folderId)
                }
                val isSentFolder = folder?.type == 5 // type 5 = Sent Items
                
                // Если из Отправленных — отвечаем получателю, иначе отправителю
                to = if (isSentFolder) email.to else email.from
                
                subject = if (email.subject.startsWith("Re:", ignoreCase = true)) {
                    email.subject
                } else {
                    "Re: ${email.subject}"
                }
                val signature = selectedSignature?.text?.takeIf { it.isNotBlank() }?.let { "\n\n--\n$it" }
                    ?: activeAccount?.signature?.takeIf { it.isNotBlank() }?.let { "\n\n--\n$it" } ?: ""
                // Очищаем HTML из тела письма для цитаты
                val plainBody = stripHtml(email.body)
                body = "$signature\n\n--- $originalMessageStr ---\n" +
                       "$quoteFromStr: ${email.from}\n" +
                       "$quoteDateStr: ${formatDate(email.dateReceived)}\n" +
                       "$quoteSubjectStr: ${email.subject}\n\n" +
                       plainBody
            }
        }
    }
    
    // Загружаем данные для пересылки
    LaunchedEffect(forwardEmailId) {
        forwardEmailId?.let { emailId ->
            mailRepo.getEmailSync(emailId)?.let { email ->
                // Поле "Кому" оставляем пустым - пользователь сам введёт
                to = ""
                subject = if (email.subject.startsWith("Fwd:", ignoreCase = true) || 
                             email.subject.startsWith("Fw:", ignoreCase = true)) {
                    email.subject
                } else {
                    "Fwd: ${email.subject}"
                }
                val signature = selectedSignature?.text?.takeIf { it.isNotBlank() }?.let { "\n\n--\n$it" }
                    ?: activeAccount?.signature?.takeIf { it.isNotBlank() }?.let { "\n\n--\n$it" } ?: ""
                // Очищаем HTML из тела письма для пересылки
                val plainBody = stripHtml(email.body)
                body = "$signature\n\n---------- $forwardedMessageStr ----------\n" +
                       "$quoteFromStr: ${email.from}\n" +
                       "$quoteDateStr: ${formatDate(email.dateReceived)}\n" +
                       "$quoteSubjectStr: ${email.subject}\n" +
                       "$quoteToStr: ${email.to}\n\n" +
                       plainBody
            }
        }
    }
    
    // Загружаем данные черновика для редактирования
    LaunchedEffect(editDraftId) {
        editDraftId?.let { draftId ->
            mailRepo.getEmailSync(draftId)?.let { draft ->
                to = draft.to
                cc = draft.cc
                subject = draft.subject
                body = stripHtml(draft.body)
                if (cc.isNotBlank()) {
                    showCcBcc = true
                }
            }
        }
    }
    
    // Локализованные строки для Toast (нужно получить до launch)
    val accountNotFoundMsg = Strings.accountNotFound
    val authErrorMsg = Strings.authError
    val sendScheduledMsg = Strings.sendScheduled
    val draftSavedMsg = Strings.draftSaved
    val draftSaveErrorMsg = Strings.draftSaveError
    
    // Функция сохранения черновика (на сервер для Exchange, локально для остальных)
    fun saveDraft() {
        scope.launch {
            isSavingDraft = true
            
            val account = activeAccount
            if (account == null) {
                Toast.makeText(context, accountNotFoundMsg, Toast.LENGTH_SHORT).show()
                isSavingDraft = false
                return@launch
            }
            
            try {
                val success: Boolean
                
                // Если редактируем существующий черновик — используем updateDraft
                if (editDraftId != null) {
                    val email = withContext(Dispatchers.IO) { database.emailDao().getEmail(editDraftId) }
                    if (email != null) {
                        val result = withContext(Dispatchers.IO) {
                            mailRepo.updateDraft(
                                accountId = account.id,
                                serverId = email.serverId,
                                to = to,
                                cc = cc,
                                subject = subject,
                                body = body,
                                fromEmail = account.email,
                                fromName = account.displayName
                            )
                        }
                        success = result is EasResult.Success
                    } else {
                        // Черновик не найден в БД - создаём новый
                        val serverId = withContext(Dispatchers.IO) {
                            mailRepo.saveDraft(
                                accountId = account.id,
                                to = to,
                                cc = cc,
                                subject = subject,
                                body = body,
                                fromEmail = account.email,
                                fromName = account.displayName,
                                hasAttachments = attachments.isNotEmpty()
                            )
                        }
                        success = serverId != null
                    }
                } else {
                    // Новый черновик
                    val serverId = withContext(Dispatchers.IO) {
                        mailRepo.saveDraft(
                            accountId = account.id,
                            to = to,
                            cc = cc,
                            subject = subject,
                            body = body,
                            fromEmail = account.email,
                            fromName = account.displayName,
                            hasAttachments = attachments.isNotEmpty()
                        )
                    }
                    success = serverId != null
                }
                
                if (success) {
                    Toast.makeText(context, draftSavedMsg, Toast.LENGTH_SHORT).show()
                    onBackClick()
                } else {
                    Toast.makeText(context, draftSaveErrorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "${draftSaveErrorMsg}: ${e.message}", Toast.LENGTH_LONG).show()
            }
            
            isSavingDraft = false
        }
    }
    
    fun sendEmail(scheduledTime: Long? = null) {
        scope.launch {
            isSending = true
            
            val account = activeAccount
            if (account == null) {
                Toast.makeText(context, accountNotFoundMsg, Toast.LENGTH_SHORT).show()
                isSending = false
                return@launch
            }
            
            val password = accountRepo.getPassword(account.id)
            if (password == null) {
                Toast.makeText(context, authErrorMsg, Toast.LENGTH_SHORT).show()
                isSending = false
                return@launch
            }
            
            // Если запланировано - создаём WorkManager задачу
            if (scheduledTime != null) {
                val delay = scheduledTime - System.currentTimeMillis()
                if (delay > 0) {
                    scheduleEmail(context, account.id, to, cc, bcc, subject, body, delay, requestReadReceipt, requestDeliveryReceipt, if (highPriority) 2 else 1)
                    Toast.makeText(context, sendScheduledMsg, Toast.LENGTH_SHORT).show()
                    onSent()
                    isSending = false
                    return@launch
                }
            }
            
            // Читаем данные вложений
            val attachmentDataList = withContext(Dispatchers.IO) {
                attachments.mapNotNull { att ->
                    try {
                        val bytes = context.contentResolver.openInputStream(att.uri)?.use { it.readBytes() }
                        if (bytes != null) Triple(att.name, att.mimeType, bytes) else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            
            val client = EasClient(
                serverUrl = account.serverUrl,
                username = account.username,
                password = password,
                domain = account.domain,
                acceptAllCerts = account.acceptAllCerts,
                deviceIdSuffix = account.email,
                certificatePath = account.certificatePath
            )
            
            val result = if (attachmentDataList.isEmpty()) {
                client.sendMail(to, subject, body, cc, bcc, importance = if (highPriority) 2 else 1, requestReadReceipt = requestReadReceipt, requestDeliveryReceipt = requestDeliveryReceipt)
            } else {
                client.sendMailWithAttachments(to, subject, body, cc, bcc, attachmentDataList, requestReadReceipt, requestDeliveryReceipt, importance = if (highPriority) 2 else 1)
            }
            
            when (result) {
                is EasResult.Success -> {
                    // Удаляем черновик если редактировали
                    editDraftId?.let { draftId ->
                        withContext(Dispatchers.IO) {
                            database.emailDao().delete(draftId)
                        }
                    }
                    val isRussian = currentLanguage == AppLanguage.RUSSIAN
                    Toast.makeText(context, NotificationStrings.getEmailSent(isRussian), Toast.LENGTH_SHORT).show()
                    com.iwo.mailclient.util.SoundPlayer.playSendSound(context)
                    onSent()
                }
                is EasResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
            isSending = false
        }
    }
    
    // Диалог подтверждения выхода — сохранить или нет
    if (showDiscardDialog) {
        com.iwo.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            icon = { Icon(AppIcons.Edit, null) },
            title = { Text(Strings.discardDraftQuestion) },
            text = { Text(Strings.draftWillBeDeleted) },
            confirmButton = {
                com.iwo.mailclient.ui.theme.GradientDialogButton(
                    onClick = { 
                        showDiscardDialog = false
                        saveDraft()
                    },
                    text = Strings.saveDraft,
                    enabled = !isSavingDraft
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showDiscardDialog = false
                        onBackClick()
                    },
                    enabled = !isSavingDraft
                ) {
                    Text(Strings.doNotSave)
                }
            }
        )
    }
    
    // Диалог планирования отправки
    if (showScheduleDialog) {
        ScheduleSendDialog(
            onDismiss = { showScheduleDialog = false },
            onSchedule = { time ->
                showScheduleDialog = false
                sendEmail(time)
            }
        )
    }
    
    // Диалог выбора подписи
    if (showSignaturePicker) {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showSignaturePicker = false },
            title = { Text(Strings.selectSignature) },
            text = {
                Column {
                    // Список подписей (без опции "Без подписи" — всегда должна быть выбрана одна)
                    signatures.forEach { signature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val oldSignature = selectedSignature
                                    selectedSignature = signature
                                    // Заменяем подпись в body
                                    body = if (oldSignature != null || body.contains("\n\n--\n")) {
                                        body.replace(SIGNATURE_REGEX, "\n\n--\n${signature.text}")
                                    } else {
                                        body + "\n\n--\n${signature.text}"
                                    }
                                    showSignaturePicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                AppIcons.Draw,
                                null,
                                modifier = Modifier.size(24.dp),
                                tint = if (signature.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    signature.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selectedSignature?.id == signature.id) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    signature.text.take(50) + if (signature.text.length > 50) "..." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            if (selectedSignature?.id == signature.id) {
                                Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSignaturePicker = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог выбора аккаунта отправителя
    if (showAccountPicker) {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showAccountPicker = false },
            title = { Text(Strings.selectSender) },
            text = {
                Column {
                    allAccounts.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeAccount = account
                                    showAccountPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(account.color)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = account.displayName.firstOrNull()?.uppercase() ?: "?",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    account.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (account.id == activeAccount?.id) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    account.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (account.id == activeAccount?.id) {
                                Icon(
                                    AppIcons.Check,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountPicker = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог выбора контактов
    if (showContactPicker) {
        activeAccount?.id?.let { accountId ->
            ContactPickerDialog(
                accountId = accountId,
                database = database,
                onDismiss = { showContactPicker = false },
                onContactsSelected = { emails ->
                    // Добавляем выбранные email к соответствующему полю
                    val newEmails = emails.joinToString(", ")
                    when (contactPickerTarget) {
                        "to" -> {
                            to = if (to.isBlank()) newEmails 
                                  else "${to.trimEnd(',', ' ')}, $newEmails"
                        }
                        "cc" -> {
                            cc = if (cc.isBlank()) newEmails 
                                  else "${cc.trimEnd(',', ' ')}, $newEmails"
                        }
                        "bcc" -> {
                            bcc = if (bcc.isBlank()) newEmails 
                                   else "${bcc.trimEnd(',', ' ')}, $newEmails"
                        }
                    }
                }
            )
        }
    }
    
    // Перехват системного жеста "назад" (свайп)
    BackHandler {
        val hasContent = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank() || attachments.isNotEmpty()
        if (hasContent) {
            showDiscardDialog = true
        } else {
            onBackClick()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = {
                        // Если есть контент — показываем диалог
                        val hasContent = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank() || attachments.isNotEmpty()
                        if (hasContent) {
                            showDiscardDialog = true
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                actions = {
                    // Кнопка выбора подписи (только если больше 1 подписи)
                    if (signatures.size > 1) {
                        IconButton(onClick = { showSignaturePicker = true }) {
                            Icon(AppIcons.Draw, contentDescription = if (currentLanguage == AppLanguage.RUSSIAN) "Подпись" else "Signature", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(AppIcons.Attachment, Strings.attach, tint = Color.White)
                    }
                    IconButton(
                        onClick = { sendEmail() },
                        enabled = !isSending && to.isNotBlank()
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(AppIcons.Send, Strings.send, tint = Color.White)
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(AppIcons.MoreVert, Strings.more, tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(Strings.requestReadReceipt) },
                                onClick = { requestReadReceipt = !requestReadReceipt },
                                leadingIcon = {
                                    Checkbox(
                                        checked = requestReadReceipt,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.requestDeliveryReceipt) },
                                onClick = { requestDeliveryReceipt = !requestDeliveryReceipt },
                                leadingIcon = {
                                    Checkbox(
                                        checked = requestDeliveryReceipt,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.highPriority) },
                                onClick = { highPriority = !highPriority },
                                leadingIcon = {
                                    Checkbox(
                                        checked = highPriority,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(Strings.scheduleSend) },
                                onClick = {
                                    showMenu = false
                                    showScheduleDialog = true
                                },
                                leadingIcon = { Icon(AppIcons.Schedule, null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            LocalColorTheme.current.gradientStart,
                            LocalColorTheme.current.gradientEnd
                        )
                    )
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // От кого - кликабельное для выбора аккаунта
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (allAccounts.size > 1) showAccountPicker = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(Strings.from, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp), maxLines = 1, softWrap = false)
                Text(
                    text = activeAccount?.email ?: Strings.loading,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (allAccounts.size > 1) {
                    Icon(
                        AppIcons.ExpandMore,
                        contentDescription = Strings.selectAccount,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            
            // Кому с автодополнением
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toFocusRequester.requestFocus() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Strings.to, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp), maxLines = 1, softWrap = false)
                    TextField(
                        value = to,
                        onValueChange = { newValue ->
                            to = newValue
                            activeAccount?.id?.let { accountId ->
                                searchSuggestions(newValue, accountId)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(toFocusRequester)
                            .onFocusChanged { focusState ->
                                toFieldFocused = focusState.isFocused
                                if (focusState.isFocused) focusedFieldIndex = 0
                                if (!focusState.isFocused) {
                                    showToSuggestions = false
                                }
                            },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface
                        ),
                        singleLine = false,
                        maxLines = 3
                    )
                    // Кнопка выбора контактов
                    IconButton(onClick = { 
                        contactPickerTarget = "to"
                        showContactPicker = true 
                    }) {
                        Icon(
                            AppIcons.PersonAdd,
                            contentDescription = if (currentLanguage == AppLanguage.RUSSIAN) "Выбрать контакты" else "Select contacts"
                        )
                    }
                    IconButton(onClick = { showCcBcc = !showCcBcc }) {
                        Icon(
                            if (showCcBcc) AppIcons.ExpandLess else AppIcons.ExpandMore,
                            Strings.showCopy
                        )
                    }
                }
                
                // Выпадающий список подсказок
                DropdownMenu(
                    expanded = showToSuggestions && toSuggestions.isNotEmpty(),
                    onDismissRequest = { showToSuggestions = false },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .heightIn(max = 300.dp),
                    properties = PopupProperties(focusable = false)
                ) {
                    toSuggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    if (suggestion.name.isNotBlank() && suggestion.name != suggestion.email) {
                                        Text(
                                            suggestion.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        suggestion.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                to = suggestion.email
                                showToSuggestions = false
                                // Увеличиваем счётчик использования контакта
                                if (suggestion.source == SuggestionSource.CONTACT) {
                                    scope.launch {
                                        activeAccount?.id?.let { accountId ->
                                            withContext(Dispatchers.IO) {
                                                database.contactDao().incrementUseCountByEmail(accountId, suggestion.email)
                                            }
                                        }
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    when (suggestion.source) {
                                        SuggestionSource.CONTACT -> AppIcons.Person
                                        SuggestionSource.HISTORY -> AppIcons.History
                                        SuggestionSource.GAL -> AppIcons.Business
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }
            HorizontalDivider()
            
            // Копия и Скрытая копия
            if (showCcBcc) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ccFocusRequester.requestFocus() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Strings.cc, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp), maxLines = 1, softWrap = false)
                    TextField(
                        value = cc,
                        onValueChange = { cc = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(ccFocusRequester)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 1 },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface
                        ),
                        singleLine = false,
                        maxLines = 3
                    )
                    // Кнопка выбора контактов для Cc
                    IconButton(onClick = { 
                        contactPickerTarget = "cc"
                        showContactPicker = true 
                    }) {
                        Icon(
                            AppIcons.PersonAdd,
                            contentDescription = if (currentLanguage == AppLanguage.RUSSIAN) "Выбрать контакты" else "Select contacts"
                        )
                    }
                }
                HorizontalDivider()
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bccFocusRequester.requestFocus() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Strings.hiddenCopy, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp), maxLines = 1, softWrap = false)
                    TextField(
                        value = bcc,
                        onValueChange = { bcc = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(bccFocusRequester)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 2 },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface
                        ),
                        singleLine = false,
                        maxLines = 3
                    )
                    // Кнопка выбора контактов для Bcc
                    IconButton(onClick = { 
                        contactPickerTarget = "bcc"
                        showContactPicker = true 
                    }) {
                        Icon(
                            AppIcons.PersonAdd,
                            contentDescription = if (currentLanguage == AppLanguage.RUSSIAN) "Выбрать контакты" else "Select contacts"
                        )
                    }
                }
                HorizontalDivider()
            }
            
            // Тема
            TextField(
                value = subject,
                onValueChange = { subject = it },
                placeholder = { Text(Strings.subject) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(subjectFocusRequester)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 3 },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true
            )
            HorizontalDivider()
            
            // Тело письма - фиксированная минимальная высота
            TextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text(Strings.messageText) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 200.dp)
                    .padding(horizontal = 16.dp)
                    .focusRequester(bodyFocusRequester)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 4 },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface
                ),
                minLines = 8
            )
            
            // Вложения - внизу после текста
            if (attachments.isNotEmpty()) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${Strings.attachmentsCount} (${attachments.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    attachments.forEach { att ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.InsertDriveFile, null, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(att.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(formatFileSize(att.size), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { attachments = attachments - att }) {
                                    Icon(AppIcons.Close, Strings.delete, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ScheduleSendDialog(
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit
) {
    val context = LocalContext.current
    var showCustomPicker by remember { mutableStateOf(false) }
    var customDate by remember { mutableStateOf(Calendar.getInstance()) }
    
    val calendar = Calendar.getInstance()
    
    // Варианты времени
    val tomorrowMorning = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 8)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    
    val tomorrowAfternoon = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 13)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    
    val mondayMorning = Calendar.getInstance().apply {
        // Находим следующий понедельник
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            add(Calendar.DAY_OF_YEAR, 1)
        }
        if (timeInMillis <= System.currentTimeMillis()) {
            add(Calendar.WEEK_OF_YEAR, 1)
        }
        set(Calendar.HOUR_OF_DAY, 8)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    
    val dateFormat = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
    val fullDateFormat = java.text.SimpleDateFormat("d MMM yyyy, HH:mm:ss", java.util.Locale.getDefault())
    
    if (showCustomPicker) {
        // Отдельные state для редактирования времени
        var hourText by remember { mutableStateOf(String.format("%02d", customDate.get(Calendar.HOUR_OF_DAY))) }
        var minuteText by remember { mutableStateOf(String.format("%02d", customDate.get(Calendar.MINUTE))) }
        var secondText by remember { mutableStateOf(String.format("%02d", customDate.get(Calendar.SECOND))) }
        
        // Диалог выбора кастомной даты и времени
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showCustomPicker = false },
            title = { Text(Strings.selectDateTime) },
            text = {
                Column {
                    // Дата
                    OutlinedTextField(
                        value = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(customDate.time),
                        onValueChange = { },
                        label = { Text(Strings.date) },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                // Показываем DatePicker через Android API
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        customDate = (customDate.clone() as Calendar).apply {
                                            set(Calendar.YEAR, year)
                                            set(Calendar.MONTH, month)
                                            set(Calendar.DAY_OF_MONTH, day)
                                        }
                                    },
                                    customDate.get(Calendar.YEAR),
                                    customDate.get(Calendar.MONTH),
                                    customDate.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }) {
                                Icon(AppIcons.DateRange, Strings.selectDate)
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Время (часы:минуты:секунды)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { value ->
                                // Разрешаем только цифры и максимум 2 символа
                                if (value.length <= 2 && value.all { it.isDigit() }) {
                                    hourText = value
                                    value.toIntOrNull()?.let { hour ->
                                        if (hour in 0..23) {
                                            customDate = (customDate.clone() as Calendar).apply {
                                                set(Calendar.HOUR_OF_DAY, hour)
                                            }
                                        }
                                    }
                                }
                            },
                            label = { Text(Strings.hour) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = minuteText,
                            onValueChange = { value ->
                                if (value.length <= 2 && value.all { it.isDigit() }) {
                                    minuteText = value
                                    value.toIntOrNull()?.let { minute ->
                                        if (minute in 0..59) {
                                            customDate = (customDate.clone() as Calendar).apply {
                                                set(Calendar.MINUTE, minute)
                                            }
                                        }
                                    }
                                }
                            },
                            label = { Text(Strings.minute) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = secondText,
                            onValueChange = { value ->
                                if (value.length <= 2 && value.all { it.isDigit() }) {
                                    secondText = value
                                    value.toIntOrNull()?.let { second ->
                                        if (second in 0..59) {
                                            customDate = (customDate.clone() as Calendar).apply {
                                                set(Calendar.SECOND, second)
                                            }
                                        }
                                    }
                                }
                            },
                            label = { Text(Strings.second) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        "${Strings.send}: ${fullDateFormat.format(customDate.time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { 
                        onSchedule(customDate.timeInMillis)
                    },
                    enabled = customDate.timeInMillis > System.currentTimeMillis()
                ) {
                    Text(Strings.schedule)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPicker = false }) {
                    Text(Strings.back)
                }
            }
        )
    } else {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Strings.scheduleSend) },
            text = {
                Column {
                    // Завтра утром
                    ScheduleOption(
                        icon = AppIcons.WbSunny,
                        title = Strings.tomorrowMorning,
                        subtitle = dateFormat.format(tomorrowMorning.time),
                        onClick = { onSchedule(tomorrowMorning.timeInMillis) }
                    )
                    
                    // Завтра днём
                    ScheduleOption(
                        icon = AppIcons.LightMode,
                        title = Strings.tomorrowAfternoon,
                        subtitle = dateFormat.format(tomorrowAfternoon.time),
                        onClick = { onSchedule(tomorrowAfternoon.timeInMillis) }
                    )
                    
                    // В понедельник утром
                    ScheduleOption(
                        icon = AppIcons.DateRange,
                        title = Strings.mondayMorning,
                        subtitle = dateFormat.format(mondayMorning.time),
                        onClick = { onSchedule(mondayMorning.timeInMillis) }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Выбрать дату и время
                    ScheduleOption(
                        icon = AppIcons.EditCalendar,
                        title = Strings.selectDateTime,
                        subtitle = Strings.specifyExactTime,
                        onClick = { showCustomPicker = true }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        "${Strings.timezone}: ${TimeZone.getDefault().displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(Strings.cancel)
                }
            }
        )
    }
}

@Composable
private fun ScheduleOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun scheduleEmail(
    context: android.content.Context,
    accountId: Long,
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    delayMillis: Long,
    requestReadReceipt: Boolean = false,
    requestDeliveryReceipt: Boolean = false,
    importance: Int = 1
) {
    val data = workDataOf(
        "accountId" to accountId,
        "to" to to,
        "cc" to cc,
        "bcc" to bcc,
        "subject" to subject,
        "body" to body,
        "requestReadReceipt" to requestReadReceipt,
        "requestDeliveryReceipt" to requestDeliveryReceipt,
        "importance" to importance
    )
    
    val request = OneTimeWorkRequestBuilder<ScheduledEmailWorker>()
        .setInputData(data)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()
    
    WorkManager.getInstance(context).enqueue(request)
}

private fun formatDate(timestamp: Long): String {
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> if (java.util.Locale.getDefault().language == "ru") "$bytes Б" else "$bytes B"
        bytes < 1024 * 1024 -> if (java.util.Locale.getDefault().language == "ru") "${bytes / 1024} КБ" else "${bytes / 1024} KB"
        else -> if (java.util.Locale.getDefault().language == "ru") String.format("%.1f МБ", bytes / (1024.0 * 1024.0)) else String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/**
 * Worker для отложенной отправки письма
 */
class ScheduledEmailWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val accountId = inputData.getLong("accountId", -1)
        val to = inputData.getString("to") ?: return Result.failure()
        val cc = inputData.getString("cc") ?: ""
        val bcc = inputData.getString("bcc") ?: ""
        val subject = inputData.getString("subject") ?: ""
        val body = inputData.getString("body") ?: ""
        val requestReadReceipt = inputData.getBoolean("requestReadReceipt", false)
        val requestDeliveryReceipt = inputData.getBoolean("requestDeliveryReceipt", false)
        val importance = inputData.getInt("importance", 1)
        
        val accountRepo = AccountRepository(applicationContext)
        val account = accountRepo.getAccount(accountId) ?: return Result.failure()
        val password = accountRepo.getPassword(accountId) ?: return Result.failure()
        
        val client = EasClient(
            serverUrl = account.serverUrl,
            username = account.username,
            password = password,
            domain = account.domain,
            acceptAllCerts = account.acceptAllCerts,
            deviceIdSuffix = account.email,
            certificatePath = account.certificatePath
        )
        
        return when (client.sendMail(to, subject, body, cc, bcc, importance = importance, requestReadReceipt = requestReadReceipt, requestDeliveryReceipt = requestDeliveryReceipt)) {
            is EasResult.Success -> {
                // Показываем уведомление об успешной отправке
                val isRu = java.util.Locale.getDefault().language == "ru"
                val title = NotificationStrings.getEmailSent(isRu)
                val text = NotificationStrings.getScheduledEmailSent(to, isRu)
                showNotification(title, text)
                Result.success()
            }
            is EasResult.Error -> Result.retry()
        }
    }
    
    private fun showNotification(title: String, text: String) {
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext, 
            com.iwo.mailclient.MailApplication.CHANNEL_NEW_MAIL
        )
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        applicationContext.getSystemService(android.app.NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), notification)
    }
}

/**
 * Очищает HTML теги из текста, оставляя только plain text
 */
private fun stripHtml(html: String): String {
    if (html.isBlank()) return ""
    
    return html
        // Заменяем <br>, <br/>, <br /> на переносы строк
        .replace(BR_REGEX, "\n")
        // Заменяем </p>, </div>, </li> на переносы строк
        .replace(BLOCK_END_REGEX, "\n")
        // Заменяем </td> на табуляцию
        .replace(TD_END_REGEX, "\t")
        // Удаляем <style>...</style> и <script>...</script> блоки
        .replace(STYLE_REGEX, "")
        .replace(SCRIPT_REGEX, "")
        // Удаляем HTML комментарии
        .replace(COMMENT_REGEX, "")
        // Удаляем все оставшиеся HTML теги
        .replace(TAG_REGEX, "")
        // Декодируем HTML entities
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        // Убираем множественные пробелы
        .replace(SPACES_REGEX, " ")
        // Убираем множественные переносы строк (больше 2 подряд)
        .replace(NEWLINES_REGEX, "\n\n")
        // Убираем пробелы в начале и конце строк
        .lines().joinToString("\n") { it.trim() }
        .trim()
}
