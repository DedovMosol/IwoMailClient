package com.dedovmosol.iwomail.ui.screens.calendar

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private fun dateTimeFormat() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
private fun dateFormat() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
private fun timeFormat() = SimpleDateFormat("HH:mm", Locale.getDefault())

private data class PendingCalendarAttachment(
    val filePath: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

private val PendingCalendarAttachmentsSaver = listSaver<List<PendingCalendarAttachment>, String>(
    save = { attachments ->
        attachments.flatMap { attachment ->
            listOf(
                attachment.filePath,
                attachment.name,
                attachment.mimeType,
                attachment.sizeBytes.toString()
            )
        }
    },
    restore = { saved ->
        saved.chunked(4).mapNotNull { chunk ->
            val sizeBytes = chunk.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
            val filePath = chunk.getOrNull(0) ?: return@mapNotNull null
            val name = chunk.getOrNull(1) ?: return@mapNotNull null
            val mimeType = chunk.getOrNull(2) ?: return@mapNotNull null
            PendingCalendarAttachment(
                filePath = filePath,
                name = name,
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        }
    }
)

private val StringSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateEventDialog(
    event: CalendarEventEntity?,
    initialDate: Date,
    isCreating: Boolean,
    accountId: Long,
    ownEmail: String,
    onDismiss: () -> Unit,
    onSave: (
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        attendees: String,
        recurrenceType: Int,
        attachments: List<DraftAttachmentData>,
        removedAttachmentIds: List<String>
    ) -> Unit
) {
    val context = LocalContext.current
    val isEditing = event != null
    
    // Получаем строки заранее для использования в onClick
    val invalidDateTimeText = Strings.invalidDateTime
    val endBeforeStartText = Strings.endBeforeStart
    
    // Состояния полей
    var subject by rememberSaveable { mutableStateOf(event?.subject ?: "") }
    var location by rememberSaveable { mutableStateOf(event?.location ?: "") }
    var body by rememberSaveable { mutableStateOf(event?.body ?: "") }
    var allDayEvent by rememberSaveable { mutableStateOf(event?.allDayEvent ?: false) }
    var reminder by rememberSaveable { mutableStateOf(event?.reminder ?: 15) }
    var busyStatus by rememberSaveable { mutableStateOf(event?.busyStatus ?: 2) }
    var attendees by rememberSaveable { mutableStateOf("") }
    // Тип повторения: -1=Нет, 0=Daily, 1=Weekly, 2=Monthly, 5=Yearly
    var recurrenceType by rememberSaveable {
        mutableStateOf(
            if (event?.isRecurring == true && event.recurrenceRule.isNotBlank()) {
                RecurrenceHelper.parseRule(event.recurrenceRule)?.type ?: -1
            } else -1
        )
    }
    // Диалог выбора контактов
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    
    // Вложения
    var pendingAttachments by rememberSaveable(stateSaver = PendingCalendarAttachmentsSaver) {
        mutableStateOf(emptyList())
    }
    var removedExistingAttachmentRefs by rememberSaveable(stateSaver = StringSetSaver) {
        mutableStateOf(emptySet())
    }
    val isRussianCallback = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN

    fun deletePendingAttachmentFile(filePath: String) {
        try {
            File(filePath).delete()
        } catch (_: Exception) {
        }
    }

    fun clearPendingAttachments() {
        pendingAttachments.forEach { attachment ->
            deletePendingAttachmentFile(attachment.filePath)
        }
        pendingAttachments = emptyList()
    }

    fun dismissDialog() {
        clearPendingAttachments()
        onDismiss()
    }

    fun buildDraftAttachments(): List<DraftAttachmentData>? {
        val result = mutableListOf<DraftAttachmentData>()
        for (attachment in pendingAttachments) {
            val file = File(attachment.filePath)
            val bytes = try {
                if (!file.exists()) null else file.readBytes()
            } catch (_: Exception) {
                null
            }

            if (bytes == null) {
                Toast.makeText(
                    context,
                    if (isRussianCallback) {
                        "Не удалось восстановить вложение ${attachment.name}"
                    } else {
                        "Failed to restore attachment ${attachment.name}"
                    },
                    Toast.LENGTH_LONG
                ).show()
                return null
            }

            result += DraftAttachmentData(
                name = attachment.name,
                mimeType = attachment.mimeType,
                data = bytes
            )
        }
        return result
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val maxSingleFile = 7L * 1024 * 1024 // 7 MB — лимит сервера
        val maxTotal = 10L * 1024 * 1024 // 10 MB суммарно
        var currentTotal = pendingAttachments.sumOf { it.sizeBytes }
        uris.forEach { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "file"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    if (size > maxSingleFile) {
                        val sizeMB = size / 1024 / 1024
                        Toast.makeText(context,
                            Strings.fileTooLargeMessage(name, sizeMB, isRussianCallback),
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (currentTotal + size > maxTotal) {
                        Toast.makeText(context,
                            Strings.attachmentLimitExceeded(isRussianCallback),
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    var streamExceededSingleLimit = false
                    var streamExceededTotalLimit = false
                    val attachmentsDir = File(context.cacheDir, "calendar_event_attachments").apply { mkdirs() }
                    val tempFile = File(attachmentsDir, "event_${UUID.randomUUID().toString().replace("-", "")}")
                    val copiedSize = context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var totalRead = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            totalRead += read
                            if (totalRead > maxSingleFile) {
                                streamExceededSingleLimit = true
                                return@use null
                            }
                            if (currentTotal + totalRead > maxTotal) {
                                streamExceededTotalLimit = true
                                return@use null
                            }
                            output.write(buffer, 0, read)
                        }
                        totalRead
                        }
                    }
                    if (copiedSize == null && streamExceededSingleLimit) {
                        deletePendingAttachmentFile(tempFile.absolutePath)
                        val sizeMB = maxSingleFile / 1024 / 1024
                        Toast.makeText(context,
                            Strings.fileTooLargeMessage(name, sizeMB, isRussianCallback),
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (copiedSize == null && streamExceededTotalLimit) {
                        deletePendingAttachmentFile(tempFile.absolutePath)
                        Toast.makeText(context,
                            Strings.attachmentLimitExceeded(isRussianCallback),
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (copiedSize == null) {
                        deletePendingAttachmentFile(tempFile.absolutePath)
                        Toast.makeText(
                            context,
                            if (isRussianCallback) "Не удалось прочитать вложение" else "Failed to read attachment",
                            Toast.LENGTH_LONG
                        ).show()
                        return@use
                    }
                    currentTotal += copiedSize
                    pendingAttachments = pendingAttachments + PendingCalendarAttachment(
                        filePath = tempFile.absolutePath,
                        name = name,
                        mimeType = mimeType,
                        sizeBytes = copiedSize
                    )
                }
            }
        }
    }
    
    // Текстовые поля для дат и времени
    var startDateText by rememberSaveable { mutableStateOf("") }
    var startTimeText by rememberSaveable { mutableStateOf("") }
    var endDateText by rememberSaveable { mutableStateOf("") }
    var endTimeText by rememberSaveable { mutableStateOf("") }
    
    var showReminderMenu by rememberSaveable { mutableStateOf(false) }
    var showStatusMenu by rememberSaveable { mutableStateOf(false) }
    
    // Состояния для DatePicker / TimePicker диалогов
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showStartTimePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndTimePicker by rememberSaveable { mutableStateOf(false) }
    
    // Инициализация текстовых полей из существующих дат
    LaunchedEffect(event, initialDate) {
        // КРИТИЧНО: НЕ устанавливаем UTC, т.к. пользователь работает в LOCAL timezone
        // БД хранит UTC, но отображаем в LOCAL
        
        if (event != null) {
            startDateText = dateFormat().format(Date(event.startTime))
            startTimeText = timeFormat().format(Date(event.startTime))
            endDateText = dateFormat().format(Date(event.endTime))
            endTimeText = timeFormat().format(Date(event.endTime))
        } else {
            // Используем текущее время, округлённое до следующего часа
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            
            startDateText = dateFormat().format(calendar.time)
            startTimeText = timeFormat().format(calendar.time)
            
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            endDateText = dateFormat().format(calendar.time)
            endTimeText = timeFormat().format(calendar.time)
        }
    }
    
    // Валидация
    val isValid = subject.isNotBlank()
    
    val lazyListState = rememberLazyListState()
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (!isCreating) dismissDialog() },
        scrollable = false, // Отключаем автоскролл диалога
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .widthIn(max = 560.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isEditing) Strings.editEvent else Strings.newEvent,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
            }
        },
        text = {
            // Контент с прокруткой + видимый скроллбар
            Box(modifier = Modifier.heightIn(min = 200.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 8.dp, bottom = 16.dp)
                ) {
                item {
                    // Название
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(Strings.eventTitle) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = subject.isBlank()
                    )
                }
                
                item {
                    
                    // Весь день
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(Strings.allDay)
                        Switch(
                            checked = allDayEvent,
                            onCheckedChange = { allDayEvent = it }
                        )
                    }
                }
                
                item {
                    
                    // Дата начала
                    Text(
                        text = Strings.startDate,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = startDateText,
                            onValueChange = { 
                                val filtered = it.filter { c -> c.isDigit() || c == '.' }
                                if (filtered.length <= 10) {
                                    startDateText = filtered
                                }
                            },
                            placeholder = { Text(Strings.datePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showStartDatePicker = true }) {
                                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        if (!allDayEvent) {
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { newValue ->
                                    val digits = newValue.filter { it.isDigit() }.take(4)
                                    startTimeText = when {
                                        digits.length <= 2 -> digits
                                        else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
                                    }
                                },
                            placeholder = { Text(Strings.timePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).height(48.dp),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { showStartTimePicker = true }) {
                                        Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                        }
                        if (startDateText.isNotEmpty() || startTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    startDateText = ""
                                    startTimeText = ""
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
                
                item {
                    val endLabel = if (recurrenceType != -1) Strings.endOfEachEvent else Strings.endDate
                    Text(
                        text = endLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (recurrenceType != -1) {
                        Text(
                            text = Strings.durationOfEachOccurrence,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = endDateText,
                            onValueChange = { 
                                val filtered = it.filter { c -> c.isDigit() || c == '.' }
                                if (filtered.length <= 10) {
                                    endDateText = filtered
                                }
                            },
                            placeholder = { Text(Strings.datePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showEndDatePicker = true }) {
                                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        if (!allDayEvent) {
                            OutlinedTextField(
                                value = endTimeText,
                                onValueChange = { newValue ->
                                    val digits = newValue.filter { it.isDigit() }.take(4)
                                    endTimeText = when {
                                        digits.length <= 2 -> digits
                                        else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
                                    }
                                },
                            placeholder = { Text(Strings.timePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).height(48.dp),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { showEndTimePicker = true }) {
                                        Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                        }
                        if (endDateText.isNotEmpty() || endTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    endDateText = ""
                                    endTimeText = ""
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
                
                item {
                    // Место
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text(Strings.eventLocation) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                item {
                    // Пригласить участников
                    OutlinedTextField(
                        value = attendees,
                        onValueChange = { attendees = it },
                        label = { Text(Strings.inviteAttendees) },
                        placeholder = { Text(Strings.attendeesHint) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 2,
                        trailingIcon = {
                            IconButton(onClick = { showContactPicker = true }) {
                                Icon(AppIcons.PersonAdd, contentDescription = null)
                            }
                        }
                    )
                }
                
                item {
                    // Напоминание
                    Box {
                        OutlinedTextField(
                            value = when (reminder) {
                                0 -> Strings.noReminder
                                5 -> Strings.minutes5
                                15 -> Strings.minutes15
                                30 -> Strings.minutes30
                                60 -> Strings.hour1
                                120 -> Strings.hours2
                                1440 -> Strings.day1
                                else -> "$reminder мин"
                            },
                            onValueChange = {},
                            label = { Text(Strings.reminder) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showReminderMenu = true },
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        
                        DropdownMenu(
                            expanded = showReminderMenu,
                            onDismissRequest = { showReminderMenu = false }
                        ) {
                            listOf(
                                0 to Strings.noReminder,
                                5 to Strings.minutes5,
                                15 to Strings.minutes15,
                                30 to Strings.minutes30,
                                60 to Strings.hour1,
                                120 to Strings.hours2,
                                1440 to Strings.day1
                            ).forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        reminder = value
                                        showReminderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                item {
                    // Статус занятости
                    Box {
                        OutlinedTextField(
                            value = when (busyStatus) {
                                0 -> Strings.statusFree
                                1 -> Strings.statusTentative
                                2 -> Strings.statusBusy
                                3 -> Strings.statusOof
                                else -> Strings.statusBusy
                            },
                            onValueChange = {},
                            label = { Text(Strings.busyStatus) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showStatusMenu = true },
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        
                        DropdownMenu(
                            expanded = showStatusMenu,
                            onDismissRequest = { showStatusMenu = false }
                        ) {
                            listOf(
                                0 to Strings.statusFree,
                                1 to Strings.statusTentative,
                                2 to Strings.statusBusy,
                                3 to Strings.statusOof
                            ).forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        busyStatus = value
                                        showStatusMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = Strings.repeatLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            -1 to Strings.noRepeat,
                            0 to Strings.everyDay,
                            1 to Strings.everyWeek,
                            2 to Strings.everyMonth,
                            5 to Strings.everyYear
                        ).forEach { (value, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { recurrenceType = value }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = recurrenceType == value,
                                    onClick = { recurrenceType = value }
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                
                item {
                    // Описание
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text(Strings.eventDescription) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        maxLines = 5
                    )
                }
                
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Существующие вложения с сервера (при редактировании)
                        if (isEditing && event != null && event.hasAttachments && event.attachments.isNotBlank()) {
                            val existingAttachments = remember(event.attachments) {
                                try {
                                    val jsonArray = org.json.JSONArray(event.attachments)
                                    (0 until jsonArray.length()).map { i ->
                                        val obj = jsonArray.getJSONObject(i)
                                        CalendarAttachmentInfo(
                                            name = obj.optString("name", "attachment"),
                                            fileReference = obj.optString("fileReference", ""),
                                            size = obj.optLong("size", 0),
                                            isInline = obj.optBoolean("isInline", false)
                                        )
                                    }.filter { !it.isInline }
                                } catch (_: Exception) { emptyList() }
                            }
                            val visibleAttachments = existingAttachments.filter { 
                                it.fileReference !in removedExistingAttachmentRefs 
                            }
                            if (visibleAttachments.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        AppIcons.Attachment,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = Strings.currentAttachmentsCount(visibleAttachments.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                visibleAttachments.forEach { att ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.fileIconFor(att.name),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.Unspecified
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = att.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (att.size > 0) {
                                            Text(
                                                text = Strings.formatFileSize(att.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                removedExistingAttachmentRefs = removedExistingAttachmentRefs + att.fileReference
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = AppIcons.Close,
                                                contentDescription = Strings.detach,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = AppIcons.Attachment,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.attachFile)
                        }
                        
                        if (pendingAttachments.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            pendingAttachments.forEachIndexed { index, att ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = AppIcons.fileIconFor(att.name),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.Unspecified
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = att.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = Strings.formatFileSize(att.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = {
                                            deletePendingAttachmentFile(att.filePath)
                                            pendingAttachments = pendingAttachments.toMutableList().also { it.removeAt(index) }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.Close,
                                            contentDescription = Strings.removeAttachment,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            LazyColumnScrollbar(lazyListState)
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = {
                    if (isValid) {
                        // Парсинг дат и времени
                        val startTime = if (allDayEvent) {
                            parseDateTime(startDateText, "00:00")
                        } else {
                            parseDateTime(startDateText, startTimeText)
                        }
                        
                        val endTime = if (allDayEvent) {
                            parseDateTime(endDateText, "23:59")
                        } else {
                            parseDateTime(endDateText, endTimeText)
                        }
                        
                        if (startTime <= 0 || endTime <= 0) {
                            Toast.makeText(context, invalidDateTimeText, Toast.LENGTH_SHORT).show()
                            return@ThemeOutlinedButton
                        }
                        
                        if (endTime <= startTime) {
                            Toast.makeText(context, endBeforeStartText, Toast.LENGTH_SHORT).show()
                            return@ThemeOutlinedButton
                        }

                        val draftAttachments = buildDraftAttachments() ?: return@ThemeOutlinedButton
                        clearPendingAttachments()
                        onSave(
                            subject,
                            startTime,
                            endTime,
                            location,
                            body,
                            allDayEvent,
                            reminder,
                            busyStatus,
                            attendees,
                            recurrenceType,
                            draftAttachments,
                            removedExistingAttachmentRefs.toList()
                        )
                    }
                },
                text = Strings.save,
                enabled = isValid,
                isLoading = isCreating
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = ::dismissDialog,
                text = Strings.cancel,
                enabled = !isCreating
            )
        }
    )
    
    // Диалог выбора контактов
    if (showContactPicker) {
        val database = remember { com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context) }
        com.dedovmosol.iwomail.ui.components.ContactPickerDialog(
            accountId = accountId,
            database = database,
            ownEmail = ownEmail,
            onDismiss = { showContactPicker = false },
            onContactsSelected = { selectedEmails ->
                if (selectedEmails.isNotEmpty()) {
                    attendees = if (attendees.isBlank()) {
                        selectedEmails.joinToString(", ")
                    } else {
                        attendees + ", " + selectedEmails.joinToString(", ")
                    }
                }
                showContactPicker = false
            }
        )
    }
    
    // DatePicker и TimePicker диалоги
    val pickerDateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try {
                pickerDateFormat.parse(startDateText)?.time
            } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        startDateText = pickerDateFormat.format(Date(millis))
                    }
                    showStartDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text(Strings.cancel) }
            }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try {
                pickerDateFormat.parse(endDateText)?.time
            } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        endDateText = pickerDateFormat.format(Date(millis))
                    }
                    showEndDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text(Strings.cancel) }
            }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showStartTimePicker) {
        val initHour = try { startTimeText.split(":")[0].toInt() } catch (_: Exception) { 9 }
        val initMinute = try { startTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val timePickerState = rememberTimePickerState(initialHour = initHour, initialMinute = initMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showStartTimePicker = false }
        ) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = timePickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showStartTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            startTimeText = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                            showStartTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
    
    if (showEndTimePicker) {
        val initHour = try { endTimeText.split(":")[0].toInt() } catch (_: Exception) { 10 }
        val initMinute = try { endTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val timePickerState = rememberTimePickerState(initialHour = initHour, initialMinute = initMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showEndTimePicker = false }
        ) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = timePickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEndTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            endTimeText = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                            showEndTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
}

/**
 * Парсит дату и время из текстовых полей
 * @param dateText дата в формате "дд.мм.гггг"
 * @param timeText время в формате "чч:мм"
 * @return timestamp в миллисекундах или 0 при ошибке
 */
private fun parseDateTime(dateText: String, timeText: String): Long {
    return try {
        // НЕ устанавливаем UTC - SimpleDateFormat.parse() автоматически возвращает UTC timestamp
        val dateTimeString = "$dateText $timeText"
        dateTimeFormat().parse(dateTimeString)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}

