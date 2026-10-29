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
import kotlinx.coroutines.sync.Mutex
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
 * Отправка — один воркер на письмо (уникальное имя), под [lock]: будильник и подстраховка не отправят его дважды.
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
        val draftStamp: Long = -1L
    )

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Отправка письма [dir] — по одной за раз (будильник и подстраховка — в одном процессе). */
    fun lock(dir: File): Mutex = locks.getOrPut(dir.name) { Mutex() }

    fun workName(dir: File): String = "scheduled_send_${dir.name}"

    /**
     * Запланировать. Тот же черновик уже запланирован — прежний срок отменяется (письмо одно, а не два). Тело и вложения
     * пишутся на диск — вызывать не с главного потока.
     */
    fun schedule(context: Context, job: Job): File {
        job.draftId?.let { draftId -> pending(context).filter { it.second.draftId == draftId }.forEach { cancel(context, it.first) } }
        val dir = File(File(context.filesDir, ROOT_DIR), "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}")
        write(dir, job)
        arm(context, dir, job.sendAt)
        return dir
    }

    /** Отменить письмо [dir]: задача, будильник и файлы. */
    fun cancel(context: Context, dir: File) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(dir))
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, dir))
        dir.deleteRecursively()
    }

    /** Будильник и подстраховка для письма [dir] (после планирования и после перезагрузки). */
    private fun arm(context: Context, dir: File, sendAt: Long) {
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
            workName(dir), ExistingWorkPolicy.KEEP, request(dir) { setInitialDelay(delay, TimeUnit.MILLISECONDS) }
        )
    }

    /**
     * Срок письма [dir] наступил (будильник): отправить сейчас — срочной задачей вместо отложенной подстраховки. Уже
     * отправляется — не мешаем (замена прервала бы отправку на середине).
     */
    suspend fun runNow(context: Context, dir: File) {
        if (!dir.exists()) return
        val manager = WorkManager.getInstance(context)
        val name = workName(dir)
        val running = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
        for ((dir, job) in pending(context)) arm(context, dir, job.sendAt)
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

    /** Ожидающие письма (каталоги с mail.json). */
    fun pending(context: Context): List<Pair<File, Job>> =
        File(context.filesDir, ROOT_DIR).listFiles()?.mapNotNull { dir -> read(dir)?.let { dir to it } }.orEmpty()

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
        File(dir, MAIL_FILE).writeText(
            JSONObject()
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
                .toString()
        )
    }

    /** Письмо из каталога; null — каталога или mail.json нет (уже отправлено, отменено или не дописано). */
    internal fun read(dir: File): Job? {
        val meta = File(dir, MAIL_FILE).takeIf { it.exists() } ?: return null
        return try {
            val json = JSONObject(meta.readText())
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
                draftStamp = json.optLong("draftStamp", -1L)
            )
        } catch (e: Exception) {
            android.util.Log.w("ScheduledSend", "Unreadable scheduled mail ${dir.name}: ${e.message}")
            null
        }
    }

    /** Вложения письма (manifest.json; так же их писали и прежние версии). */
    internal fun readAttachments(dir: File): List<Triple<String, String, ByteArray>> {
        val manifestFile = File(dir, MANIFEST_FILE).takeIf { it.exists() } ?: return emptyList()
        return try {
            val manifest = JSONArray(manifestFile.readText())
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
