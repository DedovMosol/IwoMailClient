package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.BuildConfig
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.update.DownloadProgress
import com.dedovmosol.iwomail.update.PreviousVersionInfo
import com.dedovmosol.iwomail.update.PreviousVersionResult
import com.dedovmosol.iwomail.update.UpdateChecker
import com.dedovmosol.iwomail.update.UpdateResult
import kotlinx.coroutines.launch
import java.io.File

/**
 * Экран настроек обновлений
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val updateChecker = remember { UpdateChecker(context) }
    val isRu = isRussian()
    val colorTheme = LocalColorTheme.current
    
    // Saver для UpdateCheckState (сохраняем только простые состояния, Available требует перепроверки)
    val updateStateSaver = Saver<UpdateCheckState, String>(
        save = { state ->
            when (state) {
                is UpdateCheckState.Idle -> "idle"
                is UpdateCheckState.Checking -> "checking"
                is UpdateCheckState.UpToDate -> "uptodate"
                is UpdateCheckState.Available -> "available" // При восстановлении вернём Idle, т.к. info не сериализуется
                is UpdateCheckState.Error -> "error:${state.message}"
            }
        },
        restore = { value ->
            when {
                value == "idle" -> UpdateCheckState.Idle
                value == "checking" -> UpdateCheckState.Idle // Проверка прервана поворотом — сбрасываем
                value == "uptodate" -> UpdateCheckState.UpToDate
                value == "available" -> UpdateCheckState.Idle // Нужна перепроверка
                value.startsWith("error:") -> UpdateCheckState.Error(value.removePrefix("error:"))
                else -> UpdateCheckState.Idle
            }
        }
    )
    
    // Saver для RollbackCheckState
    val rollbackStateSaver = Saver<RollbackCheckState, String>(
        save = { state ->
            when (state) {
                is RollbackCheckState.Idle -> "idle"
                is RollbackCheckState.Checking -> "checking"
                is RollbackCheckState.NotAvailable -> "notavailable"
                is RollbackCheckState.Available -> "available"
                is RollbackCheckState.Error -> "error:${state.message}"
            }
        },
        restore = { value ->
            when {
                value == "idle" -> RollbackCheckState.Idle
                value == "checking" -> RollbackCheckState.Idle
                value == "notavailable" -> RollbackCheckState.NotAvailable
                value == "available" -> RollbackCheckState.Idle // Нужна перепроверка
                value.startsWith("error:") -> RollbackCheckState.Error(value.removePrefix("error:"))
                else -> RollbackCheckState.Idle
            }
        }
    )
    
    var updateState by rememberSaveable(stateSaver = updateStateSaver) { mutableStateOf(UpdateCheckState.Idle) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.dedovmosol.iwomail.update.UpdateInfo?>(null) }
    
    // Состояние для отката
    var rollbackState by rememberSaveable(stateSaver = rollbackStateSaver) { mutableStateOf(RollbackCheckState.Idle) }
    var showRollbackDialog by rememberSaveable { mutableStateOf(false) }
    var previousVersionInfo by remember { mutableStateOf<PreviousVersionInfo?>(null) }
    
    val updateCheckInterval by settingsRepo.updateCheckInterval.collectAsState(
        initial = SettingsRepository.UpdateCheckInterval.DAILY
    )
    
    // Диалог обновления
    if (showUpdateDialog && updateInfo != null) {
        UpdateDownloadDialog(
            updateInfo = updateInfo!!,
            updateChecker = updateChecker,
            onDismiss = { 
                showUpdateDialog = false
                updateState = UpdateCheckState.Idle
            }
        )
    }
    
    // Диалог отката
    if (showRollbackDialog && previousVersionInfo != null) {
        RollbackDialog(
            previousInfo = previousVersionInfo!!,
            updateChecker = updateChecker,
            onDismiss = { 
                showRollbackDialog = false
                rollbackState = RollbackCheckState.Idle
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isRu) "Обновления" else "Updates", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                    )
                )
            )
        }
    ) { padding ->
        // Dropdown для автопроверки
        var showIntervalMenu by rememberSaveable { mutableStateOf(false) }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Карточка текущей версии с кнопкой проверки
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Иконка и версия
                    Icon(
                        AppIcons.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = colorTheme.gradientStart
                    )
                    
                    Text(
                        if (isRu) "Версия ${BuildConfig.VERSION_NAME}" else "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Статус обновления
                    Text(
                        when (updateState) {
                            is UpdateCheckState.Idle -> if (isRu) "Проверьте наличие обновлений" else "Check for updates"
                            is UpdateCheckState.Checking -> Strings.checkingForUpdates
                            is UpdateCheckState.UpToDate -> Strings.noUpdatesAvailable
                            is UpdateCheckState.Error -> "${Strings.updateError}: ${(updateState as UpdateCheckState.Error).message}"
                            is UpdateCheckState.Available -> "${Strings.updateAvailable}: ${(updateState as UpdateCheckState.Available).info.versionName}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (updateState) {
                            is UpdateCheckState.Available -> MaterialTheme.colorScheme.primary
                            is UpdateCheckState.Error -> MaterialTheme.colorScheme.error
                            is UpdateCheckState.UpToDate -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Кнопка проверки обновлений
                    Button(
                        onClick = {
                            if (updateState is UpdateCheckState.Available) {
                                showUpdateDialog = true
                            } else {
                                scope.launch {
                                    updateState = UpdateCheckState.Checking
                                    when (val result = updateChecker.checkForUpdate(isRu)) {
                                        is UpdateResult.Available -> {
                                            updateInfo = result.info
                                            updateState = UpdateCheckState.Available(result.info)
                                            showUpdateDialog = true
                                        }
                                        is UpdateResult.UpToDate -> {
                                            updateState = UpdateCheckState.UpToDate
                                        }
                                        is UpdateResult.Error -> {
                                            updateState = UpdateCheckState.Error(result.message)
                                        }
                                    }
                                }
                            }
                        },
                        enabled = updateState !is UpdateCheckState.Checking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorTheme.gradientStart
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (updateState is UpdateCheckState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            when (updateState) {
                                is UpdateCheckState.Available -> if (isRu) "Установить обновление" else "Install update"
                                is UpdateCheckState.Checking -> if (isRu) "Проверка..." else "Checking..."
                                else -> Strings.checkForUpdates
                            },
                            color = Color.White
                        )
                    }
                }
            }
            
            // Автопроверка — отдельная карточка с понятным dropdown
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        Strings.autoUpdateCheck,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Box {
                        OutlinedButton(
                            onClick = { showIntervalMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(updateCheckInterval.getDisplayName(isRu))
                                Icon(
                                    AppIcons.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        DropdownMenu(
                            expanded = showIntervalMenu,
                            onDismissRequest = { showIntervalMenu = false }
                        ) {
                            SettingsRepository.UpdateCheckInterval.entries.forEach { interval ->
                                DropdownMenuItem(
                                    text = { Text(interval.getDisplayName(isRu)) },
                                    onClick = {
                                        scope.launch {
                                            settingsRepo.setUpdateCheckInterval(interval)
                                        }
                                        showIntervalMenu = false
                                    },
                                    leadingIcon = {
                                        if (updateCheckInterval == interval) {
                                            Icon(
                                                AppIcons.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Карточка предыдущей версии внизу
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = rollbackState !is RollbackCheckState.Checking) {
                        if (rollbackState is RollbackCheckState.Available) {
                            showRollbackDialog = true
                        } else {
                            scope.launch {
                                rollbackState = RollbackCheckState.Checking
                                when (val result = updateChecker.checkPreviousVersion(isRu)) {
                                    is PreviousVersionResult.Available -> {
                                        previousVersionInfo = result.info
                                        rollbackState = RollbackCheckState.Available(result.info)
                                        showRollbackDialog = true
                                    }
                                    is PreviousVersionResult.NotAvailable -> {
                                        rollbackState = RollbackCheckState.NotAvailable
                                    }
                                    is PreviousVersionResult.Error -> {
                                        rollbackState = RollbackCheckState.Error(result.message)
                                    }
                                }
                            }
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = when (rollbackState) {
                        is RollbackCheckState.Available -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Иконка
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                when (rollbackState) {
                                    is RollbackCheckState.Available -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (rollbackState is RollbackCheckState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                AppIcons.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = when (rollbackState) {
                                    is RollbackCheckState.Available -> MaterialTheme.colorScheme.error
                                    is RollbackCheckState.NotAvailable, is RollbackCheckState.Error -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                    
                    // Текст
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when (rollbackState) {
                                is RollbackCheckState.Available -> {
                                    val version = (rollbackState as RollbackCheckState.Available).info.versionName
                                    if (isRu) "Предыдущая версия $version" else "Previous version $version"
                                }
                                else -> Strings.rollbackToPrevious
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            when (rollbackState) {
                                is RollbackCheckState.Idle -> if (isRu) "Нажмите для проверки" else "Tap to check"
                                is RollbackCheckState.Checking -> Strings.rollbackChecking
                                is RollbackCheckState.NotAvailable -> Strings.rollbackNotAvailable
                                is RollbackCheckState.Available -> if (isRu) "Нажмите для установки" else "Tap to install"
                                is RollbackCheckState.Error -> "${Strings.updateError}: ${(rollbackState as RollbackCheckState.Error).message}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (rollbackState) {
                                is RollbackCheckState.Available -> MaterialTheme.colorScheme.error
                                is RollbackCheckState.Error, is RollbackCheckState.NotAvailable -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    
                    // Стрелка
                    if (rollbackState !is RollbackCheckState.Checking) {
                        Icon(
                            AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Состояние проверки обновлений
 */
private sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(val info: com.dedovmosol.iwomail.update.UpdateInfo) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

/**
 * Состояние проверки предыдущей версии
 */
private sealed class RollbackCheckState {
    object Idle : RollbackCheckState()
    object Checking : RollbackCheckState()
    object NotAvailable : RollbackCheckState()
    data class Available(val info: PreviousVersionInfo) : RollbackCheckState()
    data class Error(val message: String) : RollbackCheckState()
}

/**
 * Состояние скачивания
 */
private sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Int, val downloadedMb: Float, val totalMb: Float) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Диалог скачивания обновления
 */
@Composable
private fun UpdateDownloadDialog(
    updateInfo: com.dedovmosol.iwomail.update.UpdateInfo,
    updateChecker: UpdateChecker,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colorTheme = LocalColorTheme.current
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
        onDismissRequest = { 
            if (downloadState !is DownloadState.Downloading) {
                onDismiss()
            }
        },
        icon = { Icon(AppIcons.Update, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(Strings.updateAvailable) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Версии
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${Strings.currentVersion}:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${Strings.newVersion}:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        updateInfo.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Changelog
                if (updateInfo.changelog.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        Strings.whatsNew,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        updateInfo.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Прогресс скачивания
                when (val state = downloadState) {
                    is DownloadState.Downloading -> {
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.downloading, style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                Strings.downloadProgress(state.downloadedMb, state.totalMb),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is DownloadState.Completed -> {
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                AppIcons.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                Strings.downloadComplete,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    is DownloadState.Error -> {
                        HorizontalDivider()
                        Text(
                            "${Strings.downloadError}: ${state.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
                
                // Кнопки
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Кнопка "Позже" слева
                    if (downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = onDismiss) {
                            Text(Strings.later)
                        }
                    } else {
                        TextButton(onClick = {
                            downloadJob?.cancel()
                            downloadJob = null
                            downloadState = DownloadState.Idle
                            updateChecker.clearUpdateFiles()
                        }) {
                            Text(Strings.cancel)
                        }
                    }
                    
                    // Кнопка действия справа с градиентом
                    when (downloadState) {
                        is DownloadState.Idle, is DownloadState.Error -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                        )
                                    )
                                    .clickable {
                                        downloadJob = scope.launch {
                                            updateChecker.downloadUpdate(updateInfo.apkUrl).collect { progress ->
                                                when (progress) {
                                                    is DownloadProgress.Starting -> {
                                                        downloadState = DownloadState.Downloading(0, 0f, 0f)
                                                    }
                                                    is DownloadProgress.Downloading -> {
                                                        downloadState = DownloadState.Downloading(
                                                            progress.progress,
                                                            progress.downloadedMb,
                                                            progress.totalMb
                                                        )
                                                    }
                                                    is DownloadProgress.Completed -> {
                                                        downloadedFile = progress.file
                                                        downloadState = DownloadState.Completed
                                                        downloadJob = null
                                                    }
                                                    is DownloadProgress.Error -> {
                                                        downloadState = DownloadState.Error(progress.message)
                                                        downloadJob = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Download, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.downloadUpdate, color = Color.White)
                                }
                            }
                        }
                        is DownloadState.Downloading -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                colorTheme.gradientStart.copy(alpha = 0.5f),
                                                colorTheme.gradientEnd.copy(alpha = 0.5f)
                                            )
                                        )
                                    )
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.downloading, color = Color.White)
                                }
                            }
                        }
                        is DownloadState.Completed -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                        )
                                    )
                                    .clickable {
                                        downloadedFile?.let { file ->
                                            updateChecker.installApk(file)
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Install, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.install, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Диалог отката на предыдущую версию
 */
@Composable
private fun RollbackDialog(
    previousInfo: PreviousVersionInfo,
    updateChecker: UpdateChecker,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colorTheme = LocalColorTheme.current
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
        onDismissRequest = { 
            if (downloadState !is DownloadState.Downloading) {
                onDismiss()
            }
        },
        icon = { Icon(AppIcons.Restore, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("${Strings.rollbackTitle} v${previousInfo.versionName}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Чего не будет в старой версии
                if (previousInfo.missingFeatures.isNotEmpty()) {
                    Text(
                        Strings.rollbackWarning,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    previousInfo.missingFeatures.forEach { feature ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", color = MaterialTheme.colorScheme.error)
                            Text(
                                feature,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Что потеряется
                if (previousInfo.lostData.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        Strings.rollbackDataLoss,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    previousInfo.lostData.forEach { data ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", color = MaterialTheme.colorScheme.error)
                            Text(
                                data,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                HorizontalDivider()
                Text(
                    Strings.rollbackDataSync,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Предупреждение о необходимости удаления (для Android < 8)
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    HorizontalDivider()
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Warning,
                                    null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isRussian()) "Важно!" else "Important!",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isRussian()) 
                                    "Для установки предыдущей версии потребуется сначала удалить текущую версию приложения. Убедитесь, что у вас есть резервная копия данных."
                                else 
                                    "To install the previous version, you need to uninstall the current version first. Make sure you have a backup of your data.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                
                // Прогресс скачивания
                when (val state = downloadState) {
                    is DownloadState.Downloading -> {
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.downloading, style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                Strings.downloadProgress(state.downloadedMb, state.totalMb),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is DownloadState.Completed -> {
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                AppIcons.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                Strings.downloadComplete,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    is DownloadState.Error -> {
                        HorizontalDivider()
                        Text(
                            "${Strings.downloadError}: ${state.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
                
                // Кнопки
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = onDismiss) {
                            Text(Strings.cancel)
                        }
                    } else {
                        TextButton(onClick = {
                            downloadJob?.cancel()
                            downloadJob = null
                            downloadState = DownloadState.Idle
                            updateChecker.clearUpdateFiles()
                        }) {
                            Text(Strings.cancel)
                        }
                    }
                    
                    when (downloadState) {
                        is DownloadState.Idle, is DownloadState.Error -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.error,
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        )
                                    )
                                    .clickable {
                                        downloadJob = scope.launch {
                                            updateChecker.downloadUpdate(previousInfo.apkUrl).collect { progress ->
                                                when (progress) {
                                                    is DownloadProgress.Starting -> {
                                                        downloadState = DownloadState.Downloading(0, 0f, 0f)
                                                    }
                                                    is DownloadProgress.Downloading -> {
                                                        downloadState = DownloadState.Downloading(
                                                            progress.progress,
                                                            progress.downloadedMb,
                                                            progress.totalMb
                                                        )
                                                    }
                                                    is DownloadProgress.Completed -> {
                                                        downloadedFile = progress.file
                                                        downloadState = DownloadState.Completed
                                                        downloadJob = null
                                                    }
                                                    is DownloadProgress.Error -> {
                                                        downloadState = DownloadState.Error(progress.message)
                                                        downloadJob = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Download, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.rollbackConfirm, color = Color.White)
                                }
                            }
                        }
                        is DownloadState.Downloading -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.downloading, color = Color.White)
                                }
                            }
                        }
                        is DownloadState.Completed -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.error,
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        )
                                    )
                                    .clickable {
                                        downloadedFile?.let { file ->
                                            updateChecker.installApk(file, isDowngrade = true)
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Install, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(Strings.install, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
