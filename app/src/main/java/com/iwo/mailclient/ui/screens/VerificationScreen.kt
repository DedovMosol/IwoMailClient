package com.iwo.mailclient.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.SyncMode
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.eas.EasClient
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.Strings
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
    onSuccess: () -> Unit,
    onError: (String, String?) -> Unit // error, savedData
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    
    // Получаем строки локализации в Composable контексте
    val verifyingAccountText = Strings.verifyingAccount
    val verifyingEmailText = Strings.verifyingEmail
    val sendingTestEmailText = Strings.sendingTestEmail
    val testEmailSubjectText = Strings.testEmailSubject
    val testEmailBodyText = Strings.testEmailBody
    val emailMismatchTitle = Strings.emailMismatch
    val isRussianLang = com.iwo.mailclient.ui.isRussian()
    
    // Функция для создания savedData (сохраняем всё кроме domain, username, password)
    fun createSavedData(): String {
        val certPath = certificatePath ?: ""
        return "$email|$displayName|$serverUrl|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|${syncMode.name}|$certPath"
    }
    
    // Функция для создания savedData при несовпадении email (сохраняем ВСЁ включая domain, username, password)
    fun createSavedDataForEmailMismatch(): String {
        val certPath = certificatePath ?: ""
        return "$email|$displayName|$serverUrl|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|${syncMode.name}|$certPath|$domain|$username|$password"
    }
    
    var statusText by remember { mutableStateOf(verifyingAccountText) }
    var showMismatchDialog by remember { mutableStateOf(false) }
    var mismatchEnteredEmail by remember { mutableStateOf("") }
    var mismatchActualEmail by remember { mutableStateOf("") }
    
    // Анимация вращения
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

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
                        certificatePath = certificatePath
                    )
                    
                    when (addResult) {
                        is EasResult.Success -> onSuccess()
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
                    onError(result.message, createSavedData())
                }
            }
        }
    }
    
    // Диалог несовпадения email
    if (showMismatchDialog) {
        val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
        val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
        
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
                                Icons.Default.Warning,
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
                        
                        Text(
                            Strings.pleaseEnterCorrectEmail,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Justify
                        )
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            com.iwo.mailclient.ui.theme.GradientDialogButton(
                                onClick = {
                                    showMismatchDialog = false
                                    onError("CLEAR_EMAIL", createSavedDataForEmailMismatch())
                                },
                                text = "OK"
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


/**
 * Извлекает email из строки формата "Name <email@domain.com>" или просто "email@domain.com"
 */
private fun extractEmailFromString(str: String): String {
    // Ищем email в угловых скобках
    val bracketMatch = "<([^>]+@[^>]+)>".toRegex().find(str)
    if (bracketMatch != null) {
        return bracketMatch.groupValues[1].lowercase().trim()
    }
    
    // Ищем просто email
    val emailMatch = "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})".toRegex().find(str)
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
    verifyingAccountText: String,
    verifyingEmailText: String,
    sendingTestEmailText: String,
    testEmailSubjectText: String,
    testEmailBodyText: String,
    onStatusChange: (String) -> Unit
): VerificationResult {
    val client = EasClient(
        serverUrl = serverUrl,
        username = username,
        password = password,
        domain = domain,
        acceptAllCerts = acceptAllCerts,
        port = port,
        useHttps = useSSL,
        deviceIdSuffix = email,
        certificatePath = certificatePath
    )
    
    // Шаг 1: Получаем список папок
    onStatusChange(verifyingAccountText)
    
    val foldersResult = client.folderSync()
    if (foldersResult is EasResult.Error) {
        return VerificationResult.Error(foldersResult.message)
    }
    
    val folders = (foldersResult as EasResult.Success).data.folders
    
    // Находим папку "Отправленные" (type = 5)
    val sentFolder = folders.find { it.type == 5 }
    // Находим папку "Входящие" (type = 2)
    val inboxFolder = folders.find { it.type == 2 }
    
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
    
    // Проверяем "From" в отправленных и запоминаем serverId для удаления
    var testEmailServerId: String? = null
    var testEmailSyncKey: String? = null
    
    if (sentFolder != null) {
        // Получаем syncKey для папки Отправленные
        val syncResult = client.sync(sentFolder.serverId, "0", 1)
        if (syncResult is EasResult.Success) {
            testEmailSyncKey = syncResult.data.syncKey
            
            // Получаем письмо
            val sentResult2 = client.sync(sentFolder.serverId, testEmailSyncKey, 1)
            if (sentResult2 is EasResult.Success && sentResult2.data.emails.isNotEmpty()) {
                val sentEmail = sentResult2.data.emails.first()
                testEmailServerId = sentEmail.serverId
                testEmailSyncKey = sentResult2.data.syncKey
                val fromEmail = extractEmailFromString(sentEmail.from)
                
                if (fromEmail.isNotEmpty() && fromEmail.contains("@")) {
                    // Удаляем тестовое письмо из Отправленных
                    if (testEmailServerId != null && testEmailSyncKey != null) {
                        client.deleteEmailPermanently(sentFolder.serverId, testEmailServerId, testEmailSyncKey)
                    }
                    
                    // Также удаляем из Входящих (письмо пришло самому себе)
                    if (inboxFolder != null) {
                        try {
                            val inboxSync = client.sync(inboxFolder.serverId, "0", 1)
                            if (inboxSync is EasResult.Success) {
                                val inboxSync2 = client.sync(inboxFolder.serverId, inboxSync.data.syncKey, 5)
                                if (inboxSync2 is EasResult.Success) {
                                    // Ищем письмо с темой тестового письма
                                    val testInboxEmail = inboxSync2.data.emails.find { 
                                        it.subject == testEmailSubjectText 
                                    }
                                    if (testInboxEmail != null) {
                                        client.deleteEmailPermanently(
                                            inboxFolder.serverId, 
                                            testInboxEmail.serverId, 
                                            inboxSync2.data.syncKey
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Игнорируем ошибки удаления из входящих
                        }
                    }
                    
                    if (emailsMatch(email, fromEmail)) {
                        return VerificationResult.Success
                    } else {
                        return VerificationResult.EmailMismatch(email, fromEmail)
                    }
                }
            }
        }
    }
    
    // Если ничего не сработало — считаем email верным (fallback)
    return VerificationResult.Success
}
