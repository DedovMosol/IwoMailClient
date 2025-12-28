package com.iwo.mailclient.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.FolderEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.data.repository.SettingsRepository
import kotlinx.coroutines.*

/**
 * ╨У╨╗╨╛╨▒╨░╨╗╤М╨╜╤Л╨╣ ╨║╨╛╨╜╤В╤А╨╛╨╗╨╗╨╡╤А ╨╜╨░╤З╨░╨╗╤М╨╜╨╛╨╣ ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕
 * ╨Ш╤Б╨┐╨╛╨╗╤М╨╖╤Г╨╡╤В ╤Б╨╛╨▒╤Б╤В╨▓╨╡╨╜╨╜╤Л╨╣ scope ╤З╤В╨╛╨▒╤Л ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤П ╨╜╨╡ ╨┐╤А╨╡╤А╤Л╨▓╨░╨╗╨░╤Б╤М ╨┐╤А╨╕ ╨┐╨╛╨▓╨╛╤А╨╛╤В╨╡ ╤Н╨║╤А╨░╨╜╨░
 */
object InitialSyncController {
    var isSyncing by mutableStateOf(false)
        private set
    var syncDone by mutableStateOf(false)
        private set
    
    private var syncJob: Job? = null
    private var syncStarted = false
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    fun startSyncIfNeeded(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        // ╨Х╤Б╨╗╨╕ ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤П ╤Г╨╢╨╡ ╨▒╤Л╨╗╨░ ╨╕╨╗╨╕ ╤Г╨╢╨╡ ╨╖╨░╨┐╤Г╤Й╨╡╨╜╨░ тАФ ╨┐╤А╨╛╨┐╤Г╤Б╨║╨░╨╡╨╝
        if (syncDone || syncStarted) {
            return
        }
        
        syncStarted = true
        isSyncing = true
        
        syncJob = syncScope.launch {
            try {
                delay(100) // ╨Э╨╡╨▒╨╛╨╗╤М╤И╨░╤П ╨╖╨░╨┤╨╡╤А╨╢╨║╨░ ╤З╤В╨╛╨▒╤Л UI ╤Г╤Б╨┐╨╡╨╗ ╨╛╤В╤А╨╕╤Б╨╛╨▓╨░╤В╤М╤Б╤П
                
                // ╨в╨░╨╣╨╝╨░╤Г╤В ╨╜╨░ ╨▓╤Б╤О ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤О - 5 ╨╝╨╕╨╜╤Г╤В
                withTimeoutOrNull(300_000L) {
                    // ╨б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨╕╤А╤Г╨╡╨╝ ╨┐╨░╨┐╨║╨╕
                    withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    
                    delay(200)
                    
                    // ╨б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨╕╤А╤Г╨╡╨╝ ╨Т╨б╨Х ╨┐╨░╨┐╨║╨╕ ╤Б ╨┐╨╕╤Б╤М╨╝╨░╨╝╨╕ ╨Я╨Р╨а╨Р╨Ы╨Ы╨Х╨Ы╨м╨Э╨Ю
                    val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                    val currentFolders = withContext(Dispatchers.IO) {
                        com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                            .folderDao().getFoldersByAccountList(accountId)
                    }
                    val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                    
                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            foldersToSync.map { folder ->
                                launch {
                                    try {
                                        withTimeoutOrNull(120_000L) {
                                            mailRepo.syncEmails(accountId, folder.id)
                                        }
                                    } catch (_: Exception) { }
                                }
                            }.forEach { it.join() }
                        }
                    }
                    
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                }
            } catch (_: Exception) { }
            
