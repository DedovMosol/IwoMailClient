package com.dedovmosol.iwomail.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.dedovmosol.iwomail.ui.screens.contacts.OrganizationContactsList
import com.dedovmosol.iwomail.ui.screens.contacts.PersonalContactsList
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.components.rememberDebouncedState
import com.dedovmosol.iwomail.ui.screens.contacts.ContactEditDialog
import com.dedovmosol.iwomail.ui.screens.contacts.ContactDetailsDialog
import com.dedovmosol.iwomail.ui.screens.contacts.ExportDialog
import com.dedovmosol.iwomail.ui.screens.contacts.cleanContactEmail
import com.dedovmosol.iwomail.ui.screens.contacts.GROUP_COLORS
import com.dedovmosol.iwomail.ui.screens.contacts.shareFile
import kotlinx.coroutines.launch

private val StringSetStateSaver = androidx.compose.runtime.saveable.Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() }
)

private val ContactPairIdListSaver = listSaver<List<Pair<String, String>>, String>(
    save = { pairs -> pairs.flatMap { listOf(it.first, it.second) } },
    restore = { saved -> saved.chunked(2).mapNotNull { if (it.size == 2) it[0] to it[1] else null } }
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
    
    // Вкладки: Личные | Exchange (папка Contacts) | GAL (адресная книга)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    
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
    
    // Tab 1: Exchange Folder контакты (папка Contacts на сервере)
    val exchangeFolderContacts by remember(accountId) { contactRepo.getExchangeFolderContacts(accountId) }.collectAsState(initial = emptyList())
    var exchangeSearchQuery by rememberSaveable { mutableStateOf("") }
    val debouncedExchangeSearch by rememberDebouncedState(exchangeSearchQuery)
    var isExchangeSyncing by remember { mutableStateOf(false) }
    var exchangeSyncError by remember(accountId) { mutableStateOf<String?>(null) }
    
    // Tab 2: GAL контакты (глобальная адресная книга)
    val galContacts by remember(accountId) { contactRepo.getGalContacts(accountId) }.collectAsState(initial = emptyList())
    var galSearchQuery by rememberSaveable { mutableStateOf("") }
    val debouncedGalSearch by rememberDebouncedState(galSearchQuery)
    var isGalSyncing by remember { mutableStateOf(false) }
    var galSyncError by remember(accountId) { mutableStateOf<String?>(null) }
    
    var initialExchangeSyncDone by rememberSaveable(accountId) { mutableStateOf(false) }
    var initialGalSyncDone by rememberSaveable(accountId) { mutableStateOf(false) }
    
    // Автоматическая синхронизация Exchange при первом открытии
    LaunchedEffect(accountId) {
        if (accountId > 0 && !initialExchangeSyncDone && !isExchangeSyncing) {
            initialExchangeSyncDone = true
            isExchangeSyncing = true
            try {
                contactRepo.syncExchangeContacts(accountId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                isExchangeSyncing = false
            }
        }
    }
    
    // Автоматическая синхронизация GAL при первом переключении на вкладку GAL.
    // snapshotFlow гарантирует, что смена вкладки не отменит уже запущенную синхронизацию.
    LaunchedEffect(accountId) {
        androidx.compose.runtime.snapshotFlow { selectedTab }.collect { tab ->
            if (tab == 2 && accountId > 0 && !initialGalSyncDone && !isGalSyncing) {
                initialGalSyncDone = true
                isGalSyncing = true
                try {
                    contactRepo.syncGalContactsToDb(accountId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                } finally {
                    isGalSyncing = false
                }
            }
        }
    }
    
    // Множественный выбор
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    // Используем ArrayList<String> saver: Set<String> не всегда надёжно сохраняется в Bundle
    var selectedContactIds by rememberSaveable(
        stateSaver = StringSetStateSaver
    ) { mutableStateOf(setOf<String>()) }
    // Флаг для пропуска первого срабатывания LaunchedEffect(selectedTab) при повороте экрана.
    // remember (не rememberSaveable) — пересоздаётся при rotation, что нам и нужно.
    var skipFirstTabEffect by remember { mutableStateOf(true) }
    
    // Email текущего аккаунта для фильтрации себя
    val ownEmail = activeAccount?.email?.lowercase() ?: ""
    
    // Количество контактов Exchange Folder (без себя)
    val exchangeFolderCount = remember(exchangeFolderContacts, ownEmail) {
        if (ownEmail.isNotBlank()) {
            exchangeFolderContacts.count { it.email.lowercase() != ownEmail }
        } else {
            exchangeFolderContacts.size
        }
    }
    
    // Количество контактов GAL (без себя)
    val galCount = remember(galContacts, ownEmail) {
        if (ownEmail.isNotBlank()) {
            galContacts.count { it.email.lowercase() != ownEmail }
        } else {
            galContacts.size
        }
    }
    
    // Фильтрация Exchange Folder контактов по поиску (исключая себя)
    val filteredExchangeContacts = remember(exchangeFolderContacts, debouncedExchangeSearch, ownEmail) {
        val filtered = if (debouncedExchangeSearch.isBlank()) {
            exchangeFolderContacts
        } else {
            exchangeFolderContacts.filter { contact ->
                contact.displayName.contains(debouncedExchangeSearch, ignoreCase = true) ||
                contact.email.contains(debouncedExchangeSearch, ignoreCase = true) ||
                contact.company.contains(debouncedExchangeSearch, ignoreCase = true)
            }
        }
        if (ownEmail.isNotBlank()) {
            filtered.filter { it.email.lowercase() != ownEmail }
        } else {
            filtered
        }
    }
    
    // Фильтрация GAL контактов по поиску (исключая себя)
    val filteredGalContacts = remember(galContacts, debouncedGalSearch, ownEmail) {
        val filtered = if (debouncedGalSearch.isBlank()) {
            galContacts
        } else {
            galContacts.filter { contact ->
                contact.displayName.contains(debouncedGalSearch, ignoreCase = true) ||
                contact.email.contains(debouncedGalSearch, ignoreCase = true) ||
                contact.company.contains(debouncedGalSearch, ignoreCase = true)
            }
        }
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
    var bulkDuplicateTotalCount by rememberSaveable { mutableIntStateOf(0) }
    var bulkNewCount by rememberSaveable { mutableIntStateOf(0) }
    var showBulkDuplicateChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingBulkContactIds by rememberSaveable(stateSaver = StringSetStateSaver) {
        mutableStateOf(emptySet())
    }
    var pendingBulkDuplicatePairIds by rememberSaveable(stateSaver = ContactPairIdListSaver) {
        mutableStateOf(emptyList())
    }
    var addToContactsConfirmId by rememberSaveable { mutableStateOf<String?>(null) }
    val addToContactsConfirmContact = addToContactsConfirmId?.let { id ->
        exchangeFolderContacts.find { it.id == id } ?: galContacts.find { it.id == id }
    }
    var duplicateCheckContactId by rememberSaveable { mutableStateOf<String?>(null) }
    val duplicateCheckContact = duplicateCheckContactId?.let { id ->
        localContacts.find { it.id == id }
            ?: exchangeFolderContacts.find { it.id == id }
            ?: galContacts.find { it.id == id }
    }
    var duplicateExistingContactId by rememberSaveable { mutableStateOf<String?>(null) }
    val duplicateExistingContact = duplicateExistingContactId?.let { id -> localContacts.find { it.id == id } }
    
    // Получаем объекты контактов по ID
    val editingContact = editingContactId?.let { id -> localContacts.find { it.id == id } }
    val showDeleteDialog = showDeleteDialogId?.let { id -> localContacts.find { it.id == id } }
    val showContactDetails: ContactEntity? = showContactDetailsId?.let { id -> 
        localContacts.find { it.id == id }
            ?: exchangeFolderContacts.find { it.id == id }
            ?: galContacts.find { it.id == id }
    }

    val allContactsById = remember(localContacts, exchangeFolderContacts, galContacts) {
        (localContacts + exchangeFolderContacts + galContacts).associateBy { it.id }
    }
    val localContactsById = remember(localContacts) {
        localContacts.associateBy { it.id }
    }
    val pendingBulkContacts = remember(pendingBulkContactIds, allContactsById) {
        pendingBulkContactIds.mapNotNull { allContactsById[it] }
    }
    val pendingBulkDuplicatePairs = remember(
        pendingBulkDuplicatePairIds,
        allContactsById,
        localContactsById
    ) {
        pendingBulkDuplicatePairIds.mapNotNull { (candidateId, existingId) ->
            val candidate = allContactsById[candidateId]
            val existing = localContactsById[existingId]
            if (candidate != null && existing != null) candidate to existing else null
        }
    }
    val isBulkDuplicateDataReady = remember(
        pendingBulkContacts,
        pendingBulkDuplicatePairs,
        bulkNewCount,
        bulkDuplicateTotalCount
    ) {
        pendingBulkContacts.size == bulkNewCount &&
            pendingBulkDuplicatePairs.size == bulkDuplicateTotalCount
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
                    val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { stream ->
                            stream.bufferedReader().readText()
                        } ?: ""
                    }
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
                    val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { stream ->
                            stream.bufferedReader().readText()
                        } ?: ""
                    }
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
                pendingBulkContactIds = emptySet()
                pendingBulkDuplicatePairIds = emptyList()
                bulkDuplicateTotalCount = 0
                bulkNewCount = 0
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
                    if (!isBulkDuplicateDataReady) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                            pendingBulkContactIds = emptySet()
                            pendingBulkDuplicatePairIds = emptyList()
                            bulkDuplicateTotalCount = 0
                            bulkNewCount = 0
                        }
                    },
                    text = if (isRussian) "Заменить все" else "Replace all",
                    enabled = isBulkDuplicateDataReady
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
                            pendingBulkContactIds = emptySet()
                            pendingBulkDuplicatePairIds = emptyList()
                            bulkDuplicateTotalCount = 0
                            bulkNewCount = 0
                        }
                    },
                    text = if (isRussian) "Пропустить" else "Skip",
                    enabled = isBulkDuplicateDataReady
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
                        val existing = contactRepo.findLocalDuplicate(accountId, cleanContactEmail(contact.email))
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
                    val contacts = when (selectedTab) { 0 -> filteredLocalContacts; 1 -> filteredExchangeContacts; else -> filteredGalContacts }
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
                    val contacts = when (selectedTab) { 0 -> filteredLocalContacts; 1 -> filteredExchangeContacts; else -> filteredGalContacts }
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
                            "Контакт с email ${cleanContactEmail(galContact.email)} уже есть в личных контактах:"
                        else
                            "A contact with email ${cleanContactEmail(galContact.email)} already exists in personal contacts:"
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
                    val galCleanEmail = cleanContactEmail(galContact.email)
                    if (galCleanEmail.isNotBlank()) {
                        Text(
                            text = galCleanEmail,
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
    val selectedContacts = remember(selectedContactIds, localContacts, exchangeFolderContacts, galContacts) {
        val allContacts = localContacts + exchangeFolderContacts + galContacts
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
                                val emails = selectedContacts.map { cleanContactEmail(it.email) }.filter { it.isNotBlank() }
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
                        // Копировать в локальные (для Exchange и GAL)
                        if (selectedTab == 1 || selectedTab == 2) {
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
                                                val existing = contactRepo.findLocalDuplicate(accountId, cleanContactEmail(contact.email))
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
                                            pendingBulkContactIds = newOnes.map { it.id }.toSet()
                                            pendingBulkDuplicatePairIds = duplicates.map { it.first.id to it.second.id }
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
                            val currentContacts = when (selectedTab) { 0 -> filteredLocalContacts; 1 -> filteredExchangeContacts; else -> filteredGalContacts }
                            selectedContactIds = if (selectedContactIds.size == currentContacts.size) {
                                emptySet()
                            } else {
                                currentContacts.map { it.id }.toSet()
                            }
                        }) {
                            Icon(
                                if (selectedContactIds.size == (when (selectedTab) { 0 -> filteredLocalContacts; 1 -> filteredExchangeContacts; else -> filteredGalContacts }).size)
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
            // Вкладки: Личные | Exchange | GAL
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("${Strings.personalContacts} (${localContacts.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${Strings.exchangeContacts} ($exchangeFolderCount)") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("${Strings.galContacts} ($galCount)") }
                )
            }
            
            // Поле поиска
            OutlinedTextField(
                value = when (selectedTab) { 0 -> localSearchQuery; 1 -> exchangeSearchQuery; else -> galSearchQuery },
                onValueChange = {
                    when (selectedTab) { 0 -> localSearchQuery = it; 1 -> exchangeSearchQuery = it; else -> galSearchQuery = it }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(Strings.searchContacts) },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    val query = when (selectedTab) { 0 -> localSearchQuery; 1 -> exchangeSearchQuery; else -> galSearchQuery }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            when (selectedTab) { 0 -> localSearchQuery = ""; 1 -> exchangeSearchQuery = ""; else -> galSearchQuery = "" }
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
                    isSyncing = isExchangeSyncing,
                    syncError = exchangeSyncError,
                    title = if (isRussian) "Контакты Exchange" else "Exchange Contacts",
                    emptySubtitle = if (isRussian) "Нажмите для синхронизации" else "Tap to sync",
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
                            isExchangeSyncing = true
                            exchangeSyncError = null
                            try {
                                when (val result = contactRepo.syncExchangeContacts(accountId)) {
                                    is EasResult.Success -> {
                                        val msg = com.dedovmosol.iwomail.ui.NotificationStrings.getSynced(result.data, isRussian)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                    is EasResult.Error -> {
                                        exchangeSyncError = result.message
                                    }
                                }
                            } finally {
                                isExchangeSyncing = false
                            }
                        }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedContactIds = selectedContactIds,
                    onDragSelect = { newIds -> selectedContactIds = newIds }
                )
                2 -> OrganizationContactsList(
                    contacts = filteredGalContacts,
                    isSyncing = isGalSyncing,
                    syncError = galSyncError,
                    title = com.dedovmosol.iwomail.ui.NotificationStrings.getOrganizationAddressBook(isRussian),
                    emptySubtitle = com.dedovmosol.iwomail.ui.NotificationStrings.getGlobalAddressList(isRussian),
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
                            isGalSyncing = true
                            galSyncError = null
                            try {
                                when (val result = contactRepo.syncGalContactsToDb(accountId)) {
                                    is EasResult.Success -> {
                                        val msg = com.dedovmosol.iwomail.ui.NotificationStrings.getSynced(result.data, isRussian)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                    is EasResult.Error -> {
                                        galSyncError = result.message
                                    }
                                }
                            } finally {
                                isGalSyncing = false
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
