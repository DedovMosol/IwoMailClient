package com.dedovmosol.iwomail.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Автоочистка корзины, спама и черновиков на устройстве («раз в N дней»).
 *
 * Правило: удаляется только то, что лежало в папке уже при прошлой проверке, не меньше N дней назад
 * (снимок id в [AutoCleanupStateStore]). Поэтому письмо, удалённое в корзину минуту назад, переживёт
 * ближайшую очистку, а первая очистка после установки, включения или смены срока ничего не удаляет —
 * она только запоминает содержимое. Черновик удаляется, только если его не правили N дней.
 *
 * Время: настенные часы можно перевести. Назад — снимок начинается заново. Вперёд — в пределах одной загрузки
 * устройства прошедшее время берётся по `elapsedRealtime` (его перевести нельзя), см. [AutoCleanupPlanner].
 */
internal enum class CleanupKind(val key: String) { TRASH("trash"), SPAM("spam"), DRAFTS("drafts") }

/** Момент проверки: настенное время, монотонное время с загрузки и номер загрузки (-1 — неизвестен). */
internal data class CleanupClock(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

/** Снимок папки на момент [clock]: [candidates] — id, которые можно удалить, когда пройдёт [intervalDays] дней. */
internal data class CleanupState(
    val intervalDays: Int,
    val clock: CleanupClock,
    val candidates: Set<String>,
    // null — черновики или старый формат; значение null в карте — папка до первого сброса.
    val resetEpochs: Map<String, String?>? = null
)

internal sealed interface CleanupPlan {
    /** Снимка нет, срок или эпоха сменились либо часы ушли назад: ничего не удалять. */
    data class Baseline(val state: CleanupState) : CleanupPlan
    /** Срок не прошёл. */
    data object Wait : CleanupPlan
    /** Удалить [ids]; после успеха сохранить [next]. При сбое снимок остаётся прежним — повтор при следующей проверке. */
    data class Purge(val ids: Set<String>, val next: CleanupState) : CleanupPlan
}

internal object AutoCleanupPlanner {

    const val ONE_DAY_MS = 24L * 60 * 60 * 1000

    /**
     * @param currentIds всё, что сейчас в папке (станет следующим снимком)
     * @param eligibleIds то из [currentIds], что по своему признаку уже можно удалять (черновик не правили N дней);
     *   для корзины и спама — то же, что [currentIds]
     */
    fun plan(
        intervalDays: Int,
        state: CleanupState?,
        now: CleanupClock,
        currentIds: Set<String>,
        eligibleIds: Set<String> = currentIds,
        resetEpochs: Map<String, String?>? = null
    ): CleanupPlan {
        require(intervalDays > 0) { "intervalDays must be positive" }
        val baseline = CleanupPlan.Baseline(CleanupState(intervalDays, now, currentIds, resetEpochs))
        if (state == null || state.intervalDays != intervalDays || state.resetEpochs != resetEpochs) return baseline

        val wallElapsed = now.wallMs - state.clock.wallMs
        if (wallElapsed < 0) return baseline

        val sameBoot = state.clock.bootCount >= 0 && state.clock.bootCount == now.bootCount &&
            now.elapsedMs >= state.clock.elapsedMs
        val elapsed = if (sameBoot) minOf(wallElapsed, now.elapsedMs - state.clock.elapsedMs) else wallElapsed
        if (elapsed < intervalDays * ONE_DAY_MS) return CleanupPlan.Wait

        val ids = state.candidates.intersect(currentIds).intersect(eligibleIds)
        return CleanupPlan.Purge(ids, baseline.state.copy(candidates = currentIds - ids))
    }
}

/**
 * Снимки автоочистки — по файлу на аккаунт и вид папки. Заголовок v2 дополняет v1 числом строк `папка|эпоха`
 * перед списком id. Пустая эпоха означает, что папку ещё не сбрасывали. Черновики сохраняют формат v1.
 * Запись атомарная (временный файл + переименование): оборванная запись не оставит полснимка.
 * Нечитаемый файл — как отсутствующий: очистка начнёт снимок заново, то есть ничего лишнего не удалит.
 */
internal class AutoCleanupStateStore(private val dir: File) {

    private fun file(accountId: Long, kind: CleanupKind) = File(dir, "${accountId}_${kind.key}.state")

    fun read(accountId: Long, kind: CleanupKind): CleanupState? {
        val f = file(accountId, kind)
        if (!f.isFile) return null
        return try {
            val lines = f.readLines(Charsets.UTF_8)
            val head = lines.firstOrNull()?.split('|') ?: return null
            val epochs: Map<String, String?>?
            val epochCount: Int
            when (head[0]) {
                "v1" -> {
                    if (head.size != 5) return null
                    epochs = null
                    epochCount = 0
                }
                "v2" -> {
                    if (head.size != 6) return null
                    epochCount = head[5].toInt()
                    if (epochCount !in 0 until lines.size) return null
                    val bindings = HashMap<String, String?>()
                    for (i in 1..epochCount) {
                        val entry = lines[i].split('|')
                        if (entry.size != 2) return null
                        val folderId = Base64.getUrlDecoder().decode(entry[0]).toString(Charsets.UTF_8)
                        if (folderId.isEmpty() || folderId in bindings) return null
                        bindings[folderId] = entry[1].ifEmpty { null }
                    }
                    epochs = bindings
                }
                else -> return null
            }
            CleanupState(
                intervalDays = head[1].toInt(),
                clock = CleanupClock(head[2].toLong(), head[3].toLong(), head[4].toInt()),
                candidates = lines.drop(1 + epochCount).filterTo(HashSet()) { it.isNotEmpty() },
                resetEpochs = epochs
            )
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** @throws IOException запись не удалась (прежний снимок при этом не испорчен) */
    fun write(accountId: Long, kind: CleanupKind, state: CleanupState) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val target = file(accountId, kind)
        val tmp = File(dir, target.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            val c = state.clock
            val epochs = state.resetEpochs
            val clock = "${state.intervalDays}|${c.wallMs}|${c.elapsedMs}|${c.bootCount}"
            if (epochs == null) {
                w.write("v1|$clock\n")
            } else {
                w.write("v2|$clock|${epochs.size}\n")
                for ((folderId, epoch) in epochs) {
                    require(epoch == null || (epoch.isNotEmpty() && '\n' !in epoch && '\r' !in epoch && '|' !in epoch))
                    val encodedId = Base64.getUrlEncoder().encodeToString(folderId.toByteArray(Charsets.UTF_8))
                    w.write("$encodedId|${epoch.orEmpty()}\n")
                }
            }
            for (id in state.candidates) {
                require('\n' !in id && '\r' !in id) { "id with line break" }
                w.write(id)
                w.write("\n")
            }
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete(accountId: Long, kind: CleanupKind) {
        file(accountId, kind).delete()
    }

    /** Снимки удалённых аккаунтов (и брошенные временные файлы) больше не нужны. */
    fun retainAccounts(accountIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val accountId = f.name.substringBefore('_').toLongOrNull()
            if (f.name.endsWith(".tmp") || accountId == null || accountId !in accountIds) f.delete()
        }
    }
}

/**
 * Выполняет автоочистку по аккаунтам. Зависимости — функциями, чтобы логику проверять без Room и сети.
 */
internal class AutoFolderCleanup(
    private val store: AutoCleanupStateStore,
    private val clock: () -> CleanupClock,
    private val ops: Ops
) {
    data class AccountSettings(val accountId: Long, val trashDays: Int, val spamDays: Int, val draftsDays: Int)
    data class FolderSnapshot(val folderId: String, val resetEpoch: String?, val emailIds: List<String>)

    interface Ops {
        /** Согласованный снимок всех папок этого вида; null — полная синхронизация ещё не закончилась. */
        suspend fun folderSnapshots(accountId: Long, kind: CleanupKind): List<FolderSnapshot>?
        /** Черновики на устройстве: id → время последнего сохранения. */
        suspend fun localDrafts(accountId: Long): Map<String, Long>
        /** Окончательно удалить письма только из ожидаемой папки и эпохи. false — не удалось. */
        suspend fun deletePermanently(ids: List<String>, expectedResetEpochs: Map<String, String?>): Boolean
        /** Удалить черновик на устройстве вместе с файлами вложений. false — не удалось. */
        suspend fun deleteLocalDraft(accountId: Long, emailId: String): Boolean
    }

    /** @return true — все проверки прошли без ошибок */
    suspend fun run(accounts: List<AccountSettings>): Boolean {
        store.retainAccounts(accounts.mapTo(HashSet()) { it.accountId })
        var ok = true
        for (a in accounts) {
            ok = runKind(a.accountId, CleanupKind.TRASH, a.trashDays) && ok
            ok = runKind(a.accountId, CleanupKind.SPAM, a.spamDays) && ok
            ok = runKind(a.accountId, CleanupKind.DRAFTS, a.draftsDays) && ok
        }
        return ok
    }

    private suspend fun runKind(accountId: Long, kind: CleanupKind, days: Int): Boolean {
        if (days <= 0) {
            store.delete(accountId, kind)
            return true
        }
        val folders = if (kind == CleanupKind.DRAFTS) {
            null
        } else {
            ops.folderSnapshots(accountId, kind) ?: return true
        }
        val now = clock()
        val current: Set<String>
        val eligible: Set<String>
        if (folders == null) {
            val drafts = ops.localDrafts(accountId)
            current = drafts.keys
            val editedBefore = now.wallMs - days * AutoCleanupPlanner.ONE_DAY_MS
            eligible = drafts.filterValues { it in 1..editedBefore }.keys
        } else {
            current = folders.flatMapTo(HashSet()) { it.emailIds }
            eligible = current
        }
        val epochs = folders?.associate { it.folderId to it.resetEpoch }
        return when (val plan = AutoCleanupPlanner.plan(days, store.read(accountId, kind), now, current, eligible, epochs)) {
            CleanupPlan.Wait -> true
            is CleanupPlan.Baseline -> save(accountId, kind, plan.state)
            is CleanupPlan.Purge -> {
                val deleted = plan.ids.isEmpty() || if (folders == null) {
                    deleteDrafts(accountId, plan.ids)
                } else {
                    deleteServerMail(folders, plan.ids)
                }
                deleted && save(accountId, kind, plan.next)
            }
        }
    }

    private suspend fun deleteDrafts(accountId: Long, ids: Set<String>): Boolean {
        var all = true
        for (id in ids) if (!ops.deleteLocalDraft(accountId, id)) all = false
        return all
    }

    private suspend fun deleteServerMail(folders: List<FolderSnapshot>, ids: Set<String>): Boolean =
        folders.all { folder ->
            // A move to any other folder after the snapshot must fail the Delete gate as well.
            val expected = mapOf(folder.folderId to folder.resetEpoch)
            folder.emailIds.asSequence().filter { it in ids }.chunked(BATCH).all { ops.deletePermanently(it, expected) }
        }

    private fun save(accountId: Long, kind: CleanupKind, state: CleanupState): Boolean = try {
        store.write(accountId, kind, state)
        true
    } catch (e: IOException) {
        android.util.Log.w(TAG, "Cannot save cleanup state ${kind.key} for $accountId", e)
        false
    } catch (e: IllegalArgumentException) {
        android.util.Log.w(TAG, "Cannot save cleanup state ${kind.key} for $accountId", e)
        false
    }

    private companion object {
        const val TAG = "AutoFolderCleanup"
        const val BATCH = 200
    }
}
