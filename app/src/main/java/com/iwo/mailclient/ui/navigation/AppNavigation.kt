package com.iwo.mailclient.ui.navigation

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
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.ui.MainScreen
import com.iwo.mailclient.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder

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
    object Verification : Screen("verification/{params}") {
        fun createRoute(
            email: String, displayName: String, serverUrl: String, username: String,
            password: String, domain: String, acceptAllCerts: Boolean, color: Int,
            incomingPort: Int, outgoingServer: String, outgoingPort: Int, useSSL: Boolean, syncMode: String,
            certificatePath: String? = null
        ): String {
            val certPathEncoded = certificatePath ?: ""
            val data = "$email|$displayName|$serverUrl|$username|$password|$domain|$acceptAllCerts|$color|$incomingPort|$outgoingServer|$outgoingPort|$useSSL|$syncMode|$certPathEncoded"
            val encoded = android.util.Base64.encodeToString(
                data.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "verification/$encoded"
        }
    }
    object EmailList : Screen("emails/{folderId}?filter={filter}") {
        fun createRoute(folderId: String, filter: String? = null): String {
            val encodedId = android.util.Base64.encodeToString(
                folderId.toByteArray(Charsets.UTF_8), 
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return if (filter != null) "emails/$encodedId?filter=$filter"
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
    object Compose : Screen("compose?replyTo={replyTo}&forwardId={forwardId}&toEmail={toEmail}&draftId={draftId}") {
        fun createRoute(replyTo: String? = null, forwardId: String? = null, toEmail: String? = null, draftId: String? = null): String {
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
    object Search : Screen("search")
    object Contacts : Screen("contacts")
    object Notes : Screen("notes")
    object Calendar : Screen("calendar")
}

@Composable
fun AppNavigation(openInboxUnread: Boolean = false, openEmailId: String? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    
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
            
            startDestination = if (hasAccounts) {
                Screen.Main.route
            } else {
                Screen.Setup.route
            }
            hasCheckedAccounts = true
        } catch (e: Exception) {
            startDestination = Screen.Setup.route
            hasCheckedAccounts = true
        }
    }
    
    // Обработка перехода на конкретное письмо
    var emailIdHandled by remember { mutableStateOf(false) }
    
    LaunchedEffect(openEmailId, hasCheckedAccounts, startDestination) {
        // Обрабатываем только один раз и только когда всё готово
        if (openEmailId != null && !emailIdHandled && hasCheckedAccounts && startDestination == Screen.Main.route) {
            emailIdHandled = true
            // Задержка чтобы NavHost успел инициализироваться
            kotlinx.coroutines.delay(500)
            try {
                // Получаем папку Входящие для навигации
                val account = withContext(Dispatchers.IO) { accountRepo.getActiveAccountSync() }
                if (account != null) {
                    val database = com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                    
                    // Проверяем что письмо существует
                    val emailExists = withContext(Dispatchers.IO) {
                        database.emailDao().getEmail(openEmailId) != null
                    }
                    
                    if (!emailExists) {
                        // Письмо не найдено — возможно ещё не синхронизировано
                        // Открываем Входящие с фильтром Непрочитанные
                        val inboxFolder = withContext(Dispatchers.IO) {
                            database.folderDao().getFolderByType(account.id, 2)
                        }
                        if (inboxFolder != null) {
                            navController.navigate(Screen.EmailList.createRoute(inboxFolder.id, "UNREAD")) {
                                launchSingleTop = true
                            }
                        }
                        return@LaunchedEffect
                    }
                    
                    val inboxFolder = withContext(Dispatchers.IO) {
                        database.folderDao().getFolderByType(account.id, 2) // type 2 = Inbox
                    }
                    
                    if (inboxFolder != null) {
                        // Сначала открываем Входящие (чтобы при нажатии "назад" вернуться туда)
                        navController.navigate(Screen.EmailList.createRoute(inboxFolder.id)) {
                            launchSingleTop = true
                        }
                        // Небольшая задержка
                        kotlinx.coroutines.delay(100)
                        // Затем открываем письмо
                        navController.navigate(Screen.EmailDetail.createRoute(openEmailId)) {
                            launchSingleTop = true
                        }
                    } else {
                        // Fallback - открываем письмо напрямую
                        navController.navigate(Screen.EmailDetail.createRoute(openEmailId)) {
                            launchSingleTop = true
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }
    
    // Обработка перехода на Входящие с фильтром Непрочитанные
    LaunchedEffect(openInboxUnread, hasCheckedAccounts) {
        if (openInboxUnread && hasCheckedAccounts) {
            // Получаем папку Входящие
            val account = withContext(Dispatchers.IO) { accountRepo.getActiveAccountSync() }
            if (account != null) {
                val database = com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
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
    
    NavHost(
        navController = navController,
        startDestination = startDestination!!,
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
                    navController.navigate(Screen.Calendar.route)
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
                    // Если есть куда вернуться — просто возвращаемся
                    // Если это первый аккаунт (нет back stack) — переходим на Main
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onNavigateToVerification = { email, displayName, serverUrl, username, password, domain, acceptAllCerts, color, incomingPort, outgoingServer, outgoingPort, useSSL, syncMode, certificatePath ->
                    navController.navigate(
                        Screen.Verification.createRoute(
                            email, displayName, serverUrl, username, password, domain,
                            acceptAllCerts, color, incomingPort, outgoingServer, outgoingPort, useSSL, syncMode, certificatePath
                        )
                    )
                },
                onBackClick = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
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
            val parts = decoded.split("|")
            if (parts.size >= 13) {
                val certificatePath = if (parts.size >= 14 && parts[13].isNotBlank()) parts[13] else null
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
                    syncMode = try { com.iwo.mailclient.data.database.SyncMode.valueOf(parts[12]) } catch (e: Exception) { com.iwo.mailclient.data.database.SyncMode.PUSH },
                    certificatePath = certificatePath,
                    onSuccess = {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(0) { inclusive = true }
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
        
        composable(
            route = Screen.EmailList.route,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType },
                navArgument("filter") { 
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
                "UNREAD" -> com.iwo.mailclient.ui.screens.MailFilter.UNREAD
                "STARRED" -> com.iwo.mailclient.ui.screens.MailFilter.STARRED
                "WITH_ATTACHMENTS" -> com.iwo.mailclient.ui.screens.MailFilter.WITH_ATTACHMENTS
                "IMPORTANT" -> com.iwo.mailclient.ui.screens.MailFilter.IMPORTANT
                else -> com.iwo.mailclient.ui.screens.MailFilter.ALL
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
                initialFilter = initialFilter
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
                onBackClick = { navController.popBackStack() },
                onReplyClick = { navController.navigate(Screen.Compose.createRoute(replyTo = emailId)) },
                onForwardClick = { navController.navigate(Screen.Compose.createRoute(forwardId = emailId)) },
                onComposeToEmail = { toEmail -> navController.navigate(Screen.Compose.createRoute(toEmail = toEmail)) }
            )
        }
        
        composable(
            route = Screen.Compose.route,
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
                }
            )
        ) { backStackEntry ->
            val replyTo = Screen.Compose.decodeId(backStackEntry.arguments?.getString("replyTo"))
            val forwardId = Screen.Compose.decodeId(backStackEntry.arguments?.getString("forwardId"))
            val toEmail = Screen.Compose.decodeId(backStackEntry.arguments?.getString("toEmail"))
            val draftId = Screen.Compose.decodeId(backStackEntry.arguments?.getString("draftId"))
            ComposeScreen(
                replyToEmailId = replyTo,
                forwardEmailId = forwardId,
                initialToEmail = toEmail,
                editDraftId = draftId,
                onBackClick = { navController.popBackStack() },
                onSent = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onEditAccount = { accountId ->
                    navController.navigate(Screen.Setup.createRoute(accountId))
                },
                onAddAccount = {
                    navController.navigate(Screen.Setup.createRoute())
                },
                onNavigateToPersonalization = {
                    navController.navigate(Screen.Personalization.route)
                },
                onNavigateToAccountSettings = { accountId ->
                    navController.navigate(Screen.AccountSettings.createRoute(accountId))
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
                onBackClick = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Personalization.route) {
            PersonalizationScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Search.route) {
            SearchScreen(
                onBackClick = { navController.popBackStack() },
                onEmailClick = { emailId ->
                    navController.navigate(Screen.EmailDetail.createRoute(emailId))
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
        
        composable(Screen.Calendar.route) {
            CalendarScreen(
                onBackClick = { navController.popBackStack() },
                onComposeClick = { email ->
                    navController.navigate(Screen.Compose.createRoute(toEmail = email))
                }
            )
        }
    }
}

