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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
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
    onAccountSelected: (AccountEntity) -> Unit,
    onAddAccount: () -> Unit,
    onFolderSelected: (FolderEntity) -> Unit,
    onFavoritesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onContactsClick: () -> Unit = {},
    onNotesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onTasksClick: () -> Unit = {},
    onCreateFolder: () -> Unit = {},
    onFolderLongClick: (FolderEntity) -> Unit = {}
) {
    LazyColumn {
        // Шапка с аккаунтом
        item {
            DrawerHeader(
                account = activeAccount,
                showPicker = showAccountPicker,
                onToggle = onToggleAccountPicker
            )
        }
        
        // Список аккаунтов
        if (showAccountPicker) {
            items(accounts, key = { it.id }) { account ->
                AccountItem(
                    account = account,
                    isActive = account.id == activeAccount?.id,
                    onClick = { onAccountSelected(account) }
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
                    Icon(AppIcons.Star, null, tint = Color(0xFFFFB300))
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
                    Icon(AppIcons.People, null, tint = MaterialTheme.colorScheme.primary)
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
                    Icon(AppIcons.StickyNote, null, tint = Color(0xFFFF9800))
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
                    Icon(AppIcons.CalendarMonth, null, tint = Color(0xFF4CAF50))
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
                    Icon(AppIcons.CheckCircle, null, tint = Color(0xFF9C27B0))
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
        
        // Остальные папки
        if (otherFolders.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            items(otherFolders, key = { it.id }) { folder ->
                FolderItem(
                    folder = folder,
                    onClick = { onFolderSelected(folder) },
                    onLongClick = { onFolderLongClick(folder) }
                )
            }
        }
        
        // Создать папку
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(Strings.createFolder) },
                leadingContent = { Icon(AppIcons.CreateNewFolder, null) },
                modifier = Modifier.clickable(onClick = onCreateFolder)
            )
        }
        
        // Настройки
        item {
            ListItem(
                headlineContent = { Text(Strings.settings) },
                leadingContent = { Icon(AppIcons.Settings, null) },
                modifier = Modifier.clickable(onClick = onSettingsClick)
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
    onToggle: () -> Unit
) {
    val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
    val accountColor = try {
        Color(account?.color ?: 0xFF1976D2.toInt())
    } catch (_: Exception) {
        Color(0xFF1976D2)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        colorTheme.gradientStart,
                        colorTheme.gradientEnd
                    )
                )
            )
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
                    .background(accountColor),
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
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
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

/**
 * Элемент списка аккаунтов
 */
@Composable
fun AccountItem(
    account: AccountEntity,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val accountColor = try {
        Color(account.color)
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }
    
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
    
    // Цвет иконки - корзина и спам имеют красный цвет
    val iconTint = when (folder.type) {
        4 -> MaterialTheme.colorScheme.error // Удалённые
        11 -> Color(0xFFE53935) // Спам - красный
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
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
