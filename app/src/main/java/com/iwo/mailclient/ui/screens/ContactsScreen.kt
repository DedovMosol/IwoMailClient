package com.iwo.mailclient.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll

import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.data.database.ContactEntity
import com.iwo.mailclient.data.database.ContactGroupEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.ContactRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.LocalLanguage
import com.iwo.mailclient.ui.AppLanguage
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

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

private fun getAvatarColor(name: String): Color {
    if (name.isBlank()) return avatarColors[0]
    val hash = name.lowercase().hashCode()
    return avatarColors[(hash and 0x7FFFFFFF) % avatarColors.size]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBackClick: () -> Unit,
    onComposeClick: (String) -> Unit // email для нового письма
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactRepo = remember { ContactRepository(context) }
    val accountRepo = remember { AccountRepository(context) }
    val clipboardManager = LocalClipboardManager.current
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN

    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    // Вкладки
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(Strings.personalContacts, Strings.organization)
    
    // Личные контакты - используем key чтобы пересоздавать Flow при смене accountId
    val localContacts by remember(accountId) { contactRepo.getLocalContacts(accountId) }.collectAsState(initial = emptyList())
    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var filteredLocalContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    
    // Группы контактов - используем key чтобы пересоздавать Flow при смене accountId
    val groups by remember(accountId) { contactRepo.getGroups(accountId) }.collectAsState(initial = emptyList())
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) } // null = все контакты
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var groupToRename by remember { mutableStateOf<ContactGroupEntity?>(null) }
    var groupToDelete by remember { mutableStateOf<ContactGroupEntity?>(null) }
    var showMoveToGroupDialog by remember { mutableStateOf<ContactEntity?>(null) }
    
    // Избранные контакты
    val favoriteContacts by remember(accountId) { contactRepo.getFavoriteContacts(accountId) }.collectAsState(initial = emptyList())
    
    // Exchange контакты из БД (синхронизированные в фоне)
    val exchangeContacts by remember(accountId) { contactRepo.getExchangeContacts(accountId) }.collectAsState(initial = emptyList())
    var exchangeSearchQuery by rememberSaveable { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    
    // Множественный выбор
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedContactIds by remember { mutableStateOf(setOf<String>()) }
    
    // Фильтрация Exchange контактов по поиску
    val filteredExchangeContacts = remember(exchangeContacts, exchangeSearchQuery) {
        if (exchangeSearchQuery.isBlank()) {
            exchangeContacts
        } else {
            exchangeContacts.filter { contact ->
                contact.displayName.contains(exchangeSearchQuery, ignoreCase = true) ||
                contact.email.contains(exchangeSearchQuery, ignoreCase = true) ||
                contact.company.contains(exchangeSearchQuery, ignoreCase = true)
            }
        }
    }
    
    // Диалоги - используем rememberSaveable для сохранения при повороте
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var showContactDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    
    // Получаем объекты контактов по ID
    val editingContact = editingContactId?.let { id -> localContacts.find { it.id == id } }
    val showDeleteDialog = showDeleteDialogId?.let { id -> localContacts.find { it.id == id } }
    val showContactDetails: ContactEntity? = showContactDetailsId?.let { id -> 
        localContacts.find { it.id == id } ?: exchangeContacts.find { it.id == id }
    }
    
    // Импорт
    val importedMessage = if (isRussian) "Импортировано контактов:" else "Imported contacts:"
    val contactSavedMsg = if (isRussian) "Контакт сохранён" else "Contact saved"
    val contactDeletedMsg = if (isRussian) "Контакт удалён" else "Contact deleted"
    val emailCopiedMsg = if (isRussian) "Email скопирован" else "Email copied"
    val noContactsToExportMsg = if (isRussian) "Нет контактов для экспорта" else "No contacts to export"
    
    val importVCardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: ""
                    val count = contactRepo.importFromVCard(accountId, content)
                    Toast.makeText(context, "$importedMessage $count", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    val importCSVLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: ""
                    val count = contactRepo.importFromCSV(accountId, content)
                    Toast.makeText(context, "$importedMessage $count", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    // Фильтрация локальных контактов
    LaunchedEffect(localContacts, localSearchQuery, selectedGroupId) {
        filteredLocalContacts = localContacts.filter { contact ->
            // Фильтр по группе
            val matchesGroup = when (selectedGroupId) {
                null -> true // Все контакты
                "favorites" -> contact.isFavorite // Избранные
                "ungrouped" -> contact.groupId == null // Без группы
                else -> contact.groupId == selectedGroupId
            }
            // Фильтр по поиску
            val matchesSearch = localSearchQuery.isBlank() || 
                contact.displayName.contains(localSearchQuery, true) ||
                contact.email.contains(localSearchQuery, true) ||
                contact.company.contains(localSearchQuery, true)
            
            matchesGroup && matchesSearch
        }
    }
    
    // Группировка по алфавиту
    val groupedContacts = remember(filteredLocalContacts) {
        filteredLocalContacts
            .sortedBy { it.displayName.lowercase() }
            .groupBy { it.displayName.firstOrNull()?.uppercaseChar() ?: '#' }
    }

    
    // Диалог добавления/редактирования контакта
    if (showAddDialog || editingContact != null) {
        ContactEditDialog(
            contact = editingContact,
            onDismiss = { 
                showAddDialog = false
                editingContactId = null
            },
            onSave = { displayName, email, firstName, lastName, phone, mobilePhone, workPhone, company, department, jobTitle, notes ->
                scope.launch {
                    if (editingContact != null) {
                        contactRepo.updateContact(editingContact.copy(
                            displayName = displayName,
                            email = email,
                            firstName = firstName,
                            lastName = lastName,
                            phone = phone,
                            mobilePhone = mobilePhone,
                            workPhone = workPhone,
                            company = company,
                            department = department,
                            jobTitle = jobTitle,
                            notes = notes
                        ))
                    } else {
                        contactRepo.addContact(
                            accountId = accountId,
                            displayName = displayName,
                            email = email,
                            firstName = firstName,
                            lastName = lastName,
                            phone = phone,
                            mobilePhone = mobilePhone,
                            workPhone = workPhone,
                            company = company,
                            department = department,
                            jobTitle = jobTitle,
                            notes = notes
                        )
                    }
                    Toast.makeText(context, contactSavedMsg, Toast.LENGTH_SHORT).show()
                }
                showAddDialog = false
                editingContactId = null
            }
        )
    }
    
    // Диалог удаления
    showDeleteDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = { showDeleteDialogId = null },
            icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteContact) },
            text = { Text(Strings.deleteContactConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        contactRepo.deleteContact(contact.id)
                        Toast.makeText(context, contactDeletedMsg, Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialogId = null
                }) {
                    Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogId = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог деталей контакта
    showContactDetails?.let { contact ->
        ContactDetailsDialog(
            contact = contact,
            onDismiss = { showContactDetailsId = null },
            onWriteEmail = { email ->
                showContactDetailsId = null
                onComposeClick(email)
            },
            onCopyEmail = { email ->
                clipboardManager.setText(AnnotatedString(email))
                Toast.makeText(context, emailCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onCall = { phone ->
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                context.startActivity(intent)
            },
            onEdit = {
                showContactDetailsId = null
                editingContactId = contact.id
            },
            onDelete = {
                showContactDetailsId = null
                showDeleteDialogId = contact.id
            },
            onAddToContacts = {
                // Для Exchange контактов — добавляем как локальный контакт
                scope.launch {
                    contactRepo.addContact(
                        accountId = accountId,
                        displayName = contact.displayName,
                        email = contact.email,
                        firstName = contact.firstName,
                        lastName = contact.lastName,
                        phone = contact.phone,
                        mobilePhone = contact.mobilePhone,
                        company = contact.company,
                        department = contact.department,
                        jobTitle = contact.jobTitle
                    )
                    Toast.makeText(context, contactSavedMsg, Toast.LENGTH_SHORT).show()
                }
                showContactDetailsId = null
            }
        )
    }
    
    // Диалог экспорта
    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onExportVCard = {
                scope.launch {
                    val contacts = if (selectedTab == 0) filteredLocalContacts else filteredExchangeContacts
                    if (contacts.isEmpty()) {
                        Toast.makeText(context, noContactsToExportMsg, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val vcardData = contactRepo.exportToVCard(contacts)
                    shareFile(context, vcardData, "contacts.vcf", "text/vcard")
                }
                showExportDialog = false
            },
            onExportCSV = {
                scope.launch {
                    val contacts = if (selectedTab == 0) filteredLocalContacts else filteredExchangeContacts
                    if (contacts.isEmpty()) {
                        Toast.makeText(context, noContactsToExportMsg, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val csvData = contactRepo.exportToCSV(contacts)
                    shareFile(context, csvData, "contacts.csv", "text/csv")
                }
                showExportDialog = false
            }
        )
    }

    // Диалог создания группы
    if (showCreateGroupDialog) {
        var newGroupName by remember { mutableStateOf("") }
        val groupCreatedMsg = Strings.groupCreated
        
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            icon = { 
                Icon(
                    AppIcons.CreateNewFolder, 
                    null,
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(48.dp)
                ) 
            },
            title = { Text(Strings.createGroup) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text(Strings.groupName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { showCreateGroupDialog = false }) {
                            Text(Strings.cancel)
                        }
                        TextButton(
                            onClick = {
                                if (newGroupName.isNotBlank()) {
                                    scope.launch {
                                        contactRepo.createGroup(accountId, newGroupName)
                                        Toast.makeText(context, groupCreatedMsg, Toast.LENGTH_SHORT).show()
                                    }
                                    showCreateGroupDialog = false
                                }
                            },
                            enabled = newGroupName.isNotBlank()
                        ) {
                            Text(Strings.save)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }
    
    // Диалог переименования группы
    groupToRename?.let { group ->
        var newName by remember { mutableStateOf(group.name) }
        val groupRenamedMsg = Strings.groupRenamed
        
        AlertDialog(
            onDismissRequest = { groupToRename = null },
            icon = { 
                Icon(
                    AppIcons.Edit, 
                    null,
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(48.dp)
                ) 
            },
            title = { Text(Strings.renameGroup) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(Strings.groupName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { groupToRename = null }) {
                            Text(Strings.cancel)
                        }
                        TextButton(
                            onClick = {
                                if (newName.isNotBlank() && newName != group.name) {
                                    scope.launch {
                                        contactRepo.renameGroup(group.id, newName)
                                        Toast.makeText(context, groupRenamedMsg, Toast.LENGTH_SHORT).show()
                                    }
                                    groupToRename = null
                                }
                            },
                            enabled = newName.isNotBlank() && newName != group.name
                        ) {
                            Text(Strings.save)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }
    
    // Диалог удаления группы
    groupToDelete?.let { group ->
        val groupDeletedMsg = Strings.groupDeleted
        
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteGroup) },
            text = { Text(Strings.deleteGroupConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        contactRepo.deleteGroup(group.id)
                        if (selectedGroupId == group.id) {
                            selectedGroupId = null
                        }
                        Toast.makeText(context, groupDeletedMsg, Toast.LENGTH_SHORT).show()
                    }
                    groupToDelete = null
                }) {
                    Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог перемещения контакта в группу
    showMoveToGroupDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = { showMoveToGroupDialog = null },
            icon = { Icon(AppIcons.Folder, null) },
            title = { Text(Strings.moveToGroup) },
            text = {
                LazyColumn {
                    // Без группы
                    item {
                        ListItem(
                            headlineContent = { Text(Strings.withoutGroup) },
                            leadingContent = { 
                                Icon(
                                    AppIcons.FolderOff, 
                                    null,
                                    tint = if (contact.groupId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                ) 
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    contactRepo.moveContactToGroup(contact.id, null)
                                }
                                showMoveToGroupDialog = null
                            }
                        )
                    }
                    // Группы
                    items(groups) { group ->
                        ListItem(
                            headlineContent = { Text(group.name) },
                            leadingContent = { 
                                Icon(
                                    AppIcons.Folder, 
                                    null,
                                    tint = if (contact.groupId == group.id) MaterialTheme.colorScheme.primary else Color(group.color)
                                ) 
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    contactRepo.moveContactToGroup(contact.id, group.id)
                                }
                                showMoveToGroupDialog = null
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveToGroupDialog = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }

    // Получить выбранные контакты
    val selectedContacts = remember(selectedContactIds, localContacts, exchangeContacts) {
        val allContacts = localContacts + exchangeContacts
        allContacts.filter { it.id in selectedContactIds }
    }
    
    // Выход из режима выбора при смене вкладки
    LaunchedEffect(selectedTab) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedContactIds = emptySet()
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                // TopAppBar для режима выбора
                TopAppBar(
                    title = { Text("${Strings.selected}: ${selectedContactIds.size}", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedContactIds = emptySet()
                        }) {
                            Icon(AppIcons.Close, Strings.cancel, tint = Color.White)
                        }
                    },
                    actions = {
                        // Написать письмо
                        IconButton(
                            onClick = {
                                val emails = selectedContacts.map { it.email }.filter { it.isNotBlank() }
                                if (emails.isNotEmpty()) {
                                    onComposeClick(emails.joinToString(", "))
                                }
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                            },
                            enabled = selectedContacts.any { it.email.isNotBlank() }
                        ) {
                            Icon(AppIcons.Email, Strings.writeEmail, tint = Color.White)
                        }
                        // Копировать в локальные (только для организации)
                        if (selectedTab == 1) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        var copied = 0
                                        selectedContacts.forEach { contact ->
                                            contactRepo.addContact(
                                                accountId = accountId,
                                                displayName = contact.displayName,
                                                email = contact.email,
                                                firstName = contact.firstName,
                                                lastName = contact.lastName,
                                                phone = contact.phone,
                                                mobilePhone = contact.mobilePhone,
                                                company = contact.company,
                                                department = contact.department,
                                                jobTitle = contact.jobTitle
                                            )
                                            copied++
                                        }
                                        val msg = com.iwo.mailclient.ui.NotificationStrings.getCopiedToPersonalContacts(isRussian)
                                        Toast.makeText(context, "$msg: $copied", Toast.LENGTH_SHORT).show()
                                    }
                                    isSelectionMode = false
                                    selectedContactIds = emptySet()
                                }
                            ) {
                                Icon(AppIcons.PersonAdd, Strings.addToContacts, tint = Color.White)
                            }
                        }
                        // Переместить в группу (только для личных)
                        if (selectedTab == 0 && selectedContacts.all { it in localContacts }) {
                            var showGroupMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showGroupMenu = true }) {
                                    Icon(AppIcons.Folder, Strings.moveToGroup, tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showGroupMenu,
                                    onDismissRequest = { showGroupMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(Strings.withoutGroup) },
                                        onClick = {
                                            scope.launch {
                                                selectedContactIds.forEach { id ->
                                                    contactRepo.moveContactToGroup(id, null)
                                                }
                                            }
                                            showGroupMenu = false
                                            isSelectionMode = false
                                            selectedContactIds = emptySet()
                                        },
                                        leadingIcon = { Icon(AppIcons.FolderOff, null) }
                                    )
                                    groups.forEach { group ->
                                        DropdownMenuItem(
                                            text = { Text(group.name) },
                                            onClick = {
                                                scope.launch {
                                                    selectedContactIds.forEach { id ->
                                                        contactRepo.moveContactToGroup(id, group.id)
                                                    }
                                                }
                                                showGroupMenu = false
                                                isSelectionMode = false
                                                selectedContactIds = emptySet()
                                            },
                                            leadingIcon = { Icon(AppIcons.Folder, null, tint = Color(group.color)) }
                                        )
                                    }
                                }
                            }
                        }
                        // Удалить (только для личных)
                        if (selectedTab == 0 && selectedContacts.all { it in localContacts }) {
                            var showDeleteConfirm by remember { mutableStateOf(false) }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(AppIcons.Delete, Strings.delete, tint = Color.White)
                            }
                            if (showDeleteConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteConfirm = false },
                                    icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    title = { Text(Strings.deleteContacts) },
                                    text = { Text("${Strings.deleteContactsConfirm} ${selectedContactIds.size}") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            scope.launch {
                                                val deleted = contactRepo.deleteContacts(selectedContactIds.toList())
                                                Toast.makeText(context, "$contactDeletedMsg: $deleted", Toast.LENGTH_SHORT).show()
                                            }
                                            showDeleteConfirm = false
                                            isSelectionMode = false
                                            selectedContactIds = emptySet()
                                        }) {
                                            Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteConfirm = false }) {
                                            Text(Strings.cancel)
                                        }
                                    }
                                )
                            }
                        }
                        // Выбрать все
                        IconButton(onClick = {
                            val currentContacts = if (selectedTab == 0) filteredLocalContacts else filteredExchangeContacts
                            selectedContactIds = if (selectedContactIds.size == currentContacts.size) {
                                emptySet()
                            } else {
                                currentContacts.map { it.id }.toSet()
                            }
                        }) {
                            Icon(
                                if (selectedContactIds.size == (if (selectedTab == 0) filteredLocalContacts else filteredExchangeContacts).size)
                                    AppIcons.CheckCircle else AppIcons.Check,
                                Strings.selectAll,
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                )
            } else {
                // Обычный TopAppBar
                TopAppBar(
                    title = { Text(Strings.contacts, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                        }
                    },
                    actions = {
                        // Режим выбора
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(AppIcons.Check, Strings.select, tint = Color.White)
                        }
                        // Добавить контакт (только для личных)
                        if (selectedTab == 0) {
                            IconButton(onClick = { showAddDialog = true }) {
                                Icon(AppIcons.PersonAdd, Strings.addContact, tint = Color.White)
                            }
                        }
                        // Меню
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(AppIcons.MoreVert, null, tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                if (selectedTab == 0) {
                                    DropdownMenuItem(
                                        text = { Text(Strings.createGroup) },
                                        onClick = {
                                            showMoreMenu = false
                                            showCreateGroupDialog = true
                                        },
                                        leadingIcon = { Icon(AppIcons.CreateNewFolder, null) }
                                    )
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(Strings.exportContacts) },
                                    onClick = {
                                        showMoreMenu = false
                                        showExportDialog = true
                                    },
                                    leadingIcon = { Icon(AppIcons.Upload, null) }
                                )
                                if (selectedTab == 0) {
                                    DropdownMenuItem(
                                        text = { Text(Strings.importFromVCard) },
                                        onClick = {
                                            showMoreMenu = false
                                            importVCardLauncher.launch("text/vcard")
                                        },
                                        leadingIcon = { Icon(AppIcons.Download, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(Strings.importFromCSV) },
                                        onClick = {
                                            showMoreMenu = false
                                            importCSVLauncher.launch("text/*")
                                        },
                                        leadingIcon = { Icon(AppIcons.Download, null) }
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
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Вкладки
            TabRow(selectedTabIndex = selectedTab) {
                // Личные
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("${Strings.personalContacts} (${localContacts.size})") }
                )
                // Организация
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${Strings.organization} (${exchangeContacts.size})") }
                )
            }
            
            // Поле поиска
            OutlinedTextField(
                value = if (selectedTab == 0) localSearchQuery else exchangeSearchQuery,
                onValueChange = {
                    if (selectedTab == 0) localSearchQuery = it else exchangeSearchQuery = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(Strings.searchContacts) },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    val query = if (selectedTab == 0) localSearchQuery else exchangeSearchQuery
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            if (selectedTab == 0) localSearchQuery = "" else exchangeSearchQuery = ""
                        }) {
                            Icon(AppIcons.Clear, null)
                        }
                    }
                },
                singleLine = true
            )
            
            // Контент
            when (selectedTab) {
                0 -> PersonalContactsList(
                    groups = groups,
                    favoriteCount = favoriteContacts.size,
                    selectedGroupId = selectedGroupId,
                    onGroupSelected = { selectedGroupId = it },
                    onGroupRename = { groupToRename = it },
                    onGroupDelete = { groupToDelete = it },
                    groupedContacts = groupedContacts,
                    onContactClick = { 
                        if (isSelectionMode) {
                            selectedContactIds = if (it.id in selectedContactIds) {
                                selectedContactIds - it.id
                            } else {
                                selectedContactIds + it.id
                            }
                        } else {
                            showContactDetailsId = it.id
                        }
                    },
                    onContactLongClick = {
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedContactIds = setOf(it.id)
                        }
                    },
                    onContactMoveToGroup = { showMoveToGroupDialog = it },
                    onContactEdit = { editingContactId = it.id },
                    onContactDelete = { showDeleteDialogId = it.id },
                    onContactToggleFavorite = { contact ->
                        scope.launch { contactRepo.toggleFavorite(contact.id) }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedContactIds = selectedContactIds
                )
                1 -> OrganizationContactsList(
                    contacts = filteredExchangeContacts,
                    isSyncing = isSyncing,
                    syncError = syncError,
                    onContactClick = { 
                        if (isSelectionMode) {
                            selectedContactIds = if (it.id in selectedContactIds) {
                                selectedContactIds - it.id
                            } else {
                                selectedContactIds + it.id
                            }
                        } else {
                            showContactDetailsId = it.id
                        }
                    },
                    onContactLongClick = {
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedContactIds = setOf(it.id)
                        }
                    },
                    onSyncClick = {
                        scope.launch {
                            isSyncing = true
                            syncError = null
                            when (val result = contactRepo.syncGalContactsToDb(accountId)) {
                                is EasResult.Success -> {
                                    val msg = com.iwo.mailclient.ui.NotificationStrings.getSynced(result.data, isRussian)
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                is EasResult.Error -> {
                                    syncError = result.message
                                }
                            }
                            isSyncing = false
                        }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedContactIds = selectedContactIds
                )
            }
        }
    }
}


