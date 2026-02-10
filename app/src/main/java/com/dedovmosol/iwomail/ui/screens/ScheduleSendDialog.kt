package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import java.util.*

/**
 * Диалог выбора времени отложенной отправки письма
 */
@Composable
fun ScheduleSendDialog(
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit
) {
    val context = LocalContext.current
    var showCustomPicker by remember { mutableStateOf(false) }
    var customDate by remember { mutableStateOf(Calendar.getInstance()) }
    
    // Кэшируем Calendar и варианты времени для оптимизации recomposition
    val calendar = remember { Calendar.getInstance() }
    
    // Варианты времени (вычисляются один раз)
    val tomorrowMorning = remember {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
    }
    
    val tomorrowAfternoon = remember {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 13)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
    }
    
    val mondayMorning = remember {
        Calendar.getInstance().apply {
            // Находим следующий понедельник
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.WEEK_OF_YEAR, 1)
            }
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
    }
    
    val dateFormat = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    val fullDateFormat = remember { java.text.SimpleDateFormat("d MMM yyyy, HH:mm:ss", java.util.Locale.getDefault()) }
    
    if (showCustomPicker) {
        // Отдельные state для редактирования времени
        var hourText by remember { mutableStateOf(String.format("%02d", customDate.get(Calendar.HOUR_OF_DAY))) }
        var minuteText by remember { mutableStateOf(String.format("%02d", customDate.get(Calendar.MINUTE))) }
        var secondText by remember { mutableStateOf(String.format("%02d", customDate.get(Calendar.SECOND))) }
        
        // Диалог выбора кастомной даты и времени
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showCustomPicker = false },
            title = { Text(Strings.selectDateTime) },
            text = {
                Column {
                    // Дата
                    OutlinedTextField(
                        value = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(customDate.time),
                        onValueChange = { },
                        label = { Text(Strings.date) },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                // Показываем DatePicker через Android API
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        customDate = (customDate.clone() as Calendar).apply {
                                            set(Calendar.YEAR, year)
                                            set(Calendar.MONTH, month)
                                            set(Calendar.DAY_OF_MONTH, day)
                                        }
                                    },
                                    customDate.get(Calendar.YEAR),
                                    customDate.get(Calendar.MONTH),
                                    customDate.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }) {
                                Icon(AppIcons.DateRange, Strings.selectDate)
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Время (часы:минуты:секунды)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { value ->
                                // Разрешаем только цифры и максимум 2 символа
                                if (value.length <= 2 && value.all { it.isDigit() }) {
                                    hourText = value
                                    value.toIntOrNull()?.let { hour ->
                                        if (hour in 0..23) {
                                            customDate = (customDate.clone() as Calendar).apply {
                                                set(Calendar.HOUR_OF_DAY, hour)
                                            }
                                        }
                                    }
                                }
                            },
                            label = { Text(Strings.hour) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = minuteText,
                            onValueChange = { value ->
                                if (value.length <= 2 && value.all { it.isDigit() }) {
                                    minuteText = value
                                    value.toIntOrNull()?.let { minute ->
                                        if (minute in 0..59) {
                                            customDate = (customDate.clone() as Calendar).apply {
                                                set(Calendar.MINUTE, minute)
                                            }
                                        }
                                    }
                                }
                            },
                            label = { Text(Strings.minute) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = secondText,
                            onValueChange = { value ->
                                if (value.length <= 2 && value.all { it.isDigit() }) {
                                    secondText = value
                                    value.toIntOrNull()?.let { second ->
                                        if (second in 0..59) {
                                            customDate = (customDate.clone() as Calendar).apply {
                                                set(Calendar.SECOND, second)
                                            }
                                        }
                                    }
                                }
                            },
                            label = { Text(Strings.second) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        "${Strings.send}: ${fullDateFormat.format(customDate.time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { 
                        onSchedule(customDate.timeInMillis)
                    },
                    enabled = customDate.timeInMillis > System.currentTimeMillis()
                ) {
                    Text(Strings.schedule)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPicker = false }) {
                    Text(Strings.back)
                }
            }
        )
    } else {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Strings.scheduleSend) },
            text = {
                Column {
                    // Завтра утром
                    ScheduleOption(
                        icon = AppIcons.WbSunny,
                        title = Strings.tomorrowMorning,
                        subtitle = dateFormat.format(tomorrowMorning.time),
                        onClick = { onSchedule(tomorrowMorning.timeInMillis) }
                    )
                    
                    // Завтра днём
                    ScheduleOption(
                        icon = AppIcons.LightMode,
                        title = Strings.tomorrowAfternoon,
                        subtitle = dateFormat.format(tomorrowAfternoon.time),
                        onClick = { onSchedule(tomorrowAfternoon.timeInMillis) }
                    )
                    
                    // В понедельник утром
                    ScheduleOption(
                        icon = AppIcons.DateRange,
                        title = Strings.mondayMorning,
                        subtitle = dateFormat.format(mondayMorning.time),
                        onClick = { onSchedule(mondayMorning.timeInMillis) }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Выбрать дату и время
                    ScheduleOption(
                        icon = AppIcons.EditCalendar,
                        title = Strings.selectDateTime,
                        subtitle = Strings.specifyExactTime,
                        onClick = { showCustomPicker = true }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        "${Strings.timezone}: ${TimeZone.getDefault().displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(Strings.cancel)
                }
            }
        )
    }
}

@Composable
private fun ScheduleOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
