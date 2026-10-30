package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.dedovmosol.iwomail.ui.screens.ScheduledEmailWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Отложенная отправка: письмо (тело, вложения, параметры) — в своём каталоге scheduled_attachments/<имя>; его же
 * читает воркер. В срок его запускает точный будильник ([AlarmManager.setExactAndAllowWhileIdle] — срабатывает и в
 * Doze), а отложенная задача WorkManager — подстраховка: переживает перезагрузку и отзыв разрешения на точные
 * будильники. Задача WorkManager с одной задержкой в Doze откладывается до окна обслуживания — «завтра в 8:00» уходило
 * через час-другой. Нет разрешения на точные будильники — только задача WorkManager (как раньше, но с сетью).
 *
 * Отправка — один воркер на письмо (уникальное имя), под [lock]: будильник, подстраховка и отмена отправки не
 * отправят письмо дважды и не отменят уже уходящее.
 */
object ScheduledSend {

    internal const val ROOT_DIR = "scheduled_attachments"
    private const val MAIL_FILE = "mail.json"
    internal const val BODY_FILE = "body.html"
    private const val MANIFEST_FILE = "manifest.json"
    internal const val KEY_PAYLOAD_DIR = "attachmentDir"
    private const val ACTION_SEND = "com.dedovmosol.iwomail.SCHEDULED_SEND"
    private const val EXTRA_PAYLOAD = "payload"

    /** Подстраховка срабатывает позже будильника: обычно письмо уже ушло, и она ничего не делает. */
    internal const val BACKSTOP_DELAY_MS = 15 * 60 * 1000L

    /** Запланированное письмо. */
    data class Job(
        val accountId: Long,
        val mail: OutgoingMail,
        val sendAt: Long,
        /** Один на все попытки: повтор после потерянного ответа не отправит письмо второй раз (EAS 14+). */
        val clientId: String,
        /** Черновик, из которого запланировано, и время его изменения: удаляется после отправки, если не менялся. */
        val draftId: String? = null,
        val draftStamp: Long = -1L,
        /**
         * Письмо ждёт отмены отправки (несколько секунд): его отправит экран отправки, а воркер — только если
         * приложение закрыли раньше. В списке запланированных не показывается.
         */
        val undo: Boolean = false
    )

    /** Ожидающее письмо для списка — без тела и вложений (их байты для списка не читаются). */
    data class Entry(
        val dir: File,
        val accountId: Long,
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val sendAt: Long,
        val draftId: String?,
        val attachmentCount: Int,
        val undo: Boolean
    )

    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _changes = MutableStateFlow(0L)

    /** Меняется при каждом изменении набора писем: запланировали, перенесли, отправили, отменили. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    /** Отправка письма [dir] — по одной за раз (будильник, подстраховка, экран отправки — в одном процессе). */
    fun lock(dir: File): Mutex = locks.getOrPut(dir.name) { Mutex() }

    fun workName(dir: File): String = "scheduled_send_${dir.name}"

    /**
     * Запланировать. Тот же черновик уже запланирован — прежний срок отменяется (письмо одно, а не два). Тело и вложения
     * пишутся на диск — вызывать не с главного потока.
     */
    suspend fun schedule(context: Context, job: Job): File = withContext(Dispatchers.IO) {
        job.draftId?.let { draftId -> pending(context).filter { it.draftId == draftId }.forEach { take(context, it.dir) } }
        val dir = File(File(context.filesDir, ROOT_DIR), "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}")
        write(dir, job)
        arm(context, dir, job.sendAt, ExistingWorkPolicy.KEEP)
        changed()
        dir
    }

    /**
     * Забрать письмо [dir], чтобы его не отправили: отменить задачу и будильник, удалить файлы. null — письма уже нет
     * (отправлено или отменено). Уже отправляется — ждёт конца отправки (и тогда вернёт null), а не обрывает её.
     */
    suspend fun take(context: Context, dir: File): Job? = lock(dir).withLock {
        withContext(Dispatchers.IO) {
            read(dir)?.also { cancel(context, dir) }
        }
    }

    /** Как [take], но без чтения письма (оно уже в памяти у вызывающего): true — отменено, false — письма уже нет. */
    suspend fun remove(context: Context, dir: File): Boolean = lock(dir).withLock {
        withContext(Dispatchers.IO) {
            isPending(dir).also { if (it) cancel(context, dir) }
        }
    }

