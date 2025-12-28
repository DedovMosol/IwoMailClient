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
import androidx.compose.material3.*
import com.iwo.mailclient.ui.theme.AppIcons
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
 * ¦Ó¦¬¦-¦-¦-¦¬TÌ¦-TË¦¦ ¦¦¦-¦-TÂTÀ¦-¦¬¦¬¦¦TÀ ¦-¦-TÇ¦-¦¬TÌ¦-¦-¦¦ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬
 * ¦ØTÁ¦¬¦-¦¬TÌ¦¬TÃ¦¦TÂ TÁ¦-¦-TÁTÂ¦-¦¦¦-¦-TË¦¦ scope TÇTÂ¦-¦-TË TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÏ ¦-¦¦ ¦¬TÀ¦¦TÀTË¦-¦-¦¬¦-TÁTÌ ¦¬TÀ¦¬ ¦¬¦-¦-¦-TÀ¦-TÂ¦¦ TÍ¦¦TÀ¦-¦-¦-
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
        // ¦ÕTÁ¦¬¦¬ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÏ TÃ¦¦¦¦ ¦-TË¦¬¦- ¦¬¦¬¦¬ TÃ¦¦¦¦ ¦¬¦-¦¬TÃTÉ¦¦¦-¦- òÀÔ ¦¬TÀ¦-¦¬TÃTÁ¦¦¦-¦¦¦-
        if (syncDone || syncStarted) {
            return
        }
        
        syncStarted = true
        isSyncing = true
        
        syncJob = syncScope.launch {
            try {
                delay(100) // ¦Ý¦¦¦-¦-¦¬TÌTÈ¦-TÏ ¦¬¦-¦+¦¦TÀ¦¦¦¦¦- TÇTÂ¦-¦-TË UI TÃTÁ¦¬¦¦¦¬ ¦-TÂTÀ¦¬TÁ¦-¦-¦-TÂTÌTÁTÏ
                
                // ¦â¦-¦¦¦-¦-TÃTÂ ¦-¦- ¦-TÁTÎ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÎ - 5 ¦-¦¬¦-TÃTÂ
                withTimeoutOrNull(300_000L) {
                    // ¦á¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦¬TÀTÃ¦¦¦- ¦¬¦-¦¬¦¦¦¬
                    withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    
                    delay(200)
                    
                    // ¦á¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦¬TÀTÃ¦¦¦- ¦Ò¦á¦Õ ¦¬¦-¦¬¦¦¦¬ TÁ ¦¬¦¬TÁTÌ¦-¦-¦-¦¬ ¦ß¦Ð¦à¦Ð¦Û¦Û¦Õ¦Û¦ì¦Ý¦Þ
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
     * ¦á¦-TÀ¦-TÁ TÁ¦-TÁTÂ¦-TÏ¦-¦¬TÏ (¦+¦¬TÏ TÂ¦¦TÁTÂ¦-¦- ¦¬¦¬¦¬ ¦¬TÀ¦¬ TÁ¦-¦¦¦-¦¦ ¦-¦¦¦¦¦-TÃ¦-TÂ¦-)
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
    // ¦ä¦¬¦-¦¦ TÇTÂ¦- ¦+¦-¦-¦-TË¦¦ ¦¬¦-¦¦TÀTÃ¦¦¦¦¦-TË (¦+¦¬TÏ ¦¬TÀ¦¦¦+¦-TÂ¦-TÀ¦-TÉ¦¦¦-¦¬TÏ ¦-¦¦TÀTÆ¦-¦-¦¬TÏ)
    var dataLoaded by remember { mutableStateOf(false) }
    
    // ¦á¦-TÁTÂ¦-TÏ¦-¦¬¦¦ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬ ¦¬¦¬ ¦¦¦¬¦-¦-¦-¦¬TÌ¦-¦-¦¦¦- ¦¦¦-¦-TÂTÀ¦-¦¬¦¬¦¦TÀ¦-
    val isSyncing = InitialSyncController.isSyncing
    val initialSyncDone = InitialSyncController.syncDone
    
    // ¦Ô¦¬¦-¦¬¦-¦¦ TÁ¦-¦¬¦+¦-¦-¦¬TÏ ¦¬¦-¦¬¦¦¦¬
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    
    // ¦Ô¦¬¦-¦¬¦-¦¦ TÃ¦+¦-¦¬¦¦¦-¦¬TÏ ¦¬¦-¦¬¦¦¦¬
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var isDeletingFolder by remember { mutableStateOf(false) }
    
    // ¦Ô¦¬¦-¦¬¦-¦¦ ¦¬¦¦TÀ¦¦¦¬¦-¦¦¦-¦-¦-¦-¦-¦¬TÏ ¦¬¦-¦¬¦¦¦¬
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var isRenamingFolder by remember { mutableStateOf(false) }
    
    // ¦Ü¦¦¦-TÎ ¦+¦¦¦¦TÁTÂ¦-¦¬¦¦ TÁ ¦¬¦-¦¬¦¦¦-¦¦ (¦¬TÀ¦¬ ¦+¦-¦¬¦¦¦-¦- ¦-¦-¦¦¦-TÂ¦¬¦¬)
    var folderForMenu by remember { mutableStateOf<FolderEntity?>(null) }
    
    // ¦ä¦¬¦-¦¦ TÇTÂ¦- ¦¬¦¦TÀ¦-TË¦¦ ¦-¦¦¦¦¦-TÃ¦-TÂ TÃ¦¦¦¦ ¦-¦¦TÂ¦¬¦-¦¬TÀ¦-¦-¦-¦-
    var firstAccountActivated by rememberSaveable { mutableStateOf(false) }
    
    // ¦ÕTÁ¦¬¦¬ ¦-¦¦TÂ ¦-¦¦TÂ¦¬¦-¦-¦-¦¦¦- ¦-¦¦¦¦¦-TÃ¦-TÂ¦- ¦-¦- ¦¦TÁTÂTÌ ¦-¦¦¦¦¦-TÃ¦-TÂTË - ¦-¦¦TÂ¦¬¦-¦¬TÀTÃ¦¦¦- ¦¬¦¦TÀ¦-TË¦¦ (TÂ¦-¦¬TÌ¦¦¦- ¦-¦+¦¬¦- TÀ¦-¦¬)
    LaunchedEffect(accounts.isNotEmpty(), activeAccount == null) {
        if (activeAccount == null && accounts.isNotEmpty() && !firstAccountActivated) {
            firstAccountActivated = true
            accountRepo.setActiveAccount(accounts.first().id)
        }
    }
    
    // ¦×¦-¦¦TÀTÃ¦¦¦-¦¦¦- ¦¬¦-¦¬¦¦¦¬ ¦¬TÀ¦¬ TÁ¦-¦¦¦-¦¦ ¦-¦¦¦¦¦-TÃ¦-TÂ¦-
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        mailRepo.getFolders(accountId).collect { 
            folders = it
            dataLoaded = true
        }
    }
    
    // ¦×¦-¦¦TÀTÃ¦¦¦-¦¦¦- TÁTÇTÑTÂTÇ¦¬¦¦ ¦¬¦¬¦-TÀ¦-¦-¦-¦-¦¦¦-
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        mailRepo.getFlaggedCount(accountId).collect { flaggedCount = it }
    }
    
    // ¦Ð¦-TÂ¦-¦-¦-TÂ¦¬TÇ¦¦TÁ¦¦¦-TÏ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÏ òÀÔ ¦Þ¦Ô¦Ø¦Ý ¦à¦Ð¦× ¦¬TÀ¦¬ ¦¬¦-¦¬TÃTÁ¦¦¦¦ ¦¬TÀ¦¬¦¬¦-¦¦¦¦¦-¦¬TÏ
    // ¦ØTÁ¦¬¦-¦¬TÌ¦¬TÃ¦¦¦- ¦¦¦¬¦-¦-¦-¦¬TÌ¦-TË¦¦ ¦¦¦-¦-TÂTÀ¦-¦¬¦¬¦¦TÀ TÇTÂ¦-¦-TË TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÏ ¦-¦¦ ¦¬TÀ¦¦TÀTË¦-¦-¦¬¦-TÁTÌ ¦¬TÀ¦¬ ¦¬¦-¦-¦-TÀ¦-TÂ¦¦ TÍ¦¦TÀ¦-¦-¦-
    LaunchedEffect(activeAccount?.id) {
        val account = activeAccount ?: return@LaunchedEffect
        InitialSyncController.startSyncIfNeeded(context, account.id, mailRepo, settingsRepo)
    }
    
    // ¦ß¦¦TÀ¦-¦¬TÇ¦-¦-TÏ ¦¬TÀ¦-¦-¦¦TÀ¦¦¦- - ¦¦TÁTÂTÌ ¦¬¦¬ ¦-¦¦¦¦¦-TÃ¦-TÂTË (TÂ¦-¦¬TÌ¦¦¦- ¦-¦+¦¬¦- TÀ¦-¦¬)
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
    
    // ¦ÞTÂTÁ¦¬¦¦¦¦¦¬¦-¦-¦¦¦- TÃ¦+¦-¦¬¦¦¦-¦¬¦¦ ¦-TÁ¦¦TÅ ¦-¦¦¦¦¦-TÃ¦-TÂ¦-¦- (TÂ¦-¦¬TÌ¦¦¦- ¦¬¦-TÁ¦¬¦¦ ¦¬¦-¦¦TÀTÃ¦¬¦¦¦¬ ¦+¦-¦-¦-TËTÅ ¦¬¦¬ Flow)
    LaunchedEffect(accounts) {
        // ¦Ö¦+TÑ¦- ¦¬¦-¦¦¦- Flow ¦¬¦-¦¦TÀTÃ¦¬¦¬TÂ ¦+¦-¦-¦-TË¦¦ (¦-¦¦ TÀ¦¦¦-¦¦¦¬TÀTÃ¦¦¦- ¦-¦- ¦-¦-TÇ¦-¦¬TÌ¦-TË¦¦ emptyList)
        if (!accountsLoaded || !initialCheckDone) return@LaunchedEffect
        
        // ¦Ô¦-TÑ¦- ¦-TÀ¦¦¦-TÏ Flow ¦¬¦-¦¦TÀTÃ¦¬¦¬TÂTÌ ¦+¦-¦-¦-TË¦¦
        kotlinx.coroutines.delay(500)
        
        // ¦ßTÀ¦-¦-¦¦TÀTÏ¦¦¦- ¦¦TÉTÑ TÀ¦-¦¬ ¦-¦-¦¬TÀTÏ¦-TÃTÎ ¦¬¦¬ ¦Ñ¦Ô
        val actualCount = accountRepo.getAccountCount()
        if (actualCount == 0) {
            onNavigateToSetup()
        }
    }
    
    // ¦Ý¦¦¦¬¦-¦-¦¬TÁ¦¬¦-TË¦¦ scope ¦+¦¬TÏ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬ (¦-¦¦ ¦-TÂ¦-¦¦¦-TÏ¦¦TÂTÁTÏ ¦¬TÀ¦¬ ¦-¦-¦-¦¬¦¦¦-TÆ¦¬¦¬)
    val syncScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    
    fun syncFolders() {
        activeAccount?.let { account ->
            syncScope.launch {
                isLoading = true
                
                try {
                    // ¦â¦-¦¦¦-¦-TÃTÂ ¦-¦- ¦-TÁTÎ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÎ - 60 TÁ¦¦¦¦TÃ¦-¦+
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                        // ¦á¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦¬TÀTÃ¦¦¦- ¦¬¦-¦¬¦¦¦¬
                        val result = withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                        
                        if (result is com.iwo.mailclient.eas.EasResult.Error) {
                            return@withTimeoutOrNull
                        }
                        
                        // ¦á¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦¬TÀTÃ¦¦¦- ¦¬¦¬TÁTÌ¦-¦- ¦-¦- ¦Ò¦á¦Õ¦å ¦¬¦-¦¬¦¦¦-TÅ TÁ ¦¬¦¬TÁTÌ¦-¦-¦-¦¬
                        val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                        val currentFolders = withContext(Dispatchers.IO) {
                            com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                .folderDao().getFoldersByAccountList(account.id)
                        }
                        val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                        
                        // ¦á¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦¬TÀTÃ¦¦¦- ¦¬¦-TÀ¦-¦¬¦¬¦¦¦¬TÌ¦-¦- TÁ TÂ¦-¦¦¦-¦-TÃTÂ¦-¦- ¦-¦- ¦¦¦-¦¦¦+TÃTÎ ¦¬¦-¦¬¦¦TÃ
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
                        
                        // ¦á¦-TÅTÀ¦-¦-TÏ¦¦¦- ¦-TÀ¦¦¦-TÏ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬
                        settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    }
                } catch (_: Exception) { }
                
                isLoading = false
            }
        }
    }
    
    // ¦Ô¦¬¦-¦¬¦-¦¦ TÁ¦-¦¬¦+¦-¦-¦¬TÏ ¦¬¦-¦¬¦¦¦¬
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
    
    // ¦Ô¦¬¦-¦¬¦-¦¦ TÃ¦+¦-¦¬¦¦¦-¦¬TÏ ¦¬¦-¦¬¦¦¦¬
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
                                        // ¦Þ¦-¦-¦-¦-¦¬TÏ¦¦¦- TÁ¦¬¦¬TÁ¦-¦¦ ¦¬¦-¦¬¦-¦¦
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
    
    // ¦Ü¦¦¦-TÎ ¦+¦¦¦¦TÁTÂ¦-¦¬¦¦ TÁ ¦¬¦-¦¬¦¦¦-¦¦ (¦¬TÀ¦¬ ¦+¦-¦¬¦¦¦-¦- ¦-¦-¦¦¦-TÂ¦¬¦¬)
    folderForMenu?.let { folder ->
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderForMenu = null },
            title = { Text(folder.displayName) },
            text = {
                Column {
                    // ¦ß¦¦TÀ¦¦¦¬¦-¦¦¦-¦-¦-¦-TÂTÌ
                    ListItem(
                        headlineContent = { Text(Strings.rename) },
                        leadingContent = { Icon(AppIcons.Edit, null) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            renameNewName = folder.displayName
                            folderToRename = folder
                        }
                    )
                    // ¦ã¦+¦-¦¬¦¬TÂTÌ
                    ListItem(
                        headlineContent = { Text(Strings.delete, color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
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
    
    // ¦Ô¦¬¦-¦¬¦-¦¦ ¦¬¦¦TÀ¦¦¦¬¦-¦¦¦-¦-¦-¦-¦-¦¬TÏ ¦¬¦-¦¬¦¦¦¬
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
                                        // ¦Þ¦-¦-¦-¦-¦¬TÏ¦¦¦- TÁ¦¬¦¬TÁ¦-¦¦ ¦¬¦-¦¬¦-¦¦
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
                        // ¦ß¦¦TÀ¦¦TÅ¦-¦+¦¬¦- ¦-¦- TÍ¦¦TÀ¦-¦- ¦¬¦¬¦-TÀ¦-¦-¦-TËTÅ (¦¬TÁ¦¬¦-¦¬TÌ¦¬TÃ¦¦¦- TÁ¦¬¦¦TÆ¦¬¦-¦¬TÌ¦-TË¦¦ ID)
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
        // ¦ß¦-¦¦¦-¦¬TË¦-¦-¦¦¦- ¦¬¦-¦¦TÀTÃ¦¬¦¦TÃ ¦¬¦-¦¦¦- ¦-¦¦¦¦¦-TÃ¦-TÂ ¦-¦¦ ¦¬¦-¦¦TÀTÃ¦¦¦¦¦-
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
                    Icon(AppIcons.Edit, Strings.compose)
                }
            }
        ) { padding ->
            // ¦ÚTÀ¦-TÁ¦¬¦-¦-TÏ ¦¦¦¬¦-¦-¦-¦-TÏ TÁTÂTÀ¦-¦-¦¬TÆ¦- TÁ ¦¬¦-TÄ¦-TÀ¦-¦-TÆ¦¬¦¦¦¦ ¦- ¦¬TÀ¦¬¦¬¦-¦¦¦¦¦-¦¬¦¬
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
    
    // ¦ÒTÀ¦¦¦-TÏ ¦¬¦-TÁ¦¬¦¦¦+¦-¦¦¦¦ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
    // ¦ßTÀ¦-¦-¦¦TÀTÏ¦¦¦- ¦-¦¦TÂ¦¬¦-¦¦¦- ¦¬¦¬ Battery Saver
    val isBatterySaverActive = remember { settingsRepo.isBatterySaverActive() }
    val ignoreBatterySaver by settingsRepo.ignoreBatterySaver.collectAsState(initial = false)
    val showBatterySaverWarning = isBatterySaverActive && !ignoreBatterySaver
    
    // ¦á¦-TÁTÂ¦-TÏ¦-¦¬¦¦ ¦+¦¬TÏ TÁ¦¦TÀTËTÂ¦¬TÏ TÀ¦¦¦¦¦-¦-¦¦¦-¦+¦-TÆ¦¬¦¬ (TÁ¦-TÅTÀ¦-¦-TÏ¦¦TÂTÁTÏ ¦¬TÀ¦¬ ¦-¦-¦-¦¬¦¦¦-TÆ¦¬¦¬, TÁ¦-TÀ¦-TÁTË¦-¦-¦¦TÂTÁTÏ ¦¬TÀ¦¬ ¦¬¦¦TÀ¦¦¦¬¦-¦¬TÃTÁ¦¦¦¦ ¦¬TÀ¦¬¦¬¦-¦¦¦¦¦-¦¬TÏ)
    var isRecommendationDismissed by rememberSaveable { mutableStateOf(false) }
    
    // ¦Û¦-¦¦¦-¦¬¦¬¦¬¦-¦-¦-¦-¦-TË¦¦ ¦-¦-¦¬¦-¦-¦-¦¬TÏ ¦¬¦-¦¬¦-¦¦ (¦-TË¦-¦¦TÁ¦¦¦-TË ¦+¦- LazyColumn)
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
        // ¦áTÂ¦-TÂTÃTÁ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬ òÀÔ TÁ¦-¦-TÀ¦¦¦-¦¦¦-¦-TË¦¦ ¦-¦¬¦+
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
        
        // ¦Ø¦-¦+¦¬¦¦¦-TÂ¦-TÀ Battery Saver
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
                            AppIcons.BatterySaver,
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
        
        // ¦ßTÀ¦¬¦-¦¦TÂTÁTÂ¦-¦¦¦-¦-¦-TÏ ¦¦¦-TÀTÂ¦-TÇ¦¦¦- òÀÔ TÁ¦-¦-TÀ¦¦¦-¦¦¦-¦-TË¦¦ ¦¦TÀ¦-¦+¦¬¦¦¦-TÂ¦-TË¦¦ TÁTÂ¦¬¦¬TÌ TÁ ¦-¦-¦¬¦-¦-TÆ¦¬¦¦¦¦
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
                                // ¦Ð¦-¦-TÂ¦-TÀ ¦-¦¦¦¦¦-TÃ¦-TÂ¦-
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeAccount?.color ?: 0xFF1976D2.toInt())),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeAccount?.displayName?.firstOrNull()?.uppercase() ?: "¨ßÓç",
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
                            
                            // ¦ÒTÀ¦¦¦-TÏ ¦¬¦-TÁ¦¬¦¦¦+¦-¦¦¦¦ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬¦¬ - ¦¬¦-¦¦¦-¦¬TË¦-¦-¦¦¦- TÂ¦-¦¬TÌ¦¦¦- ¦¦¦-¦¦¦+¦- ¦-¦¦ ¦¬¦+TÑTÂ TÁ¦¬¦-TÅTÀ¦-¦-¦¬¦¬¦-TÆ¦¬TÏ
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
        
        // ¦à¦¦¦¦¦-¦-¦¦¦-¦+¦-TÆ¦¬TÏ ¦+¦-TÏ - ¦¦TÁ¦¬¦¬ ¦¦TÁTÂTÌ ¦¬¦-¦¬¦¦¦¬ TÁ > 1000 ¦¬¦¬TÁ¦¦¦-
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
                    Strings.cleanupFoldersRecommendation(folderNames.joinToString(" ¦¬ "))
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
                                AppIcons.Close,
                                contentDescription = Strings.close,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        
        // ¦ÑTËTÁTÂTÀTË¦¦ ¦+¦-TÁTÂTÃ¦¬ ¦¦ ¦¬¦-¦¬¦¦¦-¦-
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
                        Icon(AppIcons.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(refreshText)
                    }
                }
            }
            
            // ¦ÞTÁ¦-¦-¦-¦-TË¦¦ ¦¬¦-¦¬¦¦¦¬ ¦- TÁ¦¦TÂ¦¦¦¦
            val mainFolders = folders.filter { it.type in listOf(2, 3, 4, 5) }
            
            data class FolderDisplay(val id: String, val name: String, val count: Int, val unreadCount: Int, val type: Int)
            
            // ¦ß¦-TÀTÏ¦+¦-¦¦: ¦ÒTÅ¦-¦+TÏTÉ¦¬¦¦, ¦ÞTÂ¦¬TÀ¦-¦-¦¬¦¦¦-¦-TË¦¦, ¦ç¦¦TÀ¦-¦-¦-¦¬¦¦¦¬, ¦ã¦+¦-¦¬TÑ¦-¦-TË¦¦, ¦Ø¦¬¦-TÀ¦-¦-¦-TË¦¦, ¦Ú¦-¦-TÂ¦-¦¦TÂTË
            val orderedFolders = mutableListOf<FolderDisplay>()
            
            // ¦ÒTÅ¦-¦+TÏTÉ¦¬¦¦ (type 2)
            mainFolders.find { it.type == 2 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, inboxName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ¦ÞTÂ¦¬TÀ¦-¦-¦¬¦¦¦-¦-TË¦¦ (type 5)
            mainFolders.find { it.type == 5 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, sentName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ¦ç¦¦TÀ¦-¦-¦-¦¬¦¦¦¬ (type 3)
            mainFolders.find { it.type == 3 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, draftsName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ¦ã¦+¦-¦¬TÑ¦-¦-TË¦¦ (type 4)
            mainFolders.find { it.type == 4 }?.let { folder ->
                orderedFolders.add(FolderDisplay(folder.id, trashName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ¦Ø¦¬¦-TÀ¦-¦-¦-TË¦¦
            orderedFolders.add(FolderDisplay("favorites", favoritesName, flaggedCount, 0, -1))
            // ¦Ú¦-¦-TÂ¦-¦¦TÂTË
            orderedFolders.add(FolderDisplay("contacts", contactsName, 0, 0, -2))
            
            val displayFolders = orderedFolders.toList()
            
            val chunkedFolders = displayFolders.chunked(2)
            itemsIndexed(chunkedFolders) { index, rowFolders ->
                val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                // ¦Ð¦-¦¬¦-¦-TÆ¦¬TÏ ¦¬¦-TÏ¦-¦¬¦¦¦-¦¬TÏ TÁ ¦¬¦-¦+¦¦TÀ¦¦¦¦¦-¦¦ ¦+¦¬TÏ ¦¦¦-¦¦¦+¦-¦¦ TÁTÂTÀ¦-¦¦¦¬ (¦¦TÁ¦¬¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ ¦-¦¦¦¬TÎTÇ¦¦¦-TË)
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
                    // ¦Ñ¦¦¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ òÀÔ ¦¬TÀ¦-TÁTÂ¦- ¦¬¦-¦¦¦-¦¬TË¦-¦-¦¦¦-
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
                            AppIcons.FolderOff,
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
                            Icon(AppIcons.Sync, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.synchronize)
                        }
                    }
                }
            }
        }
        
        // ¦á¦-¦-¦¦TÂTË ¦¬¦- TÀ¦-¦-¦-TÂ¦¦ TÁ ¦¬TÀ¦¬¦¬¦-¦¦¦¦¦-¦¬¦¦¦-
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
                        // ¦Ð¦-¦¬¦-¦¬TÀ¦-¦-¦-¦-¦-¦-TÏ ¦¬¦-¦-¦¬¦-TÇ¦¦¦-
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
                            imageVector = AppIcons.Lightbulb,
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
                            imageVector = if (tipsExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
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
                                icon = AppIcons.Notifications,
                                text = Strings.tipNotification,
                                iconColor = Color(0xFFFF9800),
                                iconBackgroundColor = Color(0xFFFFF3E0)
                            )
                            TipItem(
                                icon = AppIcons.BatteryChargingFull,
                                text = Strings.tipBattery,
                                iconColor = Color(0xFF4CAF50),
                                iconBackgroundColor = Color(0xFFE8F5E9)
                            )
                            TipItem(
                                icon = AppIcons.Lock,
                                text = Strings.tipCertificate,
                                iconColor = Color(0xFF9C27B0),
                                iconBackgroundColor = Color(0xFFF3E5F5)
                            )
                            TipItem(
                                icon = AppIcons.Info,
                                text = Strings.tipBeta,
                                iconColor = Color(0xFF2196F3),
                                iconBackgroundColor = Color(0xFFE3F2FD)
                            )
                        }
                    }
                }
            }
        }
        
        // ¦Þ ¦¬TÀ¦¬¦¬¦-¦¦¦¦¦-¦¬¦¬ òÀÔ ¦¦¦-¦-¦¬¦-¦¦TÂ¦-TË¦¦ TÁ¦-¦-TÀ¦¦¦-¦¦¦-¦-TË¦¦ ¦-¦¬¦+
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
                        // ¦Ð¦-¦¬¦-¦¬TÀ¦-¦-¦-¦-¦-TË¦¦ ¦¦¦-¦-¦-¦¦TÀTÂ¦¬¦¦ TÁ ¦¦TÀ¦-¦+¦¬¦¦¦-TÂ¦-¦-
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
                                AppIcons.Email,
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
                                text = "v1.2.0",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (aboutExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
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
                            
                            // ¦Ò¦-¦¬¦-¦-¦¦¦-¦-TÁTÂ¦¬ ¦- ¦-¦¬¦+¦¦ TÇ¦¬¦¬¦-¦-
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
                            
                            // ¦à¦-¦¬TÀ¦-¦-¦-TÂTÇ¦¬¦¦
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Person,
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
                                    AppIcons.Email,
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
                            
                            // Telegram ¦¦¦-¦-¦-¦¬
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { uriHandler.openUri("https://t.me/i_wantout") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Send,
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
                                text = "© 2025",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // ¦Ú¦-¦-¦¬¦¦¦- "¦Þ¦¬¦-¦-¦¦¦-¦-¦¬TÂTÌTÁTÏ TÁ TÀ¦-¦¬¦-¦¬TÂ¦¬¦¦¦- ¦¬TÀ¦-¦¦TÀ¦-¦-¦-TË" TÁ ¦-¦-¦¬¦-¦-TÆ¦¬¦¦¦¦
        item {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val isRu = LocalLanguage.current == AppLanguage.RUSSIAN
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            val changelogUrl = if (isRu) 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_RU.md"
            else 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_EN.md"
            
            // ¦Ð¦-¦¬¦-¦-TÆ¦¬TÏ ¦¬TÃ¦¬TÌTÁ¦-TÆ¦¬¦¬ (TÂ¦-¦¬TÌ¦¦¦- ¦¦TÁ¦¬¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ ¦-¦¦¦¬TÎTÇ¦¦¦-TË)
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
            
            // ¦Ð¦-¦¬¦-¦-TÆ¦¬TÏ TÁ¦-¦¦TÇ¦¦¦-¦¬TÏ ¦¦TÀ¦-¦-¦¬TÆTË
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
                    AppIcons.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(Strings.viewChangelog)
            }
        }
        
        // ¦Ú¦-¦-¦¬¦¦¦- ¦¬¦-¦¦¦¦TÀTÂ¦-¦-¦-¦-¦-¦¬¦¦ TÁ ¦¬TÃ¦¬TÌTÁ¦¬TÀTÃTÎTÉ¦¦¦¦ ¦-¦-¦¬¦-¦-TÆ¦¬¦¦¦¦
        item {
            var showDonateDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val accountCopiedText = Strings.accountCopied
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            
            // ¦ßTÃ¦¬TÌTÁ¦¬TÀTÃTÎTÉ¦-TÏ ¦-¦-¦¬¦-¦-TÆ¦¬TÏ (TÂ¦-¦¬TÌ¦¦¦- ¦¦TÁ¦¬¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ ¦-¦¦¦¬TÎTÇ¦¦¦-TË)
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
                    icon = { Icon(AppIcons.Favorite, null, tint = Color(0xFFE91E63)) },
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
                                    DonateInfoRow(Strings.recipient, "¦Ô¦-¦+¦-¦-¦-¦- ¦Ð¦-¦+TÀ¦¦¦¦ ¦Ø¦¦¦-TÀ¦¦¦-¦¬TÇ")
                                    // ¦Ý¦-¦-¦¦TÀ TÁTÇTÑTÂ¦- TÁ ¦¬¦¦TÀ¦¦¦-¦-TÁ¦-¦-
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
                                    DonateInfoRow(Strings.bank, "¦ß¦-¦-¦-¦¬¦¦TÁ¦¦¦¬¦¦ ¦-¦-¦-¦¦ ¦ß¦Ð¦Þ ¦á¦-¦¦TÀ¦-¦-¦-¦¦")
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
                                    // ¦Ú¦-¦¬¦¬TÀTÃ¦¦¦- ¦-¦-¦-¦¦TÀ TÁTÇTÑTÂ¦-
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Account", "40817810354405296071")
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, accountCopiedText, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(AppIcons.ContentCopy, null, modifier = Modifier.size(18.dp))
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
                Icon(AppIcons.Favorite, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(Strings.supportDeveloper, fontWeight = FontWeight.SemiBold)
            }
        }
        
        // ¦ÞTÂTÁTÂTÃ¦¬ TÁ¦-¦¬¦¬TÃ ¦+¦¬TÏ FAB
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
    
    // ¦Ð¦-¦¬¦-¦-TÆ¦¬TÏ ¦-¦-TÁTÈTÂ¦-¦-¦- ¦¬TÀ¦¬ ¦-¦-¦¦¦-TÂ¦¬¦¬ (TÂ¦-¦¬TÌ¦¦¦- ¦¦TÁ¦¬¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ ¦-¦¦¦¬TÎTÇ¦¦¦-TË)
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
    
    // ¦Ð¦-¦¬¦-¦-TÆ¦¬TÏ TÂ¦¦¦-¦¬ ¦¬TÀ¦¬ ¦-¦-¦¦¦-TÂ¦¬¦¬
    val elevation by animateDpAsState(
        targetValue = if (animationsEnabled && isPressed) 1.dp else 4.dp,
        animationSpec = if (animationsEnabled) tween(150) else snap(),
        label = "elevation"
    )
    
    // ¦ßTÃ¦¬TÌTÁ¦-TÆ¦¬TÏ ¦¬ ¦¬¦-¦¦¦-TÇ¦¬¦-¦-¦-¦¬¦¦ ¦¬¦¦¦-¦-¦¦¦¬ (TÂ¦-¦¬TÌ¦¦¦- ¦¦TÁ¦¬¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ ¦-¦¦¦¬TÎTÇ¦¦¦-TË)
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
    
    // ¦Þ¦¬TÀ¦¦¦+¦¦¦¬TÏ¦¦¦- TÆ¦-¦¦TÂ¦- ¦+¦¬TÏ ¦¦¦-¦¦¦+¦-¦¦¦- TÂ¦¬¦¬¦- ¦¬¦-¦¬¦¦¦¬ òÀÔ ¦¦¦+¦¬¦-TË¦¦ TÁ¦-¦-TÀ¦¦¦-¦¦¦-¦-TË¦¦ TÁTÂ¦¬¦¬TÌ
    data class FolderColors(
        val icon: ImageVector,
        val gradientColors: List<Color>
    )
    
    val folderColors = when (type) {
        2 -> FolderColors(
            AppIcons.Inbox, 
            listOf(Color(0xFF5C6BC0), Color(0xFF3949AB)) // Indigo
        )
        3 -> FolderColors(
            AppIcons.Drafts, 
            listOf(Color(0xFF78909C), Color(0xFF546E7A)) // Blue Grey
        )
        4 -> FolderColors(
            AppIcons.Delete, 
            listOf(Color(0xFFEF5350), Color(0xFFE53935)) // Red
        )
        5 -> FolderColors(
            AppIcons.Send, 
            listOf(Color(0xFF7E57C2), Color(0xFF5E35B1)) // Deep Purple
        )
        6 -> FolderColors(
            AppIcons.Outbox, 
            listOf(Color(0xFF26A69A), Color(0xFF00897B)) // Teal
        )
        -1 -> FolderColors(
            AppIcons.Star, 
            listOf(Color(0xFFFFCA28), Color(0xFFFFA000)) // Amber
        )
        -2 -> FolderColors(
            AppIcons.Contacts, 
            listOf(Color(0xFF4FC3F7), Color(0xFF29B6F6)) // Light Blue
        )
        else -> FolderColors(
            AppIcons.Folder, 
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
                // ¦Ø¦¦¦-¦-¦¦¦- TÁ ¦¬¦-¦¬TÃ¦¬TÀ¦-¦¬TÀ¦-TÇ¦-TË¦- TÄ¦-¦-¦-¦- ¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¦¦¦
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
                    // ¦Ô¦¬TÏ ¦¦¦-¦-TÂ¦-¦¦TÂ¦-¦- ¦-¦¦ ¦¬¦-¦¦¦-¦¬TË¦-¦-¦¦¦- "¦¬¦¬TÁ¦¦¦-"
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
                
                // Badge TÁ ¦-¦¦¦¬TÀ¦-TÇ¦¬TÂ¦-¦-¦-TË¦-¦¬ òÀÔ TÁ ¦¬TÃ¦¬TÌTÁ¦-TÆ¦¬¦¦¦¦ (¦¦TÁ¦¬¦¬ ¦-¦-¦¬¦-¦-TÆ¦¬¦¬ ¦-¦¦¦¬TÎTÇ¦¦¦-TË)
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
    // ¦ÓTÀ¦-¦+¦¬¦¦¦-TÂ¦-TË¦¦ TÄ¦-¦- ¦+¦¬TÏ TÂ¦-¦¬¦-¦-TÀ¦-
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
                    Icon(AppIcons.Menu, Strings.menu, tint = colorTheme.primaryLight)
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
        // ¦×¦-¦¦¦-¦¬¦-¦-¦-¦¦ TÁ ¦-¦¦¦¦¦-TÃ¦-TÂ¦-¦-
        item {
            DrawerHeader(
                account = activeAccount,
                showPicker = showAccountPicker,
                onToggle = onToggleAccountPicker
            )
        }
        
        // ¦ÒTË¦-¦-TÀ ¦-¦¦¦¦¦-TÃ¦-TÂ¦-
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
                    leadingContent = { Icon(AppIcons.Add, null) },
                    modifier = Modifier.clickable(onClick = onAddAccount)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        
        // ¦ß¦-¦¬¦¦¦¬ - TÁ¦-¦-TÇ¦-¦¬¦- ¦-TÁ¦-¦-¦-¦-TË¦¦ (¦ÒTÅ¦-¦+TÏTÉ¦¬¦¦, ¦ç¦¦TÀ¦-¦-¦-¦¬¦¦¦¬, ¦ã¦+¦-¦¬TÑ¦-¦-TË¦¦, ¦ÞTÂ¦¬TÀ¦-¦-¦¬¦¦¦-¦-TË¦¦, ¦ØTÁTÅ¦-¦+TÏTÉ¦¬¦¦, ¦á¦¬¦-¦-)
        val mainFolderTypes = listOf(2, 3, 4, 5, 6, 11)
        val mainFolders = folders.filter { it.type in mainFolderTypes }
            .sortedBy { mainFolderTypes.indexOf(it.type) }
        
        items(mainFolders) { folder ->
            FolderItem(
                folder = folder,
                onClick = { onFolderSelected(folder) }
            )
        }
        
        // ¦Ø¦¬¦-TÀ¦-¦-¦-TË¦¦ - ¦¬¦-TÁ¦¬¦¦ ¦ØTÁTÅ¦-¦+TÏTÉ¦¬TÅ
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
        
        // ¦ÞTÁTÂ¦-¦¬TÌ¦-TË¦¦ ¦¬¦-¦¬¦¦¦¬ (¦¦TÀ¦-¦-¦¦ Contacts - type 9, TÃ ¦-¦-TÁ TÁ¦-¦-¦¦ TÍ¦¦TÀ¦-¦- ¦¦¦-¦-TÂ¦-¦¦TÂ¦-¦-)
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
        
        // ¦á¦-¦¬¦+¦-TÂTÌ ¦¬¦-¦¬¦¦TÃ
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(Strings.createFolder) },
                leadingContent = { Icon(AppIcons.CreateNewFolder, null) },
                modifier = Modifier.clickable(onClick = onCreateFolder)
            )
        }
        
        // ¦Ú¦-¦-TÂ¦-¦¦TÂTË
        item {
            ListItem(
                headlineContent = { Text(Strings.contacts) },
                leadingContent = { Icon(AppIcons.People, null) },
                modifier = Modifier.clickable(onClick = onContactsClick)
            )
        }
        
        // ¦Ý¦-TÁTÂTÀ¦-¦¦¦¦¦¬
        item {
            ListItem(
                headlineContent = { Text(Strings.settings) },
                leadingContent = { Icon(AppIcons.Settings, null) },
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
    // ¦ÓTÀ¦-¦+¦¬¦¦¦-TÂ¦-TË¦¦ TÅ¦¦¦+¦¦TÀ ¦¦¦-¦¦ ¦- SetupScreen
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
                    imageVector = if (showPicker) AppIcons.KeyboardArrowUp 
                                  else AppIcons.KeyboardArrowDown,
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
                Icon(AppIcons.Check, "¦Ð¦¦TÂ¦¬¦-¦-TË¦¦", tint = MaterialTheme.colorScheme.primary)
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
    
    // ¦æ¦-¦¦TÂ ¦¬¦¦¦-¦-¦¦¦¬ - ¦¦TÀ¦-TÁ¦-TË¦¦ ¦+¦¬TÏ TÁ¦¬¦-¦-¦- ¦¬ ¦¦¦-TÀ¦¬¦¬¦-TË
    val iconTint = when (folder.type) {
        4 -> MaterialTheme.colorScheme.error // ¦ã¦+¦-¦¬TÑ¦-¦-TË¦¦
        11 -> Color(0xFFE53935) // ¦á¦¬¦-¦- - ¦¦TÀ¦-TÁ¦-TË¦¦
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    // ¦Û¦-¦¦¦-¦¬¦¬¦¬¦-¦-¦-¦-¦-¦-¦¦ ¦-¦-¦¬¦-¦-¦-¦¬¦¦ ¦¬¦-¦¬¦¦¦¬
    val displayName = Strings.getFolderName(folder.type, folder.displayName)
    
    // ¦á¦¬TÁTÂ¦¦¦-¦-TË¦¦ ¦¬¦-¦¬¦¦¦¬ ¦-¦¦¦¬TÌ¦¬TÏ TÃ¦+¦-¦¬TÏTÂTÌ (¦-¦¦¦¬TÎTÇ¦-TÏ ¦á¦¬¦-¦-)
    val isSystemFolder = folder.type in listOf(2, 3, 4, 5, 6, 11)
    
    // ¦ØTÁ¦¬¦-¦¬TÌ¦¬TÃ¦¦¦- Surface TÁ combinedClickable ¦-¦-¦¦TÁTÂ¦- NavigationDrawerItem
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
    
    // ¦Ð¦-¦¬¦-¦-TÆ¦¬TÏ ¦¬TÃ¦¬TÌTÁ¦-TÆ¦¬¦¬ ¦¬¦¦¦-¦-¦¦¦¬
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
