package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.data.model.ServerProblemType
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/** Состояние «основной / резервный» по исходам синхронизации (Dual-URL). */
class AccountServerHealthRepositoryTest {

    private val id = 42L

    @Before
    fun setUp() = AccountServerHealthRepository.reset()

    private fun fail(usedFallback: Boolean = false, bothFailed: Boolean = false, kind: ErrorKind = ErrorKind.Network) =
        AccountServerHealthRepository.reportSyncOutcome(
            id, success = false, errorKind = kind, usedFallback = usedFallback, hasAlternateUrl = true, bothFailed = bothFailed
        )

    private val health get() = AccountServerHealthRepository.healthSnapshot(id)

    @Test
    fun `success through the backup server shows the backup state immediately`() {
        AccountServerHealthRepository.reportSyncOutcome(id, success = true, usedFallback = true, hasAlternateUrl = true)

        assertThat(health.problemType).isEqualTo(ServerProblemType.PrimaryDownUsingFallback)
        assertThat(health.isUsingFallback).isTrue()
        assertThat(health.showBanner).isFalse() // почта идёт — без тревоги
    }

    @Test
    fun `primary failures with an unreachable backup are both servers down, not using backup`() {
        repeat(3) { fail(bothFailed = true) }

        assertThat(health.problemType).isEqualTo(ServerProblemType.BothServersDown)
        assertThat(health.primaryConsecutiveFailures).isEqualTo(3)
        assertThat(health.alternateConsecutiveFailures).isEqualTo(3)
    }

    @Test
    fun `primary failures without a working backup are not reported as using the backup`() {
        repeat(3) { fail() }

        assertThat(health.problemType).isEqualTo(ServerProblemType.PrimaryDownNoFallback)
    }

    @Test
    fun `backup keeps working while the primary stays down`() {
        repeat(3) { fail() }
        AccountServerHealthRepository.reportSyncOutcome(id, success = true, usedFallback = true, hasAlternateUrl = true)
        fail(usedFallback = true) // разовый сбой на резервном — основной всё ещё лежит

        assertThat(health.problemType).isEqualTo(ServerProblemType.PrimaryDownUsingFallback)
        assertThat(health.primaryConsecutiveFailures).isEqualTo(3)
    }

    @Test
    fun `success through the primary clears everything`() {
        repeat(3) { fail(bothFailed = true) }
        AccountServerHealthRepository.reportSyncOutcome(id, success = true, hasAlternateUrl = true)

        assertThat(health.problemType).isEqualTo(ServerProblemType.None)
        assertThat(health.primaryConsecutiveFailures).isEqualTo(0)
        assertThat(health.alternateConsecutiveFailures).isEqualTo(0)
        assertThat(health.isUsingFallback).isFalse()
    }

    @Test
    fun `auth and certificate errors are reported immediately`() {
        fail(kind = ErrorKind.Auth)
        assertThat(health.problemType).isEqualTo(ServerProblemType.AuthError)
        fail(kind = ErrorKind.Cert)
        assertThat(health.problemType).isEqualTo(ServerProblemType.CertError)
    }
}
