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
import com.dedovmosol.iwomail.data.database.ContactSource
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.ui.Strings
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
    onContactsSelected: (List<String>) -> Unit // Список email адресов
) {
    // Вкладки
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Контакты
    var localContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var exchangeContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Поиск
    var searchQuery by remember { mutableStateOf("") }
    
    // Выбранные контакты (email)
    var selectedEmails by remember { mutableStateOf(setOf<String>()) }
    
    // Загрузка контактов
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
        }
        isLoading = false
    }
    
    // Фильтрация по поиску
    val filteredContacts = remember(selectedTab, searchQuery, localContacts, exchangeContacts) {
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
                    if (selectedEmails.isNotEmpty()) {
                        Text(
                            text = "${selectedEmails.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    // Кнопка Готово
                    Button(
                        onClick = {
                            onContactsSelected(selectedEmails.toList())
                            onDismiss()
                        },
                        enabled = selectedEmails.isNotEmpty()
                    ) {
                        Text(Strings.done)
                    }
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
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface
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
                }
                
                // Список контактов
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = pickerListState,
                            modifier = Modifier.fillMaxSize(),
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
            getAvatarColorForContact(contact.displayName)
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

// Цвета для аватаров как в Gmail — стабильные для каждой буквы (32 цвета)
private val avatarColors = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFFC2185B),
    Color(0xFF8E24AA), Color(0xFF7B1FA2), Color(0xFF5E35B1), Color(0xFF512DA8),
    Color(0xFF3949AB), Color(0xFF303F9F), Color(0xFF1E88E5), Color(0xFF1976D2),
    Color(0xFF039BE5), Color(0xFF0288D1), Color(0xFF00ACC1), Color(0xFF0097A7),
    Color(0xFF00897B), Color(0xFF00796B), Color(0xFF43A047), Color(0xFF388E3C),
    Color(0xFF7CB342), Color(0xFF689F38), Color(0xFFC0CA33), Color(0xFFAFB42B),
    Color(0xFFFDD835), Color(0xFFFBC02D), Color(0xFFFFB300), Color(0xFFFFA000),
    Color(0xFFFB8C00), Color(0xFFF57C00), Color(0xFFF4511E), Color(0xFFE64A19),
    Color(0xFF6D4C41), Color(0xFF5D4037), Color(0xFF546E7A), Color(0xFF455A64)
)

private fun getAvatarColorForContact(name: String): Color {
    if (name.isBlank()) return avatarColors[0]
    val hash = name.lowercase().hashCode()
    val index = (hash and 0x7FFFFFFF) % avatarColors.size
    return avatarColors[index]
}
