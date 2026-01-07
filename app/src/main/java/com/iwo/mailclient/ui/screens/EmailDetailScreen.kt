package com.iwo.mailclient.ui.screens

import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll

import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.iwo.mailclient.data.database.AttachmentEntity
import com.iwo.mailclient.data.database.EmailEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.data.repository.RepositoryProvider
import com.iwo.mailclient.eas.AttachmentManager
import com.iwo.mailclient.eas.EasClient
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.LocalLanguage
import com.iwo.mailclient.ui.AppLanguage
import com.iwo.mailclient.ui.NotificationStrings
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

// Предкомпилированные regex для производительности
private val CN_REGEX = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE)
private val NAME_BEFORE_BRACKET_REGEX = Regex("^\"?([^\"<]+)\"?\\s*<")
private val EMAIL_IN_BRACKETS_REGEX = Regex("<([^>]+@[^>]+)>")
private val CID_REGEX = Regex("cid:([^\"'\\s>]+)")
private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._-]")
// Regex для парсинга задачи из письма
private val TASK_SUBJECT_REGEX = Regex("^Задача:\\s*(.+)$|^Task:\\s*(.+)$", RegexOption.IGNORE_CASE)
private val TASK_DUE_DATE_REGEX = Regex("Срок выполнения:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})|Due date:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})", RegexOption.IGNORE_CASE)
private val TASK_DESCRIPTION_REGEX = Regex("Описание:\\s*(.+?)(?=\\n\\n|Срок|Due|$)|Description:\\s*(.+?)(?=\\n\\n|Срок|Due|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
// Regex для парсинга iCalendar приглашений
private val ICAL_SUMMARY_REGEX = Regex("SUMMARY[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
private val ICAL_DTSTART_REGEX = Regex("DTSTART[^:]*:(\\d{8}T\\d{6}Z?|\\d{8})")
private val ICAL_DTEND_REGEX = Regex("DTEND[^:]*:(\\d{8}T\\d{6}Z?|\\d{8})")
private val ICAL_LOCATION_REGEX = Regex("LOCATION[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
private val ICAL_DESCRIPTION_REGEX = Regex("DESCRIPTION[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
private val ICAL_ORGANIZER_REGEX = Regex("ORGANIZER[^:]*:mailto:([^\\r\\n]+)", RegexOption.IGNORE_CASE)

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
    val mailRepo = remember { MailRepository(context) }
    val accountRepo = remember { AccountRepository(context) }
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    
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
    
    // Диалог выбора папки для перемещения
    var showMoveDialog by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<com.iwo.mailclient.data.database.FolderEntity>>(emptyList()) }
    var isMoving by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    
    // Загружаем папки для диалога перемещения
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    LaunchedEffect(activeAccount?.id) {
        activeAccount?.let { account ->
            mailRepo.getFolders(account.id).collect { folders = it }
        }
    }
    
    // Определяем, находится ли письмо в папке Удалённые (тип 4) или Отправленные (тип 5)
    val currentFolder = folders.find { it.id == email?.folderId }
    val isInTrash = currentFolder?.type == 4
    val isInSent = currentFolder?.type == 5
    
    // Кэш inline изображений: contentId -> base64 data URL
    var inlineImages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoadingInlineImages by remember { mutableStateOf(false) }
    
    // Загружаем inline изображения ПАРАЛЛЕЛЬНО
    LaunchedEffect(email?.body, attachments) {
        val body = email?.body ?: return@LaunchedEffect
        if (body.isEmpty() || isLoadingInlineImages) return@LaunchedEffect
        
        // Находим все cid: ссылки в HTML
        val cidRefs = CID_REGEX.findAll(body).map { it.groupValues[1] }.toSet()
        
        if (cidRefs.isEmpty()) return@LaunchedEffect
        
        // Находим вложения с соответствующими contentId
        val inlineAttachments = attachments.filter { att ->
            att.contentId != null && (cidRefs.contains(att.contentId) || cidRefs.contains(att.displayName))
        }
        
        if (inlineAttachments.isEmpty()) return@LaunchedEffect
        
        isLoadingInlineImages = true
        val account = accountRepo.getActiveAccountSync()
        val password = account?.let { accountRepo.getPassword(it.id) }
        
        if (account != null && password != null) {
            // Загружаем все картинки параллельно
            val results = withContext(Dispatchers.IO) {
                supervisorScope {
                    inlineAttachments.map { att ->
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
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }
            }
            
            val newImages = mutableMapOf<String, String>()
            results.filterNotNull().forEach { pair ->
                pair.first.contentId?.let { newImages[it] = pair.second }
                newImages[pair.first.displayName] = pair.second
            }
            inlineImages = newImages
        }
        isLoadingInlineImages = false
    }
    
    // Помечаем как прочитанное и загружаем тело если нужно
    LaunchedEffect(email?.id) {
        email?.let {
            if (!it.read) {
                when (val result = mailRepo.markAsRead(emailId, true)) {
                    is EasResult.Success -> { /* OK */ }
                    is EasResult.Error -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Ленивая загрузка тела если пустое
            if (it.body.isEmpty() && !isLoadingBody) {
                isLoadingBody = true
                bodyLoadError = null
                try {
                    // Таймаут 30 секунд на загрузку тела
                    val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                        mailRepo.loadEmailBody(emailId)
                    }
                    when (result) {
                        is EasResult.Success -> { /* тело обновится через Flow */ }
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
                    bodyLoadError = e.message
                } finally {
                    isLoadingBody = false
                }
            }
        }
    }
    
    // Состояние меню
    var showMoreMenu by remember { mutableStateOf(false) }
    
    // Диалог подтверждения удаления
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    
    // Диалог отчёта о прочтении (MDN)
    var showMdnDialog by remember { mutableStateOf(false) }
    var isSendingMdn by remember { mutableStateOf(false) }
    
    // Показываем диалог MDN если есть запрос и ещё не отправлен
    LaunchedEffect(email?.mdnRequestedBy, email?.mdnSent) {
        val e = email ?: return@LaunchedEffect
        if (!e.mdnRequestedBy.isNullOrBlank() && !e.mdnSent && !showMdnDialog) {
            showMdnDialog = true
        }
    }
    
    // Диалог MDN
    if (showMdnDialog && email != null && !email?.mdnRequestedBy.isNullOrBlank()) {
        val mdnEmail = email!!
        val readReceiptSentText = Strings.readReceiptSent
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
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
                TextButton(
                    onClick = { 
                        showMdnDialog = false
                        scope.launch { mailRepo.markMdnSent(emailId) }
                    },
                    enabled = !isSendingMdn
                ) {
                    Text(Strings.no)
                }
            }
        )
    }
    
    // Диалог подтверждения удаления
    if (showDeleteDialog) {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteEmail) },
            text = { Text(Strings.emailWillBeMovedToTrash) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            com.iwo.mailclient.util.SoundPlayer.playDeleteSound(context)
                            val result = withContext(Dispatchers.IO) {
                                mailRepo.moveToTrash(listOf(emailId))
                            }
                            isDeleting = false
                            showDeleteDialog = false
                            when (result) {
                                is EasResult.Success -> {
                                    val message = if (result.data > 0) 
                                        NotificationStrings.getMovedToTrash(isRussian) 
                                        else NotificationStrings.getDeletedPermanently(isRussian)
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
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.yes, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(Strings.no)
                }
            }
        )
    }
    
    // Диалог выбора папки для перемещения
    if (showMoveDialog) {
        val currentFolderId = email?.folderId
        // Системные типы папок Exchange которые НЕ для писем:
        // 7=Tasks, 8=Calendar, 9=Contacts, 10=Notes, 14=Journal, 15=RecipientInfo, 19=UserCreatedMail (но это для писем)
        // Типы для писем: 1=UserCreated, 2=Inbox, 3=Drafts, 4=DeletedItems, 5=SentItems, 6=Outbox, 12=Mail
        val nonMailFolderTypes = listOf(7, 8, 9, 10, 13, 14, 15, 17, 18) // Задачи, Календарь, Контакты, Заметки и др.
        val availableFolders = folders.filter { folder ->
            folder.id != currentFolderId &&
            folder.type !in nonMailFolderTypes
        }
        
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
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
                    Column(
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        availableFolders.forEach { folder ->
                            ListItem(
                                headlineContent = { Text(folder.displayName) },
                                leadingContent = { 
                                    Icon(AppIcons.Folder, null) 
                                },
                                modifier = Modifier.clickable {
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
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text(Strings.cancel)
                }
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
                    // Кнопка перемещения или восстановления
                    if (isInTrash) {
                        // В корзине - показываем кнопку Восстановить
                        IconButton(
                            onClick = {
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
                            enabled = !isRestoring
                        ) {
                            if (isRestoring) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Icon(AppIcons.Restore, Strings.restore, tint = Color.White)
                            }
                        }
                    } else {
                        // Не в корзине - показываем кнопку Переместить
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(AppIcons.DriveFileMove, Strings.moveTo, tint = Color.White)
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(AppIcons.Delete, Strings.delete, tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(AppIcons.MoreVert, Strings.more, tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(Strings.forward) },
                                onClick = { 
                                    showMoreMenu = false
                                    onForwardClick()
                                },
                                leadingIcon = { Icon(AppIcons.Forward, null) }
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.markUnread) },
                                onClick = { 
                                    showMoreMenu = false
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
                            // Избранное только если НЕ в корзине
                            if (!isInTrash) {
                                DropdownMenuItem(
                                    text = { Text(if (email?.flagged == true) Strings.removeFromFavorites else Strings.addToFavorites) },
                                    onClick = { 
                                        showMoreMenu = false
                                        scope.launch { mailRepo.toggleFlag(emailId) }
                                    },
                                    leadingIcon = { 
                                        Icon(
                                            if (email?.flagged == true) AppIcons.Star else AppIcons.StarOutline, 
                                            null
                                        ) 
                                    }
                                )
                            }
                            // Переместить только если НЕ в корзине
                            if (!isInTrash) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(Strings.moveTo) },
                                    onClick = { 
                                        showMoreMenu = false
                                        showMoveDialog = true
                                    },
                                    leadingIcon = { Icon(AppIcons.DriveFileMove, null) }
                                )
                            }
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
            com.iwo.mailclient.ui.theme.AnimatedFab(
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
            // email гарантированно не null в этом блоке
            val currentEmail = email!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
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
                    val accountColor = activeAccount?.color ?: 0xFF1976D2.toInt()
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(accountColor)),
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
                        // Показываем email если это нормальный адрес
                        if (displayEmail.isNotEmpty() && displayEmail.contains("@")) {
                            Text(
                                text = if (showName) "<$displayEmail>" else displayEmail,
                                style = if (showName) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                                fontWeight = if (showName) FontWeight.Normal else FontWeight.SemiBold,
                                color = if (showName) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    Text(
                        text = formatFullDate(currentEmail.dateReceived),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Звёздочка избранного только если НЕ в корзине
                    if (!isInTrash) {
                        IconButton(onClick = {
                            scope.launch { mailRepo.toggleFlag(emailId) }
                        }) {
                            Icon(
                                imageVector = if (currentEmail.flagged) AppIcons.Star else AppIcons.StarOutline,
                                contentDescription = Strings.favorites,
                                tint = if (currentEmail.flagged) MaterialTheme.colorScheme.primary 
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
                            // Кому - кликабельный
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${Strings.to}: ",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val toEmail = extractEmailAddress(currentEmail.to)
                                Text(
                                    text = formatRecipients(currentEmail.to),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (toEmail.contains("@")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = if (toEmail.contains("@")) Modifier.clickable { onComposeToEmail(toEmail) } else Modifier
                                )
                            }
                            if (currentEmail.cc.isNotEmpty()) {
                                Text(
                                    text = "${Strings.cc}: ${formatRecipients(currentEmail.cc)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                                                .replace(Regex("<[^>]+>"), "") // Убираем HTML теги
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
                                            Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_LONG).show()
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
                                            Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_LONG).show()
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
                            } catch (_: Exception) { }
                            isLoadingIcal = false
                        }
                    }
                    
                    // Используем данные из тела или из загруженного вложения
                    val icalData = when {
                        bodyHasVEvent -> currentEmail.body
                        loadedIcalData != null -> loadedIcalData!!
                        else -> ""
                    }
                    
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
                                    val dtStart = ICAL_DTSTART_REGEX.find(icalData)?.groupValues?.get(1)
                                    val dtEnd = ICAL_DTEND_REGEX.find(icalData)?.groupValues?.get(1)
                                    val meetingLocation = ICAL_LOCATION_REGEX.find(icalData)?.groupValues?.get(1)
                                        ?.replace("\\n", " ")?.replace("\\,", ",")?.trim() ?: ""
                                    val meetingDescription = ICAL_DESCRIPTION_REGEX.find(icalData)?.groupValues?.get(1)
                                        ?.replace("\\n", "\n")?.replace("\\,", ",")?.trim() ?: ""
                                    val meetingStartTime = parseICalDate(dtStart) ?: System.currentTimeMillis()
                                    val meetingEndTime = parseICalDate(dtEnd) ?: (meetingStartTime + 60 * 60 * 1000)
                                    
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
                                                        Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_LONG).show()
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
                                                        Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_LONG).show()
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
                                                        Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_LONG).show()
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
                                    // Если уже скачано - открываем
                                    if (attachment.downloaded && attachment.localPath != null) {
                                        openFile(context, File(attachment.localPath))
                                        return@launch
                                    }
                                    
                                    // Скачиваем через EasClient (умеет делать Provision при 449)
                                    downloadingId = attachment.id
                                    val account = accountRepo.getActiveAccountSync()
                                    if (account != null) {
                                        val password = accountRepo.getPassword(account.id)
                                        if (password != null) {
                                            val easClient = EasClient(
                                                serverUrl = account.serverUrl,
                                                username = account.username,
                                                password = password,
                                                domain = account.domain,
                                                acceptAllCerts = account.acceptAllCerts,
                                                port = account.incomingPort,
                                                useHttps = account.useSSL,
                                                deviceIdSuffix = account.email, // ВАЖНО: тот же deviceId что и при синхронизации!
                                                certificatePath = account.certificatePath
                                            )
                                            
                                            // Устанавливаем policyKey если есть
                                            account.policyKey?.let { easClient.setPolicyKey(it) }
                                            
                                            when (val result = easClient.downloadAttachment(attachment.fileReference)) {
                                                is EasResult.Success -> {
                                                    // Сохраняем файл
                                                    val file = withContext(Dispatchers.IO) {
                                                        val attachmentsDir = File(context.filesDir, "attachments")
                                                        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                                                        
                                                        val safeFileName = attachment.displayName.replace(SAFE_FILENAME_REGEX, "_")
                                                        val file = File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")
                                                        FileOutputStream(file).use { it.write(result.data) }
                                                        
                                                        // Обновляем policyKey если изменился
                                                        easClient.policyKey?.let { newKey ->
                                                            if (newKey != account.policyKey) {
                                                                accountRepo.savePolicyKey(account.id, newKey)
                                                            }
                                                        }
                                                        file
                                                    }
                                                    mailRepo.updateAttachmentPath(attachment.id, file.absolutePath)
                                                    openFile(context, file)
                                                }
                                                is EasResult.Error -> {
                                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                } catch (e: Exception) {
                                    val errorMsg = "${if (isRussian) "Ошибка" else "Error"}: ${e.message}"
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
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
                    // Убираем разделители Exchange из тела (включая частичные остатки типа ~*)
                    val cleanedBody = currentEmail.body
                        .replace(Regex("\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*?"), "")
                        .replace(Regex("~\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*?"), "")
                        .replace(Regex("~\\*+"), "") // Убираем остатки типа ~* или ~**
                        .replace(Regex("\\*~+"), "") // Убираем остатки типа *~ или *~~
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
                                            // Передаём все touch события родителю
                                            setOnTouchListener { v, _ ->
                                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                                false
                                            }
                                            // Измеряем высоту контента после загрузки
                                            webViewClient = object : android.webkit.WebViewClient() {
                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    view?.postDelayed({
                                                        val contentHeight = (view.contentHeight * view.scale).toInt()
                                                        if (contentHeight > 0) {
                                                            webViewHeight = contentHeight + 32 // +padding
                                                        }
                                                    }, 100)
                                                }
                                            }
                                        }
                                    },
                                    update = { webView ->
                                        // Заменяем cid: ссылки на data URL
                                        var processedBody = bodyText
                                        for ((cid, dataUrl) in inlineImages) {
                                            processedBody = processedBody
                                                .replace("cid:$cid", dataUrl)
                                                .replace("cid:${cid.removePrefix("<").removeSuffix(">")}", dataUrl)
                                        }
                                        
                                        // Оборачиваем в HTML с адаптивным контентом
                                        val styledHtml = """
                                            <html>
                                            <head>
                                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                                                <style>
                                                    body { 
                                                        background-color: white !important; 
                                                        color: black !important;
                                                        margin: 8px;
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
                                                </style>
                                            </head>
                                            <body>$processedBody</body>
                                            </html>
                                        """.trimIndent()
                                        // Используем baseURL для загрузки внешних ресурсов
                                        webView.loadDataWithBaseURL("https://localhost/", styledHtml, "text/html", "UTF-8", null)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 200.dp, max = 2000.dp)
                                        .padding(8.dp)
                                )
                        }
                    }
                    } else {
                        // Plain text
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } // end else (body loaded)
                
                Spacer(modifier = Modifier.height(80.dp)) // Для FAB
            }
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: List<AttachmentEntity>,
    downloadingId: Long?,
    onAttachmentClick: (AttachmentEntity) -> Unit
) {
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onAttachmentClick(attachment) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AttachmentManager.getFileIcon(attachment.displayName),
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = formatFileSize(attachment.estimatedSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    when {
                        downloadingId == attachment.id -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        attachment.downloaded -> {
                            Icon(
                                AppIcons.CheckCircle,
                                Strings.downloaded,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        else -> {
                            Icon(
                                AppIcons.Download,
                                Strings.download,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openFile(context: android.content.Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Используем NotificationStrings для не-Composable контекста
        val isRussian = java.util.Locale.getDefault().language == "ru"
        val message = if (isRussian) "Нет приложения для открытия файла" else "No app to open file"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun formatFullDate(timestamp: Long): String {
    return java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
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
 * Парсит дату из iCalendar формата
 * Поддерживает форматы: 20260115T100000Z, 20260115T100000, 20260115
 */
private fun parseICalDate(dateStr: String?): Long? {
    if (dateStr.isNullOrBlank()) return null
    
    return try {
        val isUtc = dateStr.endsWith("Z")
        val cleanDate = dateStr.removeSuffix("Z")
        
        val format = when (cleanDate.length) {
            8 -> java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            15 -> java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            else -> return null
        }
        
        if (isUtc) {
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        
        format.parse(cleanDate)?.time
    } catch (_: Exception) {
        null
    }
}

