package com.dedovmosol.iwomail.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.sync.SyncAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Виджет почтового клиента
 */
class MailWidget : GlanceAppWidget() {

    // Responsive — 3 фиксированных размера вместо SizeMode.Exact.
    // SizeMode.Exact использует RemoteViews(Map<SizeF,RemoteViews>) на API 31+,
    // что вызывает краш лаунчера на HyperOS 2.0 (баг в обработке size-map).
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 140.dp),
            DpSize(180.dp, 210.dp),
            DpSize(320.dp, 280.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            val data = withContext(Dispatchers.IO) { loadWidgetData(context) }
            provideContent { MailWidgetContent(data, context) }
        } catch (e: Exception) {
            // Fallback при любой ошибке
            provideContent {
                MailWidgetContent(WidgetData(emptyList(), null, false), context)
            }
        }
    }

    private suspend fun loadWidgetData(context: Context): WidgetData {
        return try {
            val db = MailDatabase.getInstance(context)
            
            val accounts = db.accountDao().getAllAccountsSync()
            val unreadCounts = db.emailDao().getUnreadCountsByAccount()
            val unreadByAccountId = unreadCounts.associate { it.accountId to it.unreadCount }
            val accountsWithUnread = accounts.map { account ->
                val unread = unreadByAccountId[account.id] ?: 0
                AccountUnread(account.id, account.displayName, account.color, unread)
            }
            
            // Ближайшее событие (по всем аккаунтам)
            val now = System.currentTimeMillis()
            val nextEvent = db.calendarEventDao().getNextEventGlobalSync(now)
            
            val eventInfo = nextEvent?.let {
                val timeFormat = DateFormat.getTimeFormat(context)
                val startStr = timeFormat.format(Date(it.startTime))
                val endStr = if (it.endTime > it.startTime) timeFormat.format(Date(it.endTime)) else ""
                EventInfo(it.subject, startStr, endStr, it.location)
            }
            
            // Дата и день недели
            val dateStr = formatTodayDate(context)
            
            // Задачи на сегодня
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val endOfDay = cal.timeInMillis
            val todayTasksCount = db.taskDao().getTodayTasksCountGlobal(endOfDay)
            val activeTasksCount = db.taskDao().getActiveTasksCountGlobal()
            val nextTask = db.taskDao().getNextTaskGlobalSync()
            val nextTaskTitle = nextTask?.subject?.take(30) ?: ""
            
            // Последние письма из Inbox
            val recentEmails = db.emailDao().getRecentUnreadInboxGlobal(3).map { email ->
                RecentEmail(
                    id = email.id,
                    sender = email.fromName.ifBlank { email.from },
                    preview = email.preview.ifBlank { email.subject.ifBlank { "(no subject)" } },
                    folderId = email.folderId,
                    dateReceived = email.dateReceived
                )
            }
            
            // Общее число непрочитанных
            val totalUnread = unreadCounts.sumOf { it.unreadCount }
            
            // Заметки
            val notesCount = db.noteDao().getNotesCountGlobal()
            val calendarEventsCount = db.calendarEventDao().getEventsCountGlobal()
            
            // Текущая тема → градиент
            val settingsRepo = SettingsRepository.getInstance(context)
            val themeCode = settingsRepo.getCurrentThemeSync()
            val gradientRes = themeToGradient(themeCode)
            
            // Время последней синхронизации
            val lastSyncTime = settingsRepo.getLastSyncTimeSync()
            val lastSyncStr = formatSyncAgo(context, lastSyncTime)
            
            val themeColor = themeToColor(themeCode)
            
            WidgetData(accountsWithUnread, eventInfo, accounts.isNotEmpty(), dateStr, todayTasksCount, activeTasksCount, recentEmails, gradientRes, totalUnread, notesCount, lastSyncStr, calendarEventsCount, nextTaskTitle, themeColor)
        } catch (e: Exception) {
            WidgetData(emptyList(), null, false, "", 0, 0, emptyList())
        }
    }
    
    private fun themeToGradient(themeCode: String): Int = when (themeCode) {
        "blue" -> R.drawable.widget_gradient_blue
        "yellow" -> R.drawable.widget_gradient_yellow
        "green" -> R.drawable.widget_gradient_green
        else -> R.drawable.widget_gradient_purple
    }
    
    private fun themeToColor(themeCode: String): Int = when (themeCode) {
        "blue" -> 0xFF1565C0.toInt()
        "yellow" -> 0xFFC77700.toInt()
        "green" -> 0xFF2E7D32.toInt()
        else -> 0xFF5C00D4.toInt()
    }
    
    private fun formatSyncAgo(context: Context, lastSyncMillis: Long): String {
        if (lastSyncMillis == 0L) return ""
        val diff = System.currentTimeMillis() - lastSyncMillis
        if (diff < 0 || diff < 60_000) return context.getString(R.string.widget_synced_just_now)
        // Для всех остальных случаев — точное время синхронизации
        val timeFormat = DateFormat.getTimeFormat(context)
        val timeStr = timeFormat.format(Date(lastSyncMillis))
        val locale = context.resources.configuration.locales[0]
        val isRu = locale.language == "ru"
        return if (isRu) "в $timeStr" else "at $timeStr"
    }
    
    private fun formatTodayDate(context: Context): String {
        val locale = context.resources.configuration.locales[0]
        val cal = Calendar.getInstance()
        val dayOfWeek = java.text.SimpleDateFormat("EE", locale).format(cal.time)
            .replaceFirstChar { it.uppercase() }
        val dayMonth = java.text.SimpleDateFormat("d MMMM", locale).format(cal.time)
        return "$dayOfWeek, $dayMonth"
    }
}

