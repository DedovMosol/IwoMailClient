package com.dedovmosol.iwomail.ui.screens.emaildetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.util.formatFileSize

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentsSection(
    attachments: List<AttachmentEntity>,
    downloadingId: Long?,
    onAttachmentClick: (AttachmentEntity) -> Unit,
    onSaveClick: (AttachmentEntity) -> Unit = {},
    onSaveAsClick: (AttachmentEntity) -> Unit = {},
    onShareClick: (AttachmentEntity) -> Unit = {},
    onDragAttachment: (AttachmentEntity) -> Unit = {}
) {
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = Strings.attachmentsWithCount(attachments.size),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        attachments.forEach { attachment ->
            val isDownloading = downloadingId == attachment.id
            var showSaveMenu by remember { mutableStateOf(false) }

            Box {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .combinedClickable(
                            enabled = !isDownloading,
                            onClick = { onAttachmentClick(attachment) },
                            onLongClick = { onDragAttachment(attachment) }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.fileIconFor(attachment.displayName),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.Unspecified
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatFileSize(attachment.estimatedSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
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
                            onAttachmentClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Visibility, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.save) },
                        onClick = {
                            showSaveMenu = false
                            onSaveClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Download, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.saveAs) },
                        onClick = {
                            showSaveMenu = false
                            onSaveAsClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isRussian) "Поделиться" else "Share") },
                        onClick = {
                            showSaveMenu = false
                            onShareClick(attachment)
                        },
                        leadingIcon = { Icon(AppIcons.Share, contentDescription = null) }
                    )
                }
            }
        }
    }
}
