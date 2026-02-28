package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactGroupEntity
import com.dedovmosol.iwomail.data.database.ContactSource
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.utils.getAvatarColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Диалог выбора контактов для полей Кому/Копия/Скрытая
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerDialog(
    accountId: Long,
    database: MailDatabase,
    ownEmail: String = "",
    onDismiss: () -> Unit,
    onContactsSelected: (List<String>) -> Unit, // Список email адресов
    onGroupsSelected: (List<Triple<String, List<String>, Int>>) -> Unit = {} // Список (groupName, emails, color)
) {
    // Вкладки: 0=Личные, 1=Организация, 2=Группы
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Контакты
    var localContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var exchangeContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Группы
    var groups by remember { mutableStateOf<List<ContactGroupEntity>>(emptyList()) }
    var groupContactCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var groupEmailsMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    
    // Поиск
    var searchQuery by remember { mutableStateOf("") }
    
    // Выбранные контакты (email)
    var selectedEmails by remember { mutableStateOf(setOf<String>()) }
    // Выбранные группы (id)
    var selectedGroupIds by remember { mutableStateOf(setOf<String>()) }
    
    // Загрузка контактов и групп
    LaunchedEffect(accountId, ownEmail) {
        val ownEmailLower = ownEmail.lowercase()
        isLoading = true
        withContext(Dispatchers.IO) {
            localContacts = database.contactDao().searchContacts(accountId, "", 2000)
                .filter { it.source == ContactSource.LOCAL }
                .filter { ownEmailLower.isBlank() || it.email.lowercase() != ownEmailLower }
            exchangeContacts = database.contactDao().searchContacts(accountId, "", 2000)
                .filter { it.source == ContactSource.EXCHANGE }
                .filter { ownEmailLower.isBlank() || it.email.lowercase() != ownEmailLower }
            // Загружаем группы и считаем контакты в каждой
            groups = database.contactGroupDao().getGroupsByAccountList(accountId)
            val counts = mutableMapOf<String, Int>()
            val emailsMap = mutableMapOf<String, List<String>>()
            for (group in groups) {
                val contacts = database.contactDao().getContactsByGroupList(accountId, group.id)
                    .filter { it.email.isNotBlank() }
                    .filter { ownEmailLower.isBlank() || it.email.lowercase() != ownEmailLower }
                counts[group.id] = contacts.size
                emailsMap[group.id] = contacts.map { it.email }.distinct()
            }
            groupContactCounts = counts
            groupEmailsMap = emailsMap
        }
        isLoading = false
    }
    
    // Фильтрация по поиску
    val filteredContacts = remember(selectedTab, searchQuery, localContacts, exchangeContacts) {
        if (selectedTab == 2) return@remember emptyList() // Группы — отдельная логика
        val contacts = if (selectedTab == 0) localContacts else exchangeContacts
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true) ||
                contact.email.contains(searchQuery, ignoreCase = true) ||
                contact.company.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    val filteredGroups = remember(selectedTab, searchQuery, groups) {
        if (selectedTab != 2) return@remember emptyList()
        if (searchQuery.isBlank()) {
            groups
        } else {
            groups.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }
    
    val hasSelection = selectedEmails.isNotEmpty() || selectedGroupIds.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Заголовок
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Close, contentDescription = Strings.close)
                    }
                    Text(
                        text = Strings.selectContacts,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // Счётчик выбранных
                    val totalSelected = selectedEmails.size + selectedGroupIds.size
                    if (totalSelected > 0) {
                        Text(
                            text = "$totalSelected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    // Кнопка Готово
                    com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                        onClick = {
                            if (selectedEmails.isNotEmpty()) {
                                onContactsSelected(selectedEmails.toList())
                            }
                            if (selectedGroupIds.isNotEmpty()) {
                                val selectedGroups = selectedGroupIds.mapNotNull { gid ->
                                    val group = groups.find { it.id == gid }
                                    val emails = groupEmailsMap[gid]
                                    if (group != null && !emails.isNullOrEmpty()) {
                                        Triple(group.name, emails, group.color)
                                    } else null
                                }
                                if (selectedGroups.isNotEmpty()) {
                                    onGroupsSelected(selectedGroups)
                                }
                            }
                            onDismiss()
                        },
                        enabled = hasSelection,
                        text = Strings.done
                    )
                }
                
                // Поиск
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text(Strings.searchContacts) },
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(AppIcons.Clear, null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Вкладки
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    edgePadding = 8.dp
                ) {
                    // Вкладка "Личные"
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Person,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${Strings.personalContacts} (${localContacts.size})")
                            }
                        }
                    )
                    // Вкладка "Организация"
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Business,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${Strings.organization} (${exchangeContacts.size})")
                            }
                        }
                    )
                    // Вкладка "Группы"
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.People,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${Strings.contactGroups} (${groups.size})")
                            }
                        }
                    )
                }
                
                // Список контактов / групп
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (selectedTab == 2) {
                    // Вкладка групп
                    if (filteredGroups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    AppIcons.People,
                                    null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    Strings.noGroups,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        val groupListState = rememberLazyListState()
                        val groupKeys = remember(filteredGroups) { filteredGroups.map { it.id } }
                        val dragSelectGroupModifier = rememberDragSelectModifier(
                            listState = groupListState,
                            itemKeys = groupKeys,
                            selectedIds = selectedGroupIds,
                            onSelectionChange = { newIds -> selectedGroupIds = newIds }
                        )
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = groupListState,
                                modifier = dragSelectGroupModifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(filteredGroups, key = { it.id }) { group ->
                                    GroupPickerItem(
                                        group = group,
                                        contactCount = groupContactCounts[group.id] ?: 0,
                                        isSelected = selectedGroupIds.contains(group.id),
                                        onToggle = {
                                            selectedGroupIds = if (selectedGroupIds.contains(group.id)) {
                                                selectedGroupIds - group.id
                                            } else {
                                                selectedGroupIds + group.id
                                            }
                                        }
                                    )
                                }
                            }
                            LazyColumnScrollbar(groupListState)
                        }
                    }
                } else if (filteredContacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                AppIcons.PersonOff,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                Strings.noContacts,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val pickerListState = rememberLazyListState()
                    // Drag selection: long-press + drag выделяет диапазон контактов
                    val contactKeys = remember(filteredContacts) { filteredContacts.map { it.id } }
                    val contactIdToEmail = remember(filteredContacts) {
                        filteredContacts.associate { it.id to it.email }
                    }
                    val filteredEmailSet = remember(filteredContacts) {
                        filteredContacts.map { it.email }.toSet()
                    }
                    val selectedIdsForDrag = remember(selectedEmails, filteredContacts) {
                        filteredContacts.filter { it.email in selectedEmails }.map { it.id }.toSet()
                    }
                    val dragSelectModifier = rememberDragSelectModifier(
                        listState = pickerListState,
                        itemKeys = contactKeys,
                        selectedIds = selectedIdsForDrag,
                        onSelectionChange = { newIds ->
                            val newEmails = newIds.mapNotNull { contactIdToEmail[it] }.toSet()
                            val otherEmails = selectedEmails.filter { it !in filteredEmailSet }.toSet()
                            selectedEmails = otherEmails + newEmails
                        }
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = pickerListState,
                            modifier = dragSelectModifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(filteredContacts, key = { it.id }) { contact ->
                                ContactPickerItem(
                                    contact = contact,
                                    isSelected = selectedEmails.contains(contact.email),
                                    onToggle = {
                                        selectedEmails = if (selectedEmails.contains(contact.email)) {
                                            selectedEmails - contact.email
                                        } else {
                                            selectedEmails + contact.email
                                        }
                                    }
                                )
                            }
                        }
                        LazyColumnScrollbar(pickerListState)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactPickerItem(
    contact: ContactEntity,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Чекбокс
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Аватар
        val avatarColor = remember(contact.displayName) {
            getAvatarColor(contact.displayName)
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Информация
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = contact.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (contact.company.isNotBlank()) {
                Text(
                    text = contact.company,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Иконка источника
        Icon(
            if (contact.source == ContactSource.LOCAL) AppIcons.Person else AppIcons.Business,
            null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun GroupPickerItem(
    group: ContactGroupEntity,
    contactCount: Int,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val contactsLabel = if (LocalLanguage.current == AppLanguage.RUSSIAN) {
        when {
            contactCount % 100 in 11..19 -> "$contactCount контактов"
            contactCount % 10 == 1 -> "$contactCount контакт"
            contactCount % 10 in 2..4 -> "$contactCount контакта"
            else -> "$contactCount контактов"
        }
    } else {
        "$contactCount contact${if (contactCount != 1) "s" else ""}"
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(group.color)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.People,
                null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = contactsLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
