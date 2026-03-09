package com.dedovmosol.iwomail.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarSelectionTopBar(
    selectedCount: Int,
    showRestore: Boolean,
    showEdit: Boolean,
    deleteIsPermanent: Boolean,
    onClearSelection: () -> Unit,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount", color = Color.White) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(AppIcons.ArrowBack, Strings.cancelSelection, tint = Color.White)
            }
        },
        actions = {
            if (showRestore) {
                IconButton(onClick = onRestore) {
                    Icon(AppIcons.Restore, Strings.restore, tint = Color.White)
                }
            }
            if (showEdit) {
                IconButton(onClick = onEdit) {
                    Icon(AppIcons.EditCalendar, Strings.editEvent, tint = Color.White)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    if (deleteIsPermanent) AppIcons.DeleteForever else AppIcons.Delete,
                    Strings.delete,
                    tint = Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = Modifier.background(
            Brush.horizontalGradient(
                colors = listOf(
                    LocalColorTheme.current.gradientStart,
                    LocalColorTheme.current.gradientEnd
                )
            )
        )
    )
}
