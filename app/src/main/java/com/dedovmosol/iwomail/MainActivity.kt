package com.dedovmosol.iwomail

import com.dedovmosol.iwomail.util.MailtoLink
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.dedovmosol.iwomail.ui.components.AppPermissionDialog
import com.dedovmosol.iwomail.ui.components.AppPermissionPrompt
import androidx.lifecycle.lifecycleScope
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import com.dedovmosol.iwomail.ui.navigation.AppNavigation

/**
 * Главная Activity.
 *
 * Суперкласс [FragmentActivity] (а не ComponentActivity): стабильный канал
 * androidx.biometric:1.1.0 конструирует BiometricPrompt только из
 * FragmentActivity/Fragment (проверено в исходниках библиотеки).
 * FragmentActivity расширяет ComponentActivity, поэтому весь Compose-код
 * (setContent, enableEdgeToEdge, registerForActivityResult) работает без изменений.
 */
class MainActivity : FragmentActivity() {
    
    companion object {
        const val EXTRA_OPEN_INBOX_UNREAD = "open_inbox_unread"
        const val EXTRA_OPEN_EMAIL_ID = "open_email_id"
        const val EXTRA_SWITCH_ACCOUNT_ID = "switch_account_id"
        const val EXTRA_OPEN_UPDATES = "open_updates"
        const val EXTRA_OPEN_CALENDAR = "open_calendar"
        const val EXTRA_CALENDAR_EVENT_ID = "calendar_event_id"
        const val EXTRA_OPEN_TASKS = "open_tasks"
        const val EXTRA_TASK_ID = "open_task_id"
        
        // App Shortcuts actions
        const val ACTION_SHORTCUT_COMPOSE = "com.dedovmosol.iwomail.SHORTCUT_COMPOSE"
        const val ACTION_SHORTCUT_INBOX = "com.dedovmosol.iwomail.SHORTCUT_INBOX"
        const val ACTION_SHORTCUT_SEARCH = "com.dedovmosol.iwomail.SHORTCUT_SEARCH"
        const val ACTION_SHORTCUT_SYNC = "com.dedovmosol.iwomail.SHORTCUT_SYNC"
        const val ACTION_SHORTCUT_ADD_WIDGET = "com.dedovmosol.iwomail.SHORTCUT_ADD_WIDGET"
        
        private const val TAG = "MainActivity"
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { promptPermissionsOnce() }

    /** Системный лист уведомлений уже показали в этом процессе — не крутить его под батареей. */
    private var notificationAskedThisProcess = false
    
    private var openInboxUnreadIntentId = mutableLongStateOf(0L)
    private var rejectedInboxIntentId = mutableLongStateOf(-1L)
    private var openEmailId = mutableStateOf<String?>(null)
    private var openEmailIntentId = mutableLongStateOf(0L)
    private var switchToAccountId = mutableStateOf<Long?>(null)
    private var showBatteryDialog = mutableStateOf(false)
    private var showAlarmDialog = mutableStateOf(false)
    // «Продолжить» в диалоге «Фоновая работа» открыл системный запрос: по возвращении — следующее разрешение
    private var awaitingBatteryResult = false
    
    // Данные из mailto: или SEND intent
    private var composeEmail = mutableStateOf<String?>(null)
    private var composeCc = mutableStateOf<String?>(null)
    private var composeBcc = mutableStateOf<String?>(null)
    private var composeSubject = mutableStateOf<String?>(null)
    private var composeBody = mutableStateOf<String?>(null)
    private var composeAttachments = mutableStateOf<List<android.net.Uri>>(emptyList())
    private var composeIntentId = mutableLongStateOf(0L)
    
    // Открытие экрана обновлений из уведомления
    private var openUpdates = mutableStateOf(false)
    
    // App Shortcuts
    private var shortcutCompose = mutableStateOf(false)
    private var shortcutInbox = mutableStateOf(false)
    private var shortcutSearch = mutableStateOf(false)
    private var shortcutCalendar = mutableStateOf(false)
    private var openCalendarEventId = mutableStateOf<String?>(null)
    private var shortcutTasks = mutableStateOf(false)
    private var openTaskId = mutableStateOf<String?>(null)
    private var shortcutNotes = mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Sync shortcut — запускаем синхронизацию через broadcast и сразу выходим
        if (intent?.action == ACTION_SHORTCUT_SYNC) {
            com.dedovmosol.iwomail.util.AppShortcuts.reportUsed(this, ACTION_SHORTCUT_SYNC)
            intent.action = null
            // Отправляем broadcast на SyncAlarmReceiver — он покажет toast о начале и завершении
            sendBroadcast(Intent(com.dedovmosol.iwomail.sync.SyncAlarmReceiver.ACTION_SYNC_NOW).apply {
                setClass(applicationContext, com.dedovmosol.iwomail.sync.SyncAlarmReceiver::class.java)
            })
            finishAffinity()
            return
        }
        
        enableHighRefreshRate()
        // Сам запрос разрешений — только при обычном запуске (значок приложения): не поверх письма из «Поделиться»,
        // ответа на mailto:, перехода из уведомления или виджета
        if (savedInstanceState == null && (intent?.action == null || intent?.action == Intent.ACTION_MAIN)) {
            checkPermissionsForDialogs()
        }
        handleIntent(intent)
        
        enableEdgeToEdge()
        setContent {
            val settingsRepo = remember { SettingsRepository.getInstance(this) }
            val initialLanguage = remember { settingsRepo.getLanguageSync() }
            val languageCode by settingsRepo.language.collectAsStateWithLifecycle(initialValue = initialLanguage)
            val initialFontSize = remember { settingsRepo.getFontSizeSync() }
            val fontSize by settingsRepo.fontSize.collectAsStateWithLifecycle(initialValue = initialFontSize)
            
            // Режим темы: системный / светлая / тёмная
            val initialThemeMode = remember { settingsRepo.getThemeModeSync() }
            val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle(initialValue = initialThemeMode)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                SettingsRepository.ThemeMode.SYSTEM -> systemDark
                SettingsRepository.ThemeMode.LIGHT -> false
                SettingsRepository.ThemeMode.DARK -> true
            }
            
            // Контраст иконок системных баров следует теме приложения, а не системы
            // (официальный паттерн Compose: enableEdgeToEdge + SystemBarStyle)
            val systemBarStyle = if (darkTheme) {
                SystemBarStyle.dark(Color.Transparent.toArgb())
            } else {
                SystemBarStyle.light(
                    Color.Transparent.toArgb(),
                    Color.Black.copy(alpha = 0.3f).toArgb()
                )
            }
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle
                )
                onDispose {}
            }
            
            // Цветовая тема с учётом расписания по дням
            val initialColorTheme = remember { settingsRepo.getCurrentThemeSync() }
            val colorThemeCode by settingsRepo.colorTheme.collectAsStateWithLifecycle(initialValue = initialColorTheme)
            val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsStateWithLifecycle(initialValue = false)
            
            // Получаем тему для текущего дня если включено расписание
            val currentDayOfWeek = remember { java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) }
            val dayThemeCode by settingsRepo.getDayTheme(currentDayOfWeek).collectAsStateWithLifecycle(initialValue = "purple")
            
            val effectiveThemeCode = if (dailyThemesEnabled) dayThemeCode else colorThemeCode
            val colorTheme = remember(effectiveThemeCode) {
                AppColorTheme.fromCode(effectiveThemeCode)
            }
            
            // Цвет скроллбара с учётом расписания по дням
            val initialScrollbarColor = remember { settingsRepo.getCurrentScrollbarColorSync() }
            val scrollbarColorCode by settingsRepo.scrollbarColor.collectAsStateWithLifecycle(initialValue = initialScrollbarColor)
            val dayScrollbarCode by settingsRepo.getDayScrollbarColor(currentDayOfWeek).collectAsStateWithLifecycle(initialValue = "blue")
            val effectiveScrollbarCode = if (dailyThemesEnabled) dayScrollbarCode else scrollbarColorCode
            val scrollbarColor = remember(effectiveScrollbarCode) {
                com.dedovmosol.iwomail.ui.components.ScrollbarColor.fromCode(effectiveScrollbarCode)
            }
            
            // Анимации
            val animationsEnabled by settingsRepo.animationsEnabled.collectAsStateWithLifecycle(initialValue = true)
            
            // ─── Блокировка приложения: пароль + отпечаток пальца (цель релиза) ───
            // Гейт поверх всей навигации: пока заблокировано, контент не виден и не доступен.
            // Состояние сессии живёт в процесс-синглтоне AppLockManager — поворот экрана
            // его НЕ сбрасывает (в отличие от remember), fail-closed при смерти процесса.
            // null — настройки ещё не прочитаны (первые миллисекунды холодного старта): см. AppLockManager.lockRequired
            val appLockFlag by remember {
                settingsRepo.appLockEnabled.map<Boolean, Boolean?> { it }
            }.collectAsStateWithLifecycle(initialValue = settingsRepo.getAppLockEnabledOrNull())
            val appLockBiometricEnabled by settingsRepo.appLockBiometricEnabled.collectAsStateWithLifecycle(
                initialValue = settingsRepo.getAppLockBiometricEnabledSync()
            )
            val appLocked by com.dedovmosol.iwomail.data.security.AppLockManager.locked.collectAsStateWithLifecycle() // UI-6: lifecycle-aware, единообразно с остальным экраном
            // Единая чистая логика гейта (SOC + юнит-тестируемость): сессия заблокирована и блокировка нужна —
            // экран пароля; включена, а пароля на устройстве нет (NAV-04) — сброс по паролю учётной записи.
            // Синглтон хранит состояние, переживающее поворот.
            val passwordSet = com.dedovmosol.iwomail.data.security.AppLockManager.isPasswordSet()
            val lockGate = com.dedovmosol.iwomail.data.security.AppLockManager.gate(appLocked, appLockFlag, passwordSet)
            val isLocked = lockGate != com.dedovmosol.iwomail.data.security.AppLockManager.Gate.OPEN
            
            // Доступность биометрии на устройстве — проверяется один раз на конфигурацию,
            // системный диалог не нужен для самой проверки.
            val biometricAvailable = remember {
                androidx.biometric.BiometricManager.from(this@MainActivity)
                    .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            }
            
            // NAV-02: при включённой блокировке письма не видны в «Недавних» (см. LockWindowPolicy); пропавший хеш
            // этого не снимает (NAV-04)
            val lockActive = com.dedovmosol.iwomail.data.security.AppLockManager.lockRequired(appLockFlag)
            DisposableEffect(lockActive, isLocked) {
                val policy = com.dedovmosol.iwomail.data.security.LockWindowPolicy.of(lockActive, isLocked)
                val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
                if (policy.secure) window.setFlags(secure, secure) else window.clearFlags(secure)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(policy.recentsScreenshot)
                }
                onDispose { }
            }
            
            val currentLanguage = remember(languageCode) {
                AppLanguage.entries.find { it.code == languageCode } ?: AppLanguage.RUSSIAN
            }
            
            val currentOpenInboxUnreadIntentId by openInboxUnreadIntentId
            val emailIdToOpen by openEmailId
            val currentOpenEmailIntentId by openEmailIntentId
            val accountIdToSwitch by switchToAccountId
            val shouldOpenUpdates by openUpdates
            val emailToCompose by composeEmail
            val ccToCompose by composeCc
            val bccToCompose by composeBcc
            val subjectToCompose by composeSubject
            val bodyToCompose by composeBody
            val attachmentsToCompose by composeAttachments
            val currentComposeIntentId by composeIntentId
            
            // App Shortcuts
            val shouldShortcutCompose by shortcutCompose
            val shouldShortcutInbox by shortcutInbox
            val shouldShortcutSearch by shortcutSearch
            
            // Контроллер отложенного удаления
            val deletionController = remember { com.dedovmosol.iwomail.ui.components.DeletionController() }
            
            // Контроллер отложенной отправки
            // Один на процесс: отправка и кнопка отмены переживают поворот экрана
            val sendController = com.dedovmosol.iwomail.ui.components.SendController.shared
            val requestBackgroundWork = remember { { showBackgroundWorkDialog() } }
            
            // Кастомный TextToolbar с правильным порядком кнопок
            val view = androidx.compose.ui.platform.LocalView.current
            val customTextToolbar = remember(view) { com.dedovmosol.iwomail.ui.theme.CustomTextToolbar(view) }
            
            CompositionLocalProvider(
                LocalLanguage provides currentLanguage,
                com.dedovmosol.iwomail.ui.components.LocalDeletionController provides deletionController,
                com.dedovmosol.iwomail.ui.components.LocalSendController provides sendController,
                com.dedovmosol.iwomail.ui.components.LocalRequestBackgroundWork provides requestBackgroundWork,
                androidx.compose.ui.platform.LocalTextToolbar provides customTextToolbar
            ) {
                ExchangeMailTheme(darkTheme = darkTheme, fontScale = fontSize.scale, colorTheme = colorTheme, animationsEnabled = animationsEnabled, scrollbarColor = scrollbarColor) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isLocked) Modifier.clearAndSetSemantics { }
                                    else Modifier
                                ),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            // Навигация всегда в композиции: после разблокировки тот же экран
                            // (файл-пикер и фон тоже ON_STOP — не уничтожаем back stack).
                            AppNavigation(
                                openInboxUnreadIntentId = if (isLocked || currentOpenInboxUnreadIntentId == rejectedInboxIntentId.longValue) 0L else currentOpenInboxUnreadIntentId,
                                openEmailId = if (isLocked) null else emailIdToOpen,
                                openEmailIntentId = if (isLocked) 0L else currentOpenEmailIntentId,
                                switchToAccountId = if (isLocked) null else accountIdToSwitch,
                                composeToEmail = emailToCompose,
                                composeCc = ccToCompose,
                                composeBcc = bccToCompose,
                                composeSubject = subjectToCompose,
                                composeBody = bodyToCompose,
                                composeAttachments = attachmentsToCompose,
                                composeIntentId = currentComposeIntentId,
                                onComposeHandled = {
                                    composeEmail.value = null
                                    composeCc.value = null
                                    composeBcc.value = null
                                    composeSubject.value = null
                                    composeBody.value = null
                                    composeAttachments.value = emptyList()
                                },
                                onAccountSwitched = { switchToAccountId.value = null },
                                onAccountSwitchFailed = {
                                    // The target may have been removed since the launcher/notification was rendered.
                                    // Consume mailbox-specific actions rather than opening them in a different account.
                                    openEmailId.value = null
                                    rejectedInboxIntentId.longValue = openInboxUnreadIntentId.longValue
                                    shortcutCalendar.value = false
                                    openCalendarEventId.value = null
                                    shortcutTasks.value = false
                                    openTaskId.value = null
                                    switchToAccountId.value = null
                                },
                                shortcutCompose = !isLocked && shouldShortcutCompose,
                                shortcutInbox = !isLocked && shouldShortcutInbox,
                                shortcutSearch = !isLocked && shouldShortcutSearch,
                                shortcutCalendar = !isLocked && shortcutCalendar.value,
                                openCalendarEventId = if (isLocked) null else openCalendarEventId.value,
                                onCalendarEventHandled = { openCalendarEventId.value = null },
                                shortcutTasks = !isLocked && shortcutTasks.value,
                                openTaskId = if (isLocked) null else openTaskId.value,
                                onTaskHandled = { openTaskId.value = null },
                                shortcutNotes = !isLocked && shortcutNotes.value,
                                onShortcutHandled = {
                                    shortcutCompose.value = false
                                    shortcutInbox.value = false
                                    shortcutSearch.value = false
                                    shortcutCalendar.value = false
                                    shortcutTasks.value = false
                                    shortcutNotes.value = false
                                },
                                openUpdates = shouldOpenUpdates,
                                onUpdatesHandled = { openUpdates.value = false },
                                onOnboardingComplete = {
                                    checkPermissionsForDialogs()
                                }
                            )
                        }
                        com.dedovmosol.iwomail.ui.components.DeletionProgressBar(
                            controller = deletionController,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                        com.dedovmosol.iwomail.ui.components.SendProgressBar(
                            controller = sendController,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                        when (lockGate) {
                            com.dedovmosol.iwomail.data.security.AppLockManager.Gate.WAITING -> {
                                androidx.activity.compose.BackHandler { }
                                Surface(modifier = Modifier.fillMaxSize()) { }
                            }
                            com.dedovmosol.iwomail.data.security.AppLockManager.Gate.LOCKED ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    com.dedovmosol.iwomail.ui.screens.LockScreen(
                                        biometricAvailable = biometricAvailable,
                                        biometricEnabled = appLockBiometricEnabled,
                                        onBiometricRequest = {
                                            showBiometricPrompt(appLockBiometricEnabled, biometricAvailable)
                                        }
                                    )
                                }
                            com.dedovmosol.iwomail.data.security.AppLockManager.Gate.RECOVERY ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    com.dedovmosol.iwomail.ui.screens.LockRecoveryScreen()
                                }
                            com.dedovmosol.iwomail.data.security.AppLockManager.Gate.OPEN -> Unit
                        }
                    }
                    
                    val showBattery by showBatteryDialog
                    if (showBattery) {
                        AppPermissionDialog(
                            prompt = AppPermissionPrompt.BackgroundWork,
                            title = Strings.backgroundWorkTitle,
                            text = Strings.backgroundWorkText,
                            dismissText = Strings.later,
                            confirmText = Strings.continueAction,
                            onDismiss = { showBatteryDialog.value = false },
                            onConfirm = {
                                showBatteryDialog.value = false
                                awaitingBatteryResult = true
                                openBatterySettings()
                            }
                        )
                    }

                    val showAlarm by showAlarmDialog
                    if (showAlarm) {
                        AppPermissionDialog(
                            prompt = AppPermissionPrompt.ExactAlarms,
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
            
            // openInboxUnreadIntentId и openEmailId не сбрасываем вручную —
            // AppNavigation обрабатывает каждый intent по инкрементному id и запоминает последний.
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        // Вернулись из системного запроса «Фоновая работа» — следующее разрешение (точные будильники), один раз
        if (awaitingBatteryResult) {
            awaitingBatteryResult = false
            promptPermissionsOnce()
        }
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
            ?: intent.data?.let { uri ->
                if (uri.scheme == "iwomail" && uri.host == "email") uri.lastPathSegment else null
            }
        if (emailId != null) {
            openEmailId.value = emailId
            openEmailIntentId.longValue++
            intent.removeExtra(EXTRA_OPEN_EMAIL_ID)
            intent.data = null
            return
        }
        
        if (intent.getBooleanExtra(EXTRA_OPEN_INBOX_UNREAD, false)) {
            if (intent.data?.let { it.scheme == "iwomail" && it.host == "private-inbox" } == true) {
                com.dedovmosol.iwomail.data.security.AppLockManager.lock()
                intent.data = null
            }
            // Инкремент intent-id — повторные тапы (виджет/уведомление) навигируют каждый раз.
            openInboxUnreadIntentId.longValue++
            intent.removeExtra(EXTRA_OPEN_INBOX_UNREAD)
            return
        }
        
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) {
            openUpdates.value = true
            intent.removeExtra(EXTRA_OPEN_UPDATES)
            return
        }

        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR, false)) {
            shortcutCalendar.value = true
            intent.getStringExtra(EXTRA_CALENDAR_EVENT_ID)?.let { openCalendarEventId.value = it }
            intent.removeExtra(EXTRA_OPEN_CALENDAR)
            intent.removeExtra(EXTRA_CALENDAR_EVENT_ID)
            return
        }

        if (intent.getBooleanExtra(EXTRA_OPEN_TASKS, false)) {
            shortcutTasks.value = true
            intent.getStringExtra(EXTRA_TASK_ID)?.let { openTaskId.value = it }
            intent.removeExtra(EXTRA_OPEN_TASKS)
            intent.removeExtra(EXTRA_TASK_ID)
            return
        }
        
        // Widget actions: extras + data URI fallback (iwomail://widget/{action})
        val widgetAction = intent.getBooleanExtra("compose", false).let { if (it) "compose" else null }
            ?: intent.getBooleanExtra("inbox", false).let { if (it) "inbox" else null }
            ?: intent.getBooleanExtra("search", false).let { if (it) "search" else null }
            ?: intent.getBooleanExtra("calendar", false).let { if (it) "calendar" else null }
            ?: intent.getBooleanExtra("tasks", false).let { if (it) "tasks" else null }
            ?: intent.getBooleanExtra("notes", false).let { if (it) "notes" else null }
            ?: intent.data?.let { uri ->
                if (uri.scheme == "iwomail" && uri.host == "widget") uri.lastPathSegment else null
            }
        
        if (widgetAction != null) {
            when (widgetAction) {
                "compose" -> shortcutCompose.value = true
                "inbox" -> shortcutInbox.value = true
                "search" -> shortcutSearch.value = true
                "calendar" -> shortcutCalendar.value = true
                "tasks" -> shortcutTasks.value = true
                "notes" -> shortcutNotes.value = true
            }
            intent.removeExtra(widgetAction)
            intent.data = null
            return
        }
        
        // App Shortcuts
        com.dedovmosol.iwomail.util.AppShortcuts.reportUsed(this, intent.action)
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
                        if (uri.scheme.equals("mailto", ignoreCase = true)) {
                            parseMailtoUri(uri, intent)
                            composeIntentId.longValue++
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
                    composeCc.value = null
                    composeBcc.value = null
                    composeSubject.value = subj
                    composeBody.value = bodyText
                    composeAttachments.value = if (streamUri != null) listOf(streamUri) else emptyList()
                    composeIntentId.longValue++
                    
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
                    composeCc.value = null
                    composeBcc.value = null
                    composeSubject.value = subj
                    composeBody.value = bodyText
                    composeAttachments.value = streamUris ?: emptyList()
                    composeIntentId.longValue++
                    
                    // Очищаем intent
                    intent.action = null
                } catch (_: Exception) { }
            }
        }
    }
    
    private fun requestPinWidget() {
        // minSdk 26 (O): pin-widget API доступен всегда.
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this)
        val widgetProvider = android.content.ComponentName(this, com.dedovmosol.iwomail.widget.MailWidgetReceiver::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(widgetProvider, null, null)
        }
    }
    
    /**
     * Системный диалог биометрической разблокировки (цель релиза «дактилоскопия»).
     *
     * Вход через чистый гейт [com.dedovmosol.iwomail.data.security.AppLockManager.canRequestBiometricUnlock]
     * (юнит-тестируемый): сессия заблокирована И флаг включён И устройство поддерживает
     * биометрию. Флаги приходят из актуального Compose-состояния вызывающего лок-экрана,
     * поэтому промпт невозможен после выключения отпечатка в настройках (гонка закрыта).
     *
     * Краш-защита: [androidx.biometric.BiometricPrompt.authenticate] бросает
     * IllegalStateException на finishing/destroyed Activity — проверяем состояние
     * и дополнительно оборачиваем вызов (промпт — опциональное удобство, его потеря
     * не должна ронять приложение; пароль всегда остаётся запасным путём).
     *
     * Требования стабильного androidx.biometric: конструктор из FragmentActivity
     * (MainActivity наследует его), аутентификаторы задаются в PromptInfo.
     *
     * Локализация строк промпта: язык берём из настроек синк-чтением
     * (несоставной контекст — Compose LocalLanguage недоступен).
     */
    private fun showBiometricPrompt(biometricEnabled: Boolean, biometricAvailable: Boolean) {
        if (!com.dedovmosol.iwomail.data.security.AppLockManager.canRequestBiometricUnlock(
                com.dedovmosol.iwomail.data.security.AppLockManager.isLocked(),
                biometricEnabled,
                biometricAvailable
            )
        ) return
        if (isFinishing || isDestroyed) return

        val settingsRepo = SettingsRepository.getInstance(applicationContext)
        val isRussian = com.dedovmosol.iwomail.ui.isRussianLanguage(settingsRepo.getLanguageSync())

        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(com.dedovmosol.iwomail.ui.NotificationStrings.getAppLockBiometricTitle(isRussian))
            .setSubtitle(com.dedovmosol.iwomail.ui.NotificationStrings.getAppLockBiometricSubtitle(isRussian))
            .setNegativeButtonText(com.dedovmosol.iwomail.ui.NotificationStrings.getAppLockBiometricNegative(isRussian))
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        try {
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                androidx.core.content.ContextCompat.getMainExecutor(this),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        // Успех биометрии = разблокировка сессии (гейт реактивно уберёт лок-экран) и сброс паузы
                        com.dedovmosol.iwomail.data.security.AppLockManager.resetAttempts()
                        com.dedovmosol.iwomail.data.security.AppLockManager.unlock()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Критичные ошибки (датчик недоступен, отмена): НЕ разблокируем.
                        // Ошибка пользователя (неверный палец) обрабатывается системным
                        // диалогом самостоятельно — здесь только лог для диагностики.
                        android.util.Log.w(TAG, "Biometric auth error: $errorCode $errString")
                    }
                }
            )
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // IllegalStateException (Activity уходит) и пр.: пароль остаётся доступным
            // путём разблокировки — не крашим приложение из-за опционального пути.
            android.util.Log.w(TAG, "Biometric prompt failed to start", e)
        }
    }
    
    /**
     * mailto: от другого приложения (RFC 6068, [MailtoLink]): адреса, копия, скрытая копия, тема и текст. Раньше
     * тема и текст терялись: getQueryParameter у непрозрачного URI mailto: бросает исключение. Тему, текст и адреса
     * приложения часто кладут не в ссылку, а в extras ACTION_SENDTO — они берутся, если в ссылке их нет.
     */
    private fun parseMailtoUri(uri: android.net.Uri, intent: Intent) {
        val link = MailtoLink.parse(uri.toString()) ?: MailtoLink(emptyList())
        fun extraList(name: String): List<String> =
            intent.getStringArrayExtra(name)?.toList() ?: listOfNotNull(intent.getStringExtra(name))
        fun recipients(fromLink: List<String>, extra: String): String? =
            fromLink.ifEmpty { extraList(extra) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .joinToString(", ").ifEmpty { null }
        composeEmail.value = recipients(link.to, Intent.EXTRA_EMAIL)
        composeCc.value = recipients(link.cc, Intent.EXTRA_CC)
        composeBcc.value = recipients(link.bcc, Intent.EXTRA_BCC)
        composeSubject.value = link.subject ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        composeBody.value = link.body ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        composeAttachments.value = emptyList() // mailto: не имеет вложений
    }
    
    /** После онбординга и при обычном запуске. */
    private fun checkPermissionsForDialogs() = promptPermissionsOnce()

    /** После добавления первого аккаунта (SetupScreen). */
    fun checkPermissionsAfterSetup() = promptPermissionsOnce()

    /**
     * Приложение само спрашивает о фоновой работе и точных будильниках — по одному разу за установку (отметка в
     * настройках, [SettingsRepository.claimBackgroundWorkPrompt]). Раньше отметка жила только в Activity: каждый новый
     * запуск снова показывал диалог, в том числе поверх письма. Дальше — баннер на главной ([showBackgroundWorkDialog]).
     * Точные будильники — только когда работа в фоне уже разрешена (по одному вопросу за раз).
     */
    private fun promptPermissionsOnce() {
        lifecycleScope.launch {
            val settingsRepo = SettingsRepository.getInstance(applicationContext)
            if (!settingsRepo.onboardingShown.first()) return@launch
            if (showBatteryDialog.value || showAlarmDialog.value) return@launch
            // Не поверх онбординга и не вместе с нашим листом: сначала системные уведомления (13+), потом фон, потом будильники
            if (requestNotificationPermission()) return@launch
            if (!com.dedovmosol.iwomail.sync.BackgroundWork.isAllowed(this@MainActivity)) {
                if (settingsRepo.claimBackgroundWorkPrompt()) showBatteryDialog.value = true
                return@launch
            }
            if (!com.dedovmosol.iwomail.sync.BackgroundWork.exactAlarmsAllowed(this@MainActivity) &&
                settingsRepo.claimExactAlarmsPrompt()
            ) {
                showAlarmDialog.value = true
            }
        }
    }

    /** Диалог «Фоновая работа» по запросу пользователя (баннер на главной) — сколько угодно раз. */
    private fun showBackgroundWorkDialog() {
        if (!showAlarmDialog.value) showBatteryDialog.value = true
    }
    
    /** @return true — системный лист открыт, следующие запросы подождут callback. */
    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return false
        if (notificationAskedThisProcess) return false
        notificationAskedThisProcess = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
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
        // minSdk 26 (>= M): запрос игнорирования оптимизации батареи доступен всегда.
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
            } else {
                // minSdk 26 (>= M): API дисплея до R доступно всегда.
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
