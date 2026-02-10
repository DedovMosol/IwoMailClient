package com.dedovmosol.iwomail.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll

import com.dedovmosol.iwomail.ui.theme.AppIcons
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.SignatureEntity
import com.dedovmosol.iwomail.data.repository.ContactRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.ContactPickerDialog
import com.dedovmosol.iwomail.ui.components.NetworkBanner
import com.dedovmosol.iwomail.ui.components.RichTextEditor
import com.dedovmosol.iwomail.ui.components.RichTextToolbar
import com.dedovmosol.iwomail.ui.components.rememberRichTextEditorController
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.util.escapeHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIGNATURE_REGEX = Regex("\n\n--\n.*", RegexOption.DOT_MATCHES_ALL)
// HTML версия подписи - ищем от открывающего до закрывающего маркера
// Жадный квантификатор .* захватит всё до последнего <!--/signature-->
private val HTML_SIGNATURE_REGEX = Regex("<div class=\"signature\"[^>]*>.*</div><!--/signature-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * Формирует HTML подпись
 * @param text текст подписи
 * @param isHtml true если подпись уже в HTML формате, false если plain text
 */
private fun formatHtmlSignature(text: String?, isHtml: Boolean = false): String {
    if (text.isNullOrBlank()) return ""
    
    val content = if (isHtml) {
        // HTML подпись — используем как есть
        text
    } else {
        // Plain text — экранируем и заменяем переносы строк
        text.escapeHtml().replace("\n", "<br>")
    }
    // Закрывающий комментарий <!--/signature--> используется как маркер конца для regex
    return "<div class=\"signature\"><br>--<br>$content</div><!--/signature-->"
}

/**
 * Формирует HTML цитату для Reply/Forward
 */
private fun formatHtmlQuote(
    header: String,
    from: String,
    date: String,
    subject: String,
    toField: String?,
    originalBody: String
): String {
    val toLine = if (toField != null) "<b>To:</b> ${toField.escapeHtml()}<br>" else ""
    return """
        <br><br>
        <div style="border-left: 2px solid #ccc; padding-left: 10px; margin-left: 5px; color: #666;">
            <b>--- ${header.escapeHtml()} ---</b><br>
            <b>From:</b> ${from.escapeHtml()}<br>
            <b>Date:</b> ${date.escapeHtml()}<br>
            <b>Subject:</b> ${subject.escapeHtml()}<br>
            $toLine
            <br>
            $originalBody
        </div>
    """.trimIndent()
}

// Regex для определения HTML контента (ищем HTML теги)
private val HTML_TAG_REGEX = Regex("<[a-zA-Z][^>]*>")
private val BRACKET_EMAIL_REGEX = Regex("<([^>]+@[^>]+)>")
private val SIMPLE_EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
// Предкомпилированные regex для normalizeRecipients и обработки вложений
private val NORMALIZE_EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
private val NORMALIZE_BRACKET_REGEX = Regex("<([^>]+)>")
private val SAFE_FILENAME_COMPOSE_REGEX = Regex("[^a-zA-Z0-9._-]")

/**
 * Проверяет, содержит ли строка HTML теги
 */
private fun String.looksLikeHtml(): Boolean = HTML_TAG_REGEX.containsMatchIn(this)

private fun extractEmailFromString(raw: String, queryHint: String? = null): String? {
    if (raw.isBlank()) return null
    val cleaned = raw.replace("\r", " ").replace("\n", " ").trim()
    if (cleaned.isBlank()) return null
    val emails = SIMPLE_EMAIL_REGEX.findAll(cleaned).map { it.value }.toList()
    val queryLower = queryHint?.trim()?.lowercase().orEmpty()
    if (queryLower.isNotEmpty()) {
        emails.firstOrNull { it.lowercase().contains(queryLower) }?.let { return it }
    }
    BRACKET_EMAIL_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let { return it }
    return emails.firstOrNull()
}

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

/**
 * Качество сжатия изображения для вставки inline
 */
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
    // Для сохранения при повороте экрана
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    replyToEmailId: String? = null,
    forwardEmailId: String? = null,
    initialToEmail: String? = null,
    editDraftId: String? = null,
    initialSubject: String? = null,
    initialBody: String? = null,
    onBackClick: () -> Unit,
    onSent: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val currentLanguage = LocalLanguage.current
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }
    
    // Контроллер отложенной отправки
    val sendController = com.dedovmosol.iwomail.ui.components.LocalSendController.current
    
    // Аккаунт отправителя
    var activeAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var allAccounts by remember { mutableStateOf<List<AccountEntity>>(emptyList()) }
    var showAccountPicker by remember { mutableStateOf(false) }
    // Сохраняем ID активного аккаунта для восстановления после фона
    var savedActiveAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    
    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var isSending by rememberSaveable { mutableStateOf(false) }
    var showCcBcc by rememberSaveable { mutableStateOf(false) }
    
    // Флаг для скрытия WebView перед навигацией (избегаем краша RenderThread)
    var hideWebView by remember { mutableStateOf(false) }
    
    // Rich Text Editor контроллер
    val richTextController = rememberRichTextEditorController()
    
    // Сохраняем вложения как строки для переживания поворота экрана
    var attachmentStrings by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val attachments = remember(attachmentStrings) { 
        attachmentStrings.mapNotNull { AttachmentInfo.fromSaveableString(it) } 
    }
    var requestReadReceipt by rememberSaveable { mutableStateOf(false) }
    var requestDeliveryReceipt by rememberSaveable { mutableStateOf(false) }
    var highPriority by rememberSaveable { mutableStateOf(false) }

    fun normalizeRecipients(value: String): String {
        val emailRegex = NORMALIZE_EMAIL_REGEX
        val bracketRegex = NORMALIZE_BRACKET_REGEX
        val tokens = value.split(",", ";")
        val result = tokens.mapNotNull { token ->
            val trimmed = token.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val bracket = bracketRegex.find(trimmed)?.groupValues?.get(1)
            val cleaned = (bracket ?: trimmed).replace("\"", "").trim()
            val emailMatch = emailRegex.find(cleaned)?.value
            // Возвращаем только валидные email адреса
            emailMatch
        }
        return result.distinct().joinToString(", ")
    }

    fun replaceCidWithDataUrl(html: String, inlineImages: Map<String, String>): String {
        var result = html
        inlineImages.forEach { (cid, dataUrl) ->
            result = result
                .replace("cid:$cid", dataUrl)
                .replace("cid:${cid.removePrefix("<").removeSuffix(">")}", dataUrl)
        }
        return result
    }
    
    // Загрузка вложений из Share intent
    var shareAttachmentsLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!shareAttachmentsLoaded && com.dedovmosol.iwomail.ui.navigation.ShareIntentData.attachments.isNotEmpty()) {
            shareAttachmentsLoaded = true
            val shareUris = com.dedovmosol.iwomail.ui.navigation.ShareIntentData.attachments
            com.dedovmosol.iwomail.ui.navigation.ShareIntentData.clear()
            
            val newAttachmentStrings = mutableListOf<String>()
            for (uri in shareUris) {
                try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            val name = if (nameIndex >= 0) it.getString(nameIndex) else "attachment"
                            val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            val attInfo = AttachmentInfo(uri, name, size, mimeType)
                            newAttachmentStrings.add(attInfo.toSaveableString())
                        }
                    }
                } catch (_: Exception) { }
            }
            if (newAttachmentStrings.isNotEmpty()) {
                attachmentStrings = attachmentStrings + newAttachmentStrings
            }
        }
    }
    
    // Инициализация из mailto: параметров
    var initialDataApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSubject, initialBody) {
        if (!initialDataApplied && (initialSubject != null || initialBody != null)) {
            initialDataApplied = true
            if (initialSubject != null && subject.isBlank()) subject = initialSubject
            if (initialBody != null && body.isBlank()) body = initialBody
        }
    }
    
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
    var suggestionJustSelected by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Скрываем подсказки при выходе с экрана
    DisposableEffect(Unit) {
        onDispose {
            suggestionSearchJob?.cancel()
            showToSuggestions = false
            toSuggestions = emptyList()
        }
    }
    
    fun extractQueryPart(text: String): String {
        val separators = charArrayOf(',', ';', '\n')
        val lastSeparatorIndex = text.lastIndexOfAny(separators)
        return if (lastSeparatorIndex >= 0) {
            text.substring(lastSeparatorIndex + 1).trim()
        } else {
            text.trim()
        }
    }
    
    fun replaceLastRecipient(text: String, newEmail: String): String {
        val separators = charArrayOf(',', ';', '\n')
        val lastSeparatorIndex = text.lastIndexOfAny(separators)
        if (lastSeparatorIndex < 0) return newEmail
        val prefix = text.substring(0, lastSeparatorIndex + 1)
        val normalizedPrefix = if (prefix.endsWith(" ") || prefix.isEmpty()) prefix else "$prefix "
        return normalizedPrefix + newEmail
    }
    
    // Функция поиска подсказок
    fun searchSuggestions(query: String, accountId: Long, accountEmail: String) {
        suggestionSearchJob?.cancel()
        // Берем только последний введенный адрес (после запятой/точки с запятой/перевода строки)
        val queryPart = extractQueryPart(query)
        val queryPartLower = queryPart.trim().lowercase()
        val queryToken = queryPart.substringBefore("@").trim().lowercase()
        if (queryToken.length < 3) {
            toSuggestions = emptyList()
            showToSuggestions = false
            return
        }
        // Если email уже полностью введён (содержит @ и домен с точкой) — не показываем подсказки
        if (queryPartLower.contains("@") && queryPartLower.substringAfter("@").contains(".")) {
            toSuggestions = emptyList()
            showToSuggestions = false
            return
        }
        
        // Email текущего аккаунта — не предлагаем самого себя
        val ownEmail = accountEmail.lowercase()
        
        suggestionSearchJob = scope.launch {
            val suggestions = mutableListOf<EmailSuggestion>()
            val seenEmails = mutableSetOf<String>() // Для отслеживания дубликатов
            
            // Функция нормализации email - извлекает email из формата "Name <email>" и приводит к lowercase
            fun normalizeEmail(email: String): String {
                return extractEmailFromString(email, queryToken)?.lowercase() ?: ""
            }
            
            val ownEmailNormalized = normalizeEmail(ownEmail)
            
            // 1. Поиск по локальным контактам (мгновенно)
            withContext(Dispatchers.IO) {
                val contacts = database.contactDao().searchForAutocomplete(accountId, queryToken, ownEmail, 5)
                contacts.forEach { contact ->
                    val emailNormalized = normalizeEmail(contact.email)
                    // Не добавляем дубликаты и самого себя
                    if (emailNormalized.isNotBlank() && emailNormalized !in seenEmails && emailNormalized != ownEmailNormalized) {
                        seenEmails.add(emailNormalized)
                        val cleanEmail = extractEmailFromString(contact.email, queryToken) ?: return@forEach
                        suggestions.add(EmailSuggestion(
                            email = cleanEmail,
                            name = contact.displayName,
                            source = SuggestionSource.CONTACT
                        ))
                    }
                }
            }
            
            // 2. Поиск по истории писем (мгновенно)
            withContext(Dispatchers.IO) {
                val history = database.emailDao().searchEmailHistory(accountId, queryToken, ownEmail, 5)
                history.forEach { result ->
                    // Нормализуем email - убираем <>, пробелы и приводим к нижнему регистру
                    val emailNormalized = normalizeEmail(result.email)
                    // Не добавляем дубликаты и самого себя
                    if (emailNormalized.isNotBlank() && emailNormalized !in seenEmails && emailNormalized != ownEmailNormalized) {
                        seenEmails.add(emailNormalized)
                        val cleanEmail = extractEmailFromString(result.email, queryToken) ?: return@forEach
                        suggestions.add(EmailSuggestion(
                            email = cleanEmail,
                            name = result.name,
                            source = SuggestionSource.HISTORY
                        ))
                    }
                }
            }
            
            // Проверяем что корутина не отменена (защита от race condition с выбором подсказки)
            kotlinx.coroutines.yield()
            toSuggestions = suggestions.take(8)
            showToSuggestions = suggestions.isNotEmpty() && toFieldFocused
            
            // 3. Поиск по GAL с задержкой (если ≥3 символа)
            if (queryToken.length >= 3) {
                delay(500) // Debounce
                try {
                    kotlinx.coroutines.yield()
                    val client = accountRepo.createEasClient(accountId)
                    if (client != null) {
                        val galResult = withContext(Dispatchers.IO) {
                            client.searchGAL(queryToken)
                        }
                        kotlinx.coroutines.yield()
                        if (galResult is EasResult.Success) {
                            val galSuggestions = galResult.data.take(5).mapNotNull { gal ->
                                val emailNormalized = normalizeEmail(gal.email)
                                // Не добавляем дубликаты и самого себя
                                if (emailNormalized !in seenEmails && emailNormalized != ownEmailNormalized) {
                                    seenEmails.add(emailNormalized)
                                    val cleanEmail = extractEmailFromString(gal.email, queryToken) ?: return@mapNotNull null
                                    EmailSuggestion(
                                        email = cleanEmail,
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
    
    // Отслеживание начального аккаунта для определения изменений
    var initialAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    
    // Функция навигации назад с переключением аккаунта если он изменился
    fun handleBackNavigation() {
        // Скрываем WebView перед навигацией чтобы избежать краша RenderThread
        hideWebView = true
        
        scope.launch {
            // Даём WebView время завершить рендеринг
            delay(100)
            
            val currentAccountId = activeAccount?.id
            // Безопасная навигация: проверяем что оба ID не null перед сравнением
            if (currentAccountId != null && initialAccountId != null && currentAccountId != initialAccountId) {
                try {
                    accountRepo.setActiveAccount(currentAccountId)
                } catch (e: Exception) {
                    // Игнорируем ошибки при переключении аккаунта
                }
            }
            
            onBackClick()
        }
    }
    
    // Загружаем активный аккаунт и все аккаунты
    LaunchedEffect(Unit) {
        // Восстанавливаем аккаунт по сохранённому ID или загружаем активный
        activeAccount = if (savedActiveAccountId != null) {
            accountRepo.getAccount(savedActiveAccountId!!)
        } else {
            accountRepo.getActiveAccountSync()
        }
        // Сохраняем ID для восстановления после фона
        savedActiveAccountId = activeAccount?.id
        // Сохраняем начальный аккаунт
        if (initialAccountId == null) {
            initialAccountId = activeAccount?.id
        }
        // Подставляем email из контактов
        if (initialToEmail != null && to.isEmpty()) {
            to = initialToEmail
        }
        accountRepo.accounts.collect { allAccounts = it }
    }
    
    // Перезагружаем подписи при смене аккаунта
    LaunchedEffect(activeAccount?.id) {
        activeAccount?.let { account ->
            signatures = database.signatureDao().getSignaturesByAccountList(account.id)
            // Выбираем подпись по умолчанию или первую
            val newSignature = signatures.find { it.isDefault } ?: signatures.firstOrNull()
            
            // Обновляем подпись в body только для нового письма (не reply/forward/draft)
            if (replyToEmailId == null && forwardEmailId == null && editDraftId == null) {
                val newSignatureHtml = formatHtmlSignature(newSignature?.text, newSignature?.isHtml ?: false)
                
                // Заменяем старую подпись на новую (HTML формат)
                if (body.contains("<div class=\"signature\">")) {
                    body = body.replace(HTML_SIGNATURE_REGEX, newSignatureHtml)
                } else if (newSignatureHtml.isNotBlank()) {
                    // Подпись в конце для нового письма
                    body = body + newSignatureHtml
                }
            }
            
            selectedSignature = newSignature
        } ?: run {
            // Аккаунт не выбран — очищаем подписи
            signatures = emptyList()
            selectedSignature = null
        }
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
                    val newAtt = AttachmentInfo(uri, name, size, mimeType)
                    attachmentStrings = attachmentStrings + newAtt.toSaveableString()
                }
            }
        }
    }
    
    // Диалог выбора качества изображения
    var showImageQualityDialog by rememberSaveable { mutableStateOf(false) }
    var pendingImageUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingImageUri = pendingImageUriString?.let { Uri.parse(it) }
    
    // Лаунчер для вставки изображений inline
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            pendingImageUriString = it.toString()
            showImageQualityDialog = true
        }
    }
    
    // Функция вставки изображения с выбранным качеством
    fun insertImageWithQuality(uri: Uri, quality: ImageQuality) {
        scope.launch {
            try {
                val compressedData = withContext(Dispatchers.IO) {
                    compressImageForInline(
                        context, uri,
                        maxWidth = quality.maxSize,
                        maxHeight = quality.maxSize,
                        quality = quality.jpegQuality
                    )
                }
                compressedData?.let { (bytes, outputMimeType) ->
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    richTextController.insertImage(base64, outputMimeType)
                }
            } catch (e: Exception) {
                // Ошибка при загрузке изображения
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
                val isSentFolder = folder?.type == FolderType.SENT_ITEMS
                
                // Если из Отправленных — отвечаем получателю, иначе отправителю
                // normalizeRecipients() очищает формат "Name" <email> до чистого email
                to = normalizeRecipients(if (isSentFolder) email.to else email.from)
                
                subject = if (email.subject.startsWith("Re:", ignoreCase = true)) {
                    email.subject
                } else {
                    "Re: ${email.subject}"
                }
                val signatureHtml = formatHtmlSignature(
                    selectedSignature?.text ?: activeAccount?.signature,
                    selectedSignature?.isHtml ?: false
                )
                // Оригинальное тело письма (сохраняем HTML если есть)
                val originalBody = if (email.body.looksLikeHtml()) {
                    email.body
                } else {
                    email.body.escapeHtml().replace("\n", "<br>")
                }
                // Структура: [место для ввода] + подпись + цитата
                body = "<br>" + signatureHtml + formatHtmlQuote(
                    header = originalMessageStr,
                    from = email.from,
                    date = formatEmailDate(email.dateReceived),
                    subject = email.subject,
                    toField = null,
                    originalBody = originalBody
                )
                
                // Загружаем вложения оригинального письма для ответа
                // Инлайн-картинки встраиваются в тело (data URL), файлы — как вложения
                val originalAttachments = mailRepo.getAttachmentsSync(emailId)
                if (originalAttachments.isNotEmpty()) {
                    val newAttachmentStrings = mutableListOf<String>()
                    val inlineImages = mutableMapOf<String, String>()
                    val account = accountRepo.getAccount(email.accountId)
                    val easClient = account?.let { accountRepo.createEasClient(it.id) }
                    // collectionId и serverId для fallback-скачивания (когда fileReference пуст)
                    val collectionId = folder?.serverId
                    val emailServerId = email.serverId
                    
                    for (att in originalAttachments) {
                        try {
                            val localPath = att.localPath
                            if (localPath != null && java.io.File(localPath).exists()) {
                                val file = java.io.File(localPath)
                                if (att.isInline && !att.contentId.isNullOrBlank()) {
                                    // Инлайн-вложение — встраиваем как data URL
                                    val bytes = file.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                                    inlineImages[att.contentId] = "data:$mimeType;base64,$base64"
                                } else {
                                    // Файловое вложение
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val attInfo = AttachmentInfo(uri, att.displayName, att.estimatedSize, att.contentType)
                                    newAttachmentStrings.add(attInfo.toSaveableString())
                                }
                            } else if (easClient != null && att.fileReference.isNotBlank()) {
                                // Скачиваем вложение с сервера
                                // Передаём collectionId/serverId для fallback (Exchange 2007 GetAttachment)
                                val downloadResult = easClient.downloadAttachment(
                                    att.fileReference, collectionId, emailServerId
                                )
                                
                                if (downloadResult is com.dedovmosol.iwomail.eas.EasResult.Success) {
                                    val attachmentsDir = java.io.File(context.filesDir, "reply_attachments")
                                    if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                                    val safeFileName = att.displayName.replace(SAFE_FILENAME_COMPOSE_REGEX, "_")
                                    val file = java.io.File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")
                                    java.io.FileOutputStream(file).use { it.write(downloadResult.data) }
                                    
                                    if (att.isInline && !att.contentId.isNullOrBlank()) {
                                        // Инлайн — встраиваем как data URL
                                        val base64 = android.util.Base64.encodeToString(downloadResult.data, android.util.Base64.NO_WRAP)
                                        val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                                        inlineImages[att.contentId] = "data:$mimeType;base64,$base64"
                                    } else {
                                        // Файловое вложение
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val attInfo = AttachmentInfo(uri, att.displayName, att.estimatedSize, att.contentType)
                                        newAttachmentStrings.add(attInfo.toSaveableString())
                                    }
                                } else {
                                    android.util.Log.w("ComposeScreen", "Reply: download failed for ${att.displayName} (ref=${att.fileReference})")
                                }
                            } else {
                                android.util.Log.w("ComposeScreen", "Reply: skipping attachment ${att.displayName} (localPath=$localPath, fileRef='${att.fileReference}', hasClient=${easClient != null})")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ComposeScreen", "Reply: error loading attachment ${att.displayName}: ${e.message}")
                        }
                    }
                    
                    if (newAttachmentStrings.isNotEmpty()) {
                        attachmentStrings = attachmentStrings + newAttachmentStrings
                    }
                    
                    // Fallback: если тело содержит неразрешённые cid: ссылки —
                    // загружаем инлайн-картинки через fetchInlineImages (MIME extraction).
                    // Критично для писем из Отправленных, где fileReference часто пустой.
                    if (body.contains("cid:", ignoreCase = true) && easClient != null) {
                        val cidPattern = Regex("cid:([^\"'\\s>]+)")
                        val allCids = cidPattern.findAll(body).map { it.groupValues[1] }.toSet()
                        val resolvedCids = inlineImages.keys.flatMap { 
                            listOf(it, it.removePrefix("<").removeSuffix(">"))
                        }.toSet()
                        val missingCids = allCids.filter { cid -> 
                            !resolvedCids.contains(cid) && !resolvedCids.contains("<$cid>")
                        }
                        if (missingCids.isNotEmpty() && collectionId != null) {
                            try {
                                when (val fetchResult = easClient.fetchInlineImages(collectionId, emailServerId)) {
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        fetchResult.data.forEach { (cid, dataUrl) ->
                                            inlineImages[cid] = dataUrl
                                        }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    
                    if (inlineImages.isNotEmpty() && body.contains("cid:", ignoreCase = true)) {
                        body = replaceCidWithDataUrl(body, inlineImages)
                    }
                }
                
                // Если после обработки вложений в body всё ещё есть cid: —
                // значит не все инлайн-картинки найдены через attachment records.
                // Пробуем fetchInlineImages напрямую (загрузка MIME с сервера).
                if (body.contains("cid:", ignoreCase = true)) {
                    val fbAccount = accountRepo.getAccount(email.accountId)
                    val fbClient = fbAccount?.let { accountRepo.createEasClient(it.id) }
                    if (fbClient != null && folder?.serverId != null) {
                        try {
                            when (val fetchResult = fbClient.fetchInlineImages(folder.serverId, email.serverId)) {
                                is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                    if (fetchResult.data.isNotEmpty()) {
                                        body = replaceCidWithDataUrl(body, fetchResult.data)
                                    }
                                }
                                is com.dedovmosol.iwomail.eas.EasResult.Error -> {}
                            }
                        } catch (_: Exception) {}
                    }
                }
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
                val signatureHtml = formatHtmlSignature(
                    selectedSignature?.text ?: activeAccount?.signature,
                    selectedSignature?.isHtml ?: false
                )
                // Оригинальное тело письма (сохраняем HTML если есть)
                val originalBody = if (email.body.looksLikeHtml()) {
                    email.body
                } else {
                    email.body.escapeHtml().replace("\n", "<br>")
                }
                // Структура: [место для ввода] + подпись + цитата
                body = "<br>" + signatureHtml + formatHtmlQuote(
                    header = forwardedMessageStr,
                    from = email.from,
                    date = formatEmailDate(email.dateReceived),
                    subject = email.subject,
                    toField = email.to,
                    originalBody = originalBody
                )
                
                // Загружаем вложения оригинального письма для пересылки
                // Инлайн-картинки встраиваются в тело (data URL), файлы — как вложения
                val originalAttachments = mailRepo.getAttachmentsSync(emailId)
                if (originalAttachments.isNotEmpty()) {
                    val newAttachmentStrings = mutableListOf<String>()
                    val inlineImages = mutableMapOf<String, String>()
                    val account = accountRepo.getAccount(email.accountId)
                    val easClient = account?.let { accountRepo.createEasClient(it.id) }
                    // Получаем folder для collectionId (fallback-скачивание при пустом fileReference)
                    val fwdFolder = withContext(Dispatchers.IO) {
                        database.folderDao().getFolder(email.folderId)
                    }
                    val collectionId = fwdFolder?.serverId
                    val emailServerId = email.serverId
                    
                    for (att in originalAttachments) {
                        try {
                            val localPath = att.localPath
                            if (localPath != null && java.io.File(localPath).exists()) {
                                val file = java.io.File(localPath)
                                if (att.isInline && !att.contentId.isNullOrBlank()) {
                                    // Инлайн-вложение — встраиваем как data URL
                                    val bytes = file.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                                    inlineImages[att.contentId] = "data:$mimeType;base64,$base64"
                                } else {
                                    // Файловое вложение
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val attInfo = AttachmentInfo(uri, att.displayName, att.estimatedSize, att.contentType)
                                    newAttachmentStrings.add(attInfo.toSaveableString())
                                }
                            } else if (easClient != null && att.fileReference.isNotBlank()) {
                                // Скачиваем вложение с сервера
                                // Передаём collectionId/serverId для fallback (Exchange 2007 GetAttachment)
                                val downloadResult = easClient.downloadAttachment(
                                    att.fileReference, collectionId, emailServerId
                                )
                                
                                if (downloadResult is com.dedovmosol.iwomail.eas.EasResult.Success) {
                                    val attachmentsDir = java.io.File(context.filesDir, "forward_attachments")
                                    if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                                    val safeFileName = att.displayName.replace(SAFE_FILENAME_COMPOSE_REGEX, "_")
                                    val file = java.io.File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")
                                    java.io.FileOutputStream(file).use { it.write(downloadResult.data) }
                                    
                                    if (att.isInline && !att.contentId.isNullOrBlank()) {
                                        // Инлайн — встраиваем как data URL
                                        val base64 = android.util.Base64.encodeToString(downloadResult.data, android.util.Base64.NO_WRAP)
                                        val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                                        inlineImages[att.contentId] = "data:$mimeType;base64,$base64"
                                    } else {
                                        // Файловое вложение
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val attInfo = AttachmentInfo(uri, att.displayName, att.estimatedSize, att.contentType)
                                        newAttachmentStrings.add(attInfo.toSaveableString())
                                    }
                                } else {
                                    android.util.Log.w("ComposeScreen", "Forward: download failed for ${att.displayName} (ref=${att.fileReference})")
                                }
                            } else {
                                android.util.Log.w("ComposeScreen", "Forward: skipping attachment ${att.displayName} (localPath=$localPath, fileRef='${att.fileReference}', hasClient=${easClient != null})")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ComposeScreen", "Forward: error loading attachment ${att.displayName}: ${e.message}")
                        }
                    }
                    if (newAttachmentStrings.isNotEmpty()) {
                        attachmentStrings = attachmentStrings + newAttachmentStrings
                    }
                    
                    // Fallback: если тело содержит неразрешённые cid: ссылки —
                    // загружаем инлайн-картинки через fetchInlineImages (MIME extraction).
                    // Критично для писем из Отправленных, где fileReference часто пустой.
                    if (body.contains("cid:", ignoreCase = true) && easClient != null) {
                        val cidPattern = Regex("cid:([^\"'\\s>]+)")
                        val allCids = cidPattern.findAll(body).map { it.groupValues[1] }.toSet()
                        val resolvedCids = inlineImages.keys.flatMap { 
                            listOf(it, it.removePrefix("<").removeSuffix(">"))
                        }.toSet()
                        val missingCids = allCids.filter { cid -> 
                            !resolvedCids.contains(cid) && !resolvedCids.contains("<$cid>")
                        }
                        if (missingCids.isNotEmpty() && collectionId != null) {
                            try {
                                when (val fetchResult = easClient.fetchInlineImages(collectionId, emailServerId)) {
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        fetchResult.data.forEach { (cid, dataUrl) ->
                                            inlineImages[cid] = dataUrl
                                        }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    
                    if (inlineImages.isNotEmpty() && body.contains("cid:", ignoreCase = true)) {
                        body = replaceCidWithDataUrl(body, inlineImages)
                    }
                }
                
                // Если после обработки вложений в body всё ещё есть cid: —
                // значит не все инлайн-картинки найдены через attachment records.
                // Пробуем fetchInlineImages напрямую (загрузка MIME с сервера).
                if (body.contains("cid:", ignoreCase = true)) {
                    val fbAccount = accountRepo.getAccount(email.accountId)
                    val fbClient = fbAccount?.let { accountRepo.createEasClient(it.id) }
                    val fwdFolderForFetch = withContext(Dispatchers.IO) {
                        database.folderDao().getFolder(email.folderId)
                    }
                    if (fbClient != null && fwdFolderForFetch?.serverId != null) {
                        try {
                            when (val fetchResult = fbClient.fetchInlineImages(fwdFolderForFetch.serverId, email.serverId)) {
                                is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                    if (fetchResult.data.isNotEmpty()) {
                                        body = replaceCidWithDataUrl(body, fetchResult.data)
                                    }
                                }
                                is com.dedovmosol.iwomail.eas.EasResult.Error -> {}
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }
    
    // Флаг: данные черновика уже загружены.
    // rememberSaveable — переживает поворот экрана и process death.
    // Предотвращает повторный запуск LaunchedEffect(editDraftId) при конфигурационных
    // изменениях (поворот), который иначе обнуляет все поля и перезагружает из БД,
    // уничтожая несохранённые правки пользователя.
    var draftLoaded by rememberSaveable { mutableStateOf(false) }
    
    // Загружаем данные черновика для редактирования
    LaunchedEffect(editDraftId) {
        if (draftLoaded) return@LaunchedEffect
        editDraftId?.let { draftId ->
            draftLoaded = true
            // Сбрасываем поля перед заполнением черновика
            to = ""
            cc = ""
            bcc = ""
            subject = ""
            body = ""
            showCcBcc = false
            attachmentStrings = emptyList()

            mailRepo.getEmailSync(draftId)?.let { draft ->
                to = normalizeRecipients(draft.to)
                cc = normalizeRecipients(draft.cc)
                subject = draft.subject
                // Черновик может быть HTML или plain text
                body = if (draft.body.looksLikeHtml()) {
                    draft.body
                } else {
                    draft.body.escapeHtml().replace("\n", "<br>")
                }
                if (cc.isNotBlank()) {
                    showCcBcc = true
                }
                // Ленивая загрузка body если пустой (как в EmailDetailScreen)
                if (body.isBlank() && draft.serverId.isNotBlank() && !draft.serverId.startsWith("local_draft_")) {
                    val result = withContext(Dispatchers.IO) {
                        mailRepo.loadEmailBody(draftId)
                    }
                    if (result is com.dedovmosol.iwomail.eas.EasResult.Success && result.data.isNotBlank()) {
                        body = if (result.data.looksLikeHtml()) {
                            result.data
                        } else {
                            result.data.escapeHtml().replace("\n", "<br>")
                        }
                    }
                }
                // Загружаем вложения черновика
                val draftAttachments = mailRepo.getAttachmentsSync(draftId)
                if (draftAttachments.isNotEmpty()) {
                    val newAttachmentStrings = mutableListOf<String>()
                    val inlineImages = mutableMapOf<String, String>()
                    val account = accountRepo.getAccount(draft.accountId)
                    val easClient = account?.let { accountRepo.createEasClient(it.id) }

                    for (att in draftAttachments) {
                        try {
                            val localPath = att.localPath
                            if (localPath != null && java.io.File(localPath).exists()) {
                                val file = java.io.File(localPath)
                                if (att.isInline && !att.contentId.isNullOrBlank()) {
                                    // Инлайн-вложение — встраиваем как data URL
                                    val bytes = file.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                                    inlineImages[att.contentId] = "data:$mimeType;base64,$base64"
                                } else {
                                    // Файловое вложение
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val attInfo = AttachmentInfo(uri, att.displayName, att.estimatedSize, att.contentType)
                                    newAttachmentStrings.add(attInfo.toSaveableString())
                                }
                            } else if (easClient != null && att.fileReference.isNotBlank()) {
                                when (val result = easClient.downloadDraftAttachment(att.fileReference)) {
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        val attachmentsDir = java.io.File(context.filesDir, "draft_attachments")
                                        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                                        val safeFileName = att.displayName.replace(SAFE_FILENAME_COMPOSE_REGEX, "_")
                                        val file = java.io.File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")
                                        java.io.FileOutputStream(file).use { it.write(result.data) }
                                        if (att.isInline && !att.contentId.isNullOrBlank()) {
                                            val base64 = android.util.Base64.encodeToString(result.data, android.util.Base64.NO_WRAP)
                                            val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                                            inlineImages[att.contentId] = "data:$mimeType;base64,$base64"
                                        } else {
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val attInfo = AttachmentInfo(uri, att.displayName, att.estimatedSize, att.contentType)
                                            newAttachmentStrings.add(attInfo.toSaveableString())
                                        }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {
                                        // Не удалось скачать - пропускаем
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }

                    if (newAttachmentStrings.isNotEmpty()) {
                        attachmentStrings = newAttachmentStrings
                    }
                    if (inlineImages.isNotEmpty() && body.contains("cid:", ignoreCase = true)) {
                        body = replaceCidWithDataUrl(body, inlineImages)
                    }
                }
                
                // Fallback: если body всё ещё содержит cid: после обработки вложений —
                // загружаем инлайн-картинки с сервера через fetchInlineImages (MIME extraction).
                // Критично когда body содержит серверные cid: ссылки (после sync),
                // а локальные вложения отсутствуют или fileReference пустой.
                if (body.contains("cid:", ignoreCase = true)) {
                    val fbAccount = accountRepo.getAccount(draft.accountId)
                    val fbClient = fbAccount?.let { accountRepo.createEasClient(it.id) }
                    if (fbClient != null) {
                        val draftFolder = withContext(Dispatchers.IO) {
                            com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                                .folderDao().getFolder(draft.folderId)
                        }
                        val collId = draftFolder?.serverId
                        val easServerId = draft.serverId
                        if (collId != null && easServerId.contains(":") && !easServerId.contains("=")) {
                            // EAS путь: serverId = "5:17" — fetchInlineImages через ItemOperations
                            try {
                                when (val fetchResult = fbClient.fetchInlineImages(collId, easServerId)) {
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        if (fetchResult.data.isNotEmpty()) {
                                            body = replaceCidWithDataUrl(body, fetchResult.data)
                                        }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {}
                                }
                            } catch (_: Exception) {}
                        } else if (!easServerId.startsWith("local_draft_") && easServerId.isNotBlank()) {
                            // EWS путь: длинный ItemId (содержит "=", base64).
                            // EAS ItemOperations не работает с EWS ItemId.
                            // Загружаем полный MIME через EWS GetItem и парсим inline-картинки.
                            try {
                                when (val ewsResult = withContext(Dispatchers.IO) { fbClient.fetchInlineImagesEws(easServerId) }) {
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        if (ewsResult.data.isNotEmpty()) {
                                            body = replaceCidWithDataUrl(body, ewsResult.data)
                                        }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                
                // КРИТИЧНО: Сохраняем разрешённый body (data: URLs) обратно в БД.
                // После резолвинга cid: → data: тело содержит самодостаточные данные,
                // которые не зависят от вложений и не требуют повторного разрешения.
                // Без этого: при каждом открытии черновика нужен сетевой запрос
                // для скачивания инлайн-картинок, который может упасть.
                // С этим: body в БД всегда содержит data: URLs → отображается мгновенно.
                if (body.isNotBlank() && body != draft.body && !body.contains("cid:", ignoreCase = true)) {
                    withContext(Dispatchers.IO) {
                        try {
                            database.emailDao().updateBody(draftId, body)
                        } catch (_: Exception) {}
                    }
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
                // Файловые вложения — байты
                val fileDraftAttachments = attachments.mapNotNull { att ->
                    try {
                        context.contentResolver.openInputStream(att.uri)?.use { input ->
                            val bytes = input.readBytes()
                            com.dedovmosol.iwomail.eas.DraftAttachmentData(
                                name = att.name,
                                mimeType = att.mimeType,
                                data = bytes
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                
                // Извлекаем inline-картинки из body: data: URL → cid: ссылки.
                // На сервер отправляем cleanBody (с cid:) + вложения через CreateAttachment.
                // Локально храним body (с data: URL) — для отображения в приложении.
                // Outlook десктопный рендерит HTML через Word, который НЕ поддерживает data: URL,
                // поэтому cid: + ContentId — единственный рабочий способ.
                val inlineDraftAttachments = mutableListOf<com.dedovmosol.iwomail.eas.DraftAttachmentData>()
                var cleanBody = body
                val dataUrlRegex = """src\s*=\s*"data:([^;]+);base64,([^"]+)"""".toRegex()
                var inlineCounter = 0
                for (match in dataUrlRegex.findAll(body)) {
                    inlineCounter++
                    val mimeType = match.groupValues[1]
                    val base64Data = match.groupValues[2]
                    val contentId = "img${inlineCounter}_${System.currentTimeMillis()}"
                    val ext = when {
                        mimeType.contains("png") -> ".png"
                        mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                        mimeType.contains("gif") -> ".gif"
                        else -> ".png"
                    }
                    try {
                        val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                        inlineDraftAttachments.add(com.dedovmosol.iwomail.eas.DraftAttachmentData(
                            name = "image_$inlineCounter$ext",
                            mimeType = mimeType,
                            data = bytes,
                            isInline = true,
                            contentId = contentId
                        ))
                        cleanBody = cleanBody.replace(
                            "data:$mimeType;base64,$base64Data",
                            "cid:$contentId"
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("ComposeScreen", "Failed to decode inline image #$inlineCounter: ${e.message}")
                    }
                }
                
                val allServerAttachments = fileDraftAttachments + inlineDraftAttachments
                val normalizedTo = normalizeRecipients(to)
                val normalizedCc = normalizeRecipients(cc)
                
                // Если редактируем существующий черновик
               if (editDraftId != null && editDraftId != "synced" && !editDraftId.startsWith("local_draft_")) {
    val email = withContext(Dispatchers.IO) { database.emailDao().getEmail(editDraftId) }
                    if (email != null) {
                        if (allServerAttachments.isNotEmpty()) {
                            // При наличии вложений: СНАЧАЛА создаём новый, ПОТОМ удаляем старый.
                            // Если создание не удалось — старый черновик остаётся (защита от потери данных).
                            // EWS UpdateItem НЕ обновляет вложения, поэтому нужен delete+create.
                            val oldServerId = email.serverId
                            // P1 FIX: Регистрируем старый черновик как "удалённый" ДО создания нового.
                            // Это предотвращает восстановление старого черновика фоновой синхронизацией
                            // (PushService/SyncWorker) в окне между create нового и delete старого.
                            // Защита действует ~30 сек — достаточно для завершения операции.
                            val oldEmailId = editDraftId ?: "${account.id}_${email.serverId}"
                            com.dedovmosol.iwomail.data.repository.EmailSyncService.registerDeletedEmail(oldEmailId, context)
                            val newServerId = withContext(Dispatchers.IO) {
                                mailRepo.saveDraft(
    accountId = account.id,
    to = normalizedTo, cc = normalizedCc,
    subject = subject,
    serverBody = cleanBody,
    localBody = body,
    fromEmail = account.email,
    fromName = account.displayName,
    hasAttachments = true,
    attachmentFiles = allServerAttachments
)
                            }
                            success = newServerId != null
                            // Удаляем старый черновик ТОЛЬКО после успешного создания нового.
                            // КРИТИЧНО: передаём excludeEwsItemId = newServerId,
                            // чтобы deleteDraft НЕ удалил НОВЫЙ черновик при поиске по subject.
                            if (success && newServerId != null) {
                                withContext(Dispatchers.IO) {
                                    try { mailRepo.deleteDraft(account.id, oldServerId, excludeEwsItemId = newServerId) } catch (_: Exception) {}
                                }
                                // ЗАЩИТА: верифицируем что новая запись существует в БД.
                                // deleteDraft может случайно удалить не тот черновик или
                                // sync-loop внутри deleteDraft может продвинуть syncKey
                                // мимо нового Add. Если запись пропала — воссоздаём.
                                val newEmailId = "${account.id}_$newServerId"
                                val verified = withContext(Dispatchers.IO) {
                                    database.emailDao().getEmail(newEmailId)
                                }
                                if (verified == null) {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val previewText = body.replace(Regex("<[^>]*>"), " ")
                                                .replace(Regex("\\s+"), " ").trim().take(150)
                                            val fallback = com.dedovmosol.iwomail.data.database.EmailEntity(
                                                id = newEmailId,
                                                accountId = account.id,
                                                folderId = email.folderId,
                                                serverId = newServerId,
                                                from = account.email,
                                                fromName = account.displayName,
                                                to = normalizedTo,
                                                cc = normalizedCc,
                                                subject = subject,
                                                preview = previewText,
                                                body = body,
                                                bodyType = 2,
                                                dateReceived = System.currentTimeMillis(),
                                                read = true,
                                                hasAttachments = true
                                            )
                                            database.emailDao().insert(fallback)
                                            // P3 FIX: Восстанавливаем записи вложений.
                                            // Без этого черновик появляется в списке, но при
                                            // открытии вложений нет (AttachmentEntity не созданы).
                                            if (allServerAttachments.isNotEmpty()) {
                                                val attEntities = allServerAttachments.map { att ->
                                                    com.dedovmosol.iwomail.data.database.AttachmentEntity(
                                                        emailId = newEmailId,
                                                        fileReference = "",
                                                        displayName = att.name,
                                                        contentType = att.mimeType,
                                                        estimatedSize = att.data.size.toLong(),
                                                        isInline = att.isInline,
                                                        contentId = att.contentId
                                                    )
                                                }
                                                database.attachmentDao().insertAll(attEntities)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        } else {
                            // Без вложений — обычный updateDraft
                            val result = withContext(Dispatchers.IO) {
                                mailRepo.updateDraft(
    accountId = account.id,
    serverId = email.serverId,
    to = normalizedTo, cc = normalizedCc,
    subject = subject,
    body = body,
    fromEmail = account.email,
    fromName = account.displayName
)
                            }
                            success = result is EasResult.Success
                        }
                    } else {
                        val serverId = withContext(Dispatchers.IO) {
                            mailRepo.saveDraft(
    accountId = account.id,
    to = normalizedTo, cc = normalizedCc,
    subject = subject,
    serverBody = cleanBody,
    localBody = body,
    fromEmail = account.email,
    fromName = account.displayName,
    hasAttachments = allServerAttachments.isNotEmpty(),
    attachmentFiles = allServerAttachments
)
                        }
                        success = serverId != null
                    }
                } else {
                    // Новый черновик
                    val serverId = withContext(Dispatchers.IO) {
                        mailRepo.saveDraft(
    accountId = account.id,
    to = normalizedTo, cc = normalizedCc,
    subject = subject,
    serverBody = cleanBody,
    localBody = body,
    fromEmail = account.email,
    fromName = account.displayName,
    hasAttachments = allServerAttachments.isNotEmpty(),
    attachmentFiles = allServerAttachments
)
                    }
                    success = serverId != null
                }
                
                if (success) {
                    Toast.makeText(context, draftSavedMsg, Toast.LENGTH_SHORT).show()
                    handleBackNavigation()
                } else {
                    Toast.makeText(context, draftSaveErrorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "${draftSaveErrorMsg}: ${e.message}", Toast.LENGTH_LONG).show()
            }
            
            isSavingDraft = false
        }
    }
    
    // Локализованная строка для прогресс-бара
    val sendingMessageText = if (currentLanguage == AppLanguage.RUSSIAN) "Отправка письма..." else "Sending email..."
    val sendErrorText = Strings.sendError // Захватываем в Composable scope
    
    fun sendEmail(scheduledTime: Long? = null) {
        // Защита от double-tap: если уже отправляем — игнорируем
        if (isSending) return
        isSending = true
        
        try {
            val account = activeAccount
            if (account == null) {
                isSending = false
                Toast.makeText(context, accountNotFoundMsg, Toast.LENGTH_SHORT).show()
                return
            }
            
            // Если запланировано - создаём WorkManager задачу (без обратного отсчёта)
            if (scheduledTime != null) {
                val delay = scheduledTime - System.currentTimeMillis()
                if (delay > 0) {
                    scheduleEmail(context, account.id, to, cc, bcc, subject, body, delay, requestReadReceipt, requestDeliveryReceipt, if (highPriority) 2 else 1)
                    Toast.makeText(context, sendScheduledMsg, Toast.LENGTH_SHORT).show()
                    onSent()
                    return
                }
            }
            
            // Читаем данные вложений синхронно перед закрытием экрана
            val attachmentDataList = attachments.mapNotNull { att ->
                try {
                    context.contentResolver.openInputStream(att.uri)?.use { it.readBytes() }
                        ?.let { bytes -> com.dedovmosol.iwomail.ui.components.AttachmentData(att.name, att.mimeType, bytes) }
                } catch (e: Exception) {
                    null
                }
            }
            
            // Убираем хвост пустых элементов от RichTextEditor
            val cleanBody = body
                .replace(Regex("(<div>\\s*(<br>|&nbsp;)?\\s*</div>\\s*)+$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("(<p>\\s*(<br>|&nbsp;)?\\s*</p>\\s*)+$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("(<br\\s*/?>\\s*)+$", RegexOption.IGNORE_CASE), "")
                .trimEnd()
            
            // Создаём PendingEmail
            val pendingEmail = com.dedovmosol.iwomail.ui.components.PendingEmail(
                account = account,
                to = to,
                cc = cc,
                bcc = bcc,
                subject = subject,
                body = cleanBody,
                attachments = attachmentDataList,
                importance = if (highPriority) 2 else 1,
                requestReadReceipt = requestReadReceipt,
                requestDeliveryReceipt = requestDeliveryReceipt,
                draftId = editDraftId // Черновик удалится после успешной отправки
            )
            
            // Запускаем отложенную отправку
            sendController.startSend(
                email = pendingEmail,
                message = sendingMessageText,
                context = context,
                mailRepo = mailRepo,
                onSuccess = { },
                onCancel = { }
            )
            
            // Скрываем WebView и ждём завершения рендеринга перед навигацией
            // Это предотвращает краш в RenderThread (GLFunctorDrawable::onDraw)
            hideWebView = true
            
            // Закрываем экран с задержкой чтобы RenderThread успел завершить работу
            scope.launch {
                // Сначала скрываем WebView (visibility = GONE через hideWebView)
                kotlinx.coroutines.delay(100)
                
                // Теперь безопасно уничтожаем WebView
                richTextController.webView?.let { webView ->
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                richTextController.webView = null
                richTextController.isLoaded = false
                
                // Ещё немного ждём и навигируем
                kotlinx.coroutines.delay(50)
                try {
                    onSent()
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            isSending = false
            Toast.makeText(context, "${sendErrorText}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    // Диалог подтверждения выхода — сохранить или нет
    if (showDiscardDialog) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            icon = { Icon(AppIcons.Edit, null) },
            title = { Text(Strings.discardDraftQuestion) },
            text = { Text(Strings.draftWillBeDeleted) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
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
                        handleBackNavigation()
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
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
                                    selectedSignature = signature
                                    val newSignatureHtml = formatHtmlSignature(signature.text, signature.isHtml)
                                    // Заменяем подпись в body (HTML формат)
                                    body = if (body.contains("<div class=\"signature\">")) {
                                        body.replace(HTML_SIGNATURE_REGEX, newSignatureHtml)
                                    } else {
                                        body + newSignatureHtml
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
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showAccountPicker = false },
            title = { Text(Strings.selectSender) },
            text = {
                Column {
                    allAccounts.forEach { account ->
                        val accountColor = try {
                            Color(account.color)
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.primary
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeAccount = account
                                    savedActiveAccountId = account.id
                                    showAccountPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(accountColor),
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
                ownEmail = activeAccount?.email ?: "",
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
    
    // Диалог выбора качества изображения
    if (showImageQualityDialog && pendingImageUri != null) {
        val imageQualityTitle = if (currentLanguage == AppLanguage.RUSSIAN) "Качество изображения" else "Image Quality"
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { 
                showImageQualityDialog = false
                pendingImageUriString = null
            },
            title = { Text(imageQualityTitle) },
            text = {
                Column {
                    ImageQuality.entries.forEach { quality ->
                        val label = if (currentLanguage == AppLanguage.RUSSIAN) quality.labelRu else quality.labelEn
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingImageUri?.let { uri ->
                                        insertImageWithQuality(uri, quality)
                                    }
                                    showImageQualityDialog = false
                                    pendingImageUriString = null
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                AppIcons.Image,
                                null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    showImageQualityDialog = false
                    pendingImageUriString = null
                }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Перехват системного жеста "назад" (свайп)
    BackHandler {
        val hasContent = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank() || attachments.isNotEmpty() || (activeAccount?.id != initialAccountId && initialAccountId != null)
        if (hasContent) {
            showDiscardDialog = true
        } else {
            handleBackNavigation()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = {
                        // Если есть контент — показываем диалог
                        val hasContent = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank() || attachments.isNotEmpty() || (activeAccount?.id != initialAccountId && initialAccountId != null)
                        if (hasContent) {
                            showDiscardDialog = true
                        } else {
                            handleBackNavigation()
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
        },
        bottomBar = {
            // Панель форматирования внизу (над клавиатурой) - скрываем вместе с WebView
            if (!hideWebView) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding()
                ) {
                    HorizontalDivider()
                    RichTextToolbar(
                        controller = richTextController,
                        onInsertImage = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Баннер "Нет сети"
            NetworkBanner()
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                Text(
                    Strings.from, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant, 
                    modifier = Modifier.widthIn(min = 80.dp, max = 120.dp), 
                    maxLines = 1, 
                    softWrap = false
                )
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
                    Text(
                        Strings.to, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier.widthIn(min = 80.dp, max = 120.dp), 
                        maxLines = 1, 
                        softWrap = false
                    )
                    TextField(
                        value = to,
                        onValueChange = { newValue ->
                            to = newValue
                            // Если только что выбрали подсказку — не запускаем поиск
                            if (suggestionJustSelected) {
                                suggestionJustSelected = false
                                return@TextField
                            }
                            activeAccount?.let { acc ->
                                searchSuggestions(newValue, acc.id, acc.email)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(toFocusRequester)
                            .onFocusChanged { focusState ->
                                toFieldFocused = focusState.isFocused
                                if (focusState.isFocused) focusedFieldIndex = 0
                                if (!focusState.isFocused) {
                                    // Задержка перед скрытием подсказок — иначе tap по подсказке
                                    // теряется из-за преждевременного закрытия DropdownMenu
                                    scope.launch {
                                        delay(200)
                                        showToSuggestions = false
                                    }
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
                                // КРИТИЧНО: Сначала отменяем фоновый поиск (GAL и т.д.),
                                // иначе он может завершиться и снова показать подсказки
                                suggestionSearchJob?.cancel()
                                suggestionJustSelected = true
                                to = replaceLastRecipient(to, suggestion.email)
                                showToSuggestions = false
                                toSuggestions = emptyList()
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
                    Text(
                        Strings.cc, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier.widthIn(min = 80.dp, max = 120.dp), 
                        maxLines = 1, 
                        softWrap = false
                    )
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
                    Text(
                        Strings.hiddenCopy, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier.widthIn(min = 80.dp, max = 120.dp), 
                        maxLines = 1, 
                        softWrap = false
                    )
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
            
            // Тело письма - Rich Text Editor (скрываем перед навигацией чтобы избежать краша)
            if (!hideWebView) {
                val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val isTablet = configuration.screenWidthDp >= 600
                val editorMinHeightDp = when {
                    isLandscape && isTablet -> 320.dp
                    isLandscape -> 240.dp
                    else -> 200.dp
                }
                val editorMinHeightPx = with(density) { editorMinHeightDp.roundToPx() }
                val isDarkTheme = !MaterialTheme.colorScheme.background.luminance().let { it > 0.5f }
                RichTextEditor(
                    initialHtml = body,
                    onHtmlChanged = { body = it },
                    controller = richTextController,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = editorMinHeightDp),
                    placeholder = Strings.messageText,
                    minHeight = editorMinHeightPx,
                    isDarkTheme = isDarkTheme
                )
            }
            
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
                                Icon(AppIcons.fileIconFor(att.name), null, modifier = Modifier.size(24.dp), tint = Color.Unspecified)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(att.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(formatAttachmentSize(att.size), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { 
                                    attachmentStrings = attachmentStrings - att.toSaveableString() 
                                }) {
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
}
