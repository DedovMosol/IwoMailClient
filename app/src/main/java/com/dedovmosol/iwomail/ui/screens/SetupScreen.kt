package com.dedovmosol.iwomail.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.utils.rememberPulseScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SetupScreen(
    editAccountId: Long? = null,
    initialError: String? = null,
    savedData: String? = null,
    onSetupComplete: () -> Unit,
    onNavigateToVerification: ((
        email: String, displayName: String, serverUrl: String, username: String,
        password: String, domain: String, acceptAllCerts: Boolean, color: Int,
        incomingPort: Int, outgoingServer: String, outgoingPort: Int, useSSL: Boolean, syncMode: String,
        certificatePath: String?, clientCertificatePath: String?, clientCertificatePassword: String?
    ) -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val currentLanguage = LocalLanguage.current
    val isRussianLang = currentLanguage == AppLanguage.RUSSIAN
    
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
    
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Сохранение фокуса при повороте экрана
    var focusedFieldIndex by rememberSaveable { mutableIntStateOf(-1) }
    val displayNameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val serverUrlFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val domainFocus = remember { FocusRequester() }
    
    // Восстановление фокуса после поворота
    LaunchedEffect(focusedFieldIndex) {
        if (focusedFieldIndex >= 0) {
            kotlinx.coroutines.delay(100)
            when (focusedFieldIndex) {
                0 -> displayNameFocus.requestFocus()
                1 -> emailFocus.requestFocus()
                2 -> serverUrlFocus.requestFocus()
                3 -> usernameFocus.requestFocus()
                4 -> passwordFocus.requestFocus()
                5 -> domainFocus.requestFocus()
            }
        }
    }
    var incomingPort by rememberSaveable { mutableStateOf("443") }
    var outgoingServer by rememberSaveable { mutableStateOf("") }
    var outgoingPort by rememberSaveable { mutableStateOf("587") }
    var useSSL by rememberSaveable { mutableStateOf(true) }
    
    // Режим синхронизации (только для Exchange)
    var syncMode by rememberSaveable { mutableStateOf(SyncMode.SCHEDULED) }
    
    // Путь к файлу сертификата сервера
    var certificatePath by rememberSaveable { mutableStateOf<String?>(null) }
    var certificateFileName by rememberSaveable { mutableStateOf<String?>(null) }
    
    // Клиентский сертификат (.p12/.pfx)
    var clientCertificatePath by rememberSaveable { mutableStateOf<String?>(null) }
    var clientCertificateFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var clientCertificatePassword by rememberSaveable { mutableStateOf("") }
    var clientCertPasswordVisible by rememberSaveable { mutableStateOf(false) }
    
    // Допустимые расширения сертификатов
    val validCertExtensions = listOf("cer", "crt", "pem", "der", "p12", "pfx", "p7b", "p7c")
    
    // Файловый пикер для выбора серверного сертификата
    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    // Получаем имя файла
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
                    
                    // Проверяем расширение
                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in validCertExtensions) {
                        errorMessage = if (isRussianLang) 
                            "Неверный формат файла. Допустимые: ${validCertExtensions.joinToString(", ") { ".$it" }}" 
                        else 
                            "Invalid file format. Allowed: ${validCertExtensions.joinToString(", ") { ".$it" }}"
                        return@launch
                    }
                    
                    // Копируем файл в приватное хранилище приложения
                    val fileName = "cert_${System.currentTimeMillis()}.$extension"
                    val certFile = File(context.filesDir, fileName)
                    
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            certFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    // Удаляем старый файл ТОЛЬКО после успешного копирования нового
                    certificatePath?.let { oldPath ->
                        try { File(oldPath).delete() } catch (_: Exception) {}
                    }
                    
                    certificatePath = certFile.absolutePath
                    certificateFileName = originalFileName ?: fileName
                } catch (e: Exception) {
                    errorMessage = NotificationStrings.getCertificateLoadingError(isRussianLang)
                }
            }
        }
    }
    
    // Файловый пикер для выбора клиентского сертификата (.p12/.pfx)
    val clientCertificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    // Получаем имя файла
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
                    
                    // Проверяем расширение (только .p12/.pfx для клиентских сертификатов)
                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in listOf("p12", "pfx")) {
                        errorMessage = if (isRussianLang) 
                            "Неверный формат файла. Допустимые: .p12, .pfx" 
                        else 
                            "Invalid file format. Allowed: .p12, .pfx"
                        return@launch
                    }
                    
                    // Копируем файл в приватное хранилище приложения
                    val fileName = "client_cert_${System.currentTimeMillis()}.$extension"
                    val certFile = File(context.filesDir, fileName)
                    
                    // Сохраняем старый путь для удаления ПОСЛЕ успешного копирования
                    val oldCertPath = clientCertificatePath
                    
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            certFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    // Удаляем старый файл ТОЛЬКО после успешного копирования нового
                    oldCertPath?.let { oldPath ->
                        try { 
                            File(oldPath).delete()
                            android.util.Log.d("SetupScreen", "Old client certificate deleted: $oldPath")
                        } catch (e: Exception) {
                            android.util.Log.e("SetupScreen", "Failed to delete old client certificate: ${e.message}")
                        }
                    }
                    
                    clientCertificatePath = certFile.absolutePath
                    clientCertificateFileName = originalFileName ?: fileName
                } catch (e: Exception) {
                    android.util.Log.e("SetupScreen", "Client certificate loading error: ${e.message}", e)
                    errorMessage = NotificationStrings.getClientCertificateLoadingError(isRussianLang)
                }
            }
        }
    }
    
    // Получаем строки локализации в Composable контексте
    val emailMismatchText = Strings.emailMismatch
    
    // Обработка ошибки верификации
    LaunchedEffect(initialError, savedData) {
        if (initialError == "CLEAR_EMAIL" && savedData != null) {
            // Восстанавливаем все данные из savedData, но очищаем email
            val parts = savedData.split("|")
            if (parts.size >= 11) {
                email = "" // Очищаем email — пользователь должен ввести правильный
                displayName = parts[1]
                serverUrl = parts[2]
                acceptAllCerts = parts[3].toBoolean()
                selectedColor = parts[4].toIntOrNull() ?: ACCOUNT_COLORS[0]
                incomingPort = parts[5]
                outgoingServer = parts[6]
                outgoingPort = parts[7]
                useSSL = parts[8].toBoolean()
                syncMode = try { SyncMode.valueOf(parts[9]) } catch (_: Exception) { SyncMode.PUSH }
                if (parts[10].isNotBlank()) {
                    certificatePath = parts[10]
                    certificateFileName = File(parts[10]).name
                }
                // Восстанавливаем domain, username, password если они есть
                if (parts.size >= 14) {
                    domain = parts[11]
                    username = parts[12]
                    password = parts[13]
                }
                // Восстанавливаем клиентский сертификат если есть
                if (parts.size >= 15 && parts[14].isNotBlank()) {
                    clientCertificatePath = parts[14]
                    clientCertificateFileName = File(parts[14]).name
                }
            }
            errorMessage = emailMismatchText
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
    // Формат: email|displayName|serverUrl|acceptAllCerts|color|incomingPort|outgoingServer|outgoingPort|useSSL|syncMode|certificatePath|clientCertificatePath
    // При CLEAR_EMAIL данные уже восстановлены выше, поэтому пропускаем
    LaunchedEffect(savedData) {
        if (savedData != null && initialError != "CLEAR_EMAIL") {
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
                // Восстанавливаем путь к серверному сертификату если есть
                if (parts.size >= 11 && parts[10].isNotBlank()) {
                    certificatePath = parts[10]
                    certificateFileName = File(parts[10]).name
                }
                // Восстанавливаем путь к клиентскому сертификату если есть
                if (parts.size >= 12 && parts[11].isNotBlank()) {
                    clientCertificatePath = parts[11]
                    clientCertificateFileName = File(parts[11]).name
                }
                // domain, username, password НЕ восстанавливаем — пользователь должен ввести заново
            }
        }
    }
    
    // Диалог выбора языка
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showHelpScreen by remember { mutableStateOf(false) }
    
    val isEditing = editAccountId != null
    
    // Диалог выбора языка
    if (showLanguageDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
                // Загружаем путь к серверному сертификату
                certificatePath = account.certificatePath
                if (certificatePath != null) {
                    certificateFileName = File(certificatePath!!).name
                }
                // Загружаем путь к клиентскому сертификату
                clientCertificatePath = account.clientCertificatePath
                if (clientCertificatePath != null) {
                    clientCertificateFileName = File(clientCertificatePath!!).name
                }
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
                                Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                            }
                        }
                    },
                    actions = {
                        // Кнопка справки
                        IconButton(onClick = { keyboardController?.hide(); showHelpScreen = true }) {
                            Icon(AppIcons.Info, contentDescription = if (isRussian()) "Справка" else "Help", tint = Color.White)
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
                val colorTheme = LocalColorTheme.current
                val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
                
                // Анимация пульсации для иконки
                val iconScale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.1f, durationMs = 1000)
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    colorTheme.gradientStart.copy(alpha = 0.1f),
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
                                .scale(iconScale)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            colorTheme.gradientStart,
                                            colorTheme.gradientEnd
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                AppIcons.Email,
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
            
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(Strings.displayName) },
                placeholder = { Text(if (isRussian()) "Рабочая почта" else "Work email") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(displayNameFocus)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 0 },
                singleLine = true
            )
            
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(Strings.email) },
                placeholder = { Text("user@company.com") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(emailFocus)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 1 },
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
                                AppIcons.Bolt,
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
                                AppIcons.BatteryChargingFull,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(serverUrlFocus)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 2 },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(domainFocus)
                        .onFocusChanged { if (it.isFocused) focusedFieldIndex = 5 },
                    singleLine = true
                )
            }
            
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(if (isRussian()) "Имя пользователя" else "Username") },
                placeholder = { Text("username") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(usernameFocus)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 3 },
                singleLine = true
            )
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(Strings.password) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    .onFocusChanged { if (it.isFocused) focusedFieldIndex = 4 },
                singleLine = true,
                visualTransformation = if (passwordVisible) 
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) 
                                AppIcons.VisibilityOff else AppIcons.Visibility,
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
                    onCheckedChange = { 
                        acceptAllCerts = it
                        // КРИТИЧНО: Если включаем "принимать все" - обнуляем выбранный сертификат
                        if (it) {
                            certificatePath = null
                            certificateFileName = ""
                        }
                    }
                )
                Text(
                    text = if (isRussian()) "Принимать все сертификаты (опаснее)" 
                           else "Accept all certificates (less secure)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { 
                        acceptAllCerts = !acceptAllCerts
                        // КРИТИЧНО: Если включаем "принимать все" - обнуляем выбранный сертификат
                        if (acceptAllCerts) {
                            certificatePath = null
                            certificateFileName = ""
                        }
                    }
                )
            }
            
            // Выбор сертификата сервера (только если не включено "Принимать все")
            if (!acceptAllCerts) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                AppIcons.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isRussian()) "Сертификат сервера" else "Server certificate",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = certificateFileName 
                                        ?: if (isRussian()) "Не выбран (опционально)" else "Not selected (optional)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (certificateFileName != null) 
                                        MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { 
                                    // MIME-типы для сертификатов + общий для файлов без типа
                                    certificatePicker.launch(arrayOf(
                                        "application/x-x509-ca-cert",      // .cer, .crt
                                        "application/x-pem-file",          // .pem
                                        "application/pkix-cert",           // .cer
                                        "application/pkcs12",              // .p12, .pfx
                                        "application/x-pkcs12",            // .p12, .pfx
                                        "application/x-pkcs7-certificates", // .p7b, .p7c
                                        "application/octet-stream",        // общий бинарный
                                        "*/*"                              // fallback для всех
                                    ))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(AppIcons.FileOpen, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isRussian()) "Выбрать" else "Select")
                            }
                            
                            if (certificatePath != null) {
                                OutlinedButton(
                                    onClick = {
                                        // Удаляем файл сертификата
                                        certificatePath?.let { path ->
                                            try { File(path).delete() } catch (_: Exception) {}
                                        }
                                        certificatePath = null
                                        certificateFileName = null
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isRussian()) "Удалить" else "Remove")
                                }
                            }
                        }
                        
                        Text(
                            text = if (isRussian()) 
                                "Для корпоративных серверов с самоподписанным сертификатом" 
                            else "For corporate servers with self-signed certificate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            // Клиентский сертификат (опционально, только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                AppIcons.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isRussian()) "Клиентский сертификат" else "Client certificate",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = clientCertificateFileName 
                                        ?: if (isRussian()) "Не выбран (опционально)" else "Not selected (optional)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (clientCertificateFileName != null) 
                                        MaterialTheme.colorScheme.secondary 
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Кнопка выбора сертификата
                        OutlinedButton(
                            onClick = { 
                                clientCertificatePicker.launch(arrayOf(
                                    "application/x-pkcs12",    // .p12, .pfx
                                    "application/pkcs12",      // .p12, .pfx
                                    "application/octet-stream", // общий бинарный
                                    "*/*"                      // fallback
                                ))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FileOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRussian()) "Выбрать .p12/.pfx" else "Select .p12/.pfx")
                        }
                        
                        // Поле пароля (показываем только если сертификат выбран)
                        if (clientCertificatePath != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedTextField(
                                value = clientCertificatePassword,
                                onValueChange = { clientCertificatePassword = it },
                                label = { 
                                    Text(if (isRussian()) "Пароль сертификата" else "Certificate password") 
                                },
                                visualTransformation = if (clientCertPasswordVisible) 
                                    VisualTransformation.None 
                                    else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { clientCertPasswordVisible = !clientCertPasswordVisible }) {
                                        Icon(
                                            if (clientCertPasswordVisible) AppIcons.VisibilityOff 
                                            else AppIcons.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Кнопка удалить
                            OutlinedButton(
                                onClick = {
                                    clientCertificatePath?.let { path ->
                                        try { File(path).delete() } catch (_: Exception) {}
                                    }
                                    clientCertificatePath = null
                                    clientCertificateFileName = null
                                    clientCertificatePassword = ""
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isRussian()) "Удалить сертификат" else "Remove certificate")
                            }
                        }
                        
                        Text(
                            text = if (isRussian()) 
                                "Для двусторонней TLS аутентификации (mTLS)" 
                            else "For mutual TLS authentication (mTLS)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            // Сообщения
            errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = NotificationStrings.localizeError(it, isRussianLang),
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
                          password.isNotBlank() &&
                          // Если указан клиентский сертификат, должен быть указан пароль
                          (clientCertificatePath == null || clientCertificatePassword.isNotBlank())
            
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        successMessage = null
                        
                        try {
                            if (clientCertificatePath != null) {
                                val isValid = com.dedovmosol.iwomail.network.HttpClientProvider
                                    .validateClientCertificate(clientCertificatePath!!, clientCertificatePassword)
                                if (!isValid) {
                                    errorMessage = if (isRussianLang) {
                                        "Неверный пароль или повреждённый сертификат"
                                    } else {
                                        "Invalid password or corrupted certificate"
                                    }
                                    isLoading = false
                                    return@launch
                                }
                            }
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
                                            syncMode = syncMode.name,
                                            certificatePath = certificatePath,
                                            clientCertificatePath = clientCertificatePath
                                        ),
                                        password = if (password.isNotBlank()) password else null
                                    )
                                    // Обновляем пароль клиентского сертификата
                                    if (clientCertificatePath != null) {
                                        // Сертификат указан - сохраняем/обновляем пароль
                                        if (clientCertificatePassword.isNotBlank()) {
                                            accountRepo.updateClientCertificatePassword(editAccountId, clientCertificatePassword)
                                        }
                                    } else {
                                        // Сертификат удален - удаляем пароль
                                        accountRepo.updateClientCertificatePassword(editAccountId, null)
                                    }
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
                                    useSSL, syncMode.name,
                                    certificatePath,
                                    clientCertificatePath,
                                    clientCertificatePassword.ifBlank { null }
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
                                    syncMode = syncMode,
                                    certificatePath = certificatePath
                                )
                            }
                            
                            when (result) {
                                is EasResult.Success -> {
                                    val accountId = result.data
                                    
                                    // Автоматически включаем Certificate Pinning если был загружен сертификат
                                    if (certificatePath != null) {
                                        try {
                                            val pinResult = accountRepo.pinCertificate(accountId)
                                            if (pinResult is EasResult.Success) {
                                                android.util.Log.d("SetupScreen", "Certificate Pinning enabled automatically for new account")
                                            } else if (pinResult is EasResult.Error) {
                                                android.util.Log.w("SetupScreen", "Failed to auto-enable Certificate Pinning: ${pinResult.message}")
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.w("SetupScreen", "Exception during auto-enable Certificate Pinning", e)
                                        }
                                    }
                                    
                                    onSetupComplete()
                                }
                                is EasResult.Error -> errorMessage = result.message
                            }
                        } catch (e: Exception) {
                            errorMessage = NotificationStrings.getErrorWithMessage(isRussianLang, e.message)
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
    
    // Полноэкранный экран справки
    if (showHelpScreen) {
        SetupHelpScreen(
            onClose = { showHelpScreen = false }
        )
    }
}

