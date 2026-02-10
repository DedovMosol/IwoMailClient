package com.dedovmosol.iwomail.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.utils.rememberRotation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Результат верификации email
 */
sealed class VerificationResult {
    object Success : VerificationResult()
    data class EmailMismatch(val enteredEmail: String, val actualEmail: String) : VerificationResult()
    data class Error(val message: String) : VerificationResult()
}

/**
 * Результат проверки доступа к делегированному ящику
 */
sealed class AccessVerificationResult {
    object HasAccess : AccessVerificationResult()
    object NoAccess : AccessVerificationResult()
    data class Error(val message: String) : AccessVerificationResult()
}

/**
 * Экран верификации email при добавлении Exchange аккаунта
 * Проверяет что введённый email соответствует реальному email на сервере
 * Получает только 1 письмо для быстрой проверки
 */
@Composable
fun VerificationScreen(
    email: String,
    displayName: String,
    serverUrl: String,
    username: String,
    password: String,
    domain: String,
    acceptAllCerts: Boolean,
    color: Int,
    incomingPort: Int,
    outgoingServer: String,
    outgoingPort: Int,
    useSSL: Boolean,
    syncMode: SyncMode,
    certificatePath: String? = null,
    clientCertificatePath: String? = null,
    clientCertificatePassword: String? = null,
    onSuccess: () -> Unit,
    onError: (String, String?) -> Unit // error, savedData
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
    
    // Получаем строки локализации в Composable контексте
    val verifyingAccountText = Strings.verifyingAccount
    val verifyingEmailText = Strings.verifyingEmail
    val sendingTestEmailText = Strings.sendingTestEmail
    val testEmailSubjectText = Strings.testEmailSubject
    val testEmailBodyText = Strings.testEmailBody
    val emailMismatchTitle = Strings.emailMismatch
    val isRussianLang = com.dedovmosol.iwomail.ui.isRussian()
    
    // Функция для создания savedData (сохраняем всё кроме domain, username, password)
    fun createSavedData(): String {
        val certPath = certificatePath ?: ""
        val clientCertPath = clientCertificatePath ?: ""
        return "$email|$displayName|$serverUrl|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|${syncMode.name}|$certPath|$clientCertPath"
    }
    
    // Функция для создания savedData при несовпадении email (сохраняем ВСЁ включая domain, username, password)
    fun createSavedDataForEmailMismatch(): String {
        val certPath = certificatePath ?: ""
        val clientCertPath = clientCertificatePath ?: ""
        return "$email|$displayName|$serverUrl|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|${syncMode.name}|$certPath|$domain|$username|$password|$clientCertPath"
    }
    
    var statusText by remember { mutableStateOf(verifyingAccountText) }
    var showMismatchDialog by remember { mutableStateOf(false) }
    var mismatchEnteredEmail by remember { mutableStateOf("") }
    var mismatchActualEmail by remember { mutableStateOf("") }
    var isCheckingAccess by remember { mutableStateOf(false) }
    var accessCheckResult by remember { mutableStateOf<AccessVerificationResult?>(null) }
    
    // Анимация вращения - создаём всегда, применяем только если animationsEnabled
    val rotation = rememberRotation(animationsEnabled, durationMs = 1000)

    // Запускаем верификацию
    LaunchedEffect(Unit) {
        scope.launch {
            val result = verifyEmail(
                email = email,
                serverUrl = serverUrl,
                username = username,
                password = password,
                domain = domain,
                acceptAllCerts = acceptAllCerts,
                port = incomingPort,
                useSSL = useSSL,
                certificatePath = certificatePath,
                clientCertificatePath = clientCertificatePath,
                clientCertificatePassword = clientCertificatePassword,
                verifyingAccountText = verifyingAccountText,
                verifyingEmailText = verifyingEmailText,
                sendingTestEmailText = sendingTestEmailText,
                testEmailSubjectText = testEmailSubjectText,
                testEmailBodyText = testEmailBodyText,
                onStatusChange = { statusText = it }
            )
            
            when (result) {
                is VerificationResult.Success -> {
                    // Email подтверждён — сохраняем аккаунт
                    val addResult = accountRepo.addAccount(
                        email = email,
                        displayName = displayName,
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        domain = domain,
                        acceptAllCerts = acceptAllCerts,
                        color = color,
                        accountType = AccountType.EXCHANGE,
                        incomingPort = incomingPort,
                        outgoingServer = outgoingServer,
                        outgoingPort = outgoingPort,
                        useSSL = useSSL,
                        syncMode = syncMode,
                        certificatePath = certificatePath,
                        clientCertificatePath = clientCertificatePath,
                        clientCertificatePassword = clientCertificatePassword
                    )
                    
                    when (addResult) {
                        is EasResult.Success -> {
                            val accountId = addResult.data
                            
                            // Автоматически включаем Certificate Pinning если был загружен сертификат
                            if (certificatePath != null) {
                                try {
                                    val pinResult = accountRepo.pinCertificate(accountId)
                                    if (pinResult is EasResult.Success) {
                                        android.util.Log.d("VerificationScreen", "Certificate Pinning enabled automatically for new account")
                                    } else if (pinResult is EasResult.Error) {
                                        android.util.Log.w("VerificationScreen", "Failed to auto-enable Certificate Pinning: ${pinResult.message}")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("VerificationScreen", "Exception during auto-enable Certificate Pinning", e)
                                }
                            }
                            
                            // Запускаем начальную синхронизацию в фоне
                            scope.launch {
                                val mailRepo = com.dedovmosol.iwomail.data.repository.MailRepository(context)
                                val settingsRepo = com.dedovmosol.iwomail.data.repository.SettingsRepository.getInstance(context)
                                com.dedovmosol.iwomail.ui.InitialSyncController.startSyncIfNeeded(
                                    context, accountId, mailRepo, settingsRepo
                                )
                            }
                            
                            // Пароль клиентского сертификата уже сохранен в addAccount()
                            onSuccess()
                        }
                        is EasResult.Error -> onError(addResult.message, createSavedData())
                    }
                }
                is VerificationResult.EmailMismatch -> {
                    // Показываем диалог
                    mismatchEnteredEmail = result.enteredEmail
                    mismatchActualEmail = result.actualEmail
                    showMismatchDialog = true
                }
                is VerificationResult.Error -> {
                    val errorMessage = when (result.message) {
                        "CLIENT_CERT_PASSWORD_REQUIRED" -> {
                            if (isRussianLang) 
                                "Требуется пароль клиентского сертификата" 
                            else 
                                "Client certificate password required"
                        }
                        "CLIENT_CERT_LOAD_FAILED" -> {
                            if (isRussianLang)
                                "Не удалось загрузить клиентский сертификат. Проверьте пароль."
                            else
                                "Failed to load client certificate. Check the password."
                        }
                        else -> NotificationStrings.localizeError(result.message, isRussianLang)
                    }
                    onError(errorMessage, createSavedData())
                }
            }
        }
    }
    
    // Диалог несовпадения email
    if (showMismatchDialog) {
        val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
        val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
        
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        
        val scale by animateFloatAsState(
            targetValue = if (visible && animationsEnabled) 1f else 0.8f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
            label = "scale"
        )
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(200),
            label = "alpha"
        )
        
        androidx.compose.ui.window.Dialog(onDismissRequest = { }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
            ) {
                Column {
                    // Градиентная полоска сверху
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                )
                            )
                    )
                    
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        // Иконка в градиентном круге
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .align(Alignment.CenterHorizontally)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                    ),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                AppIcons.Warning,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            emailMismatchTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            Strings.enteredEmail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            mismatchEnteredEmail,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            Strings.actualEmail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            mismatchActualEmail,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Сообщение о множественных ящиках
                        Text(
                            if (isRussianLang) 
                                "У вас может быть доступ к нескольким почтовым ящикам. Выберите действие:"
                            else 
                                "You may have access to multiple mailboxes. Choose an action:",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Justify
                        )
                        
                        // Результат проверки доступа
                        accessCheckResult?.let { result ->
                            Spacer(modifier = Modifier.height(12.dp))
                            when (result) {
                                is AccessVerificationResult.HasAccess -> {
                                    Text(
                                        if (isRussianLang) "✓ Доступ подтверждён — письмо найдено в Inbox" else "✓ Access verified — email found in Inbox",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                is AccessVerificationResult.NoAccess -> {
                                    Text(
                                        if (isRussianLang) "✗ Нет доступа — тестовое письмо не пришло в Inbox" else "✗ No access — test email not received in Inbox",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                is AccessVerificationResult.Error -> {
                                    val localizedMsg = NotificationStrings.localizeError(result.message, isRussianLang)
                                    Text(
                                        if (isRussianLang) "✗ Ошибка проверки: $localizedMsg" else "✗ Check error: $localizedMsg",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Индикатор проверки
                        if (isCheckingAccess) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    if (isRussianLang) "Проверка доступа..." else "Checking access...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        
                        // Кнопки
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Кнопка 1: Использовать основной ящик (рекомендуется)
                            com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                                onClick = {
                                    scope.launch {
                                        showMismatchDialog = false
                                        // Сохраняем аккаунт с actualEmail
                                        val addResult = accountRepo.addAccount(
                                            email = mismatchActualEmail,
                                            displayName = displayName,
                                            serverUrl = serverUrl,
                                            username = username,
                                            password = password,
                                            domain = domain,
                                            acceptAllCerts = acceptAllCerts,
                                            color = color,
                                            accountType = AccountType.EXCHANGE,
                                            incomingPort = incomingPort,
                                            outgoingServer = outgoingServer,
                                            outgoingPort = outgoingPort,
                                            useSSL = useSSL,
                                            syncMode = syncMode,
                                            certificatePath = certificatePath,
                                            clientCertificatePath = clientCertificatePath,
                                            clientCertificatePassword = clientCertificatePassword
                                        )
                                        
                                        when (addResult) {
                                            is EasResult.Success -> {
                                                val accountId = addResult.data
                                                
                                                // Автоматически включаем Certificate Pinning если был загружен сертификат
                                                if (certificatePath != null) {
                                                    try {
                                                        val pinResult = accountRepo.pinCertificate(accountId)
                                                        if (pinResult is EasResult.Success) {
                                                            android.util.Log.d("VerificationScreen", "Certificate Pinning enabled automatically (mismatch case 1)")
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.w("VerificationScreen", "Exception during auto-enable Certificate Pinning", e)
                                                    }
                                                }
                                                
                                                onSuccess()
                                            }
                                            is EasResult.Error -> onError(addResult.message, createSavedData())
                                        }
                                    }
                                },
                                text = if (isRussianLang) 
                                    "Использовать $mismatchActualEmail" 
                                else 
                                    "Use $mismatchActualEmail",
                                enabled = !isCheckingAccess,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            // Кнопка 2: Проверить доступ к введённому email
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isCheckingAccess = true
                                        accessCheckResult = null
                                        
                                        // Создаём временный клиент для проверки
                                        val tempClient = try {
                                            EasClient(
                                                serverUrl = serverUrl,
                                                username = username,
                                                password = password,
                                                domain = domain,
                                                acceptAllCerts = acceptAllCerts,
                                                port = incomingPort,
                                                useHttps = useSSL,
                                                deviceIdSuffix = email,
                                                certificatePath = certificatePath,
                                                clientCertificatePath = clientCertificatePath,
                                                clientCertificatePassword = clientCertificatePassword
                                            )
                                        } catch (e: Exception) {
                                            isCheckingAccess = false
                                            accessCheckResult = AccessVerificationResult.Error(e.message ?: "Unknown error")
                                            return@launch
                                        }
                                        
                                        // Получаем папки
                                        val foldersResult = tempClient.folderSync()
                                        if (foldersResult is EasResult.Success) {
                                            val folders = foldersResult.data.folders
                                            val inboxFolder = folders.find { it.type == FolderType.INBOX }
                                            val sentFolder = folders.find { it.type == FolderType.SENT_ITEMS }
                                            
                                            // Проверяем доступ
                                            val result = verifyAccessToEnteredEmail(
                                                client = tempClient,
                                                enteredEmail = mismatchEnteredEmail,
                                                inboxFolder = inboxFolder,
                                                sentFolder = sentFolder,
                                                testEmailSubject = testEmailSubjectText
                                            )
                                            
                                            accessCheckResult = result
                                            
                                            // Если доступ подтверждён (письмо найдено в Inbox) - сохраняем с введённым email
                                            if (result is AccessVerificationResult.HasAccess) {
                                                delay(1000) // Показываем результат
                                                showMismatchDialog = false
                                                
                                                val addResult = accountRepo.addAccount(
                                                    email = mismatchEnteredEmail,
                                                    displayName = displayName,
                                                    serverUrl = serverUrl,
                                                    username = username,
                                                    password = password,
                                                    domain = domain,
                                                    acceptAllCerts = acceptAllCerts,
                                                    color = color,
                                                    accountType = AccountType.EXCHANGE,
                                                    incomingPort = incomingPort,
                                                    outgoingServer = outgoingServer,
                                                    outgoingPort = outgoingPort,
                                                    useSSL = useSSL,
                                                    syncMode = syncMode,
                                                    certificatePath = certificatePath,
                                                    clientCertificatePath = clientCertificatePath,
                                                    clientCertificatePassword = clientCertificatePassword
                                                )
                                                
                                                when (addResult) {
                                                    is EasResult.Success -> {
                                                        val accountId = addResult.data
                                                        
                                                        // Автоматически включаем Certificate Pinning если был загружен сертификат
                                                        if (certificatePath != null) {
                                                            try {
                                                                val pinResult = accountRepo.pinCertificate(accountId)
                                                                if (pinResult is EasResult.Success) {
                                                                    android.util.Log.d("VerificationScreen", "Certificate Pinning enabled automatically (mismatch case 2)")
                                                                }
                                                            } catch (e: Exception) {
                                                                android.util.Log.w("VerificationScreen", "Exception during auto-enable Certificate Pinning", e)
                                                            }
                                                        }
                                                        
                                                        onSuccess()
                                                    }
                                                    is EasResult.Error -> onError(addResult.message, createSavedData())
                                                }
                                            }
                                        } else {
                                            accessCheckResult = AccessVerificationResult.Error((foldersResult as EasResult.Error).message)
                                        }
                                        
                                        isCheckingAccess = false
                                    }
                                },
                                enabled = !isCheckingAccess && accessCheckResult !is AccessVerificationResult.HasAccess,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (isRussianLang) 
                                        "Проверить доступ к $mismatchEnteredEmail" 
                                    else 
                                        "Check access to $mismatchEnteredEmail"
                                )
                            }
                            
                            // Кнопка 3: Отменить
                            TextButton(
                                onClick = {
                                    showMismatchDialog = false
                                    onError("CLEAR_EMAIL", createSavedDataForEmailMismatch())
                                },
                                enabled = !isCheckingAccess,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isRussianLang) "Отменить" else "Cancel")
                            }
                        }
                    }
                }
            }
        }
    }
    
    // UI
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Анимированный индикатор
            CircularProgressIndicator(
                modifier = Modifier
                    .size(64.dp)
                    .rotate(rotation),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Статус
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Email который проверяем
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Предкомпилированные regex для производительности
private val BRACKET_EMAIL_REGEX = "<([^>]+@[^>]+)>".toRegex()
private val SIMPLE_EMAIL_REGEX = "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})".toRegex()

/**
 * Извлекает email из строки формата "Name <email@domain.com>" или просто "email@domain.com"
 */
private fun extractEmailFromString(str: String): String {
    // Ищем email в угловых скобках
    val bracketMatch = BRACKET_EMAIL_REGEX.find(str)
    if (bracketMatch != null) {
        return bracketMatch.groupValues[1].lowercase().trim()
    }
    
    // Ищем просто email
    val emailMatch = SIMPLE_EMAIL_REGEX.find(str)
    if (emailMatch != null) {
        return emailMatch.groupValues[1].lowercase().trim()
    }
    
    return str.lowercase().trim()
}

/**
 * Сравнивает два email (без учёта регистра)
 */
private fun emailsMatch(email1: String, email2: String): Boolean {
    val e1 = extractEmailFromString(email1)
    val e2 = extractEmailFromString(email2)
    return e1.equals(e2, ignoreCase = true)
}

/**
 * Основная логика верификации email
 */
private suspend fun verifyEmail(
    email: String,
    serverUrl: String,
    username: String,
    password: String,
    domain: String,
    acceptAllCerts: Boolean,
    port: Int,
    useSSL: Boolean,
    certificatePath: String? = null,
    clientCertificatePath: String? = null,
    clientCertificatePassword: String? = null,
    verifyingAccountText: String,
    verifyingEmailText: String,
    sendingTestEmailText: String,
    testEmailSubjectText: String,
    testEmailBodyText: String,
    onStatusChange: (String) -> Unit
): VerificationResult {
    val client = try {
        EasClient(
            serverUrl = serverUrl,
            username = username,
            password = password,
            domain = domain,
            acceptAllCerts = acceptAllCerts,
            port = port,
            useHttps = useSSL,
            deviceIdSuffix = email,
            certificatePath = certificatePath,
            clientCertificatePath = clientCertificatePath,
            clientCertificatePassword = clientCertificatePassword
        )
    } catch (e: IllegalArgumentException) {
        if (e.message == "CLIENT_CERT_PASSWORD_REQUIRED") {
            return VerificationResult.Error("CLIENT_CERT_PASSWORD_REQUIRED")
        }
        return VerificationResult.Error(e.message ?: "Unknown error")
    }
    
    // Шаг 1: Получаем список папок
    onStatusChange(verifyingAccountText)
    
    val foldersResult = client.folderSync()
    if (foldersResult is EasResult.Error) {
        return VerificationResult.Error(foldersResult.message)
    }
    
    val folders = (foldersResult as EasResult.Success).data.folders
    
    // Находим папку "Отправленные"
    val sentFolder = folders.find { it.type == FolderType.SENT_ITEMS }
    // Находим папку "Входящие"
    val inboxFolder = folders.find { it.type == FolderType.INBOX }
    
    // Шаг 2: Проверяем "From" в отправленных
    if (sentFolder != null) {
        onStatusChange(verifyingEmailText)
        
        val sentResult = client.fetchOneEmailForVerification(sentFolder.serverId)
        if (sentResult is EasResult.Success && sentResult.data != null) {
            val sentEmail = sentResult.data
            val fromEmail = extractEmailFromString(sentEmail.from)
            
            if (fromEmail.isNotEmpty() && fromEmail.contains("@")) {
                if (emailsMatch(email, fromEmail)) {
                    return VerificationResult.Success
                } else {
                    return VerificationResult.EmailMismatch(email, fromEmail)
                }
            }
        }
    }
    
    // Шаг 3: Проверяем "To" во входящих
    if (inboxFolder != null) {
        onStatusChange(verifyingEmailText)
        
        val inboxResult = client.fetchOneEmailForVerification(inboxFolder.serverId)
        if (inboxResult is EasResult.Success && inboxResult.data != null) {
            val inboxEmail = inboxResult.data
            val toEmail = extractEmailFromString(inboxEmail.to)
            
            if (toEmail.isNotEmpty() && toEmail.contains("@")) {
                if (emailsMatch(email, toEmail)) {
                    return VerificationResult.Success
                } else {
                    return VerificationResult.EmailMismatch(email, toEmail)
                }
            }
        }
    }
    
    // Шаг 4: Отправляем тестовое письмо и проверяем "From"
    onStatusChange(sendingTestEmailText)
    
    val sendResult = client.sendMail(
        to = email,
        subject = testEmailSubjectText,
        body = testEmailBodyText
    )
    
    if (sendResult is EasResult.Error) {
        // Не удалось отправить — считаем что email верный (fallback)
        return VerificationResult.Success
    }
    
    // Ждём пока письмо дойдёт
    delay(3000)
    
    // Проверяем "From" в отправленных, итерируясь по всем batch'ам
    var fromEmail: String? = null
    
    if (sentFolder != null) {
        var found = false
        try {
            val initSync = client.sync(sentFolder.serverId, "0", 1)
            if (initSync is EasResult.Success) {
                var syncKey = initSync.data.syncKey
                var moreAvailable = true
                var iterations = 0
                
                while (moreAvailable && iterations < 30 && !found) {
                    iterations++
                    val batchResult = client.sync(sentFolder.serverId, syncKey, 50)
                    if (batchResult !is EasResult.Success) break
                    
                    syncKey = batchResult.data.syncKey
                    moreAvailable = batchResult.data.moreAvailable
                    
                    val testEmail = batchResult.data.emails.find {
                        it.subject.contains(testEmailSubjectText, ignoreCase = true)
                    }
                    if (testEmail != null) {
                        found = true
                        fromEmail = extractEmailFromString(testEmail.from)
                        // Удаляем тестовое письмо из Отправленных
                        try {
                            client.deleteEmailPermanently(sentFolder.serverId, testEmail.serverId, syncKey)
                        } catch (_: Exception) { }
                    }
                    
                    if (batchResult.data.emails.isEmpty() && batchResult.data.deletedIds.isEmpty()
                        && batchResult.data.changedEmails.isEmpty()) break
                }
            }
        } catch (_: Exception) { }
        
        // Если не нашли в первой итерации — повторная попытка через задержку
        if (!found) {
            delay(3000)
            try {
                findAndDeleteTestEmail(client, sentFolder.serverId, testEmailSubjectText)
            } catch (_: Exception) { }
        }
    }
    
    // Также удаляем из Входящих (письмо пришло самому себе)
    if (inboxFolder != null) {
        findAndDeleteTestEmail(client, inboxFolder.serverId, testEmailSubjectText)
    }
    
    // Проверяем FROM
    if (fromEmail != null && fromEmail.isNotEmpty() && fromEmail.contains("@")) {
        return if (emailsMatch(email, fromEmail)) {
            VerificationResult.Success
        } else {
            VerificationResult.EmailMismatch(email, fromEmail)
        }
    }
    
    // Если ничего не сработало — считаем email верным (fallback)
    return VerificationResult.Success
}

/**
 * Проверяет реальный доступ к введённому email путём отправки и получения тестового письма
 * Используется для делегированных/дополнительных ящиков
 */
private suspend fun verifyAccessToEnteredEmail(
    client: EasClient,
    enteredEmail: String,
    inboxFolder: com.dedovmosol.iwomail.eas.EasFolder?,
    sentFolder: com.dedovmosol.iwomail.eas.EasFolder?,
    testEmailSubject: String
): AccessVerificationResult {
    if (inboxFolder == null) {
        return AccessVerificationResult.Error("Inbox folder not found")
    }
    
    try {
        // Отправляем тестовое письмо НА введённый email
        val sendResult = client.sendMail(
            to = enteredEmail,
            subject = testEmailSubject,
            body = "Verification: checking access to mailbox"
        )
        
        if (sendResult is EasResult.Error) {
            // Не удалось отправить - возможно нет прав Send-As
            return AccessVerificationResult.NoAccess
        }
        
        // Ждём доставки письма (первая попытка)
        delay(3000)
        
        // Проверяем входящие - пришло ли письмо (попытка 1)
        var testEmail = findTestEmail(client, inboxFolder.serverId, testEmailSubject, enteredEmail)
        
        // Если не нашли - ждём ещё и пробуем снова
        if (testEmail == null) {
            delay(4000)
            testEmail = findTestEmail(client, inboxFolder.serverId, testEmailSubject, enteredEmail)
        }
        
        if (testEmail != null) {
            // Письмо найдено в Inbox - ДОКАЗАТЕЛЬСТВО доступа
            // Удаляем тестовое письмо из Входящих
            findAndDeleteTestEmail(client, inboxFolder.serverId, testEmailSubject)
            
            // Удаляем из Отправленных (с повторной попыткой и задержкой)
            deleteSentTestEmail(client, sentFolder, testEmailSubject)
            
            return AccessVerificationResult.HasAccess
        }
        
        // КРИТИЧНО: Если письмо НЕ пришло в Inbox - значит НЕТ доступа!
        // Отправка прошла, но письмо ушло другому получателю
        // Всё равно удаляем тестовое письмо из Отправленных
        deleteSentTestEmail(client, sentFolder, testEmailSubject)
        return AccessVerificationResult.NoAccess
        
    } catch (e: Exception) {
        // Пытаемся удалить тестовое письмо из Отправленных даже при ошибке
        try { deleteSentTestEmail(client, sentFolder, testEmailSubject) } catch (_: Exception) { }
        return AccessVerificationResult.Error(e.message ?: "Unknown error")
    }
}

/**
 * Вспомогательная функция для поиска тестового письма в Inbox
 * Итерируется по всем batch'ам синхронизации до нахождения письма
 */
private suspend fun findTestEmail(
    client: EasClient,
    inboxFolderId: String,
    testSubject: String,
    targetEmail: String
): com.dedovmosol.iwomail.eas.EasEmail? {
    return try {
        val initSync = client.sync(inboxFolderId, "0", 1)
        if (initSync !is EasResult.Success) return null
        
        var syncKey = initSync.data.syncKey
        var moreAvailable = true
        var iterations = 0
        
        while (moreAvailable && iterations < 30) {
            iterations++
            val result = client.sync(inboxFolderId, syncKey, 50)
            if (result !is EasResult.Success) break
            
            syncKey = result.data.syncKey
            moreAvailable = result.data.moreAvailable
            
            val found = result.data.emails.find {
                it.subject.contains(testSubject, ignoreCase = true) &&
                it.to.contains(targetEmail, ignoreCase = true)
            }
            if (found != null) return found
            
            if (result.data.emails.isEmpty() && result.data.deletedIds.isEmpty() 
                && result.data.changedEmails.isEmpty()) break
        }
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * Удаляет тестовое письмо из Отправленных с повторной попыткой.
 * Exchange может не сразу отдать недавно отправленное письмо через sync.
 */
private suspend fun deleteSentTestEmail(
    client: EasClient,
    sentFolder: com.dedovmosol.iwomail.eas.EasFolder?,
    subject: String
) {
    if (sentFolder == null) return
    try {
        if (!findAndDeleteTestEmail(client, sentFolder.serverId, subject)) {
            // Первая попытка не нашла — ждём и пробуем ещё раз
            delay(3000)
            findAndDeleteTestEmail(client, sentFolder.serverId, subject)
        }
    } catch (_: Exception) { }
}

/**
 * Поиск и удаление тестового письма из указанной папки
 * Итерируется по всем batch'ам до нахождения или исчерпания писем
 */
private suspend fun findAndDeleteTestEmail(
    client: EasClient,
    folderId: String,
    subjectContains: String
): Boolean {
    return try {
        val initSync = client.sync(folderId, "0", 1)
        if (initSync !is EasResult.Success) return false
        
        var syncKey = initSync.data.syncKey
        var moreAvailable = true
        var iterations = 0
        
        while (moreAvailable && iterations < 30) {
            iterations++
            val result = client.sync(folderId, syncKey, 50)
            if (result !is EasResult.Success) break
            
            syncKey = result.data.syncKey
            moreAvailable = result.data.moreAvailable
            
            val found = result.data.emails.find {
                it.subject.contains(subjectContains, ignoreCase = true)
            }
            if (found != null) {
                client.deleteEmailPermanently(folderId, found.serverId, syncKey)
                return true
            }
            if (result.data.emails.isEmpty() && result.data.deletedIds.isEmpty()
                && result.data.changedEmails.isEmpty()) break
        }
        false
    } catch (_: Exception) {
        false
    }
}
