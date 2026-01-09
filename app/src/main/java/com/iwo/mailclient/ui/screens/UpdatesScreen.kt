package com.iwo.mailclient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.BuildConfig
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.isRussian
import com.iwo.mailclient.ui.theme.AppIcons
import com.iwo.mailclient.ui.theme.LocalColorTheme
import com.iwo.mailclient.update.DownloadProgress
import com.iwo.mailclient.update.UpdateChecker
import com.iwo.mailclient.update.UpdateResult
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
    
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.iwo.mailclient.update.UpdateInfo?>(null) }
    
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Текущая версия
            item {
                ListItem(
                    headlineContent = { Text(Strings.currentVersion) },
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    leadingContent = { Icon(AppIcons.Info, null) }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Проверить обновления
            item {
                ListItem(
                    headlineContent = { Text(Strings.checkForUpdates) },
                    supportingContent = { 
                        Text(
                            when (updateState) {
                                is UpdateCheckState.Idle -> if (isRu) "Нажмите для проверки" else "Tap to check"
                                is UpdateCheckState.Checking -> Strings.checkingForUpdates
                                is UpdateCheckState.UpToDate -> Strings.noUpdatesAvailable
                                is UpdateCheckState.Error -> "${Strings.updateError}: ${(updateState as UpdateCheckState.Error).message}"
                                is UpdateCheckState.Available -> "${Strings.updateAvailable}: ${(updateState as UpdateCheckState.Available).info.versionName}"
                            }
                        )
                    },
                    leadingContent = { 
                        if (updateState is UpdateCheckState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(AppIcons.Refresh, null)
                        }
                    },
                    modifier = Modifier.clickable(enabled = updateState !is UpdateCheckState.Checking) {
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
                    }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Автопроверка обновлений
            item {
                Text(
                    text = Strings.autoUpdateCheck,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            // Варианты интервала
            item {
                Column {
                    SettingsRepository.UpdateCheckInterval.entries.forEach { interval ->
                        ListItem(
                            headlineContent = { Text(interval.getDisplayName(isRu)) },
                            leadingContent = {
                                RadioButton(
                                    selected = updateCheckInterval == interval,
                                    onClick = null
                                )
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    settingsRepo.setUpdateCheckInterval(interval)
                                }
                            }
                        )
                    }
                }
            }
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
    data class Available(val info: com.iwo.mailclient.update.UpdateInfo) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
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
    updateInfo: com.iwo.mailclient.update.UpdateInfo,
    updateChecker: UpdateChecker,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colorTheme = LocalColorTheme.current
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    
    com.iwo.mailclient.ui.theme.StyledAlertDialog(
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
                        Spacer(modifier = Modifier.width(1.dp))
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
                                        scope.launch {
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
                                                    }
                                                    is DownloadProgress.Error -> {
                                                        downloadState = DownloadState.Error(progress.message)
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