    /** Письмо [dir] ещё ждёт отправки (не отправлено и не отменено). Проверять под [lock]. */
    fun isPending(dir: File): Boolean = File(dir, MAIL_FILE).exists()

    /** Перенести отправку письма [dir] на [sendAt]. false — письма уже нет (отправлено или отменено). */
    suspend fun reschedule(context: Context, dir: File, sendAt: Long): Boolean = lock(dir).withLock {
        withContext(Dispatchers.IO) {
            val job = read(dir) ?: return@withContext false
            writeMeta(dir, job.copy(sendAt = sendAt))
            context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, dir))
            // REPLACE: прежняя подстраховка ждёт старого срока
            arm(context, dir, sendAt, ExistingWorkPolicy.REPLACE)
            changed()
            true
        }
    }

    /** Письмо [dir] отправлено или окончательно не отправлено: будильник и файлы больше не нужны. */
    fun finish(context: Context, dir: File) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, dir))
        dir.deleteRecursively()
        changed()
    }

    /** Отменить письмо [dir]: задача, будильник и файлы. Без блокировки — только когда отправка не идёт ([take]). */
    internal fun cancel(context: Context, dir: File) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(dir))
        finish(context, dir)
    }

    /** Будильник и подстраховка для письма [dir] (после планирования, переноса и перезагрузки). */
    private fun arm(context: Context, dir: File, sendAt: Long, policy: ExistingWorkPolicy) {
        val now = System.currentTimeMillis()
        val exact = BackgroundWork.exactAlarmsAllowed(context) && sendAt > now
        if (exact) {
            try {
                context.getSystemService(AlarmManager::class.java)
                    ?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sendAt, alarmIntent(context, dir))
            } catch (e: SecurityException) {
                // Разрешение отозвали между проверкой и вызовом — сработает подстраховка
                android.util.Log.w("ScheduledSend", "Exact alarm denied: ${e.message}")
            }
        }
        val delay = (sendAt - now).coerceAtLeast(0) + if (exact) BACKSTOP_DELAY_MS else 0
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(dir), policy, request(dir) { setInitialDelay(delay, TimeUnit.MILLISECONDS) }
        )
    }

    /**
     * Отправить письмо [dir] сейчас — срочной задачей (когда есть связь) вместо отложенной подстраховки. Уже
     * отправляется — не мешаем (замена прервала бы отправку на середине).
     */
    suspend fun runNow(context: Context, dir: File) {
        if (!dir.exists()) return
        val manager = WorkManager.getInstance(context)
        val name = workName(dir)
        val running = withContext(Dispatchers.IO) {
            manager.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }
        }
        if (running) return
        manager.enqueueUniqueWork(
            name, ExistingWorkPolicy.REPLACE,
            request(dir) { setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
        )
    }

    /** После перезагрузки будильники стёрты — заново для всех ожидающих писем (задачи WorkManager живы). */
    fun rearm(context: Context) {
        for (entry in pending(context)) arm(context, entry.dir, entry.sendAt, ExistingWorkPolicy.KEEP)
    }

    private fun request(dir: File, configure: OneTimeWorkRequest.Builder.() -> Unit): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ScheduledEmailWorker>()
            .setInputData(workDataOf(KEY_PAYLOAD_DIR to dir.absolutePath))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .apply(configure)
            .build()

    private fun alarmIntent(context: Context, dir: File): PendingIntent {
        val intent = Intent(context, ScheduledSendReceiver::class.java)
            .setAction("$ACTION_SEND/${dir.name}") // своё действие — свой PendingIntent на письмо
            .putExtra(EXTRA_PAYLOAD, dir.name)
        return PendingIntent.getBroadcast(
            context, dir.name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Каталог письма из намерения будильника (только внутри scheduled_attachments: имя без путей). */
    internal fun dirFromAlarm(context: Context, intent: Intent): File? {
        val name = intent.getStringExtra(EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }
            ?: return null
        return File(File(context.filesDir, ROOT_DIR), name)
    }

    /** Ожидающие письма (каталоги с mail.json), по сроку. Читаются только параметры — вызывать не с главного потока. */
    fun pending(context: Context): List<Entry> =
        File(context.filesDir, ROOT_DIR).listFiles()?.mapNotNull(::entry).orEmpty().sortedBy { it.sendAt }

    // === Хранение ===

    internal fun write(dir: File, job: Job) {
        dir.mkdirs()
        File(dir, BODY_FILE).writeText(job.mail.body)
        val manifest = JSONArray()
        for ((index, attachment) in job.mail.attachments.withIndex()) {
            val (name, mimeType, bytes) = attachment
            val file = "${index}_${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            File(dir, file).writeBytes(bytes)
            manifest.put(JSONObject().put("name", name).put("mimeType", mimeType).put("file", file))
        }
        if (manifest.length() > 0) File(dir, MANIFEST_FILE).writeText(manifest.toString())
        // Последним: каталог без mail.json — недописанное письмо, его никто не отправит
        writeMeta(dir, job)
    }

    /** Параметры письма — во временный файл и переименованием: оборванная запись не испортит прежний mail.json. */
    private fun writeMeta(dir: File, job: Job) {
        val json = JSONObject()
            .put("accountId", job.accountId)
            .put("to", job.mail.to).put("cc", job.mail.cc).put("bcc", job.mail.bcc)
            .put("subject", job.mail.subject)
            .put("importance", job.mail.importance)
            .put("requestReadReceipt", job.mail.requestReadReceipt)
            .put("requestDeliveryReceipt", job.mail.requestDeliveryReceipt)
            .put("forwardFolderId", job.mail.forwardFolderId ?: "")
            .put("forwardItemId", job.mail.forwardItemId ?: "")
            .put("sendAt", job.sendAt)
            .put("clientId", job.clientId)
            .put("draftId", job.draftId ?: "")
            .put("draftStamp", job.draftStamp)
            .put("undo", job.undo)
        val tmp = File(dir, "$MAIL_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(dir, MAIL_FILE))) {
            File(dir, MAIL_FILE).writeText(json.toString())
            tmp.delete()
        }
    }

    private fun meta(dir: File): JSONObject? {
        val file = File(dir, MAIL_FILE).takeIf { it.exists() } ?: return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            android.util.Log.w("ScheduledSend", "Unreadable scheduled mail ${dir.name}: ${e.message}")
            null
        }
    }

    private fun entry(dir: File): Entry? {
        val json = meta(dir) ?: return null
        return Entry(
            dir = dir,
            accountId = json.optLong("accountId", -1L),
            to = json.optString("to"), cc = json.optString("cc"), bcc = json.optString("bcc"),
            subject = json.optString("subject"),
            sendAt = json.optLong("sendAt"),
            draftId = json.optString("draftId").takeIf { it.isNotBlank() },
            attachmentCount = manifest(dir)?.length() ?: 0,
            undo = json.optBoolean("undo")
        )
    }

    /** Письмо из каталога; null — каталога или mail.json нет (уже отправлено, отменено или не дописано). */
    internal fun read(dir: File): Job? {
        val json = meta(dir) ?: return null
        return try {
            Job(
                accountId = json.getLong("accountId"),
                mail = OutgoingMail(
                    to = json.optString("to"), cc = json.optString("cc"), bcc = json.optString("bcc"),
                    subject = json.optString("subject"),
                    body = File(dir, BODY_FILE).takeIf { it.exists() }?.readText().orEmpty(),
                    attachments = readAttachments(dir),
                    importance = json.optInt("importance", 1),
                    requestReadReceipt = json.optBoolean("requestReadReceipt"),
                    requestDeliveryReceipt = json.optBoolean("requestDeliveryReceipt"),
                    forwardFolderId = json.optString("forwardFolderId").takeIf { it.isNotBlank() },
                    forwardItemId = json.optString("forwardItemId").takeIf { it.isNotBlank() }
                ),
                sendAt = json.optLong("sendAt"),
                clientId = json.getString("clientId"),
                draftId = json.optString("draftId").takeIf { it.isNotBlank() },
                draftStamp = json.optLong("draftStamp", -1L),
                undo = json.optBoolean("undo")
            )
        } catch (e: Exception) {
            android.util.Log.w("ScheduledSend", "Unreadable scheduled mail ${dir.name}: ${e.message}")
            null
        }
    }

    private fun manifest(dir: File): JSONArray? {
        val file = File(dir, MANIFEST_FILE).takeIf { it.exists() } ?: return null
        return try {
            JSONArray(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Вложения письма (manifest.json; так же их писали и прежние версии). */
    internal fun readAttachments(dir: File): List<Triple<String, String, ByteArray>> {
        val manifest = manifest(dir) ?: return emptyList()
        return try {
            (0 until manifest.length()).mapNotNull { i ->
                val entry = manifest.getJSONObject(i)
                File(dir, entry.getString("file")).takeIf { it.exists() }
                    ?.let { Triple(entry.getString("name"), entry.getString("mimeType"), it.readBytes()) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
