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
import com.dedovmosol.iwomail.sync.SyncAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Виджет почтового клиента
 */
class MailWidget : GlanceAppWidget() {

    // Используем Responsive вместо Exact для корректной перерисовки
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 140.dp),  // Минимальный размер
            DpSize(300.dp, 180.dp),  // Средний
            DpSize(400.dp, 200.dp)   // Большой
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
                EventInfo(it.subject, timeFormat.format(Date(it.startTime)))
            }
            
            WidgetData(accountsWithUnread, eventInfo, accounts.isNotEmpty())
        } catch (e: Exception) {
            WidgetData(emptyList(), null, false)
        }
    }
}

data class AccountUnread(val id: Long, val name: String, val color: Int, val unreadCount: Int)
data class EventInfo(val title: String, val time: String)
data class WidgetData(
    val accounts: List<AccountUnread>,
    val nextEvent: EventInfo?,
    val hasAccount: Boolean
)

private val widgetBg = Color(0xFFE8F4FC)
private val searchBg = Color.White
private val textDark = Color(0xFF212121)
private val textGray = Color(0xFF757575)
private val accentBlue = Color(0xFF2196F3)

@Composable
private fun MailWidgetContent(data: WidgetData, context: Context) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(ColorProvider(widgetBg))
            .padding(16.dp)
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
            style = TextStyle(color = ColorProvider(textDark), fontSize = 14.sp)
        )
    }
}

@Composable
private fun FullWidgetView(data: WidgetData, context: Context) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Строка поиска + кнопка синхронизации
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Поиск
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(44.dp)
                    .cornerRadius(22.dp)
                    .background(ColorProvider(searchBg))
                    .clickable(actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
                        colorFilter = ColorFilter.tint(ColorProvider(textGray))
                    )
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Text(
                        text = context.getString(R.string.widget_search_mail),
                        style = TextStyle(color = ColorProvider(textGray), fontSize = 14.sp)
                    )
                }
            }
            
            Spacer(modifier = GlanceModifier.width(10.dp))
            
            // Кнопка "Написать"
            Box(
                modifier = GlanceModifier
                    .size(44.dp)
                    .cornerRadius(22.dp)
                    .background(ColorProvider(searchBg))
                    .clickable(actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("compose", true)
                        }
                    )),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_edit),
                    contentDescription = null,
                    modifier = GlanceModifier.size(22.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(textDark))
                )
            }
            
            Spacer(modifier = GlanceModifier.width(10.dp))
            
            // Кнопка синхронизации
            Box(
                modifier = GlanceModifier
                    .size(44.dp)
                    .cornerRadius(22.dp)
                    .background(ColorProvider(searchBg))
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
                    modifier = GlanceModifier.size(22.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(textDark))
                )
            }
        }
        
        Spacer(modifier = GlanceModifier.height(12.dp))
        
        // Событие календаря
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("calendar", true)
                    }
                )),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_calendar_month),
                contentDescription = null,
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(ColorProvider(accentBlue))
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
            if (data.nextEvent != null) {
                Text(
                    text = data.nextEvent.time,
                    style = TextStyle(color = ColorProvider(textDark), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                )
                Spacer(modifier = GlanceModifier.width(10.dp))
                Text(
                    text = data.nextEvent.title,
                    style = TextStyle(color = ColorProvider(textGray), fontSize = 14.sp),
                    maxLines = 1
                )
            } else {
                Text(
                    text = context.getString(R.string.widget_calendar),
                    style = TextStyle(color = ColorProvider(textGray), fontSize = 14.sp)
                )
            }
        }
        
        Spacer(modifier = GlanceModifier.height(14.dp))
        
        // Аккаунты
        Row(verticalAlignment = Alignment.CenterVertically) {
            data.accounts.take(4).forEach { acc ->
                AccountAvatar(acc, context)
                Spacer(modifier = GlanceModifier.width(4.dp))
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
    
    // Контейнер 56x56 чтобы badge не обрезался
    Box(
        modifier = GlanceModifier
            .size(56.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, account.id)
                }
            )),
        contentAlignment = Alignment.TopStart
    ) {
        // Аватар (круг) 48x48 с отступом сверху-слева
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .cornerRadius(24.dp)
                .background(ColorProvider(accountColor)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.name.firstOrNull()?.uppercase() ?: "?",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        // Счётчик справа внизу от аватара (внутри большего контейнера)
        if (account.unreadCount > 0) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.BottomEnd
            ) {
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(accentBlue))
                        .cornerRadius(8.dp)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
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
