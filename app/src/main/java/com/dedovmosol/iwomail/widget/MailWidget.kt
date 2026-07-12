package com.dedovmosol.iwomail.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val appContext = context.applicationContext
        try {
            val data = withContext(Dispatchers.IO) { loadWidgetData(appContext) }
            provideContent { MailWidgetContent(data, appContext) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            provideContent {
                MailWidgetContent(
                    WidgetData(
                        accounts = emptyList(),
                        nextEvent = null,
                        hasAccount = false
                    ),
                    appContext
                )
            }
        }
    }

    private suspend fun loadWidgetData(context: Context): WidgetData {
        val db = try {
            MailDatabase.getInstance(context)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return WidgetData(
                accounts = emptyList(),
                nextEvent = null,
                hasAccount = false,
                todayDate = "",
                todayTasksCount = 0,
                activeTasksCount = 0,
                recentEmails = emptyList()
            )
        }

        val accounts = try { db.accountDao().getAllAccountsList() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }
        val unreadCounts = try { db.emailDao().getUnreadCountsByAccount() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }
        val unreadByAccountId = unreadCounts.associate { it.accountId to it.unreadCount }
        val accountsWithUnread = accounts.map { account ->
            val unread = unreadByAccountId[account.id] ?: 0
            AccountUnread(account.id, account.displayName, account.color, unread)
        }

        val now = System.currentTimeMillis()
        val nextEvent = try {
            db.calendarEventDao().getCurrentEventGlobalSync(now)
                ?: db.calendarEventDao().getNextUpcomingEventGlobalSync(now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; null
        }
        val eventInfo = nextEvent?.let {
            val startStr = DateFormat.getTimeFormat(context).format(Date(it.startTime))
            EventInfo(it.subject, startStr)
        }

        val dateStr = try { formatTodayDate(context) } catch (_: Exception) { "" }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfDay = cal.timeInMillis
        val todayTasksCount = try { db.taskDao().getTodayTasksCountGlobal(endOfDay) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }
        val activeTasksCount = try { db.taskDao().getActiveTasksCountGlobal() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }
        val nextTask = try {
            db.taskDao().getNextDatedTaskGlobalSync()
                ?: db.taskDao().getFirstUndatedTaskGlobalSync()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; null
        }
        val nextTaskTitle = nextTask?.subject?.take(30) ?: ""

        val recentEmails = try {
            db.emailDao().getRecentUnreadInboxGlobal(3).map { email ->
                RecentEmail(
                    id = email.id,
                    sender = email.fromName.ifBlank { email.from },
                    preview = email.preview.ifBlank { email.subject.ifBlank { "(no subject)" } },
                    folderId = email.folderId,
                    dateReceived = email.dateReceived
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }

        val totalUnread = unreadCounts.sumOf { it.unreadCount }

        val notesCount = try { db.noteDao().getNotesCountGlobal() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }
        val calendarEventsCount = try { db.calendarEventDao().getEventsCountGlobal() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }

        val settingsRepo = SettingsRepository.getInstance(context)
        val themeCode = try { settingsRepo.getCurrentThemeSync() } catch (_: Exception) { "purple" }
        val lastSyncTime = try { settingsRepo.getLastSyncTimeSync() } catch (_: Exception) { 0L }
        val lastSyncStr = formatSyncAgo(context, lastSyncTime)
        val themeColor = themeToColor(themeCode)

        return WidgetData(
            accounts = accountsWithUnread,
            nextEvent = eventInfo,
            hasAccount = accounts.isNotEmpty(),
            todayDate = dateStr,
            todayTasksCount = todayTasksCount,
            activeTasksCount = activeTasksCount,
            recentEmails = recentEmails,
            totalUnread = totalUnread,
            notesCount = notesCount,
            lastSyncStr = lastSyncStr,
            calendarEventsCount = calendarEventsCount,
            nextTaskTitle = nextTaskTitle,
            themeColor = themeColor
        )
    }
    
    // DRY: единый источник цветов тем — AppColorTheme (Theme.kt). Раньше hex дублировались
    // здесь и в Theme.kt (риск дрейфа при смене палитры). fromCode() даёт PURPLE по умолчанию —
    // совпадает с прежней веткой else. toArgb() — точный round-trip sRGB для этих значений.
    private fun themeToColor(themeCode: String): Int =
        AppColorTheme.fromCode(themeCode).gradientStart.toArgb()
    
    private fun formatSyncAgo(context: Context, lastSyncMillis: Long): String {
        if (lastSyncMillis == 0L) return ""
        val now = System.currentTimeMillis()
        val locale = context.resources.configuration.locales[0]
        // Context-зависимые части (строка ресурса, системный формат времени 12/24ч, локаль) —
        // здесь; ветвление «сегодня/не-сегодня/только что» — в чистой formatSyncLabel (под тестом).
        // dateText/timeText вычисляются заранее: виджет рендерится редко, стоимость двух format() мала.
        return formatSyncLabel(
            lastSyncMillis = lastSyncMillis,
            nowMillis = now,
            sameLocalDay = isSameLocalDay(lastSyncMillis, now),
            isRussian = locale.language == "ru",
            justNowText = context.getString(R.string.widget_synced_just_now),
            dateText = java.text.SimpleDateFormat("dd.MM", locale).format(Date(lastSyncMillis)),
            timeText = DateFormat.getTimeFormat(context).format(Date(lastSyncMillis))
        )
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
data class EventInfo(val title: String, val time: String)
data class RecentEmail(val id: String, val sender: String, val preview: String, val folderId: String = "", val dateReceived: Long = 0L)
data class WidgetData(
    val accounts: List<AccountUnread>,
    val nextEvent: EventInfo?,
    val hasAccount: Boolean,
    val todayDate: String = "",
    val todayTasksCount: Int = 0,
    val activeTasksCount: Int = 0,
    val recentEmails: List<RecentEmail> = emptyList(),
    val totalUnread: Int = 0,
    val notesCount: Int = 0,
    val lastSyncStr: String = "",
    val calendarEventsCount: Int = 0,
    val nextTaskTitle: String = "",
    val themeColor: Int = 0xFF5C00D4.toInt()
)

/**
 * true, если оба момента приходятся на один и тот же календарный день в текущей таймзоне.
 * Вынесено top-level `internal` — чистая функция (только [Calendar]), покрыта юнит-тестом.
 * Используется для «сегодня → время, иначе → дата» в метке синка и списке последних писем.
 */
internal fun isSameLocalDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/**
 * Чистая логика метки «время последнего синка» (тестируемо, без [Context]).
 * Строки ([justNowText]/[dateText]/[timeText]) и флаги locale вычисляются вызывающим кодом.
 *  - `""` при отсутствии синка (0);
 *  - [justNowText] при diff < 60с или отрицательном (часы уехали назад);
 *  - [dateText] («dd.MM») для НЕ-сегодняшнего синка (иначе «14:30» вчера выглядит как сегодня);
 *  - «в HH:MM»/«at HH:MM» ([timeText]) для сегодняшнего.
 */
internal fun formatSyncLabel(
    lastSyncMillis: Long,
    nowMillis: Long,
    sameLocalDay: Boolean,
    isRussian: Boolean,
    justNowText: String,
    dateText: String,
    timeText: String
): String {
    if (lastSyncMillis == 0L) return ""
    val diff = nowMillis - lastSyncMillis
    if (diff < 0 || diff < 60_000) return justNowText
    if (!sameLocalDay) return dateText
    return if (isRussian) "в $timeText" else "at $timeText"
}

private val searchBg = Color.White
private val searchHint = Color(0xFF757575)
private val textWhite = Color.White
private val textWhiteAlpha = Color(0xCCFFFFFF)
private val darkBg = Color(0xE6181828)
private val textLight = Color(0xFFEEEEEE)
private val textLightAlpha = Color(0xB3EEEEEE)
private val textDimmed = Color(0x80EEEEEE)
private val accentBlue = Color(0xFF448AFF)
private val widgetUpdateMutex = Mutex()

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
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                    // Форматтеры создаём один раз вне цикла (было — до 3× на рендер).
                    val nowMillis = System.currentTimeMillis()
                    val emailTimeFmt = DateFormat.getTimeFormat(context)
                    val emailDayFmt = java.text.SimpleDateFormat("dd.MM", Locale.getDefault())
                    data.recentEmails.forEachIndexed { index, email ->
                        val rowBg = if (index % 2 == 0) evenRowBg else oddRowBg
                        // Сегодняшнее письмо → время (полезнее для свежей почты), старое → дата.
                        val emailDateStr = when {
                            email.dateReceived <= 0 -> ""
                            isSameLocalDay(email.dateReceived, nowMillis) -> emailTimeFmt.format(Date(email.dateReceived))
                            else -> emailDayFmt.format(Date(email.dateReceived))
                        }
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .background(ColorProvider(rowBg))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .clickable(actionStartActivity(
                                    Intent(context, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                // Аватары аккаунтов (слева). Glance Row → горизонтальный LinearLayout без переноса:
                // на узком виджете 4 аватара + текст синка + 2 кнопки не влезают по ширине и обрезаются
                // (кнопка синка уходит за край). Поэтому на узких размерах — меньше аватаров и без метки
                // синхронизации; сами аватары масштабируем вместе с остальным UI (было — фикс. 48dp).
                val isWide = size.width >= 300.dp
                val maxAvatars = if (isWide) 4 else 2
                data.accounts.take(maxAvatars).forEach { acc ->
                    AccountAvatar(acc, context, scale)
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }

                // Время синка (только на широком) — ГИБКИЙ элемент: забирает свободное место между
                // аватарами и кнопками и усекается по ширине (maxLines=1), а НЕ выталкивает кнопки за
                // край при любой локали/длине метки (Row → LinearLayout без переноса). Иначе — распорка.
                if (isWide && data.lastSyncStr.isNotBlank()) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = data.lastSyncStr,
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(textDimmed),
                            fontSize = secondaryFontSize
                        )
                    )
                } else {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }

                // Кнопка "Написать письмо" (иконка)
                val iconBtnSize = (32 * scale).dp
                Box(
                    modifier = GlanceModifier
                        .size(iconBtnSize)
                        .background(ColorProvider(btnColor))
                        .cornerRadius(iconBtnSize / 2)
                        .clickable(actionStartActivity(
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
private fun AccountAvatar(account: AccountUnread, context: Context, scale: Float) {
    val accountColor = try {
        Color(account.color)
    } catch (_: Exception) {
        Color(0xFF6200EE)
    }
    // Масштабируем вместе с остальным UI (было — фикс. 48/40dp): на узком виджете аватары
    // ужимаются, чтобы 2 аватара + 2 кнопки гарантированно влезали в ширину без обрезки.
    val boxSize = (40 * scale).dp
    val circleSize = (34 * scale).dp
    val letterSize = (15 * scale).sp

    Box(
        modifier = GlanceModifier
            .size(boxSize)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        // Аватар (круг) — размер масштабируется вместе с виджетом
        Box(
            modifier = GlanceModifier
                .size(circleSize)
                .cornerRadius(circleSize / 2)
                .background(ColorProvider(accountColor)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.name.firstOrNull()?.uppercase() ?: "?",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = letterSize,
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
    val appContext = context.applicationContext
    widgetUpdateMutex.withLock {
        try { MailWidget().updateAll(appContext) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }
}
