package com.dedovmosol.iwomail.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    val candidates: Set<String>
)

internal sealed interface CleanupPlan {
    /** Снимка нет, срок сменили или часы ушли назад: запомнить содержимое, ничего не удалять. */
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
        eligibleIds: Set<String> = currentIds
    ): CleanupPlan {
        require(intervalDays > 0) { "intervalDays must be positive" }
        val baseline = CleanupPlan.Baseline(CleanupState(intervalDays, now, currentIds))
        if (state == null || state.intervalDays != intervalDays) return baseline

        val wallElapsed = now.wallMs - state.clock.wallMs
        if (wallElapsed < 0) return baseline

        val sameBoot = state.clock.bootCount >= 0 && state.clock.bootCount == now.bootCount &&
            now.elapsedMs >= state.clock.elapsedMs
        val elapsed = if (sameBoot) minOf(wallElapsed, now.elapsedMs - state.clock.elapsedMs) else wallElapsed
        if (elapsed < intervalDays * ONE_DAY_MS) return CleanupPlan.Wait

        val ids = state.candidates.intersect(currentIds).intersect(eligibleIds)
        return CleanupPlan.Purge(ids, CleanupState(intervalDays, now, currentIds - ids))
    }
}

/**
 * Снимки автоочистки — по файлу на аккаунт и вид папки. Формат строчный (не org.json: он недоступен в JVM-тестах
 * и не нужен для списка id): первая строка `v1|интервал|wall|elapsed|boot`, дальше по id в строке.
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
            if (head.size != 5 || head[0] != VERSION) return null
            CleanupState(
                intervalDays = head[1].toInt(),
                clock = CleanupClock(head[2].toLong(), head[3].toLong(), head[4].toInt()),
                candidates = lines.drop(1).filterTo(HashSet()) { it.isNotEmpty() }
            )
        } catch (e: IOException) {
            null
        } catch (e: NumberFormatException) {
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
            w.write("$VERSION|${state.intervalDays}|${c.wallMs}|${c.elapsedMs}|${c.bootCount}\n")
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

    private companion object {
        const val VERSION = "v1"
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

    interface Ops {
        /** id писем во всех папках этого вида у аккаунта. */
        suspend fun folderEmailIds(accountId: Long, kind: CleanupKind): Set<String>
        /** Черновики на устройстве: id → время последнего сохранения. */
        suspend fun localDrafts(accountId: Long): Map<String, Long>
        /** Окончательно удалить письма (на сервере и локально). false — не удалось. */
        suspend fun deletePermanently(ids: List<String>): Boolean
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
        val now = clock()
        val current: Set<String>
        val eligible: Set<String>
        if (kind == CleanupKind.DRAFTS) {
            val drafts = ops.localDrafts(accountId)
            current = drafts.keys
            val editedBefore = now.wallMs - days * AutoCleanupPlanner.ONE_DAY_MS
            eligible = drafts.filterValues { it in 1..editedBefore }.keys
        } else {
            current = ops.folderEmailIds(accountId, kind)
            eligible = current
        }
        return when (val plan = AutoCleanupPlanner.plan(days, store.read(accountId, kind), now, current, eligible)) {
            CleanupPlan.Wait -> true
            is CleanupPlan.Baseline -> save(accountId, kind, plan.state)
            is CleanupPlan.Purge -> {
                val deleted = plan.ids.isEmpty() || delete(accountId, kind, plan.ids)
                deleted && save(accountId, kind, plan.next)
            }
        }
    }

    private suspend fun delete(accountId: Long, kind: CleanupKind, ids: Set<String>): Boolean =
        if (kind == CleanupKind.DRAFTS) {
            var all = true
            for (id in ids) if (!ops.deleteLocalDraft(accountId, id)) all = false
            all
        } else {
            ids.chunked(BATCH).all { ops.deletePermanently(it) }
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
