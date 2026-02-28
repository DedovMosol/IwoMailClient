package com.dedovmosol.iwomail.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран пользовательских папок.
 * Показывает список user-created папок (type 1 и 12 по EAS спецификации)
 * с возможностью создания, переименования, удаления и навигации к письмам.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UserFoldersScreen(
    onBackClick: () -> Unit,
    onFolderClick: (String) -> Unit,
    onComposeClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncScope = com.dedovmosol.iwomail.ui.components.rememberSyncScope()
    
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    // Все папки из Room Flow → фильтруем пользовательские
    val allFolders by remember(accountId) { mailRepo.getFolders(accountId) }
        .collectAsState(initial = emptyList())
    val userFolders = remember(allFolders) {
        allFolders.filter { it.type in listOf(1, FolderType.USER_CREATED) }
            .sortedBy { it.displayName }
    }
    
    // Состояния
    var isSyncing by remember { mutableStateOf(false) }
    
    // Диалог создания папки
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    
    // Контекстное меню (long press)
    var folderForMenu by remember { mutableStateOf<FolderEntity?>(null) }
    
    // Диалог переименования — ID сохраняется при повороте
    var folderToRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val folderToRename = folderToRenameId?.let { id -> userFolders.find { it.id == id } }
    var renameNewName by rememberSaveable { mutableStateOf("") }
    
    // Диалог удаления — ID сохраняется при повороте
    var folderToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val folderToDelete = folderToDeleteId?.let { id -> userFolders.find { it.id == id } }
    
    // Кэш строк для use в корутинах (вне @Composable)
    val foldersSyncedText = Strings.foldersSynced
    val folderCreatedText = Strings.folderCreated
    val folderDeletedText = Strings.folderDeleted
    val folderRenamedText = Strings.folderRenamed
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    
    // Автоматическая синхронизация при первом открытии если папок нет
    LaunchedEffect(accountId, userFolders.isEmpty()) {
        if (accountId > 0 && userFolders.isEmpty() && !isSyncing) {
            isSyncing = true
            syncScope.launch {
                withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                isSyncing = false
            }
        }
    }
    
    val colorTheme = LocalColorTheme.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.userFolders, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                actions = {
                    // Кнопка синхронизации
                    IconButton(
                        onClick = {
                            val accId = accountId
                            if (accId > 0) {
                                syncScope.launch {
                                    isSyncing = true
                                    val result = withContext(Dispatchers.IO) {
                                        mailRepo.syncFolders(accId)
                                    }
                                    isSyncing = false
                                    when (result) {
                                        is EasResult.Success -> {
                                            Toast.makeText(context, foldersSyncedText, Toast.LENGTH_SHORT).show()
                                        }
                                        is EasResult.Error -> {
                                            val msg = NotificationStrings.localizeError(result.message, isRussian)
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(AppIcons.Refresh, Strings.syncFolders, tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorTheme.gradientStart),
            )
        },
        floatingActionButton = {
            com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                onClick = onComposeClick,
                containerColor = colorTheme.gradientStart
            ) {
                Icon(AppIcons.Edit, Strings.compose, tint = Color.White)
            }
        },
        containerColor = Color.White
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
        ) {
            if (userFolders.isEmpty() && isSyncing) {
                // Загрузка — индикатор по центру
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (userFolders.isEmpty()) {
                // Пустой экран
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            AppIcons.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            Strings.userFoldersEmpty,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(userFolders, key = { it.id }) { folder ->
                            UserFolderItem(
                                folder = folder,
                                onClick = { onFolderClick(folder.id) },
                                onLongClick = { folderForMenu = folder }
                            )
                        }
                        
                        // Отступ для FAB
                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                    LazyColumnScrollbar(listState)
                }
            }
            
            // Кнопка создать папку — слева внизу
            SmallFloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp),
                containerColor = colorTheme.gradientStart
            ) {
                Icon(AppIcons.CreateNewFolder, Strings.createFolder, tint = Color.White)
            }
            
            // Индикатор синхронизации
            if (isSyncing && userFolders.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = colorTheme.gradientStart,
                    trackColor = colorTheme.gradientStart.copy(alpha = 0.2f)
                )
            }
        }
    }
    
    // === Диалоги ===
    
    // Диалог создания папки
    if (showCreateDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                newFolderName = ""
            },
            title = { Text(Strings.createFolder) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(Strings.folderName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        val accId = accountId
                        if (accId > 0 && newFolderName.isNotBlank() && !isCreatingFolder) {
                            isCreatingFolder = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.createFolder(accId, newFolderName)
                                }
                                isCreatingFolder = false
                                when (result) {
                                    is EasResult.Success -> {
                                        Toast.makeText(context, folderCreatedText, Toast.LENGTH_SHORT).show()
                                    }
                                    is EasResult.Error -> {
                                        val msg = NotificationStrings.localizeError(result.message, isRussian)
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                                showCreateDialog = false
                                newFolderName = ""
                            }
                        }
                    },
                    enabled = newFolderName.isNotBlank() && !isCreatingFolder,
                    isLoading = isCreatingFolder,
                    text = Strings.save
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showCreateDialog = false
                        newFolderName = ""
                    },
                    text = Strings.cancel
                )
            }
        )
    }
    
    // Контекстное меню (переименовать / удалить)
    folderForMenu?.let { folder ->
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderForMenu = null },
            title = {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            confirmButton = {
                // Удалить справа (красная обводка)
                OutlinedButton(
                    onClick = {
                        folderForMenu = null
                        folderToDeleteId = folder.id
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                    ),
                    border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
                ) {
                    Icon(
                        AppIcons.Delete,
                        contentDescription = Strings.delete,
                        modifier = Modifier.size(20.dp),
                        tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                    )
                }
            },
            dismissButton = {
                // Переименовать слева (цвет из темы)
                OutlinedButton(
                    onClick = {
                        folderForMenu = null
                        renameNewName = folder.displayName
                        folderToRenameId = folder.id
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = LocalColorTheme.current.gradientStart
                    )
                ) {
                    Icon(
                        AppIcons.Edit,
                        contentDescription = Strings.rename,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )
    }
    
    // Диалог переименования
    folderToRename?.let { folder ->
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = {
                folderToRenameId = null
                renameNewName = ""
            },
            title = { Text(Strings.renameFolder) },
            text = {
                OutlinedTextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    label = { Text(Strings.newName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        val accId = accountId
                        val folderId = folder.id
                        val newName = renameNewName
                        folderToRenameId = null
                        renameNewName = ""
                        if (accId > 0) {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.renameFolder(accId, folderId, newName)
                                }
                                when (result) {
                                    is EasResult.Success -> {
                                        Toast.makeText(context, folderRenamedText, Toast.LENGTH_SHORT).show()
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(accId) }
                                    }
                                    is EasResult.Error -> {
                                        val msg = NotificationStrings.localizeError(result.message, isRussian)
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    enabled = renameNewName.isNotBlank(),
                    text = Strings.save
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        folderToRenameId = null
                        renameNewName = ""
                    },
                    text = Strings.cancel
                )
            }
        )
    }
    
    // Диалог удаления
    folderToDelete?.let { folder ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { folderToDeleteId = null },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteFolder) },
            text = { Text(Strings.deleteFolderConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        val accId = accountId
                        val folderId = folder.id
                        folderToDeleteId = null
                        if (accId > 0) {
                            scope.launch {
                                com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.deleteFolder(accId, folderId)
                                }
                                when (result) {
                                    is EasResult.Success -> {
                                        Toast.makeText(context, folderDeletedText, Toast.LENGTH_SHORT).show()
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(accId) }
                                    }
                                    is EasResult.Error -> {
                                        val msg = NotificationStrings.localizeError(result.message, isRussian)
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { folderToDeleteId = null },
                    text = Strings.no
                )
            }
        )
    }
}

/**
 * Элемент списка пользовательской папки
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserFolderItem(
    folder: FolderEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colorTheme = LocalColorTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Иконка папки — контрастный цвет темы
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(com.dedovmosol.iwomail.ui.theme.AppColors.folder.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Folder,
                    contentDescription = null,
                    tint = com.dedovmosol.iwomail.ui.theme.AppColors.folder,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(14.dp))
            
            // Название папки
            Text(
                text = folder.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (folder.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Счётчики справа: непрочитанные (акцентный) + общий (серый)
            if (folder.unreadCount > 0) {
                // Бейдж непрочитанных — яркий акцентный
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colorTheme.gradientStart,
                    shadowElevation = 2.dp
                ) {
                    Text(
                        text = folder.unreadCount.toString(),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 26.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                // Общее кол-во — рядом в сером чипе (если отличается от непрочитанных)
                if (folder.totalCount > folder.unreadCount) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = folder.totalCount.toString(),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 26.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (folder.totalCount > 0) {
                // Только общее кол-во — серый чип
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = folder.totalCount.toString(),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 26.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            
            // Стрелка навигации
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
