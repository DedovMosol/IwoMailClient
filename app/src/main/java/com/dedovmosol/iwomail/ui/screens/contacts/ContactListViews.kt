package com.dedovmosol.iwomail.ui.screens.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactGroupEntity
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier
import com.dedovmosol.iwomail.ui.theme.AppColors
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.utils.getAvatarColor

@Composable
internal fun PersonalContactsList(
    groups: List<ContactGroupEntity>,
    favoriteCount: Int = 0,
    groupCounts: Map<String, Int> = emptyMap(),
    selectedGroupId: String?,
    onGroupSelected: (String?) -> Unit,
    onGroupRename: (ContactGroupEntity) -> Unit,
    onGroupDelete: (ContactGroupEntity) -> Unit,
    groupedContacts: Map<Char, List<ContactEntity>>,
    onContactClick: (ContactEntity) -> Unit,
    onContactLongClick: (ContactEntity) -> Unit = {},
    onContactMoveToGroup: (ContactEntity) -> Unit,
    onContactEdit: (ContactEntity) -> Unit,
    onContactDelete: (ContactEntity) -> Unit,
    onContactToggleFavorite: (ContactEntity) -> Unit = {},
    isSelectionMode: Boolean = false,
    selectedContactIds: Set<String> = emptySet(),
    onDragSelect: (Set<String>) -> Unit = {}
) {
    var expandedGroupMenu by remember { mutableStateOf<String?>(null) }

    // Map для быстрого поиска группы по ID
    val groupsMap = remember(groups) { groups.associateBy { it.id } }

    val contactsListState = rememberLazyListState()

    // Drag selection
    val contactKeys = remember(groupedContacts) {
        groupedContacts.values.flatten().map { it.id }
    }
    val dragModifier = rememberDragSelectModifier(
        listState = contactsListState,
        itemKeys = contactKeys,
        selectedIds = selectedContactIds,
        onSelectionChange = onDragSelect
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = contactsListState, modifier = dragModifier.fillMaxSize()) {
            // Фильтр по группам (горизонтальный скролл чипов)
            item {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Все контакты
                    item {
                        FilterChip(
                            selected = selectedGroupId == null,
                            onClick = { onGroupSelected(null) },
                            label = { Text(Strings.filterAll) },
                            leadingIcon = if (selectedGroupId == null) {
                                { Icon(AppIcons.Check, null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                    // Избранные
                    item {
                        FilterChip(
                            selected = selectedGroupId == "favorites",
                            onClick = { onGroupSelected("favorites") },
                            label = { Text("${Strings.favoriteContacts} ($favoriteCount)") },
                            leadingIcon = {
                                Icon(
                                    AppIcons.Star,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = AppColors.favorites
                                )
                            }
                        )
                    }
                    // Группы
                    items(groups) { group ->
                        val groupColor = try { Color(group.color) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                        val count = groupCounts[group.id] ?: 0
                        Box {
                            FilterChip(
                                selected = selectedGroupId == group.id,
                                onClick = { onGroupSelected(group.id) },
                                label = { Text("${group.name} ($count)") },
                                leadingIcon = {
                                    Icon(
                                        AppIcons.Folder,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                        tint = groupColor
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { expandedGroupMenu = group.id },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(AppIcons.MoreVert, null, modifier = Modifier.size(14.dp))
                                    }
                                }
                            )
                            DropdownMenu(
                                expanded = expandedGroupMenu == group.id,
                                onDismissRequest = { expandedGroupMenu = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(Strings.rename) },
                                    onClick = {
                                        expandedGroupMenu = null
                                        onGroupRename(group)
                                    },
                                    leadingIcon = { Icon(AppIcons.Edit, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(Strings.delete, color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        expandedGroupMenu = null
                                        onGroupDelete(group)
                                    },
                                    leadingIcon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                    // Без группы
                    item {
                        val ungroupedCount = groupCounts["__ungrouped__"] ?: 0
                        FilterChip(
                            selected = selectedGroupId == "ungrouped",
                            onClick = { onGroupSelected("ungrouped") },
                            label = { Text("${Strings.withoutGroup} ($ungroupedCount)") },
                            leadingIcon = {
                                Icon(
                                    AppIcons.FolderOff,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Контакты
            if (groupedContacts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                AppIcons.People,
                                null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                Strings.noContacts,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                groupedContacts.forEach { (letter, contacts) ->
                    item {
                        Text(
                            text = letter.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(contacts, key = { it.id }) { contact ->
                        ContactItemWithGroup(
                            contact = contact,
                            group = contact.groupId?.let { groupsMap[it] },
                            onClick = { onContactClick(contact) },
                            onLongClick = { onContactLongClick(contact) },
                            onMoveToGroup = { onContactMoveToGroup(contact) },
                            onEdit = { onContactEdit(contact) },
                            onDelete = { onContactDelete(contact) },
                            onToggleFavorite = { onContactToggleFavorite(contact) },
                            isSelected = contact.id in selectedContactIds,
                            isSelectionMode = isSelectionMode
                        )
                    }
                }
            }
        }
        LazyColumnScrollbar(contactsListState)
    }
}

@Composable
internal fun OrganizationContactsList(
    contacts: List<ContactEntity>,
    isSyncing: Boolean,
    syncError: String?,
    title: String,
    emptySubtitle: String,
    onContactClick: (ContactEntity) -> Unit,
    onContactLongClick: (ContactEntity) -> Unit = {},
    onSyncClick: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedContactIds: Set<String> = emptySet(),
    onDragSelect: (Set<String>) -> Unit = {}
) {
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN

    // Группировка контактов по алфавиту
    val groupedContacts = remember(contacts) {
        contacts
            .sortedBy { it.displayName.lowercase() }
            .groupBy { it.displayName.firstOrNull()?.uppercaseChar() ?: '#' }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок с кнопкой синхронизации
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (contacts.isNotEmpty())
                        NotificationStrings.getContactsCount(contacts.size, isRussian)
                    else
                        emptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Кнопка синхронизации
            IconButton(
                onClick = onSyncClick,
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        AppIcons.Sync,
                        contentDescription = NotificationStrings.getSyncAction(isRussian),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Ошибка
        syncError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Контент
        when {
            isSyncing && contacts.isEmpty() -> {
                // Загрузка (ещё нет контактов)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            NotificationStrings.getLoadingContacts(isRussian),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            contacts.isEmpty() && syncError == null && !isSyncing -> {
                // Нет контактов
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            AppIcons.Business,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            NotificationStrings.getTapToLoadContacts(isRussian),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onSyncClick) {
                            Icon(AppIcons.Sync, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(NotificationStrings.getLoadAction(isRussian))
                        }
                    }
                }
            }
            else -> {
                // Показываем контакты
                val orgListState = rememberLazyListState()
                val orgContactKeys = remember(groupedContacts) {
                    groupedContacts.values.flatten().map { it.id }
                }
                val orgDragModifier = rememberDragSelectModifier(
                    listState = orgListState,
                    itemKeys = orgContactKeys,
                    selectedIds = selectedContactIds,
                    onSelectionChange = onDragSelect
                )
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    LazyColumn(
                        state = orgListState,
                        modifier = orgDragModifier.fillMaxSize()
                    ) {
                        groupedContacts.forEach { (letter, letterContacts) ->
                            item {
                                Text(
                                    text = letter.toString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(letterContacts, key = { it.id }) { contact ->
                                ExchangeContactItem(
                                    contact = contact,
                                    onClick = { onContactClick(contact) },
                                    onLongClick = { onContactLongClick(contact) },
                                    isSelected = contact.id in selectedContactIds,
                                    isSelectionMode = isSelectionMode
                                )
                            }
                        }
                    }
                    LazyColumnScrollbar(orgListState)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExchangeContactItem(
    contact: ContactEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false
) {
    val emailClean = cleanContactEmail(contact.email)
    ListItem(
        headlineContent = {
            Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (emailClean.isNotBlank()) {
                    Text(emailClean, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (contact.company.isNotBlank() || contact.department.isNotBlank()) {
                    Text(
                        listOfNotNull(
                            contact.company.takeIf { it.isNotBlank() },
                            contact.department.takeIf { it.isNotBlank() }
                        ).joinToString(" • "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingContent = {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(getAvatarColor(contact.displayName)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        modifier = Modifier
            .then(
                if (isSelectionMode) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
    )
}

@Composable
internal fun ContactItem(
    name: String,
    email: String,
    company: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (email.isNotBlank()) {
                    Text(email, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (company.isNotBlank()) {
                    Text(company, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(getAvatarColor(name)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContactItemWithGroup(
    contact: ContactEntity,
    group: ContactGroupEntity? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMoveToGroup: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    val emailClean = cleanContactEmail(contact.email)

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (contact.isFavorite) {
                    Icon(
                        AppIcons.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.favorites
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (emailClean.isNotBlank()) {
                    Text(emailClean, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Метка группы
                if (group != null) {
                    val groupColor = try { Color(group.color) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(groupColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = groupColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    // Метка "Без группы" для контактов без группы
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            AppIcons.FolderOff,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = Strings.withoutGroup,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (contact.company.isNotBlank()) {
                    Text(contact.company, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingContent = {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(getAvatarColor(contact.displayName)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        trailingContent = {
            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(AppIcons.MoreVert, null)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (contact.isFavorite) Strings.removeFromFavorites else Strings.addToFavorites) },
                            onClick = {
                                showMenu = false
                                onToggleFavorite()
                            },
                            leadingIcon = {
                                Icon(
                                    if (contact.isFavorite) AppIcons.Star else AppIcons.StarOutline,
                                    null,
                                    tint = AppColors.favorites
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.moveToGroup) },
                            onClick = {
                                showMenu = false
                                onMoveToGroup()
                            },
                            leadingIcon = { Icon(AppIcons.Folder, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.edit) },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                            leadingIcon = { Icon(AppIcons.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.delete, color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .then(
                if (isSelectionMode) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
    )
}
