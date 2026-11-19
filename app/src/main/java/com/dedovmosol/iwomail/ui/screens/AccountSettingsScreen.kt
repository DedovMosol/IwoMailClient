package com.dedovmosol.iwomail.ui.screens

import android.net.Uri
import com.dedovmosol.iwomail.ui.components.ScrollbarColor
import com.dedovmosol.iwomail.ui.components.launchLeavingApp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import com.dedovmosol.iwomail.util.SafeToast
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.theme.DeleteButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    accountId: Long,
    onBackClick: () -> Unit,
    onEditCredentials: (Long) -> Unit = {},
    onNavigateToSyncCleanup: (Long) -> Unit = {},
    onNoAccountsLeft: () -> Unit = {},
    /** id строки из поиска настроек ([rememberSettingsCatalog]): подсветить и прокрутить к ней. */
    highlight: String = ""
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }
    val isRu = isRussian()

    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var signatures by remember { mutableStateOf<List<com.dedovmosol.iwomail.data.database.SignatureEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(accountId) {
        isLoading = true
        account = accountRepo.getAccount(accountId)
        signatures = database.signatureDao().getSignaturesByAccountList(accountId)
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val currentAccount = account ?: return

    val accountType = AccountType.fromStored(currentAccount.accountType)



    // Диалоги
    var showDeleteAccount by rememberSaveable { mutableStateOf(false) }
    var showSignaturesDialog by rememberSaveable { mutableStateOf(false) }
    var showCertificateDialog by rememberSaveable { mutableStateOf(false) }
    var showClientCertificateDialog by rememberSaveable { mutableStateOf(false) }
    var showClientCertPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var pendingClientCertPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingClientCertFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOldClientCertPath by rememberSaveable { mutableStateOf<String?>(null) }
    var clientCertPasswordInput by rememberSaveable { mutableStateOf("") }
    var clientCertPasswordVisible by rememberSaveable { mutableStateOf(false) }

    // Пикеры для серверного сертификата

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
                            SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateExported(isRu))
                        }
                    }
                } catch (e: Exception) {
                    SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getExportError(isRu))
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
                    val originalFileName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.dedovmosol.iwomail.util.queryOpenableFileMeta(context.contentResolver, selectedUri)?.name
                    }

                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in validExtensions) {
                        SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getInvalidFileFormat(isRu))
                        return@launch
                    }

                    val fileName = "cert_${currentAccount.id}_${System.currentTimeMillis()}.$extension"
                    val certFile = java.io.File(context.filesDir, fileName)
                    val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.dedovmosol.iwomail.util.copyUriLimited(
                            context.contentResolver, selectedUri, certFile,
                            com.dedovmosol.iwomail.util.MAX_CERTIFICATE_BYTES
                        )
                    }
                    if (!copied) {
                        SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateLoadingError(isRu))
                        return@launch
                    }
                    currentAccount.certificatePath?.let { oldPath ->
                        try { java.io.File(oldPath).delete() } catch (_: Exception) {}
                    }

                    accountRepo.updateCertificatePath(accountId, certFile.absolutePath)
                    account = accountRepo.getAccount(accountId)

                    // Автоматически включаем Certificate Pinning после загрузки сертификата
                    val pinResult = accountRepo.pinCertificate(accountId)
                    when (pinResult) {
                        is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                            account = accountRepo.getAccount(accountId)
                            SafeToast.long(context, NotificationStrings.getCertLoadedProtected(isRu))
                        }
                        is com.dedovmosol.iwomail.eas.EasResult.Error -> {
                            android.util.Log.w("AccountSettings", "Failed to auto-enable certificate pinning: ${pinResult.message}")
                            SafeToast.long(context, NotificationStrings.getCertLoadedUnprotected(pinResult.message, isRu))
                        }
                    }
                } catch (e: Exception) {
                    SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateLoadingError(isRu))
                }
            }
        }
    }


    // Пикеры для клиентского сертификата

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
                            SafeToast.short(context, if (isRu) "Клиентский сертификат экспортирован" else "Client certificate exported")
                        }
                    }
                } catch (e: Exception) {
                    SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getExportError(isRu))
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
                    val originalFileName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.dedovmosol.iwomail.util.queryOpenableFileMeta(context.contentResolver, selectedUri)?.name
                    }

                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in validExtensions) {
                        SafeToast.short(context, if (isRu) "Неверный формат файла. Выберите .p12 или .pfx" else "Invalid file format. Select .p12 or .pfx")
                        return@launch
                    }

                    val fileName = "client_cert_${currentAccount.id}_${System.currentTimeMillis()}.$extension"
                    val certFile = java.io.File(context.filesDir, fileName)
                    val oldCertPath = currentAccount.clientCertificatePath

                    val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.dedovmosol.iwomail.util.copyUriLimited(
                            context.contentResolver, selectedUri, certFile,
                            com.dedovmosol.iwomail.util.MAX_CERTIFICATE_BYTES
                        )
                    }
                    if (!copied) {
                        SafeToast.short(context, NotificationStrings.getCertificateLoadingError(isRu))
                        return@launch
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
                    SafeToast.short(context, NotificationStrings.getCertificateLoadingError(isRu))
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
    val safeCertPath = currentAccount.certificatePath
    if (showCertificateDialog && !safeCertPath.isNullOrBlank()) {
        val certFile = java.io.File(safeCertPath)
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
                                // КРИТИЧНО: При удалении сертификата полностью отключаем Certificate Pinning
                                accountRepo.updatePinnedCertHash(accountId, null)
                                accountRepo.updateCertificatePinningEnabled(accountId, false)
                                accountRepo.updateCertificatePath(accountId, null)
                                account = accountRepo.getAccount(accountId)
                            }
                            SafeToast.short(context, com.dedovmosol.iwomail.ui.NotificationStrings.getCertificateRemoved(isRu))
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
                                exportCertificatePicker.launchLeavingApp(certFile.name)
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
                                certificatePicker.launchLeavingApp(arrayOf("*/*"))
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
    val safeClientCertPath = currentAccount.clientCertificatePath
    if (showClientCertificateDialog && !safeClientCertPath.isNullOrBlank()) {
        val certFile = java.io.File(safeClientCertPath)
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
                            SafeToast.short(context, if (isRu) "Клиентский сертификат удалён" else "Client certificate removed")
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
                                exportClientCertificatePicker.launchLeavingApp(certFile.name)
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
                                clientCertificatePicker.launchLeavingApp(arrayOf("*/*"))
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
                            SafeToast.short(context, if (isRu) "Введите пароль сертификата" else "Enter certificate password")
                        } else {
                            val newPath = pendingClientCertPath
                            if (newPath != null) {
                                val isValid = com.dedovmosol.iwomail.network.HttpClientProvider
                                    .validateClientCertificate(newPath, clientCertPasswordInput)
                                if (!isValid) {
                                    SafeToast.short(context, if (isRu) "Неверный пароль или повреждённый сертификат" else "Invalid password or corrupted certificate")
                                } else {
                                    val oldPath = pendingOldClientCertPath
                                    scope.launch {
                                        accountRepo.updateClientCertificatePath(accountId, newPath)
                                        accountRepo.updateClientCertificatePassword(accountId, clientCertPasswordInput)
                                        oldPath?.let { path -> try { java.io.File(path).delete() } catch (_: Exception) {} }
                                        com.dedovmosol.iwomail.network.HttpClientProvider.clearAllCertificateCache()
                                        account = accountRepo.getAccount(accountId)
                                        SafeToast.short(context, if (isRu) "Клиентский сертификат обновлён" else "Client certificate updated")
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


    var deletingAccount by remember { mutableStateOf(false) }
    if (showDeleteAccount) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { if (!deletingAccount) showDeleteAccount = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteAccount) },
            text = { Text("${currentAccount.displayName}: ${Strings.deleteAccountConfirm}") },
            confirmButton = {
                DeleteButton(
                    enabled = !deletingAccount,
                    onClick = {
                        if (deletingAccount) return@DeleteButton
                        deletingAccount = true
                        scope.launch {
                            try {
                                val remaining = accountRepo.deleteAccount(currentAccount.id)
                                showDeleteAccount = false
                                if (remaining == 0) onNoAccountsLeft() else onBackClick()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                deletingAccount = false
                            }
                        }
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    enabled = !deletingAccount,
                    onClick = { showDeleteAccount = false },
                    text = Strings.cancel
                )
            }
        )
    }

    val currentDraftMode = try {
        DraftMode.valueOf(currentAccount.draftMode)
    } catch (_: Exception) {
        DraftMode.SERVER
    }
    val hasTlsEndpoint = com.dedovmosol.iwomail.data.repository.ServerCertificateEndpoint.of(currentAccount) != null

    SettingsPageScaffold(title = Strings.accountSettings, onBackClick = onBackClick, hue = SettingsSearchDest.Accounts.hue) {
        // Заголовок аккаунта и состояние подключения
        item {
            SettingsGroup {
                val accountColor = Color(currentAccount.color)
                ListItem(
                    headlineContent = {
                        Text(currentAccount.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    },
                    supportingContent = {
                        Column {
                            Text(currentAccount.email)
                            Text(accountType.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                color = Color(contrastingContentArgb(currentAccount.color)),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ConnectionStatusBlock(
                    accountId = accountId,
                    serverUrl = currentAccount.serverUrl,
                    alternateServerUrl = currentAccount.alternateServerUrl,
                    port = currentAccount.incomingPort
                )
            }
        }

        // Остальное — одним элементом списка: строку из поиска ([highlight]) внизу страницы есть кому подсветить и
        // прокрутить к ней (элемент LazyColumn за экраном не создаётся). Страница короткая — ленивость не нужна.
        item {
            SettingsSectionTitle(Strings.accountSection)
            SettingsGroup {
                SettingsNavRow(
                    title = Strings.changeCredentials,
                    icon = AppIcons.Edit,
                    highlightId = "credentials",
                    highlight = highlight,
                    onClick = { onEditCredentials(accountId) }
                )
                SettingsNavRow(
                    title = Strings.signature,
                    icon = AppIcons.Draw,
                    value = if (signatures.isEmpty()) Strings.noSignature else Strings.signaturesCount(signatures.size),
                    highlightId = "signature",
                    highlight = highlight,
                    onClick = { showSignaturesDialog = true }
                )
                if (accountType == AccountType.EXCHANGE) {
                    SettingsChoiceRow(
                        title = Strings.draftMode,
                        options = listOf(DraftMode.SERVER, DraftMode.LOCAL),
                        selected = currentDraftMode,
                        label = { if (it == DraftMode.LOCAL) Strings.draftModeLocal else Strings.draftModeServer },
                        icon = AppIcons.Drafts,
                        highlightId = "draftMode",
                        highlight = highlight,
                        onSelect = { mode ->
                            scope.launch {
                                accountRepo.updateDraftMode(accountId, mode.name)
                                account = accountRepo.getAccount(accountId)
                            }
                        }
                    )
                }
                SettingsNavRow(
                    title = Strings.syncAndCleanup,
                    subtitle = Strings.syncAndCleanupDesc,
                    icon = AppIcons.Sync,
                    highlightId = "syncAndCleanup",
                    highlight = highlight,
                    onClick = { onNavigateToSyncCleanup(accountId) }
                )
            }

            // Сертификаты и доверие серверу
            val hasServerCert = !currentAccount.certificatePath.isNullOrBlank()
            val hasClientCert = !currentAccount.clientCertificatePath.isNullOrBlank()
            if (hasServerCert || hasClientCert || hasTlsEndpoint) {
                SettingsSectionTitle(Strings.securitySection)
            }
            if (hasServerCert || hasClientCert) {
                SettingsGroup {
                    if (hasServerCert) {
                        SettingsNavRow(
                            title = Strings.serverCertificate,
                            subtitle = java.io.File(currentAccount.certificatePath!!).name,
                            icon = AppIcons.Lock,
                            highlightId = "serverCertificate",
                            highlight = highlight,
                            onClick = { showCertificateDialog = true }
                        )
                    }
                    if (hasClientCert) {
                        SettingsNavRow(
                            title = Strings.clientCertificate,
                            subtitle = java.io.File(currentAccount.clientCertificatePath!!).name,
                            icon = AppIcons.Lock,
                            highlightId = "clientCertificate",
                            highlight = highlight,
                            onClick = { showClientCertificateDialog = true }
                        )
                    }
                }
            }
            // С импортированным сертификатом — закрепление его ключа; без него — доверие по отпечатку (ONB-08).
            // У Exchange по http TLS нет — карточки нет.
            if (hasServerCert || hasTlsEndpoint) {
                CertificatePinningCard(
                    account = currentAccount,
                    accountRepo = accountRepo,
                    onAccountUpdated = { updatedAccount -> account = updatedAccount }
                )
            }

            SettingsGroup {
                SettingsNavRow(
                    title = Strings.deleteAccountAction,
                    titleColor = MaterialTheme.colorScheme.error,
                    leading = { SettingsIconTile(AppIcons.Delete, hue = ScrollbarColor.RED) },
                    trailingIcon = null,
                    highlightId = "deleteAccount",
                    highlight = highlight,
                    onClick = { showDeleteAccount = true }
                )
            }
        }
    }
}

/**
 * Защита от подмены сервера: закрепление ключа сертификата и его состояние. Цвет плитки — состояние: смена
 * сертификата — ошибка, ключ закреплён — успех, иначе — предупреждение.
 */
@Composable
fun CertificatePinningCard(
    account: AccountEntity,
    accountRepo: AccountRepository,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    var isLoading by remember { mutableStateOf(false) }

    // Обнаруженная смена сертификата — подписка: карточка обновляется сама после «Принять» (в том числе
    // сертификата резервного сервера, когда пин основного не меняется), «Отключить» и при новом обнаружении.
    val certificateChanges by com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.changes.collectAsStateWithLifecycle()
    val certificateChange = certificateChanges[account.id]

    val statusColor = when {
        certificateChange != null -> MaterialTheme.colorScheme.error
        account.pinnedCertificateHash != null -> SettingsTokens.successColor
        else -> SettingsTokens.warningColor
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsTokens.HorizontalPadding, vertical = 6.dp),
        shape = SettingsTokens.CardShape,
        colors = CardDefaults.cardColors(containerColor = SettingsTokens.cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(SettingsTokens.TileSize)
                        .clip(SettingsTokens.TileShape)
                        .background(statusColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (account.pinnedCertificateHash != null) AppIcons.Lock else AppIcons.Security,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(text = Strings.certProtectionTitle, style = MaterialTheme.typography.titleSmall)
            }

            Spacer(Modifier.height(12.dp))

            when {
                certificateChange != null -> CertificateChangeWarning(
                    certificateChange = certificateChange,
                    accountId = account.id,
                    accountRepo = accountRepo,
                    hasDualUrl = !account.alternateServerUrl.isNullOrBlank(),
                    isAlternate = com.dedovmosol.iwomail.data.repository.CertificatePins
                        .isAlternateEndpoint(account, certificateChange.hostname),
                    isFirstAlternateCertificate = account.alternatePinnedCertificateHash == null,
                    // Ключ закреплён по отпечатку (без файла): «Отключить» оставило бы аккаунт без доверия
                    // вовсе — соединение с внутренним УЦ перестало бы работать
                    canDisable = account.certificatePath != null,
                    onAccountUpdated = onAccountUpdated
                )
                // Без импортированного сертификата — доверие по отпечатку (ONB-08)
                account.certificatePath == null -> TrustByFingerprint(account, accountRepo, onAccountUpdated)
                account.pinnedCertificateHash != null -> CertificatePinnedInfo(
                    account = account,
                    accountRepo = accountRepo,
                    isLoading = isLoading,
                    onLoadingChange = { isLoading = it },
                    onAccountUpdated = onAccountUpdated
                )
                else -> CertificatePinningDisabled(
                    accountId = account.id,
                    accountRepo = accountRepo,
                    isLoading = isLoading,
                    onLoadingChange = { isLoading = it },
                    onAccountUpdated = onAccountUpdated
                )
            }
        }
    }
}

/** Строка «подпись: значение» в описании сертификата. */
@Composable
private fun CertLine(label: String, value: String, color: Color = Color.Unspecified, monospace: Boolean = false) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontFamily = if (monospace) FontFamily.Monospace else null
    )
}

/**
 * ONB-08: без импортированного сертификата. Ключ закреплён — чему доверяем и проверка заново. «Принимать все» —
 * проверить сертификат сервера: система ему доверяет — перейти на системную проверку, нет — закрепить ключ
 * после сверки отпечатка. Системная проверка (без «Принимать все» и без пина) — действий не нужно: закреплять
 * ключ сертификата от доверенного УЦ хуже — продление со сменой ключа вызвало бы предупреждение.
 */
@Composable
private fun TrustByFingerprint(
    account: AccountEntity,
    accountRepo: AccountRepository,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val isRu = isRussian()
    var fetching by remember { mutableStateOf(false) }
    var toConfirm by remember { mutableStateOf<com.dedovmosol.iwomail.network.HttpClientProvider.ServerCertificate?>(null) }
    val doneText = Strings.certTrustDone
    val systemText = Strings.certSystemTrustDone
    val pinned = account.pinnedCertificateHash != null && !account.acceptAllCerts

    when {
        pinned -> {
            Text(Strings.certTrustDone, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            CertLine(Strings.certIssuedTo, account.pinnedCertificateCN ?: Strings.certUnknown)
            account.pinnedCertificateValidTo?.let {
                CertLine(Strings.certValidity, "${formatDate(account.pinnedCertificateValidFrom ?: 0L)} – ${formatDate(it)}")
            }
            CertLine("SPKI SHA-256", "${account.pinnedCertificateHash?.take(16)}…", monospace = true)
        }
        account.acceptAllCerts -> Text(Strings.certAcceptAllWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> {
            Text(Strings.certVerifiedBySystem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
    }
    Spacer(Modifier.height(12.dp))
    com.dedovmosol.iwomail.ui.theme.ThemeButton(
        onClick = {
            if (fetching) return@ThemeButton
            fetching = true
            scope.launch {
                when (val result = accountRepo.fetchServerCertificate(account)) {
                    is com.dedovmosol.iwomail.eas.EasResult.Success -> toConfirm = result.data
                    is com.dedovmosol.iwomail.eas.EasResult.Error -> SafeToast.long(
                        context, NotificationStrings.localizeError(result.message, isRu)
                    )
                }
                fetching = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
        isLoading = fetching
    ) {
        Icon(AppIcons.Lock, null, modifier = Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(Strings.certTrustFromServer, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(6.dp))
    Text(Strings.certTrustFromServerDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    toConfirm?.let { cert ->
        val label = com.dedovmosol.iwomail.data.repository.ServerCertificateEndpoint.of(account)?.let { "${it.host}:${it.port}" } ?: account.serverUrl
        com.dedovmosol.iwomail.ui.components.ServerCertificateDialog(
            certificate = cert,
            serverLabel = label,
            onTrust = {
                toConfirm = null
                scope.launch {
                    accountRepo.trustServerCertificate(account.id, cert.info)
                    accountRepo.getAccount(account.id)?.let(onAccountUpdated)
                    SafeToast.short(context, doneText)
                }
            },
            onUseSystem = {
                toConfirm = null
                scope.launch {
                    accountRepo.useSystemTrust(account.id)
                    accountRepo.getAccount(account.id)?.let(onAccountUpdated)
                    SafeToast.short(context, systemText)
                }
            },
            onDismiss = { toConfirm = null }
        )
    }
}

/** Предупреждение об изменении сертификата: прежний и новый рядом, «Принять» или «Отключить». */
@Composable
private fun CertificateChangeWarning(
    certificateChange: com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector.CertificateChange,
    accountId: Long,
    accountRepo: AccountRepository,
    hasDualUrl: Boolean = false,
    isAlternate: Boolean = false,
    isFirstAlternateCertificate: Boolean = false,
    canDisable: Boolean = true,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val error = MaterialTheme.colorScheme.error

    Text(Strings.certNewDetected, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = error)
    Spacer(Modifier.height(8.dp))
    Text(
        "${if (isAlternate) Strings.certBackupServer else Strings.certServer}: ${certificateChange.hostname}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(Strings.certOld, style = MaterialTheme.typography.labelMedium)
    CertificateDetails(certificateChange.oldCert)
    Spacer(Modifier.height(8.dp))
    Text(Strings.certNew, style = MaterialTheme.typography.labelMedium, color = error)
    CertificateDetails(certificateChange.newCert, color = error)
    Spacer(Modifier.height(8.dp))
    Text(Strings.certChangeAdvice, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    val note = when {
        isAlternate && isFirstAlternateCertificate -> Strings.certFirstBackupNote
        hasDualUrl -> Strings.certDualUrlNote
        else -> null
    }
    if (note != null) {
        Spacer(Modifier.height(4.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.dedovmosol.iwomail.ui.theme.ThemeButton(
            onClick = {
                scope.launch {
                    // В пин того сервера, где обнаружен сертификат (основной или резервный)
                    accountRepo.acceptChangedCertificate(accountId, certificateChange)
                    accountRepo.getAccount(accountId)?.let { onAccountUpdated(it) }
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(Strings.certAccept, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        if (canDisable) DangerButton(
            text = Strings.certDisable,
            modifier = Modifier.weight(1f),
            onClick = {
                scope.launch {
                    accountRepo.updatePinnedCertHash(accountId, null)
                    accountRepo.getAccount(accountId)?.let { onAccountUpdated(it) }
                }
            }
        )
    }
}

/** Кнопка необратимого действия: цвет ошибки темы. */
@Composable
private fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Импортированный сертификат, ключ закреплён: сведения, «Обновить» (закрепить заново) и «Отключить». */
@Composable
private fun CertificatePinnedInfo(
    account: AccountEntity,
    accountRepo: AccountRepository,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val unknown = Strings.certUnknown

    Text(Strings.certProtectionOn, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SettingsTokens.successColor)
    Spacer(Modifier.height(8.dp))
    CertLine("CN", account.pinnedCertificateCN ?: unknown)
    CertLine(Strings.certOrganization, account.pinnedCertificateOrg ?: unknown)
    account.pinnedCertificateValidFrom?.let { CertLine(Strings.certIssuedOn, formatDate(it)) }
    account.pinnedCertificateValidTo?.let { CertLine(Strings.certExpires, formatDate(it)) }
    CertLine("SPKI SHA-256", "${account.pinnedCertificateHash?.take(16)}…", monospace = true)
    // Сертификат резервного сервера (Dual-URL) хранится отдельно — см. CertificatePins
    account.alternatePinnedCertificateHash?.let { backupHash ->
        Spacer(Modifier.height(4.dp))
        CertLine(
            Strings.certBackupServer,
            (account.alternatePinnedCertificateCN ?: unknown) +
                (account.alternatePinnedCertificateValidTo?.let { ", ${Strings.certExpires.lowercase()} ${formatDate(it)}" } ?: "")
        )
        CertLine("SPKI SHA-256", "${backupHash.take(16)}…", monospace = true)
    }
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.dedovmosol.iwomail.ui.theme.ThemeButton(
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
            isLoading = isLoading,
            modifier = Modifier.weight(1f)
        ) {
            Icon(AppIcons.Refresh, null, modifier = Modifier.size(18.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(Strings.certUpdate, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        DangerButton(
            text = Strings.certDisable,
            enabled = !isLoading,
            modifier = Modifier.weight(1f),
            onClick = {
                scope.launch {
                    accountRepo.updatePinnedCertHash(account.id, null)
                    accountRepo.getAccount(account.id)?.let { onAccountUpdated(it) }
                }
            }
        )
    }
}

/** Импортированный сертификат без закрепления: включить защиту. */
@Composable
private fun CertificatePinningDisabled(
    accountId: Long,
    accountRepo: AccountRepository,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onAccountUpdated: (AccountEntity) -> Unit
) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val isRu = isRussian()

    Text(Strings.certProtectionOff, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SettingsTokens.warningColor)
    Spacer(Modifier.height(4.dp))
    Text(Strings.certProtectionOffDesc, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    com.dedovmosol.iwomail.ui.theme.ThemeButton(
        onClick = {
            scope.launch {
                onLoadingChange(true)
                val result = accountRepo.pinCertificate(accountId)
                onLoadingChange(false)
                when (result) {
                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                        accountRepo.getAccount(accountId)?.let { onAccountUpdated(it) }
                        SafeToast.short(context, NotificationStrings.getCertProtectionEnabled(isRu))
                    }
                    is com.dedovmosol.iwomail.eas.EasResult.Error ->
                        SafeToast.long(context, NotificationStrings.getCertProtectionError(result.message, isRu))
                }
            }
        },
        enabled = !isLoading,
        isLoading = isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(AppIcons.Lock, null, modifier = Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(4.dp))
        Text(Strings.certEnableProtection, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(8.dp))
    Text(Strings.certPinningExplain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Сведения о сертификате в предупреждении о смене. */
@Composable
private fun CertificateDetails(
    cert: com.dedovmosol.iwomail.network.HttpClientProvider.CertificateInfo,
    color: Color = Color.Unspecified
) {
    CertLine("CN", cert.cn, color)
    CertLine(Strings.certOrganization, cert.organization, color)
    CertLine(Strings.certIssuedOn, formatDate(cert.validFrom), color)
    CertLine(Strings.certExpires, formatDate(cert.validTo), color)
    CertLine("SPKI SHA-256", "${cert.hash.take(16)}…", color, monospace = true)
}

private val ACCOUNT_DATE_FORMAT = java.lang.ThreadLocal.withInitial {
    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
}

private fun formatDate(timestamp: Long): String {
    return ACCOUNT_DATE_FORMAT.get()?.format(java.util.Date(timestamp)) ?: ""
}

@Composable
private fun ConnectionStatusBlock(
    accountId: Long,
    serverUrl: String,
    alternateServerUrl: String?,
    port: Int
) {
    val health by com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
        .healthFlow(accountId).collectAsStateWithLifecycle()

    val isRu = isRussian()
    val statusText: String
    val statusColor: Color

    when (health.problemType) {
        com.dedovmosol.iwomail.data.model.ServerProblemType.None -> {
            statusText = Strings.connectedOk
            statusColor = SettingsTokens.successColor
        }
        com.dedovmosol.iwomail.data.model.ServerProblemType.PrimaryDownUsingFallback -> {
            statusText = Strings.usingFallbackServer
            statusColor = SettingsTokens.warningColor
        }
        com.dedovmosol.iwomail.data.model.ServerProblemType.AuthError -> {
            statusText = Strings.authErrorServer
            statusColor = MaterialTheme.colorScheme.error
        }
        com.dedovmosol.iwomail.data.model.ServerProblemType.CertError -> {
            statusText = Strings.certErrorServer
            statusColor = MaterialTheme.colorScheme.error
        }
        else -> {
            statusText = Strings.serverUnavailable
            statusColor = MaterialTheme.colorScheme.error
        }
    }

    // host:port как в запросах: порт из адреса важнее порта аккаунта (ServerAddress)
    fun endpointLabel(url: String) = com.dedovmosol.iwomail.eas.ServerAddress.parse(url, port)?.display ?: url
    val activeEndpoint = if (health.isUsingFallback && !alternateServerUrl.isNullOrBlank()) {
        endpointLabel(alternateServerUrl)
    } else {
        endpointLabel(serverUrl)
    }

    val backupLabel = if (health.isUsingFallback) " (${Strings.certBackupServer.lowercase()})" else ""

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = Strings.connectionStatus,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${Strings.activeEndpoint}: $activeEndpoint$backupLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "${Strings.lastSync} ${Strings.timeAgo(health.lastSuccessAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        health.lastErrorMessage?.let { rawError ->
            // Коротко и на языке интерфейса; кнопка копирует исходный текст целиком (для поддержки).
            val shortError = com.dedovmosol.iwomail.ui.NotificationStrings.connectionFailureTitle(rawError, isRu)
                ?: com.dedovmosol.iwomail.ui.NotificationStrings.localizeError(rawError, isRu)
            com.dedovmosol.iwomail.ui.components.CopyableErrorText(
                text = "${Strings.lastError}: $shortError",
                copyText = rawError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}
