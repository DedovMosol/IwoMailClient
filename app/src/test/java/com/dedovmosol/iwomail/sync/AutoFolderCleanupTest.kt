package com.dedovmosol.iwomail.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DATA-01 / DATA-02: автоочистка не должна удалять при первой проверке и не должна удалять то, что попало в папку
 * позже прошлой проверки. Время и папки — подменные, без Room и сети.
 */
class AutoFolderCleanupTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = AutoCleanupPlanner.ONE_DAY_MS
    private val t0 = 1_763_337_600_000L // 2025-11-17 00:00 UTC

    private lateinit var store: AutoCleanupStateStore
    private lateinit var ops: FakeOps
    private var now = CleanupClock(t0, 1_000L, 7)

    private fun cleanup() = AutoFolderCleanup(store, { now }, ops)

    private fun settings(trash: Int = 0, spam: Int = 0, drafts: Int = 0, id: Long = 1) =
        AutoFolderCleanup.AccountSettings(id, trash, spam, drafts)

    /** Сдвиг вперёд в той же загрузке: оба времени идут вместе. */
    private fun advance(ms: Long) {
        now = now.copy(wallMs = now.wallMs + ms, elapsedMs = now.elapsedMs + ms)
    }

    @Before fun setUp() {
        store = AutoCleanupStateStore(File(tmp.root, "auto_cleanup"))
        ops = FakeOps()
    }

    @Test fun `first check after install deletes nothing and remembers folder`() = runTest {
        ops.trash += setOf("1_a", "1_b")

        assertThat(cleanup().run(listOf(settings(trash = 60)))).isTrue()

        assertThat(ops.deleted).isEmpty()
        assertThat(store.read(1, CleanupKind.TRASH)!!.candidates).containsExactly("1_a", "1_b")
    }

    @Test fun `after interval only items present at previous check are deleted`() = runTest {
        ops.trash += setOf("1_old")
        cleanup().run(listOf(settings(trash = 7)))
        advance(7 * day - 60_000)
        ops.trash += "1_new" // удалено в корзину за минуту до очистки
        advance(60_000)

        cleanup().run(listOf(settings(trash = 7)))

        assertThat(ops.deleted).containsExactly("1_old")
        assertThat(ops.trash).containsExactly("1_new")
        // Новое письмо — кандидат следующего цикла
        advance(7 * day)
        cleanup().run(listOf(settings(trash = 7)))
        assertThat(ops.deleted).containsExactly("1_old", "1_new")
    }

    @Test fun `same id after a completed reset must age again`() = runTest {
        val account = listOf(settings(trash = 7))
        ops.trash += "1_a"
        ops.resetEpochs["1_trash"] = "old-epoch"
        cleanup().run(account)
        advance(7 * day)
        ops.resetEpochs["1_trash"] = "new-epoch" // The new message reused the old row ID.

        assertThat(cleanup().run(account)).isTrue()
        assertThat(ops.deleted).isEmpty()
        assertThat(store.read(1, CleanupKind.TRASH)?.resetEpochs).isEqualTo(mapOf("1_trash" to "new-epoch"))

        advance(7 * day)
        assertThat(cleanup().run(account)).isTrue()
        assertThat(ops.deleted).containsExactly("1_a")
    }

    @Test fun `reset between snapshot and delete rejects old epoch and preserves baseline`() = runTest {
        val account = listOf(settings(spam = 7))
        ops.spam += "1_a"
        cleanup().run(account)
        val oldState = store.read(1, CleanupKind.SPAM)
        advance(7 * day)
        ops.beforeDelete = { ops.resetEpochs["1_spam"] = "new-epoch" }

        assertThat(cleanup().run(account)).isFalse()
        assertThat(ops.deleted).isEmpty()
        assertThat(store.read(1, CleanupKind.SPAM)).isEqualTo(oldState)

        assertThat(cleanup().run(account)).isTrue()
        assertThat(ops.deleted).isEmpty()
    }

    @Test fun `legacy mail state is rebaselined but local drafts keep their age`() = runTest {
        ops.trash += "1_a"
        ops.drafts["1_d"] = t0 - 30 * day
        store.write(1, CleanupKind.TRASH, CleanupState(7, now, setOf("1_a")))
        store.write(1, CleanupKind.DRAFTS, CleanupState(7, now, setOf("1_d")))
        advance(7 * day)

        assertThat(cleanup().run(listOf(settings(trash = 7, drafts = 7)))).isTrue()
        assertThat(ops.deleted).isEmpty()
        assertThat(ops.deletedDrafts).containsExactly("1_d")
        assertThat(store.read(1, CleanupKind.TRASH)?.resetEpochs).isEqualTo(mapOf("1_trash" to null))
    }

    @Test fun `just before interval nothing is deleted`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 3)))
        advance(3 * day - 1)

        cleanup().run(listOf(settings(trash = 3)))

        assertThat(ops.deleted).isEmpty()
    }

    @Test fun `changing interval restarts the period`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 60)))
        advance(50 * day)

        cleanup().run(listOf(settings(trash = 3))) // сменили 60 → 3 дня

        assertThat(ops.deleted).isEmpty()
        advance(3 * day)
        cleanup().run(listOf(settings(trash = 3)))
        assertThat(ops.deleted).containsExactly("1_a")
    }

    @Test fun `wall clock moved back restarts the period`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 7)))
        now = now.copy(wallMs = now.wallMs - 365 * day, elapsedMs = now.elapsedMs + 60_000)

        cleanup().run(listOf(settings(trash = 7)))
        assertThat(ops.deleted).isEmpty()
        // Отсчёт пошёл от новых показаний часов
        advance(7 * day)
        cleanup().run(listOf(settings(trash = 7)))
        assertThat(ops.deleted).containsExactly("1_a")
    }

    @Test fun `wall clock moved forward within the same boot does not trigger cleanup`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 7)))
        now = now.copy(wallMs = now.wallMs + 365 * day, elapsedMs = now.elapsedMs + 3_600_000) // прошёл час

        cleanup().run(listOf(settings(trash = 7)))

        assertThat(ops.deleted).isEmpty()
    }

    @Test fun `after reboot wall time is used`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 7)))
        now = CleanupClock(now.wallMs + 8 * day, elapsedMs = 5_000L, bootCount = 8)

        cleanup().run(listOf(settings(trash = 7)))

        assertThat(ops.deleted).containsExactly("1_a")
    }

    @Test fun `unknown boot count falls back to wall time`() = runTest {
        now = now.copy(bootCount = -1)
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 7)))
        advance(7 * day)

        cleanup().run(listOf(settings(trash = 7)))

        assertThat(ops.deleted).containsExactly("1_a")
    }

    @Test fun `failed server deletion keeps snapshot and retries next time`() = runTest {
        ops.trash += setOf("1_a", "1_b")
        cleanup().run(listOf(settings(trash = 7)))
        advance(7 * day)
        ops.failDeletes = true

        assertThat(cleanup().run(listOf(settings(trash = 7)))).isFalse()
        assertThat(ops.deleted).isEmpty()

        ops.failDeletes = false
        advance(60_000)
        assertThat(cleanup().run(listOf(settings(trash = 7)))).isTrue()
        assertThat(ops.deleted).containsExactly("1_a", "1_b")
    }

    @Test fun `spam is cleaned independently from trash`() = runTest {
        ops.trash += "1_t"
        ops.spam += "1_s"
        cleanup().run(listOf(settings(trash = 0, spam = 3)))
        advance(3 * day)

        cleanup().run(listOf(settings(trash = 0, spam = 3)))

        assertThat(ops.deleted).containsExactly("1_s")
    }

    @Test fun `large trash is deleted in batches of 200`() = runTest {
        ops.trash += (1..450).map { "1_$it" }
        cleanup().run(listOf(settings(trash = 3)))
        advance(3 * day)

        cleanup().run(listOf(settings(trash = 3)))

        assertThat(ops.batches.map { it.size }).containsExactly(200, 200, 50)
        assertThat(ops.deleted).hasSize(450)
    }

    @Test fun `draft edited during the period survives even if it was in the snapshot`() = runTest {
        ops.drafts["1_d_old"] = t0 - 30 * day
        ops.drafts["1_d_edited"] = t0 - 30 * day
        cleanup().run(listOf(settings(drafts = 7)))
        advance(6 * day)
        ops.drafts["1_d_edited"] = now.wallMs // пользователь открыл и сохранил
        advance(day)

        cleanup().run(listOf(settings(drafts = 7)))

        assertThat(ops.deletedDrafts).containsExactly("1_d_old")
        assertThat(ops.drafts.keys).containsExactly("1_d_edited")
    }

    @Test fun `draft with unknown save time is never deleted`() = runTest {
        ops.drafts["1_d"] = 0L
        cleanup().run(listOf(settings(drafts = 3)))
        advance(30 * day)

        cleanup().run(listOf(settings(drafts = 3)))

        assertThat(ops.deletedDrafts).isEmpty()
    }

    @Test fun `first check after install keeps all local drafts`() = runTest {
        ops.drafts["1_d1"] = t0 - 400 * day
        ops.drafts["1_d2"] = t0 - day

        cleanup().run(listOf(settings(trash = 60, spam = 60, drafts = 60)))

        assertThat(ops.deletedDrafts).isEmpty()
        assertThat(ops.deleted).isEmpty()
    }

    @Test fun `turning cleanup off forgets the snapshot`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 7)))
        cleanup().run(listOf(settings(trash = 0)))
        advance(7 * day)

        cleanup().run(listOf(settings(trash = 7))) // включили снова — новый отсчёт

        assertThat(ops.deleted).isEmpty()
    }

    @Test fun `corrupted state file is treated as missing`() = runTest {
        ops.trash += "1_a"
        cleanup().run(listOf(settings(trash = 7)))
        File(tmp.root, "auto_cleanup/1_trash.state").writeText("garbage\n1_a\n")
        advance(7 * day)

        cleanup().run(listOf(settings(trash = 7)))

        assertThat(ops.deleted).isEmpty()
    }

    @Test fun `state of deleted accounts is removed`() = runTest {
        ops.trash += setOf("1_a", "2_a")
        cleanup().run(listOf(settings(trash = 7, id = 1), settings(trash = 7, id = 2)))
        File(tmp.root, "auto_cleanup/2_trash.state.tmp").writeText("x")

        cleanup().run(listOf(settings(trash = 7, id = 1)))

        assertThat(File(tmp.root, "auto_cleanup").list()!!.toList()).containsExactly("1_trash.state")
    }

    @Test fun `store round trip keeps all ids`() {
        val ids = (1..20_000).map { "42_5:$it" }.toSet()
        val state = CleanupState(14, CleanupClock(t0, 123L, 9), ids, mapOf("42_spam|папка" to "epoch", "42_junk" to null))
        store.write(42, CleanupKind.SPAM, state)

        assertThat(store.read(42, CleanupKind.SPAM)).isEqualTo(state)
        assertThat(File(tmp.root, "auto_cleanup").list()!!.toList()).containsExactly("42_spam.state")
    }

    @Test fun `planner deletes only candidates still in folder and eligible`() {
        val state = CleanupState(7, CleanupClock(t0, 0, 1), setOf("a", "b", "c"))
        val plan = AutoCleanupPlanner.plan(
            7, state, CleanupClock(t0 + 7 * day, 7 * day, 1),
            currentIds = setOf("b", "c", "d"), eligibleIds = setOf("c", "d")
        )
        assertThat(plan).isEqualTo(
            CleanupPlan.Purge(setOf("c"), CleanupState(7, CleanupClock(t0 + 7 * day, 7 * day, 1), setOf("b", "d")))
        )
    }

    private class FakeOps : AutoFolderCleanup.Ops {
        val trash = mutableSetOf<String>()
        val spam = mutableSetOf<String>()
        val drafts = mutableMapOf<String, Long>()
        val deleted = mutableListOf<String>()
        val deletedDrafts = mutableListOf<String>()
        val batches = mutableListOf<List<String>>()
        val resetEpochs = mutableMapOf<String, String>()
        var beforeDelete: (() -> Unit)? = null
        var failDeletes = false

        override suspend fun folderSnapshots(accountId: Long, kind: CleanupKind): List<AutoFolderCleanup.FolderSnapshot> {
            val src = if (kind == CleanupKind.SPAM) spam else trash
            val folderId = "${accountId}_${kind.key}"
            return listOf(AutoFolderCleanup.FolderSnapshot(folderId, resetEpochs[folderId], src.filter { it.startsWith("${accountId}_") }))
        }

        override suspend fun localDrafts(accountId: Long): Map<String, Long> =
            drafts.filterKeys { it.startsWith("${accountId}_") }

        override suspend fun deletePermanently(ids: List<String>, expectedResetEpochs: Map<String, String?>): Boolean {
            beforeDelete?.also { beforeDelete = null }?.invoke()
            if (failDeletes) return false
            for (id in ids) {
                val kind = if (id in spam) CleanupKind.SPAM else CleanupKind.TRASH
                val folderId = "${id.substringBefore('_')}_${kind.key}"
                if (folderId !in expectedResetEpochs || expectedResetEpochs[folderId] != resetEpochs[folderId]) return false
            }
            batches += ids
            deleted += ids
            trash.removeAll(ids.toSet())
            spam.removeAll(ids.toSet())
            return true
        }

        override suspend fun deleteLocalDraft(accountId: Long, emailId: String): Boolean {
            if (failDeletes) return false
            deletedDrafts += emailId
            drafts.remove(emailId)
            return true
        }
    }
}