/**
 * Полноэкранный экран справки по настройке аккаунта
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupHelpScreen(onClose: () -> Unit) {
    val isRu = isRussian()
    val interactionSource = remember { MutableInteractionSource() }
    val contentInteractionSource = remember { MutableInteractionSource() }
    
    // Перехватываем системную кнопку/жест "назад"
    androidx.activity.compose.BackHandler(onBack = onClose)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isRu) "Справка по настройке" else "Setup guide", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(AppIcons.ArrowBack, contentDescription = if (isRu) "Закрыть" else "Close", tint = Color.White)
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
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClose
        )
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .clickable(
                    interactionSource = contentInteractionSource,
                    indication = null,
                    onClick = { /* Предотвращаем закрытие при клике на контент */ }
                )
        ) {
            if (isRu) {
                // Русская версия
                HelpSection(
                    icon = "📧",
                    title = "Email",
                    description = "Ваш полный адрес электронной почты",
                    example = "Пример: user@company.com"
                )
                
                HelpSection(
                    icon = "🖥️",
                    title = "Сервер",
                    description = "Адрес Exchange сервера без протокола (http:// или https://)",
                    example = "Пример: mail.company.com"
                )
                
                HelpSection(
                    icon = "🔢",
                    title = "Порт",
                    description = "Порт для подключения к серверу",
                    example = "443 для HTTPS (рекомендуется)\n80 для HTTP"
                )
                
                HelpSection(
                    icon = "🏢",
                    title = "Домен (опционально)",
                    description = "Домен Active Directory вашей организации",
                    example = "Пример: COMPANY\nОбычно не требуется для Exchange Online"
                )
                
                HelpSection(
                    icon = "👤",
                    title = "Имя пользователя",
                    description = "Логин для входа в систему",
                    example = "Может совпадать с email или быть в формате domain\\username"
                )
                
                HelpSection(
                    icon = "🔒",
                    title = "Пароль",
                    description = "Пароль от учётной записи Exchange",
                    example = "Хранится в зашифрованном виде (AES-256-GCM)"
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    "🔐 Сертификаты (опционально)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                HelpSection(
                    icon = "📜",
                    title = "Сертификат сервера",
                    description = "Используется для доверия самоподписанным сертификатам корпоративных серверов",
                    example = "Форматы: .cer, .crt, .pem, .der",
                    benefits = listOf(
                        "✓ Защищает от подмены сервера злоумышленником",
                        "✓ Защищает от атак \"человек посередине\" (перехват данных)"
                    )
                )
                
                HelpSection(
                    icon = "🔑",
                    title = "Клиентский сертификат",
                    description = "Для двусторонней TLS аутентификации (mTLS). Требуется если сервер запрашивает сертификат клиента.",
                    example = "Форматы: .p12, .pfx\nТребуется пароль от сертификата",
                    benefits = listOf(
                        "✓ Максимальная безопасность",
                        "✓ Сервер проверяет подлинность устройства",
                        "⚠️ НЕ заменяет логин/пароль - это дополнительный слой защиты"
                    )
                )
            } else {
                // English version
                HelpSection(
                    icon = "📧",
                    title = "Email",
                    description = "Your full email address",
                    example = "Example: user@company.com"
                )
                
                HelpSection(
                    icon = "🖥️",
                    title = "Server",
                    description = "Exchange server address without protocol (http:// or https://)",
                    example = "Example: mail.company.com"
                )
                
                HelpSection(
                    icon = "🔢",
                    title = "Port",
                    description = "Port for server connection",
                    example = "443 for HTTPS (recommended)\n80 for HTTP"
                )
                
                HelpSection(
                    icon = "🏢",
                    title = "Domain (optional)",
                    description = "Your organization's Active Directory domain",
                    example = "Example: COMPANY\nUsually not required for Exchange Online"
                )
                
                HelpSection(
                    icon = "👤",
                    title = "Username",
                    description = "Login username for the system",
                    example = "May match email or be in domain\\username format"
                )
                
                HelpSection(
                    icon = "🔒",
                    title = "Password",
                    description = "Exchange account password",
                    example = "Stored encrypted (AES-256-GCM)"
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    "🔐 Certificates (optional)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                HelpSection(
                    icon = "📜",
                    title = "Server certificate",
                    description = "Used to trust self-signed certificates of corporate servers",
                    example = "Formats: .cer, .crt, .pem, .der",
                    benefits = listOf(
                        "✓ Ensures you connect to the real company server",
                        "✓ Protects against man-in-the-middle attacks (data interception)"
                    )
                )
                
                HelpSection(
                    icon = "🔑",
                    title = "Client certificate",
                    description = "For mutual TLS authentication (mTLS). Required if server requests client certificate.",
                    example = "Formats: .p12, .pfx\nCertificate password required",
                    benefits = listOf(
                        "✓ Maximum security",
                        "✓ Server verifies device authenticity",
                        "⚠️ Does NOT replace login/password - it's an additional security layer"
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Компонент секции справки
 */
@Composable
private fun HelpSection(
    icon: String,
    title: String,
    description: String,
    example: String,
    benefits: List<String>? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                example,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
            
            benefits?.let {
                Spacer(modifier = Modifier.height(8.dp))
                it.forEach { benefit ->
                    Text(
                        benefit,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (benefit.startsWith("⚠️")) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(12.dp))
}

