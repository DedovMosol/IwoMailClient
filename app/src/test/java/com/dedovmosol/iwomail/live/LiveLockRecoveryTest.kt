package com.dedovmosol.iwomail.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.security.PasswordStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NAV-04 на настоящем Exchange: пароль учётной записи для сброса блокировки, когда сохранённого пароля нет
 * (хранилище ключей сброшено) — проверяется входом на сервер. Верный — true, неверный — false (401), не null.
 * Без IWO_LIVE_* тест пропускается. Неверная попытка одна: не приближает учётную запись к блокировке AD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveLockRecoveryTest {

    private val host = System.getenv("IWO_LIVE_HOST")
    private val domain = System.getenv("IWO_LIVE_DOMAIN").orEmpty()
    private val user = System.getenv("IWO_LIVE_USER")
    private val pass = System.getenv("IWO_LIVE_PASS")
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var accountId = 0L

    @Before
    fun setUp() {
        assumeTrue("live server not configured", !host.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
        PasswordStorage.resetForTesting()
        accountId = runBlocking {
            MailDatabase.getInstance(context).accountDao().insert(
                AccountEntity(
                    email = "$user@live.test", displayName = "Live", serverUrl = host!!, username = user!!,
                    domain = domain, acceptAllCerts = true
                )
            )
        }
        // Пароля на устройстве нет — как после сброса хранилища ключей
        assertThat(PasswordStorage.getInstance(context).getPassword(accountId)).isNull()
    }

    @After
    fun tearDown() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
        PasswordStorage.resetForTesting()
    }

    @Test
    fun `the server confirms the right password and rejects a wrong one`() = runBlocking {
        val repo = RepositoryProvider.getAccountRepository(context)
        assertThat(repo.verifyAccountPassword(accountId, pass!!)).isTrue()
        assertThat(repo.verifyAccountPassword(accountId, pass + "-wrong")).isFalse()
    }
}
