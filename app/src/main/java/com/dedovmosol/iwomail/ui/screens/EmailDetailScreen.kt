package com.dedovmosol.iwomail.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.View
import com.dedovmosol.iwomail.BuildConfig
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll

import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.components.NetworkBanner
import com.dedovmosol.iwomail.network.NetworkMonitor
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File

// Предкомпилированные regex для производительности
private val CN_REGEX = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE)
private val NAME_BEFORE_BRACKET_REGEX = Regex("^\"?([^\"<]+)\"?\\s*<")
private val EMAIL_IN_BRACKETS_REGEX = Regex("<([^>]+@[^>]+)>")
private val CID_REGEX = Regex("cid:([^\"'\\s>]+)")
private val SAFE_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|]")
// Regex для парсинга задачи из письма
private val TASK_SUBJECT_REGEX = Regex("^Задача:\\s*(.+)$|^Task:\\s*(.+)$", RegexOption.IGNORE_CASE)
// Regex для обнаружения URL, email и телефонов в plain-text теле
private val BODY_LINK_REGEX = Regex(
    "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)" +  // URL
    "|" +
    "(\\b[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}\\b)" +  // email
    "|" +
    "(\\+?\\d[\\d\\s\\-()]{6,}\\d)"  // телефон (8+ цифр, допускаются пробелы, дефисы, скобки)
)
private val TASK_DUE_DATE_REGEX = Regex("Срок выполнения:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})|Due date:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})", RegexOption.IGNORE_CASE)
private val TASK_DESCRIPTION_REGEX = Regex("Описание:\\s*(.+?)(?=\\n\\n|Срок|Due|$)|Description:\\s*(.+?)(?=\\n\\n|Срок|Due|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
// Regex для парсинга iCalendar приглашений
private val ICAL_SUMMARY_REGEX = Regex("SUMMARY[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
// Поддерживаем форматы: DTSTART:20251203T170000Z, DTSTART;TZID=...:20251203T170000, DTSTART;VALUE=DATE:20251203
// Группа 1 - TZID (опционально), Группа 2 - дата
private val ICAL_DTSTART_REGEX = Regex("DTSTART(?:;TZID=([^:;]+))?(?:;[^:]+)?:(\\d{8}(?:T\\d{6})?Z?)")
private val ICAL_DTEND_REGEX = Regex("DTEND(?:;TZID=([^:;]+))?(?:;[^:]+)?:(\\d{8}(?:T\\d{6})?Z?)")
private val ICAL_LOCATION_REGEX = Regex("LOCATION[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
private val ICAL_DESCRIPTION_REGEX = Regex("DESCRIPTION[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
private val ICAL_ORGANIZER_REGEX = Regex("ORGANIZER[^:]*:mailto:([^\\r\\n]+)", RegexOption.IGNORE_CASE)
// Regex для очистки тела письма
private val HTML_STRIP_REGEX = Regex("<[^>]+>")
private val LINE_FOLDING_REGEX = Regex("\\r?\\n[ \\t]")
private val EXCHANGE_SEPARATOR_1_REGEX = Regex("\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*?")
private val EXCHANGE_SEPARATOR_2_REGEX = Regex("~\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*?")
private val EXCHANGE_SEPARATOR_3_REGEX = Regex("~\\*+")
private val EXCHANGE_SEPARATOR_4_REGEX = Regex("\\*~+")
private val BASE64_DETECT_REGEX = Regex("^[A-Za-z0-9+/=\\s]+$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailDetailScreen(
    emailId: String,
    onBackClick: () -> Unit,
    onReplyClick: () -> Unit,
    onForwardClick: () -> Unit = {},
    onComposeToEmail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN

    suspend fun fetchAttachmentBytes(att: AttachmentEntity): ByteArray? {
        if (att.downloaded && att.localPath != null) {
            val f = File(att.localPath)
            if (f.exists()) return withContext(Dispatchers.IO) { f.readBytes() }
        }
        if (!NetworkMonitor.isNetworkAvailable(context)) {
            Toast.makeText(context, if (isRussian) "Нет сети" else "No network", Toast.LENGTH_SHORT).show()
            return null
        }
        val account = accountRepo.getActiveAccountSync()
        val easClient = account?.let { accountRepo.createEasClient(it.id) }
        if (easClient != null) {
            return when (val result = withContext(Dispatchers.IO) { easClient.downloadAttachment(att.fileReference) }) {
                is EasResult.Success -> {
                    easClient.policyKey?.let { newKey ->
                        if (account != null && newKey != account.policyKey) {
                            accountRepo.savePolicyKey(account.id, newKey)
                        }
                    }
                    result.data
                }
                is EasResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    null
                }
            }
        }
        return null
    }

    val email by mailRepo.getEmail(emailId).collectAsState(initial = null)
    
    // Вложения - используем Flow напрямую, сохраняем ID для восстановления после поворота
    var attachmentIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    val attachmentsFlow by mailRepo.getAttachments(emailId).collectAsState(initial = emptyList())
    
    // Синхронизируем ID при получении данных из Flow
    LaunchedEffect(attachmentsFlow) {
        if (attachmentsFlow.isNotEmpty()) {
            attachmentIds = attachmentsFlow.map { it.id }
        }
    }
    
    // Используем Flow напрямую - он автоматически обновится после поворота
    val attachments = attachmentsFlow
    
    var downloadingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var isLoadingBody by remember { mutableStateOf(false) } // НЕ saveable - сбрасывается при входе
    var bodyLoadError by remember { mutableStateOf<String?>(null) } // НЕ saveable
    
    // Save As: запоминаем вложение для сохранения через системный файл-пикер
    var pendingSaveAsAttachment by remember { mutableStateOf<AttachmentEntity?>(null) }
    var pendingPreviewFile by remember { mutableStateOf<File?>(null) }
    val previewLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        pendingPreviewFile?.delete()
        pendingPreviewFile = null
    }
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val attachment = pendingSaveAsAttachment ?: return@rememberLauncherForActivityResult
        pendingSaveAsAttachment = null
        scope.launch {
            try {
                val sourceFile = if (attachment.downloaded && attachment.localPath != null) {
                    File(attachment.localPath)
                } else null
                if (sourceFile != null && sourceFile.exists() && uri != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            sourceFile.inputStream().use { it.copyTo(out) }
                        }
                    }
                    val savedMsg = if (isRussian) "Файл сохранён" else "File saved"
                    Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                } else if (uri != null) {
                    // Нужно сначала скачать
                    downloadingId = attachment.id
                    val account = accountRepo.getActiveAccountSync()
                    val easClient = account?.let { accountRepo.createEasClient(it.id) }
                    if (easClient != null) {
                        when (val result = withContext(Dispatchers.IO) { easClient.downloadAttachment(attachment.fileReference) }) {
                            is EasResult.Success -> {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                        out.write(result.data)
                                    }
                                }
                                val savedMsg = if (isRussian) "Файл сохранён" else "File saved"
                                Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                            }
                            is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                    downloadingId = null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                downloadingId = null
            }
        }
    }
    
    // Диалог выбора папки для перемещения
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<com.dedovmosol.iwomail.data.database.FolderEntity>>(emptyList()) }
    var isMoving by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    
    // Загружаем папки для диалога перемещения
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    LaunchedEffect(activeAccount?.id) {
        activeAccount?.let { account ->
            mailRepo.getFolders(account.id).collect { folders = it }
        }
    }
    
    // Определяем, находится ли письмо в папке Удалённые (тип 4) или Отправленные (тип 5) или Черновики (тип 3)
    val currentFolder = folders.find { it.id == email?.folderId }
    val isInTrash = currentFolder?.type == FolderType.DELETED_ITEMS
    val isInSent = currentFolder?.type == FolderType.SENT_ITEMS
    val isInDrafts = currentFolder?.type == FolderType.DRAFTS
    
    // Кэш inline изображений: contentId -> base64 data URL
    var inlineImages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoadingInlineImages by remember { mutableStateOf(false) }
    
    // Загружаем inline изображения ПАРАЛЛЕЛЬНО
    LaunchedEffect(email?.body, attachments, email?.folderId, email?.bodyType) {
        val body = email?.body ?: return@LaunchedEffect
        val currentEmail = email ?: return@LaunchedEffect
        if (body.isEmpty()) return@LaunchedEffect
        // КРИТИЧНО: НЕ проверяем isLoadingInlineImages в guard!
        // При отмене LaunchedEffect (смена ключей, напр. attachments Flow) isLoadingInlineImages
        // мог остаться = true от предыдущего выполнения → повторный LaunchedEffect навсегда
        // блокировался, inline-картинки не загружались при повторном входе в письмо.
        isLoadingInlineImages = false
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("InlineImages", "=== START: emailId=${currentEmail.id}, subject='${currentEmail.subject.take(30)}', bodyType=${currentEmail.bodyType}")
            android.util.Log.d("InlineImages", "Attachments count: ${attachments.size}")
        }
        
        // Если bodyType=4 (MIME) И тело действительно содержит MIME-структуру —
        // пробуем извлечь inline-картинки прямо из MIME.
        // КРИТИЧНО: После loadEmailBody тело в БД — уже HTML (извлечённый из MIME),
        // а bodyType остаётся = 4. Проверяем наличие MIME-маркеров чтобы не тратить
        // время на парсинг HTML как MIME (всегда возвращает пусто).
        val bodyLooksMime = body.contains("Content-Type:", ignoreCase = true) && 
                           body.contains("boundary", ignoreCase = true)
        if (currentEmail.bodyType == 4 && bodyLooksMime) {
            if (BuildConfig.DEBUG) android.util.Log.d("InlineImages", "BodyType=4 MIME, extracting inline images")
            isLoadingInlineImages = true
            val mimeImages = withContext(Dispatchers.IO) {
                extractInlineImagesFromMime(body)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("InlineImages", "Extracted ${mimeImages.size} images from MIME")
            if (mimeImages.isNotEmpty()) {
                inlineImages = mimeImages
                isLoadingInlineImages = false
                return@LaunchedEffect
            }
            // Если из MIME ничего не извлеклось — падаем в fallback через cid/fetchInlineImages
            isLoadingInlineImages = false
            if (BuildConfig.DEBUG) android.util.Log.d("InlineImages", "MIME extraction empty, falling through to cid/fetchInlineImages")
        } else if (currentEmail.bodyType == 4) {
            if (BuildConfig.DEBUG) android.util.Log.d("InlineImages", "BodyType=4 already HTML, skipping MIME parse")
        }
        
        // Находим все cid: ссылки в HTML
        val cidRefs = CID_REGEX.findAll(body).map { it.groupValues[1] }.toSet()
        if (BuildConfig.DEBUG) android.util.Log.d("InlineImages", "Found ${cidRefs.size} cid refs in body")
        
        if (cidRefs.isEmpty()) {
            return@LaunchedEffect
        }
        
        // Находим вложения с соответствующими contentId
        // КРИТИЧНО: contentId может храниться с угловыми скобками <...> (из MIME Content-ID),
        // а cid: ссылки в HTML — без них. Убираем скобки для корректного сравнения.
        val inlineAttachments = attachments.filter { att ->
            if (att.contentId == null) return@filter false
            val cleanCid = att.contentId.removeSurrounding("<", ">")
            cidRefs.contains(cleanCid) || cidRefs.contains(att.contentId) || cidRefs.contains(att.displayName)
        }
        if (BuildConfig.DEBUG) android.util.Log.d("InlineImages", "Found ${inlineAttachments.size} inline attachments out of ${attachments.size} total")
        
        isLoadingInlineImages = true
        val account = accountRepo.getActiveAccountSync()
        
        if (account != null) {
            val newImages = mutableMapOf<String, String>()
            
            // Проверяем есть ли вложения с валидным fileReference
            val attachmentsWithRef = inlineAttachments.filter { it.fileReference.isNotBlank() }
            
            withContext(Dispatchers.IO) {
                // Если есть вложения с fileReference - загружаем через downloadAttachment
                if (attachmentsWithRef.isNotEmpty()) {
                    android.util.Log.d("InlineImages", "Downloading ${attachmentsWithRef.size} attachments via fileReference")
                    supervisorScope {
                        attachmentsWithRef.map { att ->
                            async {
                                try {
                                    val easClient = accountRepo.createEasClient(account.id) ?: return@async null
                                    when (val result = easClient.downloadAttachment(att.fileReference)) {
                                        is EasResult.Success -> {
                                            val base64 = android.util.Base64.encodeToString(result.data, android.util.Base64.NO_WRAP)
                                            val dataUrl = "data:${att.contentType};base64,$base64"
                                            Pair(att, dataUrl)
                                        }
                                        is EasResult.Error -> null
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull().forEach { pair ->
                            pair.first.contentId?.let { newImages[it] = pair.second }
                            newImages[pair.first.displayName] = pair.second
                        }
                    }
                    android.util.Log.d("InlineImages", "Downloaded ${newImages.size} images via fileReference")
                }
                
                // Если не все изображения загружены - пробуем через MIME (особенно для Sent Items)
                val missingCids = cidRefs.filter { cid -> 
                    !newImages.containsKey(cid) && !newImages.containsKey("<$cid>")
                }
                android.util.Log.d("InlineImages", "Missing ${missingCids.size} cids after fileReference download: ${missingCids.take(5)}")
                
                if (missingCids.isNotEmpty()) {
                    android.util.Log.d("InlineImages", "Trying fetchInlineImages for missing cids...")
                    try {
                        val easClient = accountRepo.createEasClient(account.id)
                        if (easClient != null) {
                            val folderServerId = currentEmail.folderId.substringAfter("_")
                            android.util.Log.d("InlineImages", "Calling fetchInlineImages: folderServerId=$folderServerId, serverId=${currentEmail.serverId}")
                            when (val result = easClient.fetchInlineImages(folderServerId, currentEmail.serverId)) {
                                is EasResult.Success -> {
                                    android.util.Log.d("InlineImages", "fetchInlineImages SUCCESS: got ${result.data.size} images")
                                    result.data.forEach { (cid, dataUrl) ->
                                        newImages[cid] = dataUrl
                                    }
                                }
                                is EasResult.Error -> {
                                    android.util.Log.w("InlineImages", "fetchInlineImages ERROR: ${result.message}")
                                }
                            }
                        } else {
                            android.util.Log.w("InlineImages", "Failed to create EAS client")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.e("InlineImages", "fetchInlineImages exception: ${e.message}")
                    }
                }
            }
            
            android.util.Log.d("InlineImages", "=== FINISH: Total ${newImages.size} images loaded")
            inlineImages = newImages
        }
        isLoadingInlineImages = false
    }
    
    // Помечаем как прочитанное и загружаем тело если нужно
    LaunchedEffect(emailId) {
        val currentEmail = withContext(Dispatchers.IO) {
            mailRepo.getEmailSync(emailId)
        }
        
        if (currentEmail == null) {
            // Письмо не найдено в БД - возможно ещё не синхронизировано
            bodyLoadError = if (isRussian) "Письмо не найдено. Попробуйте обновить входящие." else "Email not found. Try refreshing inbox."
            return@LaunchedEffect
        }
        
        // Помечаем как прочитанное (не блокируем загрузку тела)
        if (!currentEmail.read) {
            launch(Dispatchers.IO) {
                when (val result = mailRepo.markAsRead(emailId, true)) {
                    is EasResult.Success -> { /* OK */ }
                    is EasResult.Error -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        
        // Загружаем тело если пустое (СРАЗУ, без задержки)
        if (currentEmail.body.isEmpty() && !isLoadingBody) {
            isLoadingBody = true
            bodyLoadError = null
            try {
                // Таймаут 30 секунд на загрузку тела
                val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    mailRepo.loadEmailBody(emailId)
                }
                when (result) {
                    is EasResult.Success -> {
                        // Тело загружено — ПОСЛЕДОВАТЕЛЬНО обновляем FileReference вложений
                        launch(Dispatchers.IO) {
                            try { mailRepo.refreshAttachmentMetadata(emailId) } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                    }
                    is EasResult.Error -> {
                        if (result.message == "OBJECT_NOT_FOUND") {
                            // Письмо удалено на сервере - показываем Toast и возвращаемся
                            android.widget.Toast.makeText(
                                context,
                                if (isRussian) "Письмо удалено на сервере" else "Email deleted on server",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onBackClick()
                        } else {
                            bodyLoadError = result.message
                        }
                    }
                    null -> bodyLoadError = if (isRussian) "Таймаут загрузки" else "Loading timeout"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                bodyLoadError = e.message
            } finally {
                isLoadingBody = false
            }
        } else if (currentEmail.body.isNotEmpty() && !isLoadingBody) {
            // Тело уже есть — фоновое обновление body + FileReference вложений
            // ПОСЛЕДОВАТЕЛЬНО (не параллельно) чтобы не было race condition с EAS командами
            // ИСПРАВЛЕНО: withContext вместо launch — отменяется вместе с LaunchedEffect,
            // не пишет isLoadingBody=false в stale state после навигации назад
            isLoadingBody = true
            try {
                withContext(Dispatchers.IO) {
                    mailRepo.loadEmailBody(emailId, forceReload = true)
                    mailRepo.refreshAttachmentMetadata(emailId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally { isLoadingBody = false }
        }
    }
    
    // Диалог подтверждения удаления
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    
    // Диалог отчёта о прочтении (MDN)
    var showMdnDialog by rememberSaveable { mutableStateOf(false) }
    var isSendingMdn by remember { mutableStateOf(false) }
    
    // Показываем диалог MDN если есть запрос и ещё не отправлен
    LaunchedEffect(email?.mdnRequestedBy, email?.mdnSent) {
        val e = email ?: return@LaunchedEffect
        if (!e.mdnRequestedBy.isNullOrBlank() && !e.mdnSent && !showMdnDialog) {
            showMdnDialog = true
        }
    }
    
    // Диалог MDN
    // КРИТИЧНО: используем локальную переменную вместо !! чтобы избежать NPE при race condition в recomposition
    val currentEmail = email
    if (showMdnDialog && currentEmail != null && !currentEmail.mdnRequestedBy.isNullOrBlank()) {
        val mdnEmail = currentEmail
        val readReceiptSentText = Strings.readReceiptSent
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { 
                showMdnDialog = false
                // Помечаем что пользователь отказался (чтобы не показывать снова)
                scope.launch { mailRepo.markMdnSent(emailId) }
            },
            icon = { Icon(AppIcons.MarkEmailRead, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(Strings.readReceiptRequest) },
            text = { Text(Strings.readReceiptRequestText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSendingMdn = true
                            when (val result = mailRepo.sendMdn(emailId)) {
                                is EasResult.Success -> {
                                    Toast.makeText(context, readReceiptSentText, Toast.LENGTH_SHORT).show()
                                }
                                is EasResult.Error -> {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                            isSendingMdn = false
                            showMdnDialog = false
                        }
                    },
                    enabled = !isSendingMdn
                ) {
                    if (isSendingMdn) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.send)
                    }
                }
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { 
                        showMdnDialog = false
                        scope.launch { mailRepo.markMdnSent(emailId) }
                    },
                    text = Strings.no,
                    enabled = !isSendingMdn
                )
            }
        )
    }
    
    // Диалог подтверждения удаления
    if (showDeleteDialog) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteEmail) },
            text = { Text(if (isInTrash) Strings.emailWillBeDeletedPermanently else Strings.emailWillBeMovedToTrash) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            val result = withContext(Dispatchers.IO) {
                                if (isInTrash) {
                                    mailRepo.deleteEmailsPermanentlyWithProgress(listOf(emailId)) { _, _ -> }
                                } else {
                                    mailRepo.moveToTrash(listOf(emailId))
                                }
                            }
                            isDeleting = false
                            showDeleteDialog = false
                            when (result) {
                                is EasResult.Success -> {
                                    val message = if (isInTrash) {
                                        NotificationStrings.getDeletedPermanently(isRussian)
                                    } else if ((result.data as? Int ?: 0) > 0) {
                                        NotificationStrings.getMovedToTrash(isRussian)
                                    } else {
                                        NotificationStrings.getDeletedPermanently(isRussian)
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    onBackClick()
                                }
                                is EasResult.Error -> {
                                    val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                    Toast.makeText(context, localizedMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    text = Strings.yes,
                    enabled = !isDeleting
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteDialog = false },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог выбора папки для перемещения
    if (showMoveDialog) {
        val currentFolderId = email?.folderId
        // Исключаем не-почтовые папки и служебные (Outbox, Drafts)
        val nonMailFolderTypes = setOf(
            FolderType.TASKS, FolderType.CALENDAR, FolderType.CONTACTS, FolderType.NOTES,
            FolderType.OUTBOX, FolderType.DRAFTS,
            13, 14, 15, 17, 18 // Journal, RecipientInfo и др. служебные
        )
        val availableFolders = folders.filter { folder ->
            folder.id != currentFolderId &&
            folder.type !in nonMailFolderTypes
        }
        
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(Strings.moveTo) },
            text = {
                if (isMoving) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Box(modifier = Modifier.heightIn(max = 300.dp)) {
                        Column(
                            modifier = Modifier.verticalScroll(scrollState)
                        ) {
                            availableFolders.forEach { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.displayName) },
                                    leadingContent = { 
                                        Icon(AppIcons.Folder, null) 
                                    },
                                    modifier = Modifier.clickable(enabled = !isMoving) {
                                        if (isMoving) return@clickable
                                        scope.launch {
                                            isMoving = true
                                            val result = withContext(Dispatchers.IO) {
                                                mailRepo.moveEmails(listOf(emailId), folder.id)
                                            }
                                            when (result) {
                                                is EasResult.Success -> {
                                                    Toast.makeText(context, NotificationStrings.getMoved(isRussian), Toast.LENGTH_SHORT).show()
                                                    showMoveDialog = false
                                                    onBackClick()
                                                }
                                                is EasResult.Error -> {
                                                    val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                                    Toast.makeText(context, localizedMsg, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            isMoving = false
                                        }
                                    }
                                )
                            }
                        }
                        ScrollColumnScrollbar(scrollState)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showMoveDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                actions = {
                    // Обновить письмо (sync папки + перезагрузка тела)
                    IconButton(
                        onClick = {
                            val currentEmail = email ?: return@IconButton
                            scope.launch {
                                isLoadingBody = true
                                bodyLoadError = null
                                try {
                                    // Сначала синхронизируем папку — обновляет SyncKey и FileReference вложений
                                    withContext(Dispatchers.IO) {
                                        mailRepo.syncEmails(currentEmail.accountId, currentEmail.folderId)
                                    }
                                    // Затем перезагружаем тело письма
                                    val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                                        mailRepo.loadEmailBody(emailId, forceReload = true)
                                    }
                                    when (result) {
                                        is EasResult.Success -> {
                                            Toast.makeText(
                                                context,
                                                if (isRussian) "Письмо обновлено" else "Email refreshed",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        is EasResult.Error -> {
                                            bodyLoadError = result.message
                                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        }
                                        null -> {
                                            bodyLoadError = if (isRussian) "Таймаут загрузки" else "Loading timeout"
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    bodyLoadError = e.message
                                } finally {
                                    isLoadingBody = false
                                }
                            }
                        },
                        enabled = !isLoadingBody
                    ) {
                        Icon(AppIcons.Refresh, Strings.refresh, tint = Color.White)
                    }
                    
                    // Overflow menu (троеточие)
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(AppIcons.MoreVert, Strings.more, tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            // Переслать
                            DropdownMenuItem(
                                text = { Text(Strings.forward) },
                                onClick = { showOverflowMenu = false; onForwardClick() },
                                leadingIcon = { Icon(AppIcons.Forward, null) }
                            )
                            // Переместить / Восстановить
                            if (isInTrash) {
                                DropdownMenuItem(
                                    text = { Text(Strings.restore) },
                                    onClick = {
                                        showOverflowMenu = false
                                        scope.launch {
                                            isRestoring = true
                                            val result = withContext(Dispatchers.IO) {
                                                mailRepo.restoreFromTrash(listOf(emailId))
                                            }
                                            when (result) {
                                                is EasResult.Success -> {
                                                    Toast.makeText(context, NotificationStrings.getRestored(isRussian), Toast.LENGTH_SHORT).show()
                                                    onBackClick()
                                                }
                                                is EasResult.Error -> {
                                                    val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                                    Toast.makeText(context, localizedMsg, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            isRestoring = false
                                        }
                                    },
                                    leadingIcon = { Icon(AppIcons.Restore, null) },
                                    enabled = !isRestoring
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(Strings.moveTo) },
                                    onClick = { showOverflowMenu = false; showMoveDialog = true },
                                    leadingIcon = { Icon(AppIcons.DriveFileMove, null) }
                                )
                            }
                            if (!isInSent) {
                                DropdownMenuItem(
                                    text = { Text(Strings.markUnread) },
                                    onClick = {
                                        showOverflowMenu = false
                                        scope.launch {
                                            when (val result = mailRepo.markAsRead(emailId, false)) {
                                                is EasResult.Success -> { /* OK */ }
                                                is EasResult.Error -> {
                                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = { Icon(AppIcons.MarkEmailUnread, null) }
                                )
                            }
                            // Удалить
                            DropdownMenuItem(
                                text = { Text(Strings.delete) },
                                onClick = { showOverflowMenu = false; showDeleteDialog = true },
                                leadingIcon = { Icon(AppIcons.Delete, null) }
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
        floatingActionButton = {
            com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                onClick = onReplyClick,
                containerColor = LocalColorTheme.current.gradientStart
            ) {
                Icon(
                    if (isInSent) AppIcons.Email else AppIcons.Reply, 
                    if (isInSent) Strings.writeMore else Strings.reply, 
                    tint = Color.White
                )
            }
        }
    ) { padding ->
        if (email == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // Используем локальную переменную для безопасного доступа
            // PERF: smart-cast вместо !! — безопаснее при recomposition race
            val currentEmail = email ?: return@Scaffold
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Баннер "Нет сети"
                NetworkBanner()
                
                val detailScrollState = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(detailScrollState)
                ) {
                // Тема
                Text(
                    text = currentEmail.subject,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                // Отправитель
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Используем цвет аккаунта для аватара
                    val accountColor = try {
                        Color(activeAccount?.color ?: 0xFF1976D2.toInt())
                    } catch (_: Exception) {
                        Color(0xFF1976D2)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accountColor),
                        contentAlignment = Alignment.Center
                    ) {
                        // Берём первую букву из имени или email
                        val displayName = currentEmail.fromName.ifEmpty { extractDisplayName(currentEmail.from) }
                        val displayEmail = extractEmailAddress(currentEmail.from)
                        val avatarLetter = when {
                            displayName.isNotEmpty() && !displayName.contains("@") -> displayName.first().uppercase()
                            displayEmail.isNotEmpty() -> displayEmail.first().uppercase()
                            else -> "?"
                        }
                        Text(
                            text = avatarLetter,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        val displayName = currentEmail.fromName.ifEmpty { extractDisplayName(currentEmail.from) }
                        val displayEmail = extractEmailAddress(currentEmail.from)
                        
                        // Показываем имя только если оно отличается от email
                        val showName = displayName.isNotEmpty() && 
                            !displayName.equals(displayEmail, ignoreCase = true) &&
                            !displayName.equals(displayEmail.substringBefore("@"), ignoreCase = true)
                        
                        if (showName) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        // Показываем email если это нормальный адрес (кликабельный)
                        if (displayEmail.isNotEmpty() && displayEmail.contains("@")) {
                            Text(
                                text = if (showName) "<$displayEmail>" else displayEmail,
                                style = if (showName) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                                fontWeight = if (showName) FontWeight.Normal else FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onComposeToEmail(displayEmail) }
                            )
                        }
                    }
                    
                    Text(
                        text = formatFullDate(currentEmail.dateReceived),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Звёздочка избранного только если НЕ в корзине и НЕ черновик
                    if (!isInTrash && !isInDrafts) {
                        IconButton(onClick = {
                            scope.launch { mailRepo.toggleFlag(emailId) }
                        }) {
                            Icon(
                                imageVector = if (currentEmail.flagged) AppIcons.Star else AppIcons.StarOutline,
                                contentDescription = Strings.favorites,
                                tint = if (currentEmail.flagged) com.dedovmosol.iwomail.ui.theme.AppColors.favorites 
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Получатели - кликабельные для написания письма
                if (currentEmail.to.isNotEmpty() || currentEmail.cc.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Кому - каждый получатель кликабелен отдельно
                            val toLinkColor = MaterialTheme.colorScheme.primary
                            val toRecipients = remember(currentEmail.to) {
                                parseRecipientPairs(currentEmail.to)
                            }
                            val toAnnotated = remember(toRecipients, toLinkColor) {
                                buildAnnotatedString {
                                    toRecipients.forEachIndexed { index, (name, email) ->
                                        if (index > 0) append(", ")
                                        // Показываем имя если есть, иначе email
                                        val display = if (name.isNotBlank() && !name.equals(email, ignoreCase = true) &&
                                            !name.equals(email.substringBefore("@"), ignoreCase = true)) {
                                            name
                                        } else {
                                            email.ifEmpty { name }
                                        }
                                        if (email.isNotBlank() && email.contains("@")) {
                                            pushStringAnnotation("email", email)
                                            withStyle(SpanStyle(color = toLinkColor)) {
                                                append(display)
                                            }
                                            pop()
                                        } else {
                                            append(display)
                                        }
                                    }
                                }
                            }
                            val toStyle = MaterialTheme.typography.bodySmall
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = "${Strings.to}: ",
                                    style = toStyle
                                )
                                @Suppress("DEPRECATION")
                                ClickableText(
                                    text = toAnnotated,
                                    style = toStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.weight(1f),
                                    onClick = { offset ->
                                        toAnnotated.getStringAnnotations("email", offset, offset)
                                            .firstOrNull()?.let { onComposeToEmail(it.item) }
                                    }
                                )
                            }
                            // CC - аналогично с кликабельными именами
                            if (currentEmail.cc.isNotEmpty()) {
                                val ccRecipients = remember(currentEmail.cc) {
                                    parseRecipientPairs(currentEmail.cc)
                                }
                                val ccAnnotated = remember(ccRecipients, toLinkColor) {
                                    buildAnnotatedString {
                                        ccRecipients.forEachIndexed { index, (name, email) ->
                                            if (index > 0) append(", ")
                                            val display = if (name.isNotBlank() && !name.equals(email, ignoreCase = true) &&
                                                !name.equals(email.substringBefore("@"), ignoreCase = true)) {
                                                name
                                            } else {
                                                email.ifEmpty { name }
                                            }
                                            if (email.isNotBlank() && email.contains("@")) {
                                                pushStringAnnotation("email", email)
                                                withStyle(SpanStyle(color = toLinkColor)) {
                                                    append(display)
                                                }
                                                pop()
                                            } else {
                                                append(display)
                                            }
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = "${Strings.cc}: ",
                                        style = toStyle
                                    )
                                    @Suppress("DEPRECATION")
                                    ClickableText(
                                        text = ccAnnotated,
                                        style = toStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.weight(1f),
                                        onClick = { offset ->
                                            ccAnnotated.getStringAnnotations("email", offset, offset)
                                                .firstOrNull()?.let { onComposeToEmail(it.item) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Кнопка "Добавить в задачи" для писем с задачами
                val isTaskEmail = currentEmail.subject.startsWith("Задача:") || currentEmail.subject.startsWith("Task:")
                if (isTaskEmail) {
                    val taskRepo = remember { RepositoryProvider.getTaskRepository(context) }
                    var isAddingTask by rememberSaveable { mutableStateOf(false) }
                    var taskAdded by rememberSaveable { mutableStateOf(false) }
                    val taskAddedMsg = Strings.taskAddedToTasks
                    
                    if (!taskAdded) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable(enabled = !isAddingTask) {
                                    scope.launch {
                                        isAddingTask = true
                                        try {
                                            val accountId = activeAccount?.id ?: return@launch
                                            
                                            // Парсим данные задачи из письма
                                            val taskTitle = TASK_SUBJECT_REGEX.find(currentEmail.subject)?.let {
                                                it.groupValues[1].ifEmpty { it.groupValues[2] }
                                            } ?: currentEmail.subject.removePrefix("Задача:").removePrefix("Task:").trim()
                                            
                                            val bodyText = currentEmail.body
                                                .replace(HTML_STRIP_REGEX, "") // Убираем HTML теги
                                                .replace("&nbsp;", " ")
                                                .replace("&amp;", "&")
                                                .replace("&lt;", "<")
                                                .replace("&gt;", ">")
                                            
                                            // Парсим срок выполнения
                                            val dueDateMatch = TASK_DUE_DATE_REGEX.find(bodyText)
                                            val dueDate = dueDateMatch?.let {
                                                val dateStr = it.groupValues[1].ifEmpty { it.groupValues[2] }
                                                try {
                                                    val format = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                                    format.parse(dateStr)?.time ?: System.currentTimeMillis()
                                                } catch (_: Exception) {
                                                    System.currentTimeMillis() + 24 * 60 * 60 * 1000 // +1 день
                                                }
                                            } ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
                                            
                                            // Парсим описание
                                            val descMatch = TASK_DESCRIPTION_REGEX.find(bodyText)
                                            val description = descMatch?.let {
                                                it.groupValues[1].ifEmpty { it.groupValues[2] }.trim()
                                            } ?: "Задача из письма от ${currentEmail.fromName.ifEmpty { currentEmail.from }}"
                                            
                                            // Напоминание за 15 минут до срока, но только если это в будущем
                                            val now = System.currentTimeMillis()
                                            val reminderTime = dueDate - 15 * 60 * 1000
                                            val shouldRemind = reminderTime > now
                                            
                                            // Создаём задачу
                                            val result = withContext(Dispatchers.IO) {
                                                taskRepo.createTask(
                                                    accountId = accountId,
                                                    subject = taskTitle,
                                                    body = description,
                                                    dueDate = dueDate,
                                                    reminderSet = shouldRemind,
                                                    reminderTime = if (shouldRemind) reminderTime else 0
                                                )
                                            }
                                            
                                            when (result) {
                                                is EasResult.Success -> {
                                                    taskAdded = true
                                                    Toast.makeText(context, taskAddedMsg, Toast.LENGTH_SHORT).show()
                                                }
                                                is EasResult.Error -> {
                                                    val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                                    Toast.makeText(context, localizedMsg, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            Toast.makeText(context, e.message ?: NotificationStrings.getUnknownError(isRussian), Toast.LENGTH_LONG).show()
                                        } finally {
                                            isAddingTask = false
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isAddingTask) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        AppIcons.Task,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = Strings.addToTasks,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
                
                // Приглашение на встречу (iCalendar в теле письма или вложении)
                val calendarAttachment = attachments.find { 
                    it.contentType.contains("text/calendar", ignoreCase = true) ||
                    it.contentType.contains("application/ics", ignoreCase = true) ||
                    it.displayName.endsWith(".ics", ignoreCase = true)
                }
                val hasCalendarAttachment = calendarAttachment != null
                val bodyHasVEvent = currentEmail.body.contains("BEGIN:VEVENT", ignoreCase = true) ||
                    currentEmail.body.contains("BEGIN:VCALENDAR", ignoreCase = true)
                
                // Проверяем признаки приглашения Exchange в теле (Когда: ... Где: ...)
                val bodyHasMeetingInfo = (currentEmail.body.contains("Когда:", ignoreCase = true) || 
                    currentEmail.body.contains("When:", ignoreCase = true)) &&
                    (currentEmail.body.contains("Где:", ignoreCase = true) || 
                    currentEmail.body.contains("Where:", ignoreCase = true))
                
                // Определяем тип письма: приглашение или ответ на приглашение
                val isAcceptedResponse = currentEmail.subject.startsWith("Принято:", ignoreCase = true) ||
                    currentEmail.subject.startsWith("Accepted:", ignoreCase = true)
                val isDeclinedResponse = currentEmail.subject.startsWith("Отклонено:", ignoreCase = true) ||
                    currentEmail.subject.startsWith("Declined:", ignoreCase = true)
                val isTentativeResponse = currentEmail.subject.startsWith("Под вопросом:", ignoreCase = true) ||
                    currentEmail.subject.startsWith("Tentative:", ignoreCase = true)
                val isMeetingResponse = isAcceptedResponse || isDeclinedResponse || isTentativeResponse
                
                // Проверяем типичные признаки приглашения в теме
                val subjectHasInvitation = currentEmail.subject.contains("Приглашение:", ignoreCase = true) ||
                    currentEmail.subject.contains("Invitation:", ignoreCase = true) ||
                    currentEmail.subject.contains("Meeting:", ignoreCase = true) ||
                    currentEmail.subject.contains("Встреча:", ignoreCase = true)
                val isMeetingInvitation = (hasCalendarAttachment || bodyHasVEvent || subjectHasInvitation || bodyHasMeetingInfo) && !isMeetingResponse
                
                // UI для ответа на приглашение (организатор видит ответ участника)
                if (isMeetingResponse && !isTaskEmail) {
                    val responseStatus = when {
                        isAcceptedResponse -> if (isRussian) "принял приглашение" else "accepted the invitation"
                        isDeclinedResponse -> if (isRussian) "отклонил приглашение" else "declined the invitation"
                        isTentativeResponse -> if (isRussian) "ответил \"Под вопросом\"" else "responded \"Tentative\""
                        else -> ""
                    }
                    val senderName = currentEmail.fromName.ifEmpty { currentEmail.from.substringBefore("@") }
                    val senderEmail = currentEmail.from
                    val calendarRepo = remember { RepositoryProvider.getCalendarRepository(context) }
                    var isUpdating by rememberSaveable { mutableStateOf(false) }
                    
                    // Извлекаем название события из темы (после "Принято:", "Отклонено:" и т.д.)
                    val meetingSubject = currentEmail.subject
                        .removePrefix("Принято:").removePrefix("Accepted:")
                        .removePrefix("Отклонено:").removePrefix("Declined:")
                        .removePrefix("Под вопросом:").removePrefix("Tentative:")
                        .trim()
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isAcceptedResponse -> MaterialTheme.colorScheme.primaryContainer
                                isDeclinedResponse -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when {
                                        isAcceptedResponse -> AppIcons.CheckCircle
                                        isTentativeResponse -> AppIcons.Info
                                        else -> AppIcons.Info // Для отклонённых тоже Info, не крестик
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isAcceptedResponse -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isDeclinedResponse -> MaterialTheme.colorScheme.onErrorContainer
                                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$senderName $responseStatus",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = when {
                                        isAcceptedResponse -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isDeclinedResponse -> MaterialTheme.colorScheme.onErrorContainer
                                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                                    }
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Кнопка ОК для подтверждения и обновления статуса участника
                            Button(
                                onClick = {
                                    scope.launch {
                                        isUpdating = true
                                        try {
                                            val accountId = currentEmail.accountId
                                            
                                            // Определяем статус участника
                                            val attendeeStatus = when {
                                                isAcceptedResponse -> 3 // Accepted
                                                isDeclinedResponse -> 4 // Declined
                                                isTentativeResponse -> 2 // Tentative
                                                else -> 0
                                            }
                                            
                                            // Обновляем статус участника в событии
                                            val result = withContext(Dispatchers.IO) {
                                                calendarRepo.updateAttendeeStatus(
                                                    accountId = accountId,
                                                    meetingSubject = meetingSubject,
                                                    attendeeEmail = senderEmail,
                                                    status = attendeeStatus
                                                )
                                            }
                                            
                                            when (result) {
                                                is EasResult.Success -> {
                                                    Toast.makeText(
                                                        context,
                                                        if (isRussian) "Статус обновлён" else "Status updated",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                is EasResult.Error -> {
                                                    Toast.makeText(
                                                        context,
                                                        NotificationStrings.localizeError(result.message, isRussian),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            Toast.makeText(context, e.message ?: NotificationStrings.getUnknownError(isRussian), Toast.LENGTH_LONG).show()
                                        } finally {
                                            isUpdating = false
                                        }
                                    }
                                },
                                modifier = Modifier.align(Alignment.End),
                                enabled = !isUpdating
                            ) {
                                if (isUpdating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("OK")
                                }
                            }
                        }
                    }
                }
                
                // UI для приглашения на встречу (участник получает приглашение)
                
                if (isMeetingInvitation && !isTaskEmail) {
                    val calendarRepo = remember { RepositoryProvider.getCalendarRepository(context) }
                    var isAccepting by rememberSaveable { mutableStateOf(false) }
                    var eventAdded by rememberSaveable { mutableStateOf(false) }
                    val invitationAcceptedMsg = Strings.invitationAccepted
                    
                    // Состояние для загруженных iCalendar данных из вложения
                    var loadedIcalData by remember { mutableStateOf<String?>(null) }
                    var isLoadingIcal by remember { mutableStateOf(false) }
                    
                    // Загружаем iCalendar из вложения если нужно
                    LaunchedEffect(calendarAttachment?.id, bodyHasVEvent) {
                        if (!bodyHasVEvent && calendarAttachment != null && loadedIcalData == null && !isLoadingIcal) {
                            isLoadingIcal = true
                            try {
                                val account = accountRepo.getActiveAccountSync()
                                if (account != null) {
                                    val easClient = accountRepo.createEasClient(account.id)
                                    if (easClient != null) {
                                        when (val result = easClient.downloadAttachment(calendarAttachment.fileReference)) {
                                            is EasResult.Success -> {
                                                loadedIcalData = String(result.data, Charsets.UTF_8)
                                            }
                                            is EasResult.Error -> { }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                            isLoadingIcal = false
                        }
                    }
                    
                    // Используем данные из тела или из загруженного вложения
                    // Обрабатываем iCalendar line folding (RFC 5545): строки могут быть разбиты
                    // с пробелом или табом в начале продолжения
                    val resolvedIcalData = loadedIcalData
                    val icalData = when {
                        bodyHasVEvent -> currentEmail.body
                        resolvedIcalData != null -> resolvedIcalData
                        else -> ""
                    }.replace(LINE_FOLDING_REGEX, "") // Убираем line folding
                    
                    if (!eventAdded) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        AppIcons.CalendarMonth,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = Strings.meetingInvitation,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            
                                // Индикатор загрузки iCalendar из вложения
                                if (isLoadingIcal) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isRussian) "Загрузка данных..." else "Loading data...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // Получаем email организатора для отправки ответа
                                    val organizerEmail = ICAL_ORGANIZER_REGEX.find(icalData)?.groupValues?.get(1)
                                        ?: currentEmail.from
                                    
                                    // Парсим данные встречи заранее
                                    val meetingSummary = ICAL_SUMMARY_REGEX.find(icalData)?.groupValues?.get(1)
                                        ?.replace("\\n", " ")?.replace("\\,", ",")?.trim()
                                        ?: currentEmail.subject
                                    // Извлекаем TZID и дату из DTSTART/DTEND
                                    val dtStartMatch = ICAL_DTSTART_REGEX.find(icalData)
                                    val dtStartTzid = dtStartMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                                    val dtStart = dtStartMatch?.groupValues?.get(2)
                                    val dtEndMatch = ICAL_DTEND_REGEX.find(icalData)
                                    val dtEndTzid = dtEndMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                                    val dtEnd = dtEndMatch?.groupValues?.get(2)
                                    val meetingLocation = ICAL_LOCATION_REGEX.find(icalData)?.groupValues?.get(1)
                                        ?.replace("\\n", " ")?.replace("\\,", ",")?.trim() ?: ""
                                    val meetingDescription = ICAL_DESCRIPTION_REGEX.find(icalData)?.groupValues?.get(1)
                                        ?.replace("\\n", "\n")?.replace("\\,", ",")?.trim() ?: ""
                                    val meetingStartTime = parseICalDate(dtStart, dtStartTzid) ?: System.currentTimeMillis()
                                    val meetingEndTime = parseICalDate(dtEnd, dtEndTzid) ?: (meetingStartTime + 60 * 60 * 1000)
                                    
                                    // Функция для отправки ответа организатору
                                    suspend fun sendResponseToOrganizer(responseType: String, statusText: String) {
                                        // Используем аккаунт владельца письма, а не activeAccount
                                        val account = withContext(Dispatchers.IO) {
                                            accountRepo.getAccount(currentEmail.accountId)
                                        } ?: return
                                        val myName = account.displayName.ifEmpty { account.email.substringBefore("@") }
                                        val subject = "$statusText: $meetingSummary"
                                        val body = if (isRussian) {
                                            "$myName ${responseType.lowercase()} приглашение на встречу \"$meetingSummary\""
                                        } else {
                                            "$myName ${responseType.lowercase()} the meeting invitation \"$meetingSummary\""
                                        }
                                        
                                        withContext(Dispatchers.IO) {
                                            val easClient = accountRepo.createEasClient(account.id)
                                            easClient?.sendMail(
                                                to = organizerEmail,
                                                subject = subject,
                                                body = body
                                            )
                                        }
                                    }
                            
                                    // Первый ряд: Принять и Отклонить
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Принять
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isAccepting = true
                                                    try {
                                                        // Используем аккаунт владельца письма
                                                        val accountId = currentEmail.accountId
                                                
                                                        // Создаём событие в календаре
                                                        val result = withContext(Dispatchers.IO) {
                                                            calendarRepo.createEvent(
                                                                accountId = accountId,
                                                                subject = meetingSummary,
                                                                startTime = meetingStartTime,
                                                                endTime = meetingEndTime,
                                                                location = meetingLocation,
                                                                body = meetingDescription.ifEmpty { "Приглашение от ${currentEmail.fromName.ifEmpty { currentEmail.from }}" },
                                                                reminder = 15,
                                                                busyStatus = 2 // Busy
                                                            )
                                                        }
                                                
                                                        when (result) {
                                                            is EasResult.Success -> {
                                                                // Отправляем ответ организатору
                                                                sendResponseToOrganizer(
                                                                    if (isRussian) "принял" else "accepted",
                                                                    if (isRussian) "Принято" else "Accepted"
                                                                )
                                                                eventAdded = true
                                                                Toast.makeText(context, invitationAcceptedMsg, Toast.LENGTH_SHORT).show()
                                                            }
                                                            is EasResult.Error -> {
                                                                val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                                                Toast.makeText(context, localizedMsg, Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                                        Toast.makeText(context, e.message ?: NotificationStrings.getUnknownError(isRussian), Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        isAccepting = false
                                                    }
                                                }
                                            },
                                            enabled = !isAccepting,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            if (isAccepting) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color.White
                                                )
                                            } else {
                                                Text(Strings.acceptInvitation)
                                            }
                                        }
                                
                                        // Отклонить
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    isAccepting = true
                                                    try {
                                                        // Отправляем ответ организатору (без добавления в календарь)
                                                        sendResponseToOrganizer(
                                                            if (isRussian) "отклонил" else "declined",
                                                            if (isRussian) "Отклонено" else "Declined"
                                                        )
                                                        eventAdded = true
                                                        Toast.makeText(
                                                            context,
                                                            if (isRussian) "Приглашение отклонено" else "Invitation declined",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    } catch (e: Exception) {
                                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                                        Toast.makeText(context, e.message ?: NotificationStrings.getUnknownError(isRussian), Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        isAccepting = false
                                                    }
                                                }
                                            },
                                            enabled = !isAccepting,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Text(Strings.declineInvitation)
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // Второй ряд: Под вопросом (по центру, жёлтая)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isAccepting = true
                                                    try {
                                                        // Используем аккаунт владельца письма
                                                        val accountId = currentEmail.accountId
                                                    
                                                        // Создаём событие в календаре со статусом Tentative
                                                        val result = withContext(Dispatchers.IO) {
                                                            calendarRepo.createEvent(
                                                                accountId = accountId,
                                                                subject = meetingSummary,
                                                                startTime = meetingStartTime,
                                                                endTime = meetingEndTime,
                                                                location = meetingLocation,
                                                                body = meetingDescription.ifEmpty { "Приглашение от ${currentEmail.fromName.ifEmpty { currentEmail.from }}" },
                                                                reminder = 15,
                                                                busyStatus = 1 // Tentative
                                                            )
                                                        }
                                                        
                                                        when (result) {
                                                            is EasResult.Success -> {
                                                                // Отправляем ответ организатору
                                                                sendResponseToOrganizer(
                                                                    if (isRussian) "ответил \"Под вопросом\" на" else "tentatively accepted",
                                                                    if (isRussian) "Под вопросом" else "Tentative"
                                                                )
                                                                eventAdded = true
                                                                Toast.makeText(context, invitationAcceptedMsg, Toast.LENGTH_SHORT).show()
                                                            }
                                                            is EasResult.Error -> Toast.makeText(context, NotificationStrings.localizeError(result.message, isRussian), Toast.LENGTH_LONG).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                                        Toast.makeText(context, e.message ?: NotificationStrings.getUnknownError(isRussian), Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        isAccepting = false
                                                    }
                                                }
                                            },
                                            enabled = !isAccepting,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = androidx.compose.ui.graphics.Color(0xFFFFC107), // Жёлтый
                                                contentColor = androidx.compose.ui.graphics.Color.Black
                                            )
                                        ) {
                                            Text(Strings.tentativeInvitation)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Вложения (исключаем inline изображения)
                val visibleAttachments = attachments.filter { !it.isInline }
                if (visibleAttachments.isNotEmpty()) {
                    AttachmentsSection(
                        attachments = visibleAttachments,
                        downloadingId = downloadingId,
                        onAttachmentClick = { attachment ->
                            scope.launch {
                                try {
                                    downloadingId = attachment.id
                                    val data = fetchAttachmentBytes(attachment)

                                    if (data != null) {
                                        val safeFileName = attachment.displayName.replace(SAFE_FILENAME_REGEX, "_")
                                        val tempFile = withContext(Dispatchers.IO) {
                                            val previewDir = File(context.cacheDir, "email_preview")
                                            if (!previewDir.exists()) previewDir.mkdirs()
                                            File(previewDir, safeFileName).apply { writeBytes(data) }
                                        }

                                        val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                            .getMimeTypeFromExtension(File(attachment.displayName).extension.lowercase(java.util.Locale.ROOT))
                                            ?: "application/octet-stream"

                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            tempFile
                                        )

                                        pendingPreviewFile = tempFile
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mimeType)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            previewLauncher.launch(intent)

                                            scope.launch {
                                                kotlinx.coroutines.delay(60 * 60 * 1000L)
                                                if (pendingPreviewFile?.absolutePath == tempFile.absolutePath) {
                                                    pendingPreviewFile?.delete()
                                                    pendingPreviewFile = null
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            withContext(Dispatchers.IO) { tempFile.delete() }
                                            pendingPreviewFile = null
                                            val message = if (isRussian) "Нет приложения для просмотра файла" else "No app to preview this file"
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Toast.makeText(context, NotificationStrings.getErrorWithMessage(isRussian, e.message), Toast.LENGTH_LONG).show()
                                } finally {
                                    downloadingId = null
                                }
                            }
                        },
                        onSaveClick = { attachment ->
                            scope.launch {
                                try {
                                    downloadingId = attachment.id
                                    val data = fetchAttachmentBytes(attachment)
                                    if (data != null) {
                                        val safeFileName = attachment.displayName.replace(SAFE_FILENAME_REGEX, "_")
                                        withContext(Dispatchers.IO) {
                                            val contentValues = android.content.ContentValues().apply {
                                                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeFileName)
                                                put(android.provider.MediaStore.Downloads.MIME_TYPE,
                                                    android.webkit.MimeTypeMap.getSingleton()
                                                        .getMimeTypeFromExtension(File(safeFileName).extension) ?: "application/octet-stream")
                                                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/IwoMail")
                                            }
                                            val uri = context.contentResolver.insert(
                                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                                            )
                                            uri?.let {
                                                context.contentResolver.openOutputStream(it)?.use { out -> out.write(data) }
                                            }
                                        }
                                        Toast.makeText(context, if (isRussian) "Сохранено в Downloads/IwoMail/" else "Saved to Downloads/IwoMail/", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                } finally {
                                    downloadingId = null
                                }
                            }
                        },
                        onSaveAsClick = { attachment ->
                            pendingSaveAsAttachment = attachment
                            saveAsLauncher.launch(attachment.displayName)
                        },
                        onShareClick = { attachment ->
                            scope.launch {
                                try {
                                    downloadingId = attachment.id
                                    val data = fetchAttachmentBytes(attachment)
                                    if (data != null) {
                                        val safeFileName = attachment.displayName.replace(SAFE_FILENAME_REGEX, "_")
                                        val shareFile = withContext(Dispatchers.IO) {
                                            val dir = File(context.cacheDir, "email_share")
                                            if (!dir.exists()) dir.mkdirs()
                                            File(dir, safeFileName).apply { writeBytes(data) }
                                        }
                                        val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                            .getMimeTypeFromExtension(File(safeFileName).extension.lowercase(java.util.Locale.ROOT))
                                            ?: "application/octet-stream"
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", shareFile
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = mimeType
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, attachment.displayName))
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                } finally {
                                    downloadingId = null
                                }
                            }
                        },
                        onDragAttachment = { attachment ->
                            scope.launch {
                                try {
                                    downloadingId = attachment.id
                                    val data = fetchAttachmentBytes(attachment)
                                    if (data != null) {
                                        val safeFileName = attachment.displayName.replace(SAFE_FILENAME_REGEX, "_")
                                        val dragFile = withContext(Dispatchers.IO) {
                                            val dir = File(context.cacheDir, "email_drag")
                                            if (!dir.exists()) dir.mkdirs()
                                            File(dir, safeFileName).apply { writeBytes(data) }
                                        }
                                        val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                            .getMimeTypeFromExtension(File(safeFileName).extension.lowercase(java.util.Locale.ROOT))
                                            ?: "application/octet-stream"
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", dragFile
                                        )
                                        val clipData = ClipData(
                                            attachment.displayName,
                                            arrayOf(mimeType),
                                            ClipData.Item(uri)
                                        )
                                        val label = android.widget.TextView(context).apply {
                                            text = attachment.displayName
                                            setTextColor(0xFF333333.toInt())
                                            textSize = 14f
                                            setPadding(24, 12, 24, 12)
                                            setBackgroundColor(0xFFE8E8E8.toInt())
                                            measure(
                                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                                            )
                                            layout(0, 0, measuredWidth, measuredHeight)
                                        }
                                        view.startDragAndDrop(
                                            clipData,
                                            View.DragShadowBuilder(label),
                                            null,
                                            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                                        )
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                } finally {
                                    downloadingId = null
                                }
                            }
                        }
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Индикатор загрузки тела
                if (isLoadingBody) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(Strings.loadingEmail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (bodyLoadError != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "${Strings.loadError}: $bodyLoadError",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else {
                    // Тело письма - определяем HTML или plain text
                    // КРИТИЧНО: Если bodyType=4 (MIME), извлекаем HTML из MIME
                    val rawBody = if (currentEmail.bodyType == 4) {
                        extractHtmlFromMime(currentEmail.body)
                    } else {
                        currentEmail.body
                    }
                    
                    // КРИТИЧНО: Расэкранируем XML entities, если body содержит закодированные HTML-теги.
                    // Проблема: WBXML-парсер при EAS Sync выводит тело как XML,
                    // где <div> становится &lt;div&gt;, <br> → &lt;br&gt; и т.д.
                    // В parseEmail() мы уже добавили unescapeXml, но старые закэшированные
                    // письма в БД всё ещё содержат encoded entities. Этот safety-net
                    // исправляет и старые, и новые данные.
                    val unescapedBody = if (rawBody.contains("&lt;") && (
                            rawBody.contains("&lt;html", ignoreCase = true) ||
                            rawBody.contains("&lt;body", ignoreCase = true) ||
                            rawBody.contains("&lt;div", ignoreCase = true) ||
                            rawBody.contains("&lt;br", ignoreCase = true) ||
                            rawBody.contains("&lt;p>", ignoreCase = true) ||
                            rawBody.contains("&lt;p ", ignoreCase = true) ||
                            rawBody.contains("&lt;table", ignoreCase = true) ||
                            rawBody.contains("&lt;span", ignoreCase = true) ||
                            rawBody.contains("&lt;a ", ignoreCase = true)
                        )) {
                        rawBody
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                            .replace("&amp;", "&")
                    } else {
                        rawBody
                    }
                    
                    // Убираем разделители Exchange из тела (включая частичные остатки типа ~*)
                    val cleanedBody = unescapedBody
                        .replace(EXCHANGE_SEPARATOR_1_REGEX, "")
                        .replace(EXCHANGE_SEPARATOR_2_REGEX, "")
                        .replace(EXCHANGE_SEPARATOR_3_REGEX, "") // Убираем остатки типа ~* или ~**
                        .replace(EXCHANGE_SEPARATOR_4_REGEX, "") // Убираем остатки типа *~ или *~~
                        .trim()
                    val bodyText = cleanedBody.ifEmpty { Strings.noText }
                    val isHtml = bodyText.contains("<html", ignoreCase = true) || 
                                 bodyText.contains("<body", ignoreCase = true) ||
                                 bodyText.contains("<div", ignoreCase = true) ||
                                 bodyText.contains("<p>", ignoreCase = true)
                    
                    if (isHtml) {
                        // HTML контент - используем WebView с белым фоном
                        // key нужен чтобы WebView не пересоздавался при рекомпозиции
                        var webViewHeight by remember { mutableStateOf(0) }
                        var webViewRef by remember { mutableStateOf<WebView?>(null) }
                        // PERF: Отслеживаем последний загруженный HTML чтобы не перезагружать WebView при каждой рекомпозиции
                        var lastLoadedHtml by remember { mutableStateOf("") }
                        
                        DisposableEffect(emailId) {
                            onDispose {
                                webViewRef?.let { webView ->
                                    try {
                                        webView.stopLoading()
                                        webView.loadUrl("about:blank")
                                        webView.clearHistory()
                                        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                                        webView.removeAllViews()
                                        webView.destroy()
                                    } catch (_: Exception) { }
                                }
                                webViewRef = null
                            }
                        }
                        
                        key(emailId) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .then(if (webViewHeight > 0) Modifier.height(webViewHeight.dp) else Modifier),
                                colors = CardDefaults.cardColors(
                                    containerColor = androidx.compose.ui.graphics.Color.White
                                )
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        WebView(ctx).apply {
                                            webViewRef = this
                                            settings.apply {
                                                javaScriptEnabled = true // Нужен для измерения высоты
                                                // Масштабирование контента под ширину экрана
                                                loadWithOverviewMode = true
                                                useWideViewPort = true
                                                // Включаем зум для широкого контента
                                                builtInZoomControls = true
                                                displayZoomControls = false
                                                setSupportZoom(true)
                                                // Включаем загрузку изображений
                                                loadsImagesAutomatically = true
                                                blockNetworkImage = false
                                                blockNetworkLoads = false
                                                // Разрешаем смешанный контент (http изображения на https странице)
                                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                // Дополнительные настройки для загрузки контента
                                                domStorageEnabled = true
                                                allowContentAccess = true
                                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                            }
                                            setBackgroundColor(android.graphics.Color.WHITE)
                                            // Отключаем скролл внутри WebView - скроллит родитель
                                            isVerticalScrollBarEnabled = false
                                            isHorizontalScrollBarEnabled = false
                                            
                                            // Умная обработка touch событий для горизонтального скролла
                                            var startX = 0f
                                            var startY = 0f
                                            setOnTouchListener { v, event ->
                                                when (event.action) {
                                                    android.view.MotionEvent.ACTION_DOWN -> {
                                                        startX = event.x
                                                        startY = event.y
                                                        // Пока не знаем направление - разрешаем родителю
                                                        v.parent?.requestDisallowInterceptTouchEvent(false)
                                                    }
                                                    android.view.MotionEvent.ACTION_MOVE -> {
                                                        val deltaX = kotlin.math.abs(event.x - startX)
                                                        val deltaY = kotlin.math.abs(event.y - startY)
                                                        
                                                        // Если горизонтальное движение больше вертикального - блокируем родителя
                                                        if (deltaX > deltaY && deltaX > 10) {
                                                            v.parent?.requestDisallowInterceptTouchEvent(true)
                                                        } else if (deltaY > deltaX && deltaY > 10) {
                                                            // Вертикальное движение - разрешаем родителю
                                                            v.parent?.requestDisallowInterceptTouchEvent(false)
                                                        }
                                                    }
                                                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                                        // Сбрасываем блокировку
                                                        v.parent?.requestDisallowInterceptTouchEvent(false)
                                                    }
                                                }
                                                false
                                            }
                                            // Измеряем высоту контента после загрузки
                                            webViewClient = object : android.webkit.WebViewClient() {
                                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                                    request?.url?.let { uri ->
                                                        when (uri.scheme) {
                                                            "mailto" -> {
                                                                // Открываем окно написания письма в нашем приложении
                                                                val email = uri.schemeSpecificPart.substringBefore("?")
                                                                onComposeToEmail(email)
                                                            }
                                                            "tel" -> {
                                                                // Номеронабиратель с fallback на буфер обмена (планшеты без телефона)
                                                                try {
                                                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, uri))
                                                                } catch (_: Exception) {
                                                                    val phone = uri.schemeSpecificPart
                                                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Phone", phone))
                                                                    android.widget.Toast.makeText(context, if (isRussian) "Номер скопирован" else "Number copied", android.widget.Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                            else -> {
                                                                // http/https и прочее — открываем в браузере
                                                                try {
                                                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                                                } catch (_: Exception) {
                                                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Link", uri.toString()))
                                                                    android.widget.Toast.makeText(context, if (isRussian) "Ссылка скопирована" else "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                        return true
                                                    }
                                                    return false
                                                }
                                                
                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    val measureJs = "(function(){var b=document.body,d=document.documentElement;return Math.max(b.scrollHeight||0,b.offsetHeight||0,d.scrollHeight||0);})()"
                                                    fun measure(v: WebView) {
                                                        try {
                                                            v.evaluateJavascript(measureJs) { result ->
                                                                val cssH = result?.replace("\"", "")?.toIntOrNull()
                                                                if (cssH != null && cssH > 0) {
                                                                    @Suppress("DEPRECATION")
                                                                    val scale = v.scale
                                                                    val h = (cssH.toFloat() * scale).toInt()
                                                                    if (h > 0 && h < 20000) {
                                                                        webViewHeight = h + 16
                                                                    }
                                                                }
                                                            }
                                                        } catch (_: Exception) { }
                                                    }
                                                    view?.postDelayed({ measure(view) }, 500)
                                                    view?.postDelayed({ measure(view) }, 2000)
                                                }
                                            }
                                        }
                                    },
                                    update = { webView ->
                                        webViewRef = webView
                                        var processedBody = bodyText
                                        for ((cid, dataUrl) in inlineImages) {
                                            processedBody = processedBody
                                                .replace("cid:$cid", dataUrl)
                                                .replace("cid:${cid.removePrefix("<").removeSuffix(">")}", dataUrl)
                                        }
                                        
                                        val sanitizedBody = sanitizeEmailHtml(processedBody)
                                        
                                        val contentKey = sanitizedBody.hashCode().toString() + "_" + inlineImages.size
                                        if (contentKey == lastLoadedHtml) return@AndroidView
                                        lastLoadedHtml = contentKey
                                        
                                        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
                                        
                                        val styledHtml = """
                                            <html>
                                            <head>
                                                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data: cid: https: http: blob:; font-src https: data:;">
                                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                                                <style>
                                                    html { margin: 0 !important; padding: 0 !important; }
                                                    body { 
                                                        background-color: white !important; 
                                                        color: black !important;
                                                        margin: 0 8px !important;
                                                        padding: 0 !important;
                                                        font-family: sans-serif;
                                                        font-size: 14px;
                                                        word-wrap: break-word;
                                                        overflow-wrap: break-word;
                                                    }
                                                    a { color: #1a73e8; word-break: break-all; }
                                                    img { 
                                                        max-width: 100% !important; 
                                                        height: auto !important;
                                                    }
                                                    /* Placeholder для сломанных/незагруженных картинок */
                                                    img.broken-img {
                                                        display: inline-block;
                                                        min-width: 120px;
                                                        min-height: 40px;
                                                        background: #f0f0f0;
                                                        border: 1px dashed #ccc;
                                                        border-radius: 4px;
                                                        position: relative;
                                                    }
                                                    img.broken-img::after {
                                                        content: attr(alt);
                                                        display: block;
                                                        padding: 8px;
                                                        color: #888;
                                                        font-size: 12px;
                                                        text-align: center;
                                                    }
                                                    table { 
                                                        max-width: 100% !important; 
                                                        table-layout: fixed;
                                                    }
                                                    td, th { word-wrap: break-word; }
                                                    pre, code { 
                                                        white-space: pre-wrap; 
                                                        word-wrap: break-word;
                                                        max-width: 100%;
                                                        overflow-x: auto;
                                                    }
                                                    /* Скрываем сломанные CID-изображения (не загрузились из вложений) */
                                                    img[src^="cid:"].broken-img { display: none; }
                                                    /* Убираем хвост пустых элементов от RichTextEditor */
                                                    .image-block + br, .image-block + div:empty,
                                                    body > br:last-child, body > div:last-child:empty,
                                                    body > p:last-child:empty { display: none; }
                                                </style>
                                                <script nonce="$nonce">
                                                document.addEventListener('DOMContentLoaded', function() {
                                                    // Обработка сломанных изображений — показываем placeholder
                                                    document.querySelectorAll('img').forEach(function(img) {
                                                        img.onerror = function() {
                                                            this.classList.add('broken-img');
                                                            if (!this.alt) this.alt = '\u{1F5BC} Image';
                                                            this.onerror = null;
                                                        };
                                                        // Перепроверяем уже failed изображения
                                                        if (img.complete && img.naturalHeight === 0 && img.src && !img.src.startsWith('data:')) {
                                                            img.classList.add('broken-img');
                                                            if (!img.alt) img.alt = '\u{1F5BC} Image';
                                                        }
                                                    });
                                                    function cleanTrail(el, depth) {
                                                        if (depth > 5) return;
                                                        while (el.lastChild) {
                                                            var last = el.lastChild;
                                                            if (last.nodeType === 3 && last.textContent.trim() === '') {
                                                                el.removeChild(last); continue;
                                                            }
                                                            if (last.nodeType !== 1) break;
                                                            var tag = last.tagName;
                                                            if (tag === 'IMG' && last.classList.contains('broken-img')) {
                                                                el.removeChild(last); continue;
                                                            }
                                                            if (tag === 'BR') { el.removeChild(last); continue; }
                                                            var inner = last.innerHTML ? last.innerHTML.replace(/&nbsp;/g,'').replace(/<br\s*\/?>/gi,'').trim() : '';
                                                            if ((tag === 'P' || tag === 'DIV' || tag === 'SPAN') && inner === '') {
                                                                el.removeChild(last); continue;
                                                            }
                                                            if ((tag === 'DIV' || tag === 'P' || tag === 'SPAN') && last.children.length > 0) {
                                                                var onlyBroken = true;
                                                                for (var ci = 0; ci < last.children.length; ci++) {
                                                                    var ch = last.children[ci];
                                                                    if (!(ch.tagName === 'IMG' && ch.classList.contains('broken-img')) && ch.tagName !== 'BR') {
                                                                        onlyBroken = false; break;
                                                                    }
                                                                }
                                                                if (onlyBroken && last.textContent.trim() === '') {
                                                                    el.removeChild(last); continue;
                                                                }
                                                            }
                                                            break;
                                                        }
                                                    }
                                                    cleanTrail(document.body, 0);
                                                    var lastSig = document.body.lastElementChild;
                                                    if (lastSig && /^(DIV|SECTION|TD|ARTICLE)$/.test(lastSig.tagName)) {
                                                        cleanTrail(lastSig, 1);
                                                    }
                                                    function isInsideLink(node) {
                                                        var p = node.parentNode;
                                                        while (p && p !== document.body) {
                                                            if (p.tagName === 'A') return true;
                                                            p = p.parentNode;
                                                        }
                                                        return false;
                                                    }
                                                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                                                    var nodes = [];
                                                    while (walker.nextNode()) nodes.push(walker.currentNode);
                                                    nodes.forEach(function(node) {
                                                        if (isInsideLink(node)) return;
                                                        var p = node.parentNode;
                                                        if (p && (p.tagName === 'SCRIPT' || p.tagName === 'STYLE')) return;
                                                        var text = node.textContent;
                                                        var html = text.replace(
                                                            /(https?:\/\/[^\s<>"']+)|(\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b)|(\+?\d[\d\s\-()]{6,}\d)/g,
                                                            function(m, url, email, phone) {
                                                                if (url) return '<a href="' + url + '">' + url + '</a>';
                                                                if (email) return '<a href="mailto:' + email + '">' + email + '</a>';
                                                                if (phone) return '<a href="tel:' + phone.replace(/[\s\-()]/g, '') + '">' + phone + '</a>';
                                                                return m;
                                                            }
                                                        );
                                                        if (html !== text) {
                                                            var span = document.createElement('span');
                                                            span.innerHTML = html;
                                                            p.replaceChild(span, node);
                                                        }
                                                    });
                                                });
                                                </script>
                                            </head>
                                            <body>$sanitizedBody</body>
                                            </html>
                                        """.trimIndent()
                                        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                        }
                    }
                    } else {
                        // Plain text — с кликабельными ссылками и email-адресами
                        val linkColor = MaterialTheme.colorScheme.primary
                        val bodyStyle = MaterialTheme.typography.bodyMedium
                        val annotatedBody = remember(bodyText, linkColor) {
                            buildAnnotatedString {
                                var lastIndex = 0
                                BODY_LINK_REGEX.findAll(bodyText).forEach { match ->
                                    // Текст до ссылки
                                    if (match.range.first > lastIndex) {
                                        append(bodyText.substring(lastIndex, match.range.first))
                                    }
                                    val link = match.value
                                    val isEmail = link.contains("@") && !link.startsWith("http")
                                    val isPhone = !isEmail && !link.startsWith("http") && link.any { it.isDigit() }
                                    val tag = when {
                                        isEmail -> "email"
                                        isPhone -> "phone"
                                        else -> "url"
                                    }
                                    pushStringAnnotation(
                                        tag = tag,
                                        annotation = link
                                    )
                                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                        append(link)
                                    }
                                    pop()
                                    lastIndex = match.range.last + 1
                                }
                                if (lastIndex < bodyText.length) {
                                    append(bodyText.substring(lastIndex))
                                }
                            }
                        }
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = annotatedBody,
                            style = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.padding(16.dp),
                            onClick = { offset ->
                                annotatedBody.getStringAnnotations("url", offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item)))
                                        } catch (_: Exception) {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Link", annotation.item))
                                            android.widget.Toast.makeText(context, if (isRussian) "Ссылка скопирована" else "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                annotatedBody.getStringAnnotations("email", offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        onComposeToEmail(annotation.item)
                                    }
                                annotatedBody.getStringAnnotations("phone", offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            val cleanPhone = annotation.item.replace(Regex("[\\s\\-()]"), "")
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")))
                                        } catch (_: Exception) {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Phone", annotation.item))
                                            android.widget.Toast.makeText(context, if (isRussian) "Номер скопирован" else "Number copied", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            }
                        )
                    }
                } // end else (body loaded)
                
                Spacer(modifier = Modifier.height(80.dp)) // Для FAB
                }
                ScrollColumnScrollbar(detailScrollState)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentsSection(
    attachments: List<AttachmentEntity>,
    downloadingId: Long?,
    onAttachmentClick: (AttachmentEntity) -> Unit,
    onSaveClick: (AttachmentEntity) -> Unit = {},
    onSaveAsClick: (AttachmentEntity) -> Unit = {},
    onShareClick: (AttachmentEntity) -> Unit = {},
    onDragAttachment: (AttachmentEntity) -> Unit = {}
) {
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = Strings.attachmentsWithCount(attachments.size),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        attachments.forEach { attachment ->
            val isDownloading = downloadingId == attachment.id
            var showSaveMenu by remember { mutableStateOf(false) }
            
            Box {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .combinedClickable(
                            enabled = !isDownloading,
                            onClick = { onAttachmentClick(attachment) },
                            onLongClick = { onDragAttachment(attachment) }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.fileIconFor(attachment.displayName),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.Unspecified
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatFileSize(attachment.estimatedSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = { showSaveMenu = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    AppIcons.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                DropdownMenu(
                    expanded = showSaveMenu,
                    onDismissRequest = { showSaveMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isRussian) "Просмотр" else "Preview") },
                        onClick = {
                            showSaveMenu = false
                            onAttachmentClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Visibility, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.save) },
                        onClick = {
                            showSaveMenu = false
                            onSaveClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Download, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.saveAs) },
                        onClick = {
                            showSaveMenu = false
                            onSaveAsClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isRussian) "Поделиться" else "Share") },
                        onClick = {
                            showSaveMenu = false
                            onShareClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Share, contentDescription = null) }
                    )
                }
            }
        }
    }
}

private val detailDateFormat = java.lang.ThreadLocal.withInitial { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }

private fun formatFullDate(timestamp: Long): String {
    return detailDateFormat.get()?.format(java.util.Date(timestamp)) ?: ""
}

private fun formatFileSize(bytes: Long): String {
    val isRussian = java.util.Locale.getDefault().language == "ru"
    return when {
        bytes < 1024 -> if (isRussian) "$bytes Б" else "$bytes B"
        bytes < 1024 * 1024 -> if (isRussian) "${bytes / 1024} КБ" else "${bytes / 1024} KB"
        else -> if (isRussian) "${bytes / (1024 * 1024)} МБ" else "${bytes / (1024 * 1024)} MB"
    }
}

/**
 * Извлекает отображаемое имя из email строки
 * Обрабатывает X.500 DN формат: "name" </O=ORG/OU=.../CN=NAME>
 */
private fun extractDisplayName(email: String): String {
    // X.500 DN формат
    if (email.contains("/O=") || email.contains("/CN=")) {
        // Ищем CN (Common Name)
        val cnMatch = CN_REGEX.findAll(email).toList()
        val lastCn = cnMatch.lastOrNull()?.groupValues?.get(1)?.trim()
        if (lastCn != null && !lastCn.equals("RECIPIENTS", ignoreCase = true)) {
            return lastCn.lowercase().replaceFirstChar { it.uppercase() }
        }
        // Fallback - имя до <
        val nameMatch = NAME_BEFORE_BRACKET_REGEX.find(email)
        if (nameMatch != null) {
            return nameMatch.groupValues[1].trim()
        }
    }
    
    // Стандартный формат: "John Doe <john@example.com>"
    val match = NAME_BEFORE_BRACKET_REGEX.find(email)
    return match?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") 
        ?: email.substringBefore("@").substringBefore("<").trim()
}

/**
 * Извлекает email адрес из строки
 * Возвращает пустую строку если это X.500 DN без нормального email
 */
private fun extractEmailAddress(email: String): String {
    // Ищем email в угловых скобках: <user@domain.com>
    val emailMatch = EMAIL_IN_BRACKETS_REGEX.find(email)
    if (emailMatch != null) {
        return emailMatch.groupValues[1]
    }
    
    // Если это X.500 DN без нормального email - возвращаем пустую строку
    if (email.contains("/O=") || email.contains("/CN=")) {
        return ""
    }
    
    // Простой email без скобок
    if (email.contains("@") && !email.contains("<")) {
        return email.trim()
    }
    
    return ""
}

/**
 * Парсит строку получателей в пары (displayName, email)
 * Разделитель: запятая или точка с запятой
 */
private fun parseRecipientPairs(recipients: String): List<Pair<String, String>> {
    if (recipients.isBlank()) return emptyList()
    return recipients.split(",", ";").map { recipient ->
        val trimmed = recipient.trim()
        val name = extractDisplayName(trimmed)
        val email = extractEmailAddress(trimmed)
        Pair(name, email)
    }
}

/**
 * Форматирует строку получателей для отображения
 * Убирает дублирование имени и email, показывает только имя или email
 */
private fun formatRecipients(recipients: String): String {
    if (recipients.isBlank()) return ""
    
    // Разбиваем по запятой (может быть несколько получателей)
    return recipients.split(",").joinToString(", ") { recipient ->
        val trimmed = recipient.trim()
        val name = extractDisplayName(trimmed)
        val email = extractEmailAddress(trimmed)
        
        // Если имя совпадает с email (или его частью) - показываем только email
        if (name.isBlank() || name.equals(email, ignoreCase = true) || 
            name.equals(email.substringBefore("@"), ignoreCase = true)) {
            email.ifEmpty { trimmed }
        } else {
            // Показываем имя, а email в скобках только если отличается
            if (email.isNotEmpty()) "$name <$email>" else name
        }
    }
}

/**
 * Извлекает HTML из MIME данных (для bodyType=4)
 * Также используется для извлечения inline-картинок
 */
private fun extractHtmlFromMime(mimeData: String): String {
    // Декодируем base64 если нужно
    val decoded = try {
        if (mimeData.matches(BASE64_DETECT_REGEX) && mimeData.length > 100) {
            String(android.util.Base64.decode(mimeData, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } else {
            mimeData
        }
    } catch (e: Exception) {
        mimeData
    }
    
    // Ищем HTML часть
    val htmlPattern = "Content-Type:\\s*text/html.*?\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val htmlMatch = htmlPattern.find(decoded)
    if (htmlMatch != null) {
        var content = htmlMatch.groupValues[1].trim()
        // Декодируем quoted-printable если нужно
        if (decoded.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
            content = decodeQuotedPrintableMime(content)
        }
        return content
    }
    
    // Fallback на text/plain
    val textPattern = "Content-Type:\\s*text/plain.*?\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val textMatch = textPattern.find(decoded)
    if (textMatch != null) {
        var content = textMatch.groupValues[1].trim()
        if (decoded.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
            content = decodeQuotedPrintableMime(content)
        }
        // Конвертируем text в HTML
        return content.replace("\n", "<br>")
    }
    
    // Если не нашли - возвращаем как есть
    return mimeData
}

/**
 * Извлекает inline изображения из MIME данных
 * Возвращает Map<contentId, base64DataUrl>
 */
private fun extractInlineImagesFromMime(mimeData: String): Map<String, String> {
    val images = mutableMapOf<String, String>()
    
    // Декодируем base64 если нужно
    val decoded = try {
        if (mimeData.matches(BASE64_DETECT_REGEX) && mimeData.length > 100) {
            String(android.util.Base64.decode(mimeData, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } else {
            mimeData
        }
    } catch (e: Exception) {
        return images
    }
    
    if (!decoded.contains("Content-Type:", ignoreCase = true)) {
        return images
    }
    
    // КРИТИЧНО: Рекурсивно обрабатываем вложенные multipart-структуры.
    // При файловых вложениях MIME: multipart/mixed { multipart/related { html + image } + attachment }
    extractImagesRecursive(decoded, images)
    
    return images
}

/**
 * Рекурсивно извлекает inline-картинки из вложенных multipart-структур MIME
 */
private fun extractImagesRecursive(mimeSection: String, images: MutableMap<String, String>) {
    val boundaryRegex = "boundary=\"?([^\"\\r\\n]+)\"?".toRegex(RegexOption.IGNORE_CASE)
    val boundaryMatch = boundaryRegex.find(mimeSection) ?: return
    val boundary = boundaryMatch.groupValues[1]
    val parts = mimeSection.split("--$boundary")
    
    for (part in parts) {
        // Если часть содержит вложенный multipart — рекурсивно обрабатываем
        val isNestedMultipart = part.contains("Content-Type: multipart/", ignoreCase = true) ||
                               part.contains("Content-Type:multipart/", ignoreCase = true)
        if (isNestedMultipart) {
            extractImagesRecursive(part, images)
            continue
        }
        
        // Ищем inline изображения
        val isImage = part.contains("Content-Type: image/", ignoreCase = true) ||
                     part.contains("Content-Type:image/", ignoreCase = true)
        
        if (!isImage) continue
        
        // Извлекаем Content-ID
        val cidPattern = "Content-ID:\\s*<([^>]+)>".toRegex(RegexOption.IGNORE_CASE)
        val cidMatch = cidPattern.find(part)
        val contentId = cidMatch?.groupValues?.get(1) ?: continue
        
        // Извлекаем Content-Type для data URL
        val typePattern = "Content-Type:\\s*(image/[^;\\r\\n]+)".toRegex(RegexOption.IGNORE_CASE)
        val typeMatch = typePattern.find(part)
        val contentType = typeMatch?.groupValues?.get(1)?.trim() ?: "image/png"
        
        // Извлекаем данные (после пустой строки)
        val contentStart = part.indexOf("\r\n\r\n")
        if (contentStart == -1) continue
        
        var content = part.substring(contentStart + 4).trim()
        // Убираем закрывающий boundary
        if (content.endsWith("--")) {
            content = content.dropLast(2).trim()
        }
        content = content.replace("\r\n", "").replace("\n", "").replace(" ", "")
        
        // Формируем data URL
        if (content.isNotBlank()) {
            val dataUrl = "data:$contentType;base64,$content"
            images[contentId] = dataUrl
        }
    }
}

/**
 * Декодирует quoted-printable с корректной поддержкой UTF-8
 */
private fun decodeQuotedPrintableMime(input: String): String {
    val text = input.replace("=\r\n", "").replace("=\n", "") // Soft line breaks
    val bytes = mutableListOf<Byte>()
    var i = 0
    
    while (i < text.length) {
        val c = text[i]
        if (c == '=' && i + 2 < text.length) {
            val hex = text.substring(i + 1, i + 3)
            try {
                val byte = hex.toInt(16).toByte()
                bytes.add(byte)
                i += 3
                continue
            } catch (_: Exception) {}
        }
        // Обычный ASCII символ - добавляем как байт
        bytes.add(c.code.toByte())
        i++
    }
    
    // Декодируем байты как UTF-8
    return try {
        String(bytes.toByteArray(), Charsets.UTF_8)
    } catch (_: Exception) {
        // Fallback на ISO-8859-1 если UTF-8 не сработал
        String(bytes.toByteArray(), Charsets.ISO_8859_1)
    }
}

/**
 * Парсит дату из iCalendar формата с учётом таймзоны
 * Поддерживает форматы: 20260115T100000Z, 20260115T100000, 20260115
 * @param dateStr строка даты
 * @param tzid идентификатор таймзоны (например "Europe/Moscow"), null для локальной
 */
private fun parseICalDate(dateStr: String?, tzid: String? = null): Long? {
    if (dateStr.isNullOrBlank()) return null
    
    return try {
        val isUtc = dateStr.endsWith("Z")
        val cleanDate = dateStr.removeSuffix("Z")
        
        val format = when (cleanDate.length) {
            8 -> java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            15 -> java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            else -> return null
        }
        
        // Устанавливаем таймзону: UTC если Z, иначе TZID если указан, иначе локальная
        format.timeZone = when {
            isUtc -> java.util.TimeZone.getTimeZone("UTC")
            !tzid.isNullOrBlank() -> java.util.TimeZone.getTimeZone(tzid)
            else -> java.util.TimeZone.getDefault()
        }
        
        format.parse(cleanDate)?.time
    } catch (_: Exception) {
        null
    }
}

private val SCRIPT_TAG_REGEX = Regex("(?si)<script[^>]*>.*?</script>")
private val SCRIPT_OPEN_CLOSE_REGEX = Regex("(?i)</?script[^>]*>")
private val EVENT_HANDLER_DOUBLE_QUOTE = Regex("""(?i)\s+on\w+\s*=\s*"[^"]*"""")
private val EVENT_HANDLER_SINGLE_QUOTE = Regex("""(?i)\s+on\w+\s*=\s*'[^']*'""")
private val EVENT_HANDLER_UNQUOTED = Regex("""(?i)\s+on\w+\s*=\s*[^\s>"']+""")
private val JS_URI_DOUBLE_QUOTE = Regex("""(?i)(href|src|action|formaction)\s*=\s*"\s*javascript:[^"]*"""")
private val JS_URI_SINGLE_QUOTE = Regex("""(?i)(href|src|action|formaction)\s*=\s*'\s*javascript:[^']*'""")

private fun sanitizeEmailHtml(html: String): String = html
    .replace(SCRIPT_TAG_REGEX, "")
    .replace(SCRIPT_OPEN_CLOSE_REGEX, "")
    .replace(EVENT_HANDLER_DOUBLE_QUOTE, "")
    .replace(EVENT_HANDLER_SINGLE_QUOTE, "")
    .replace(EVENT_HANDLER_UNQUOTED, "")
    .replace(JS_URI_DOUBLE_QUOTE, """$1="#"""")
    .replace(JS_URI_SINGLE_QUOTE, """$1='#'""")

