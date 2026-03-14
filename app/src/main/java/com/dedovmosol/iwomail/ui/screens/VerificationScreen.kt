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

private const val VERIFICATION_INVALID_EMAIL = "VERIFICATION_INVALID_EMAIL"
private const val VERIFICATION_INCONCLUSIVE = "VERIFICATION_INCONCLUSIVE"

private data class VerificationEmailMatch(
    val email: com.dedovmosol.iwomail.eas.EasEmail,
    val syncKey: String
)

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
    alternateServerUrl: String? = null,
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
    val tryingBackupServerText = Strings.tryingBackupServer
    val isRussianLang = com.dedovmosol.iwomail.ui.isRussian()

    fun localizeVerificationError(message: String): String {
        return when {
            message == "CLIENT_CERT_PASSWORD_REQUIRED" -> {
                if (isRussianLang) {
                    "Требуется пароль клиентского сертификата"
                } else {
                    "Client certificate password required"
                }
            }
            message == "CLIENT_CERT_LOAD_FAILED" -> {
                if (isRussianLang) {
                    "Не удалось загрузить клиентский сертификат. Проверьте пароль."
                } else {
                    "Failed to load client certificate. Check the password."
                }
            }
            message == VERIFICATION_INVALID_EMAIL -> {
                if (isRussianLang) {
                    "Введите корректный email адрес"
                } else {
                    "Enter a valid email address"
                }
            }
            message == VERIFICATION_INCONCLUSIVE -> {
                if (isRussianLang) {
                    "Не удалось подтвердить введённый email. Проверьте адрес или повторите попытку позже."
                } else {
                    "Could not verify the entered email. Check the address or try again later."
                }
            }
            message.startsWith("Неверный адрес получателя:") -> {
                val invalidAddress = message.substringAfter(":").trim()
                if (isRussianLang) {
                    "Неверный адрес получателя: $invalidAddress"
                } else {
                    "Invalid recipient address: $invalidAddress"
                }
            }
            message.startsWith("Ошибка отправки письма:") -> {
                val details = message.substringAfter(":").trim()
                if (isRussianLang) {
                    "Ошибка отправки письма: $details"
                } else {
                    "Email send error: $details"
                }
            }
            message.startsWith("Ошибка отправки письма (") -> {
                if (isRussianLang) {
                    message
                } else {
                    message.replace("Ошибка отправки письма", "Email send error")
                }
            }
            else -> NotificationStrings.localizeError(message, isRussianLang)
        }
    }
    
    // Функция для создания savedData (пароль не сохраняем)
    fun createSavedData(): String {
        val certPath = certificatePath ?: ""
        val clientCertPath = clientCertificatePath ?: ""
        val altUrl = alternateServerUrl ?: ""
        return "$email|$displayName|$serverUrl|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|${syncMode.name}|$certPath|$clientCertPath|$domain|$username|$altUrl"
    }
    
    fun createSavedDataForEmailMismatch(): String {
        val certPath = certificatePath ?: ""
        val clientCertPath = clientCertificatePath ?: ""
        val altUrl = alternateServerUrl ?: ""
        return "$email|$displayName|$serverUrl|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|${syncMode.name}|$certPath|$domain|$username|$clientCertPath|$altUrl"
    }
    
    var statusText by remember { mutableStateOf(verifyingAccountText) }
    var showMismatchDialog by remember { mutableStateOf(false) }
    var mismatchEnteredEmail by remember { mutableStateOf("") }
    var mismatchActualEmail by remember { mutableStateOf("") }
    var isCheckingAccess by remember { mutableStateOf(false) }
    var accessCheckResult by remember { mutableStateOf<AccessVerificationResult?>(null) }

    fun startInitialSync(accountId: Long) {
        val appContext = context.applicationContext
        val mailRepo = RepositoryProvider.getMailRepository(appContext)
        val settingsRepo = RepositoryProvider.getSettingsRepository(appContext)
        com.dedovmosol.iwomail.sync.InitialSyncController.startSyncIfNeeded(
            context = appContext,
            accountId = accountId,
            mailRepo = mailRepo,
            settingsRepo = settingsRepo
        )
    }

    suspend fun tryEnableCertificatePinning(accountId: Long, sourceTag: String) {
        if (certificatePath == null) return
        try {
            val pinResult = accountRepo.pinCertificate(accountId)
            if (pinResult is EasResult.Success) {
                android.util.Log.d("VerificationScreen", "Certificate Pinning enabled automatically ($sourceTag)")
            } else if (pinResult is EasResult.Error) {
                android.util.Log.w("VerificationScreen", "Failed to auto-enable Certificate Pinning ($sourceTag): ${pinResult.message}")
            }
        } catch (e: Exception) {
            android.util.Log.w("VerificationScreen", "Exception during auto-enable Certificate Pinning ($sourceTag)", e)
        }
    }

    suspend fun saveVerifiedExchangeAccount(
        verifiedEmail: String,
        errorSavedData: String,
        sourceTag: String
    ) {
        val addResult = accountRepo.addAccount(
            email = verifiedEmail,
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
            clientCertificatePassword = clientCertificatePassword,
            alternateServerUrl = alternateServerUrl
        )

        when (addResult) {
            is EasResult.Success -> {
                val accountId = addResult.data
                tryEnableCertificatePinning(accountId, sourceTag)
                startInitialSync(accountId)
                onSuccess()
            }
            is EasResult.Error -> onError(addResult.message, errorSavedData)
        }
    }
    
    // Анимация вращения - создаём всегда, применяем только если animationsEnabled
    val rotation = rememberRotation(animationsEnabled, durationMs = 1000)

    // Запускаем верификацию
    LaunchedEffect(Unit) {
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
            alternateServerUrl = alternateServerUrl,
            verifyingAccountText = verifyingAccountText,
            verifyingEmailText = verifyingEmailText,
            sendingTestEmailText = sendingTestEmailText,
            testEmailSubjectText = testEmailSubjectText,
            testEmailBodyText = testEmailBodyText,
            tryingBackupServerText = tryingBackupServerText,
            onStatusChange = { statusText = it }
        )
        
        when (result) {
            is VerificationResult.Success -> {
                saveVerifiedExchangeAccount(
                    verifiedEmail = email,
                    errorSavedData = createSavedData(),
                    sourceTag = "initial verification"
                )
            }
            is VerificationResult.EmailMismatch -> {
                // Показываем диалог
                mismatchEnteredEmail = result.enteredEmail
                mismatchActualEmail = result.actualEmail
                showMismatchDialog = true
            }
            is VerificationResult.Error -> {
                onError(localizeVerificationError(result.message), createSavedData())
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
            targetValue = if (animationsEnabled) { if (visible) 1f else 0.8f } else 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
            label = "scale"
        )
        val alpha by animateFloatAsState(
            targetValue = if (animationsEnabled) { if (visible) 1f else 0f } else 1f,
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
                                    val localizedMsg = localizeVerificationError(result.message)
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
                            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        showMismatchDialog = false
                                        saveVerifiedExchangeAccount(
                                            verifiedEmail = mismatchActualEmail,
                                            errorSavedData = createSavedData(),
                                            sourceTag = "email mismatch actual mailbox"
                                        )
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
                                            val accessVerificationSubject = buildVerificationSubject(testEmailSubjectText)
                                            
                                            // Проверяем доступ
                                            val result = verifyAccessToEnteredEmail(
                                                client = tempClient,
                                                enteredEmail = mismatchEnteredEmail,
                                                inboxFolder = inboxFolder,
                                                sentFolder = sentFolder,
                                                testEmailSubject = accessVerificationSubject
                                            )
                                            
                                            accessCheckResult = result
                                            
                                            // Если доступ подтверждён (письмо найдено в Inbox) - сохраняем с введённым email
                                            if (result is AccessVerificationResult.HasAccess) {
                                                delay(1000) // Показываем результат
                                                showMismatchDialog = false
                                                saveVerifiedExchangeAccount(
                                                    verifiedEmail = mismatchEnteredEmail,
                                                    errorSavedData = createSavedData(),
                                                    sourceTag = "email mismatch delegated mailbox"
                                                )
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
                            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                                onClick = {
                                    showMismatchDialog = false
                                    accessCheckResult = null
                                    onError("CLEAR_EMAIL", createSavedDataForEmailMismatch())
                                },
                                text = if (isRussianLang) "Отменить" else "Cancel",
                                enabled = !isCheckingAccess,
                                modifier = Modifier.fillMaxWidth()
                            )
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

private fun extractVerifiedEmail(str: String): String? {
    val bracketMatch = BRACKET_EMAIL_REGEX.find(str)
    if (bracketMatch != null) {
        return bracketMatch.groupValues[1].lowercase().trim()
    }

    val emailMatch = SIMPLE_EMAIL_REGEX.find(str)
    if (emailMatch != null) {
        return emailMatch.groupValues[1].lowercase().trim()
    }

    return null
}

private fun extractAllEmailsFromString(str: String): List<String> {
    val bracketMatches = BRACKET_EMAIL_REGEX.findAll(str)
        .map { it.groupValues[1].lowercase().trim() }
        .toList()
    if (bracketMatches.isNotEmpty()) {
        return bracketMatches.distinct()
    }

    return SIMPLE_EMAIL_REGEX.findAll(str)
        .map { it.groupValues[1].lowercase().trim() }
        .distinct()
        .toList()
}

private fun addressContainsEmail(addresses: String, targetEmail: String): Boolean {
    val normalizedTargetEmail = extractVerifiedEmail(targetEmail) ?: return false
    return extractAllEmailsFromString(addresses).any { it.equals(normalizedTargetEmail, ignoreCase = true) }
}

private fun buildVerificationSubject(baseSubject: String): String {
    val token = buildString {
        append(java.lang.Long.toString(System.currentTimeMillis(), 36))
        append("-")
        append(java.lang.Long.toString(System.nanoTime(), 36))
    }
    return "$baseSubject [$token]"
}

private fun subjectsMatch(actualSubject: String, expectedSubject: String): Boolean {
    return actualSubject.trim().equals(expectedSubject.trim(), ignoreCase = true)
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
    alternateServerUrl: String? = null,
    verifyingAccountText: String,
    verifyingEmailText: String,
    sendingTestEmailText: String,
    testEmailSubjectText: String,
    testEmailBodyText: String,
    tryingBackupServerText: String = "",
    onStatusChange: (String) -> Unit
): VerificationResult {
    val normalizedEmail = extractVerifiedEmail(email)
        ?: return VerificationResult.Error(VERIFICATION_INVALID_EMAIL)
    var client = try {
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
    
    var foldersResult = client.folderSync()

    if (foldersResult is EasResult.Error && !alternateServerUrl.isNullOrBlank()
        && com.dedovmosol.iwomail.data.model.isConnectionLevelError(foldersResult.message)
    ) {
        val primaryError = foldersResult.message
        onStatusChange(tryingBackupServerText)
        client = try {
            EasClient(
                serverUrl = alternateServerUrl,
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
        } catch (_: IllegalArgumentException) {
            return VerificationResult.Error(primaryError)
        }
        foldersResult = client.folderSync()
        if (foldersResult is EasResult.Error) {
            return VerificationResult.Error(primaryError)
        }
    }

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
            val fromEmail = extractVerifiedEmail(sentEmail.from)
            
            if (fromEmail != null) {
                if (emailsMatch(normalizedEmail, fromEmail)) {
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
            val recipientEmails = extractAllEmailsFromString(inboxEmail.to)

            // Inbox.To — это только список адресатов конкретного письма.
            // Для Exchange 2007 SP1 / EAS 12.1 это не является надёжным источником
            // "основного адреса" ящика: письмо могло прийти по alias, группе или BCC.
            if (recipientEmails.any { emailsMatch(normalizedEmail, it) }) {
                return VerificationResult.Success
            }
        }
    }
    
    // Шаг 4: Отправляем тестовое письмо и подтверждаем адрес по уникальному subject.
    onStatusChange(sendingTestEmailText)
    val verificationSubject = buildVerificationSubject(testEmailSubjectText)
    
    val sendResult = client.sendMail(
        to = normalizedEmail,
        subject = verificationSubject,
        body = testEmailBodyText
    )
    
    if (sendResult is EasResult.Error) {
        return VerificationResult.Error(sendResult.message)
    }
    
    // Ждём пока письмо дойдёт
    delay(3000)
    
    val sentMatch = sentFolder?.let {
        findTestEmailWithRetry(
            client = client,
            folderId = it.serverId,
            testSubject = verificationSubject,
            retryDelaysMs = listOf(0L, 3000L)
        )
    }
    val fromEmail = sentMatch?.let { extractVerifiedEmail(it.email.from) }

    val inboxMatch = inboxFolder?.let {
        findTestEmailWithRetry(
            client = client,
            folderId = it.serverId,
            testSubject = verificationSubject,
            targetEmail = normalizedEmail,
            retryDelaysMs = listOf(0L, 4000L)
        )
    }

    sentFolder?.let { folder ->
        if (sentMatch != null) {
            deleteMatchedEmail(client, folder.serverId, sentMatch)
        } else {
            findAndDeleteTestEmail(client, folder.serverId, verificationSubject)
        }
    }

    inboxFolder?.let { folder ->
        if (inboxMatch != null) {
            deleteMatchedEmail(client, folder.serverId, inboxMatch)
        } else {
            findAndDeleteTestEmail(client, folder.serverId, verificationSubject, normalizedEmail)
        }
    }

    if (fromEmail != null) {
        return if (emailsMatch(normalizedEmail, fromEmail)) {
            VerificationResult.Success
        } else {
            VerificationResult.EmailMismatch(email, fromEmail)
        }
    }

    if (inboxMatch != null) {
        return VerificationResult.Success
    }

    return VerificationResult.Error(VERIFICATION_INCONCLUSIVE)
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

    val normalizedEnteredEmail = extractVerifiedEmail(enteredEmail)
        ?: return AccessVerificationResult.Error(VERIFICATION_INVALID_EMAIL)
    
    try {
        // Отправляем тестовое письмо НА введённый email
        val sendResult = client.sendMail(
            to = normalizedEnteredEmail,
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
        var testEmail = findTestEmail(client, inboxFolder.serverId, testEmailSubject, normalizedEnteredEmail)
        
        // Если не нашли - ждём ещё и пробуем снова
        if (testEmail == null) {
            delay(4000)
            testEmail = findTestEmail(client, inboxFolder.serverId, testEmailSubject, normalizedEnteredEmail)
        }
        
        if (testEmail != null) {
            // Письмо найдено в Inbox - ДОКАЗАТЕЛЬСТВО доступа
            // Удаляем тестовое письмо из Входящих
            deleteMatchedEmail(client, inboxFolder.serverId, testEmail)
            
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
    folderId: String,
    testSubject: String,
    targetEmail: String? = null
): VerificationEmailMatch? {
    return try {
        val initSync = client.sync(folderId, "0", 1)
        if (initSync !is EasResult.Success) return null
        
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
                subjectsMatch(it.subject, testSubject) &&
                (targetEmail == null || addressContainsEmail(it.to, targetEmail))
            }
            if (found != null) return VerificationEmailMatch(found, syncKey)
            
            if (result.data.emails.isEmpty() && result.data.deletedIds.isEmpty() 
                && result.data.changedEmails.isEmpty()) break
        }
        null
    } catch (_: Exception) {
        null
    }
}

private suspend fun findTestEmailWithRetry(
    client: EasClient,
    folderId: String,
    testSubject: String,
    targetEmail: String? = null,
    retryDelaysMs: List<Long>
): VerificationEmailMatch? {
    for (retryDelayMs in retryDelaysMs) {
        if (retryDelayMs > 0) {
            delay(retryDelayMs)
        }
        val match = findTestEmail(client, folderId, testSubject, targetEmail)
        if (match != null) {
            return match
        }
    }
    return null
}

private suspend fun deleteMatchedEmail(
    client: EasClient,
    folderId: String,
    match: VerificationEmailMatch
) {
    try {
        client.deleteEmailPermanently(folderId, match.email.serverId, match.syncKey)
    } catch (_: Exception) { }
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
    subjectContains: String,
    targetEmail: String? = null
): Boolean {
    val match = findTestEmail(client, folderId, subjectContains, targetEmail) ?: return false
    return try {
        client.deleteEmailPermanently(folderId, match.email.serverId, match.syncKey)
        true
    } catch (_: Exception) {
        false
    }
}
