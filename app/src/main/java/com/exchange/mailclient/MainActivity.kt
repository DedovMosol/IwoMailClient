package com.exchange.mailclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.exchange.mailclient.data.repository.SettingsRepository
import com.exchange.mailclient.ui.AppLanguage
import com.exchange.mailclient.ui.LocalLanguage
import com.exchange.mailclient.ui.theme.ExchangeMailTheme
import com.exchange.mailclient.ui.navigation.AppNavigation

class MainActivity : ComponentActivity() {
    
    companion object {
        const val EXTRA_OPEN_INBOX_UNREAD = "open_inbox_unread"
        const val EXTRA_OPEN_EMAIL_ID = "open_email_id"
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    
    private var openInboxUnread = mutableStateOf(false)
    private var openEmailId = mutableStateOf<String?>(null)
    private var showBatteryDialog = mutableStateOf(false)
    private var showAlarmDialog = mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHighRefreshRate()
        requestNotificationPermission()
        checkPermissionsForDialogs()
        handleIntent(intent)
        
        enableEdgeToEdge()
        setContent {
            val settingsRepo = remember { SettingsRepository.getInstance(this) }
            val initialLanguage = remember { settingsRepo.getLanguageSync() }
            val languageCode by settingsRepo.language.collectAsState(initial = initialLanguage)
            val initialFontSize = remember { settingsRepo.getFontSizeSync() }
            val fontSize by settingsRepo.fontSize.collectAsState(initial = initialFontSize)
            
            val currentLanguage = remember(languageCode) {
                AppLanguage.entries.find { it.code == languageCode } ?: AppLanguage.RUSSIAN
            }
            
            val shouldOpenInboxUnread by openInboxUnread
            val emailIdToOpen by openEmailId
            
            CompositionLocalProvider(LocalLanguage provides currentLanguage) {
                ExchangeMailTheme(fontScale = fontSize.scale) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavigation(
                            openInboxUnread = shouldOpenInboxUnread,
                            openEmailId = emailIdToOpen
                        )
                    }
                    
                    val showBattery by showBatteryDialog
                    if (showBattery) {
                        val isRu = currentLanguage == AppLanguage.RUSSIAN
                        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
                            onDismissRequest = { showBatteryDialog.value = false },
                            title = { 
                                Text(if (isRu) "Фоновая работа" else "Background work") 
                            },
                            text = { 
                                Text(
                                    if (isRu) 
                                        "Для получения уведомлений о новых письмах приложению нужно работать в фоне.\n\nНажмите «Разрешить» в следующем окне."
                                    else 
                                        "To receive notifications about new emails, the app needs to work in the background.\n\nTap «Allow» in the next screen."
                                ) 
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showBatteryDialog.value = false
                                    openBatterySettings()
                                }) {
                                    Text(if (isRu) "Продолжить" else "Continue")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBatteryDialog.value = false }) {
                                    Text(if (isRu) "Позже" else "Later")
                                }
                            }
                        )
                    }
                    
                    val showAlarm by showAlarmDialog
                    if (showAlarm) {
                        val isRu = currentLanguage == AppLanguage.RUSSIAN
                        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
                            onDismissRequest = { showAlarmDialog.value = false },
                            title = { 
                                Text(if (isRu) "Точные уведомления" else "Exact notifications") 
                            },
                            text = { 
                                Text(
                                    if (isRu) 
                                        "Для своевременной синхронизации почты приложению нужно разрешение на точные будильники.\n\nВключите переключатель в следующем окне."
                                    else 
                                        "For timely mail sync, the app needs permission for exact alarms.\n\nEnable the toggle in the next screen."
                                ) 
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showAlarmDialog.value = false
                                    openAlarmSettings()
                                }) {
                                    Text(if (isRu) "Продолжить" else "Continue")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAlarmDialog.value = false }) {
                                    Text(if (isRu) "Позже" else "Later")
                                }
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
    
    private fun handleIntent(intent: Intent?) {
        val emailId = intent?.getStringExtra(EXTRA_OPEN_EMAIL_ID)
        if (emailId != null) {
            openEmailId.value = emailId
        } else if (intent?.getBooleanExtra(EXTRA_OPEN_INBOX_UNREAD, false) == true) {
            openInboxUnread.value = true
        }
    }
    
    private fun checkPermissionsForDialogs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryDialog.value = true
                return
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(android.app.AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                showAlarmDialog.value = true
            }
        }
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
