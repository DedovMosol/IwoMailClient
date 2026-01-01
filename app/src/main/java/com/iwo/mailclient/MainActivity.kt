package com.iwo.mailclient

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
import com.iwo.mailclient.ui.theme.AppIcons
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
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.ui.AppLanguage
import com.iwo.mailclient.ui.LocalLanguage
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.theme.ExchangeMailTheme
import com.iwo.mailclient.ui.theme.AppColorTheme
import com.iwo.mailclient.ui.navigation.AppNavigation

@Composable
private fun PermissionDialog(
    title: String,
    text: String,
    dismissText: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    
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
                        text.split("\n\n").forEach { paragraph ->
                            if (paragraph.isNotBlank()) {
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textIndent = TextIndent(firstLine = 24.sp)
                                    ),
                                    textAlign = TextAlign.Justify
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
                        com.iwo.mailclient.ui.theme.GradientDialogButton(
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
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    
    private var openInboxUnread = mutableStateOf(false)
    private var openEmailId = mutableStateOf<String?>(null)
    private var showBatteryDialog = mutableStateOf(false)
    private var showAlarmDialog = mutableStateOf(false)
    private var permissionsChecked = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            
            // Контроллер отложенного удаления
            val deletionController = remember { com.iwo.mailclient.ui.components.DeletionController() }
            
            // Кастомный TextToolbar с правильным порядком кнопок
            val view = androidx.compose.ui.platform.LocalView.current
            val customTextToolbar = remember(view) { com.iwo.mailclient.ui.theme.CustomTextToolbar(view) }
            
            CompositionLocalProvider(
                LocalLanguage provides currentLanguage,
                com.iwo.mailclient.ui.components.LocalDeletionController provides deletionController,
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
                                openEmailId = emailIdToOpen
                            )
                        }
                        
                        // Плашка прогресса удаления поверх всего
                        com.iwo.mailclient.ui.components.DeletionProgressBar(
                            controller = deletionController,
                            modifier = Modifier.align(Alignment.TopCenter)
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
        if (permissionsChecked && !showBatteryDialog.value && !showAlarmDialog.value) {
            checkNextPermission()
        }
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