data class AccountUnread(val id: Long, val name: String, val color: Int, val unreadCount: Int)
data class EventInfo(val title: String, val time: String, val endTime: String = "", val location: String = "")
data class RecentEmail(val id: String, val sender: String, val preview: String, val folderId: String = "", val dateReceived: Long = 0L)
data class WidgetData(
    val accounts: List<AccountUnread>,
    val nextEvent: EventInfo?,
    val hasAccount: Boolean,
    val todayDate: String = "",
    val todayTasksCount: Int = 0,
    val activeTasksCount: Int = 0,
    val recentEmails: List<RecentEmail> = emptyList(),
    val gradientRes: Int = R.drawable.widget_gradient_purple,
    val totalUnread: Int = 0,
    val notesCount: Int = 0,
    val lastSyncStr: String = "",
    val calendarEventsCount: Int = 0,
    val nextTaskTitle: String = "",
    val themeColor: Int = 0xFF5C00D4.toInt()
)

private val searchBg = Color.White
private val searchHint = Color(0xFF757575)
private val textWhite = Color.White
private val textWhiteAlpha = Color(0xCCFFFFFF)
private val darkBg = Color(0xE6181828)
private val textLight = Color(0xFFEEEEEE)
private val textLightAlpha = Color(0xB3EEEEEE)
private val textDimmed = Color(0x80EEEEEE)
private val accentBlue = Color(0xFF448AFF)

@Composable
private fun MailWidgetContent(data: WidgetData, context: Context) {
    // Внешний Box — скруглённые углы, сплошной фон темы.
    // ImageProvider как background создаёт FrameLayout+ImageView в RemoteViews,
    // что крашит лаунчер HyperOS 2.0. ColorProvider = простой setBackgroundColor().
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(ColorProvider(Color(data.themeColor)))
    ) {
        if (!data.hasAccount) {
            NoAccountView(context)
        } else {
            FullWidgetView(data, context)
        }
    }
}

