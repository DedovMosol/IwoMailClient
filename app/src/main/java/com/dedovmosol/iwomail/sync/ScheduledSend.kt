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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
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
        val undo: Boolean,
        val status: Status = Status.PENDING
    ) {
        /**
         * Показывать в списке запланированных. Письмо с отменой отправки скрыто, пока идёт отсчёт; срок его подстраховки
         * прошёл (приложение закрыли во время отсчёта, а связи нет) — оно обычное ожидающее, его видно и можно отменить.
         */
        fun listed(now: Long): Boolean = status != Status.PENDING || !undo || sendAt <= now
    }

    enum class Status { PENDING, FAILED, CORRUPT, GONE }
    enum class RestoreResult { RESTORED, DRAFT_SAVE_FAILED, UNAVAILABLE, GONE }
    enum class RunResult { ENQUEUED, RUNNING, UNAVAILABLE, GONE, NOT_DUE }

    internal class ReplacementFailed(cause: IOException) : IOException(cause.message, cause)

    private const val LOCK_COUNT = 128
    private val locks = Array(LOCK_COUNT) { Mutex() }
    private val scheduleLock = Mutex()
    private const val FAILED_FILE = ".failed"
    private const val CORRUPT_FILE = ".corrupt"
    private const val DONE_FILE = ".done"
    private const val REPLACEMENT_FILE = ".replacement"
    private const val META_TEMP = "$MAIL_FILE.tmp"

    private val _changes = MutableStateFlow(0L)

    /** Меняется при каждом изменении набора писем: запланировали, перенесли, отправили, отменили. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    /** Fixed stripes: the same name always uses the same lock, even while another coroutine is waiting. */
    private fun stripe(dir: File): Int = dir.name.hashCode() and (LOCK_COUNT - 1)
    fun lock(dir: File): Mutex = locks[stripe(dir)]
    fun workName(dir: File): String = "scheduled_send_${dir.name}"

    private suspend fun <T> withStripes(stripes: List<Int>, index: Int = 0, block: suspend () -> T): T =
        if (index == stripes.size) block() else locks[stripes[index]].withLock { withStripes(stripes, index + 1, block) }

    /** Write the replacement and register its work before committing it. Until mail.json is published, the old
     * jobs still own delivery. After publication the links suppress them, including after a process restart. */
    suspend fun schedule(context: Context, job: Job): File = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            val dir = File(root(context), "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
            val previous = if (job.draftId == null) emptyList() else pending(context)
                .filter { it.status == Status.PENDING && it.accountId == job.accountId && it.draftId == job.draftId }
                .map { it.dir }
            withStripes((previous + dir).map(::stripe).distinct().sorted()) {
                withContext(NonCancellable) {
                    val stillPending = previous.filter(::isPending)
                    try {
                        write(dir, job, staged = true)
                        for (old in stillPending) {
                            atomicText(File(old, REPLACEMENT_FILE), dir.name)
                        }
                        arm(context, dir, job.sendAt, ExistingWorkPolicy.KEEP)
                        publishMeta(dir)
                    } catch (e: Exception) {
                        stopTriggers(context, dir, cancelWork = true)
                        dir.deleteRecursively()
                        if (e is IOException && stillPending.isNotEmpty()) throw ReplacementFailed(e)
                        throw e
                    }
                    // Cleanup is not part of committing the new job; do not report a successful commit as a failure.
                    runCatching { retirePredecessors(context, dir) }.onFailure {
                        android.util.Log.w("ScheduledSend", "Replacement committed; old payload cleanup deferred", it)
                    }
                    changed()
                    dir
                }
            }
        }
    }

    /** Hold the delivery lock until a durable draft exists. Failure or coroutine cancellation leaves the source intact. */
    suspend fun restoreDraft(context: Context, dir: File, stopDeliveryOnFailure: Boolean = false, save: suspend (Job) -> Boolean): RestoreResult =
        lock(dir).withLock {
            withContext(Dispatchers.IO) {
                when (status(dir)) {
                    Status.GONE -> return@withContext RestoreResult.GONE
                    Status.CORRUPT -> return@withContext RestoreResult.UNAVAILABLE
                    else -> Unit
                }
                val job = read(dir) ?: run {
                    quarantine(context, dir)
                    return@withContext RestoreResult.UNAVAILABLE
                }
                if (stopDeliveryOnFailure) withContext(NonCancellable) { markFailed(context, dir) }
                if (!attemptDraftSave { save(job) }) return@withContext RestoreResult.DRAFT_SAVE_FAILED
                withContext(NonCancellable) { finish(context, dir, cancelWork = true) }
                RestoreResult.RESTORED
            }
        }

    internal suspend fun attemptDraftSave(save: suspend () -> Boolean): Boolean = try {
        save()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ScheduledSend", "Draft save failed", e)
        false
    }

    /** Check the disk status while holding [lock] before any send or mutation. */
    internal fun status(dir: File): Status {
        if (!dir.isDirectory || File(dir, DONE_FILE).exists() || superseded(dir)) return Status.GONE
        if (File(dir, CORRUPT_FILE).exists()) return Status.CORRUPT
        if (!File(dir, MAIL_FILE).exists()) return Status.GONE
        if (File(dir, FAILED_FILE).exists()) return Status.FAILED
        return if (validatedMetadata(dir) == null) Status.CORRUPT else Status.PENDING
    }

    fun isPending(dir: File): Boolean = status(dir) == Status.PENDING

    suspend fun reschedule(context: Context, dir: File, sendAt: Long): Boolean = lock(dir).withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            when (status(dir)) {
                Status.PENDING -> Unit
                Status.CORRUPT -> { quarantine(context, dir); return@withContext false }
                else -> return@withContext false
            }
            val metadata = validatedMetadata(dir) ?: run {
                quarantine(context, dir)
                return@withContext false
            }
            val previous = metadata.getLong("sendAt")
            writeSendAt(dir, metadata, sendAt)
            try {
                cancelAlarm(context, dir)
                arm(context, dir, sendAt, ExistingWorkPolicy.REPLACE)
            } catch (e: Exception) {
                writeSendAt(dir, metadata, previous)
                arm(context, dir, previous, ExistingWorkPolicy.KEEP)
                throw e
            }
            changed()
            true
        }
    }

    private fun writeSendAt(dir: File, metadata: JSONObject, sendAt: Long) {
        atomicText(File(dir, MAIL_FILE), metadata.put("sendAt", sendAt).toString())
    }

    /** The complete payload remains available for draft recovery, but delivery must never retry it. */
    internal fun hasFailureMarker(dir: File): Boolean = File(dir, FAILED_FILE).exists()

    /** Caller holds the payload lock. Publish only after a no-send marker is visible. */
    internal fun publishFailed(context: Context, dir: File, job: Job) {
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create recovery directory")
        markFailed(context, dir)
        write(dir, job, staged = true)
        publishMeta(dir)
        changed()
    }

    /** Move a legacy terminal row into the same recoverable UI as other failed messages. */
    internal suspend fun recoverLegacyOutbox(context: Context, key: UUID, job: Job) {
        val dir = File(root(context), "outbox_$key")
        lock(dir).withLock {
            if (!hasMetadata(dir) && !legacyOutboxReceipt(dir).exists()) publishFailed(context, dir, job)
        }
    }

    private fun legacyOutboxReceipt(dir: File) = File(dir.parentFile, ".${dir.name}.recovered")

    internal fun legacyInlineReceipt(dir: File) = File(dir.parentFile, ".${dir.name}.done")

    internal fun markFailed(context: Context, dir: File) {
        File(dir, FAILED_FILE).createNewFile()
        stopTriggers(context, dir, cancelWork = false)
        changed()
    }

    /** Caller holds the delivery lock. Persist the terminal decision before attempting draft recovery. */
    internal suspend fun retainFailure(context: Context, dir: File, save: suspend () -> Boolean): Boolean {
        withContext(Dispatchers.IO + NonCancellable) { markFailed(context, dir) }
        val saved = attemptDraftSave(save)
        if (saved) withContext(Dispatchers.IO + NonCancellable) { finish(context, dir) }
        return saved
    }

    internal fun quarantine(context: Context, dir: File) {
        if (File(dir, CORRUPT_FILE).createNewFile()) {
            stopTriggers(context, dir, cancelWork = false)
            changed()
        }
    }

    /** Only after a successful send, a durable draft save, or a durable replacement. Move out of the active
     * namespace before deleting any bytes, so partial cleanup can never create a sendable payload. */
    internal fun finish(context: Context, dir: File, cancelWork: Boolean = false) {
        try {
            if (dir.name.startsWith("outbox_")) legacyOutboxReceipt(dir).createNewFile()
            if (dir.name.startsWith("legacy_")) legacyInlineReceipt(dir).createNewFile()
            retirePredecessors(context, dir)
            retire(context, dir, cancelWork)
        } catch (e: IOException) {
            // Keep mail.json as a tombstone if a predecessor could not be retired. Its link must not become active.
            File(dir, DONE_FILE).createNewFile()
            stopTriggers(context, dir, cancelWork)
            android.util.Log.w("ScheduledSend", "Payload cleanup deferred", e)
        }
        changed()
    }

    private fun replacement(dir: File): String? = runCatching {
        File(dir, REPLACEMENT_FILE).takeIf { it.isFile }?.readText()?.takeIf(::validName)
    }.getOrNull()

    private fun superseded(dir: File): Boolean = replacement(dir)?.let { name ->
        File(File(dir.parentFile, name), MAIL_FILE).isFile
    } == true

    private fun retirePredecessors(context: Context, dir: File) {
        for (old in dir.parentFile?.listFiles().orEmpty()) {
            if (old != dir && replacement(old) == dir.name) {
                retirePredecessors(context, old)
                retire(context, old, cancelWork = true)
            }
        }
    }

    private fun retire(context: Context, dir: File, cancelWork: Boolean) {
        if (!dir.exists()) return
        val trash = File(dir.parentFile, ".retired_${UUID.randomUUID()}")
        runCatching { Files.move(dir.toPath(), trash.toPath(), ATOMIC_MOVE) }
            .getOrElse { Files.move(dir.toPath(), trash.toPath(), REPLACE_EXISTING) }
        stopTriggers(context, dir, cancelWork)
        trash.deleteRecursively()
    }

    private fun cancelAlarm(context: Context, dir: File) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, dir))
    }

    private fun stopTriggers(context: Context, dir: File, cancelWork: Boolean) {
        runCatching { cancelAlarm(context, dir) }
        if (cancelWork) runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(dir)).result.get() }
    }

    /** Будильник и подстраховка для письма [dir] (после планирования, переноса и перезагрузки). */
    private fun arm(context: Context, dir: File, sendAt: Long, policy: ExistingWorkPolicy) {
        val now = System.currentTimeMillis()
        var usedExact = false
        if (sendAt > now) {
            context.getSystemService(AlarmManager::class.java)?.let { alarms ->
                try {
                    usedExact = BackgroundWork.scheduleRtcWakeup(alarms, sendAt, alarmIntent(context, dir))
                } catch (e: SecurityException) {
                    android.util.Log.w("ScheduledSend", "Alarm denied: ${e.message}")
                }
            }
        }
        val delay = (sendAt - now).coerceAtLeast(0) + if (usedExact) BACKSTOP_DELAY_MS else 0
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(dir), policy, request(dir) { setInitialDelay(delay, TimeUnit.MILLISECONDS) }
        ).result.get()
    }

    /** User action makes the job due now; a stale alarm must not advance a rescheduled job. */
    suspend fun runNow(context: Context, dir: File, onlyIfDue: Boolean = false): RunResult = lock(dir).withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            when (status(dir)) {
                Status.GONE -> return@withContext RunResult.GONE
                Status.PENDING -> Unit
                Status.CORRUPT -> {
                    quarantine(context, dir)
                    return@withContext RunResult.UNAVAILABLE
                }
                Status.FAILED -> return@withContext RunResult.UNAVAILABLE
            }
            val metadata = validatedMetadata(dir) ?: run {
                quarantine(context, dir)
                return@withContext RunResult.UNAVAILABLE
            }
            val now = System.currentTimeMillis()
            val previous = metadata.getLong("sendAt")
            if (onlyIfDue && previous > now) return@withContext RunResult.NOT_DUE
            val manager = WorkManager.getInstance(context)
            val name = workName(dir)
            if (manager.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }) {
                return@withContext RunResult.RUNNING
            }
            if (!onlyIfDue && previous > now) writeSendAt(dir, metadata, now)
            try {
                manager.enqueueUniqueWork(
                    name, ExistingWorkPolicy.REPLACE,
                    request(dir) { setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                ).result.get()
            } catch (e: Exception) {
                if (!onlyIfDue && previous > now) writeSendAt(dir, metadata, previous)
                throw e
            }
            runCatching { cancelAlarm(context, dir) }
            changed()
            RunResult.ENQUEUED
        }
    }

    /** An undo mail handed over to the worker is immediately due, so it becomes visible. */
    suspend fun queueNow(context: Context, dir: File): Boolean = when (runNow(context, dir)) {
        RunResult.ENQUEUED, RunResult.RUNNING -> true
        else -> false
    }

    /** После перезагрузки будильники стёрты — заново только для ожидающих писем. */
    suspend fun rearm(context: Context) {
        for (entry in pending(context).filter { it.status == Status.PENDING }) {
            lock(entry.dir).withLock {
                if (isPending(entry.dir)) validatedMetadata(entry.dir)?.let {
                    arm(context, entry.dir, it.getLong("sendAt"), ExistingWorkPolicy.KEEP)
                }
            }
        }
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
        val name = intent.getStringExtra(EXTRA_PAYLOAD)?.takeIf(::validName) ?: return null
        return File(root(context), name)
    }

    private fun validName(name: String): Boolean = name.isNotBlank() && !name.startsWith('.') &&
        '/' !in name && '\\' !in name && ".." !in name

    internal fun root(context: Context): File = File(context.filesDir, ROOT_DIR)

    /** Includes retained failures. Listing validates layout but does not load body or attachment bytes. */
    fun pending(context: Context): List<Entry> = root(context).listFiles()
        ?.filter { validName(it.name) }?.mapNotNull(::entry).orEmpty().sortedBy { it.sendAt }

    // === Хранение ===

    internal fun write(dir: File, job: Job, staged: Boolean = false) {
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create scheduled mail directory")
        writeSynced(File(dir, BODY_FILE), job.mail.body.toByteArray())
        val manifest = JSONArray()
        for ((index, attachment) in job.mail.attachments.withIndex()) {
            val (name, mimeType, bytes) = attachment
            val file = "${index}_${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            writeSynced(File(dir, file), bytes)
            manifest.put(JSONObject().put("name", name).put("mimeType", mimeType).put("file", file).put("bytes", bytes.size))
        }
        if (manifest.length() > 0) writeSynced(File(dir, MANIFEST_FILE), manifest.toString().toByteArray())
        writeNewMeta(dir, job, staged)
    }

    /** Also used to publish a validated legacy payload without rewriting its attachment files. */
    internal fun writeNewMeta(dir: File, job: Job, staged: Boolean = false) {
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
            .put("attachmentCount", job.mail.attachments.size)
            .put("bodyBytes", File(dir, BODY_FILE).length())
        writeSynced(File(dir, META_TEMP), json.toString().toByteArray())
        if (!staged) publishMeta(dir)
    }

    internal fun publishMeta(dir: File) = move(File(dir, META_TEMP), File(dir, MAIL_FILE))

    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    internal fun atomicText(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        writeSynced(tmp, text.toByteArray())
        move(tmp, file)
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }

    internal fun hasMetadata(dir: File): Boolean = File(dir, MAIL_FILE).exists()

    private fun checkedMeta(dir: File): JSONObject = JSONObject(File(dir, MAIL_FILE).readText()).apply {
        require(getLong("accountId") >= 0L)
        require(getString("clientId").isNotBlank())
        getLong("sendAt")
    }

    private fun validatedMetadata(dir: File): JSONObject? = runCatching {
        checkedMeta(dir).also { json ->
            checkBody(dir, json)
            attachmentFiles(dir, expectedCount(json))
        }
    }.getOrNull()

    private fun expectedCount(json: JSONObject): Int? =
        if (json.has("attachmentCount")) json.getInt("attachmentCount").also { require(it >= 0) } else null

    private fun checkBody(dir: File, json: JSONObject): File = File(dir, BODY_FILE).also { body ->
        require(body.isFile && body.canonicalFile.parentFile == dir.canonicalFile)
        if (json.has("bodyBytes")) require(body.length() == json.getLong("bodyBytes"))
    }

    private fun entry(dir: File): Entry? {
        val status = status(dir)
        if (status == Status.GONE) return null
        val json = runCatching { checkedMeta(dir) }.getOrNull() ?: JSONObject()
        val count = runCatching {
            checkBody(dir, json)
            attachmentFiles(dir, expectedCount(json)).size
        }.getOrNull()
        return Entry(
            dir = dir,
            accountId = json.optLong("accountId", -1L),
            to = json.optString("to"), cc = json.optString("cc"), bcc = json.optString("bcc"),
            subject = json.optString("subject"),
            sendAt = json.optLong("sendAt"),
            draftId = json.optString("draftId").takeIf { it.isNotBlank() },
            attachmentCount = count ?: 0,
            undo = json.optBoolean("undo"),
            status = if (count == null || !json.has("accountId")) Status.CORRUPT else status
        )
    }

    /** Strict, all-or-nothing disk read. A complete terminal failure can still be recovered as a draft. */
    internal fun read(dir: File): Job? {
        if (!hasMetadata(dir)) return null
        return try {
            val json = checkedMeta(dir)
            val body = checkBody(dir, json).readText()
            if (json.has("bodyBytes")) require(body.toByteArray().size.toLong() == json.getLong("bodyBytes"))
            Job(
                accountId = json.getLong("accountId"),
                mail = OutgoingMail(
                    to = json.optString("to"), cc = json.optString("cc"), bcc = json.optString("bcc"),
                    subject = json.optString("subject"),
                    body = body,
                    attachments = readAttachments(dir, expectedCount(json)),
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

    private data class AttachmentFile(val name: String, val mimeType: String, val file: File, val bytes: Long?)

    private fun attachmentFiles(dir: File, expected: Int?, requireManifest: Boolean = false): List<AttachmentFile> {
        val file = File(dir, MANIFEST_FILE)
        val files = checkNotNull(dir.listFiles())
        val namesOnDisk = files.map { it.name }.filterNot { it in controlFiles }.toSet()
        if (!file.exists()) {
            // Old mail.json did not record a count. Stray files are evidence that a manifest was lost;
            // if both the legacy manifest and every attachment disappeared, the old format cannot tell.
            require(!requireManifest && (expected == null || expected == 0) && namesOnDisk.isEmpty())
            return emptyList()
        }
        require(file.isFile && file.canonicalFile.parentFile == dir.canonicalFile)
        val manifest = JSONArray(file.readText())
        if (expected != null) require(manifest.length() == expected)
        val seen = mutableSetOf<String>()
        return (0 until manifest.length()).map { i ->
            val item = manifest.getJSONObject(i)
            val name = item.getString("file")
            require(name in namesOnDisk && seen.add(name))
            val attachment = File(dir, name)
            require(attachment.isFile && attachment.canonicalFile.parentFile == dir.canonicalFile)
            val bytes = if (item.has("bytes")) item.getLong("bytes").also { require(attachment.length() == it) } else null
            AttachmentFile(item.getString("name"), item.getString("mimeType"), attachment, bytes)
        }.also { require(seen == namesOnDisk) }
    }

    private val controlFiles = setOf(MAIL_FILE, META_TEMP, BODY_FILE, "$BODY_FILE.tmp", MANIFEST_FILE,
        FAILED_FILE, CORRUPT_FILE, DONE_FILE, REPLACEMENT_FILE, "$REPLACEMENT_FILE.tmp")

    /** The on-disk manifest shape is shared by old and new writers. Never drop individual attachments. */
    internal fun readAttachments(dir: File, expected: Int? = null, requireManifest: Boolean = false): List<Triple<String, String, ByteArray>> =
        attachmentFiles(dir, expected, requireManifest).map { attachment ->
            val bytes = attachment.file.readBytes()
            if (attachment.bytes != null) require(bytes.size.toLong() == attachment.bytes)
            Triple(attachment.name, attachment.mimeType, bytes)
        }
}
