package com.dedovmosol.iwomail.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.sync.SyncAlarmReceiver
import java.util.Date
import java.util.Locale

internal val widgetTextWhite = Color.White
private val searchHint = Color(0xFF616161)
private val textLight = Color(0xFFEEEEEE)
private val textLightAlpha = Color(0xB3EEEEEE)
private val accentBlue = Color(0xFF2962FF)
// Те же цвета, что в рисунках widget_shape_card_dark / _light (до Android 12 фон рисуется ими)
private val widgetDarkBlock = Color(0xE6181828)
private val widgetMailList = Color(0x1FFFFFFF)

private fun openApp(context: Context, uri: String, configure: Intent.() -> Unit = {}): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        // Свой data URI у каждой цели: PendingIntent.filterEquals не смотрит extras
        data = android.net.Uri.parse(uri)
        configure()
    }
)

/**
 * Скруглённый фон по размеру содержимого (DRY: строка поиска, бейдж, карточки, корень). С Android 12 — цвет и
 * cornerRadius (так рисовалось и раньше, на HyperOS 2.0 проверено); до 12 cornerRadius не работает — фон-рисунок
 * той же формы. Ребёнок-картинка с fillMaxSize в Box без размера не годится: Glance растягивает её на всего
 * родителя, и строка поиска занимала полвиджета. [shape] — белый рисунок под тинт [color] либо уже цветной
 * ([tint] = false: тинт полупрозрачным цветом не сохранил бы прозрачность).
 */
internal fun GlanceModifier.shapeBackground(color: Color, radius: Dp, shape: Int, tint: Boolean = true): GlanceModifier =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        background(ColorProvider(color)).cornerRadius(radius)
    } else {
        background(ImageProvider(shape), ContentScale.FillBounds, if (tint) ColorFilter.tint(ColorProvider(color)) else null)
    }

/** Круг заданного размера: картинка-форма с явным размером рисуется кругом на всех версиях Android. */
@Composable
private fun ShapeFill(shape: Int, color: Color?, modifier: GlanceModifier = GlanceModifier.fillMaxSize()) {
    Image(
        provider = ImageProvider(shape),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds,
        colorFilter = color?.let { ColorFilter.tint(ColorProvider(it)) }
    )
}

@Composable
internal fun FullWidgetView(data: WidgetData, context: Context) {
    val size = LocalSize.current
    val fontScale = context.resources.configuration.fontScale
    val unreadLabel = "${data.totalUnread} ${context.getString(R.string.widget_unread)}"
    val mailLines = if (data.contentHidden) 1 else data.recentEmails.size.coerceAtLeast(1)
    val spec = widgetSpec(
        size = size,
        fontScale = fontScale,
        accounts = data.accounts.size,
        mailLines = mailLines,
        summaryChars = data.todayDate.length + if (data.totalUnread > 0) unreadLabel.length else 0
    )

    // Row и Column виджета держат не больше 10 детей, остальные Glance молча отбрасывает (документация Row/Column).
    // Поэтому верх — отдельная колонка (до 9 детей), а виджет — две: верх и нижний блок
    val top = buildList<@Composable () -> Unit> {
        if (spec.showSearch) add { SearchPill(context, spec) }
        if (spec.showSummary && data.todayDate.isNotBlank()) add { SummaryRow(data, spec, unreadLabel) }
        if (spec.infoRows >= 1) add { CalendarRow(data, spec, context) }
        if (spec.infoRows >= 2) add { TasksRow(data, spec, context) }
        if (spec.infoRows >= 3) add { NotesRow(data, spec, context) }
    }
    Column(modifier = GlanceModifier.fillMaxSize().padding(spec.padding)) {
        // Свободная высота — поровну между строками верха: на большом виджете они расходятся равномерно,
        // последняя прижата к нижнему блоку
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            top.forEachIndexed { i, row ->
                if (i > 0) Spacer(GlanceModifier.defaultWeight())
                row()
            }
        }
        Spacer(GlanceModifier.height(WIDGET_GAP))
        BottomBlock(data, spec, context)
    }
}

