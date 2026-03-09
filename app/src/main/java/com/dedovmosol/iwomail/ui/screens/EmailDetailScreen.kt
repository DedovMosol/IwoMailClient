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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dedovmosol.iwomail.util.SAFE_FILENAME_REGEX
import com.dedovmosol.iwomail.util.TASK_SUBJECT_REGEX
import com.dedovmosol.iwomail.util.BODY_LINK_REGEX
import com.dedovmosol.iwomail.util.TASK_DUE_DATE_REGEX
import com.dedovmosol.iwomail.util.TASK_DESCRIPTION_REGEX
import com.dedovmosol.iwomail.util.HTML_STRIP_REGEX
import com.dedovmosol.iwomail.util.ICAL_SUMMARY_REGEX
import com.dedovmosol.iwomail.util.ICAL_DTSTART_REGEX
import com.dedovmosol.iwomail.util.ICAL_DTEND_REGEX
import com.dedovmosol.iwomail.util.ICAL_LOCATION_REGEX
import com.dedovmosol.iwomail.util.ICAL_DESCRIPTION_REGEX
import com.dedovmosol.iwomail.util.ICAL_ORGANIZER_REGEX
import com.dedovmosol.iwomail.util.LINE_FOLDING_REGEX
import com.dedovmosol.iwomail.util.EXCHANGE_SEPARATOR_1_REGEX
import com.dedovmosol.iwomail.util.EXCHANGE_SEPARATOR_2_REGEX
import com.dedovmosol.iwomail.util.EXCHANGE_SEPARATOR_3_REGEX
import com.dedovmosol.iwomail.util.EXCHANGE_SEPARATOR_4_REGEX
import com.dedovmosol.iwomail.util.MimeHtmlProcessor
import com.dedovmosol.iwomail.util.ICalParser
import com.dedovmosol.iwomail.ui.screens.emaildetail.AttachmentsSection
import com.dedovmosol.iwomail.util.extractDisplayName
import com.dedovmosol.iwomail.util.extractEmailAddress
import com.dedovmosol.iwomail.util.parseRecipientPairs
import com.dedovmosol.iwomail.util.formatFullDate
import com.dedovmosol.iwomail.util.formatFileSize
import com.dedovmosol.iwomail.util.sanitizeEmailHtml
import java.io.File

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
    val calendarRepo = remember { RepositoryProvider.getCalendarRepository(context) }
    val taskRepo = remember { RepositoryProvider.getTaskRepository(context) }
    val actions = remember { com.dedovmosol.iwomail.ui.screens.emaildetail.EmailDetailActions(mailRepo, accountRepo, calendarRepo, taskRepo) }
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN

    suspend fun fetchAttachmentBytes(att: AttachmentEntity): ByteArray? {
        if (!NetworkMonitor.isNetworkAvailable(context) && !(att.downloaded && att.localPath != null)) {
            Toast.makeText(context, if (isRussian) "Нет сети" else "No network", Toast.LENGTH_SHORT).show()
            return null
        }
        return when (val result = actions.fetchAttachmentBytes(att)) {
            is EasResult.Success -> result.data
            is EasResult.Error -> {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                null
            }
        }
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
                    downloadingId = attachment.id
                    val tmpFile = withContext(Dispatchers.IO) {
                        java.io.File.createTempFile("att_save_", ".tmp", context.cacheDir)
                    }
                    try {
                        when (val result = actions.downloadAttachmentToFile(attachment, tmpFile)) {
                            is EasResult.Success -> {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                        tmpFile.inputStream().use { input -> input.copyTo(out) }
                                    }
                                }
                                val savedMsg = if (isRussian) "Файл сохранён" else "File saved"
                                Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                            }
                            is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        tmpFile.delete()
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
        
        isLoadingInlineImages = true
        val loaded = actions.loadInlineImages(
            emailBody = body,
            bodyType = currentEmail.bodyType,
            emailServerId = currentEmail.serverId,
            folderId = currentEmail.folderId,
            accountId = currentEmail.accountId,
            attachments = attachments
        )
        if (BuildConfig.DEBUG) {
            android.util.Log.d("InlineImages", "=== FINISH: Total ${loaded.size} images loaded")
        }
        inlineImages = loaded
        isLoadingInlineImages = false
    }
    
    LaunchedEffect(emailId) {
        val currentEmail = withContext(Dispatchers.IO) {
            actions.getEmailSync(emailId)
        }
        
        if (currentEmail == null) {
            bodyLoadError = if (isRussian) "Письмо не найдено. Попробуйте обновить входящие." else "Email not found. Try refreshing inbox."
            return@LaunchedEffect
        }
        
        if (!currentEmail.read) {
            launch(Dispatchers.IO) {
                when (val result = actions.markAsRead(emailId, true)) {
                    is EasResult.Success -> { }
                    is EasResult.Error -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        
        if (currentEmail.body.isEmpty() && !isLoadingBody) {
            isLoadingBody = true
            bodyLoadError = null
            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    actions.loadEmailBody(emailId)
                }
                when (result) {
                    is EasResult.Success -> {
                        launch(Dispatchers.IO) {
                            try { actions.refreshAttachmentMetadata(emailId) } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                    }
                    is EasResult.Error -> {
                        if (result.message == "OBJECT_NOT_FOUND") {
                            Toast.makeText(
                                context,
                                if (isRussian) "Письмо удалено на сервере" else "Email deleted on server",
                                Toast.LENGTH_SHORT
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
            isLoadingBody = true
            try {
                withContext(Dispatchers.IO) {
                    actions.loadEmailBody(emailId, forceReload = true)
                    actions.refreshAttachmentMetadata(emailId)
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
                scope.launch { actions.markMdnSent(emailId) }
            },
            icon = { Icon(AppIcons.MarkEmailRead, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(Strings.readReceiptRequest) },
            text = { Text(Strings.readReceiptRequestText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSendingMdn = true
                            when (val result = actions.sendMdn(emailId)) {
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
                        scope.launch { actions.markMdnSent(emailId) }
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
                            val result = actions.deleteEmails(listOf(emailId), isInTrash)
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
                                            val result = actions.moveEmails(listOf(emailId), folder.id)
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
                                    val result = actions.syncAndReloadBody(emailId, currentEmail.accountId, currentEmail.folderId)
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
                                            val result = actions.restoreFromTrash(listOf(emailId))
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
                                            when (val result = actions.markAsRead(emailId, false)) {
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
                            scope.launch { actions.toggleFlag(emailId) }
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
                
                val isTaskEmail = currentEmail.subject.startsWith("Задача:") || currentEmail.subject.startsWith("Task:")
                if (isTaskEmail) {
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
                                            
                                            val taskInfo = ICalParser.parseTaskFromEmailBody(currentEmail.subject, currentEmail.body)
                                            val taskTitle = taskInfo?.subject ?: currentEmail.subject
                                            val dueDate = taskInfo?.dueDate ?: (System.currentTimeMillis() + 86_400_000L)
                                            val description = taskInfo?.description?.ifEmpty { null }
                                                ?: "Задача из письма от ${currentEmail.fromName.ifEmpty { currentEmail.from }}"

                                            val now = System.currentTimeMillis()
                                            val reminderTime = dueDate - 15 * 60 * 1000
                                            val shouldRemind = reminderTime > now
                                            
                                            val result = actions.createTask(
                                                accountId = accountId,
                                                subject = taskTitle,
                                                body = description,
                                                dueDate = dueDate,
                                                reminderSet = shouldRemind,
                                                reminderTime = reminderTime
                                            )
                                            
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
                                            
                                            val result = actions.updateAttendeeStatus(
                                                accountId = accountId,
                                                meetingSubject = meetingSubject,
                                                attendeeEmail = senderEmail,
                                                status = attendeeStatus
                                            )
                                            
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
                                when (val result = actions.fetchAttachmentBytes(calendarAttachment)) {
                                    is EasResult.Success -> {
                                        loadedIcalData = String(result.data, Charsets.UTF_8)
                                    }
                                    is EasResult.Error -> { }
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
                                    val meetingStartTime = ICalParser.parseICalDate(dtStart, dtStartTzid) ?: System.currentTimeMillis()
                                    val meetingEndTime = ICalParser.parseICalDate(dtEnd, dtEndTzid) ?: (meetingStartTime + 60 * 60 * 1000)
                                    
                                    suspend fun buildResponseAndSend(responseType: String, statusText: String) {
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
                                        actions.sendResponseToOrganizer(currentEmail.accountId, organizerEmail, subject, body)
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
                                                        val accountId = currentEmail.accountId
                                                        val desc = meetingDescription.ifEmpty { "Приглашение от ${currentEmail.fromName.ifEmpty { currentEmail.from }}" }
                                                        val responseSubject = "${if (isRussian) "Принято" else "Accepted"}: $meetingSummary"
                                                        val myName = withContext(Dispatchers.IO) {
                                                            accountRepo.getAccount(accountId)
                                                        }?.let { it.displayName.ifEmpty { it.email.substringBefore("@") } } ?: ""
                                                        val responseBody = if (isRussian) "$myName принял приглашение на встречу \"$meetingSummary\"" else "$myName accepted the meeting invitation \"$meetingSummary\""
                                                        val result = actions.acceptMeeting(
                                                            accountId = accountId,
                                                            summary = meetingSummary,
                                                            startTime = meetingStartTime,
                                                            endTime = meetingEndTime,
                                                            location = meetingLocation,
                                                            description = desc,
                                                            busyStatus = 2,
                                                            organizerEmail = organizerEmail,
                                                            responseSubject = responseSubject,
                                                            responseBody = responseBody
                                                        )
                                                
                                                        when (result) {
                                                            is EasResult.Success -> {
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
                                                        buildResponseAndSend(
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
                                                        val accountId = currentEmail.accountId
                                                        val desc = meetingDescription.ifEmpty { "Приглашение от ${currentEmail.fromName.ifEmpty { currentEmail.from }}" }
                                                        val responseSubject = "${if (isRussian) "Под вопросом" else "Tentative"}: $meetingSummary"
                                                        val myName = withContext(Dispatchers.IO) {
                                                            accountRepo.getAccount(accountId)
                                                        }?.let { it.displayName.ifEmpty { it.email.substringBefore("@") } } ?: ""
                                                        val responseBody = if (isRussian) "$myName ответил \"Под вопросом\" на приглашение на встречу \"$meetingSummary\"" else "$myName tentatively accepted the meeting invitation \"$meetingSummary\""
                                                        val result = actions.acceptMeeting(
                                                            accountId = accountId,
                                                            summary = meetingSummary,
                                                            startTime = meetingStartTime,
                                                            endTime = meetingEndTime,
                                                            location = meetingLocation,
                                                            description = desc,
                                                            busyStatus = 1,
                                                            organizerEmail = organizerEmail,
                                                            responseSubject = responseSubject,
                                                            responseBody = responseBody
                                                        )
                                                        
                                                        when (result) {
                                                            is EasResult.Success -> {
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
                        MimeHtmlProcessor.extractHtmlFromMime(currentEmail.body)
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



