package com.dedovmosol.iwomail.ui.screens.calendar

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.repository.CalendarRepository
import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private val markdownImageLinkPattern = Regex("""\[!\[([^\]]*)\]\(([^)]+)\)\]\(([^)]+)\)|\!\[([^\]]*)\]\(([^)]+)\)""")
private val hrefPattern = Regex("""<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
private val urlPattern = Regex("""(https?://[^\s<>"']+)""")
private val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".svg")
private val HTML_TAG_REGEX = Regex("<[^>]*>")
private val HTML_TAG_KEEP_IMG_A_REGEX = Regex("<(?!/?(img|a)\\b)[^>]*>", RegexOption.IGNORE_CASE)
private val WHITESPACE_REGEX = "\\s+".toRegex()
private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")


@Composable
internal fun EventDetailDialog(
    event: CalendarEventEntity,
    calendarRepo: CalendarRepository,
    currentUserEmail: String = "",
    onDismiss: () -> Unit,
    onComposeClick: (String) -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val attendees = remember(event.attendees) { calendarRepo.parseAttendeesFromJson(event.attendees) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    
    // cleanBody вЂ” plain text РґР»СЏ collapsed preview (РІСЃРµ HTML-С‚РµРіРё СѓР±СЂР°РЅС‹)
    // richBody  вЂ” HTML СЃ <img>/<a> РґР»СЏ expanded RichTextWithImages
    val (cleanBody, richBody) = remember(event.body) {
        fun dedup(text: String): String {
            var t = text
            val sepIdx = t.indexOf("*~*~*")
            if (sepIdx > 0) {
                val after = t.substring(sepIdx)
                val nl = after.indexOf("\n")
                if (nl > 0) t = after.substring(nl + 1)
            }
            val seen = mutableSetOf<String>()
            return t.lines().mapNotNull { line ->
                val n = line.trim().replace(WHITESPACE_REGEX, " ")
                if (n.isBlank()) null
                else if (seen.add(n.lowercase().replace(" ", ""))) n else null
            }.joinToString("\n")
        }

        fun decodeEntities(s: String) = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\r", "")
            .replace("\u00A0", " ")
            .replace("\t", " ")

        val clean = dedup(decodeEntities(event.body.replace(HTML_TAG_REGEX, "\n")))
        val rich  = dedup(decodeEntities(
            event.body.replace(HTML_TAG_KEEP_IMG_A_REGEX, "\n")
        ))
        clean to rich
    }
    
    // РР·РІР»РµРєР°РµРј email РёР· СЃС‚СЂРѕРєРё РѕСЂРіР°РЅРёР·Р°С‚РѕСЂР°
    val organizerEmail = remember(event.organizer) {
        EMAIL_REGEX.find(event.organizer)?.value ?: ""
    }
    
    // РџСЂРѕРІРµСЂСЏРµРј С‡С‚Рѕ СЏ РЅРµ РѕСЂРіР°РЅРёР·Р°С‚РѕСЂ (С‚РѕРіРґР° РїРѕРєР°Р·С‹РІР°РµРј РєРЅРѕРїРєРё РѕС‚РІРµС‚Р°)
    val isOrganizer = remember(organizerEmail, currentUserEmail) {
        organizerEmail.isNotBlank() && currentUserEmail.isNotBlank() &&
        organizerEmail.equals(currentUserEmail, ignoreCase = true)
    }
    
    // РџСЂРѕРІРµСЂСЏРµРј РµСЃС‚СЊ Р»Рё С‡С‚Рѕ РїРѕРєР°Р·С‹РІР°С‚СЊ РІ СЂР°СЃС€РёСЂРµРЅРЅРѕРј РІРёРґРµ
    val hasMoreContent = richBody.isNotBlank() || event.organizer.isNotBlank() || event.organizerName.isNotBlank() || attendees.isNotEmpty() || event.hasAttachments || event.onlineMeetingLink.isNotBlank()
    
    // Р”РёР°Р»РѕРі РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ СѓРґР°Р»РµРЅРёСЏ
    if (showDeleteConfirm) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteEvent) },
            text = { Text(Strings.deleteEventConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteConfirm = false },
                    text = Strings.no
                )
            }
        )
    }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = event.subject.ifBlank { Strings.noSubject },
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            // РќРµ РґРѕР±Р°РІР»СЏРµРј verticalScroll - ScaledAlertDialog СѓР¶Рµ РёРјРµРµС‚ СЃРєСЂРѕР»Р»
            Column {
                // Р”Р°С‚Р°/РІСЂРµРјСЏ
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        AppIcons.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (event.allDayEvent) {
                            "${dateFormat.format(Date(event.startTime))} - ${Strings.allDay}"
                        } else {
                            "${dateFormat.format(Date(event.startTime))} ${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // РџСЂР°РІРёР»Рѕ РїРѕРІС‚РѕСЂРµРЅРёСЏ
                if (event.isRecurring && event.recurrenceRule.isNotBlank()) {
                    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    val ruleDescription = RecurrenceHelper.describeRule(event.recurrenceRule, isRussian)
                    if (ruleDescription.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                AppIcons.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ruleDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // РњРµСЃС‚Рѕ
                if (event.location.isNotBlank()) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    val isUrl = event.location.startsWith("http://") || event.location.startsWith("https://")
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            if (isUrl) AppIcons.OpenInNew else AppIcons.Business,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = event.location,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = if (isUrl) {
                                Modifier.clickable { uriHandler.openUri(event.location) }
                            } else Modifier
                        )
                    }
                }
                
                // РљСЂР°С‚РєРѕРµ РѕРїРёСЃР°РЅРёРµ (СЃРІС‘СЂРЅСѓС‚С‹Р№ РІРёРґ)
                if (!expanded && cleanBody.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cleanBody.take(200) + if (cleanBody.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // РљРЅРѕРїРєР° "РџРѕРєР°Р·Р°С‚СЊ РµС‰С‘"
                if (!expanded && hasMoreContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { expanded = true }) {
                            Text(Strings.showMore)
                            Icon(
                                AppIcons.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                // Р Р°СЃС€РёСЂРµРЅРЅС‹Р№ РІРёРґ
                if (expanded) {
                    // РљРЅРѕРїРєР° "РЎРІРµСЂРЅСѓС‚СЊ" РІРІРµСЂС…Сѓ РґР»СЏ СѓРґРѕР±СЃС‚РІР°
                    TextButton(
                        onClick = { expanded = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(
                            AppIcons.ExpandLess,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(Strings.showLess)
                    }
                    
                    // РћСЂРіР°РЅРёР·Р°С‚РѕСЂ
                    if (event.organizer.isNotBlank() || event.organizerName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                AppIcons.Person,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = Strings.organizer,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val displayOrganizer = if (event.organizerName.isNotBlank()) {
                                    event.organizerName
                                } else {
                                    event.organizer.replace(HTML_TAG_REGEX, "")
                                }
                                Text(
                                    text = displayOrganizer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (organizerEmail.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = if (organizerEmail.isNotBlank()) {
                                        Modifier.clickable { onComposeClick(organizerEmail) }
                                    } else Modifier
                                )
                            }
                        }
                    }
                    
                    // РЎСЃС‹Р»РєР° РЅР° РѕРЅР»Р°Р№РЅ-РІСЃС‚СЂРµС‡Сѓ
                    if (event.onlineMeetingLink.isNotBlank()) {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .clickable { uriHandler.openUri(event.onlineMeetingLink) }
                        ) {
                            Icon(
                                AppIcons.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "РћРЅР»Р°Р№РЅ-РІСЃС‚СЂРµС‡Р°" else "Online meeting",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    // Р’Р»РѕР¶РµРЅРёСЏ
                    if (event.hasAttachments) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (event.attachments.isNotBlank()) {
                            CalendarAttachmentsList(
                                attachmentsJson = event.attachments,
                                accountId = event.accountId,
                                calendarRepo = calendarRepo
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Attachment,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN)
                                        "Р•СЃС‚СЊ РІР»РѕР¶РµРЅРёСЏ (СЃРёРЅС…СЂРѕРЅРёР·РёСЂСѓР№С‚Рµ РґР»СЏ Р·Р°РіСЂСѓР·РєРё)"
                                    else
                                        "Has attachments (sync to load details)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    // РЈС‡Р°СЃС‚РЅРёРєРё
                    if (attendees.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                AppIcons.People,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = Strings.attendees,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                attendees.forEach { attendee ->
                                    val nameOrEmail = if (attendee.name.isNotBlank()) attendee.name else attendee.email
                                    // РЎС‚Р°С‚СѓСЃ СѓС‡Р°СЃС‚РЅРёРєР°: 0=Unknown, 2=Tentative, 3=Accepted, 4=Declined, 5=Not responded
                                    val statusText = when (attendee.status) {
                                        2 -> " (${Strings.statusTentative})"
                                        3 -> " (${Strings.statusAccepted})"
                                        4 -> " (${Strings.statusDeclined})"
                                        5 -> " (${Strings.statusNotResponded})"
                                        else -> ""
                                    }
                                    val displayText = "$nameOrEmail$statusText"
                                    Text(
                                        text = displayText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (attendee.email.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = if (attendee.email.isNotBlank()) {
                                            Modifier.clickable { onComposeClick(attendee.email) }
                                        } else Modifier
                                    )
                                }
                            }
                        }
                    }
                    
                    // РџРѕР»РЅРѕРµ РѕРїРёСЃР°РЅРёРµ СЃ РёР·РѕР±СЂР°Р¶РµРЅРёСЏРјРё Рё СЃСЃС‹Р»РєР°РјРё
                    if (richBody.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        SelectionContainer {
                            com.dedovmosol.iwomail.ui.components.RichTextWithImages(
                                htmlContent = richBody,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // РЎРїСЂР°РІР°: РЈРґР°Р»РёС‚СЊ (С‚РѕР»СЊРєРѕ РёРєРѕРЅРєР° СЃ РѕР±РІРѕРґРєРѕР№)
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                ),
                border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
            ) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = Strings.delete,
                    modifier = Modifier.size(20.dp),
                    tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                )
            }
        },
        dismissButton = {
            // РЎР»РµРІР°: Р РµРґР°РєС‚РёСЂРѕРІР°С‚СЊ (С‚РѕР»СЊРєРѕ РёРєРѕРЅРєР° СЃ РѕР±РІРѕРґРєРѕР№, С†РІРµС‚ РёР· С‚РµРјС‹)
            OutlinedButton(
                onClick = onEditClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = LocalColorTheme.current.gradientStart
                )
            ) {
                Icon(
                    AppIcons.Edit,
                    contentDescription = Strings.edit,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    )
}

@Composable
private fun ClickableHtmlText(
    text: String,
    style: androidx.compose.ui.text.TextStyle
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val couldNotOpenLinkText = Strings.couldNotOpenLink
    
    data class Part(val content: String, val type: Int, val url: String = "", val linkUrl: String = "")
    
    val parts = remember(text) {
        data class Element(val start: Int, val end: Int, val imageUrl: String, val linkUrl: String, val display: String, val isImage: Boolean, val isClickableImage: Boolean = false)
        val elements = mutableListOf<Element>()
        
        markdownImageLinkPattern.findAll(text).forEach { match ->
            val imgUrl = match.groupValues[1]
            val lnkUrl = match.groupValues[2]
            val isImg = imageExtensions.any { imgUrl.lowercase().contains(it) }
            elements.add(Element(match.range.first, match.range.last + 1, imgUrl, lnkUrl, imgUrl, isImg, isImg))
        }
        
        hrefPattern.findAll(text).forEach { match ->
            val overlaps = elements.any { it.start <= match.range.first && it.end >= match.range.last }
            if (!overlaps) {
                elements.add(Element(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[1], match.groupValues[2], false))
            }
        }
        
        urlPattern.findAll(text).forEach { match ->
            val overlaps = elements.any { it.start <= match.range.first && it.end >= match.range.last }
            if (!overlaps) {
                val isImg = imageExtensions.any { match.value.lowercase().contains(it) }
                elements.add(Element(match.range.first, match.range.last + 1, match.value, match.value, match.value, isImg))
            }
        }
        
        elements.sortBy { it.start }
        
        val result = mutableListOf<Part>()
        var lastIndex = 0
        elements.forEach { elem ->
            if (elem.start > lastIndex) {
                result.add(Part(text.substring(lastIndex, elem.start), 0))
            }
            when {
                elem.isClickableImage -> result.add(Part(elem.imageUrl, 3, elem.imageUrl, elem.linkUrl))
                elem.isImage -> result.add(Part(elem.imageUrl, 2, elem.imageUrl))
                else -> result.add(Part(elem.display, 1, elem.linkUrl))
            }
            lastIndex = elem.end
        }
        if (lastIndex < text.length) {
            result.add(Part(text.substring(lastIndex), 0))
        }
        result.toList()
    }
    
    Column {
        parts.forEach { part ->
            when (part.type) {
                0 -> if (part.content.isNotBlank()) {
                    Text(text = part.content, style = style, color = onSurfaceColor)
                }
                1 -> Text(
                    text = part.content,
                    style = style.copy(color = primaryColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    modifier = Modifier.clickable {
                        try { uriHandler.openUri(part.url) }
                        catch (e: Exception) { Toast.makeText(context, couldNotOpenLinkText, Toast.LENGTH_SHORT).show() }
                    }
                )
                2 -> NetworkImage(url = part.url, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                3 -> NetworkImage(
                    url = part.url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable {
                            try { uriHandler.openUri(part.linkUrl) }
                            catch (e: Exception) { Toast.makeText(context, couldNotOpenLinkText, Toast.LENGTH_SHORT).show() }
                        }
                )
            }
        }
    }
}


@Composable
private fun NetworkImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf(false) }
    
    // РћСЃРІРѕР±РѕР¶РґР°РµРј Bitmap РїСЂРё РІС‹С…РѕРґРµ РёР· РєРѕРјРїРѕР·РёС†РёРё
    DisposableEffect(url) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }
    
    LaunchedEffect(url) {
        isLoading = true
        error = false
        try {
            bitmap = withContext(Dispatchers.IO) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.connect()
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val maxDim = 2048
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                        sampleSize *= 2
                    }
                    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                } finally {
                    conn?.disconnect()
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = true
        }
        isLoading = false
    }
    
    val safeBitmap = bitmap
    when {
        isLoading -> Box(modifier.height(100.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        !error && safeBitmap != null -> androidx.compose.foundation.Image(
            bitmap = safeBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
        )
    }
}


@Composable
internal fun DeletedEventDetailDialog(
    event: CalendarEventEntity,
    onDismiss: () -> Unit,
    onRestoreClick: () -> Unit,
    onDeletePermanentlyClick: () -> Unit
) {
    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
    val dateTimeFormat = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()) }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = event.subject.ifBlank { if (isRussian) "Р‘РµР· РЅР°Р·РІР°РЅРёСЏ" else "No title" },
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                )
            }
        },
        text = {
            Column {
                Text(
                    text = if (isRussian) "РЎРѕР±С‹С‚РёРµ РІ РєРѕСЂР·РёРЅРµ" else "Event in trash",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Р’СЂРµРјСЏ РЅР°С‡Р°Р»Р° вЂ” РѕРєРѕРЅС‡Р°РЅРёСЏ
                Text(
                    text = "${dateTimeFormat.format(Date(event.startTime))} вЂ” ${dateTimeFormat.format(Date(event.endTime))}",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // РњРµСЃС‚Рѕ
                if (event.location.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // РћРїРёСЃР°РЅРёРµ
                if (event.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = event.body.replace(HTML_TAG_REGEX, "").trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // РљРЅРѕРїРєРё: Р’РѕСЃСЃС‚Р°РЅРѕРІРёС‚СЊ / РЈРґР°Р»РёС‚СЊ РЅР°РІСЃРµРіРґР°
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRestoreClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = LocalColorTheme.current.gradientStart
                        )
                    ) {
                        Icon(
                            AppIcons.Restore,
                            contentDescription = if (isRussian) "Р’РѕСЃСЃС‚Р°РЅРѕРІРёС‚СЊ" else "Restore",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    OutlinedButton(
                        onClick = onDeletePermanentlyClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                        ),
                        border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
                    ) {
                        Icon(
                            AppIcons.DeleteForever,
                            contentDescription = if (isRussian) "РЈРґР°Р»РёС‚СЊ РЅР°РІСЃРµРіРґР°" else "Delete permanently",
                            modifier = Modifier.size(20.dp),
                            tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
