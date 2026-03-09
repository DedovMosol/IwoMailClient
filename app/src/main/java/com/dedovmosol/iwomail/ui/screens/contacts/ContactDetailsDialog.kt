package com.dedovmosol.iwomail.ui.screens.contacts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactGroupEntity
import com.dedovmosol.iwomail.data.database.ContactSource
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppColors
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog
import com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton
import com.dedovmosol.iwomail.ui.utils.getAvatarColor
import kotlinx.coroutines.launch

@Composable
internal fun ContactDetailsDialog(
    contact: ContactEntity,
    groups: List<ContactGroupEntity>,
    onDismiss: () -> Unit,
    onWriteEmail: (String) -> Unit,
    onCopyEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToContacts: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)

    val isLocal = contact.source == ContactSource.LOCAL
    val name = contact.displayName
    val email = cleanContactEmail(contact.email)
    val phone = contact.phone
    val mobilePhone = contact.mobilePhone
    val workPhone = contact.workPhone
    val company = contact.company
    val department = contact.department
    val jobTitle = contact.jobTitle
    val notes = contact.notes

    // Находим группу контакта
    val contactGroup = contact.groupId?.let { groupId ->
        groups.find { it.id == groupId }
    }

    val avatarColor = getAvatarColor(name)

    // Анимация появления
    val scale = remember { Animatable(if (animationsEnabled) 0.8f else 1f) }
    val animatedAlpha = remember { Animatable(if (animationsEnabled) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (animationsEnabled) {
            launch {
                scale.animateTo(1f, tween(200))
            }
            launch {
                animatedAlpha.animateTo(1f, tween(200))
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = animatedAlpha.value
                },
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Аватар + имя в одну строку
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Группа контакта (только для локальных контактов)
                if (isLocal) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (contactGroup != null) {
                            // Контакт в группе
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(contactGroup.color))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = contactGroup.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(contactGroup.color),
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            // Контакт без группы
                            Icon(
                                AppIcons.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (LocalLanguage.current == AppLanguage.RUSSIAN) "Без группы" else "Without group",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Email с зелёной иконкой
                if (email.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            AppIcons.Email,
                            null,
                            tint = AppColors.calendar,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Две кнопки в ряд
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Написать письмо
                        OutlinedButton(
                            onClick = { onWriteEmail(email) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    AppIcons.Send,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF2196F3)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    Strings.writeEmail,
                                    color = Color(0xFF03A9F4),
                                    maxLines = 2,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        // Копировать email
                        OutlinedButton(
                            onClick = { onCopyEmail(email) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    AppIcons.ContentCopy,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = AppColors.tasks
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    Strings.copyEmail,
                                    color = AppColors.tasks,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Кнопки действий в зависимости от типа контакта
                if (isLocal) {
                    // Для локальных контактов - редактирование и удаление
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                AppIcons.Edit,
                                contentDescription = Strings.edit,
                                tint = avatarColor,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                AppIcons.Delete,
                                contentDescription = Strings.delete,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    // Для Exchange контактов - добавить в личные контакты
                    IconButton(
                        onClick = onAddToContacts,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            AppIcons.PersonAdd,
                            contentDescription = Strings.addToContacts,
                            tint = avatarColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ContactDetailRow(
    icon: ImageVector,
    text: String,
    label: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(36.dp)) {
                Icon(
                    AppIcons.Call,
                    Strings.callPhone,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
internal fun DetailRow(
    icon: ImageVector,
    text: String,
    actions: @Composable (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text)
            actions?.invoke()
        }
    }
}

@Composable
internal fun ExportDialog(
    onDismiss: () -> Unit,
    onExportVCard: () -> Unit,
    onExportCSV: () -> Unit
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.exportContacts) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(Strings.exportToVCard) },
                    leadingContent = { Icon(AppIcons.ContactPage, null) },
                    modifier = Modifier.clickable(onClick = onExportVCard)
                )
                ListItem(
                    headlineContent = { Text(Strings.exportToCSV) },
                    leadingContent = { Icon(AppIcons.TableChart, null) },
                    modifier = Modifier.clickable(onClick = onExportCSV)
                )
            }
        },
        confirmButton = {
            ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.close
            )
        },
        dismissButton = {}
    )
}
