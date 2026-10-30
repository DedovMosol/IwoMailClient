package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dedovmosol.iwomail.sync.ScheduledSend
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.util.SafeToast

/** Запланированные письма: отправить сейчас, перенести, отменить (письмо вернётся в Черновики). */
@Composable
fun ScheduledMailScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ScheduledMailViewModel = viewModel(
        factory = ScheduledMailViewModel.provideFactory(context.applicationContext as android.app.Application)
    )
    val items by viewModel.items.collectAsStateWithLifecycle()

    // Диалоги — по папке письма (переживают поворот)
    var rescheduleDir by rememberSaveable { mutableStateOf<String?>(null) }
    var cancelDir by rememberSaveable { mutableStateOf<String?>(null) }

    val sendingNow by rememberUpdatedState(Strings.scheduledSendingNow)
    val rescheduled by rememberUpdatedState(Strings.scheduledRescheduled)
    val returned by rememberUpdatedState(Strings.scheduledReturnedToDrafts)
    val gone by rememberUpdatedState(Strings.scheduledAlreadySent)
    val saveFailed by rememberUpdatedState(Strings.scheduledDraftSaveFailed)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ScheduledMailEvent.SendingNow -> SafeToast.short(context, sendingNow)
                ScheduledMailEvent.Rescheduled -> SafeToast.short(context, rescheduled)
                ScheduledMailEvent.ReturnedToDrafts -> SafeToast.short(context, returned)
                ScheduledMailEvent.AlreadyGone -> SafeToast.short(context, gone)
                ScheduledMailEvent.DraftSaveFailed -> SafeToast.long(context, saveFailed)
            }
        }
    }

    val current = items.orEmpty()
    current.firstOrNull { it.dir.name == rescheduleDir }?.let { entry ->
        ScheduleSendDialog(
            onDismiss = { rescheduleDir = null },
            onSchedule = { sendAt ->
                rescheduleDir = null
                viewModel.reschedule(entry, sendAt)
            }
        )
    }
    current.firstOrNull { it.dir.name == cancelDir }?.let { entry ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { cancelDir = null },
            icon = { Icon(AppIcons.Schedule, null) },
            title = { Text(Strings.cancelSending) },
            text = { Text(Strings.cancelSendingText) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        cancelDir = null
                        viewModel.cancel(entry)
                    },
                    text = Strings.cancelSending
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(onClick = { cancelDir = null }, text = Strings.cancel)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.scheduledMail, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) { Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(listOf(LocalColorTheme.current.gradientStart, LocalColorTheme.current.gradientEnd))
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                items == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                current.isEmpty() -> Text(
                    Strings.scheduledEmpty,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> {
                    val listState = rememberLazyListState()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(current, key = { it.dir.name }) { entry ->
                            ScheduledMailRow(
                                entry = entry,
                                onSendNow = { viewModel.sendNow(entry) },
                                onReschedule = { rescheduleDir = entry.dir.name },
                                onCancel = { cancelDir = entry.dir.name }
                            )
                            HorizontalDivider()
                        }
                    }
                    LazyColumnScrollbar(listState)
                }
            }
        }
    }
}

@Composable
private fun ScheduledMailRow(
    entry: ScheduledSend.Entry,
    onSendNow: () -> Unit,
    onReschedule: () -> Unit,
    onCancel: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val time = remember(entry.sendAt) {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(entry.sendAt))
    }
    val recipients = remember(entry) {
        listOf(entry.to, entry.cc, entry.bcc).filter { it.isNotBlank() }.joinToString(", ")
    }
    ListItem(
        headlineContent = {
            Text(entry.subject.ifBlank { Strings.noSubject }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(recipients, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Strings.scheduledFor(time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        leadingContent = {
            Icon(if (entry.attachmentCount > 0) AppIcons.Attachment else AppIcons.Schedule, null)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(AppIcons.MoreVert, null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(Strings.sendNow) },
                        leadingIcon = { Icon(AppIcons.Send, null) },
                        onClick = { menuOpen = false; onSendNow() }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.changeTime) },
                        leadingIcon = { Icon(AppIcons.Schedule, null) },
                        onClick = { menuOpen = false; onReschedule() }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.cancelSending) },
                        leadingIcon = { Icon(AppIcons.Close, null) },
                        onClick = { menuOpen = false; onCancel() }
                    )
                }
            }
        }
    )
}
