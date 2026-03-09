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
import java.text.SimpleDateFormat
import java.util.*

private val PARSE_DATE_TIME_FORMAT = java.lang.ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
private val PARSE_DATE_FORMAT = java.lang.ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
private val PARSE_TIME_FORMAT = java.lang.ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

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
    
    // РџРѕР»СѓС‡Р°РµРј СЃС‚СЂРѕРєРё Р·Р°СЂР°РЅРµРµ РґР»СЏ РёСЃРїРѕР»СЊР·РѕРІР°РЅРёСЏ РІ onClick
    val invalidDateTimeText = Strings.invalidDateTime
    val endBeforeStartText = Strings.endBeforeStart
    
    // РЎРѕСЃС‚РѕСЏРЅРёСЏ РїРѕР»РµР№
    var subject by rememberSaveable { mutableStateOf(event?.subject ?: "") }
    var location by rememberSaveable { mutableStateOf(event?.location ?: "") }
    var body by rememberSaveable { mutableStateOf(event?.body ?: "") }
    var allDayEvent by rememberSaveable { mutableStateOf(event?.allDayEvent ?: false) }
    var reminder by rememberSaveable { mutableStateOf(event?.reminder ?: 15) }
    var busyStatus by rememberSaveable { mutableStateOf(event?.busyStatus ?: 2) }
    var attendees by rememberSaveable { mutableStateOf("") }
    // РўРёРї РїРѕРІС‚РѕСЂРµРЅРёСЏ: -1=РќРµС‚, 0=Daily, 1=Weekly, 2=Monthly, 5=Yearly
    var recurrenceType by rememberSaveable {
        mutableStateOf(
            if (event?.isRecurring == true && event.recurrenceRule.isNotBlank()) {
                RecurrenceHelper.parseRule(event.recurrenceRule)?.type ?: -1
            } else -1
        )
    }
    // Р”РёР°Р»РѕРі РІС‹Р±РѕСЂР° РєРѕРЅС‚Р°РєС‚РѕРІ
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    
    // Р’Р»РѕР¶РµРЅРёСЏ
    var pickedAttachments by remember { mutableStateOf(listOf<DraftAttachmentData>()) }
    var removedExistingAttachmentRefs by remember { mutableStateOf(setOf<String>()) }
    val isRussianPicker = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val maxSingleFile = 7L * 1024 * 1024 // 7 MB вЂ” Р»РёРјРёС‚ СЃРµСЂРІРµСЂР°
        val maxTotal = 10L * 1024 * 1024 // 10 MB СЃСѓРјРјР°СЂРЅРѕ
        var currentTotal = pickedAttachments.sumOf { it.data.size.toLong() }
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
                            if (isRussianPicker) "Р¤Р°Р№Р» '$name' СЃР»РёС€РєРѕРј Р±РѕР»СЊС€РѕР№ (${sizeMB} РњР‘, РјР°РєСЃ 7 РњР‘)"
                            else "File '$name' too large (${sizeMB} MB, max 7 MB)",
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (currentTotal + size > maxTotal) {
                        Toast.makeText(context,
                            if (isRussianPicker) "РџСЂРµРІС‹С€РµРЅ РѕР±С‰РёР№ Р»РёРјРёС‚ РІР»РѕР¶РµРЅРёР№ (10 РњР‘)"
                            else "Total attachment limit exceeded (10 MB)",
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    var streamExceededSingleLimit = false
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
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
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                    if (bytes == null && streamExceededSingleLimit) {
                        val sizeMB = maxSingleFile / 1024 / 1024
                        Toast.makeText(context,
                            if (isRussianPicker) "Р¤Р°Р№Р» '$name' СЃР»РёС€РєРѕРј Р±РѕР»СЊС€РѕР№ (${sizeMB} РњР‘, РјР°РєСЃ 7 РњР‘)"
                            else "File '$name' too large (${sizeMB} MB, max 7 MB)",
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (bytes != null) {
                        if (bytes.size.toLong() > maxSingleFile) {
                            val sizeMB = bytes.size / 1024 / 1024
                            Toast.makeText(context,
                                if (isRussianPicker) "Р¤Р°Р№Р» '$name' СЃР»РёС€РєРѕРј Р±РѕР»СЊС€РѕР№ (${sizeMB} РњР‘, РјР°РєСЃ 7 РњР‘)"
                                else "File '$name' too large (${sizeMB} MB, max 7 MB)",
                                Toast.LENGTH_LONG).show()
                            return@use
                        }
                        if (currentTotal + bytes.size > maxTotal) {
                            Toast.makeText(context,
                                if (isRussianPicker) "РџСЂРµРІС‹С€РµРЅ РѕР±С‰РёР№ Р»РёРјРёС‚ РІР»РѕР¶РµРЅРёР№ (10 РњР‘)"
                                else "Total attachment limit exceeded (10 MB)",
                                Toast.LENGTH_LONG).show()
                            return@use
                        }
                        currentTotal += bytes.size.toLong()
                        pickedAttachments = pickedAttachments + DraftAttachmentData(
                            name = name,
                            mimeType = mimeType,
                            data = bytes
                        )
                    }
                }
            }
        }
    }
    
    // РўРµРєСЃС‚РѕРІС‹Рµ РїРѕР»СЏ РґР»СЏ РґР°С‚ Рё РІСЂРµРјРµРЅРё
    var startDateText by rememberSaveable { mutableStateOf("") }
    var startTimeText by rememberSaveable { mutableStateOf("") }
    var endDateText by rememberSaveable { mutableStateOf("") }
    var endTimeText by rememberSaveable { mutableStateOf("") }
    
    var showReminderMenu by rememberSaveable { mutableStateOf(false) }
    var showStatusMenu by rememberSaveable { mutableStateOf(false) }
    
    // РЎРѕСЃС‚РѕСЏРЅРёСЏ РґР»СЏ DatePicker / TimePicker РґРёР°Р»РѕРіРѕРІ
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showStartTimePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndTimePicker by rememberSaveable { mutableStateOf(false) }
    
    // РРЅРёС†РёР°Р»РёР·Р°С†РёСЏ С‚РµРєСЃС‚РѕРІС‹С… РїРѕР»РµР№ РёР· СЃСѓС‰РµСЃС‚РІСѓСЋС‰РёС… РґР°С‚
    LaunchedEffect(event, initialDate) {
        // РљР РРўРР§РќРћ: РќР• СѓСЃС‚Р°РЅР°РІР»РёРІР°РµРј UTC, С‚.Рє. РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ СЂР°Р±РѕС‚Р°РµС‚ РІ LOCAL timezone
        // Р‘Р” С…СЂР°РЅРёС‚ UTC, РЅРѕ РѕС‚РѕР±СЂР°Р¶Р°РµРј РІ LOCAL
        
        if (event != null) {
            startDateText = PARSE_DATE_FORMAT.get().format(Date(event.startTime))
            startTimeText = PARSE_TIME_FORMAT.get().format(Date(event.startTime))
            endDateText = PARSE_DATE_FORMAT.get().format(Date(event.endTime))
            endTimeText = PARSE_TIME_FORMAT.get().format(Date(event.endTime))
        } else {
            // РСЃРїРѕР»СЊР·СѓРµРј С‚РµРєСѓС‰РµРµ РІСЂРµРјСЏ, РѕРєСЂСѓРіР»С‘РЅРЅРѕРµ РґРѕ СЃР»РµРґСѓСЋС‰РµРіРѕ С‡Р°СЃР°
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            
            startDateText = PARSE_DATE_FORMAT.get().format(calendar.time)
            startTimeText = PARSE_TIME_FORMAT.get().format(calendar.time)
            
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            endDateText = PARSE_DATE_FORMAT.get().format(calendar.time)
            endTimeText = PARSE_TIME_FORMAT.get().format(calendar.time)
        }
    }
    
    // Р’Р°Р»РёРґР°С†РёСЏ
    val isValid = subject.isNotBlank()
    
    val lazyListState = rememberLazyListState()
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        scrollable = false, // РћС‚РєР»СЋС‡Р°РµРј Р°РІС‚РѕСЃРєСЂРѕР»Р» РґРёР°Р»РѕРіР°
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
            // РљРѕРЅС‚РµРЅС‚ СЃ РїСЂРѕРєСЂСѓС‚РєРѕР№ + РІРёРґРёРјС‹Р№ СЃРєСЂРѕР»Р»Р±Р°СЂ
            Box(modifier = Modifier.heightIn(min = 200.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 8.dp, bottom = 16.dp)
                ) {
                item {
                    // РќР°Р·РІР°РЅРёРµ
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
                    
                    // Р’РµСЃСЊ РґРµРЅСЊ
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
                    
                    // Р”Р°С‚Р° РЅР°С‡Р°Р»Р°
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
                    // Р”Р°С‚Р° РѕРєРѕРЅС‡Р°РЅРёСЏ (РїСЂРё РїРѕРІС‚РѕСЂРµРЅРёРё вЂ” СѓС‚РѕС‡РЅСЏРµРј, С‡С‚Рѕ СЌС‚Рѕ РєРѕРЅРµС† РєР°Р¶РґРѕРіРѕ СЌРєР·РµРјРїР»СЏСЂР°)
                    val isRussianLang = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    val endLabel = if (recurrenceType != -1) {
                        if (isRussianLang) "РћРєРѕРЅС‡Р°РЅРёРµ РєР°Р¶РґРѕРіРѕ СЃРѕР±С‹С‚РёСЏ" else "End of each event"
                    } else {
                        Strings.endDate
                    }
                    Text(
                        text = endLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (recurrenceType != -1) {
                        Text(
                            text = if (isRussianLang) "РџСЂРѕРґРѕР»Р¶РёС‚РµР»СЊРЅРѕСЃС‚СЊ РєР°Р¶РґРѕРіРѕ РїРѕРІС‚РѕСЂРµРЅРёСЏ" else "Sets the duration of each occurrence",
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
                    // РњРµСЃС‚Рѕ
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text(Strings.eventLocation) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                item {
                    // РџСЂРёРіР»Р°СЃРёС‚СЊ СѓС‡Р°СЃС‚РЅРёРєРѕРІ
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
                    // РќР°РїРѕРјРёРЅР°РЅРёРµ
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
                                else -> "$reminder РјРёРЅ"
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
                    // РЎС‚Р°С‚СѓСЃ Р·Р°РЅСЏС‚РѕСЃС‚Рё
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
                    // РџРѕРІС‚РѕСЂРµРЅРёРµ вЂ” СЂР°РґРёРѕРєРЅРѕРїРєРё
                    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (isRussian) "РџРѕРІС‚РѕСЂРµРЅРёРµ" else "Repeat",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            -1 to (if (isRussian) "РќРµ РїРѕРІС‚РѕСЂСЏС‚СЊ" else "No repeat"),
                            0 to (if (isRussian) "РљР°Р¶РґС‹Р№ РґРµРЅСЊ" else "Daily"),
                            1 to (if (isRussian) "РљР°Р¶РґСѓСЋ РЅРµРґРµР»СЋ" else "Weekly"),
                            2 to (if (isRussian) "РљР°Р¶РґС‹Р№ РјРµСЃСЏС†" else "Monthly"),
                            5 to (if (isRussian) "РљР°Р¶РґС‹Р№ РіРѕРґ" else "Yearly")
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
                    // РћРїРёСЃР°РЅРёРµ
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
                    // Р’Р»РѕР¶РµРЅРёСЏ
                    val isRussianAtt = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // РЎСѓС‰РµСЃС‚РІСѓСЋС‰РёРµ РІР»РѕР¶РµРЅРёСЏ СЃ СЃРµСЂРІРµСЂР° (РїСЂРё СЂРµРґР°РєС‚РёСЂРѕРІР°РЅРёРё)
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
                                        text = if (isRussianAtt) "РўРµРєСѓС‰РёРµ РІР»РѕР¶РµРЅРёСЏ (${visibleAttachments.size})" else "Current attachments (${visibleAttachments.size})",
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
                                                contentDescription = if (isRussianAtt) "РћС‚РєСЂРµРїРёС‚СЊ" else "Detach",
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
                            Text(if (isRussianAtt) "РџСЂРёРєСЂРµРїРёС‚СЊ С„Р°Р№Р»" else "Attach file")
                        }
                        
                        if (pickedAttachments.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            pickedAttachments.forEachIndexed { index, att ->
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
                                        text = Strings.formatFileSize(att.data.size.toLong()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = {
                                            pickedAttachments = pickedAttachments.toMutableList().also { it.removeAt(index) }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.Close,
                                            contentDescription = if (isRussianAtt) "РЈРґР°Р»РёС‚СЊ" else "Remove",
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
                        // РџР°СЂСЃРёРЅРі РґР°С‚ Рё РІСЂРµРјРµРЅРё
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
                        
                        onSave(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, attendees, recurrenceType, pickedAttachments, removedExistingAttachmentRefs.toList())
                    }
                },
                text = Strings.save,
                enabled = isValid,
                isLoading = isCreating
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel,
                enabled = !isCreating
            )
        }
    )
    
    // Р”РёР°Р»РѕРі РІС‹Р±РѕСЂР° РєРѕРЅС‚Р°РєС‚РѕРІ
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
    
    // DatePicker Рё TimePicker РґРёР°Р»РѕРіРё
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
 * РџР°СЂСЃРёС‚ РґР°С‚Сѓ Рё РІСЂРµРјСЏ РёР· С‚РµРєСЃС‚РѕРІС‹С… РїРѕР»РµР№
 * @param dateText РґР°С‚Р° РІ С„РѕСЂРјР°С‚Рµ "РґРґ.РјРј.РіРіРіРі"
 * @param timeText РІСЂРµРјСЏ РІ С„РѕСЂРјР°С‚Рµ "С‡С‡:РјРј"
 * @return timestamp РІ РјРёР»Р»РёСЃРµРєСѓРЅРґР°С… РёР»Рё 0 РїСЂРё РѕС€РёР±РєРµ
 */
private fun parseDateTime(dateText: String, timeText: String): Long {
    return try {
        // РќР• СѓСЃС‚Р°РЅР°РІР»РёРІР°РµРј UTC - SimpleDateFormat.parse() Р°РІС‚РѕРјР°С‚РёС‡РµСЃРєРё РІРѕР·РІСЂР°С‰Р°РµС‚ UTC timestamp
        val dateTimeString = "$dateText $timeText"
        PARSE_DATE_TIME_FORMAT.get().parse(dateTimeString)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}

