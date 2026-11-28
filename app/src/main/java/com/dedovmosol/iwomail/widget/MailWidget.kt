package com.dedovmosol.iwomail.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.data.security.LockPrivacy
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Виджет почтового клиента
 */
class MailWidget : GlanceAppWidget() {

    private companion object {
        /**
         * `updateAll()` only sends an event to an existing Glance session. This flow is the session's data invalidation
         * signal, so a lock/account/sync change also reloads the Room snapshot while the launcher keeps the session alive.
         */
        val refreshRequests = MutableStateFlow(0L)
    }

    // Responsive — фиксированный набор размеров вместо SizeMode.Exact (Exact пересобирает виджет на каждое изменение
    // размера и ронял лаунчер HyperOS 2.0). Лаунчер берёт наибольший размер, что помещается; раскладка по каждому —
    // widgetSpec, поэтому на любом размере ничего не обрезается. Размеров не больше 16 — предел RemoteViews(Map).
    override val sizeMode = SizeMode.Responsive(WIDGET_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId): Nothing = coroutineScope {
        val appContext = context.applicationContext
        val settings = try {
            SettingsRepository.getInstance(appContext)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("MailWidget", "Cannot open widget settings", e)
            provideContent { MailWidgetContent(EMPTY_PRIVATE_SNAPSHOT, appContext) }
        }
        // One state and one loader for the entire session, not a separate effect for each responsive size.
        val snapshot = mutableStateOf(EMPTY_PRIVATE_SNAPSHOT)
        val initialSnapshotReady = CompletableDeferred<Unit>()
        launch {
            observeWidgetSnapshots(
                appLockEnabled = settings.appLockEnabled,
                lockEnabledNow = { settings.getAppLockEnabledOrNull() == true },
                refreshRequests = refreshRequests,
                load = { hidden -> withContext(Dispatchers.IO) { loadWidgetData(appContext, hidden) } },
                publish = { data ->
                    snapshot.value = data
                    initialSnapshotReady.complete(Unit)
                }
            )
        }
        initialSnapshotReady.await()
        provideContent {
            val data = snapshot.value
            // The setter updates the in-process cache before DataStore notifies observers.
            MailWidgetContent(if (settings.getAppLockEnabledOrNull() == true) data.redact() else data, appContext)
        }
    }

    internal fun requestRefresh() {
        refreshRequests.update { it + 1L }
    }

    internal suspend fun loadWidgetData(context: Context): WidgetData {
        val hidden = try {
            val settings = SettingsRepository.getInstance(context)
            LockPrivacy.contentHidden(settings) || settings.getAppLockEnabledOrNull() == true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            true
        }
        return loadWidgetData(context, hidden)
    }

