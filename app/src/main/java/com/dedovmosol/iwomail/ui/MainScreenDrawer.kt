package com.dedovmosol.iwomail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.ui.theme.AppColors
import com.dedovmosol.iwomail.ui.theme.AppIcons

/**
 * Содержимое Navigation Drawer
 */
@Composable
fun DrawerContent(
    accounts: List<AccountEntity>,
    activeAccount: AccountEntity?,
    folders: List<FolderEntity>,
    flaggedCount: Int,
    notesCount: Int = 0,
    eventsCount: Int = 0,
    tasksCount: Int = 0,
    showAccountPicker: Boolean,
    onToggleAccountPicker: () -> Unit,
    onAccountSettingsClick: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onFolderSelected: (FolderEntity) -> Unit,
    onFavoritesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onContactsClick: () -> Unit = {},
    onNotesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onTasksClick: () -> Unit = {},
    onCreateFolder: () -> Unit = {},
    onFolderLongClick: (FolderEntity) -> Unit = {},
    onAboutClick: () -> Unit = {}
) {
    LazyColumn {
        // Шапка с аккаунтом
        item {
            DrawerHeader(
                account = activeAccount,
                showPicker = showAccountPicker,
                onToggle = onToggleAccountPicker,
                onAccountClick = {
                    activeAccount?.let { onAccountSettingsClick(it.id) }
                }
            )
        }
        
        // Список аккаунтов
        if (showAccountPicker) {
            items(accounts, key = { it.id }) { account ->
                AccountItem(
                    account = account,
                    isActive = account.id == activeAccount?.id,
                    onClick = { onAccountSettingsClick(account.id) }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.addAccount) },
                    leadingContent = { Icon(AppIcons.Add, null) },
                    modifier = Modifier.clickable(onClick = onAddAccount)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        
        // Основные папки (Входящие, Черновики, Удалённые, Отправленные, Исходящие, Спам)
        val mainFolderTypes = listOf(2, 3, 4, 5, 6, 11)
        val mainFolders = folders.filter { it.type in mainFolderTypes }
            .sortedBy { mainFolderTypes.indexOf(it.type) }
        
        items(mainFolders, key = { it.id }) { folder ->
            FolderItem(
                folder = folder,
                onClick = { onFolderSelected(folder) }
            )
        }
        
        // Избранные
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onFavoritesClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Star, null, tint = AppColors.favorites)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.favorites,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (flaggedCount > 0) {
                        Text(
                            text = flaggedCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Остальные папки (скрываем Contacts, Calendar, Notes, Tasks - они показаны отдельно)
        val hiddenFolderTypes = listOf(7, 8, 9, 10) // Tasks, Calendar, Contacts, Notes
        val otherFolders = folders.filter { folder ->
            folder.type !in mainFolderTypes && 
            folder.type !in hiddenFolderTypes
        }
        
        // Контакты
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onContactsClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.People, null, tint = AppColors.contacts)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.contacts,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        
        // Заметки
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onNotesClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.StickyNote, null, tint = AppColors.notes)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.notes,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (notesCount > 0) {
                        Text(
                            text = notesCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Календарь
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onCalendarClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.CalendarMonth, null, tint = AppColors.calendar)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.calendar,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (eventsCount > 0) {
                        Text(
                            text = eventsCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Задачи
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onTasksClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.CheckCircle, null, tint = AppColors.tasks)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.tasks,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (tasksCount > 0) {
                        Text(
                            text = tasksCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Сворачиваемая секция "Папки" для пользовательских папок
        if (otherFolders.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            item(key = "folders_header") {
                var foldersExpanded by rememberSaveable { mutableStateOf(false) }
                
                Column {
                // Заголовок секции с иконкой раскрытия
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clickable { foldersExpanded = !foldersExpanded },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.Folder,
                            null,
                            tint = AppColors.folder
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = Strings.folders,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = otherFolders.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            if (foldersExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Анимированный список папок
                AnimatedVisibility(
                    visible = foldersExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        otherFolders.forEach { folder ->
                            FolderItem(
                                folder = folder,
                                onClick = { onFolderSelected(folder) },
                                onLongClick = { onFolderLongClick(folder) }
                            )
                        }
                    }
                }
                } // Column
            }
        }
        
        // Создать папку
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(Strings.createFolder) },
                leadingContent = { Icon(AppIcons.CreateNewFolder, null, tint = AppColors.createFolder) },
                modifier = Modifier.clickable(onClick = onCreateFolder)
            )
        }
        
        // Настройки
        item {
            ListItem(
                headlineContent = { Text(Strings.settings) },
                leadingContent = { Icon(AppIcons.Settings, null, tint = AppColors.settings) },
                modifier = Modifier.clickable(onClick = onSettingsClick)
            )
        }
        
        // О приложении
        item {
            ListItem(
                headlineContent = { Text(Strings.aboutApp) },
                leadingContent = { Icon(AppIcons.Info, null, tint = AppColors.settings) },
                modifier = Modifier.clickable(onClick = onAboutClick)
            )
        }
    }
}

/**
 * Шапка Drawer с информацией об аккаунте
 */
@Composable
fun DrawerHeader(
    account: AccountEntity?,
    showPicker: Boolean,
    onToggle: () -> Unit,
    onAccountClick: () -> Unit
) {
    val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
    // PERF: remember — Color() и Brush не пересоздаются при каждой рекомпозиции DrawerHeader
    val accountColor = remember(account?.color) {
        try { Color(account?.color ?: 0xFF1976D2.toInt()) } catch (_: Exception) { Color(0xFF1976D2) }
    }
    val drawerBrush = remember(colorTheme.gradientStart, colorTheme.gradientEnd) {
        Brush.linearGradient(colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush = drawerBrush)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .padding(top = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(accountColor)
                    .clickable(onClick = onAccountClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account?.displayName?.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onAccountClick)
                ) {
                    Text(
                        text = account?.displayName ?: Strings.noAccount,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = account?.email ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (showPicker) AppIcons.KeyboardArrowUp
                        else AppIcons.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Элемент списка аккаунтов
 */
@Composable
fun AccountItem(
    account: AccountEntity,
    isActive: Boolean,
    onClick: () -> Unit
) {
    // PERF: remember — Color() не пересоздаётся при каждой рекомпозиции AccountItem
    val accountColor = remember(account.color) {
        try { Color(account.color) } catch (_: Exception) { null }
    } ?: MaterialTheme.colorScheme.primary
    
    ListItem(
        headlineContent = { Text(account.displayName) },
        supportingContent = { Text(account.email) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accountColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account.displayName.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        trailingContent = {
            if (isActive) {
                Icon(AppIcons.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * Элемент папки в Drawer
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderItem(
    folder: FolderEntity,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val icon = when (folder.type) {
        2 -> AppIcons.Inbox
        3 -> AppIcons.Drafts
        4 -> AppIcons.Delete
        5 -> AppIcons.Send
        6 -> AppIcons.Outbox
        7 -> AppIcons.Task
        8 -> AppIcons.CalendarMonth
        9 -> AppIcons.Contacts
        11 -> AppIcons.Report // Junk/Spam
        else -> AppIcons.Folder
    }
    
    // Контрастные цвета иконок (из единого источника AppColors)
    val iconTint = AppColors.folderTint(folder.type)
    
    // Локализованное название папки
    val displayName = Strings.getFolderName(folder.type, folder.displayName)
    
    // Системные папки нельзя удалять (долгий клик)
    val isSystemFolder = folder.type in listOf(2, 3, 4, 5, 6, 11)
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!isSystemFolder && onLongClick != null) onLongClick else null
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (folder.totalCount > 0) {
                Text(
                    text = folder.totalCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