@Composable
private fun SearchPill(context: Context, spec: WidgetSpec) {
    Box(
        modifier = GlanceModifier.fillMaxWidth()
            .shapeBackground(Color.White, 999.dp, R.drawable.widget_shape_pill)
            .clickable(openApp(context, "iwomail://widget/search") { putExtra("search", true) }),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = SEARCH_PADDING_V.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_search),
                contentDescription = null,
                modifier = GlanceModifier.size(spec.iconSize + 2.dp),
                colorFilter = ColorFilter.tint(ColorProvider(searchHint))
            )
            Spacer(GlanceModifier.width(8.dp).height(1.dp))
            Text(
                text = context.getString(R.string.widget_search_mail),
                style = TextStyle(color = ColorProvider(searchHint), fontSize = spec.searchSp.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
        }
    }
}

@Composable
private fun SummaryRow(data: WidgetData, spec: WidgetSpec, unreadLabel: String) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = data.todayDate,
            style = TextStyle(color = ColorProvider(widgetTextWhite), fontSize = spec.dateSp.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        // Полная надпись — если помещается рядом с датой; иначе только число (у аватаров — свои числа)
        if (data.totalUnread > 0) {
            Spacer(GlanceModifier.width(8.dp).height(1.dp))
            Text(
                text = if (spec.showUnreadText) unreadLabel else data.totalUnread.toString(),
                style = TextStyle(color = ColorProvider(widgetTextWhite), fontSize = spec.textSp.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
        }
    }
}

/**
 * Строка «Календарь / Задачи / Заметки». Box — цель нажатия: у Row с Image и Text Glance ловит нажатие только на
 * пустом месте вокруг них. Широкий виджет: «название: число» и подробность, которая сокращается многоточием; узкий —
 * одна надпись на всю ширину.
 */
@Composable
private fun InfoRow(icon: Int, spec: WidgetSpec, action: Action, label: String, detail: String?, narrow: String) {
    Box(modifier = GlanceModifier.fillMaxWidth().clickable(action)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(spec.iconSize),
                colorFilter = ColorFilter.tint(ColorProvider(widgetTextWhite))
            )
            Spacer(GlanceModifier.width(6.dp).height(1.dp))
            val style = TextStyle(color = ColorProvider(widgetTextWhite), fontSize = spec.textSp.sp)
            if (spec.infoDetails && detail != null) {
                Text(text = label, style = style, maxLines = 1)
                Spacer(GlanceModifier.width(8.dp).height(1.dp))
                Text(
                    text = detail,
                    style = TextStyle(color = ColorProvider(textLight), fontSize = (spec.textSp - 1f).sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            } else {
                Text(text = if (spec.infoDetails) label else narrow, style = style, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun CalendarRow(data: WidgetData, spec: WidgetSpec, context: Context) {
    val event = data.nextEvent
    val none = context.getString(R.string.widget_no_events_today)
    val eventText = event?.let { "${it.time} ${it.title}".trim() }
    InfoRow(
        icon = R.drawable.ic_calendar_month,
        spec = spec,
        action = openApp(context, "iwomail://widget/calendar") { putExtra("calendar", true) },
        label = if (event != null) "${context.getString(R.string.widget_calendar)}: ${data.calendarEventsCount}" else none,
        detail = eventText?.let { "• $it" },
        narrow = eventText ?: none
    )
}

@Composable
private fun TasksRow(data: WidgetData, spec: WidgetSpec, context: Context) {
    val count = if (data.todayTasksCount > 0) data.todayTasksCount else data.activeTasksCount
    val label = if (count > 0) "${context.getString(R.string.widget_tasks)}: $count" else context.getString(R.string.widget_no_tasks)
    InfoRow(
        icon = R.drawable.ic_task,
        spec = spec,
        action = openApp(context, "iwomail://widget/tasks") { putExtra("tasks", true) },
        label = label,
        detail = data.nextTaskTitle.takeIf { count > 0 && it.isNotBlank() }?.let { "• $it" },
        narrow = label
    )
}

@Composable
private fun NotesRow(data: WidgetData, spec: WidgetSpec, context: Context) {
    val label = if (data.notesCount > 0) "${context.getString(R.string.widget_notes)}: ${data.notesCount}"
        else context.getString(R.string.widget_no_notes)
    InfoRow(R.drawable.ic_sticky_note, spec, openApp(context, "iwomail://widget/notes") { putExtra("notes", true) }, label, null, label)
}

@Composable
private fun BottomBlock(data: WidgetData, spec: WidgetSpec, context: Context) {
    Column(
        modifier = GlanceModifier.fillMaxWidth()
            .shapeBackground(widgetDarkBlock, 16.dp, R.drawable.widget_shape_card_dark, tint = false)
            .padding(spec.blockPadding)
    ) {
        if (spec.mailRows > 0) {
            MailRows(data, spec, context)
            Spacer(GlanceModifier.height(WIDGET_GAP))
        }
        BottomRow(data, spec, context)
    }
}

@Composable
private fun MailRows(data: WidgetData, spec: WidgetSpec, context: Context) {
    val textStyle = TextStyle(color = ColorProvider(textLight), fontSize = spec.mailSp.sp)
    val active = data.accounts.firstOrNull { it.isActive } ?: data.accounts.firstOrNull()
    when {
        // Never render individual mail rows when locked, even if a caller supplied them.
        data.contentHidden && active != null && active.unreadCount > 0 -> {
            Box(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(privateInboxIntent(context, active.id)))) {
                Text(
                    text = context.getString(R.string.widget_account_unread, active.name, active.unreadCount),
                    style = textStyle,
                    maxLines = 1,
                    modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = MAIL_ROW_PADDING_V.dp)
                )
            }
        }
        data.contentHidden || data.recentEmails.isEmpty() -> Text(
            text = context.getString(R.string.widget_no_new_mail),
            style = TextStyle(color = ColorProvider(textLightAlpha), fontSize = spec.mailSp.sp),
            maxLines = 1,
            modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = MAIL_ROW_PADDING_V.dp)
        )
        else -> Box(modifier = GlanceModifier.fillMaxWidth().shapeBackground(widgetMailList, 12.dp, R.drawable.widget_shape_card_light, tint = false)) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                val now = System.currentTimeMillis()
                val timeFmt = DateFormat.getTimeFormat(context)
                val dayFmt = java.text.SimpleDateFormat("dd.MM", Locale.getDefault())
                data.recentEmails.take(spec.mailRows).forEach { email ->
                    val time = when {
                        email.dateReceived <= 0 -> ""
                        isSameLocalDay(email.dateReceived, now) -> timeFmt.format(Date(email.dateReceived))
                        else -> dayFmt.format(Date(email.dateReceived))
                    }
                    Box(
                        modifier = GlanceModifier.fillMaxWidth()
                            .clickable(openApp(context, "iwomail://email/${email.id}") { putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, email.id) })
                    ) {
                        // Отправитель и превью делят ширину, время — всегда видно справа: длинное имя не выталкивает его
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = MAIL_ROW_PADDING_V.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = email.sender,
                                style = textStyle.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            if (spec.mailPreview) {
                                Spacer(GlanceModifier.width(6.dp).height(1.dp))
                                Text(
                                    text = email.preview,
                                    style = TextStyle(color = ColorProvider(textLightAlpha), fontSize = spec.mailSp.sp),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                            }
                            if (time.isNotEmpty()) {
                                Spacer(GlanceModifier.width(6.dp).height(1.dp))
                                Text(text = time, style = TextStyle(color = ColorProvider(textLightAlpha), fontSize = spec.mailSp.sp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomRow(data: WidgetData, spec: WidgetSpec, context: Context) {
    // Не больше 10 детей у Row: аватары — одним вложенным рядом (раньше 4 аватара с промежутками, метка и кнопки
    // давали 12 детей, и кнопка синхронизации на виджете 320 dp пропадала)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val shown = widgetAvatars(data.accounts, spec.avatars)
        if (shown.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Аватар и промежуток — 2 ребёнка на аккаунт, аватаров не больше 4 на самом широком виджете
                shown.forEach { account ->
                    AccountAvatar(account, spec.avatarSize, context)
                    Spacer(GlanceModifier.width(WIDGET_AVATAR_GAP).height(1.dp))
                }
            }
        }
        if (spec.showSyncLabel && data.lastSyncStr.isNotBlank()) {
            Text(
                text = data.lastSyncStr,
                style = TextStyle(color = ColorProvider(textLightAlpha), fontSize = spec.mailSp.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 4.dp)
            )
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        CircleButton(
            spec = spec,
            icon = R.drawable.ic_edit,
            color = Color(data.themeColor),
            description = context.getString(R.string.widget_compose),
            action = openApp(context, "iwomail://widget/compose") { putExtra("compose", true) }
        )
        Spacer(GlanceModifier.width(spec.buttonGap).height(1.dp))
        CircleButton(
            spec = spec,
            icon = R.drawable.ic_sync,
            color = Color(data.themeColor),
            description = context.getString(R.string.widget_sync_now),
            action = actionSendBroadcast(Intent(SyncAlarmReceiver.ACTION_SYNC_NOW).setClass(context, SyncAlarmReceiver::class.java))
        )
    }
}

/** Круглая кнопка: прозрачная зона нажатия [WidgetSpec.buttonTouch] вокруг круга, иконка по центру, название для TalkBack. */
@Composable
private fun CircleButton(spec: WidgetSpec, icon: Int, color: Color, description: String, action: Action) {
    Box(
        modifier = GlanceModifier.size(spec.buttonTouch).clickable(action).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = GlanceModifier.size(spec.buttonSize), contentAlignment = Alignment.Center) {
            ShapeFill(R.drawable.widget_shape_circle, color)
            Image(
                provider = ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(spec.buttonSize * 0.5f),
                colorFilter = ColorFilter.tint(ColorProvider(Color.White))
            )
        }
    }
}

/**
 * Аватар аккаунта: круг цвета аккаунта с первой буквой, у активного — белое кольцо, число непрочитанных — капсулой
 * в правом нижнем углу. Буква и число — по размеру аватара, а не по системному шрифту: иначе вылезли бы за круг; полное
 * название и число — в описании для TalkBack.
 */
@Composable
private fun AccountAvatar(account: AccountUnread, size: Dp, context: Context) {
    val color = Color(account.color)
    // Pick the higher-contrast of black and white for the small initial.
    val initialColor = if (color.luminance() > 0.179f) Color.Black else Color.White
    val base = if (account.unreadCount > 0) {
        context.getString(R.string.widget_account_unread, account.name, account.unreadCount)
    } else {
        context.getString(R.string.widget_account, account.name)
    }
    val description = if (account.isActive) context.getString(R.string.widget_account_active, base)
        else context.getString(R.string.widget_account_select, base)
    // Активный — открыть его почту; другой — сделать активным прямо на рабочем столе
    val action = if (account.isActive) {
        openApp(context, "iwomail://account/${account.id}") {
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, account.id)
            if (account.unreadCount > 0) putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
        }
    } else {
        actionRunCallback<SelectWidgetAccountAction>(actionParametersOf(SelectWidgetAccountAction.ACCOUNT_ID to account.id))
    }
    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(0.5f)
    // Круг — в левом верхнем углу, бейдж — в правом нижнем, поверх края круга, но не поверх буквы
    val circle = size * 0.84f
    val letterSp = circle.value * 0.38f / fontScale
    // Бейдж — по размеру аватара, не по системному шрифту: иначе на маленьком виджете он наползает на букву
    val badgeSp = size.value * 0.24f / fontScale

    Box(
        modifier = GlanceModifier.size(size).clickable(action).semantics { contentDescription = description },
        contentAlignment = Alignment.TopStart
    ) {
        Box(modifier = GlanceModifier.size(circle), contentAlignment = Alignment.Center) {
            ShapeFill(R.drawable.widget_shape_circle, if (account.isActive) Color.White else color)
            if (account.isActive) ShapeFill(R.drawable.widget_shape_circle, color, GlanceModifier.size(circle - 5.dp))
            Text(
                text = account.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = TextStyle(color = ColorProvider(initialColor), fontSize = letterSp.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
        if (account.unreadCount > 0) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = GlanceModifier.shapeBackground(accentBlue, 999.dp, R.drawable.widget_shape_pill),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (account.unreadCount > 99) "99+" else account.unreadCount.toString(),
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = badgeSp.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
