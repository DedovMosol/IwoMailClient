package com.dedovmosol.iwomail.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.MainScreen
import com.dedovmosol.iwomail.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Глобальное хранилище для вложений из Share intent
 * Используется потому что URI нельзя передать через навигацию
 */
object ShareIntentData {
    var attachments: List<android.net.Uri> = emptyList()
    
    fun clear() {
        attachments = emptyList()
    }
}

// Анимации переходов
private fun enterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(300))
}

private fun exitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(150))
}

private fun popEnterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(300))
}

private fun popExitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(150))
}

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding?isFirstLaunch={isFirstLaunch}") {
        fun createRoute(isFirstLaunch: Boolean = true): String {
            return "onboarding?isFirstLaunch=$isFirstLaunch"
        }
    }
    object Main : Screen("main")
    object Setup : Screen("setup?editAccountId={editAccountId}&verificationError={verificationError}&savedData={savedData}") {
        fun createRoute(editAccountId: Long? = null, verificationError: String? = null, savedData: String? = null): String {
            val params = mutableListOf<String>()
            if (editAccountId != null) params.add("editAccountId=$editAccountId")
            if (verificationError != null) {
                val encoded = android.util.Base64.encodeToString(
                    verificationError.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("verificationError=$encoded")
            }
            if (savedData != null) {
                val encoded = android.util.Base64.encodeToString(
                    savedData.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("savedData=$encoded")
            }
            return if (params.isNotEmpty()) "setup?${params.joinToString("&")}" else "setup"
        }
        fun decodeError(encoded: String?): String? {
            if (encoded == null) return null
            return try {
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) { null }
        }
        fun decodeSavedData(encoded: String?): String? {
            if (encoded == null) return null
            return try {
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) { null }
        }
    }
    object AddAnotherAccount : Screen("add_another_account")
    object Verification : Screen("verification/{params}") {
        fun createRoute(
            email: String, displayName: String, serverUrl: String, username: String,
            password: String, domain: String, acceptAllCerts: Boolean, color: Int,
            incomingPort: Int, outgoingServer: String, outgoingPort: Int, useSSL: Boolean, syncMode: String,
            certificatePath: String? = null,
            clientCertificatePath: String? = null,
            clientCertificatePassword: String? = null,
            isFirstAccount: Boolean = false
        ): String {
            val certPathEncoded = certificatePath ?: ""
            val clientCertPathEncoded = clientCertificatePath ?: ""
            val clientCertPasswordEncoded = clientCertificatePassword ?: ""
            // URL-encode каждый параметр, чтобы символы | в пароле/полях не ломали split
            val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
            val data = listOf(
                enc(email), enc(displayName), enc(serverUrl), enc(username),
                enc(password), enc(domain), enc(acceptAllCerts.toString()), enc(color.toString()),
                enc(incomingPort.toString()), enc(outgoingServer), enc(outgoingPort.toString()),
                enc(useSSL.toString()), enc(syncMode), enc(certPathEncoded),
                enc(isFirstAccount.toString()), enc(clientCertPathEncoded), enc(clientCertPasswordEncoded)
            ).joinToString("|")
            val encoded = android.util.Base64.encodeToString(
                data.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "verification/$encoded"
        }
    }
    object EmailList : Screen("emails/{folderId}?filter={filter}&dateFilter={dateFilter}") {
        fun createRoute(folderId: String, filter: String? = null, dateFilter: String? = null): String {
            val encodedId = android.util.Base64.encodeToString(
                folderId.toByteArray(Charsets.UTF_8), 
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            val params = mutableListOf<String>()
            if (filter != null) params.add("filter=$filter")
            if (dateFilter != null) params.add("dateFilter=$dateFilter")
            return if (params.isNotEmpty()) "emails/$encodedId?${params.joinToString("&")}"
            else "emails/$encodedId"
        }
        fun decodeId(encoded: String): String {
            return try {
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) {
                URLDecoder.decode(encoded, "UTF-8") // Fallback для старых ссылок
            }
        }
    }
    object EmailDetail : Screen("email/{emailId}") {
        fun createRoute(emailId: String): String {
            val encodedId = android.util.Base64.encodeToString(
                emailId.toByteArray(Charsets.UTF_8), 
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "email/$encodedId"
        }
        fun decodeId(encoded: String): String {
            return try {
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) {
                URLDecoder.decode(encoded, "UTF-8") // Fallback для старых ссылок
            }
        }
    }
    object Compose : Screen("compose?replyTo={replyTo}&forwardId={forwardId}&toEmail={toEmail}&draftId={draftId}&subject={subject}&body={body}") {
        fun createRoute(replyTo: String? = null, forwardId: String? = null, toEmail: String? = null, draftId: String? = null, subject: String? = null, body: String? = null): String {
            val params = mutableListOf<String>()
            if (replyTo != null) {
                val encoded = android.util.Base64.encodeToString(
                    replyTo.toByteArray(Charsets.UTF_8), 
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("replyTo=$encoded")
            }
            if (forwardId != null) {
                val encoded = android.util.Base64.encodeToString(
                    forwardId.toByteArray(Charsets.UTF_8), 
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("forwardId=$encoded")
            }
            if (toEmail != null) {
                val encoded = android.util.Base64.encodeToString(
                    toEmail.toByteArray(Charsets.UTF_8), 
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("toEmail=$encoded")
            }
            if (draftId != null) {
                val encoded = android.util.Base64.encodeToString(
                    draftId.toByteArray(Charsets.UTF_8), 
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("draftId=$encoded")
            }
            if (subject != null) {
                val encoded = android.util.Base64.encodeToString(
                    subject.toByteArray(Charsets.UTF_8), 
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("subject=$encoded")
            }
            if (body != null) {
                val encoded = android.util.Base64.encodeToString(
                    body.toByteArray(Charsets.UTF_8), 
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                params.add("body=$encoded")
            }
            return if (params.isNotEmpty()) "compose?${params.joinToString("&")}" else "compose"
        }
        fun decodeId(encoded: String?): String? {
            if (encoded == null) return null
            return try {
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) {
                try { URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { encoded }
            }
        }
    }
    object Settings : Screen("settings")
    object AccountSettings : Screen("accountSettings/{accountId}") {
        fun createRoute(accountId: Long): String = "accountSettings/$accountId"
    }
    object Personalization : Screen("personalization")
    object SyncCleanup : Screen("syncCleanup/{accountId}") {
        fun createRoute(accountId: Long): String = "syncCleanup/$accountId"
    }
    object Updates : Screen("updates")
    object About : Screen("about")
    object Search : Screen("search")
    object Contacts : Screen("contacts")
    object Notes : Screen("notes")
    object Calendar : Screen("calendar?filter={filter}") {
        fun createRoute(filter: String? = null): String {
            return if (filter != null) "calendar?filter=$filter" else "calendar"
        }
    }
    object Tasks : Screen("tasks?filter={filter}") {
        fun createRoute(filter: String? = null): String {
            return if (filter != null) "tasks?filter=$filter" else "tasks"
        }
    }
    object UserFolders : Screen("user_folders")
}

@Composable
fun AppNavigation(
    openInboxUnread: Boolean = false, 
    openEmailId: String? = null,
    switchToAccountId: Long? = null,
    composeToEmail: String? = null,
    composeSubject: String? = null,
    composeBody: String? = null,
    composeAttachments: List<android.net.Uri> = emptyList(),
    onComposeHandled: () -> Unit = {},
    onAccountSwitched: () -> Unit = {},
    // App Shortcuts
    shortcutCompose: Boolean = false,
    shortcutInbox: Boolean = false,
    shortcutSearch: Boolean = false,
    shortcutCalendar: Boolean = false,
    shortcutTasks: Boolean = false,
    onShortcutHandled: () -> Unit = {},
    openUpdates: Boolean = false,
    onUpdatesHandled: () -> Unit = {},
    onOnboardingComplete: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    
    // Определяем стартовый экран
    var startDestination by remember { mutableStateOf<String?>(null) }
    var hasCheckedAccounts by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        try {
            // Таймаут на проверку аккаунтов чтобы избежать зависания
            val hasAccounts = withTimeoutOrNull(3000L) {
                withContext(Dispatchers.IO) {
                    accountRepo.getAccountCount() > 0
                }
            } ?: false
            
            val onboardingShown = settingsRepo.getOnboardingShownSync()
            
            startDestination = when {
                // Есть аккаунты — сразу на главный экран
                hasAccounts -> Screen.Main.route
                // Нет аккаунтов и onboarding не показан — показываем onboarding
                !onboardingShown -> Screen.Onboarding.createRoute(isFirstLaunch = true)
                // Нет аккаунтов, но onboarding уже показан — на экран добавления аккаунта
                else -> Screen.Setup.route
            }
            hasCheckedAccounts = true
        } catch (e: Exception) {
            startDestination = Screen.Setup.route
            hasCheckedAccounts = true
        }
    }
    
    // Обработка переключения аккаунта при клике на уведомление
    // Храним ID последнего обработанного аккаунта, чтобы повторные уведомления обрабатывались
    var lastSwitchedAccountId by remember { mutableStateOf<Long?>(null) }
    var accountSwitchCompleted by remember { mutableStateOf(switchToAccountId == null) }
    
    LaunchedEffect(switchToAccountId, hasCheckedAccounts) {
        if (switchToAccountId != null && switchToAccountId > 0 && switchToAccountId != lastSwitchedAccountId && hasCheckedAccounts) {
            lastSwitchedAccountId = switchToAccountId
            accountSwitchCompleted = false
            try {
                withContext(Dispatchers.IO) {
                    accountRepo.setActiveAccount(switchToAccountId)
                }
                // Задержка чтобы Flow успел обновиться
                kotlinx.coroutines.delay(100)
                accountSwitchCompleted = true
                onAccountSwitched()
            } catch (_: Exception) {
                accountSwitchCompleted = true
            }
        } else if (switchToAccountId == null && hasCheckedAccounts) {
            // Нет переключения аккаунта — сразу готовы
            accountSwitchCompleted = true
        } else if (switchToAccountId == lastSwitchedAccountId) {
            // Тот же аккаунт — уже переключён
            accountSwitchCompleted = true
        }
    }
    
    // Обработка mailto: и SEND intent'ов — открываем экран создания письма
    var composeHandled by remember { mutableStateOf(false) }
    
    LaunchedEffect(composeToEmail, composeSubject, composeBody, composeAttachments, hasCheckedAccounts, startDestination) {
        if ((composeToEmail != null || composeSubject != null || composeBody != null || composeAttachments.isNotEmpty()) && 
            !composeHandled && hasCheckedAccounts && startDestination == Screen.Main.route) {
            composeHandled = true
            
            // Сохраняем вложения в глобальное хранилище
            if (composeAttachments.isNotEmpty()) {
                ShareIntentData.attachments = composeAttachments
            }
            
            // Увеличенная задержка чтобы NavHost успел инициализироваться
            kotlinx.coroutines.delay(500)
            try {
                navController.navigate(Screen.Compose.createRoute(
                    toEmail = composeToEmail,
                    subject = composeSubject,
                    body = composeBody
                )) {
                    launchSingleTop = true
                }
                onComposeHandled()
            } catch (_: Exception) { }
        }
    }
    
    // Обработка перехода на конкретное письмо (из уведомления)
    // Храним ID последнего обработанного письма вместо boolean,
    // чтобы повторное уведомление (с другим emailId) тоже обрабатывалось
    var lastHandledEmailId by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(openEmailId, hasCheckedAccounts, startDestination, accountSwitchCompleted) {
        // Обрабатываем только когда всё готово (включая переключение аккаунта)
        // и этот конкретный emailId ещё не обработан
        if (openEmailId != null && openEmailId != lastHandledEmailId && hasCheckedAccounts && startDestination == Screen.Main.route && accountSwitchCompleted) {
            lastHandledEmailId = openEmailId
            // Задержка чтобы NavHost успел инициализироваться
            kotlinx.coroutines.delay(500)
            try {
                // Получаем папку Входящие для навигации
                val account = withContext(Dispatchers.IO) { accountRepo.getActiveAccountSync() }
                if (account != null) {
                    val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                    
                    // Получаем папку Входящие
                    val inboxFolder = withContext(Dispatchers.IO) {
                        database.folderDao().getFolderByType(account.id, 2) // type 2 = Inbox
                    }
                    
                    // Проверяем что письмо существует
                    val emailExists = withContext(Dispatchers.IO) {
                        database.emailDao().getEmail(openEmailId) != null
                    }
                    
                    if (!emailExists) {
                        // Письмо не найдено — возможно ещё не синхронизировано
                        // Открываем Входящие с фильтром Непрочитанные
                        if (inboxFolder != null) {
                            navController.navigate(Screen.EmailList.createRoute(inboxFolder.id, "UNREAD")) {
                                launchSingleTop = true
                            }
                        }
                        return@LaunchedEffect
                    }
                    
                    if (inboxFolder != null) {
                        // Сначала открываем Входящие (чтобы при нажатии "назад" вернуться туда)
                        navController.navigate(Screen.EmailList.createRoute(inboxFolder.id)) {
                            launchSingleTop = true
                        }
                        // Ждём пока навигация завершится перед вторым переходом
                        kotlinx.coroutines.delay(300)
                    }
                    
                    // Открываем письмо (даже если inbox не найден — пользователь должен увидеть письмо)
                    navController.navigate(Screen.EmailDetail.createRoute(openEmailId)) {
                        launchSingleTop = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppNavigation", "Failed to navigate to email from notification", e)
            }
        }
    }
    
    // Обработка перехода на Входящие с фильтром Непрочитанные
    LaunchedEffect(openInboxUnread, hasCheckedAccounts, accountSwitchCompleted) {
        if (openInboxUnread && hasCheckedAccounts && accountSwitchCompleted) {
            // Получаем папку Входящие
            val account = withContext(Dispatchers.IO) { accountRepo.getActiveAccountSync() }
            if (account != null) {
                val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                val inboxFolder = withContext(Dispatchers.IO) {
                    database.folderDao().getFolderByType(account.id, 2) // type 2 = Inbox
                }
                if (inboxFolder != null) {
                    // Небольшая задержка чтобы NavHost успел инициализироваться
                    kotlinx.coroutines.delay(100)
                    navController.navigate(Screen.EmailList.createRoute(inboxFolder.id, "UNREAD")) {
                        // Очищаем back stack до Main
                        popUpTo(Screen.Main.route) { inclusive = false }
                    }
                }
            }
        }
    }
    
    // Обработка App Shortcuts
    var shortcutHandled by remember { mutableStateOf(false) }
    
    LaunchedEffect(shortcutCompose, shortcutInbox, shortcutSearch, shortcutCalendar, shortcutTasks, hasCheckedAccounts, startDestination) {
        if (!shortcutHandled && hasCheckedAccounts && startDestination == Screen.Main.route) {
            when {
                shortcutCompose -> {
                    shortcutHandled = true
                    kotlinx.coroutines.delay(300)
                    try {
                        navController.navigate(Screen.Compose.createRoute()) {
                            launchSingleTop = true
                        }
                        onShortcutHandled()
                    } catch (_: Exception) { }
                }
                shortcutInbox -> {
                    shortcutHandled = true
                    kotlinx.coroutines.delay(300)
                    try {
                        val account = withContext(Dispatchers.IO) { accountRepo.getActiveAccountSync() }
                        if (account != null) {
                            val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            val inboxFolder = withContext(Dispatchers.IO) {
                                database.folderDao().getFolderByType(account.id, 2)
                            }
                            if (inboxFolder != null) {
                                navController.navigate(Screen.EmailList.createRoute(inboxFolder.id)) {
                                    launchSingleTop = true
                                }
                            }
                        }
                        onShortcutHandled()
                    } catch (_: Exception) { }
                }
                shortcutSearch -> {
                    shortcutHandled = true
                    kotlinx.coroutines.delay(300)
                    try {
                        navController.navigate(Screen.Search.route) {
                            launchSingleTop = true
                        }
                        onShortcutHandled()
                    } catch (_: Exception) { }
                }
                shortcutCalendar -> {
                    shortcutHandled = true
                    kotlinx.coroutines.delay(300)
                    try {
                        navController.navigate(Screen.Calendar.createRoute()) {
                            launchSingleTop = true
                        }
                        onShortcutHandled()
                    } catch (_: Exception) { }
                }
                shortcutTasks -> {
                    shortcutHandled = true
                    kotlinx.coroutines.delay(300)
                    try {
                        navController.navigate(Screen.Tasks.createRoute()) {
                            launchSingleTop = true
                        }
                        onShortcutHandled()
                    } catch (_: Exception) { }
                }
            }
        }
    }
    
    // Обработка перехода на экран обновлений (из push-уведомления)
    LaunchedEffect(openUpdates, hasCheckedAccounts, startDestination) {
        if (openUpdates && hasCheckedAccounts && startDestination == Screen.Main.route) {
            kotlinx.coroutines.delay(300)
            try {
                navController.navigate(Screen.Updates.route) {
                    launchSingleTop = true
                }
            } catch (_: Exception) { }
            onUpdatesHandled()
        }
    }
    
    // Показываем индикатор загрузки пока определяем стартовый экран
    if (startDestination == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp
                )
                Text(
                    text = "Загрузка...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    
    // startDestination гарантированно не null здесь (проверка выше с return)
    val destination = startDestination ?: return
    
    NavHost(
        navController = navController,
        startDestination = destination,
        enterTransition = { enterTransition() },
        exitTransition = { exitTransition() },
        popEnterTransition = { popEnterTransition() },
        popExitTransition = { popExitTransition() }
    ) {
        composable(
            route = Screen.Main.route,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { exitTransition() },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) }
        ) {
            MainScreen(
                onNavigateToSetup = {
                    navController.navigate(Screen.Setup.createRoute())
                },
                onNavigateToEmailList = { folderId ->
                    navController.navigate(Screen.EmailList.createRoute(folderId))
                },
                onNavigateToCompose = {
                    navController.navigate(Screen.Compose.createRoute())
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToOnboarding = {
                    navController.navigate(Screen.Onboarding.createRoute(isFirstLaunch = false))
                },
                onNavigateToSearch = {
                    navController.navigate(Screen.Search.route)
                },
                onNavigateToEmailDetail = { emailId ->
                    navController.navigate(Screen.EmailDetail.createRoute(emailId))
                },
                onNavigateToContacts = {
                    navController.navigate(Screen.Contacts.route)
                },
                onNavigateToNotes = {
                    navController.navigate(Screen.Notes.route)
                },
                onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.createRoute())
                },
                onNavigateToTasks = {
                    navController.navigate(Screen.Tasks.createRoute())
                },
                onNavigateToUserFolders = {
                    navController.navigate(Screen.UserFolders.route)
                },
                // Навигация из карточки "Сегодня" с фильтрами
                onNavigateToEmailListWithDateFilter = { folderId, dateFilter ->
                    navController.navigate(Screen.EmailList.createRoute(folderId, dateFilter = dateFilter.ifEmpty { null }))
                },
                onNavigateToCalendarToday = {
                    navController.navigate(Screen.Calendar.createRoute(filter = "TODAY"))
                },
                onNavigateToTasksToday = {
                    navController.navigate(Screen.Tasks.createRoute(filter = "TODAY"))
                }
            )
        }
        
        composable(
            route = Screen.Setup.route,
            arguments = listOf(
                navArgument("editAccountId") { 
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("verificationError") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("savedData") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val editAccountId = backStackEntry.arguments?.getString("editAccountId")?.toLongOrNull()
            val verificationError = Screen.Setup.decodeError(backStackEntry.arguments?.getString("verificationError"))
            val savedData = Screen.Setup.decodeSavedData(backStackEntry.arguments?.getString("savedData"))
            SetupScreen(
                editAccountId = editAccountId,
                initialError = verificationError,
                savedData = savedData,
                onSetupComplete = {
                    // Если редактируем существующий аккаунт — просто возвращаемся
                    if (editAccountId != null) {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Screen.Main.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    } else if (navController.previousBackStackEntry == null) {
                        // КРИТИЧНО: Проверяем разрешения СРАЗУ после первого аккаунта
                        // Диалог "Фоновая работа" должен показаться ДО перехода на AddAnotherAccount
                        (context as? MainActivity)?.checkPermissionsAfterSetup()
                        
                        // Первый аккаунт (пришли с Onboarding) — показываем экран "Добавить ещё?"
                        navController.navigate(Screen.AddAnotherAccount.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    } else {
                        // Добавление аккаунта из MainScreen — просто возвращаемся
                        navController.popBackStack()
                    }
                },
                onNavigateToVerification = { email, displayName, serverUrl, username, password, domain, acceptAllCerts, color, incomingPort, outgoingServer, outgoingPort, useSSL, syncMode, certificatePath, clientCertificatePath, clientCertificatePassword ->
                    // Проверяем, первый ли это аккаунт (нет previousBackStackEntry = пришли с Onboarding)
                    val isFirstAccount = navController.previousBackStackEntry == null
                    navController.navigate(
                        Screen.Verification.createRoute(
                            email, displayName, serverUrl, username, password, domain,
                            acceptAllCerts, color, incomingPort, outgoingServer, outgoingPort, useSSL, syncMode, certificatePath,
                            clientCertificatePath, clientCertificatePassword,
                            isFirstAccount = isFirstAccount
                        )
                    )
                },
                onBackClick = if (navController.previousBackStackEntry != null) {
                    {
                        // Если пришли с AddAnotherAccount — переходим сразу на MainScreen
                        val previousRoute = navController.previousBackStackEntry?.destination?.route
                        if (previousRoute == Screen.AddAnotherAccount.route) {
                            navController.navigate(Screen.Main.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }
                } else null
            )
        }
        
        composable(
            route = Screen.Verification.route,
            arguments = listOf(navArgument("params") { type = NavType.StringType })
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("params") ?: ""
            val decoded = try {
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) { "" }
            val rawParts = decoded.split("|")
            // URL-decode каждый параметр (обратная операция к createRoute)
            val dec = { s: String -> try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s } }
            val parts = rawParts.map { dec(it) }
            if (parts.size >= 13) {
                val certificatePath = if (parts.size >= 14 && parts[13].isNotBlank()) parts[13] else null
                val isFirstAccount = if (parts.size >= 15) parts[14].toBoolean() else false
                val clientCertificatePath = if (parts.size >= 16 && parts[15].isNotBlank()) parts[15] else null
                val clientCertificatePassword = if (parts.size >= 17 && parts[16].isNotBlank()) parts[16] else null
                VerificationScreen(
                    email = parts[0],
                    displayName = parts[1],
                    serverUrl = parts[2],
                    username = parts[3],
                    password = parts[4],
                    domain = parts[5],
                    acceptAllCerts = parts[6].toBoolean(),
                    color = parts[7].toIntOrNull() ?: 0xFF1976D2.toInt(),
                    incomingPort = parts[8].toIntOrNull() ?: 443,
                    outgoingServer = parts[9],
                    outgoingPort = parts[10].toIntOrNull() ?: 587,
                    useSSL = parts[11].toBoolean(),
                    syncMode = try { com.dedovmosol.iwomail.data.database.SyncMode.valueOf(parts[12]) } catch (e: Exception) { com.dedovmosol.iwomail.data.database.SyncMode.SCHEDULED },
                    certificatePath = certificatePath,
                    clientCertificatePath = clientCertificatePath,
                    clientCertificatePassword = clientCertificatePassword,
                    onSuccess = {
                        if (isFirstAccount) {
                            // Первый аккаунт — показываем экран "Добавить ещё аккаунт?"
                            navController.navigate(Screen.AddAnotherAccount.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        } else {
                            // Не первый — сразу на главный экран
                            navController.navigate(Screen.Main.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    onError = { error, savedData ->
                        navController.navigate(Screen.Setup.createRoute(verificationError = error, savedData = savedData)) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    }
                )
            }
        }
        
        // Экран "Добавить ещё одну учётную запись?"
        composable(route = Screen.AddAnotherAccount.route) {
            AddAnotherAccountScreen(
                onAddAccount = {
                    // Не удаляем AddAnotherAccount из стека, чтобы кнопка "Назад" работала
                    navController.navigate(Screen.Setup.createRoute())
                },
                onSkip = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        
        composable(
            route = Screen.EmailList.route,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType },
                navArgument("filter") { 
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("dateFilter") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val encodedFolderId = backStackEntry.arguments?.getString("folderId") ?: ""
            val folderId = Screen.EmailList.decodeId(encodedFolderId)
            val filterStr = backStackEntry.arguments?.getString("filter")
            val initialFilter = when (filterStr) {
                "UNREAD" -> com.dedovmosol.iwomail.ui.screens.MailFilter.UNREAD
                "STARRED" -> com.dedovmosol.iwomail.ui.screens.MailFilter.STARRED
                "WITH_ATTACHMENTS" -> com.dedovmosol.iwomail.ui.screens.MailFilter.WITH_ATTACHMENTS
                "IMPORTANT" -> com.dedovmosol.iwomail.ui.screens.MailFilter.IMPORTANT
                else -> com.dedovmosol.iwomail.ui.screens.MailFilter.ALL
            }
            val dateFilterStr = backStackEntry.arguments?.getString("dateFilter")
            val initialDateFilter = when (dateFilterStr) {
                "TODAY" -> com.dedovmosol.iwomail.ui.screens.EmailDateFilter.TODAY
                "WEEK" -> com.dedovmosol.iwomail.ui.screens.EmailDateFilter.WEEK
                "MONTH" -> com.dedovmosol.iwomail.ui.screens.EmailDateFilter.MONTH
                "YEAR" -> com.dedovmosol.iwomail.ui.screens.EmailDateFilter.YEAR
                else -> com.dedovmosol.iwomail.ui.screens.EmailDateFilter.ALL
            }
            EmailListScreen(
                folderId = folderId,
                onEmailClick = { emailId ->
                    navController.navigate(Screen.EmailDetail.createRoute(emailId))
                },
                onDraftClick = { draftId ->
                    navController.navigate(Screen.Compose.createRoute(draftId = draftId))
                },
                onBackClick = { navController.popBackStack() },
                onComposeClick = { navController.navigate(Screen.Compose.createRoute()) },
                onSearchClick = { navController.navigate(Screen.Search.route) },
                initialFilter = initialFilter,
                initialDateFilter = initialDateFilter
            )
        }
        
        composable(
            route = Screen.EmailDetail.route,
            arguments = listOf(navArgument("emailId") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedEmailId = backStackEntry.arguments?.getString("emailId") ?: ""
            val emailId = Screen.EmailDetail.decodeId(encodedEmailId)
            EmailDetailScreen(
                emailId = emailId,
                onBackClick = {
                    // Безопасная навигация назад: если back stack пуст (например,
                    // письмо открыто из уведомления), переходим на главный экран
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onReplyClick = { navController.navigate(Screen.Compose.createRoute(replyTo = emailId)) },
                onForwardClick = { navController.navigate(Screen.Compose.createRoute(forwardId = emailId)) },
                onComposeToEmail = { toEmail -> navController.navigate(Screen.Compose.createRoute(toEmail = toEmail)) }
            )
        }
        
        composable(
            route = Screen.Compose.route,
            enterTransition = { fadeIn(animationSpec = tween(250)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { fadeIn(animationSpec = tween(250)) },
            popExitTransition = { fadeOut(animationSpec = tween(200)) },
            arguments = listOf(
                navArgument("replyTo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("forwardId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("toEmail") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("draftId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("subject") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("body") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val replyTo = Screen.Compose.decodeId(backStackEntry.arguments?.getString("replyTo"))
            val forwardId = Screen.Compose.decodeId(backStackEntry.arguments?.getString("forwardId"))
            val toEmail = Screen.Compose.decodeId(backStackEntry.arguments?.getString("toEmail"))
            val draftId = Screen.Compose.decodeId(backStackEntry.arguments?.getString("draftId"))
            val subject = Screen.Compose.decodeId(backStackEntry.arguments?.getString("subject"))
            val body = Screen.Compose.decodeId(backStackEntry.arguments?.getString("body"))
            ComposeScreen(
                replyToEmailId = replyTo,
                forwardEmailId = forwardId,
                initialToEmail = toEmail,
                editDraftId = draftId,
                initialSubject = subject,
                initialBody = body,
                onBackClick = { navController.popBackStack() },
                onSent = { 
                    // После отправки переходим на главную
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Main.route) { inclusive = false }
                    }
                }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onAddAccount = {
                    navController.navigate(Screen.Setup.createRoute())
                },
                onNavigateToPersonalization = {
                    navController.navigate(Screen.Personalization.route)
                },
                onNavigateToAccountSettings = { accountId ->
                    navController.navigate(Screen.AccountSettings.createRoute(accountId))
                },
                onNoAccountsLeft = {
                    navController.navigate(Screen.Setup.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToAbout = {
                    navController.navigate(Screen.About.route)
                }
            )
        }
        
        composable(
            route = Screen.AccountSettings.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
            AccountSettingsScreen(
                accountId = accountId,
                onBackClick = { navController.popBackStack() },
                onEditCredentials = { id -> navController.navigate(Screen.Setup.createRoute(id)) },
                onNavigateToSyncCleanup = { id -> navController.navigate(Screen.SyncCleanup.createRoute(id)) }
            )
        }
        
        composable(Screen.Personalization.route) {
            PersonalizationScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        composable(
            route = Screen.SyncCleanup.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
            SyncCleanupScreen(
                accountId = accountId,
                onBackClick = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Updates.route) {
            UpdatesScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        composable(Screen.About.route) {
            AboutScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToUpdates = {
                    navController.navigate(Screen.Updates.route)
                },
                onNavigateToOnboarding = {
                    navController.navigate(Screen.Onboarding.createRoute(isFirstLaunch = false))
                }
            )
        }
        
        composable(Screen.Search.route) {
            SearchScreen(
                onBackClick = { navController.popBackStack() },
                onEmailClick = { emailId ->
                    navController.navigate(Screen.EmailDetail.createRoute(emailId))
                },
                onComposeClick = {
                    navController.navigate(Screen.Compose.route)
                }
            )
        }
        
        composable(Screen.Contacts.route) {
            ContactsScreen(
                onBackClick = { navController.popBackStack() },
                onComposeClick = { email ->
                    // Создаём новое письмо с заполненным получателем
                    navController.navigate(Screen.Compose.createRoute(toEmail = email))
                }
            )
        }
        
        composable(Screen.Notes.route) {
            NotesScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        composable(
            route = Screen.Calendar.route,
            arguments = listOf(
                navArgument("filter") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val filterStr = backStackEntry.arguments?.getString("filter")
            val initialDateFilter = when (filterStr) {
                "TODAY" -> com.dedovmosol.iwomail.ui.screens.CalendarDateFilter.TODAY
                "WEEK" -> com.dedovmosol.iwomail.ui.screens.CalendarDateFilter.WEEK
                "MONTH" -> com.dedovmosol.iwomail.ui.screens.CalendarDateFilter.MONTH
                else -> com.dedovmosol.iwomail.ui.screens.CalendarDateFilter.ALL
            }
            CalendarScreen(
                onBackClick = { navController.popBackStack() },
                onComposeClick = { email ->
                    navController.navigate(Screen.Compose.createRoute(toEmail = email))
                },
                initialDateFilter = initialDateFilter
            )
        }
        
        composable(
            route = Screen.Tasks.route,
            arguments = listOf(
                navArgument("filter") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val filterStr = backStackEntry.arguments?.getString("filter")
            val initialFilter = when (filterStr) {
                "TODAY" -> com.dedovmosol.iwomail.ui.screens.TaskFilter.TODAY
                "ACTIVE" -> com.dedovmosol.iwomail.ui.screens.TaskFilter.ACTIVE
                "COMPLETED" -> com.dedovmosol.iwomail.ui.screens.TaskFilter.COMPLETED
                "HIGH_PRIORITY" -> com.dedovmosol.iwomail.ui.screens.TaskFilter.HIGH_PRIORITY
                "OVERDUE" -> com.dedovmosol.iwomail.ui.screens.TaskFilter.OVERDUE
                "DELETED" -> com.dedovmosol.iwomail.ui.screens.TaskFilter.DELETED
                else -> com.dedovmosol.iwomail.ui.screens.TaskFilter.ALL
            }
            TasksScreen(
                onBackClick = { navController.popBackStack() },
                initialFilter = initialFilter
            )
        }
        
        composable(Screen.UserFolders.route) {
            UserFoldersScreen(
                onBackClick = { navController.popBackStack() },
                onFolderClick = { folderId ->
                    navController.navigate(Screen.EmailList.createRoute(folderId))
                },
                onComposeClick = { navController.navigate(Screen.Compose.createRoute()) }
            )
        }
        
        composable(
            route = Screen.Onboarding.route,
            arguments = listOf(
                navArgument("isFirstLaunch") {
                    type = NavType.BoolType
                    defaultValue = true
                }
            )
        ) { backStackEntry ->
            val isFirstLaunch = backStackEntry.arguments?.getBoolean("isFirstLaunch") ?: true
            OnboardingScreen(
                isFirstLaunch = isFirstLaunch,
                onComplete = {
                    if (isFirstLaunch) {
                        // После первого onboarding — на экран добавления аккаунта
                        onOnboardingComplete()
                        navController.navigate(Screen.Setup.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    } else {
                        // Ручной вызов из MainScreen — просто назад
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}

