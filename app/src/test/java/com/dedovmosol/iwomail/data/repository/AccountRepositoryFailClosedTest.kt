package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.security.EncryptedStoreProvider
import com.dedovmosol.iwomail.data.security.PasswordStorage
import com.dedovmosol.iwomail.data.security.SecurityTelemetry
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AccountRepositoryFailClosedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        EncryptedStoreProvider.forceInsecureForTesting = true
        PasswordStorage.resetForTesting()
        SecurityTelemetry.resetForTesting()
        SecurityTelemetry.getInstance(context).setFailClosedMode(true)
        PasswordStorage.getInstance(context)
    }

    @After
    fun tearDown() {
        EncryptedStoreProvider.forceInsecureForTesting = false
        PasswordStorage.resetForTesting()
        SecurityTelemetry.resetForTesting()
    }

    @Test
    fun `addAccount does not leave a mailbox row when fail-closed blocks the password`() = runBlocking {
        val repo = AccountRepository(context)
        val before = repo.getAccountCount()
        val email = "failclosed-${System.nanoTime()}@corp.example"
        val result = repo.addAccount(
            email = email,
            displayName = "Fail Closed",
            serverUrl = "mail.corp.example",
            username = "failclosed",
            password = "secret",
            domain = "",
            acceptAllCerts = true,
            color = 0xFF1976D2.toInt(),
            accountType = AccountType.EXCHANGE,
            alreadyVerified = true
        )
        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).isEqualTo("FAIL_CLOSED")
        assertThat(repo.getAccountCount()).isEqualTo(before)
        val leftover = MailDatabase.getInstance(context).accountDao().getAllAccountsList()
            .any { it.email.equals(email, ignoreCase = true) }
        assertThat(leftover).isFalse()
    }
}