            isSyncing = false
            syncDone = true
        }
    }
    
    /**
     * ╨б╨▒╤А╨╛╤Б ╤Б╨╛╤Б╤В╨╛╤П╨╜╨╕╤П (╨┤╨╗╤П ╤В╨╡╤Б╤В╨╛╨▓ ╨╕╨╗╨╕ ╨┐╤А╨╕ ╤Б╨╝╨╡╨╜╨╡ ╨░╨║╨║╨░╤Г╨╜╤В╨░)
     */
    fun reset() {
        syncJob?.cancel()
        syncJob = null
        syncStarted = false
        syncDone = false
        isSyncing = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToEmailList: (String) -> Unit,
    onNavigateToCompose: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToEmailDetail: (String) -> Unit = {},
    onNavigateToContacts: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val accountRepo = remember { AccountRepository(context) }
    val mailRepo = remember { MailRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    
    val accounts by accountRepo.accounts.collectAsState(initial = emptyList())
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    
    var folders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }
    var flaggedCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var accountsLoaded by remember { mutableStateOf(false) }
    // ╨д╨╗╨░╨│ ╤З╤В╨╛ ╨┤╨░╨╜╨╜╤Л╨╡ ╨╖╨░╨│╤А╤Г╨╢╨╡╨╜╤Л (╨┤╨╗╤П ╨┐╤А╨╡╨┤╨╛╤В╨▓╤А╨░╤Й╨╡╨╜╨╕╤П ╨╝╨╡╤А╤Ж╨░╨╜╨╕╤П)
    var dataLoaded by remember { mutableStateOf(false) }
    
    // ╨б╨╛╤Б╤В╨╛╤П╨╜╨╕╨╡ ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕ ╨╕╨╖ ╨│╨╗╨╛╨▒╨░╨╗╤М╨╜╨╛╨│╨╛ ╨║╨╛╨╜╤В╤А╨╛╨╗╨╗╨╡╤А╨░
    val isSyncing = InitialSyncController.isSyncing
    val initialSyncDone = InitialSyncController.syncDone
    
    // ╨Ф╨╕╨░╨╗╨╛╨│ ╤Б╨╛╨╖╨┤╨░╨╜╨╕╤П ╨┐╨░╨┐╨║╨╕
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    
    // ╨Ф╨╕╨░╨╗╨╛╨│ ╤Г╨┤╨░╨╗╨╡╨╜╨╕╤П ╨┐╨░╨┐╨║╨╕
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var isDeletingFolder by remember { mutableStateOf(false) }
    
    // ╨Ф╨╕╨░╨╗╨╛╨│ ╨┐╨╡╤А╨╡╨╕╨╝╨╡╨╜╨╛╨▓╨░╨╜╨╕╤П ╨┐╨░╨┐╨║╨╕
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var isRenamingFolder by remember { mutableStateOf(false) }
    
    // ╨Ь╨╡╨╜╤О ╨┤╨╡╨╣╤Б╤В╨▓╨╕╨╣ ╤Б ╨┐╨░╨┐╨║╨╛╨╣ (╨┐╤А╨╕ ╨┤╨╛╨╗╨│╨╛╨╝ ╨╜╨░╨╢╨░╤В╨╕╨╕)
    var folderForMenu by remember { mutableStateOf<FolderEntity?>(null) }
    
    // ╨д╨╗╨░╨│ ╤З╤В╨╛ ╨┐╨╡╤А╨▓╤Л╨╣ ╨░╨║╨║╨░╤Г╨╜╤В ╤Г╨╢╨╡ ╨░╨║╤В╨╕╨▓╨╕╤А╨╛╨▓╨░╨╜
    var firstAccountActivated by rememberSaveable { mutableStateOf(false) }
    
    // ╨Х╤Б╨╗╨╕ ╨╜╨╡╤В ╨░╨║╤В╨╕╨▓╨╜╨╛╨│╨╛ ╨░╨║╨║╨░╤Г╨╜╤В╨░ ╨╜╨╛ ╨╡╤Б╤В╤М ╨░╨║╨║╨░╤Г╨╜╤В╤Л - ╨░╨║╤В╨╕╨▓╨╕╤А╤Г╨╡╨╝ ╨┐╨╡╤А╨▓╤Л╨╣ (╤В╨╛╨╗╤М╨║╨╛ ╨╛╨┤╨╕╨╜ ╤А╨░╨╖)
    LaunchedEffect(accounts.isNotEmpty(), activeAccount == null) {
        if (activeAccount == null && accounts.isNotEmpty() && !firstAccountActivated) {
            firstAccountActivated = true
            accountRepo.setActiveAccount(accounts.first().id)
        }
    }
    
    // ╨Ч╨░╨│╤А╤Г╨╢╨░╨╡╨╝ ╨┐╨░╨┐╨║╨╕ ╨┐╤А╨╕ ╤Б╨╝╨╡╨╜╨╡ ╨░╨║╨║╨░╤Г╨╜╤В╨░
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        mailRepo.getFolders(accountId).collect { 
            folders = it
            dataLoaded = true
        }
    }
    
    // ╨Ч╨░╨│╤А╤Г╨╢╨░╨╡╨╝ ╤Б╤З╤С╤В╤З╨╕╨║ ╨╕╨╖╨▒╤А╨░╨╜╨╜╨╛╨│╨╛
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        mailRepo.getFlaggedCount(accountId).collect { flaggedCount = it }
    }
    
    // ╨Р╨▓╤В╨╛╨╝╨░╤В╨╕╤З╨╡╤Б╨║╨░╤П ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤П тАФ ╨Ю╨Ф╨Ш╨Э ╨а╨Р╨Ч ╨┐╤А╨╕ ╨╖╨░╨┐╤Г╤Б╨║╨╡ ╨┐╤А╨╕╨╗╨╛╨╢╨╡╨╜╨╕╤П
    // ╨Ш╤Б╨┐╨╛╨╗╤М╨╖╤Г╨╡╨╝ ╨│╨╗╨╛╨▒╨░╨╗╤М╨╜╤Л╨╣ ╨║╨╛╨╜╤В╤А╨╛╨╗╨╗╨╡╤А ╤З╤В╨╛╨▒╤Л ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤П ╨╜╨╡ ╨┐╤А╨╡╤А╤Л╨▓╨░╨╗╨░╤Б╤М ╨┐╤А╨╕ ╨┐╨╛╨▓╨╛╤А╨╛╤В╨╡ ╤Н╨║╤А╨░╨╜╨░
    LaunchedEffect(activeAccount?.id) {
        val account = activeAccount ?: return@LaunchedEffect
        InitialSyncController.startSyncIfNeeded(context, account.id, mailRepo, settingsRepo)
    }
    
    // ╨Я╨╡╤А╨▓╨╕╤З╨╜╨░╤П ╨┐╤А╨╛╨▓╨╡╤А╨║╨░ - ╨╡╤Б╤В╤М ╨╗╨╕ ╨░╨║╨║╨░╤Г╨╜╤В╤Л (╤В╨╛╨╗╤М╨║╨╛ ╨╛╨┤╨╕╨╜ ╤А╨░╨╖)
    var initialCheckDone by rememberSaveable { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        if (initialCheckDone) return@LaunchedEffect
        
        val count = accountRepo.getAccountCount()
        if (count == 0) {
            onNavigateToSetup()
        }
        accountsLoaded = true
        initialCheckDone = true
    }
    
    // ╨Ю╤В╤Б╨╗╨╡╨╢╨╕╨▓╨░╨╡╨╝ ╤Г╨┤╨░╨╗╨╡╨╜╨╕╨╡ ╨▓╤Б╨╡╤Е ╨░╨║╨║╨░╤Г╨╜╤В╨╛╨▓ (╤В╨╛╨╗╤М╨║╨╛ ╨┐╨╛╤Б╨╗╨╡ ╨╖╨░╨│╤А╤Г╨╖╨║╨╕ ╨┤╨░╨╜╨╜╤Л╤Е ╨╕╨╖ Flow)
    LaunchedEffect(accounts) {
        // ╨Ц╨┤╤С╨╝ ╨┐╨╛╨║╨░ Flow ╨╖╨░╨│╤А╤Г╨╖╨╕╤В ╨┤╨░╨╜╨╜╤Л╨╡ (╨╜╨╡ ╤А╨╡╨░╨│╨╕╤А╤Г╨╡╨╝ ╨╜╨░ ╨╜╨░╤З╨░╨╗╤М╨╜╤Л╨╣ emptyList)
        if (!accountsLoaded || !initialCheckDone) return@LaunchedEffect
        
        // ╨Ф╨░╤С╨╝ ╨▓╤А╨╡╨╝╤П Flow ╨╖╨░╨│╤А╤Г╨╖╨╕╤В╤М ╨┤╨░╨╜╨╜╤Л╨╡
        kotlinx.coroutines.delay(500)
        
        // ╨Я╤А╨╛╨▓╨╡╤А╤П╨╡╨╝ ╨╡╤Й╤С ╤А╨░╨╖ ╨╜╨░╨┐╤А╤П╨╝╤Г╤О ╨╕╨╖ ╨С╨Ф
        val actualCount = accountRepo.getAccountCount()
        if (actualCount == 0) {
            onNavigateToSetup()
        }
    }
    
    // ╨Э╨╡╨╖╨░╨▓╨╕╤Б╨╕╨╝╤Л╨╣ scope ╨┤╨╗╤П ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕ (╨╜╨╡ ╨╛╤В╨╝╨╡╨╜╤П╨╡╤В╤Б╤П ╨┐╤А╨╕ ╨╜╨░╨▓╨╕╨│╨░╤Ж╨╕╨╕)
    val syncScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    
    fun syncFolders() {
        activeAccount?.let { account ->
            syncScope.launch {
                isLoading = true
                
                try {
                    // ╨в╨░╨╣╨╝╨░╤Г╤В ╨╜╨░ ╨▓╤Б╤О ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤О - 60 ╤Б╨╡╨║╤Г╨╜╨┤
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                        // ╨б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨╕╤А╤Г╨╡╨╝ ╨┐╨░╨┐╨║╨╕
                        val result = withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                        
                        if (result is com.iwo.mailclient.eas.EasResult.Error) {
                            return@withTimeoutOrNull
                        }
                        
                        // ╨б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨╕╤А╤Г╨╡╨╝ ╨┐╨╕╤Б╤М╨╝╨░ ╨▓╨╛ ╨Т╨б╨Х╨е ╨┐╨░╨┐╨║╨░╤Е ╤Б ╨┐╨╕╤Б╤М╨╝╨░╨╝╨╕
                        val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                        val currentFolders = withContext(Dispatchers.IO) {
                            com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                .folderDao().getFoldersByAccountList(account.id)
                        }
                        val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                        
                        // ╨б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨╕╤А╤Г╨╡╨╝ ╨┐╨░╤А╨░╨╗╨╗╨╡╨╗╤М╨╜╨╛ ╤Б ╤В╨░╨╣╨╝╨░╤Г╤В╨╛╨╝ ╨╜╨░ ╨║╨░╨╢╨┤╤Г╤О ╨┐╨░╨┐╨║╤Г
                        withContext(Dispatchers.IO) {
                            kotlinx.coroutines.supervisorScope {
                                foldersToSync.map { folder ->
                                    launch {
                                        try {
                                            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                                                mailRepo.syncEmails(account.id, folder.id)
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }.forEach { it.join() }
                            }
                        }
                        
                        // ╨б╨╛╤Е╤А╨░╨╜╤П╨╡╨╝ ╨▓╤А╨╡╨╝╤П ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕
                        settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    }
                } catch (_: Exception) { }
                
                isLoading = false
            }
        }
    }
    
    // ╨Ф╨╕╨░╨╗╨╛╨│ ╤Б╨╛╨╖╨┤╨░╨╜╨╕╤П ╨┐╨░╨┐╨║╨╕
    if (showCreateFolderDialog) {
        val folderCreatedMsg = Strings.folderCreated
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { 
                showCreateFolderDialog = false
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
                TextButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isCreatingFolder = true
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.createFolder(account.id, newFolderName)
                                }
                                isCreatingFolder = false
                                when (result) {
                                    is com.iwo.mailclient.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderCreatedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is com.iwo.mailclient.eas.EasResult.Error -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            result.message, 
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                showCreateFolderDialog = false
                                newFolderName = ""
                            }
                        }
                    },
                    enabled = newFolderName.isNotBlank() && !isCreatingFolder
                ) {
                    if (isCreatingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.save)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showCreateFolderDialog = false
                    newFolderName = ""
                }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // ╨Ф╨╕╨░╨╗╨╛╨│ ╤Г╨┤╨░╨╗╨╡╨╜╨╕╤П ╨┐╨░╨┐╨║╨╕
    folderToDelete?.let { folder ->
        val folderDeletedMsg = Strings.folderDeleted
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(Strings.deleteFolder) },
            text = { 
                Text(Strings.deleteFolderConfirm) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isDeletingFolder = true
                                com.iwo.mailclient.util.SoundPlayer.playDeleteSound(context)
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.deleteFolder(account.id, folder.id)
                                }
                                isDeletingFolder = false
                                when (result) {
                                    is com.iwo.mailclient.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderDeletedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        // ╨Ю╨▒╨╜╨╛╨▓╨╗╤П╨╡╨╝ ╤Б╨┐╨╕╤Б╨╛╨║ ╨┐╨░╨┐╨╛╨║
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                                    }
                                    is com.iwo.mailclient.eas.EasResult.Error -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            result.message, 
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                folderToDelete = null
                            }
                        }
                    },
                    enabled = !isDeletingFolder
                ) {
                    if (isDeletingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.yes)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(Strings.no)
                }
            }
        )
    }
    
    // ╨Ь╨╡╨╜╤О ╨┤╨╡╨╣╤Б╤В╨▓╨╕╨╣ ╤Б ╨┐╨░╨┐╨║╨╛╨╣ (╨┐╤А╨╕ ╨┤╨╛╨╗╨│╨╛╨╝ ╨╜╨░╨╢╨░╤В╨╕╨╕)
    folderForMenu?.let { folder ->
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderForMenu = null },
            title = { Text(folder.displayName) },
            text = {
                Column {
                    // ╨Я╨╡╤А╨╡╨╕╨╝╨╡╨╜╨╛╨▓╨░╤В╤М
                    ListItem(
                        headlineContent = { Text(Strings.rename) },
                        leadingContent = { Icon(Icons.Default.Edit, null) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            renameNewName = folder.displayName
                            folderToRename = folder
                        }
                    )
                    // ╨г╨┤╨░╨╗╨╕╤В╤М
                    ListItem(
                        headlineContent = { Text(Strings.delete, color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            folderToDelete = folder
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { folderForMenu = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // ╨Ф╨╕╨░╨╗╨╛╨│ ╨┐╨╡╤А╨╡╨╕╨╝╨╡╨╜╨╛╨▓╨░╨╜╨╕╤П ╨┐╨░╨┐╨║╨╕
    folderToRename?.let { folder ->
        val folderRenamedMsg = Strings.folderRenamed
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { 
                folderToRename = null
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
                TextButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isRenamingFolder = true
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.renameFolder(account.id, folder.id, renameNewName)
                                }
                                isRenamingFolder = false
                                when (result) {
                                    is com.iwo.mailclient.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderRenamedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        // ╨Ю╨▒╨╜╨╛╨▓╨╗╤П╨╡╨╝ ╤Б╨┐╨╕╤Б╨╛╨║ ╨┐╨░╨┐╨╛╨║
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                                    }
                                    is com.iwo.mailclient.eas.EasResult.Error -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            result.message, 
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                folderToRename = null
                                renameNewName = ""
                            }
                        }
                    },
                    enabled = renameNewName.isNotBlank() && renameNewName != folder.displayName && !isRenamingFolder
                ) {
                    if (isRenamingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.rename)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    folderToRename = null
                    renameNewName = ""
                }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                DrawerContent(
                    accounts = accounts,
                    activeAccount = activeAccount,
                    folders = folders,
                    flaggedCount = flaggedCount,
                    showAccountPicker = showAccountPicker,
                    onToggleAccountPicker = { showAccountPicker = !showAccountPicker },
                    onAccountSelected = { account ->
                        scope.launch {
                            accountRepo.setActiveAccount(account.id)
                            showAccountPicker = false
                        }
                    },
                    onAddAccount = {
                        scope.launch { drawerState.close() }
                        onNavigateToSetup()
                    },
                    onFolderSelected = { folder ->
                        scope.launch { drawerState.close() }
                        onNavigateToEmailList(folder.id)
                    },
                    onFavoritesClick = {
                        scope.launch { drawerState.close() }
                        // ╨Я╨╡╤А╨╡╤Е╨╛╨┤╨╕╨╝ ╨╜╨░ ╤Н╨║╤А╨░╨╜ ╨╕╨╖╨▒╤А╨░╨╜╨╜╤Л╤Е (╨╕╤Б╨┐╨╛╨╗╤М╨╖╤Г╨╡╨╝ ╤Б╨┐╨╡╤Ж╨╕╨░╨╗╤М╨╜╤Л╨╣ ID)
                        onNavigateToEmailList("favorites")
                    },
                    onSettingsClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings()
                    },
                    onContactsClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToContacts()
                    },
                    onCreateFolder = {
                        scope.launch { drawerState.close() }
                        showCreateFolderDialog = true
                    },
                    onFolderLongClick = { folder ->
                        scope.launch { drawerState.close() }
                        folderForMenu = folder
                    }
                )
            }
        }
    ) {
        // ╨Я╨╛╨║╨░╨╖╤Л╨▓╨░╨╡╨╝ ╨╖╨░╨│╤А╤Г╨╖╨║╤Г ╨┐╨╛╨║╨░ ╨░╨║╨║╨░╤Г╨╜╤В ╨╜╨╡ ╨╖╨░╨│╤А╤Г╨╢╨╡╨╜
        if (activeAccount == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        Strings.loading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@ModalNavigationDrawer
        }
        
        Scaffold(
            topBar = {
                SearchTopBar(
                    accountName = activeAccount?.displayName ?: "",
                    accountColor = activeAccount?.color ?: 0xFF1976D2.toInt(),
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onSearchClick = onNavigateToSearch
                )
            },
            floatingActionButton = {
                val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
                FloatingActionButton(
                    onClick = onNavigateToCompose,
                    containerColor = colorTheme.gradientStart,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Edit, Strings.compose)
                }
            }
        ) { padding ->
            // ╨Ъ╤А╨░╤Б╨╕╨▓╨░╤П ╨│╨╗╨░╨▓╨╜╨░╤П ╤Б╤В╤А╨░╨╜╨╕╤Ж╨░ ╤Б ╨╕╨╜╤Д╨╛╤А╨╝╨░╤Ж╨╕╨╡╨╣ ╨╛ ╨┐╤А╨╕╨╗╨╛╨╢╨╡╨╜╨╕╨╕
            HomeContent(
                activeAccount = activeAccount,
                folders = folders,
                flaggedCount = flaggedCount,
                isLoading = isLoading,
                isSyncing = isSyncing,
                onSyncFolders = { syncFolders() },
                onFolderClick = onNavigateToEmailList,
                onContactsClick = onNavigateToContacts,
                onSettingsClick = onNavigateToSettings,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(
    activeAccount: AccountEntity?,
    folders: List<FolderEntity>,
    flaggedCount: Int,
    isLoading: Boolean,
    isSyncing: Boolean = false,
    onSyncFolders: () -> Unit,
    onFolderClick: (String) -> Unit,
    onContactsClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }
    var tipsExpanded by rememberSaveable { mutableStateOf(false) }
    
    // ╨Т╤А╨╡╨╝╤П ╨┐╨╛╤Б╨╗╨╡╨┤╨╜╨╡╨╣ ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
    // ╨Я╤А╨╛╨▓╨╡╤А╤П╨╡╨╝ ╨░╨║╤В╨╕╨▓╨╡╨╜ ╨╗╨╕ Battery Saver
    val isBatterySaverActive = remember { settingsRepo.isBatterySaverActive() }
    val ignoreBatterySaver by settingsRepo.ignoreBatterySaver.collectAsState(initial = false)
    val showBatterySaverWarning = isBatterySaverActive && !ignoreBatterySaver
    
    // ╨б╨╛╤Б╤В╨╛╤П╨╜╨╕╨╡ ╨┤╨╗╤П ╤Б╨║╤А╤Л╤В╨╕╤П ╤А╨╡╨║╨╛╨╝╨╡╨╜╨┤╨░╤Ж╨╕╨╕ (╤Б╨╛╤Е╤А╨░╨╜╤П╨╡╤В╤Б╤П ╨┐╤А╨╕ ╨╜╨░╨▓╨╕╨│╨░╤Ж╨╕╨╕, ╤Б╨▒╤А╨░╤Б╤Л╨▓╨░╨╡╤В╤Б╤П ╨┐╤А╨╕ ╨┐╨╡╤А╨╡╨╖╨░╨┐╤Г╤Б╨║╨╡ ╨┐╤А╨╕╨╗╨╛╨╢╨╡╨╜╨╕╤П)
    var isRecommendationDismissed by rememberSaveable { mutableStateOf(false) }
    
    // ╨Ы╨╛╨║╨░╨╗╨╕╨╖╨╛╨▓╨░╨╜╨╜╤Л╨╡ ╨╜╨░╨╖╨▓╨░╨╜╨╕╤П ╨┐╨░╨┐╨╛╨║ (╨▓╤Л╨╜╨╡╤Б╨╡╨╜╤Л ╨┤╨╛ LazyColumn)
    val inboxName = Strings.inbox
    val draftsName = Strings.drafts
    val trashName = Strings.trash
    val sentName = Strings.sent
    val favoritesName = Strings.favorites
    val foldersTitle = Strings.folders
    val refreshText = Strings.refresh
    val emailsCountText = Strings.emailsCount
    val emptyText = Strings.empty
    val contactsName = Strings.contacts
    
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ╨б╤В╨░╤В╤Г╤Б ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕ тАФ ╤Б╨╛╨▓╤А╨╡╨╝╨╡╨╜╨╜╤Л╨╣ ╨▓╨╕╨┤
        if (isSyncing || isLoading) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = Strings.syncingMail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        
        // ╨Ш╨╜╨┤╨╕╨║╨░╤В╨╛╤А Battery Saver
        if (showBatterySaverWarning) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSettingsClick() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.BatterySaver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = Strings.batterySaverActive,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
        
        // ╨Я╤А╨╕╨▓╨╡╤В╤Б╤В╨▓╨╡╨╜╨╜╨░╤П ╨║╨░╤А╤В╨╛╤З╨║╨░ тАФ ╤Б╨╛╨▓╤А╨╡╨╝╨╡╨╜╨╜╤Л╨╣ ╨│╤А╨░╨┤╨╕╨╡╨╜╤В╨╜╤Л╨╣ ╤Б╤В╨╕╨╗╤М ╤Б ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╡╨╣
        item {
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            var welcomeVisible by remember { mutableStateOf(!animationsEnabled) }
            LaunchedEffect(animationsEnabled) { welcomeVisible = true }
            
            val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
            val cardContent = @Composable {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = colorTheme.gradientStart
                    )
                ) {
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
                            .padding(24.dp)
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // ╨Р╨▓╨░╤В╨░╤А ╨░╨║╨║╨░╤Г╨╜╤В╨░
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeAccount?.color ?: 0xFF1976D2.toInt())),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeAccount?.displayName?.firstOrNull()?.uppercase() ?: "ЁЯУз",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = Strings.hello,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = activeAccount?.email ?: Strings.loading,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            
                            // ╨Т╤А╨╡╨╝╤П ╨┐╨╛╤Б╨╗╨╡╨┤╨╜╨╡╨╣ ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╨╕ - ╨┐╨╛╨║╨░╨╖╤Л╨▓╨░╨╡╨╝ ╤В╨╛╨╗╤М╨║╨╛ ╨║╨╛╨│╨┤╨░ ╨╜╨╡ ╨╕╨┤╤С╤В ╤Б╨╕╨╜╤Е╤А╨╛╨╜╨╕╨╖╨░╤Ж╨╕╤П
                            if (!isSyncing && !isLoading && lastSyncTime > 0) {
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                val formatter = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                val syncTimeText = "${Strings.lastSync} ${formatter.format(java.util.Date(lastSyncTime))}"
                                
                                Text(
                                    text = syncTimeText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
            
            if (animationsEnabled) {
                AnimatedVisibility(
                    visible = welcomeVisible,
                    enter = fadeIn(animationSpec = tween(400)) + 
                            scaleIn(
                                initialScale = 0.92f,
                                animationSpec = tween(400, easing = FastOutSlowInEasing)
                            )
                ) {
                    cardContent()
                }
            } else {
                cardContent()
            }
        }
        
        // ╨а╨╡╨║╨╛╨╝╨╡╨╜╨┤╨░╤Ж╨╕╤П ╨┤╨╜╤П - ╨╡╤Б╨╗╨╕ ╨╡╤Б╤В╤М ╨┐╨░╨┐╨║╨╕ ╤Б > 1000 ╨┐╨╕╤Б╨╡╨╝
        val foldersOver1000 = folders.filter { 
            it.type in listOf(2, 3, 4, 5) && it.totalCount > 1000 
        }
        if (foldersOver1000.isNotEmpty() && !isRecommendationDismissed) {
            item {
                val folderNames = foldersOver1000.map { folder ->
                    when (folder.type) {
                        2 -> inboxName
                        3 -> draftsName
                        4 -> trashName
                        5 -> sentName
                        else -> folder.displayName
                    }
                }
                val recommendationText = if (folderNames.size == 1) {
                    Strings.cleanupFolderRecommendation(folderNames.first())
                } else {
                    Strings.cleanupFoldersRecommendation(folderNames.joinToString(" ╨╕ "))
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = Strings.recommendationOfDay,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = recommendationText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        IconButton(onClick = { isRecommendationDismissed = true }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = Strings.close,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        
        // ╨С╤Л╤Б╤В╤А╤Л╨╣ ╨┤╨╛╤Б╤В╤Г╨┐ ╨║ ╨┐╨░╨┐╨║╨░╨╝
        if (folders.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = foldersTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onSyncFolders) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(refreshText)
                    }
                }
            }
            
            // ╨Ю╤Б╨╜╨╛╨▓╨╜╤Л╨╡ ╨┐╨░╨┐╨║╨╕ ╨▓ ╤Б╨╡╤В╨║╨╡
            val mainFolders = folders.filter { it.type in listOf(2, 3, 4, 5) }
            
            data class FolderDisplay(val id: String, val name: String, val count: Int, val unreadCount: Int, val type: Int)
            
            // ╨Я╨╛╤А╤П╨┤╨╛╨║: ╨Т╤Е╨╛╨┤╤П╤Й╨╕╨╡, ╨Ю╤В╨┐╤А╨░╨▓╨╗╨╡╨╜╨╜╤Л╨╡, ╨з╨╡╤А╨╜╨╛╨▓╨╕╨║╨╕, ╨г╨┤╨░╨╗╤С╨╜╨╜╤Л╨╡, ╨Ш╨╖╨▒╤А╨░╨╜╨╜╤Л╨╡, ╨Ъ╨╛╨╜╤В╨░╨║╤В╤Л
            val orderedFolders = mutableListOf<FolderDisplay>()
            
            // ╨Т╤Е╨╛╨┤╤П╤Й╨╕╨╡ (type 2)
            mainFolders.find { it.type == 2 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, inboxName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ╨Ю╤В╨┐╤А╨░╨▓╨╗╨╡╨╜╨╜╤Л╨╡ (type 5)
            mainFolders.find { it.type == 5 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, sentName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ╨з╨╡╤А╨╜╨╛╨▓╨╕╨║╨╕ (type 3)
            mainFolders.find { it.type == 3 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, draftsName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ╨г╨┤╨░╨╗╤С╨╜╨╜╤Л╨╡ (type 4)
            mainFolders.find { it.type == 4 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, trashName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ╨Ш╨╖╨▒╤А╨░╨╜╨╜╤Л╨╡
            orderedFolders.add(FolderDisplay("favorites", favoritesName, flaggedCount, 0, -1))
            // ╨Ъ╨╛╨╜╤В╨░╨║╤В╤Л
            orderedFolders.add(FolderDisplay("contacts", contactsName, 0, 0, -2))
            
            val displayFolders = orderedFolders.toList()
            
            val chunkedFolders = displayFolders.chunked(2)
            itemsIndexed(chunkedFolders) { index, rowFolders ->
                val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                // ╨Р╨╜╨╕╨╝╨░╤Ж╨╕╤П ╨┐╨╛╤П╨▓╨╗╨╡╨╜╨╕╤П ╤Б ╨╖╨░╨┤╨╡╤А╨╢╨║╨╛╨╣ ╨┤╨╗╤П ╨║╨░╨╢╨┤╨╛╨╣ ╤Б╤В╤А╨╛╨║╨╕ (╨╡╤Б╨╗╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ ╨▓╨║╨╗╤О╤З╨╡╨╜╤Л)
                var visible by remember { mutableStateOf(!animationsEnabled) }
                LaunchedEffect(animationsEnabled) {
                    if (animationsEnabled) {
                        kotlinx.coroutines.delay(index * 80L)
                        visible = true
                    } else {
                        visible = true
                    }
                }
                
                if (animationsEnabled) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(300)) + 
                                slideInVertically(
                                    initialOffsetY = { it / 2 },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (rowFolders.size == 1) Arrangement.Center else Arrangement.spacedBy(12.dp)
                        ) {
                            rowFolders.forEach { folder ->
                                FolderCardDisplay(
                                    id = folder.id,
                                    name = folder.name,
                                    count = folder.count,
                                    unreadCount = folder.unreadCount,
                                    type = folder.type,
                                    onClick = { 
                                        if (folder.id == "contacts") onContactsClick() 
                                        else onFolderClick(folder.id) 
                                    },
                                    modifier = if (rowFolders.size == 1) {
                                        Modifier.fillMaxWidth(0.48f)
                                    } else {
                                        Modifier.weight(1f)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // ╨С╨╡╨╖ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ тАФ ╨┐╤А╨╛╤Б╤В╨╛ ╨┐╨╛╨║╨░╨╖╤Л╨▓╨░╨╡╨╝
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (rowFolders.size == 1) Arrangement.Center else Arrangement.spacedBy(12.dp)
                    ) {
                        rowFolders.forEach { folder ->
                            FolderCardDisplay(
                                id = folder.id,
                                name = folder.name,
                                count = folder.count,
                                unreadCount = folder.unreadCount,
                                type = folder.type,
                                onClick = { 
                                    if (folder.id == "contacts") onContactsClick() 
                                    else onFolderClick(folder.id) 
                                },
                                modifier = if (rowFolders.size == 1) {
                                    Modifier.fillMaxWidth(0.48f)
                                } else {
                                    Modifier.weight(1f)
                                }
                            )
                        }
                    }
                }
            }
        } else if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            Strings.loadingFolders,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOff,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            Strings.noFoldersFound,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            Strings.tapToSync,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = onSyncFolders) {
                            Icon(Icons.Default.Sync, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.synchronize)
                        }
                    }
                }
            }
        }
        
        // ╨б╨╛╨▓╨╡╤В╤Л ╨┐╨╛ ╤А╨░╨▒╨╛╤В╨╡ ╤Б ╨┐╤А╨╕╨╗╨╛╨╢╨╡╨╜╨╕╨╡╨╝
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tipsExpanded = !tipsExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ╨Р╨╜╨╕╨╝╨╕╤А╨╛╨▓╨░╨╜╨╜╨░╤П ╨╗╨░╨╝╨┐╨╛╤З╨║╨░
                        val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                        
                        val bulbScale: Float
                        val bulbAlpha: Float
                        
                        if (animationsEnabled) {
                            val infiniteTransition = rememberInfiniteTransition(label = "bulb")
                            bulbScale = infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.15f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "bulbScale"
                            ).value
                            bulbAlpha = infiniteTransition.animateFloat(
                                initialValue = 0.7f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "bulbAlpha"
                            ).value
                        } else {
                            bulbScale = 1f
                            bulbAlpha = 1f
                        }
                        
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = Color(0xFFFFB300).copy(alpha = bulbAlpha),
                            modifier = Modifier
                                .size(24.dp)
                                .scale(bulbScale)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = Strings.tipsTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (tipsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = tipsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TipItem(
                                icon = Icons.Default.Notifications,
                                text = Strings.tipNotification,
                                iconColor = Color(0xFFFF9800),
                                iconBackgroundColor = Color(0xFFFFF3E0)
                            )
                            TipItem(
                                icon = Icons.Default.BatteryChargingFull,
                                text = Strings.tipBattery,
                                iconColor = Color(0xFF4CAF50),
                                iconBackgroundColor = Color(0xFFE8F5E9)
                            )
                            TipItem(
                                icon = Icons.Default.Lock,
                                text = Strings.tipCertificate,
                                iconColor = Color(0xFF9C27B0),
                                iconBackgroundColor = Color(0xFFF3E5F5)
                            )
                            TipItem(
                                icon = Icons.Default.Info,
                                text = Strings.tipBeta,
                                iconColor = Color(0xFF2196F3),
                                iconBackgroundColor = Color(0xFFE3F2FD)
                            )
                        }
                    }
                }
            }
        }
        
        // ╨Ю ╨┐╤А╨╕╨╗╨╛╨╢╨╡╨╜╨╕╨╕ тАФ ╨║╨╛╨╝╨┐╨░╨║╤В╨╜╤Л╨╣ ╤Б╨╛╨▓╤А╨╡╨╝╨╡╨╜╨╜╤Л╨╣ ╨▓╨╕╨┤
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { aboutExpanded = !aboutExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ╨Р╨╜╨╕╨╝╨╕╤А╨╛╨▓╨░╨╜╨╜╤Л╨╣ ╨║╨╛╨╜╨▓╨╡╤А╤В╨╕╨║ ╤Б ╨│╤А╨░╨┤╨╕╨╡╨╜╤В╨╛╨╝
                        val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
                        val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                        
                        val envelopeScale: Float
                        val envelopeRotation: Float
                        
                        if (animationsEnabled) {
                            val infiniteTransition = rememberInfiniteTransition(label = "envelope")
                            envelopeScale = infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.08f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "envelopeScale"
                            ).value
                            envelopeRotation = infiniteTransition.animateFloat(
                                initialValue = -3f,
                                targetValue = 3f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(2000, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "envelopeRotation"
                            ).value
                        } else {
                            envelopeScale = 1f
                            envelopeRotation = 0f
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .scale(envelopeScale)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            colorTheme.gradientStart,
                                            colorTheme.gradientEnd
                                        )
                                    )
                                )
                                .shadow(
                                    elevation = 4.dp,
                                    shape = MaterialTheme.shapes.medium,
                                    ambientColor = colorTheme.gradientStart.copy(alpha = 0.3f),
                                    spotColor = colorTheme.gradientStart.copy(alpha = 0.3f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Email,
                                null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(26.dp)
                                    .rotate(envelopeRotation)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Exchange Mail Client",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "v1.1.2",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (aboutExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = aboutExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                        ) {
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = Strings.appDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // ╨Т╨╛╨╖╨╝╨╛╨╢╨╜╨╛╤Б╤В╨╕ ╨▓ ╨▓╨╕╨┤╨╡ ╤З╨╕╨┐╨╛╨▓
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FeatureChip(Strings.featureSync)
                                FeatureChip(Strings.featureAttachments)
                                FeatureChip(Strings.featureSend)
                                FeatureChip(Strings.featureSearch)
                                FeatureChip(Strings.featureFolders)
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // ╨а╨░╨╖╤А╨░╨▒╨╛╤В╤З╨╕╨║
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${Strings.developerLabel} DedovMosol",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { uriHandler.openUri("mailto:andreyid@outlook.com") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Email,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "andreyid@outlook.com",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            // Telegram ╨║╨░╨╜╨░╨╗
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { uriHandler.openUri("https://t.me/i_wantout") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Telegram: @i_wantout",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "┬й 2025",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // ╨Ъ╨╜╨╛╨┐╨║╨░ "╨Ю╨╖╨╜╨░╨║╨╛╨╝╨╕╤В╤М╤Б╤П ╤Б ╤А╨░╨╖╨▓╨╕╤В╨╕╨╡╨╝ ╨┐╤А╨╛╨│╤А╨░╨╝╨╝╤Л" ╤Б ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╡╨╣
        item {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val isRu = LocalLanguage.current == AppLanguage.RUSSIAN
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            val changelogUrl = if (isRu) 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_RU.md"
            else 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_EN.md"
            
            // ╨Р╨╜╨╕╨╝╨░╤Ж╨╕╤П ╨┐╤Г╨╗╤М╤Б╨░╤Ж╨╕╨╕ (╤В╨╛╨╗╤М╨║╨╛ ╨╡╤Б╨╗╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ ╨▓╨║╨╗╤О╤З╨╡╨╜╤Л)
            val pulseScale: Float = if (animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "changelogPulse")
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.02f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "changelogScale"
                ).value
            } else {
                1f
            }
            
            // ╨Р╨╜╨╕╨╝╨░╤Ж╨╕╤П ╤Б╨▓╨╡╤З╨╡╨╜╨╕╤П ╨│╤А╨░╨╜╨╕╤Ж╤Л
            val borderAlpha: Float = if (animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "changelogBorder")
                infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "borderAlpha"
                ).value
            } else {
                1f
            }
            
            OutlinedButton(
                onClick = { uriHandler.openUri(changelogUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .scale(pulseScale),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = com.iwo.mailclient.ui.theme.LocalColorTheme.current.gradientStart
                ),
                border = BorderStroke(
                    1.5.dp, 
                    com.iwo.mailclient.ui.theme.LocalColorTheme.current.gradientStart.copy(alpha = borderAlpha)
                )
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(Strings.viewChangelog)
            }
        }
        
        // ╨Ъ╨╜╨╛╨┐╨║╨░ ╨┐╨╛╨╢╨╡╤А╤В╨▓╨╛╨▓╨░╨╜╨╕╨╣ ╤Б ╨┐╤Г╨╗╤М╤Б╨╕╤А╤Г╤О╤Й╨╡╨╣ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╡╨╣
        item {
            var showDonateDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val accountCopiedText = Strings.accountCopied
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            
            // ╨Я╤Г╨╗╤М╤Б╨╕╤А╤Г╤О╤Й╨░╤П ╨░╨╜╨╕╨╝╨░╤Ж╨╕╤П (╤В╨╛╨╗╤М╨║╨╛ ╨╡╤Б╨╗╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ ╨▓╨║╨╗╤О╤З╨╡╨╜╤Л)
            val pulseScale: Float = if (animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.03f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                ).value
            } else {
                1f
            }
            
            if (showDonateDialog) {
                com.iwo.mailclient.ui.theme.ScaledAlertDialog(
                    onDismissRequest = { showDonateDialog = false },
                    icon = { Icon(Icons.Default.Favorite, null, tint = Color(0xFFE91E63)) },
                    title = { 
                        Text(
                            Strings.supportDeveloper,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        ) 
                    },
                    text = {
                        Column {
                            Text(
                                Strings.supportText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    DonateInfoRow(Strings.recipient, "╨Ф╨╛╨┤╨╛╨╜╨╛╨▓ ╨Р╨╜╨┤╤А╨╡╨╣ ╨Ш╨│╨╛╤А╨╡╨▓╨╕╤З")
                                    // ╨Э╨╛╨╝╨╡╤А ╤Б╤З╤С╤В╨░ ╤Б ╨┐╨╡╤А╨╡╨╜╨╛╤Б╨╛╨╝
                                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(
                                            Strings.accountNumber,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            softWrap = false,
                                            modifier = Modifier.widthIn(min = 70.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        SelectionContainer {
                                            Text(
                                                "4081 7810 3544 0529 6071",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    DonateInfoRow(Strings.bank, "╨Я╨╛╨▓╨╛╨╗╨╢╤Б╨║╨╕╨╣ ╨▒╨░╨╜╨║ ╨Я╨Р╨Ю ╨б╨▒╨╡╤А╨▒╨░╨╜╨║")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { showDonateDialog = false }) {
                                Text(Strings.closeDialog)
                            }
                            TextButton(
                                onClick = {
                                    // ╨Ъ╨╛╨┐╨╕╤А╤Г╨╡╨╝ ╨╜╨╛╨╝╨╡╤А ╤Б╤З╤С╤В╨░
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Account", "40817810354405296071")
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, accountCopiedText, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(Strings.copyAccount)
                            }
                        }
                    },
                    dismissButton = { }
                )
            }
            
            Button(
                onClick = { showDonateDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .scale(pulseScale),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE91E63)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Favorite, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(Strings.supportDeveloper, fontWeight = FontWeight.SemiBold)
            }
        }
        
        // ╨Ю╤В╤Б╤В╤Г╨┐ ╤Б╨╜╨╕╨╖╤Г ╨┤╨╗╤П FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun FeatureChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun DonateInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier.widthIn(min = 70.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCard(
    folder: FolderEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FolderCardDisplay(
        id = folder.id,
        name = folder.displayName,
        count = folder.totalCount,
        unreadCount = folder.unreadCount,
        type = folder.type,
        onClick = onClick,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCardDisplay(
    id: String,
    name: String,
    count: Int,
    unreadCount: Int = 0,
    type: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // ╨Р╨╜╨╕╨╝╨░╤Ж╨╕╤П ╨╝╨░╤Б╤И╤В╨░╨▒╨░ ╨┐╤А╨╕ ╨╜╨░╨╢╨░╤В╨╕╨╕ (╤В╨╛╨╗╤М╨║╨╛ ╨╡╤Б╨╗╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ ╨▓╨║╨╗╤О╤З╨╡╨╜╤Л)
    val scale by animateFloatAsState(
        targetValue = if (animationsEnabled && isPressed) 0.96f else 1f,
        animationSpec = if (animationsEnabled) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        } else {
            snap()
        },
        label = "scale"
    )
    
    // ╨Р╨╜╨╕╨╝╨░╤Ж╨╕╤П ╤В╨╡╨╜╨╕ ╨┐╤А╨╕ ╨╜╨░╨╢╨░╤В╨╕╨╕
    val elevation by animateDpAsState(
        targetValue = if (animationsEnabled && isPressed) 1.dp else 4.dp,
        animationSpec = if (animationsEnabled) tween(150) else snap(),
        label = "elevation"
    )
    
    // ╨Я╤Г╨╗╤М╤Б╨░╤Ж╨╕╤П ╨╕ ╨┐╨╛╨║╨░╤З╨╕╨▓╨░╨╜╨╕╨╡ ╨╕╨║╨╛╨╜╨║╨╕ (╤В╨╛╨╗╤М╨║╨╛ ╨╡╤Б╨╗╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ ╨▓╨║╨╗╤О╤З╨╡╨╜╤Л)
    val iconScale: Float
    val iconRotation: Float
    
    if (animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "icon")
        iconScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconScale"
        ).value
        
        iconRotation = infiniteTransition.animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconRotation"
        ).value
    } else {
        iconScale = 1f
        iconRotation = 0f
    }
    
    // ╨Ю╨┐╤А╨╡╨┤╨╡╨╗╤П╨╡╨╝ ╤Ж╨▓╨╡╤В╨░ ╨┤╨╗╤П ╨║╨░╨╢╨┤╨╛╨│╨╛ ╤В╨╕╨┐╨░ ╨┐╨░╨┐╨║╨╕ тАФ ╨╡╨┤╨╕╨╜╤Л╨╣ ╤Б╨╛╨▓╤А╨╡╨╝╨╡╨╜╨╜╤Л╨╣ ╤Б╤В╨╕╨╗╤М
    data class FolderColors(
        val icon: ImageVector,
        val gradientColors: List<Color>
    )
    
    val folderColors = when (type) {
        2 -> FolderColors(
            Icons.Default.Inbox, 
            listOf(Color(0xFF5C6BC0), Color(0xFF3949AB)) // Indigo
        )
        3 -> FolderColors(
            Icons.Default.Drafts, 
            listOf(Color(0xFF78909C), Color(0xFF546E7A)) // Blue Grey
        )
        4 -> FolderColors(
            Icons.Default.Delete, 
            listOf(Color(0xFFEF5350), Color(0xFFE53935)) // Red
        )
        5 -> FolderColors(
            Icons.Default.Send, 
            listOf(Color(0xFF7E57C2), Color(0xFF5E35B1)) // Deep Purple
        )
        6 -> FolderColors(
            Icons.Default.Outbox, 
            listOf(Color(0xFF26A69A), Color(0xFF00897B)) // Teal
        )
        -1 -> FolderColors(
            Icons.Default.Star, 
            listOf(Color(0xFFFFCA28), Color(0xFFFFA000)) // Amber
        )
        -2 -> FolderColors(
            Icons.Default.Contacts, 
            listOf(Color(0xFF4FC3F7), Color(0xFF29B6F6)) // Light Blue
        )
        else -> FolderColors(
            Icons.Default.Folder, 
            listOf(Color(0xFF90A4AE), Color(0xFF78909C)) // Blue Grey Light
        )
    }
    
    Card(
        onClick = onClick,
        modifier = modifier
            .height(80.dp)
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        interactionSource = interactionSource
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(folderColors.gradientColors)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ╨Ш╨║╨╛╨╜╨║╨░ ╤Б ╨┐╨╛╨╗╤Г╨┐╤А╨╛╨╖╤А╨░╤З╨╜╤Л╨╝ ╤Д╨╛╨╜╨╛╨╝ ╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╡╨╣
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .scale(if (animationsEnabled) iconScale else 1f)
                        .rotate(if (animationsEnabled) iconRotation else 0f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        folderColors.icon, 
                        null, 
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // ╨Ф╨╗╤П ╨║╨╛╨╜╤В╨░╨║╤В╨╛╨▓ ╨╜╨╡ ╨┐╨╛╨║╨░╨╖╤Л╨▓╨░╨╡╨╝ "╨┐╨╕╤Б╨╡╨╝"
                    if (type != -2) {
                        Text(
                            text = when {
                                count > 0 -> "$count ${Strings.emailsCount}"
                                else -> Strings.empty
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                
                // Badge ╤Б ╨╜╨╡╨┐╤А╨╛╤З╨╕╤В╨░╨╜╨╜╤Л╨╝╨╕ тАФ ╤Б ╨┐╤Г╨╗╤М╤Б╨░╤Ж╨╕╨╡╨╣ (╨╡╤Б╨╗╨╕ ╨░╨╜╨╕╨╝╨░╤Ж╨╕╨╕ ╨▓╨║╨╗╤О╤З╨╡╨╜╤Л)
                if (unreadCount > 0) {
                    val badgeScale: Float = if (animationsEnabled) {
                        val badgeTransition = rememberInfiniteTransition(label = "badge")
                        badgeTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "badgeScale"
                        ).value
                    } else {
                        1f
                    }
                    
                    Badge(
                        modifier = Modifier.scale(badgeScale),
                        containerColor = folderColors.gradientColors.first(),
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    accountName: String,
    accountColor: Int,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    // ╨У╤А╨░╨┤╨╕╨╡╨╜╤В╨╜╤Л╨╣ ╤Д╨╛╨╜ ╨┤╨╗╤П ╤В╨╛╨┐╨▒╨░╤А╨░
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        colorTheme.gradientStart,
                        colorTheme.gradientEnd
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.White.copy(alpha = 0.95f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSearchClick)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, Strings.menu, tint = colorTheme.primaryLight)
                }
                
                Text(
                    text = Strings.searchInMail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(accountColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accountName.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun DrawerContent(
    accounts: List<AccountEntity>,
    activeAccount: AccountEntity?,
    folders: List<FolderEntity>,
    flaggedCount: Int,
    showAccountPicker: Boolean,
    onToggleAccountPicker: () -> Unit,
    onAccountSelected: (AccountEntity) -> Unit,
    onAddAccount: () -> Unit,
    onFolderSelected: (FolderEntity) -> Unit,
    onFavoritesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onContactsClick: () -> Unit = {},
    onCreateFolder: () -> Unit = {},
    onFolderLongClick: (FolderEntity) -> Unit = {}
) {
    LazyColumn {
        // ╨Ч╨░╨│╨╛╨╗╨╛╨▓╨╛╨║ ╤Б ╨░╨║╨║╨░╤Г╨╜╤В╨╛╨╝
        item {
            DrawerHeader(
                account = activeAccount,
                showPicker = showAccountPicker,
                onToggle = onToggleAccountPicker
            )
        }
        
        // ╨Т╤Л╨▒╨╛╤А ╨░╨║╨║╨░╤Г╨╜╤В╨░
        if (showAccountPicker) {
            items(accounts) { account ->
                AccountItem(
                    account = account,
                    isActive = account.id == activeAccount?.id,
                    onClick = { onAccountSelected(account) }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.addAccount) },
                    leadingContent = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.clickable(onClick = onAddAccount)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        
        // ╨Я╨░╨┐╨║╨╕ - ╤Б╨╜╨░╤З╨░╨╗╨░ ╨╛╤Б╨╜╨╛╨▓╨╜╤Л╨╡ (╨Т╤Е╨╛╨┤╤П╤Й╨╕╨╡, ╨з╨╡╤А╨╜╨╛╨▓╨╕╨║╨╕, ╨г╨┤╨░╨╗╤С╨╜╨╜╤Л╨╡, ╨Ю╤В╨┐╤А╨░╨▓╨╗╨╡╨╜╨╜╤Л╨╡, ╨Ш╤Б╤Е╨╛╨┤╤П╤Й╨╕╨╡, ╨б╨┐╨░╨╝)
        val mainFolderTypes = listOf(2, 3, 4, 5, 6, 11)
        val mainFolders = folders.filter { it.type in mainFolderTypes }
            .sortedBy { mainFolderTypes.indexOf(it.type) }
        
        items(mainFolders) { folder ->
            FolderItem(
                folder = folder,
                onClick = { onFolderSelected(folder) }
            )
        }
        
        // ╨Ш╨╖╨▒╤А╨░╨╜╨╜╤Л╨╡ - ╨┐╨╛╤Б╨╗╨╡ ╨Ш╤Б╤Е╨╛╨┤╤П╤Й╨╕╤Е
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
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFB300))
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
        
        // ╨Ю╤Б╤В╨░╨╗╤М╨╜╤Л╨╡ ╨┐╨░╨┐╨║╨╕ (╨║╤А╨╛╨╝╨╡ Contacts - type 9, ╤Г ╨╜╨░╤Б ╤Б╨▓╨╛╨╣ ╤Н╨║╤А╨░╨╜ ╨║╨╛╨╜╤В╨░╨║╤В╨╛╨▓)
        val hiddenFolderTypes = listOf(9) // Contacts
        val otherFolders = folders.filter { it.type !in mainFolderTypes && it.type !in hiddenFolderTypes }
        if (otherFolders.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            items(otherFolders) { folder ->
                FolderItem(
                    folder = folder,
                    onClick = { onFolderSelected(folder) },
                    onLongClick = { onFolderLongClick(folder) }
                )
            }
        }
        
        // ╨б╨╛╨╖╨┤╨░╤В╤М ╨┐╨░╨┐╨║╤Г
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(Strings.createFolder) },
                leadingContent = { Icon(Icons.Default.CreateNewFolder, null) },
                modifier = Modifier.clickable(onClick = onCreateFolder)
            )
        }
        
        // ╨Ъ╨╛╨╜╤В╨░╨║╤В╤Л
        item {
            ListItem(
                headlineContent = { Text(Strings.contacts) },
                leadingContent = { Icon(Icons.Default.People, null) },
                modifier = Modifier.clickable(onClick = onContactsClick)
            )
        }
        
        // ╨Э╨░╤Б╤В╤А╨╛╨╣╨║╨╕
        item {
            ListItem(
                headlineContent = { Text(Strings.settings) },
                leadingContent = { Icon(Icons.Default.Settings, null) },
                modifier = Modifier.clickable(onClick = onSettingsClick)
            )
        }
    }
}

@Composable
private fun DrawerHeader(
    account: AccountEntity?,
    showPicker: Boolean,
    onToggle: () -> Unit
) {
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    // ╨У╤А╨░╨┤╨╕╨╡╨╜╤В╨╜╤Л╨╣ ╤Е╨╡╨┤╨╡╤А ╨║╨░╨║ ╨▓ SetupScreen
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
                    .background(Color(account?.color ?: 0xFF1976D2.toInt())),
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
                    imageVector = if (showPicker) Icons.Default.KeyboardArrowUp 
                                  else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun AccountItem(
    account: AccountEntity,
    isActive: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(account.displayName) },
        supportingContent = { Text(account.email) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(account.color)),
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
                Icon(Icons.Default.Check, "╨Р╨║╤В╨╕╨▓╨╜╤Л╨╣", tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    folder: FolderEntity,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val icon = when (folder.type) {
        2 -> Icons.Default.Inbox
        3 -> Icons.Default.Drafts
        4 -> Icons.Default.Delete
        5 -> Icons.Default.Send
        6 -> Icons.Default.Outbox
        7 -> Icons.Default.Task
        8 -> Icons.Default.CalendarMonth
        9 -> Icons.Default.Contacts
        11 -> Icons.Default.Report // Junk/Spam
        else -> Icons.Default.Folder
    }
    
    // ╨ж╨▓╨╡╤В ╨╕╨║╨╛╨╜╨║╨╕ - ╨║╤А╨░╤Б╨╜╤Л╨╣ ╨┤╨╗╤П ╤Б╨┐╨░╨╝╨░ ╨╕ ╨║╨╛╤А╨╖╨╕╨╜╤Л
    val iconTint = when (folder.type) {
        4 -> MaterialTheme.colorScheme.error // ╨г╨┤╨░╨╗╤С╨╜╨╜╤Л╨╡
        11 -> Color(0xFFE53935) // ╨б╨┐╨░╨╝ - ╨║╤А╨░╤Б╨╜╤Л╨╣
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    // ╨Ы╨╛╨║╨░╨╗╨╕╨╖╨╛╨▓╨░╨╜╨╜╨╛╨╡ ╨╜╨░╨╖╨▓╨░╨╜╨╕╨╡ ╨┐╨░╨┐╨║╨╕
    val displayName = Strings.getFolderName(folder.type, folder.displayName)
    
    // ╨б╨╕╤Б╤В╨╡╨╝╨╜╤Л╨╡ ╨┐╨░╨┐╨║╨╕ ╨╜╨╡╨╗╤М╨╖╤П ╤Г╨┤╨░╨╗╤П╤В╤М (╨▓╨║╨╗╤О╤З╨░╤П ╨б╨┐╨░╨╝)
    val isSystemFolder = folder.type in listOf(2, 3, 4, 5, 6, 11)
    
    // ╨Ш╤Б╨┐╨╛╨╗╤М╨╖╤Г╨╡╨╝ Surface ╤Б combinedClickable ╨▓╨╝╨╡╤Б╤В╨╛ NavigationDrawerItem
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


@Composable
private fun TipItem(
    icon: ImageVector,
    text: String,
    iconColor: Color = MaterialTheme.colorScheme.tertiary,
    iconBackgroundColor: Color = MaterialTheme.colorScheme.tertiaryContainer
) {
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    
    // ╨Р╨╜╨╕╨╝╨░╤Ж╨╕╤П ╨┐╤Г╨╗╤М╤Б╨░╤Ж╨╕╨╕ ╨╕╨║╨╛╨╜╨║╨╕
    val iconScale: Float
    if (animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "tipIcon")
        iconScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "tipIconScale"
        ).value
    } else {
        iconScale = 1f
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .scale(iconScale)
                .clip(CircleShape)
                .background(iconBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = iconColor
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
