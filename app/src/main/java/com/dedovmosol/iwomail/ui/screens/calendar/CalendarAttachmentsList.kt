package com.dedovmosol.iwomail.ui.screens.calendar

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.repository.CalendarRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal data class CalendarAttachmentInfo(val name: String, val fileReference: String, val size: Long, val isInline: Boolean)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CalendarAttachmentsList(
    attachmentsJson: String,
    accountId: Long,
    calendarRepo: CalendarRepository
) {
    val context = LocalContext.current
    val accountRepo = remember { com.dedovmosol.iwomail.data.repository.RepositoryProvider.getAccountRepository(context) }
    val scope = rememberCoroutineScope()
    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
    
    val attachments = remember(attachmentsJson) {
        try {
            val jsonArray = org.json.JSONArray(attachmentsJson)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                CalendarAttachmentInfo(
                    name = obj.optString("name", "attachment"),
                    fileReference = obj.optString("fileReference", ""),
                    size = obj.optLong("size", 0),
                    isInline = obj.optBoolean("isInline", false)
                )
            }.filter { it.fileReference.isNotBlank() && !it.isInline }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    if (attachments.isEmpty()) return
    
    // Save As: системный файл-пикер
    var pendingSaveAsAtt by remember { mutableStateOf<CalendarAttachmentInfo?>(null) }
    var pendingPreviewFile by remember { mutableStateOf<java.io.File?>(null) }
    var downloadingRef by remember { mutableStateOf<String?>(null) }
    val previewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Удаляем временный файл после возврата из просмотрщика
        pendingPreviewFile?.delete()
        pendingPreviewFile = null
    }
    val saveAsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val att = pendingSaveAsAtt ?: return@rememberLauncherForActivityResult
        pendingSaveAsAtt = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            downloadingRef = att.fileReference
            try {
                when (val result = calendarRepo.downloadCalendarAttachment(accountId, att.fileReference)) {
                    is EasResult.Success -> {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(result.data) }
                        }
                        Toast.makeText(context, if (isRussian) "Файл сохранён" else "File saved", Toast.LENGTH_SHORT).show()
                    }
                    is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } finally {
                downloadingRef = null
            }
        }
    }
    
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Attachment,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRussian) "Вложения (${attachments.size})" else "Attachments (${attachments.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        
        attachments.forEach { att ->
            val isDownloading = downloadingRef == att.fileReference
            var showSaveMenu by remember { mutableStateOf(false) }
            val openPreview = {
                downloadingRef = att.fileReference
                scope.launch {
                    try {
                        when (val result = calendarRepo.downloadCalendarAttachment(accountId, att.fileReference)) {
                            is EasResult.Success -> {
                                val safeFileName = att.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                val tempFile = withContext(Dispatchers.IO) {
                                    val previewDir = java.io.File(context.cacheDir, "calendar_preview")
                                    if (!previewDir.exists()) previewDir.mkdirs()
                                    java.io.File(previewDir, safeFileName).apply {
                                        writeBytes(result.data)
                                    }
                                }

                                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(java.io.File(att.name).extension.lowercase(Locale.ROOT))
                                    ?: "application/octet-stream"

                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tempFile
                                )

                                pendingPreviewFile = tempFile
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mimeType)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    previewLauncher.launch(intent)

                                    // Fallback: если внешнее приложение не вернёт result, чистим позже
                                    scope.launch {
                                        kotlinx.coroutines.delay(60 * 60 * 1000L)
                                        if (pendingPreviewFile?.absolutePath == tempFile.absolutePath) {
                                            pendingPreviewFile?.delete()
                                            pendingPreviewFile = null
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    withContext(Dispatchers.IO) { tempFile.delete() }
                                    pendingPreviewFile = null
                                    Toast.makeText(
                                        context,
                                        if (isRussian) "Нет приложения для просмотра файла" else "No app to preview this file",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Toast.makeText(context, e.message ?: (if (isRussian) "Ошибка просмотра" else "Preview error"), Toast.LENGTH_LONG).show()
                    } finally {
                        downloadingRef = null
                    }
                }
                Unit
            }
            
            Box {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .combinedClickable(
                            enabled = !isDownloading,
                            onClick = openPreview,
                            onLongClick = { showSaveMenu = true }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.fileIconFor(att.name),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = att.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (att.size > 0) {
                                val sizeStr = when {
                                    att.size < 1024 -> "${att.size} B"
                                    att.size < 1024 * 1024 -> "${att.size / 1024} KB"
                                    else -> String.format("%.1f MB", att.size / (1024.0 * 1024.0))
                                }
                                Text(
                                    text = sizeStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = { showSaveMenu = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    AppIcons.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                DropdownMenu(
                    expanded = showSaveMenu,
                    onDismissRequest = { showSaveMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isRussian) "Просмотр" else "Preview") },
                        onClick = {
                            showSaveMenu = false
                            openPreview()
                        },
                        leadingIcon = { Icon(AppIcons.Visibility, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(com.dedovmosol.iwomail.ui.Strings.save) },
                        onClick = {
                            showSaveMenu = false
                            downloadingRef = att.fileReference
                            scope.launch {
                                try {
                                    when (val result = calendarRepo.downloadCalendarAttachment(accountId, att.fileReference)) {
                                        is EasResult.Success -> {
                                            val safeFileName = att.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                            val calendarPath = withContext(Dispatchers.IO) {
                                                accountRepo.getResolvedCalendarRelativePath(accountId)
                                            } ?: run {
                                                Toast.makeText(
                                                    context,
                                                    com.dedovmosol.iwomail.ui.NotificationStrings.localizeError(
                                                        com.dedovmosol.iwomail.data.repository.RepositoryErrors.ACCOUNT_NOT_FOUND,
                                                        isRussian
                                                    ),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                return@launch
                                            }
                                            withContext(Dispatchers.IO) {
                                                val contentValues = android.content.ContentValues().apply {
                                                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeFileName)
                                                    put(android.provider.MediaStore.Downloads.MIME_TYPE,
                                                        android.webkit.MimeTypeMap.getSingleton()
                                                            .getMimeTypeFromExtension(java.io.File(safeFileName).extension) ?: "application/octet-stream")
                                                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, calendarPath)
                                                }
                                                val uri = context.contentResolver.insert(
                                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                                                )
                                                uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(result.data) } }
                                            }
                                            val calPath = "Downloads/${calendarPath.removePrefix("Download/")}"
                                            Toast.makeText(context, if (isRussian) "Сохранено в $calPath/" else "Saved to $calPath/", Toast.LENGTH_SHORT).show()
                                        }
                                        is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                } finally {
                                    downloadingRef = null
                                }
                            }
                        },
                        leadingIcon = { Icon(AppIcons.Download, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(com.dedovmosol.iwomail.ui.Strings.saveAs) },
                        onClick = {
                            showSaveMenu = false
                            pendingSaveAsAtt = att
                            saveAsLauncher.launch(att.name)
                        },
                        leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) }
                    )
                }
            }
        }
    }
}
