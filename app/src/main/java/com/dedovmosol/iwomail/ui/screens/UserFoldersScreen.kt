package com.dedovmosol.iwomail.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.DragSelectionIndicator
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.util.SafeToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран пользовательских папок.
 * Показывает список user-created папок (type 1 и 12 по EAS спецификации)
 * с возможностью создания, переименования, удаления и навигации к письмам.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // Контекстное меню через явную кнопку действий
    var folderForMenu by remember(accountId) { mutableStateOf<FolderEntity?>(null) }

    // Диалог переименования — ID сохраняется при повороте
    var folderToRenameId by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val folderToRename = folderToRenameId?.let { id -> userFolders.find { it.id == id } }
    var renameNewName by rememberSaveable { mutableStateOf("") }

    // Диалог удаления — ID сохраняется при повороте
    var folderToDeleteId by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val folderToDelete = folderToDeleteId?.let { id -> userFolders.find { it.id == id } }

    // Batch-selection: выбранные ID сохраняются при повороте
    var selectedFolderIds by rememberSaveable(accountId,
        saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })
    ) { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedFolderIds.isNotEmpty()
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    // Прогресс batch-удаления: null = неактивно, (done, total)
    var batchDeleteProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val haptic = LocalHapticFeedback.current
    // Очищаем выбор, если пользовательский набор папок изменился (например, после sync)
    LaunchedEffect(userFolders) {
        val existing = userFolders.map { it.id }.toSet()
        if (selectedFolderIds.any { it !in existing }) {
            selectedFolderIds = selectedFolderIds intersect existing
        }
    }
    // Системная кнопка «назад»: сначала гасим selection
    BackHandler(enabled = isSelectionMode) { selectedFolderIds = emptySet() }

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
            if (isSelectionMode) {
                FoldersSelectionTopBar(
                    selectedCount = selectedFolderIds.size,
                    onClearSelection = { selectedFolderIds = emptySet() },
                    onDelete = { showBatchDeleteDialog = true }
                )
            } else {
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
                                                SafeToast.short(context, foldersSyncedText)
                                            }
                                            is EasResult.Error -> {
                                                val msg = NotificationStrings.localizeError(result.message, isRussian)
                                                SafeToast.long(context, msg)
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
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                    onClick = onComposeClick,
                    containerColor = colorTheme.gradientStart
                ) {
                    Icon(AppIcons.Edit, Strings.compose, tint = Color.White)
                }
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
                val folderKeys = remember(userFolders) { userFolders.map { it.id } }
                val dragModifier = rememberDragSelectModifier(
                    listState = listState,
                    itemKeys = folderKeys,
                    selectedIds = selectedFolderIds,
                    onSelectionChange = { newIds -> selectedFolderIds = newIds }
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = dragModifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isSelectionMode) {
                            item {
                                val allSelected = userFolders.isNotEmpty() &&
                                    selectedFolderIds.size == userFolders.size
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedFolderIds = if (allSelected) emptySet()
                                            else userFolders.map { it.id }.toSet()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = allSelected,
                                        onCheckedChange = {
                                            selectedFolderIds = if (allSelected) emptySet()
                                            else userFolders.map { it.id }.toSet()
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.selectAll)
                                }
                                HorizontalDivider()
                            }
                        }
                        items(userFolders, key = { it.id }) { folder ->
                            UserFolderItem(
                                folder = folder,
                                isSelected = folder.id in selectedFolderIds,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedFolderIds = if (folder.id in selectedFolderIds) {
                                            selectedFolderIds - folder.id
                                        } else {
                                            selectedFolderIds + folder.id
                                        }
                                    } else {
                                        onFolderClick(folder.id)
                                    }
                                },
                                onMenuClick = { folderForMenu = folder }
                            )
                        }

                        // Отступ для FAB
                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                    LazyColumnScrollbar(listState)
                }
            }

            // Кнопка создать папку — слева внизу (скрывается в selection mode)
            if (!isSelectionMode) {
                SmallFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 16.dp),
                    containerColor = colorTheme.gradientStart
                ) {
                    Icon(AppIcons.CreateNewFolder, Strings.createFolder, tint = Color.White)
                }
            }

            // Прогресс batch-удаления
            batchDeleteProgress?.let { (done, total) ->
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = colorTheme.gradientStart,
                    trackColor = colorTheme.gradientStart.copy(alpha = 0.2f)
                )
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
                                        SafeToast.short(context, folderCreatedText)
                                    }
                                    is EasResult.Error -> {
                                        val msg = NotificationStrings.localizeError(result.message, isRussian)
                                        SafeToast.long(context, msg)
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
                                        SafeToast.short(context, folderRenamedText)
                                    }
                                    is EasResult.Error -> {
                                        val msg = NotificationStrings.localizeError(result.message, isRussian)
                                        SafeToast.long(context, msg)
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
                                        SafeToast.short(context, folderDeletedText)
                                    }
                                    is EasResult.Error -> {
                                        val msg = NotificationStrings.localizeError(result.message, isRussian)
                                        SafeToast.long(context, msg)
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

    // Диалог массового удаления выбранных папок
    if (showBatchDeleteDialog) {
        val count = selectedFolderIds.size
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteFolders(count)) },
            text = { Text(Strings.deleteFoldersConfirm(count)) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        val accId = accountId
                        val idsToDelete = selectedFolderIds.toList()
                        showBatchDeleteDialog = false
                        if (accId > 0 && idsToDelete.isNotEmpty()) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            selectedFolderIds = emptySet()
                            syncScope.launch {
                                val total = idsToDelete.size
                                batchDeleteProgress = 0 to total
                                var processed = 0
                                var deleted = 0
                                var failedMsg: String? = null
                                // Последовательное удаление: FolderSyncService per-account Mutex
                                // всё равно сериализовал бы параллельные вызовы, но явный цикл
                                // даёт прогресс и корректный порядок SyncKey по MS-ASCMD 2.2.1.4.
                                for (fid in idsToDelete) {
                                    val res = withContext(Dispatchers.IO) {
                                        mailRepo.deleteFolder(accId, fid)
                                    }
                                    when (res) {
                                        is EasResult.Success -> deleted++
                                        is EasResult.Error -> {
                                            if (failedMsg == null) {
                                                failedMsg = NotificationStrings.localizeError(res.message, isRussian)
                                            }
                                        }
                                    }
                                    processed++
                                    batchDeleteProgress = processed to total
                                }
                                batchDeleteProgress = null
                                if (deleted > 0) {
                                    SafeToast.short(context, Strings.foldersDeleted(deleted, isRussian))
                                }
                                failedMsg?.let { SafeToast.long(context, it) }
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showBatchDeleteDialog = false },
                    text = Strings.no
                )
            }
        )
    }
}

/**
 * Топ-бар в режиме массового выделения папок
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersSelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDelete: () -> Unit
) {
    val colorTheme = LocalColorTheme.current
    TopAppBar(
        title = { Text("$selectedCount", color = Color.White) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(AppIcons.ArrowBack, Strings.cancelSelection, tint = Color.White)
            }
        },
        actions = {
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(AppIcons.Delete, Strings.delete, tint = Color.White)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = Modifier.background(
            Brush.horizontalGradient(
                colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
            )
        )
    )
}

/**
 * Элемент списка пользовательской папки
 */
@Composable
private fun UserFolderItem(
    folder: FolderEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    val colorTheme = LocalColorTheme.current
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                DragSelectionIndicator(selected = isSelected)
                Spacer(modifier = Modifier.width(12.dp))
            }
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

            Spacer(modifier = Modifier.width(8.dp))
            if (!isSelectionMode) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        AppIcons.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