    private suspend fun loadWidgetData(context: Context, contentHidden: Boolean): WidgetData {
        val db = try {
            MailDatabase.getInstance(context)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return EMPTY_PRIVATE_SNAPSHOT
        }

        val accounts = try { db.accountDao().getAllAccountsList() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }
        val unreadCounts = try { db.emailDao().getUnreadCountsByAccount() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }
        val unreadByAccountId = unreadCounts.associate { it.accountId to it.unreadCount }
        // Одинаковые цвета (раньше новый аккаунт всегда получал синий) — различимые для показа, как в боковом меню
        val colors = com.dedovmosol.iwomail.domain.AccountColors.distinct(accounts.map { it.color })
        val accountsWithUnread = accounts.mapIndexed { i, account ->
            val unread = unreadByAccountId[account.id] ?: 0
            AccountUnread(account.id, account.displayName, colors[i], unread, account.isActive)
        }
        // Письма, события, задачи и заметки — активного аккаунта: виджет меняется вместе с выбором аккаунта. ет
        // активного (сбой при удалении) — первый, как и в приложении
        val activeId = widgetActiveAccountId(accountsWithUnread)

        val now = System.currentTimeMillis()
        val nextEvent = try {
            db.calendarEventDao().getWidgetCurrentEvent(activeId, now)
                ?: db.calendarEventDao().getWidgetNextEvent(activeId, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; null
        }
        // SET-01: при включённой блокировке виджет не показывает отправителей, темы и названия.
        val eventInfo = nextEvent?.let {
            val startStr = DateFormat.getTimeFormat(context).format(Date(it.startTime))
            EventInfo(if (contentHidden) "" else it.subject, startStr)
        }

        val dateStr = try { formatTodayDate(context) } catch (_: Exception) { "" }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfDay = cal.timeInMillis
        val todayTasksCount = try { db.taskDao().getTodayTasksCount(activeId, endOfDay) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }
        val activeTasksCount = try { db.taskDao().getWidgetActiveTasksCount(activeId) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }
        val nextTask = try {
            db.taskDao().getWidgetNextDatedTask(activeId)
                ?: db.taskDao().getWidgetFirstUndatedTask(activeId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; null
        }
        val nextTaskTitle = if (contentHidden) "" else nextTask?.subject?.take(30) ?: ""

        val noSubjectText = context.getString(R.string.widget_no_subject)
        val recentEmails = if (contentHidden) emptyList() else try {
            db.emailDao().getWidgetRecentUnreadInbox(activeId, WIDGET_MAX_MAIL_ROWS).map { email ->
                RecentEmail(
                    id = email.id,
                    sender = email.fromName.ifBlank { email.from },
                    preview = email.preview.ifBlank { email.subject.ifBlank { noSubjectText } },
                    folderId = email.folderId,
                    dateReceived = email.dateReceived
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }

        // Непрочитанные активного аккаунта; у каждого аватара — свои

        val notesCount = try { db.noteDao().getNotesCountSync(activeId) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; 0
        }
        val calendarEventsCount = try { db.calendarEventDao().getWidgetEventsCount(activeId) } catch (e: Exception) {
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
            totalUnread = unreadByAccountId[activeId] ?: 0,
            notesCount = notesCount,
            lastSyncStr = lastSyncStr,
            calendarEventsCount = calendarEventsCount,
            nextTaskTitle = nextTaskTitle,
            themeColor = themeColor,
            contentHidden = contentHidden
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

internal fun privateInboxIntent(context: Context, accountId: Long) = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    data = android.net.Uri.parse("iwomail://private-inbox/$accountId")
    putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, accountId)
    putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
}

data class AccountUnread(val id: Long, val name: String, val color: Int, val unreadCount: Int, val isActive: Boolean = false)

/**
 * Аватары в строке виджета: первые [max] по порядку аккаунтов, но активный — всегда среди них (иначе выбранный
 * аккаунт не виден): если он дальше [max], он встаёт на место последнего. Порядок остальных не меняется, поэтому
 * аватары не прыгают при выборе другого аккаунта.
 */
/** Аккаунт, чьё содержимое показывает виджет: активный, а если активного нет — первый; нет аккаунтов — -1. */
internal fun widgetActiveAccountId(accounts: List<AccountUnread>): Long =
    (accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull())?.id ?: -1L

internal fun widgetAvatars(accounts: List<AccountUnread>, max: Int): List<AccountUnread> {
    if (max <= 0) return emptyList()
    val shown = accounts.take(max)
    val active = accounts.firstOrNull { it.isActive } ?: return shown
    return if (active in shown) shown else shown.dropLast(1) + active
}
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
    val themeColor: Int = 0xFF5C00D4.toInt(),
    /** SET-01: блокировка включена — письма, названия событий и задач не показываются. */
    val contentHidden: Boolean = false
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

private val EMPTY_PRIVATE_SNAPSHOT = WidgetData(emptyList(), null, hasAccount = false, contentHidden = true)

private fun WidgetData.redact(): WidgetData = copy(
    nextEvent = nextEvent?.copy(title = ""),
    recentEmails = emptyList(),
    nextTaskTitle = "",
    contentHidden = true
)

/** Session-owned loader. Privacy changes mask the current view before any Room call can finish. */
internal suspend fun observeWidgetSnapshots(
    appLockEnabled: Flow<Boolean>,
    lockEnabledNow: () -> Boolean,
    refreshRequests: Flow<Long>,
    load: suspend (contentHidden: Boolean) -> WidgetData,
    publish: (WidgetData) -> Unit
): Unit = coroutineScope {
    val lockChanges = appLockEnabled.catch { failure ->
        android.util.Log.w("MailWidget", "Cannot read App Lock; hiding widget content", failure)
        emit(true)
    }.distinctUntilChanged()
    val commit = Mutex()
    var snapshot = EMPTY_PRIVATE_SNAPSHOT
    var generation = 0L
    var pending: Job? = null

    combine(lockChanges, refreshRequests) { locked, _ -> locked }.collect { locked ->
        val hidden = locked || lockEnabledNow()
        val version = commit.withLock {
            if (hidden && !snapshot.contentHidden) {
                snapshot = snapshot.redact()
                publish(snapshot)
            }
            ++generation
        }
        pending?.cancel()
        pending = launch {
            val loaded = try {
                load(hidden)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("MailWidget", "Refresh snapshot failed", e)
                null
            }
            commit.withLock {
                if (generation != version) return@withLock
                val candidate = loaded ?: snapshot
                snapshot = if (hidden || lockEnabledNow() || loaded == null) candidate.redact() else candidate
                publish(snapshot)
            }
        }
    }
}

private val widgetUpdateMutex = Mutex()

@Composable
internal fun MailWidgetContent(data: WidgetData, context: Context) {
    // Внешний Box — скруглённые углы, сплошной фон темы.
    // ImageProvider как background создаёт FrameLayout+ImageView в RemoteViews,
    // что крашит лаунчер HyperOS 2.0. ColorProvider = простой setBackgroundColor().
    // До Android 12 cornerRadius не работает — там скругление даёт форма-рисунок под содержимым (ImageView-ребёнок,
    // не фон-рисунок, который ронял HyperOS; HyperOS 2.0 — Android 14, у неё ветка с cornerRadius)
    val rounded = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    Box(
        modifier = if (rounded) {
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(ColorProvider(Color(data.themeColor)))
        } else {
            GlanceModifier.fillMaxSize()
        }
    ) {
        if (!rounded) {
            Image(
                provider = ImageProvider(R.drawable.widget_shape_root),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = androidx.glance.layout.ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(ColorProvider(Color(data.themeColor)))
            )
        }
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
            style = TextStyle(color = ColorProvider(widgetTextWhite), fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 2
        )
    }
}

/**
 * Нажатие на неактивный аватар: аккаунт становится активным, виджет перерисовывается с кольцом на нём. Аккаунт
 * мог быть удалён, пока виджет показывал старый снимок, — тогда ничего не меняем: иначе deactivateAll оставил бы
 * приложение без активного аккаунта.
 */
class SelectWidgetAccountAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val accountId = parameters[ACCOUNT_ID] ?: return
        val app = context.applicationContext
        try {
            withContext(Dispatchers.IO) { MailDatabase.getInstance(app).accountDao().setActiveAccountIfExists(accountId) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("MailWidget", "Select account $accountId failed", e)
        }
        // Явно, не только через наблюдатель в MailApplication: процесс мог стартовать ради этого нажатия, и
        // первое значение наблюдателя (оно пропускается) уже содержит новый активный аккаунт
        updateMailWidget(app)
    }

    companion object {
        val ACCOUNT_ID = ActionParameters.Key<Long>("accountId")
    }
}

class MailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MailWidget()
}

suspend fun updateMailWidget(context: Context) {
    val appContext = context.applicationContext
    widgetUpdateMutex.withLock {
        try {
            val widget = MailWidget()
            widget.requestRefresh()
            widget.updateAll(appContext)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("MailWidget", "Widget update failed", e)
        }
    }
}