@Composable
private fun PersonalContactsList(
    groups: List<ContactGroupEntity>,
    favoriteCount: Int = 0,
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
    selectedContactIds: Set<String> = emptySet()
) {
    var expandedGroupMenu by remember { mutableStateOf<String?>(null) }
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Фильтр по группам (горизонтальный скролл чипов)
        item {
            androidx.compose.foundation.lazy.LazyRow(
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
                                tint = Color(0xFFFFB300)
                            )
                        }
                    )
                }
                // Группы
                items(groups) { group ->
                    Box {
                        FilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { onGroupSelected(group.id) },
                            label = { Text(group.name) },
                            leadingIcon = {
                                Icon(
                                    AppIcons.Folder,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(group.color)
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
                    FilterChip(
                        selected = selectedGroupId == "ungrouped",
                        onClick = { onGroupSelected("ungrouped") },
                        label = { Text(Strings.withoutGroup) },
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
}

@Composable
private fun OrganizationContactsList(
    contacts: List<ContactEntity>,
    isSyncing: Boolean,
    syncError: String?,
    onContactClick: (ContactEntity) -> Unit,
    onContactLongClick: (ContactEntity) -> Unit = {},
    onSyncClick: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedContactIds: Set<String> = emptySet()
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
                    com.iwo.mailclient.ui.NotificationStrings.getOrganizationAddressBook(isRussian),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (contacts.isNotEmpty()) 
                        com.iwo.mailclient.ui.NotificationStrings.getContactsCount(contacts.size, isRussian)
                    else 
                        com.iwo.mailclient.ui.NotificationStrings.getGlobalAddressList(isRussian),
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
                        contentDescription = com.iwo.mailclient.ui.NotificationStrings.getSyncAction(isRussian),
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
                            com.iwo.mailclient.ui.NotificationStrings.getLoadingContacts(isRussian),
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
                            com.iwo.mailclient.ui.NotificationStrings.getTapToLoadContacts(isRussian),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onSyncClick) {
                            Icon(AppIcons.Sync, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.iwo.mailclient.ui.NotificationStrings.getLoadAction(isRussian))
                        }
                    }
                }
            }
            else -> {
                // Показываем контакты
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
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
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExchangeContactItem(
    contact: ContactEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false
) {
    ListItem(
        headlineContent = {
            Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (contact.email.isNotBlank()) {
                    Text(contact.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
    )
}

@Composable
private fun ContactItem(
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
private fun ContactItemWithGroup(
    contact: ContactEntity,
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
    
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (contact.isFavorite) {
                    Icon(
                        AppIcons.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFFB300)
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (contact.email.isNotBlank()) {
                    Text(contact.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    tint = Color(0xFFFFB300)
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
    )
}


@Composable
private fun ContactEditDialog(
    contact: ContactEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String, String, String, String) -> Unit
) {
    var displayName by rememberSaveable { mutableStateOf(contact?.displayName ?: "") }
    var email by rememberSaveable { mutableStateOf(contact?.email ?: "") }
    var firstName by rememberSaveable { mutableStateOf(contact?.firstName ?: "") }
    var lastName by rememberSaveable { mutableStateOf(contact?.lastName ?: "") }
    var phone by rememberSaveable { mutableStateOf(contact?.phone ?: "") }
    var mobilePhone by rememberSaveable { mutableStateOf(contact?.mobilePhone ?: "") }
    var workPhone by rememberSaveable { mutableStateOf(contact?.workPhone ?: "") }
    var company by rememberSaveable { mutableStateOf(contact?.company ?: "") }
    var department by rememberSaveable { mutableStateOf(contact?.department ?: "") }
    var jobTitle by rememberSaveable { mutableStateOf(contact?.jobTitle ?: "") }
    var notes by rememberSaveable { mutableStateOf(contact?.notes ?: "") }
    
    // Сохранение фокуса при повороте экрана
    var focusedFieldIndex by rememberSaveable { mutableIntStateOf(-1) }
    val displayNameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val firstNameFocus = remember { FocusRequester() }
    val lastNameFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val mobilePhoneFocus = remember { FocusRequester() }
    val workPhoneFocus = remember { FocusRequester() }
    val companyFocus = remember { FocusRequester() }
    val departmentFocus = remember { FocusRequester() }
    val jobTitleFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    
    // Восстановление фокуса после поворота
    LaunchedEffect(focusedFieldIndex) {
        if (focusedFieldIndex >= 0) {
            kotlinx.coroutines.delay(100)
            when (focusedFieldIndex) {
                0 -> displayNameFocus.requestFocus()
                1 -> emailFocus.requestFocus()
                2 -> firstNameFocus.requestFocus()
                3 -> lastNameFocus.requestFocus()
                4 -> phoneFocus.requestFocus()
                5 -> mobilePhoneFocus.requestFocus()
                6 -> workPhoneFocus.requestFocus()
                7 -> companyFocus.requestFocus()
                8 -> departmentFocus.requestFocus()
                9 -> jobTitleFocus.requestFocus()
                10 -> notesFocus.requestFocus()
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (contact == null) Strings.addContact else Strings.editContact) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(Strings.displayName) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(displayNameFocus)
                        .onFocusChanged { if (it.isFocused) focusedFieldIndex = 0 }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text(Strings.firstName) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(firstNameFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 2 }
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text(Strings.lastName) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(lastNameFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 3 }
                    )
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(Strings.emailAddress) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(emailFocus)
                        .onFocusChanged { if (it.isFocused) focusedFieldIndex = 1 }
                )
                // Телефоны в ряд
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(Strings.phone) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(phoneFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 4 }
                    )
                    OutlinedTextField(
                        value = mobilePhone,
                        onValueChange = { mobilePhone = it },
                        label = { Text(Strings.mobilePhone) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(mobilePhoneFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 5 }
                    )
                }
                OutlinedTextField(
                    value = workPhone,
                    onValueChange = { workPhone = it },
                    label = { Text(Strings.workPhone) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(workPhoneFocus)
                        .onFocusChanged { if (it.isFocused) focusedFieldIndex = 6 }
                )
                // Компания и отдел в ряд
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = company,
                        onValueChange = { company = it },
                        label = { Text(Strings.company) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(companyFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 7 }
                    )
                    OutlinedTextField(
                        value = department,
                        onValueChange = { department = it },
                        label = { Text(Strings.department) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(departmentFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 8 }
                    )
                }
                OutlinedTextField(
                    value = jobTitle,
                    onValueChange = { jobTitle = it },
                    label = { Text(Strings.jobTitle) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(jobTitleFocus)
                        .onFocusChanged { if (it.isFocused) focusedFieldIndex = 9 }
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(Strings.contactNotes) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(notesFocus)
                        .onFocusChanged { if (it.isFocused) focusedFieldIndex = 10 }
                )
                
                // Кнопки в разных сторонах
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(Strings.cancel)
                    }
                    TextButton(
                        onClick = {
                            val name = displayName.ifBlank { "$firstName $lastName".trim().ifBlank { email } }
                            onSave(name, email, firstName, lastName, phone, mobilePhone, workPhone, company, department, jobTitle, notes)
                        },
                        enabled = displayName.isNotBlank() || email.isNotBlank() || firstName.isNotBlank() || lastName.isNotBlank()
                    ) {
                        Text(Strings.save)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}


@Composable
private fun ContactDetailsDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onWriteEmail: (String) -> Unit,
    onCopyEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToContacts: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { com.iwo.mailclient.data.repository.SettingsRepository.getInstance(context) }
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
    
    val isLocal = contact.source == com.iwo.mailclient.data.database.ContactSource.LOCAL
    val name = contact.displayName
    val email = contact.email
    val phone = contact.phone
    val mobilePhone = contact.mobilePhone
    val workPhone = contact.workPhone
    val company = contact.company
    val department = contact.department
    val jobTitle = contact.jobTitle
    val notes = contact.notes
    
    val avatarColor = getAvatarColor(name)
    
    // Анимация появления
    val scale = remember { androidx.compose.animation.core.Animatable(if (animationsEnabled) 0.8f else 1f) }
    val animatedAlpha = remember { androidx.compose.animation.core.Animatable(if (animationsEnabled) 0f else 1f) }
    
    LaunchedEffect(Unit) {
        if (animationsEnabled) {
            launch {
                scale.animateTo(1f, androidx.compose.animation.core.tween(200))
            }
            launch {
                animatedAlpha.animateTo(1f, androidx.compose.animation.core.tween(200))
            }
        }
    }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = animatedAlpha.value
                },
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Аватар + имя в одну строку
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Email с зелёной иконкой
                if (email.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            AppIcons.Email, 
                            null, 
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    // Две кнопки в ряд
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Написать письмо
                        OutlinedButton(
                            onClick = { onWriteEmail(email) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    AppIcons.Send, 
                                    null, 
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF2196F3)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    Strings.writeEmail, 
                                    color = Color(0xFF03A9F4),
                                    maxLines = 2,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        // Копировать email
                        OutlinedButton(
                            onClick = { onCopyEmail(email) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    AppIcons.ContentCopy, 
                                    null, 
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF9C27B0)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    Strings.copyEmail, 
                                    color = Color(0xFF9C27B0),
                                    maxLines = 2,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Кнопки действий в зависимости от типа контакта
                if (isLocal) {
                    // Для локальных контактов - редактирование и удаление
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                AppIcons.Edit,
                                contentDescription = Strings.edit,
                                tint = avatarColor,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                AppIcons.Delete,
                                contentDescription = Strings.delete,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    // Для Exchange контактов - добавить в личные контакты
                    IconButton(
                        onClick = onAddToContacts,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            AppIcons.PersonAdd,
                            contentDescription = Strings.addToContacts,
                            tint = avatarColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    label: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icon, 
            null, 
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(36.dp)) {
                Icon(
                    AppIcons.Call, 
                    Strings.callPhone, 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    actions: @Composable (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text)
            actions?.invoke()
        }
    }
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onExportVCard: () -> Unit,
    onExportCSV: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.exportContacts) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(Strings.exportToVCard) },
                    leadingContent = { Icon(AppIcons.ContactPage, null) },
                    modifier = Modifier.clickable(onClick = onExportVCard)
                )
                ListItem(
                    headlineContent = { Text(Strings.exportToCSV) },
                    leadingContent = { Icon(AppIcons.TableChart, null) },
                    modifier = Modifier.clickable(onClick = onExportCSV)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        }
    )
}

private fun shareFile(context: android.content.Context, content: String, fileName: String, mimeType: String) {
    try {
        val file = java.io.File(context.cacheDir, fileName)
        file.writeText(content)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
    }
}
