package com.dedovmosol.iwomail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import kotlinx.coroutines.launch
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import com.dedovmosol.iwomail.ui.navigation.AppNavigation

@Composable
private fun PermissionDialog(
    title: String,
    text: String,
    dismissText: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
    val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
    
    // Анимация появления
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
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
            shape = RoundedCornerShape(28.dp)
        ) {
            Column {
                // Градиентная полоска сверху
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            brush = Brush.horizontalGradient(
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
                                brush = Brush.linearGradient(
                                    colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            AppIcons.BatteryChargingFull,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Разбиваем текст на абзацы с красной строкой
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        text.split("\n\n").forEachIndexed { index, paragraph ->
                            if (paragraph.isNotBlank()) {
                                val isLastParagraph = index == text.split("\n\n").lastIndex
                                Text(
                                    text = paragraph,
                                    style = if (isLastParagraph) {
                                        MaterialTheme.typography.bodyMedium
                                    } else {
                                        MaterialTheme.typography.bodyMedium.copy(
                                            textIndent = TextIndent(firstLine = 24.sp)
                                        )
                                    },
                                    textAlign = if (isLastParagraph) TextAlign.Center else TextAlign.Justify,
                                    modifier = if (isLastParagraph) Modifier.fillMaxWidth() else Modifier
                                )
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                            onClick = onConfirm,
                            text = confirmText
                        )
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    
    companion object {
        const val EXTRA_OPEN_INBOX_UNREAD = "open_inbox_unread"
        const val EXTRA_OPEN_EMAIL_ID = "open_email_id"
        const val EXTRA_SWITCH_ACCOUNT_ID = "switch_account_id"
        const val EXTRA_OPEN_UPDATES = "open_updates"
        
        // App Shortcuts actions
        const val ACTION_SHORTCUT_COMPOSE = "com.dedovmosol.iwomail.SHORTCUT_COMPOSE"
        const val ACTION_SHORTCUT_INBOX = "com.dedovmosol.iwomail.SHORTCUT_INBOX"
        const val ACTION_SHORTCUT_SEARCH = "com.dedovmosol.iwomail.SHORTCUT_SEARCH"
        const val ACTION_SHORTCUT_SYNC = "com.dedovmosol.iwomail.SHORTCUT_SYNC"
        const val ACTION_SHORTCUT_ADD_WIDGET = "com.dedovmosol.iwomail.SHORTCUT_ADD_WIDGET"
        
        // Keys for savedInstanceState
        private const val KEY_PERMISSIONS_CHECKED = "permissions_checked"
        private const val KEY_PERMISSIONS_DIALOGS_SHOWN = "permissions_dialogs_shown"
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    
    private var openInboxUnread = mutableStateOf(false)
    private var openEmailId = mutableStateOf<String?>(null)
    private var switchToAccountId = mutableStateOf<Long?>(null)
    private var showBatteryDialog = mutableStateOf(false)
    private var showAlarmDialog = mutableStateOf(false)
    
    // КРИТИЧНО: Сохраняем состояние при rotation через savedInstanceState
    // Иначе в альбомной ориентации после онбординга диалог не показывается
    private var permissionsChecked = false
    private var permissionsDialogsShown = false
    
    // Данные из mailto: или SEND intent
    private var composeEmail = mutableStateOf<String?>(null)
    private var composeSubject = mutableStateOf<String?>(null)
    private var composeBody = mutableStateOf<String?>(null)
    private var composeAttachments = mutableStateOf<List<android.net.Uri>>(emptyList())
    
    // Открытие экрана обновлений из уведомления
    private var openUpdates = mutableStateOf(false)
    
    // App Shortcuts
    private var shortcutCompose = mutableStateOf(false)
    private var shortcutInbox = mutableStateOf(false)
    private var shortcutSearch = mutableStateOf(false)
    private var shortcutCalendar = mutableStateOf(false)
    private var shortcutTasks = mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // КРИТИЧНО: Восстанавливаем состояние после rotation
        // Иначе диалог battery не показывается в альбомной ориентации
        if (savedInstanceState != null) {
            permissionsChecked = savedInstanceState.getBoolean(KEY_PERMISSIONS_CHECKED, false)
            permissionsDialogsShown = savedInstanceState.getBoolean(KEY_PERMISSIONS_DIALOGS_SHOWN, false)
        }
        
        // Sync shortcut — запускаем синхронизацию через broadcast и сразу выходим
        if (intent?.action == ACTION_SHORTCUT_SYNC) {
            intent.action = null
            // Отправляем broadcast на SyncAlarmReceiver — он покажет toast о начале и завершении
            sendBroadcast(Intent(com.dedovmosol.iwomail.sync.SyncAlarmReceiver.ACTION_SYNC_NOW).apply {
                setClass(applicationContext, com.dedovmosol.iwomail.sync.SyncAlarmReceiver::class.java)
            })
            finishAffinity()
            return
        }
        
        enableHighRefreshRate()
        requestNotificationPermission()
        if (!permissionsChecked) {
            permissionsChecked = true
            checkPermissionsForDialogs()
        }
        handleIntent(intent)
        
        enableEdgeToEdge()
        setContent {
            val settingsRepo = remember { SettingsRepository.getInstance(this) }
            val initialLanguage = remember { settingsRepo.getLanguageSync() }
            val languageCode by settingsRepo.language.collectAsState(initial = initialLanguage)
            val initialFontSize = remember { settingsRepo.getFontSizeSync() }
            val fontSize by settingsRepo.fontSize.collectAsState(initial = initialFontSize)
            
            // Цветовая тема с учётом расписания по дням
            val initialColorTheme = remember { settingsRepo.getCurrentThemeSync() }
            val colorThemeCode by settingsRepo.colorTheme.collectAsState(initial = initialColorTheme)
            val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsState(initial = false)
            
            // Получаем тему для текущего дня если включено расписание
            val currentDayOfWeek = remember { java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) }
            val dayThemeCode by settingsRepo.getDayTheme(currentDayOfWeek).collectAsState(initial = "purple")
            
            val effectiveThemeCode = if (dailyThemesEnabled) dayThemeCode else colorThemeCode
            val colorTheme = remember(effectiveThemeCode) {
                AppColorTheme.fromCode(effectiveThemeCode)
            }
            
            // Анимации
            val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
            
            val currentLanguage = remember(languageCode) {
                AppLanguage.entries.find { it.code == languageCode } ?: AppLanguage.RUSSIAN
            }
            
            val shouldOpenInboxUnread by openInboxUnread
            val emailIdToOpen by openEmailId
            val accountIdToSwitch by switchToAccountId
            val shouldOpenUpdates by openUpdates
            val emailToCompose by composeEmail
            val subjectToCompose by composeSubject
            val bodyToCompose by composeBody
            val attachmentsToCompose by composeAttachments
            
            // App Shortcuts
            val shouldShortcutCompose by shortcutCompose
            val shouldShortcutInbox by shortcutInbox
            val shouldShortcutSearch by shortcutSearch
            
            // Контроллер отложенного удаления
            val deletionController = remember { com.dedovmosol.iwomail.ui.components.DeletionController() }
            
            // Контроллер отложенной отправки
            val sendController = remember { com.dedovmosol.iwomail.ui.components.SendController() }
            
            // Кастомный TextToolbar с правильным порядком кнопок
            val view = androidx.compose.ui.platform.LocalView.current
            val customTextToolbar = remember(view) { com.dedovmosol.iwomail.ui.theme.CustomTextToolbar(view) }
            
            CompositionLocalProvider(
                LocalLanguage provides currentLanguage,
                com.dedovmosol.iwomail.ui.components.LocalDeletionController provides deletionController,
                com.dedovmosol.iwomail.ui.components.LocalSendController provides sendController,
                androidx.compose.ui.platform.LocalTextToolbar provides customTextToolbar
            ) {
                ExchangeMailTheme(fontScale = fontSize.scale, colorTheme = colorTheme, animationsEnabled = animationsEnabled) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            AppNavigation(
                                openInboxUnread = shouldOpenInboxUnread,
                                openEmailId = emailIdToOpen,
                                switchToAccountId = accountIdToSwitch,
                                composeToEmail = emailToCompose,
                                composeSubject = subjectToCompose,
                                composeBody = bodyToCompose,
                                composeAttachments = attachmentsToCompose,
                                onComposeHandled = {
                                    composeEmail.value = null
                                    composeSubject.value = null
                                    composeBody.value = null
                                    composeAttachments.value = emptyList()
                                },
                                onAccountSwitched = {
                                    switchToAccountId.value = null
                                },
                                shortcutCompose = shouldShortcutCompose,
                                shortcutInbox = shouldShortcutInbox,
                                shortcutSearch = shouldShortcutSearch,
                                shortcutCalendar = shortcutCalendar.value,
                                shortcutTasks = shortcutTasks.value,
                                onShortcutHandled = {
                                    shortcutCompose.value = false
                                    shortcutInbox.value = false
                                    shortcutSearch.value = false
                                    shortcutCalendar.value = false
                                    shortcutTasks.value = false
                                },
                                openUpdates = shouldOpenUpdates,
                                onUpdatesHandled = { openUpdates.value = false },
                                onOnboardingComplete = {
                                    checkPermissionsForDialogs()
                                }
                            )
                        }
                        
                        // Плашка прогресса удаления (внизу экрана)
                        com.dedovmosol.iwomail.ui.components.DeletionProgressBar(
                            controller = deletionController,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                        
                        // Плашка прогресса отправки (внизу экрана, зелёная)
                        com.dedovmosol.iwomail.ui.components.SendProgressBar(
                            controller = sendController,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                    
                    val showBattery by showBatteryDialog
                    if (showBattery) {
                        PermissionDialog(
                            title = Strings.backgroundWorkTitle,
                            text = Strings.backgroundWorkText,
                            dismissText = Strings.later,
                            confirmText = Strings.continueAction,
                            onDismiss = { showBatteryDialog.value = false },
                            onConfirm = {
                                showBatteryDialog.value = false
                                openBatterySettings()
                            }
                        )
                    }
                    
                    val showAlarm by showAlarmDialog
                    if (showAlarm) {
                        PermissionDialog(
                            title = Strings.exactAlarmsTitle,
                            text = Strings.exactAlarmsText,
                            dismissText = Strings.later,
                            confirmText = Strings.continueAction,
                            onDismiss = { showAlarmDialog.value = false },
                            onConfirm = {
                                showAlarmDialog.value = false
                                openAlarmSettings()
                            }
                        )
                    }
                }
            }
            
            LaunchedEffect(shouldOpenInboxUnread) {
                if (shouldOpenInboxUnread) {
                    kotlinx.coroutines.delay(2000)
                    openInboxUnread.value = false
                }
            }
            
            // openEmailId не сбрасываем — AppNavigation сама обработает и запомнит
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        // После возврата из настроек проверяем следующее разрешение
        // НО только если мы в процессе первичной настройки (permissionsDialogsShown = true)
        if (permissionsChecked && permissionsDialogsShown && !showBatteryDialog.value && !showAlarmDialog.value) {
            checkNextPermission()
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // КРИТИЧНО: Сохраняем состояние разрешений при rotation
        // Иначе диалог battery не показывается в альбомной ориентации после онбординга
        outState.putBoolean(KEY_PERMISSIONS_CHECKED, permissionsChecked)
        outState.putBoolean(KEY_PERMISSIONS_DIALOGS_SHOWN, permissionsDialogsShown)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        // Проверяем нужно ли переключить аккаунт
        val accountId = intent.getLongExtra(EXTRA_SWITCH_ACCOUNT_ID, -1L)
        if (accountId > 0) {
            switchToAccountId.value = accountId
            intent.removeExtra(EXTRA_SWITCH_ACCOUNT_ID)
        }
        
        val emailId = intent.getStringExtra(EXTRA_OPEN_EMAIL_ID)
        if (emailId != null) {
            openEmailId.value = emailId
            intent.removeExtra(EXTRA_OPEN_EMAIL_ID)
            return
        }
        
        if (intent.getBooleanExtra(EXTRA_OPEN_INBOX_UNREAD, false)) {
            openInboxUnread.value = true
            intent.removeExtra(EXTRA_OPEN_INBOX_UNREAD)
            return
        }
        
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) {
            openUpdates.value = true
            intent.removeExtra(EXTRA_OPEN_UPDATES)
            return
        }
        
        // Widget actions (Glance передаёт параметры как Boolean extras)
        if (intent.getBooleanExtra("compose", false)) {
            shortcutCompose.value = true
            intent.removeExtra("compose")
            return
        }
        if (intent.getBooleanExtra("inbox", false)) {
            shortcutInbox.value = true
            intent.removeExtra("inbox")
            return
        }
        if (intent.getBooleanExtra("search", false)) {
            shortcutSearch.value = true
            intent.removeExtra("search")
            return
        }
        if (intent.getBooleanExtra("calendar", false)) {
            shortcutCalendar.value = true
            intent.removeExtra("calendar")
            return
        }
        if (intent.getBooleanExtra("tasks", false)) {
            shortcutTasks.value = true
            intent.removeExtra("tasks")
            return
        }
        
        // App Shortcuts
        when (intent.action) {
            ACTION_SHORTCUT_COMPOSE -> {
                shortcutCompose.value = true
                intent.action = null
                return
            }
            ACTION_SHORTCUT_INBOX -> {
                shortcutInbox.value = true
                intent.action = null
                return
            }
            ACTION_SHORTCUT_SEARCH -> {
                shortcutSearch.value = true
                intent.action = null
                return
            }
            ACTION_SHORTCUT_SYNC -> {
                // Обрабатывается в onCreate до setContent
                intent.action = null
                return
            }
            ACTION_SHORTCUT_ADD_WIDGET -> {
                requestPinWidget()
                intent.action = null
                return
            }
        }
        
        // Обработка mailto: ссылок
        when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                try {
                    intent.data?.let { uri ->
                        if (uri.scheme == "mailto") {
                            parseMailtoUri(uri)
                            // Очищаем intent чтобы не обрабатывать повторно при повороте экрана
                            intent.data = null
                            intent.action = null
                        }
                    }
                } catch (_: Exception) { }
            }
            Intent.ACTION_SEND -> {
                try {
                    // Получаем данные из SEND intent
                    val email = intent.getStringExtra(Intent.EXTRA_EMAIL)
                        ?: intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.firstOrNull()
                    val subj = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    val bodyText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    
                    // Получаем вложение (файл, картинка и т.д.)
                    @Suppress("DEPRECATION")
                    val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    
                    // Устанавливаем значения (null если не переданы)
                    composeEmail.value = email
                    composeSubject.value = subj
                    composeBody.value = bodyText
                    composeAttachments.value = if (streamUri != null) listOf(streamUri) else emptyList()
                    
                    // Очищаем intent
                    intent.action = null
                } catch (_: Exception) { }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                try {
                    val subj = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    val bodyText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    
                    // Получаем несколько вложений
                    @Suppress("DEPRECATION")
                    val streamUris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    
                    // Устанавливаем значения
                    composeEmail.value = null
                    composeSubject.value = subj
                    composeBody.value = bodyText
                    composeAttachments.value = streamUris ?: emptyList()
                    
                    // Очищаем intent
                    intent.action = null
                } catch (_: Exception) { }
            }
        }
    }
    
    private fun requestPinWidget() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this)
            val widgetProvider = android.content.ComponentName(this, com.dedovmosol.iwomail.widget.MailWidgetReceiver::class.java)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                appWidgetManager.requestPinAppWidget(widgetProvider, null, null)
            }
        }
    }
    
    private fun parseMailtoUri(uri: android.net.Uri) {
        try {
            // mailto:user@example.com?subject=Test&body=Hello
            // schemeSpecificPart = "user@example.com?subject=Test&body=Hello"
            val ssp = uri.schemeSpecificPart ?: ""
            val email = ssp.substringBefore("?").trim()
            composeEmail.value = email.ifBlank { null }
            composeAttachments.value = emptyList() // mailto: не имеет вложений
            
            // Парсим query параметры безопасно
            try {
                composeSubject.value = uri.getQueryParameter("subject")
                composeBody.value = uri.getQueryParameter("body")
                uri.getQueryParameter("to")?.let { if (composeEmail.value == null) composeEmail.value = it }
                uri.getQueryParameter("cc")?.let { /* можно добавить поддержку cc */ }
                uri.getQueryParameter("bcc")?.let { /* можно добавить поддержку bcc */ }
            } catch (_: Exception) {
                // Если query параметры не парсятся - игнорируем
                composeSubject.value = null
                composeBody.value = null
            }
        } catch (_: Exception) {
            // Если URI некорректный - просто открываем пустой compose
        }
    }
    
    private fun checkPermissionsForDialogs() {
        // Не показываем диалоги разрешений пока не пройден онбординг
        val settingsRepo = SettingsRepository.getInstance(applicationContext)
        if (!settingsRepo.getOnboardingShownSync()) {
            return
        }
        
        // ИСПРАВЛЕНО БАГ #2: Показываем диалог разрешения СРАЗУ после онбоардинга
        // (даже если аккаунта ещё нет) - по требованию пользователя
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(android.os.PowerManager::class.java)
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    showBatteryDialog.value = true
                    return@launch
                }
            }
            
            checkNextPermission()
        }
    }
    
    /**
     * Проверка разрешений для экрана авторизации (SetupScreen)
     * Вызывается после успешного добавления аккаунта
     */
    fun checkPermissionsAfterSetup() {
        permissionsChecked = true
        permissionsDialogsShown = true // Отмечаем что начали показывать диалоги
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryDialog.value = true
                return
            }
        }
        
        checkNextPermission()
    }
    
    private fun checkNextPermission() {
        // Проверяем alarm только если батарея уже разрешена
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                return // Батарея ещё не разрешена
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(android.app.AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                showAlarmDialog.value = true
                return
            }
        }
        
        // Все разрешения проверены - сбрасываем флаг
        permissionsDialogsShown = false
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> { }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    
    private fun openAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) { }
        }
    }
    
    @android.annotation.SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallbackIntent = android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    )
                    startActivity(fallbackIntent)
                } catch (_: Exception) { }
            }
        }
    }
    
    private fun enableHighRefreshRate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.let { display ->
                    val modes = display.supportedModes
                    val highestMode = modes.maxByOrNull { it.refreshRate }
                    highestMode?.let { mode ->
                        window.attributes = window.attributes.apply {
                            preferredDisplayModeId = mode.modeId
                        }
                    }
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val modes = display.supportedModes
                val highestMode = modes.maxByOrNull { it.refreshRate }
                highestMode?.let { mode ->
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = mode.modeId
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
