package com.dedovmosol.iwomail.sync

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.model.AccountServerHealth
import com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
import com.dedovmosol.iwomail.ui.NotificationStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Фоновые уведомления о ПОТЕРЕ СВЯЗИ С СЕРВЕРОМ (по одному на аккаунт).
 *
 * Зачем: состояние проблемы сервера ([AccountServerHealth]) раньше было видно только ВНУТРИ
 * приложения (ServerStatusBanner/drawer/настройки). Пока клиент в фоне/закрыт, пользователь не
 * узнавал, что почта перестала синхронизироваться (сервер недоступен, протухли креды, таймаут,
 * ошибка сертификата, 5xx). Этот компонент наблюдает тот же health и всплывает системным
 * уведомлением — закрывает дыру «пока не зайдёшь, не узнаешь».
 *
 * Условие показа = [AccountServerHealth.showBanner] (ТА ЖЕ логика, что у баннера): проблема
 * ВИДИМАЯ (сервер недоступен / оба сервера / авторизация / сертификат / таймаут / 5xx), НО НЕ
 * «работаем по резервному серверу» (PrimaryDownUsingFallback) — там почта идёт, тревожить незачем.
 *
 * Антиспам:
 *  - Гистерезис (3 последовательных сбоя ≈ 1 мин для сетевых ошибок) заложен в
 *    [AccountServerHealthRepository]; Auth/Cert — сразу (детерминированные).
 *  - Одно уведомление на переход «ок → плохо»; при восстановлении снимается.
 *  - Уважает свайп: пока проблема та же, повторно не навязываемся ([notified]).
 *
 * Идемпотентность/перезапуск процесса: health хранится в памяти и после рестарта = None, поэтому
 * первая же сверка по здоровому/отсутствующему состоянию СНИМАЕТ возможный «висяк»-уведомление от
 * прошлого процесса (ветка !shouldShow → cancel). Если проблема реально ещё есть — ping/sync
 * переоткроют её за ~1 мин и уведомление появится снова.
 *
 * Потокобезопасность: [notified] — конкурентное множество; вызывается последовательно из единого
 * наблюдателя в [MailApplication], но множество защищено на случай иных вызовов.
 */
object ServerHealthNotifier {

    private const val NOTIFICATION_ID_BASE = 300_000

    /** Снимок «аккаунт → его здоровье сервера» для одной сверки. */
    data class AccountConnState(
        val accountId: Long,
        val email: String,
        val health: AccountServerHealth
    )

    // Аккаунты, для которых уведомление уже показано (процесс-lifetime). Анти-навязчивость + анти-дубль.
    private val notified = ConcurrentHashMap.newKeySet<Long>()

    private fun notificationId(accountId: Long): Int = NOTIFICATION_ID_BASE + accountId.toInt()

    /**
     * Строит поток снимков здоровья по потоку списка аккаунтов (id, email).
     *
     * [distinctUntilChanged] — реагируем только на смену НАБОРА (id,email), а не на прочие апдейты
     * строки аккаунта (folderSyncKey/policyKey пишутся при каждом синке → иначе лишние переподписки).
     * [flatMapLatest] — при изменении набора переподписываем health-flow (добавление/удаление аккаунта).
     * Пустой список → один эмит пустого снимка (наблюдатель снимет уведомления удалённых аккаунтов).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun connectionStates(idEmailsFlow: Flow<List<Pair<Long, String>>>): Flow<List<AccountConnState>> =
        idEmailsFlow
            .distinctUntilChanged()
            .flatMapLatest { idEmails ->
                if (idEmails.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    AccountServerHealthRepository.aggregatedHealthFlow(idEmails.map { it.first })
                        .map { healthMap ->
                            idEmails.mapNotNull { (id, email) ->
                                healthMap[id]?.let { AccountConnState(id, email, it) }
                            }
                        }
                }
            }

    /**
     * Приводит ВСЕ уведомления в соответствие со снимком [states]: показывает/снимает по каждому
     * аккаунту и снимает уведомления аккаунтов, которых больше нет в снимке (удаление/отключение).
     */
    fun reconcile(
        context: Context,
        states: List<AccountConnState>,
        notificationsEnabled: Boolean,
        isRussian: Boolean
    ) {
        states.forEach { sync(context, it.accountId, it.email, it.health, notificationsEnabled, isRussian) }
        cancelStale(context, states.mapTo(HashSet()) { it.accountId })
    }

    /**
     * Идемпотентно приводит уведомление ОДНОГО аккаунта в соответствие с [health]:
     * показывает при переходе в проблемное состояние, снимает при восстановлении.
     *
     * @param notificationsEnabled мастер-тумблер уведомлений (SettingsRepository.notificationsEnabled)
     * @param isRussian язык для текста (читается наблюдателем один раз на эмиссию)
     */
    fun sync(
        context: Context,
        accountId: Long,
        accountEmail: String,
        health: AccountServerHealth,
        notificationsEnabled: Boolean,
        isRussian: Boolean
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val id = notificationId(accountId)

        val shouldShow = health.showBanner &&
            notificationsEnabled &&
            NotificationHelper.canPostNotifications(context)

        if (!shouldShow) {
            // Снятие: восстановление ИЛИ чистка «висяка» после перезапуска процесса (health=None).
            notified.remove(accountId)
            nm.cancel(id)
            return
        }

        // Уже показано для этого аккаунта — уважаем свайп, повторно не навязываемся.
        if (!notified.add(accountId)) return

        val title = NotificationStrings.getConnectionProblemTitle(isRussian)
        val text = NotificationStrings.getConnectionProblemText(health.problemType, isRussian)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, accountId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, MailApplication.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(accountEmail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$accountEmail"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        nm.notify(id, notification)
    }

    /**
     * Снимает уведомления аккаунтов, которых больше нет среди активных (удаление/отключение аккаунта).
     */
    fun cancelStale(context: Context, activeAccountIds: Set<Long>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        (notified - activeAccountIds).forEach { accountId ->
            notified.remove(accountId)
            nm.cancel(notificationId(accountId))
        }
    }
}
