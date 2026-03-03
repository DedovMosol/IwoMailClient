package com.dedovmosol.iwomail.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactGroupEntity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.ContactRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.utils.getAvatarColor
import com.dedovmosol.iwomail.ui.components.rememberDebouncedState
import kotlinx.coroutines.launch

private val GROUP_COLORS = listOf(
    0xFF1976D2.toInt(), 0xFFD32F2F.toInt(), 0xFF388E3C.toInt(), 0xFFF57C00.toInt(),
    0xFF7B1FA2.toInt(), 0xFF0097A7.toInt(), 0xFFC2185B.toInt(), 0xFF5D4037.toInt(),
    0xFF303F9F.toInt(), 0xFF00796B.toInt(), 0xFFFBC02D.toInt(), 0xFF455A64.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBackClick: () -> Unit,
    onComposeClick: (String) -> Unit // email для нового письма
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactRepo = remember { ContactRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
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
    val debouncedLocalSearch by rememberDebouncedState(localSearchQuery)
    // filteredLocalContacts вычисляется ниже через remember с ключами
    
    // Группы контактов - используем key чтобы пересоздавать Flow при смене accountId
    val groups by remember(accountId) { contactRepo.getGroups(accountId) }.collectAsState(initial = emptyList())
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) } // null = все контакты
    var showCreateGroupDialog by rememberSaveable { mutableStateOf(false) }
    var groupToRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val groupToRename = groups.find { it.id == groupToRenameId }
    var groupToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val groupToDelete = groups.find { it.id == groupToDeleteId }
    var moveToGroupContactId by rememberSaveable { mutableStateOf<String?>(null) }
    val showMoveToGroupDialog = moveToGroupContactId?.let { id -> localContacts.find { it.id == id } }
    
    // Избранные контакты
    val favoriteContacts by remember(accountId) { contactRepo.getFavoriteContacts(accountId) }.collectAsState(initial = emptyList())
    
    // Exchange контакты из БД (синхронизированные в фоне)
    val exchangeContacts by remember(accountId) { contactRepo.getExchangeContacts(accountId) }.collectAsState(initial = emptyList())
    var exchangeSearchQuery by rememberSaveable { mutableStateOf("") }
    val debouncedExchangeSearch by rememberDebouncedState(exchangeSearchQuery)
    var isSyncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    
    var initialSyncDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(accountId) {
        if (accountId > 0 && !initialSyncDone && !isSyncing) {
            initialSyncDone = true
            isSyncing = true
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contactRepo.syncExchangeContacts(accountId)
                    contactRepo.syncGalContactsToDb(accountId)
                }
            } finally {
                isSyncing = false
            }
        }
    }
    
    // Множественный выбор
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    // Используем ArrayList<String> saver: Set<String> не всегда надёжно сохраняется в Bundle
    var selectedContactIds by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { ArrayList(it) },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(setOf<String>()) }
    // Флаг для пропуска первого срабатывания LaunchedEffect(selectedTab) при повороте экрана.
    // remember (не rememberSaveable) — пересоздаётся при rotation, что нам и нужно.
    var skipFirstTabEffect by remember { mutableStateOf(true) }
    
    // Email текущего аккаунта для фильтрации себя
    val ownEmail = activeAccount?.email?.lowercase() ?: ""
    
    // Количество контактов организации (без себя) для отображения в табе
    val exchangeContactsCount = remember(exchangeContacts, ownEmail) {
        if (ownEmail.isNotBlank()) {
            exchangeContacts.count { it.email.lowercase() != ownEmail }
        } else {
            exchangeContacts.size
        }
    }
    
    // Фильтрация Exchange контактов по поиску (исключая себя)
    val filteredExchangeContacts = remember(exchangeContacts, debouncedExchangeSearch, ownEmail) {
        val filtered = if (debouncedExchangeSearch.isBlank()) {
            exchangeContacts
        } else {
            exchangeContacts.filter { contact ->
                contact.displayName.contains(debouncedExchangeSearch, ignoreCase = true) ||
                contact.email.contains(debouncedExchangeSearch, ignoreCase = true) ||
                contact.company.contains(debouncedExchangeSearch, ignoreCase = true)
            }
        }
        // Исключаем себя из списка
        if (ownEmail.isNotBlank()) {
            filtered.filter { it.email.lowercase() != ownEmail }
        } else {
            filtered
        }
    }
    
    // Диалоги - используем rememberSaveable для сохранения при повороте
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var showContactDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var bulkDuplicateTotalCount by remember { mutableStateOf(0) }
    var bulkNewCount by remember { mutableStateOf(0) }
    var showBulkDuplicateChoiceDialog by remember { mutableStateOf(false) }
    var pendingBulkContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var pendingBulkDuplicatePairs by remember { mutableStateOf<List<Pair<ContactEntity, ContactEntity>>>(emptyList()) }
    var addToContactsConfirmId by rememberSaveable { mutableStateOf<String?>(null) }
    val addToContactsConfirmContact = addToContactsConfirmId?.let { id -> exchangeContacts.find { it.id == id } }
    var duplicateCheckContactId by rememberSaveable { mutableStateOf<String?>(null) }
    val duplicateCheckContact = duplicateCheckContactId?.let { id -> localContacts.find { it.id == id } ?: exchangeContacts.find { it.id == id } }
    var duplicateExistingContactId by rememberSaveable { mutableStateOf<String?>(null) }
    val duplicateExistingContact = duplicateExistingContactId?.let { id -> localContacts.find { it.id == id } }
    
    // Получаем объекты контактов по ID
    val editingContact = editingContactId?.let { id -> localContacts.find { it.id == id } }
    val showDeleteDialog = showDeleteDialogId?.let { id -> localContacts.find { it.id == id } }
    val showContactDetails: ContactEntity? = showContactDetailsId?.let { id -> 
        localContacts.find { it.id == id } ?: exchangeContacts.find { it.id == id }
    }
    
    // Импорт
    val importedMessage = if (isRussian) "Импортировано контактов:" else "Imported contacts:"
    val contactSavedMsg = if (isRussian) "Контакт сохранён" else "Contact saved"
    val contactReplacedMsg = if (isRussian) "Контакт заменён" else "Contact replaced"
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
    
    val filteredLocalContacts = remember(localContacts, debouncedLocalSearch, selectedGroupId, ownEmail) {
        localContacts.filter { contact ->
            val notSelf = ownEmail.isBlank() || contact.email.lowercase() != ownEmail
            val matchesGroup = when (selectedGroupId) {
                null -> true
                "favorites" -> contact.isFavorite
                "ungrouped" -> contact.groupId == null
                else -> contact.groupId == selectedGroupId
            }
            val matchesSearch = debouncedLocalSearch.isBlank() || 
                contact.displayName.contains(debouncedLocalSearch, true) ||
                contact.email.contains(debouncedLocalSearch, true) ||
                contact.company.contains(debouncedLocalSearch, true)
            
            notSelf && matchesGroup && matchesSearch
        }
    }
    
    val groupCounts = remember(localContacts) {
        val counts = mutableMapOf<String, Int>()
        var ungroupedCount = 0
        for (contact in localContacts) {
            val gid = contact.groupId
            if (gid != null) {
                counts[gid] = (counts[gid] ?: 0) + 1
            } else {
                ungroupedCount++
            }
        }
        counts["__ungrouped__"] = ungroupedCount
        counts
    }
    
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

    if (showBulkDuplicateChoiceDialog && bulkDuplicateTotalCount > 0) {
        val dupCount = bulkDuplicateTotalCount
        val newCount = bulkNewCount
        val dupPairs = pendingBulkDuplicatePairs
        val newContacts = pendingBulkContacts
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = {
                showBulkDuplicateChoiceDialog = false
                pendingBulkContacts = emptyList()
                pendingBulkDuplicatePairs = emptyList()
            },
            icon = { Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(if (isRussian) "Найдены дубликаты" else "Duplicates found")
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isRussian)
                            "Из выбранных контактов $dupCount уже существуют в личных контактах."
                        else
                            "$dupCount of selected contacts already exist in personal contacts.",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    if (newCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (isRussian)
                                "Новых контактов (будут добавлены): $newCount"
                            else
                                "New contacts (will be added): $newCount",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isRussian)
                            "Что сделать с дубликатами?"
                        else
                            "What to do with duplicates?",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showBulkDuplicateChoiceDialog = false
                        scope.launch {
                            var count = 0
                            newContacts.forEach { c ->
                                try {
                                    contactRepo.addContact(accountId, c.displayName, c.email, c.firstName, c.lastName, c.phone, c.mobilePhone, c.workPhone, c.company, c.department, c.jobTitle)
                                    count++
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                            }
                            dupPairs.forEach { (galContact, existing) ->
                                try {
                                    contactRepo.deleteContact(existing.id)
                                    contactRepo.addContact(accountId, galContact.displayName, galContact.email, galContact.firstName, galContact.lastName, galContact.phone, galContact.mobilePhone, galContact.workPhone, galContact.company, galContact.department, galContact.jobTitle)
                                    count++
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                            }
                            val msg = com.dedovmosol.iwomail.ui.NotificationStrings.getCopiedToPersonalContacts(isRussian)
                            Toast.makeText(context, "$msg: $count", Toast.LENGTH_SHORT).show()
                            pendingBulkContacts = emptyList()
                            pendingBulkDuplicatePairs = emptyList()
                        }
                    },
                    text = if (isRussian) "Заменить все" else "Replace all"
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showBulkDuplicateChoiceDialog = false
                        scope.launch {
                            var count = 0
                            newContacts.forEach { c ->
                                try {
                                    contactRepo.addContact(accountId, c.displayName, c.email, c.firstName, c.lastName, c.phone, c.mobilePhone, c.workPhone, c.company, c.department, c.jobTitle)
                                    count++
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                            }
                            val msg = com.dedovmosol.iwomail.ui.NotificationStrings.getCopiedToPersonalContacts(isRussian)
                            Toast.makeText(context, "$msg: $count", Toast.LENGTH_SHORT).show()
                            pendingBulkContacts = emptyList()
                            pendingBulkDuplicatePairs = emptyList()
                        }
                    },
                    text = if (isRussian) "Пропустить" else "Skip"
                )
            }
        )
    }
    
    // Диалог удаления
    showDeleteDialog?.let { contact ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteDialogId = null },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteContact) },
            text = { Text(Strings.deleteContactConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        scope.launch {
                            contactRepo.deleteContact(contact.id)
                            Toast.makeText(context, contactDeletedMsg, Toast.LENGTH_SHORT).show()
                        }
                        showDeleteDialogId = null
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteDialogId = null },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог деталей контакта
    showContactDetails?.let { contact ->
        ContactDetailsDialog(
            contact = contact,
            groups = groups,
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
                try {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                } catch (_: Exception) {
                    // Планшет без приложения «Телефон» — копируем номер в буфер обмена
                    clipboardManager.setText(AnnotatedString(phone))
                    Toast.makeText(context, if (isRussian) "Номер скопирован" else "Number copied", Toast.LENGTH_SHORT).show()
                }
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
                // Для Exchange/GAL контактов — сначала проверяем дубликат, потом спрашиваем
                showContactDetailsId = null
                scope.launch {
                    try {
                        val existing = contactRepo.findLocalDuplicate(accountId, contact.email)
                        if (existing != null) {
                            duplicateCheckContactId = contact.id
                            duplicateExistingContactId = existing.id
                        } else {
                            addToContactsConfirmId = contact.id
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Toast.makeText(context, if (isRussian) "Ошибка проверки контакта" else "Failed to check contact", Toast.LENGTH_SHORT).show()
                    }
                }
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

    // Диалог подтверждения дубликата при экспорте GAL → локальные контакты
    val safeGalContact = duplicateCheckContact
    val safeExistingContact = duplicateExistingContact
    if (safeGalContact != null && safeExistingContact != null) {
        val galContact = safeGalContact
        val existingContact = safeExistingContact
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = {
                duplicateCheckContactId = null
                duplicateExistingContactId = null
            },
            icon = { Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(if (isRussian) "Контакт уже существует" else "Contact already exists")
            },
            text = {
                Column {
                    Text(
                        if (isRussian)
                            "Контакт с email ${galContact.email} уже есть в личных контактах:"
                        else
                            "A contact with email ${galContact.email} already exists in personal contacts:"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = existingContact.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (existingContact.company.isNotBlank()) {
                        Text(
                            text = existingContact.company,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isRussian) "Заменить существующий контакт?" else "Replace existing contact?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                contactRepo.deleteContact(existingContact.id)
                                contactRepo.addContact(
                                    accountId = accountId,
                                    displayName = galContact.displayName,
                                    email = galContact.email,
                                    firstName = galContact.firstName,
                                    lastName = galContact.lastName,
                                    phone = galContact.phone,
                                    mobilePhone = galContact.mobilePhone,
                                    workPhone = galContact.workPhone,
                                    company = galContact.company,
                                    department = galContact.department,
                                    jobTitle = galContact.jobTitle
                                )
                                Toast.makeText(context, contactReplacedMsg, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Toast.makeText(context, if (isRussian) "Ошибка замены контакта" else "Failed to replace contact", Toast.LENGTH_SHORT).show()
                            }
                        }
                        duplicateCheckContactId = null
                        duplicateExistingContactId = null
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        duplicateCheckContactId = null
                        duplicateExistingContactId = null
                    },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог подтверждения добавления GAL-контакта в личные контакты
    addToContactsConfirmContact?.let { galContact ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { addToContactsConfirmId = null },
            icon = { Icon(AppIcons.PersonAdd, null) },
            title = {
                Text(if (isRussian) "Добавить в контакты?" else "Add to contacts?")
            },
            text = {
                Column {
                    Text(
                        text = galContact.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (galContact.email.isNotBlank()) {
                        Text(
                            text = galContact.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (galContact.company.isNotBlank()) {
                        Text(
                            text = galContact.company,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        val contactToAdd = galContact
                        addToContactsConfirmId = null
                        scope.launch {
                            try {
                                contactRepo.addContact(
                                    accountId = accountId,
                                    displayName = contactToAdd.displayName,
                                    email = contactToAdd.email,
                                    firstName = contactToAdd.firstName,
                                    lastName = contactToAdd.lastName,
                                    phone = contactToAdd.phone,
                                    mobilePhone = contactToAdd.mobilePhone,
                                    workPhone = contactToAdd.workPhone,
                                    company = contactToAdd.company,
                                    department = contactToAdd.department,
                                    jobTitle = contactToAdd.jobTitle
                                )
                                Toast.makeText(context, contactSavedMsg, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Toast.makeText(context, if (isRussian) "Ошибка сохранения контакта" else "Failed to save contact", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { addToContactsConfirmId = null },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог создания группы
    if (showCreateGroupDialog) {
        var newGroupName by rememberSaveable { mutableStateOf("") }
        val groupCreatedMsg = Strings.groupCreated
        
        val defaultColor = remember(groups) {
            val usedColors = groups.map { it.color }.toSet()
            GROUP_COLORS.firstOrNull { it !in usedColors } ?: GROUP_COLORS[0]
        }
        
        var selectedColor by rememberSaveable { mutableIntStateOf(defaultColor) }
        
        val currentConfig = LocalContext.current.resources.configuration
        val isLandscape = currentConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenHeightDp = currentConfig.screenHeightDp
        // Высота текстовой области: оставляем место для иконки, заголовка, кнопок
        val maxTextHeight = if (isLandscape) {
            (screenHeightDp * 0.35f).dp.coerceIn(120.dp, 220.dp)
        } else {
            300.dp
        }
        
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            scrollable = false, // скроллим только текстовую область, кнопки всегда видны
            icon = { 
                Icon(
                    AppIcons.CreateNewFolder, 
                    null,
                    tint = Color(selectedColor),
                    modifier = Modifier.size(48.dp)
                ) 
            },
            title = { Text(Strings.createGroup) },
            text = {
                val scrollState = rememberScrollState()
                
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxTextHeight)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = if (scrollState.maxValue > 0) 10.dp else 0.dp)
                    ) {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            label = { Text(Strings.groupName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Заголовок палитры — центрирован
                        Text(
                            text = if (LocalLanguage.current == AppLanguage.RUSSIAN) "Цвет группы" else "Group color",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Палитра цветов — 2 ряда по 6, центрирована, с отступами
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GROUP_COLORS.chunked(6).forEach { rowColors ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowColors.forEach { color ->
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(color))
                                                .clickable { selectedColor = color }
                                                .then(
                                                    if (selectedColor == color) {
                                                        Modifier.border(
                                                            3.dp,
                                                            MaterialTheme.colorScheme.primary,
                                                            CircleShape
                                                        )
                                                    } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selectedColor == color) {
                                                Icon(
                                                    AppIcons.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    ScrollColumnScrollbar(scrollState)
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            scope.launch {
                                contactRepo.createGroup(accountId, newGroupName, selectedColor)
                                Toast.makeText(context, groupCreatedMsg, Toast.LENGTH_SHORT).show()
                            }
                            showCreateGroupDialog = false
                        }
                    },
                    text = Strings.save,
                    enabled = newGroupName.isNotBlank()
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showCreateGroupDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }
    
    // Диалог переименования группы
    groupToRename?.let { group ->
        var newName by rememberSaveable { mutableStateOf(group.name) }
        val groupRenamedMsg = Strings.groupRenamed
        
        var selectedColor by rememberSaveable { mutableIntStateOf(group.color) }
        
        val currentConfig = LocalContext.current.resources.configuration
        val isLandscape = currentConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenHeightDp = currentConfig.screenHeightDp
        val maxTextHeight = if (isLandscape) {
            (screenHeightDp * 0.35f).dp.coerceIn(120.dp, 220.dp)
        } else {
            300.dp
        }
        
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { groupToRenameId = null },
            scrollable = false,
            icon = { 
                Icon(
                    AppIcons.Edit, 
                    null,
                    tint = Color(selectedColor),
                    modifier = Modifier.size(48.dp)
                ) 
            },
            title = { Text(Strings.renameGroup) },
            text = {
                val scrollState = rememberScrollState()
                
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxTextHeight)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = if (scrollState.maxValue > 0) 10.dp else 0.dp)
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(Strings.groupName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Заголовок палитры — центрирован
                        Text(
                            text = if (LocalLanguage.current == AppLanguage.RUSSIAN) "Цвет группы" else "Group color",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Палитра цветов — 2 ряда по 6, центрирована, с отступами
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GROUP_COLORS.chunked(6).forEach { rowColors ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowColors.forEach { color ->
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(color))
                                                .clickable { selectedColor = color }
                                                .then(
                                                    if (selectedColor == color) {
                                                        Modifier.border(
                                                            3.dp,
                                                            MaterialTheme.colorScheme.primary,
                                                            CircleShape
                                                        )
                                                    } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selectedColor == color) {
                                                Icon(
                                                    AppIcons.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    ScrollColumnScrollbar(scrollState)
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        val nameChanged = newName.isNotBlank() && newName != group.name
                        val colorChanged = selectedColor != group.color
                        
                        if (nameChanged || colorChanged) {
                            scope.launch {
                                if (nameChanged) {
                                    contactRepo.renameGroup(group.id, newName)
                                }
                                if (colorChanged) {
                                    contactRepo.updateGroupColor(group.id, selectedColor)
                                }
                                Toast.makeText(context, groupRenamedMsg, Toast.LENGTH_SHORT).show()
                            }
                            groupToRenameId = null
                        }
                    },
                    text = Strings.save,
                    enabled = (newName.isNotBlank() && newName != group.name) || (selectedColor != group.color)
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { groupToRenameId = null },
                    text = Strings.cancel
                )
            }
        )
    }
    
    // Диалог удаления группы — StyledAlertDialog как при удалении писем/событий
    groupToDelete?.let { group ->
        val groupDeletedMsg = Strings.groupDeleted
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { groupToDeleteId = null },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteGroup) },
            text = { Text(Strings.deleteGroupConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        scope.launch {
                            contactRepo.deleteGroup(group.id)
                            if (selectedGroupId == group.id) {
                                selectedGroupId = null
                            }
                            Toast.makeText(context, groupDeletedMsg, Toast.LENGTH_SHORT).show()
                        }
                        groupToDeleteId = null
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { groupToDeleteId = null },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог перемещения контакта в группу
    showMoveToGroupDialog?.let { contact ->
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { moveToGroupContactId = null },
            icon = { Icon(AppIcons.Folder, null) },
            title = { Text(Strings.moveToGroup) },
            text = {
                // Ограничиваем высоту LazyColumn чтобы избежать вложенного скролла
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
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
                                moveToGroupContactId = null
                            }
                        )
                    }
                    // Группы
                    items(groups) { group ->
                        val groupColor = try { Color(group.color) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                        ListItem(
                            headlineContent = { Text(group.name) },
                            leadingContent = {
                                Icon(
                                    AppIcons.Folder,
                                    null,
                                    tint = if (contact.groupId == group.id) MaterialTheme.colorScheme.primary else groupColor
                                )
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    contactRepo.moveContactToGroup(contact.id, group.id)
                                }
                                moveToGroupContactId = null
                            }
                        )
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { moveToGroupContactId = null },
                    text = Strings.close
                )
            },
            dismissButton = {}
        )
    }

    // Получить выбранные контакты
    val selectedContacts = remember(selectedContactIds, localContacts, exchangeContacts) {
        val allContacts = localContacts + exchangeContacts
        allContacts.filter { it.id in selectedContactIds }
    }
    
    // Выход из режима выбора при смене вкладки.
    // skipFirstTabEffect предотвращает сброс выделения при повороте экрана:
    // LaunchedEffect запускается при каждом входе в composable, но первый запуск — это rotation,
    // а не настоящая смена вкладки пользователем.
    LaunchedEffect(selectedTab) {
        if (skipFirstTabEffect) {
            skipFirstTabEffect = false
            return@LaunchedEffect
        }
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
                                    val contactsToCopy = selectedContacts.toList()
                                    isSelectionMode = false
                                    selectedContactIds = emptySet()
                                    scope.launch {
                                        val newOnes = mutableListOf<ContactEntity>()
                                        val duplicates = mutableListOf<Pair<ContactEntity, ContactEntity>>()
                                        contactsToCopy.forEach { contact ->
                                            try {
                                                val existing = contactRepo.findLocalDuplicate(accountId, contact.email)
                                                if (existing != null) duplicates.add(contact to existing)
                                                else newOnes.add(contact)
                                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                                        }
                                        if (duplicates.isEmpty()) {
                                            var count = 0
                                            newOnes.forEach { c ->
                                                try {
                                                    contactRepo.addContact(accountId, c.displayName, c.email, c.firstName, c.lastName, c.phone, c.mobilePhone, c.workPhone, c.company, c.department, c.jobTitle)
                                                    count++
                                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                                            }
                                            val msg = com.dedovmosol.iwomail.ui.NotificationStrings.getCopiedToPersonalContacts(isRussian)
                                            Toast.makeText(context, "$msg: $count", Toast.LENGTH_SHORT).show()
                                        } else {
                                            pendingBulkContacts = newOnes
                                            pendingBulkDuplicatePairs = duplicates
                                            bulkDuplicateTotalCount = duplicates.size
                                            bulkNewCount = newOnes.size
                                            showBulkDuplicateChoiceDialog = true
                                        }
                                    }
                                }
                            ) {
                                Icon(AppIcons.PersonAdd, Strings.addToContacts, tint = Color.White)
                            }
                        }
                        // Переместить в группу (только для личных)
                        // localContactIds объявляется здесь для использования в обоих условиях
                        val localContactIds = remember(localContacts) { localContacts.map { it.id }.toSet() }
                        if (selectedTab == 0 && selectedContactIds.all { it in localContactIds }) {
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
                                            showGroupMenu = false
                                            scope.launch {
                                                selectedContactIds.forEach { id ->
                                                    contactRepo.moveContactToGroup(id, null)
                                                }
                                                isSelectionMode = false
                                                selectedContactIds = emptySet()
                                            }
                                        },
                                        leadingIcon = { Icon(AppIcons.FolderOff, null) }
                                    )
                                    groups.forEach { group ->
                                        val groupColor = try { Color(group.color) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                                        DropdownMenuItem(
                                            text = { Text(group.name) },
                                            onClick = {
                                                showGroupMenu = false
                                                scope.launch {
                                                    selectedContactIds.forEach { id ->
                                                        contactRepo.moveContactToGroup(id, group.id)
                                                    }
                                                    isSelectionMode = false
                                                    selectedContactIds = emptySet()
                                                }
                                            },
                                            leadingIcon = { Icon(AppIcons.Folder, null, tint = groupColor) }
                                        )
                                    }
                                }
                            }
                        }
                        // Удалить (только для личных)
                        if (selectedTab == 0 && selectedContactIds.all { it in localContactIds }) {
                            var showDeleteConfirm by remember { mutableStateOf(false) }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(AppIcons.Delete, Strings.delete, tint = Color.White)
                            }
                            if (showDeleteConfirm) {
                                com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
                                    onDismissRequest = { showDeleteConfirm = false },
                                    icon = { Icon(AppIcons.Delete, null) },
                                    title = { Text(Strings.deleteContacts) },
                                    text = { Text("${Strings.deleteContactsConfirm} ${selectedContactIds.size}") },
                                    confirmButton = {
                                        com.dedovmosol.iwomail.ui.theme.DeleteButton(
                                            onClick = {
                                                val idsToDelete = selectedContactIds.toList()
                                                showDeleteConfirm = false
                                                scope.launch {
                                                    val deleted = contactRepo.deleteContacts(idsToDelete)
                                                    Toast.makeText(context, "$contactDeletedMsg: $deleted", Toast.LENGTH_SHORT).show()
                                                    isSelectionMode = false
                                                    selectedContactIds = emptySet()
                                                }
                                            },
                                            text = Strings.yes
                                        )
                                    },
                                    dismissButton = {
                                        com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                                            onClick = { showDeleteConfirm = false },
                                            text = Strings.no
                                        )
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
                    text = { Text("${Strings.organization} ($exchangeContactsCount)") }
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
                    groupCounts = groupCounts,
                    selectedGroupId = selectedGroupId,
                    onGroupSelected = { selectedGroupId = it },
                    onGroupRename = { groupToRenameId = it.id },
                    onGroupDelete = { groupToDeleteId = it.id },
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
                    onContactMoveToGroup = { moveToGroupContactId = it.id },
                    onContactEdit = { editingContactId = it.id },
                    onContactDelete = { showDeleteDialogId = it.id },
                    onContactToggleFavorite = { contact ->
                        scope.launch { contactRepo.toggleFavorite(contact.id) }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedContactIds = selectedContactIds,
                    onDragSelect = { newIds -> selectedContactIds = newIds }
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
                            try {
                                when (val result = contactRepo.syncGalContactsToDb(accountId)) {
                                    is EasResult.Success -> {
                                        val msg = com.dedovmosol.iwomail.ui.NotificationStrings.getSynced(result.data, isRussian)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                    is EasResult.Error -> {
                                        syncError = result.message
                                    }
                                }
                            } finally {
                                isSyncing = false
                            }
                        }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedContactIds = selectedContactIds,
                    onDragSelect = { newIds -> selectedContactIds = newIds }
                )
            }
        }
    }
}


@Composable
private fun PersonalContactsList(
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
    val dragModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
        listState = contactsListState,
        itemKeys = contactKeys,
        selectedIds = selectedContactIds,
        onSelectionChange = onDragSelect
    )
    
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(state = contactsListState, modifier = dragModifier.fillMaxSize()) {
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
                                tint = com.dedovmosol.iwomail.ui.theme.AppColors.favorites
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
private fun OrganizationContactsList(
    contacts: List<ContactEntity>,
    isSyncing: Boolean,
    syncError: String?,
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
                    com.dedovmosol.iwomail.ui.NotificationStrings.getOrganizationAddressBook(isRussian),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (contacts.isNotEmpty()) 
                        com.dedovmosol.iwomail.ui.NotificationStrings.getContactsCount(contacts.size, isRussian)
                    else 
                        com.dedovmosol.iwomail.ui.NotificationStrings.getGlobalAddressList(isRussian),
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
                        contentDescription = com.dedovmosol.iwomail.ui.NotificationStrings.getSyncAction(isRussian),
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
                            com.dedovmosol.iwomail.ui.NotificationStrings.getLoadingContacts(isRussian),
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
                            com.dedovmosol.iwomail.ui.NotificationStrings.getTapToLoadContacts(isRussian),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onSyncClick) {
                            Icon(AppIcons.Sync, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getLoadAction(isRussian))
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
                val orgDragModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
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
    
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (contact.isFavorite) {
                    Icon(
                        AppIcons.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = com.dedovmosol.iwomail.ui.theme.AppColors.favorites
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (contact.email.isNotBlank()) {
                    Text(contact.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    tint = com.dedovmosol.iwomail.ui.theme.AppColors.favorites
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
    
    val currentConfig = LocalContext.current.resources.configuration
    val isLandscape = currentConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenHeightDp = currentConfig.screenHeightDp
    // Высота текстовой области: оставляем место для заголовка и кнопок
    // Оставляем место для заголовка (~56dp) и кнопок (~56dp) внутри Surface(maxHeight=500dp)
    val maxTextHeight = if (isLandscape) {
        (screenHeightDp * 0.40f).dp.coerceIn(150.dp, 280.dp)
    } else {
        (screenHeightDp * 0.50f).dp.coerceIn(200.dp, 340.dp)
    }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        scrollable = false, // скроллим только текстовую область, кнопки всегда видны
        title = { Text(if (contact == null) Strings.addContact else Strings.editContact) },
        text = {
            val scrollState = rememberScrollState()
            
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxTextHeight)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = if (scrollState.maxValue > 0) 10.dp else 0.dp)
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
                }
                
                ScrollColumnScrollbar(scrollState)
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = {
                    val name = displayName.ifBlank { "$firstName $lastName".trim().ifBlank { email } }
                    onSave(name, email, firstName, lastName, phone, mobilePhone, workPhone, company, department, jobTitle, notes)
                },
                text = Strings.save,
                enabled = displayName.isNotBlank() || email.isNotBlank() || firstName.isNotBlank() || lastName.isNotBlank()
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel
            )
        }
    )
}


@Composable
private fun ContactDetailsDialog(
    contact: ContactEntity,
    groups: List<ContactGroupEntity>,
    onDismiss: () -> Unit,
    onWriteEmail: (String) -> Unit,
    onCopyEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToContacts: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { com.dedovmosol.iwomail.data.repository.SettingsRepository.getInstance(context) }
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
    
    val isLocal = contact.source == com.dedovmosol.iwomail.data.database.ContactSource.LOCAL
    val name = contact.displayName
    val email = contact.email
    val phone = contact.phone
    val mobilePhone = contact.mobilePhone
    val workPhone = contact.workPhone
    val company = contact.company
    val department = contact.department
    val jobTitle = contact.jobTitle
    val notes = contact.notes
    
    // Находим группу контакта
    val contactGroup = contact.groupId?.let { groupId ->
        groups.find { it.id == groupId }
    }
    
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
                
                // Группа контакта (только для локальных контактов)
                if (isLocal) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (contactGroup != null) {
                            // Контакт в группе
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(contactGroup.color))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = contactGroup.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(contactGroup.color),
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            // Контакт без группы
                            Icon(
                                AppIcons.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (LocalLanguage.current == AppLanguage.RUSSIAN) "Без группы" else "Without group",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
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
                            tint = com.dedovmosol.iwomail.ui.theme.AppColors.calendar,
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
                                    tint = com.dedovmosol.iwomail.ui.theme.AppColors.tasks
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    Strings.copyEmail, 
                                    color = com.dedovmosol.iwomail.ui.theme.AppColors.tasks,
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
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.close
            )
        },
        dismissButton = {}
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
