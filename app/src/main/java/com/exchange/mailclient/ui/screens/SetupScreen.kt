package com.exchange.mailclient.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.exchange.mailclient.data.database.AccountType
import com.exchange.mailclient.data.database.SyncMode
import com.exchange.mailclient.data.repository.AccountRepository
import com.exchange.mailclient.data.repository.SettingsRepository
import com.exchange.mailclient.eas.EasClient
import com.exchange.mailclient.eas.EasResult
import com.exchange.mailclient.ui.AppLanguage
import com.exchange.mailclient.ui.LocalLanguage
import com.exchange.mailclient.ui.Strings
import com.exchange.mailclient.ui.isRussian
import com.exchange.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ACCOUNT_COLORS = listOf(
    0xFF1976D2.toInt(), // Blue
    0xFF388E3C.toInt(), // Green
    0xFFD32F2F.toInt(), // Red
    0xFF7B1FA2.toInt(), // Purple
    0xFFF57C00.toInt(), // Orange
    0xFF0097A7.toInt(), // Cyan
    0xFF5D4037.toInt(), // Brown
    0xFF455A64.toInt(), // Blue Grey
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    editAccountId: Long? = null,
    initialError: String? = null,
    savedData: String? = null,
    onSetupComplete: () -> Unit,
    onNavigateToVerification: ((
        email: String, displayName: String, serverUrl: String, username: String,
        password: String, domain: String, acceptAllCerts: Boolean, color: Int,
        incomingPort: Int, outgoingServer: String, outgoingPort: Int, useSSL: Boolean, syncMode: String
    ) -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val currentLanguage = LocalLanguage.current
    
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var acceptAllCerts by rememberSaveable { mutableStateOf(false) }
    var selectedColor by rememberSaveable { mutableStateOf(ACCOUNT_COLORS[0]) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var successMessage by rememberSaveable { mutableStateOf<String?>(null) }
    
    // Поля для IMAP/POP3
    var accountType by rememberSaveable { mutableStateOf(AccountType.EXCHANGE) }
    var incomingPort by rememberSaveable { mutableStateOf("443") }
    var outgoingServer by rememberSaveable { mutableStateOf("") }
    var outgoingPort by rememberSaveable { mutableStateOf("587") }
    var useSSL by rememberSaveable { mutableStateOf(true) }
    
    // Режим синхронизации (только для Exchange)
    var syncMode by rememberSaveable { mutableStateOf(SyncMode.PUSH) }
    
    // Получаем строки локализации в Composable контексте
    val emailMismatchText = Strings.emailMismatch
    val isRussianLang = isRussian()
    
    // Обработка ошибки верификации
    LaunchedEffect(initialError) {
        if (initialError == "CLEAR_EMAIL") {
            // Очищаем только email, остальные поля сохраняются
            email = ""
            errorMessage = null
        } else if (initialError != null && initialError.startsWith("EMAIL_MISMATCH:")) {
            val parts = initialError.split(":")
            if (parts.size >= 3) {
                val entered = parts[1]
                val actual = parts[2]
                errorMessage = if (isRussianLang) 
                    "Введённый email: $entered\nРеальный email: $actual\n\nПожалуйста, введите правильный email."
                else "Entered email: $entered\nActual email: $actual\n\nPlease enter the correct email."
            } else {
                errorMessage = emailMismatchText
            }
        } else if (initialError != null) {
            errorMessage = initialError
        }
    }
    
    // Восстановление данных из savedData (при возврате с ошибкой верификации)
    // Формат: email|displayName|serverUrl|acceptAllCerts|color|incomingPort|outgoingServer|outgoingPort|useSSL|syncMode
    LaunchedEffect(savedData) {
        if (savedData != null) {
            val parts = savedData.split("|")
            if (parts.size >= 10) {
                email = parts[0]
                displayName = parts[1]
                serverUrl = parts[2]
                acceptAllCerts = parts[3].toBoolean()
                selectedColor = parts[4].toIntOrNull() ?: ACCOUNT_COLORS[0]
                incomingPort = parts[5]
                outgoingServer = parts[6]
                outgoingPort = parts[7]
                useSSL = parts[8].toBoolean()
                syncMode = try { SyncMode.valueOf(parts[9]) } catch (_: Exception) { SyncMode.PUSH }
                // domain, username, password НЕ восстанавливаем — пользователь должен ввести заново
            }
        }
    }
    
    // Диалог выбора языка
    var showLanguageDialog by remember { mutableStateOf(false) }
    
    val isEditing = editAccountId != null
    
    // Диалог выбора языка
    if (showLanguageDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(Strings.selectLanguage) },
            text = {
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsRepo.setLanguage(lang.code)
                                    }
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == lang,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(lang.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Загружаем данные аккаунта при редактировании
    LaunchedEffect(editAccountId) {
        editAccountId?.let { id ->
            accountRepo.getAccount(id)?.let { account ->
                displayName = account.displayName
                email = account.email
                serverUrl = account.serverUrl
                username = account.username
                domain = account.domain
                acceptAllCerts = account.acceptAllCerts
                selectedColor = account.color
                accountType = AccountType.valueOf(account.accountType)
                incomingPort = account.incomingPort.toString()
                outgoingServer = account.outgoingServer
                outgoingPort = account.outgoingPort.toString()
                useSSL = account.useSSL
                syncMode = try { SyncMode.valueOf(account.syncMode) } catch (_: Exception) { SyncMode.PUSH }
            }
        }
    }
    
    Scaffold(
        topBar = {
            val colorTheme = LocalColorTheme.current
            // Современный TopAppBar с градиентом
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
            ) {
                TopAppBar(
                    title = { 
                        Text(
                            if (isEditing) {
                                if (isRussian()) "Редактировать аккаунт" else "Edit account"
                            } else {
                                Strings.addAccount
                            },
                            color = Color.White
                        ) 
                    },
                    navigationIcon = {
                        onBackClick?.let {
                            IconButton(onClick = it) {
                                Icon(Icons.Default.ArrowBack, Strings.back, tint = Color.White)
                            }
                        }
                    },
                    actions = {
                        // Кнопка выбора языка
                        IconButton(onClick = { showLanguageDialog = true }) {
                            Icon(Icons.Default.Language, Strings.language, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Приветственный блок для нового аккаунта
            if (!isEditing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF536DFE).copy(alpha = 0.1f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF7C4DFF),
                                            Color(0xFF536DFE)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Email,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isRussian()) "Добавьте почтовый аккаунт" else "Add email account",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isRussian()) "Exchange, IMAP или POP3" else "Exchange, IMAP or POP3",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Основной контент
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // Цвет аккаунта в карточке
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (isRussian()) "🎨 Цвет аккаунта" else "🎨 Account color", 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ACCOUNT_COLORS) { color ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .then(
                                        if (color == selectedColor) {
                                            Modifier.border(3.dp, Color.White, CircleShape)
                                        } else Modifier
                                    )
                                    .clickable { selectedColor = color },
                                contentAlignment = Alignment.Center
                            ) {
                                if (color == selectedColor) {
                                    Icon(
                                        Icons.Default.Check,
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
            
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(Strings.displayName) },
                placeholder = { Text(if (isRussian()) "Рабочая почта" else "Work email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(Strings.email) },
                placeholder = { Text("user@company.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            
            HorizontalDivider()
            Text(Strings.accountType, style = MaterialTheme.typography.titleMedium)
            
            // Выбор типа аккаунта с бейджем beta для IMAP/POP3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccountType.entries.forEach { type ->
                    val isBeta = type == AccountType.IMAP || type == AccountType.POP3
                    
                    if (accountType == type) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(type.displayName, maxLines = 1, softWrap = false)
                            if (isBeta) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "β",
                                    color = Color(0xFFFFD54F), // Жёлтый/золотой
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { 
                                accountType = type
                                // Устанавливаем порты по умолчанию
                                when (type) {
                                    AccountType.EXCHANGE -> {
                                        incomingPort = "443"
                                    }
                                    AccountType.IMAP -> {
                                        incomingPort = if (useSSL) "993" else "143"
                                        outgoingPort = "587"
                                    }
                                    AccountType.POP3 -> {
                                        incomingPort = if (useSSL) "995" else "110"
                                        outgoingPort = "587"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(type.displayName, maxLines = 1, softWrap = false)
                            if (isBeta) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "β",
                                    color = Color(0xFFFF9800), // Оранжевый
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            
            // Выбор режима синхронизации (только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                HorizontalDivider()
                Text(Strings.syncMode, style = MaterialTheme.typography.titleMedium)
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Push
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { syncMode = SyncMode.PUSH },
                        colors = CardDefaults.cardColors(
                            containerColor = if (syncMode == SyncMode.PUSH) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = syncMode == SyncMode.PUSH,
                                onClick = { syncMode = SyncMode.PUSH }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    Strings.syncModePush,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    Strings.syncModePushDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.Bolt,
                                null,
                                tint = if (syncMode == SyncMode.PUSH) 
                                    MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    
                    // Scheduled
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { syncMode = SyncMode.SCHEDULED },
                        colors = CardDefaults.cardColors(
                            containerColor = if (syncMode == SyncMode.SCHEDULED) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = syncMode == SyncMode.SCHEDULED,
                                onClick = { syncMode = SyncMode.SCHEDULED }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    Strings.syncModeScheduled,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    Strings.syncModeScheduledDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.BatteryChargingFull,
                                null,
                                tint = if (syncMode == SyncMode.SCHEDULED) 
                                    MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider()
            Text(
                if (isRussian()) "Настройки сервера" else "Server settings", 
                style = MaterialTheme.typography.titleMedium
            )
            
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { 
                    Text(
                        when (accountType) {
                            AccountType.EXCHANGE -> Strings.server
                            AccountType.IMAP -> if (isRussian()) "IMAP сервер" else "IMAP server"
                            AccountType.POP3 -> if (isRussian()) "POP3 сервер" else "POP3 server"
                        }
                    )
                },
                placeholder = { 
                    Text(
                        when (accountType) {
                            AccountType.EXCHANGE -> "mail.company.com"
                            AccountType.IMAP -> "imap.company.com"
                            AccountType.POP3 -> "pop.company.com"
                        }
                    )
                },
                supportingText = {
                    if (accountType == AccountType.EXCHANGE) {
                        Text(
                            if (isRussian()) "Формат: IP:порт или домен (порт 443 по умолчанию)"
                            else "Format: IP:port or domain (port 443 by default)"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            
            // Порт для Exchange
            if (accountType == AccountType.EXCHANGE) {
                OutlinedTextField(
                    value = incomingPort,
                    onValueChange = { incomingPort = it.filter { c -> c.isDigit() } },
                    label = { Text(Strings.port) },
                    placeholder = { Text("443 (HTTPS)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                // Выбор HTTP/HTTPS
                Text(
                    if (isRussian()) "Протокол" else "Protocol", 
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (useSSL) {
                        Button(onClick = { }, modifier = Modifier.weight(1f)) {
                            Text("HTTPS")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { useSSL = true; incomingPort = "443" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("HTTPS")
                        }
                    }
                    if (!useSSL) {
                        Button(onClick = { }, modifier = Modifier.weight(1f)) {
                            Text("HTTP")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { useSSL = false; incomingPort = "80" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("HTTP")
                        }
                    }
                }
            }
            
            // Порт и SSL для IMAP/POP3
            if (accountType != AccountType.EXCHANGE) {
                // Тип безопасности
                Text(
                    if (isRussian()) "Тип безопасности" else "Security type", 
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (useSSL) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SSL/TLS")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { 
                                useSSL = true
                                when (accountType) {
                                    AccountType.IMAP -> incomingPort = "993"
                                    AccountType.POP3 -> incomingPort = "995"
                                    else -> {}
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SSL/TLS")
                        }
                    }
                    
                    if (!useSSL) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isRussian()) "Нет" else "None")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { 
                                useSSL = false
                                when (accountType) {
                                    AccountType.IMAP -> incomingPort = "143"
                                    AccountType.POP3 -> incomingPort = "110"
                                    else -> {}
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isRussian()) "Нет" else "None")
                        }
                    }
                }
                
                OutlinedTextField(
                    value = incomingPort,
                    onValueChange = { incomingPort = it.filter { c -> c.isDigit() } },
                    label = { Text(Strings.port) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                HorizontalDivider()
                Text(
                    if (isRussian()) "Исходящая почта (SMTP)" else "Outgoing mail (SMTP)", 
                    style = MaterialTheme.typography.labelLarge
                )
                
                // SMTP сервер для отправки
                OutlinedTextField(
                    value = outgoingServer,
                    onValueChange = { outgoingServer = it },
                    label = { Text(if (isRussian()) "SMTP сервер" else "SMTP server") },
                    placeholder = { Text("smtp.company.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = outgoingPort,
                    onValueChange = { outgoingPort = it.filter { c -> c.isDigit() } },
                    label = { Text(if (isRussian()) "SMTP порт" else "SMTP port") },
                    placeholder = { Text("587 (TLS)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            
            // Домен для Exchange
            if (accountType == AccountType.EXCHANGE) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("${Strings.domain} (${Strings.optional})") },
                    placeholder = { Text("DOMAIN") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(if (isRussian()) "Имя пользователя" else "Username") },
                placeholder = { Text("username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(Strings.password) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) 
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) 
                                Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = acceptAllCerts,
                    onCheckedChange = { acceptAllCerts = it }
                )
                Text(
                    text = if (isRussian()) "Принимать самоподписанные сертификаты" 
                           else "Accept self-signed certificates",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { acceptAllCerts = !acceptAllCerts }
                )
            }
            
            // Сообщения
            errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            successMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Кнопки
            val canSave = displayName.isNotBlank() && email.isNotBlank() && 
                          serverUrl.isNotBlank() && username.isNotBlank() && 
                          (password.isNotBlank() || isEditing)
            
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        successMessage = null
                        
                        try {
                            val result: EasResult<Long> = if (isEditing && editAccountId != null) {
                                // Обновление существующего аккаунта — без верификации
                                val account = accountRepo.getAccount(editAccountId)
                                if (account != null) {
                                    accountRepo.updateAccount(
                                        account.copy(
                                            displayName = displayName,
                                            email = email,
                                            serverUrl = serverUrl,
                                            username = username,
                                            domain = domain,
                                            acceptAllCerts = acceptAllCerts,
                                            color = selectedColor,
                                            accountType = accountType.name,
                                            incomingPort = incomingPort.toIntOrNull() ?: 993,
                                            outgoingServer = outgoingServer,
                                            outgoingPort = outgoingPort.toIntOrNull() ?: 587,
                                            useSSL = useSSL,
                                            syncMode = syncMode.name
                                        ),
                                        password = if (password.isNotBlank()) password else null
                                    )
                                    EasResult.Success(editAccountId)
                                } else {
                                    EasResult.Error("Аккаунт не найден")
                                }
                            } else if (accountType == AccountType.EXCHANGE && onNavigateToVerification != null) {
                                // Для Exchange — переходим на экран верификации
                                isLoading = false
                                onNavigateToVerification(
                                    email, displayName, serverUrl, username, password, domain,
                                    acceptAllCerts, selectedColor,
                                    incomingPort.toIntOrNull() ?: 443,
                                    outgoingServer,
                                    outgoingPort.toIntOrNull() ?: 587,
                                    useSSL, syncMode.name
                                )
                                return@launch // Выходим, навигация произойдёт
                            } else {
                                // Для IMAP/POP3 — без верификации
                                val defaultPort = when (accountType) {
                                    AccountType.EXCHANGE -> 443
                                    AccountType.IMAP -> if (useSSL) 993 else 143
                                    AccountType.POP3 -> if (useSSL) 995 else 110
                                }
                                accountRepo.addAccount(
                                    email = email,
                                    displayName = displayName,
                                    serverUrl = serverUrl,
                                    username = username,
                                    password = password,
                                    domain = domain,
                                    acceptAllCerts = acceptAllCerts,
                                    color = selectedColor,
                                    accountType = accountType,
                                    incomingPort = incomingPort.toIntOrNull() ?: defaultPort,
                                    outgoingServer = outgoingServer,
                                    outgoingPort = outgoingPort.toIntOrNull() ?: 587,
                                    useSSL = useSSL,
                                    syncMode = syncMode
                                )
                            }
                            
                            when (result) {
                                is EasResult.Success -> onSetupComplete()
                                is EasResult.Error -> errorMessage = result.message
                            }
                        } catch (e: Exception) {
                            errorMessage = "Ошибка: ${e.message ?: "Неизвестная ошибка"}"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF43A047) // Зелёный
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (isEditing) Strings.save else Strings.addAccountBtn,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            } // Закрываем внутренний Column
        } // Закрываем внешний Column
    }
}