@Composable
private fun NoAccountView(context: Context) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(16.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = context.getString(R.string.widget_add_account),
            style = TextStyle(color = ColorProvider(textWhite), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun FullWidgetView(data: WidgetData, context: Context) {
    val size = LocalSize.current
    val isLarge = size.height >= 200.dp
    // Масштабирование текста: адаптивное — маленький по умолчанию, растёт при растягивании
    val baseHeight = 300f
    val scale = (size.height.value / baseHeight).coerceIn(0.8f, 1.35f)
    val dateFontSize = (12 * scale).sp
    val primaryFontSize = (11 * scale).sp
    val secondaryFontSize = (10 * scale).sp
    val iconSize = (16 * scale).dp
    val iconGap = (4 * scale).dp
    val btnColor = Color(data.themeColor)
    
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // ═══════════════════════════════════════════════
        // ВЕРХНЯЯ ЧАСТЬ — градиентный фон (растягивается при ресайзе)
        // ═══════════════════════════════════════════════
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
        ) {
            // Строка поиска (белая pill)
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .cornerRadius(22.dp)
                    .background(ColorProvider(searchBg))
                    .clickable(actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            setData(android.net.Uri.parse("iwomail://widget/search"))
                            putExtra("search", true)
                        }
                    ))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = GlanceModifier.size(18.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(searchHint))
                    )
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Text(
                        text = context.getString(R.string.widget_search_mail),
                        style = TextStyle(color = ColorProvider(searchHint), fontSize = 14.sp)
                    )
                }
            }
            
            Spacer(modifier = GlanceModifier.defaultWeight())
            
            // Дата + непрочитанные
            if (data.todayDate.isNotBlank()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = data.todayDate,
                        style = TextStyle(
                            color = ColorProvider(textWhite),
                            fontSize = dateFontSize,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (data.totalUnread > 0) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Text(
                            text = "${data.totalUnread} ${context.getString(R.string.widget_unread)}",
                            style = TextStyle(
                                color = ColorProvider(textWhiteAlpha),
                                fontSize = primaryFontSize,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
            
            Spacer(modifier = GlanceModifier.defaultWeight())
            
            // Календарь
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            setData(android.net.Uri.parse("iwomail://widget/calendar"))
                            putExtra("calendar", true)
                        }
                    )),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_calendar_month),
                    contentDescription = null,
                    modifier = GlanceModifier.size(iconSize),
                    colorFilter = ColorFilter.tint(ColorProvider(textWhiteAlpha))
                )
                Spacer(modifier = GlanceModifier.width(iconGap))
                if (data.nextEvent != null) {
                    Text(
                        text = "${context.getString(R.string.widget_calendar)}: ${data.calendarEventsCount}",
                        style = TextStyle(color = ColorProvider(textWhiteAlpha), fontSize = primaryFontSize)
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = "• ${data.nextEvent.time} ${data.nextEvent.title}",
                        style = TextStyle(color = ColorProvider(textDimmed), fontSize = secondaryFontSize),
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = context.getString(R.string.widget_no_events_today),
                        style = TextStyle(color = ColorProvider(textDimmed), fontSize = primaryFontSize)
                    )
                }
            }
            
            Spacer(modifier = GlanceModifier.defaultWeight())
            
            // Задачи
            val taskCount = if (data.todayTasksCount > 0) data.todayTasksCount else data.activeTasksCount
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            setData(android.net.Uri.parse("iwomail://widget/tasks"))
                            putExtra("tasks", true)
                        }
                    )),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_task),
                    contentDescription = null,
                    modifier = GlanceModifier.size(iconSize),
                    colorFilter = ColorFilter.tint(ColorProvider(textWhiteAlpha))
                )
                Spacer(modifier = GlanceModifier.width(iconGap))
                if (taskCount > 0) {
                    Text(
                        text = "${context.getString(R.string.widget_tasks)}: $taskCount",
                        style = TextStyle(color = ColorProvider(textWhiteAlpha), fontSize = primaryFontSize)
                    )
                    if (data.nextTaskTitle.isNotBlank()) {
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Text(
                            text = "• ${data.nextTaskTitle}",
                            style = TextStyle(color = ColorProvider(textDimmed), fontSize = secondaryFontSize),
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = context.getString(R.string.widget_no_tasks),
                        style = TextStyle(color = ColorProvider(textDimmed), fontSize = primaryFontSize)
                    )
                }
            }
            
            Spacer(modifier = GlanceModifier.defaultWeight())
            
            // Заметки
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            setData(android.net.Uri.parse("iwomail://widget/notes"))
                            putExtra("notes", true)
                        }
                    )),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_sticky_note),
                    contentDescription = null,
                    modifier = GlanceModifier.size(iconSize),
                    colorFilter = ColorFilter.tint(ColorProvider(textWhiteAlpha))
                )
                Spacer(modifier = GlanceModifier.width(iconGap))
                if (data.notesCount > 0) {
                    Text(
                        text = "${context.getString(R.string.widget_notes)}: ${data.notesCount}",
                        style = TextStyle(color = ColorProvider(textWhiteAlpha), fontSize = primaryFontSize)
                    )
                } else {
                    Text(
                        text = context.getString(R.string.widget_no_notes),
                        style = TextStyle(color = ColorProvider(textDimmed), fontSize = primaryFontSize)
                    )
                }
            }
        }
        
        // ═══════════════════════════════════════════════
        // НИЖНЯЯ ЧАСТЬ — тёмный полупрозрачный блок (компактный, внизу)
        // ═══════════════════════════════════════════════
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(darkBg))
                .cornerRadius(24.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Последние письма (при большом виджете)
            val emailSenderSize = (12 * scale).sp
            val emailPreviewSize = (11 * scale).sp
            if (isLarge && data.recentEmails.isNotEmpty()) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(ColorProvider(Color(0x0AFFFFFF)))
                        .cornerRadius(12.dp)
                ) {
                    val evenRowBg = Color(0x30FFFFFF)
                    val oddRowBg = Color(0x1AFFFFFF)
                    data.recentEmails.forEachIndexed { index, email ->
                        val rowBg = if (index % 2 == 0) evenRowBg else oddRowBg
                        val emailDateStr = if (email.dateReceived > 0) {
                            java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(email.dateReceived))
                        } else ""
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .background(ColorProvider(rowBg))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .clickable(actionStartActivity(
                                    Intent(context, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        this.data = android.net.Uri.parse("iwomail://email/${email.id}")
                                        putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, email.id)
                                    }
                                )),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (emailDateStr.isNotBlank()) "${email.sender} ($emailDateStr)" else email.sender,
                                style = TextStyle(
                                    color = ColorProvider(textLight),
                                    fontSize = (10 * scale).sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Text(
                                text = email.preview,
                                style = TextStyle(color = ColorProvider(textLightAlpha), fontSize = emailPreviewSize),
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = GlanceModifier.height(6.dp))
            } else if (isLarge) {
                Text(
                    text = context.getString(R.string.widget_no_new_mail),
                    style = TextStyle(color = ColorProvider(textLightAlpha), fontSize = secondaryFontSize)
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
            
            // Аккаунты + время синхронизации + кнопки-иконки
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Аватары аккаунтов (слева)
                data.accounts.take(4).forEach { acc ->
                    AccountAvatar(acc, context)
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }
                
                // Время последней синхронизации
                if (data.lastSyncStr.isNotBlank()) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = data.lastSyncStr,
                        style = TextStyle(
                            color = ColorProvider(textDimmed),
                            fontSize = secondaryFontSize
                        )
                    )
                }
                
                Spacer(modifier = GlanceModifier.defaultWeight())
                
                // Кнопка "Написать письмо" (иконка)
                val iconBtnSize = (32 * scale).dp
                Box(
                    modifier = GlanceModifier
                        .size(iconBtnSize)
                        .background(ColorProvider(btnColor))
                        .cornerRadius(iconBtnSize / 2)
                        .clickable(actionStartActivity(
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                setData(android.net.Uri.parse("iwomail://widget/compose"))
                                putExtra("compose", true)
                            }
                        )),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_edit),
                        contentDescription = null,
                        modifier = GlanceModifier.size(iconSize),
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White))
                    )
                }
                
                Spacer(modifier = GlanceModifier.width(8.dp))
                
                // Кнопка синхронизации (иконка)
                Box(
                    modifier = GlanceModifier
                        .size(iconBtnSize)
                        .background(ColorProvider(btnColor))
                        .cornerRadius(iconBtnSize / 2)
                        .clickable(actionSendBroadcast(
                            Intent(SyncAlarmReceiver.ACTION_SYNC_NOW).apply {
                                setClass(context, SyncAlarmReceiver::class.java)
                            }
                        )),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_sync),
                        contentDescription = null,
                        modifier = GlanceModifier.size(iconSize),
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White))
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountAvatar(account: AccountUnread, context: Context) {
    val accountColor = try {
        Color(account.color)
    } catch (_: Exception) {
        Color(0xFF6200EE)
    }
    
    Box(
        modifier = GlanceModifier
            .size(48.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    this.data = android.net.Uri.parse("iwomail://account/${account.id}")
                    putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, account.id)
                    // Если есть непрочитанные → фильтр непрочитанных, иначе → просто входящие
                    if (account.unreadCount > 0) {
                        putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
                    }
                }
            )),
        contentAlignment = Alignment.TopStart
    ) {
        // Аватар (круг) 40x40
        Box(
            modifier = GlanceModifier
                .size(40.dp)
                .cornerRadius(20.dp)
                .background(ColorProvider(accountColor)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.name.firstOrNull()?.uppercase() ?: "?",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        // Бейдж непрочитанных (справа внизу)
        if (account.unreadCount > 0) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.BottomEnd
            ) {
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(accentBlue))
                        .cornerRadius(8.dp)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (account.unreadCount > 99) "99+" else account.unreadCount.toString(),
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

class MailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MailWidget()
}

suspend fun updateMailWidget(context: Context) {
    try { MailWidget().updateAll(context) } catch (_: Exception) { }
}
