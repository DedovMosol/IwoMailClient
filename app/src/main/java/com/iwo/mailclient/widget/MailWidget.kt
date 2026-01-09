package com.iwo.mailclient.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import com.iwo.mailclient.MainActivity
import com.iwo.mailclient.R
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.sync.SyncAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Виджет почтового клиента
 */
class MailWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            val data = withContext(Dispatchers.IO) { loadWidgetData(context) }
            provideContent { MailWidgetContent(data, context) }
        } catch (e: Exception) {
            // Fallback при любой ошибке
            val isRussian = try {
                SettingsRepository.getInstance(context).getLanguageSync() == "ru"
            } catch (_: Exception) { true }
            provideContent { 
                MailWidgetContent(WidgetData(emptyList(), null, false, isRussian), context) 
            }
        }
    }
    
    private suspend fun loadWidgetData(context: Context): WidgetData {
        return try {
            val db = MailDatabase.getInstance(context)
            val settingsRepo = SettingsRepository.getInstance(context)
            val isRussian = settingsRepo.getLanguageSync() == "ru"
            
            val accounts = db.accountDao().getAllAccountsSync()
            val accountsWithUnread = accounts.map { account ->
                val unread = db.emailDao().getUnreadCountByAccount(account.id)
                AccountUnread(account.id, account.displayName, account.color, unread)
            }
            
            // Ближайшее событие (по всем аккаунтам)
            val now = System.currentTimeMillis()
            var nextEvent: com.iwo.mailclient.data.database.CalendarEventEntity? = null
            for (account in accounts) {
                val event = db.calendarEventDao().getNextEventSync(account.id, now)
                if (event != null && (nextEvent == null || event.startTime < nextEvent.startTime)) {
                    nextEvent = event
                }
            }
            
            val eventInfo = nextEvent?.let {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                EventInfo(it.subject, timeFormat.format(Date(it.startTime)))
            }
            
            WidgetData(accountsWithUnread, eventInfo, accounts.isNotEmpty(), isRussian)
        } catch (e: Exception) {
            WidgetData(emptyList(), null, false, true)
        }
    }
}

data class AccountUnread(val id: Long, val name: String, val color: Int, val unreadCount: Int)
data class EventInfo(val title: String, val time: String)
data class WidgetData(
    val accounts: List<AccountUnread>,
    val nextEvent: EventInfo?,
    val hasAccount: Boolean,
    val isRussian: Boolean
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
            NoAccountView(data.isRussian, context)
        } else {
            FullWidgetView(data, context)
        }
    }
}

@Composable
private fun NoAccountView(isRussian: Boolean, context: Context) {
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
            text = if (isRussian) "Добавьте аккаунт" else "Add account",
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
                        text = if (data.isRussian) "Поиск в почте" else "Search mail",
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
                    text = if (data.isRussian) "Календарь" else "Calendar",
                    style = TextStyle(color = ColorProvider(textGray), fontSize = 14.sp)
                )
            }
        }
        
        Spacer(modifier = GlanceModifier.height(14.dp))
        
        // Аккаунты
        Row(verticalAlignment = Alignment.CenterVertically) {
            data.accounts.take(4).forEach { acc ->
                AccountAvatar(acc, context)
                Spacer(modifier = GlanceModifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun AccountAvatar(account: AccountUnread, context: Context) {
    Box(
        modifier = GlanceModifier
            .size(48.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, account.id)
                }
            ))
    ) {
        // Аватар (круг)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(ColorProvider(Color(account.color))),
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
        
        // Счётчик справа внизу
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
