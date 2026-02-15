package com.dedovmosol.iwomail.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape

import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.DraftMode
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    accountId: Long,
    onBackClick: () -> Unit,
    onEditCredentials: (Long) -> Unit = {},
    onNavigateToSyncCleanup: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }
    val isRu = isRussian()
    
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var signatures by remember { mutableStateOf<List<com.dedovmosol.iwomail.data.database.SignatureEntity>>(emptyList()) }
    
    // Загружаем аккаунт
    LaunchedEffect(accountId) {
        account = accountRepo.getAccount(accountId)
        signatures = database.signatureDao().getSignaturesByAccountList(accountId)
    }
    
    val currentAccount = account ?: return
    
    val accountType = try {
        AccountType.valueOf(currentAccount.accountType)
    } catch (_: Exception) {
        AccountType.EXCHANGE
    }
    
    val syncMode = try {
        SyncMode.valueOf(currentAccount.syncMode)
    } catch (_: Exception) {
        SyncMode.PUSH
    }

    
    // Диалоги
    var showSignaturesDialog by rememberSaveable { mutableStateOf(false) }
    var showCertificateDialog by rememberSaveable { mutableStateOf(false) }
    var showClientCertificateDialog by rememberSaveable { mutableStateOf(false) }
    var showClientCertPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var pendingClientCertPath by remember { mutableStateOf<String?>(null) }
    var pendingClientCertFileName by remember { mutableStateOf<String?>(null) }
    var pendingOldClientCertPath by remember { mutableStateOf<String?>(null) }
    var clientCertPasswordInput by rememberSaveable { mutableStateOf("") }
    var clientCertPasswordVisible by rememberSaveable { mutableStateOf(false) }
    
    // Пикеры для серверного сертификата
    val certFileName = currentAccount.certificatePath?.let { java.io.File(it).name } ?: "certificate.cer"
    
    val exportCertificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            scope.launch {
                try {
                    currentAccount.certificatePath?.let { certPath ->
                        val certFile = java.io.File(certPath)
                        if (certFile.exists()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                context.contentResolver.openOutputStream(destUri)?.use { output ->
                                    certFile.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateExported(isRu),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        com.dedovmosol.iwomail.ui.NotificationStrings.getExportError(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    val validExtensions = listOf("cer", "crt", "pem", "der", "p12", "pfx", "p7b", "p7c")
                    var originalFileName: String? = null
                    val cursor = context.contentResolver.query(selectedUri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                originalFileName = it.getString(nameIndex)
                            }
                        }
                    }
                    
                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in validExtensions) {
                        android.widget.Toast.makeText(
                            context,
                            com.dedovmosol.iwomail.ui.NotificationStrings.getInvalidFileFormat(isRu),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    
                    currentAccount.certificatePath?.let { oldPath ->
                        try { java.io.File(oldPath).delete() } catch (_: Exception) {}
                    }
                    
                    val fileName = "cert_${currentAccount.id}_${System.currentTimeMillis()}.$extension"
                    val certFile = java.io.File(context.filesDir, fileName)
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            certFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    accountRepo.updateCertificatePath(accountId, certFile.absolutePath)
                    account = accountRepo.getAccount(accountId)
                    
                    // Автоматически включаем Certificate Pinning после загрузки сертификата
                    val pinResult = accountRepo.pinCertificate(accountId)
                    when (pinResult) {
                        is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                            account = accountRepo.getAccount(accountId)
                            android.widget.Toast.makeText(
                                context,
                                if (isRu) 
                                    "✅ Сертификат загружен, защита включена" 
                                else 
                                    "✅ Certificate loaded, protection enabled",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        is com.dedovmosol.iwomail.eas.EasResult.Error -> {
                            android.util.Log.w("AccountSettings", "Failed to auto-enable certificate pinning: ${pinResult.message}")
                            android.widget.Toast.makeText(
                                context,
                                if (isRu)
                                    "⚠️ Сертификат загружен, но защита не включена: ${pinResult.message}"
                                else
                                    "⚠️ Certificate loaded, but protection not enabled: ${pinResult.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateLoadingError(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    
    // Пикеры для клиентского сертификата
    val clientCertFileName = currentAccount.clientCertificatePath?.let { java.io.File(it).name } ?: "client_certificate.p12"
    
    val exportClientCertificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            scope.launch {
                try {
                    currentAccount.clientCertificatePath?.let { certPath ->
                        val certFile = java.io.File(certPath)
                        if (certFile.exists()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                context.contentResolver.openOutputStream(destUri)?.use { output ->
                                    certFile.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (isRu) "Клиентский сертификат экспортирован" else "Client certificate exported",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        com.dedovmosol.iwomail.ui.NotificationStrings.getExportError(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    val clientCertificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    val validExtensions = listOf("p12", "pfx")
                    var originalFileName: String? = null
                    val cursor = context.contentResolver.query(selectedUri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                originalFileName = it.getString(nameIndex)
                            }
                        }
                    }
                    
                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in validExtensions) {
                        android.widget.Toast.makeText(
                            context,
                            if (isRu) "Неверный формат файла. Выберите .p12 или .pfx" else "Invalid file format. Select .p12 or .pfx",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    
                    // Копируем новый файл сертификата
                    val fileName = "client_cert_${currentAccount.id}_${System.currentTimeMillis()}.$extension"
                    val certFile = java.io.File(context.filesDir, fileName)
                    
                    // Сохраняем старый путь для удаления ПОСЛЕ успешного копирования
                    val oldCertPath = currentAccount.clientCertificatePath
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            certFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    // Запоминаем новый файл и просим пароль
                    pendingOldClientCertPath = oldCertPath
                    pendingClientCertPath = certFile.absolutePath
                    pendingClientCertFileName = originalFileName ?: fileName
                    clientCertPasswordInput = ""
                    clientCertPasswordVisible = false
                    showClientCertPasswordDialog = true
                } catch (e: Exception) {
                    android.util.Log.e("AccountSettings", "Client certificate loading error: ${e.message}", e)
                    android.widget.Toast.makeText(
                        context,
                        NotificationStrings.getCertificateLoadingError(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    
    // Диалог подписей
    if (showSignaturesDialog) {
        SignaturesManagementDialog(
            isRu = isRu,
            accountId = accountId,
            signatures = signatures,
            onSignaturesChanged = { newSignatures ->
                signatures = newSignatures
            },
            onDismiss = { showSignaturesDialog = false }
        )
    }

    
    // Диалог сертификата
    if (showCertificateDialog && !currentAccount.certificatePath.isNullOrBlank()) {
        val certFile = java.io.File(currentAccount.certificatePath!!)
        val certFileSize = if (certFile.exists()) "${certFile.length() / 1024} KB" else "—"
        var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
        
        if (showDeleteConfirm) {
            com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                icon = { Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(com.dedovmosol.iwomail.ui.NotificationStrings.getDeleteCertificateTitle(isRu)) },
                text = {
                    Text(com.dedovmosol.iwomail.ui.NotificationStrings.getDeleteCertificateWarning(isRu))
                },
                confirmButton = {
                    com.dedovmosol.iwomail.ui.theme.DeleteButton(
                        onClick = {
                            showDeleteConfirm = false
                            showCertificateDialog = false
                            try { certFile.delete() } catch (_: Exception) {}
                            scope.launch {
                                // КРИТИЧНО: При удалении сертификата отключаем Certificate Pinning
                                accountRepo.updateCertificatePinningEnabled(accountId, false)
                                accountRepo.updateCertificatePath(accountId, null)
                                account = accountRepo.getAccount(accountId)
                            }
                            android.widget.Toast.makeText(
                                context,
                                com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateRemoved(isRu),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        text = com.dedovmosol.iwomail.ui.NotificationStrings.getRemove(isRu)
                    )
                },
                dismissButton = {
                    com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                        onClick = { showDeleteConfirm = false },
                        text = Strings.cancel
                    )
                }
            )
        }
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showCertificateDialog = false },
            icon = { Icon(AppIcons.Lock, null) },
            title = { Text(Strings.serverCertificate) },
            text = {
                Column {
                    Text(com.dedovmosol.iwomail.ui.NotificationStrings.getFileLabel(isRu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(certFile.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(com.dedovmosol.iwomail.ui.NotificationStrings.getSizeLabel(isRu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(certFileSize, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                exportCertificatePicker.launch(certFile.name)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getExport(isRu))
                        }
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                certificatePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getReplace(isRu))
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getRemove(isRu))
                        }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showCertificateDialog = false },
                    text = Strings.close
                )
            }
        )
    }

    
    // Диалог клиентского сертификата
    if (showClientCertificateDialog && !currentAccount.clientCertificatePath.isNullOrBlank()) {
        val certFile = java.io.File(currentAccount.clientCertificatePath!!)
        val certFileSize = if (certFile.exists()) "${certFile.length() / 1024} KB" else "—"
        var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
        
        if (showDeleteConfirm) {
            com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                icon = { Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(if (isRu) "Удалить клиентский сертификат?" else "Delete client certificate?") },
                text = {
                    Text(if (isRu) "Сертификат и пароль будут удалены. Это действие нельзя отменить." else "Certificate and password will be deleted. This action cannot be undone.")
                },
                confirmButton = {
                    com.dedovmosol.iwomail.ui.theme.DeleteButton(
                        onClick = {
                            showDeleteConfirm = false
                            showClientCertificateDialog = false
                            try { certFile.delete() } catch (_: Exception) {}
                            scope.launch {
                                accountRepo.updateClientCertificatePath(accountId, null)
                                // Удаляем пароль из EncryptedSharedPreferences
                                accountRepo.updateClientCertificatePassword(accountId, null)
                                // Очищаем кеши
                                com.dedovmosol.iwomail.network.HttpClientProvider.clearAllCertificateCache()
                                account = accountRepo.getAccount(accountId)
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (isRu) "Клиентский сертификат удалён" else "Client certificate removed",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        text = com.dedovmosol.iwomail.ui.NotificationStrings.getRemove(isRu)
                    )
                },
                dismissButton = {
                    com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                        onClick = { showDeleteConfirm = false },
                        text = Strings.cancel
                    )
                }
            )
        }
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showClientCertificateDialog = false },
            icon = { Icon(AppIcons.Lock, null) },
            title = { Text(if (isRu) "Клиентский сертификат" else "Client certificate") },
            text = {
                Column {
                    Text(com.dedovmosol.iwomail.ui.NotificationStrings.getFileLabel(isRu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(certFile.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(com.dedovmosol.iwomail.ui.NotificationStrings.getSizeLabel(isRu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(certFileSize, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showClientCertificateDialog = false
                                exportClientCertificatePicker.launch(certFile.name)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getExport(isRu))
                        }
                        OutlinedButton(
                            onClick = {
                                showClientCertificateDialog = false
                                clientCertificatePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getReplace(isRu))
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.dedovmosol.iwomail.ui.NotificationStrings.getRemove(isRu))
                        }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showClientCertificateDialog = false },
                    text = Strings.close
                )
            }
        )
    }

    // Диалог ввода пароля для нового клиентского сертификата
    if (showClientCertPasswordDialog && pendingClientCertPath != null) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = {
                showClientCertPasswordDialog = false
                pendingClientCertPath?.let { path -> try { java.io.File(path).delete() } catch (_: Exception) {} }
                pendingClientCertPath = null
                pendingClientCertFileName = null
                pendingOldClientCertPath = null
                clientCertPasswordInput = ""
                clientCertPasswordVisible = false
            },
            icon = { Icon(AppIcons.Lock, null) },
            title = { Text(if (isRu) "Пароль клиентского сертификата" else "Client certificate password") },
            text = {
                Column {
                    pendingClientCertFileName?.let { fileName ->
                        Text(
                            text = if (isRu) "Файл: $fileName" else "File: $fileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = clientCertPasswordInput,
                        onValueChange = { clientCertPasswordInput = it },
                        label = { Text(if (isRu) "Пароль сертификата" else "Certificate password") },
                        visualTransformation = if (clientCertPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { clientCertPasswordVisible = !clientCertPasswordVisible }) {
                                Icon(
                                    if (clientCertPasswordVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        if (clientCertPasswordInput.isBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                if (isRu) "Введите пароль сертификата" else "Enter certificate password",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val newPath = pendingClientCertPath
                            if (newPath != null) {
                                val isValid = com.dedovmosol.iwomail.network.HttpClientProvider
                                    .validateClientCertificate(newPath, clientCertPasswordInput)
                                if (!isValid) {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (isRu) "Неверный пароль или повреждённый сертификат" else "Invalid password or corrupted certificate",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val oldPath = pendingOldClientCertPath
                                    scope.launch {
                                        accountRepo.updateClientCertificatePath(accountId, newPath)
                                        accountRepo.updateClientCertificatePassword(accountId, clientCertPasswordInput)
                                        oldPath?.let { path -> try { java.io.File(path).delete() } catch (_: Exception) {} }
                                        com.dedovmosol.iwomail.network.HttpClientProvider.clearAllCertificateCache()
                                        account = accountRepo.getAccount(accountId)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (isRu) "Клиентский сертификат обновлён" else "Client certificate updated",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        showClientCertPasswordDialog = false
                                        pendingClientCertPath = null
                                        pendingClientCertFileName = null
                                        pendingOldClientCertPath = null
                                        clientCertPasswordInput = ""
                                        clientCertPasswordVisible = false
                                    }
                                }
                            }
                        }
                    },
                    text = Strings.save
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showClientCertPasswordDialog = false
                        pendingClientCertPath?.let { path -> try { java.io.File(path).delete() } catch (_: Exception) {} }
                        pendingClientCertPath = null
                        pendingClientCertFileName = null
                        pendingOldClientCertPath = null
                        clientCertPasswordInput = ""
                        clientCertPasswordVisible = false
                    },
                    text = Strings.cancel
                )
            }
        )
    }

    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.accountSettings, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Заголовок аккаунта
            item {
                val accountColor = try {
                    Color(currentAccount.color)
                } catch (_: Exception) {
                    MaterialTheme.colorScheme.primary
                }
                
                ListItem(
                    headlineContent = { 
                        Text(currentAccount.displayName, fontWeight = FontWeight.Bold)
                    },
                    supportingContent = { 
                        Column {
                            Text(currentAccount.email)
                            Text(accountType.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(accountColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentAccount.displayName.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                )
            }
            
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            
            // Изменить учётные данные
            item {
                ListItem(
                    headlineContent = { Text(Strings.changeCredentials) },
                    leadingContent = { Icon(AppIcons.Edit, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { onEditCredentials(accountId) }
                )
            }
            
            // Сертификат сервера (если есть)
            if (!currentAccount.certificatePath.isNullOrBlank()) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.serverCertificate) },
                        supportingContent = { Text(java.io.File(currentAccount.certificatePath!!).name) },
                        leadingContent = { Icon(AppIcons.Lock, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showCertificateDialog = true }
                    )
                }
                
                // Certificate Pinning UI (защита от MITM)
                item {
                    CertificatePinningCard(
                        account = currentAccount,
                        accountRepo = accountRepo,
                        isRu = isRu,
                        onAccountUpdated = { updatedAccount ->
                            account = updatedAccount
                        }
                    )
                }
            }
            
            // Клиентский сертификат (если есть)
            if (!currentAccount.clientCertificatePath.isNullOrBlank()) {
                item {
                    ListItem(
                        headlineContent = { Text(if (isRu) "Клиентский сертификат" else "Client certificate") },
                        supportingContent = { Text(java.io.File(currentAccount.clientCertificatePath!!).name) },
                        leadingContent = { Icon(AppIcons.Lock, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showClientCertificateDialog = true }
                    )
                }
            }
            
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            
            // Подписи
            item {
                ListItem(
                    headlineContent = { Text(Strings.signature) },
                    supportingContent = { 
                        Text(
                            if (signatures.isEmpty()) Strings.noSignature 
                            else Strings.signaturesCount(signatures.size)
                        ) 
                    },
                    leadingContent = { Icon(AppIcons.Draw, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { showSignaturesDialog = true }
                )
            }
            
            // Режим черновиков (только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                item {
                    val currentDraftMode = try {
                        DraftMode.valueOf(currentAccount.draftMode)
                    } catch (_: Exception) {
                        DraftMode.SERVER
                    }
                    val draftModeText = if (currentDraftMode == DraftMode.LOCAL) {
                        if (isRu) "Локальные" else "Local"
                    } else {
                        if (isRu) "Серверные" else "Server"
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Column {
                        ListItem(
                            headlineContent = { Text(if (isRu) "Режим черновиков (бета)" else "Draft mode (beta)") },
                            supportingContent = { Text(draftModeText) },
                            leadingContent = { Icon(AppIcons.Drafts, null) },
                            trailingContent = {
                                Icon(
                                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(start = 56.dp)) {
                                // Серверные
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (currentDraftMode != DraftMode.SERVER) {
                                                scope.launch {
                                                    accountRepo.updateDraftMode(accountId, DraftMode.SERVER.name)
                                                    account = accountRepo.getAccount(accountId)
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    RadioButton(
                                        selected = currentDraftMode == DraftMode.SERVER,
                                        onClick = {
                                            if (currentDraftMode != DraftMode.SERVER) {
                                                scope.launch {
                                                    accountRepo.updateDraftMode(accountId, DraftMode.SERVER.name)
                                                    account = accountRepo.getAccount(accountId)
                                                }
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isRu) "Серверные" else "Server")
                                }
                                // Локальные
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (currentDraftMode != DraftMode.LOCAL) {
                                                scope.launch {
                                                    accountRepo.updateDraftMode(accountId, DraftMode.LOCAL.name)
                                                    account = accountRepo.getAccount(accountId)
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    RadioButton(
                                        selected = currentDraftMode == DraftMode.LOCAL,
                                        onClick = {
                                            if (currentDraftMode != DraftMode.LOCAL) {
                                                scope.launch {
                                                    accountRepo.updateDraftMode(accountId, DraftMode.LOCAL.name)
                                                    account = accountRepo.getAccount(accountId)
                                                }
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isRu) "Локальные" else "Local")
                                }
                            }
                        }
                    }
                }
            }
            
            // Синхронизация и очистка - открывает отдельный экран
            item {
                ListItem(
                    headlineContent = { Text(Strings.syncAndCleanup) },
                    supportingContent = { Text(Strings.syncAndCleanupDesc) },
                    leadingContent = { Icon(AppIcons.Sync, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { onNavigateToSyncCleanup(accountId) }
                )
            }
        }
    }
}

/**
 * Универсальный диалог выбора интервала синхронизации
 */
@Composable
fun SyncIntervalDialog(
    isRu: Boolean,
    title: String,
    currentDays: Int,
    onDaysChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val intervals = listOf(
        0 to (NotificationStrings.getNever(isRu)),
        1 to (NotificationStrings.getDaily(isRu)),
        7 to (NotificationStrings.getWeekly(isRu)),
        14 to (NotificationStrings.getEveryTwoWeeks(isRu)),
        30 to (NotificationStrings.getMonthly(isRu))
    )
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                intervals.forEach { (days, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDaysChange(days)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentDays == days, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel
            )
        }
    )
}




/**
 * Card для отображения статуса Certificate Pinning и управления им
 */
@Composable
fun CertificatePinningCard(
    account: AccountEntity,
    accountRepo: AccountRepository,
    isRu: Boolean,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    
    // Проверяем наличие изменения сертификата
    val certificateChange = remember(account.id) {
        com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.getChange(account.id)
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                certificateChange != null -> Color.Red.copy(alpha = 0.1f)
                account.pinnedCertificateHash != null -> Color(0xFF4CAF50).copy(alpha = 0.1f) // Green
                else -> Color(0xFFFF9800).copy(alpha = 0.1f) // Orange
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (account.pinnedCertificateHash != null) AppIcons.Lock else AppIcons.Security,
                    contentDescription = null,
                    tint = when {
                        certificateChange != null -> Color.Red
                        account.pinnedCertificateHash != null -> Color(0xFF4CAF50) // Green
                        else -> Color(0xFFFF9800) // Orange
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRu) "🔒 Защита от MITM атак" else "🔒 MITM Attack Protection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Проверяем наличие сертификата
            if (account.certificatePath == null) {
                // Сертификат не загружен - Certificate Pinning недоступен
                CertificateNotAvailable(isRu = isRu)
            } else if (certificateChange != null) {
                // Предупреждение об изменении сертификата
                CertificateChangeWarning(
                    certificateChange = certificateChange,
                    accountId = account.id,
                    accountRepo = accountRepo,
                    isRu = isRu,
                    onAccountUpdated = onAccountUpdated
                )
            } else if (account.pinnedCertificateHash != null) {
                // Сертификат привязан - показываем информацию
                CertificatePinnedInfo(
                    account = account,
                    accountRepo = accountRepo,
                    isRu = isRu,
                    isLoading = isLoading,
                    onLoadingChange = { isLoading = it },
                    onAccountUpdated = onAccountUpdated
                )
            } else {
                // Защита отключена
                CertificatePinningDisabled(
                    accountId = account.id,
                    accountRepo = accountRepo,
                    isRu = isRu,
                    isLoading = isLoading,
                    onLoadingChange = { isLoading = it },
                    onAccountUpdated = onAccountUpdated
                )
            }
        }
    }
}

/**
 * Сертификат не загружен - Certificate Pinning недоступен
 */
@Composable
private fun CertificateNotAvailable(isRu: Boolean) {
    Text(
        text = if (isRu) "ℹ️ Используется системное хранилище" else "ℹ️ Using system certificate store",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color.Gray
    )
    
    Spacer(Modifier.height(4.dp))
    
    Text(
        text = if (isRu)
            "Certificate Pinning недоступен без пользовательского сертификата"
        else
            "Certificate Pinning unavailable without custom certificate",
        style = MaterialTheme.typography.bodySmall
    )
    
    Spacer(Modifier.height(8.dp))
    
    Text(
        text = if (isRu)
            "ℹ️ Загрузите сертификат сервера в разделе выше, после чего защита включится автоматически"
        else
            "ℹ️ Upload server certificate in the section above, protection will be enabled automatically",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Предупреждение об изменении сертификата
 */
@Composable
private fun CertificateChangeWarning(
    certificateChange: com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.CertificateChange,
    accountId: Long,
    accountRepo: AccountRepository,
    isRu: Boolean,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    
    Text(
        text = if (isRu) "⚠️ Обнаружен новый сертификат!" else "⚠️ New certificate detected!",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color.Red
    )
    
    Spacer(Modifier.height(8.dp))
    
    Text(
        text = "${if (isRu) "Сервер" else "Server"}: ${certificateChange.hostname}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )
    
    Spacer(Modifier.height(8.dp))
    
    // Старый сертификат
    Text(
        text = if (isRu) "СТАРЫЙ СЕРТИФИКАТ:" else "OLD CERTIFICATE:",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )
    CertificateDetails(certificateChange.oldCert, isRu, isOld = true)
    
    Spacer(Modifier.height(8.dp))
    
    // Новый сертификат
    Text(
        text = if (isRu) "НОВЫЙ СЕРТИФИКАТ:" else "NEW CERTIFICATE:",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = Color.Red
    )
    CertificateDetails(certificateChange.newCert, isRu, isOld = false)
    
    Spacer(Modifier.height(8.dp))
    
    Text(
        text = if (isRu)
            "⚠️ Это может быть плановое обновление или MITM-атака. Проверьте с администратором сервера."
        else
            "⚠️ This could be a planned update or MITM attack. Check with your server administrator.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )
    
    Spacer(Modifier.height(12.dp))
    
    // Кнопки действий
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                scope.launch {
                    accountRepo.updatePinnedCertificate(
                        accountId = accountId,
                        hash = certificateChange.newCert.hash,
                        cn = certificateChange.newCert.cn,
                        org = certificateChange.newCert.organization,
                        validFrom = certificateChange.newCert.validFrom,
                        validTo = certificateChange.newCert.validTo
                    )
                    accountRepo.updateCertificatePinningFailCount(accountId, 0)
                    com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.clearChange(accountId)
                    accountRepo.clearEasClientCache(accountId)
                    accountRepo.getAccount(accountId)?.let { onAccountUpdated(it) }
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (isRu) "✅ Принять" else "✅ Accept")
        }
        
        OutlinedButton(
            onClick = {
                scope.launch {
                    accountRepo.updatePinnedCertHash(accountId, null)
                    accountRepo.getAccount(accountId)?.let { onAccountUpdated(it) }
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (isRu) "🔓 Отключить" else "🔓 Disable")
        }
    }
}

/**
 * Информация о привязанном сертификате
 */
@Composable
private fun CertificatePinnedInfo(
    account: AccountEntity,
    accountRepo: AccountRepository,
    isRu: Boolean,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    
    Text(
        text = if (isRu) "✅ Защита включена" else "✅ Protection enabled",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color.Green
    )
    
    Spacer(Modifier.height(8.dp))
    
    // Детали сертификата
    Text("• CN: ${account.pinnedCertificateCN ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
    Text("• ${if (isRu) "Организация" else "Organization"}: ${account.pinnedCertificateOrg ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
    
    if (account.pinnedCertificateValidFrom != null) {
        Text(
            "• ${if (isRu) "Выдан" else "Issued"}: ${formatDate(account.pinnedCertificateValidFrom)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (account.pinnedCertificateValidTo != null) {
        Text(
            "• ${if (isRu) "Истекает" else "Expires"}: ${formatDate(account.pinnedCertificateValidTo)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    
    Text(
        "• Hash: ${account.pinnedCertificateHash?.take(16)}...",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
    
    // Предупреждение о множественных ошибках
    if (account.certificatePinningFailCount > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "⚠️ ${if (isRu) "Ошибок проверки" else "Verification errors"}: ${account.certificatePinningFailCount}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800), // Orange
            fontWeight = FontWeight.Bold
        )
    }
    
    Spacer(Modifier.height(12.dp))
    
    // Кнопки управления
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                scope.launch {
                    onLoadingChange(true)
                    val result = accountRepo.pinCertificate(account.id)
                    onLoadingChange(false)
                    if (result is com.dedovmosol.iwomail.eas.EasResult.Success) {
                        accountRepo.getAccount(account.id)?.let { onAccountUpdated(it) }
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.weight(1f)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Icon(AppIcons.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isRu) "Обновить" else "Update")
            }
        }
        
        OutlinedButton(
            onClick = {
                scope.launch {
                    accountRepo.updatePinnedCertHash(account.id, null)
                    accountRepo.getAccount(account.id)?.let { onAccountUpdated(it) }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (isRu) "Отключить" else "Disable")
        }
    }
    
    // Предупреждение при множественных ошибках
    if (account.certificatePinningFailCount >= 3) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRu)
                "⚠️ Множественные ошибки проверки. Возможно, сертификат изменился. Рекомендуется обновить или отключить защиту."
            else
                "⚠️ Multiple verification errors. Certificate may have changed. Consider updating or disabling protection.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800) // Orange
        )
    }
}

/**
 * Защита отключена
 */
@Composable
private fun CertificatePinningDisabled(
    accountId: Long,
    accountRepo: AccountRepository,
    isRu: Boolean,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    
    Text(
        text = if (isRu) "⚠️ Защита отключена" else "⚠️ Protection disabled",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFFF9800) // Orange
    )
    
    Spacer(Modifier.height(4.dp))
    
    Text(
        text = if (isRu) "Соединение уязвимо к MITM-атакам" else "Vulnerable to MITM attacks",
        style = MaterialTheme.typography.bodySmall
    )
    
    Spacer(Modifier.height(12.dp))
    
    val context = androidx.compose.ui.platform.LocalContext.current
    
    Button(
        onClick = {
            scope.launch {
                onLoadingChange(true)
                val result = accountRepo.pinCertificate(accountId)
                onLoadingChange(false)
                when (result) {
                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                        accountRepo.getAccount(accountId)?.let { onAccountUpdated(it) }
                        android.widget.Toast.makeText(
                            context,
                            if (isRu) "✅ Защита включена" else "✅ Protection enabled",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {
                        android.widget.Toast.makeText(
                            context,
                            if (isRu) 
                                "❌ Ошибка: ${result.message}" 
                            else 
                                "❌ Error: ${result.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(AppIcons.Lock, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (isRu) "Включить защиту" else "Enable protection")
    }
    
    Spacer(Modifier.height(8.dp))
    
    Text(
        text = if (isRu)
            "ℹ️ Certificate Pinning защищает от MITM-атак, привязывая соединение к конкретному сертификату сервера."
        else
            "ℹ️ Certificate Pinning protects against MITM attacks by binding the connection to a specific server certificate.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    Spacer(Modifier.height(12.dp))
    
    // Подробное объяснение
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (isRu) "📋 Подробнее о защите" else "📋 Protection Details",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = if (isRu) "✅ Что защищено:" else "✅ What's Protected:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
            Text(
                text = if (isRu)
                    "• Шифрование трафика (HTTPS/TLS)\n• Проверка сертификата от доверенного CA\n• Защита от большинства MITM-атак"
                else
                    "• Traffic encryption (HTTPS/TLS)\n• Certificate verification from trusted CA\n• Protection from most MITM attacks",
                style = MaterialTheme.typography.bodySmall
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = if (isRu) "🔒 Certificate Pinning добавляет:" else "🔒 Certificate Pinning Adds:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2196F3)
            )
            Text(
                text = if (isRu)
                    "• Защиту от скомпрометированных CA\n• Проверку публичного ключа сервера\n• Обнаружение подмены сертификата"
                else
                    "• Protection from compromised CAs\n• Verification of server's public key\n• Detection of certificate substitution",
                style = MaterialTheme.typography.bodySmall
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = if (isRu) "⚠️ Не работает, если:" else "⚠️ Won't Work If:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9800)
            )
            Text(
                text = if (isRu)
                    "• Публичный ключ сертификата изменился (смена ключевой пары)\n• Ошибка при получении сертификата"
                else
                    "• Certificate's public key changed (key pair rotation)\n• Error retrieving certificate",
                style = MaterialTheme.typography.bodySmall
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = if (isRu) "✅ Работает при:" else "✅ Works When:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
            Text(
                text = if (isRu)
                    "• Обновлении сертификата с тем же ключом (продление срока)\n• Смене домена (внутренний ↔ внешний) с тем же ключом\n• Использовании разных CN в сертификате\n• Работе с разными доменами (внутренний/внешний)"
                else
                    "• Certificate renewal with same key (expiration extension)\n• Domain change (internal ↔ external) with same key\n• Using different CNs in certificate\n• Works with different domains (internal/external)",
                style = MaterialTheme.typography.bodySmall
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = if (isRu) "💡 Рекомендация:" else "💡 Recommendation:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isRu)
                    "Включите защиту для максимальной безопасности. Обновление сертификата (без смены ключа) не требует действий. При смене ключевой пары вы получите уведомление."
                else
                    "Enable protection for maximum security. Certificate renewal (without key change) requires no action. You'll be notified when key pair changes.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Детали сертификата
 */
@Composable
private fun CertificateDetails(
    cert: com.dedovmosol.iwomail.network.HttpClientProvider.CertificateInfo,
    isRu: Boolean,
    isOld: Boolean
) {
    val textColor = if (isOld) Color.Unspecified else Color.Red
    
    Text("• CN: ${cert.cn}", style = MaterialTheme.typography.bodySmall, color = textColor)
    Text("• ${if (isRu) "Организация" else "Organization"}: ${cert.organization}", style = MaterialTheme.typography.bodySmall, color = textColor)
    Text("• ${if (isRu) "Выдан" else "Issued"}: ${formatDate(cert.validFrom)}", style = MaterialTheme.typography.bodySmall, color = textColor)
    Text("• ${if (isRu) "Истекает" else "Expires"}: ${formatDate(cert.validTo)}", style = MaterialTheme.typography.bodySmall, color = textColor)
    Text("• Hash: ${cert.hash.take(16)}...", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = textColor)
}

/**
 * Форматирование даты
 */
private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
