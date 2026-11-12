package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.repository.FlagWriteTracker.Outcome
import com.dedovmosol.iwomail.eas.MailFlag
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Оптимистичная запись флага: частые нажатия, откат к подтверждённому сервером флагу, защита от устаревших ответов
 * синхронизации. «БД» — одна переменная; часы управляются тестом.
 */
class FlagWriteTrackerTest {

    private var now = 1_000L
    private val tracker = FlagWriteTracker { now }
    private var db = MailFlag.NONE
    private var pendingWrites = 0
    private val on = MailFlag(MailFlag.STATUS_ACTIVE)

    private suspend fun tap(emailId: String = "e") =
        tracker.start(emailId, { db }, { if (it.active) MailFlag.NONE else on }, { pendingWrites++; db = it })!!

    private suspend fun finish(started: FlagWriteTracker.Started, outcome: Outcome, emailId: String = "e"): Outcome? {
        var settled: Outcome? = null
        tracker.finish(emailId, started, outcome) { flag, result ->
            if (flag != null) db = flag
            settled = result
        }
        return settled
    }

    @Test
    fun `tap writes immediately, accepted flag stays`() = runTest {
        val s = tap()
        assertThat(db).isEqualTo(on)

        assertThat(finish(s, Outcome.Applied(on))).isEqualTo(Outcome.Applied(on))
        assertThat(db).isEqualTo(on)
    }

    @Test
    fun `rejected tap rolls back to the server value`() = runTest {
        val s = tap()

        finish(s, Outcome.Rejected)

        assertThat(db).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `no connection keeps the local flag and reports it for the queue`() = runTest {
        val s = tap()

        assertThat(finish(s, Outcome.KeptLocal)).isEqualTo(Outcome.KeptLocal)
        assertThat(db).isEqualTo(on)
    }

    @Test
    fun `only the latest of fast taps settles the database`() = runTest {
        val first = tap()   // off → on
        val second = tap()  // on → off
        assertThat(db).isEqualTo(MailFlag.NONE)
        assertThat(tracker.isLatest("e", first.ticket)).isFalse()
        assertThat(tracker.isLatest("e", second.ticket)).isTrue()

        // Ответ на первое нажатие пришёл позже второго — звезду не трогает
        assertThat(finish(first, Outcome.Applied(on))).isNull()
        assertThat(db).isEqualTo(MailFlag.NONE)

        finish(second, Outcome.Applied(MailFlag.NONE))
        assertThat(db).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `both fast taps rejected end at the server value, not at a stale snapshot`() = runTest {
        val first = tap()
        val second = tap()

        finish(first, Outcome.Rejected)
        finish(second, Outcome.Rejected)

        // Раньше откат второго возвращал снимок «до нажатия» — оптимистичное значение первого (звезда залипала)
        assertThat(db).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `rollback goes to the flag the server accepted last`() = runTest {
        val first = tap()
        finish(first, Outcome.Applied(on))
        val second = tap() // on → off
        val third = tap()  // off → on
        finish(second, Outcome.KeptLocal)

        finish(third, Outcome.Rejected)

        assertThat(db).isEqualTo(on)
    }

    @Test
    fun `server-adjusted flag replaces the optimistic one`() = runTest {
        val s = tap()
        val dated = MailFlag.due(10L, 10L)

        finish(s, Outcome.Applied(dated))

        assertThat(db).isEqualTo(dated)
    }

    @Test
    fun `retrying the same flag does not rewrite the database`() = runTest {
        db = on
        val s = tracker.start("e", { db }, { it }, { pendingWrites++; db = it })!!

        assertThat(pendingWrites).isEqualTo(0)
        assertThat(s.previous).isEqualTo(s.target)
    }

    @Test
    fun `missing email starts nothing`() = runTest {
        assertThat(tracker.start("e", { null }, { on }, { db = it })).isNull()
        assertThat(db).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `sync answer requested before or during the write does not overwrite the star`() = runTest {
        val s = tap()
        now = 2_000
        // Пока запись идёт — значение сервера не пишется, но становится целью отката
        tracker.applyServerFlag("e", MailFlag.NONE, requestedAt = 1_500) { db = it }
        assertThat(db).isEqualTo(on)

        now = 2_500
        finish(s, Outcome.Applied(on))
        // Запрошен до завершения записи — устарел
        tracker.applyServerFlag("e", MailFlag.NONE, requestedAt = 2_400) { db = it }
        assertThat(db).isEqualTo(on)

        // Запрошен после — свежий
        tracker.applyServerFlag("e", MailFlag.NONE, requestedAt = 3_000) { db = it }
        assertThat(db).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `server value seen during the write becomes the rollback target`() = runTest {
        val s = tap()
        val outlook = MailFlag(MailFlag.STATUS_COMPLETE)
        tracker.applyServerFlag("e", outlook, requestedAt = now) { db = it }

        finish(s, Outcome.Rejected)

        assertThat(db).isEqualTo(outlook)
    }

    @Test
    fun `untouched emails take the server flag directly`() = runTest {
        tracker.applyServerFlag("other", on, requestedAt = now) { db = it }

        assertThat(db).isEqualTo(on)
    }

    @Test
    fun `idle entries are forgotten after ten minutes`() = runTest {
        val s = tap()
        finish(s, Outcome.Applied(on))
        now += 11 * 60_000L
        tracker.start("other", { MailFlag.NONE }, { it }, {})

        // Запись «e» забыта: ответ сервера, запрошенный когда угодно, применяется
        tracker.applyServerFlag("e", MailFlag.NONE, requestedAt = 0) { db = it }
        assertThat(db).isEqualTo(MailFlag.NONE)
    }
}
