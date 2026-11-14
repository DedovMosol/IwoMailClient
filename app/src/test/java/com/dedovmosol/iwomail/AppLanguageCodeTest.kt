package com.dedovmosol.iwomail

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppLanguageCodeTest {

    @After
    fun tearDown() {
        AppLanguageCode.persist(ApplicationProvider.getApplicationContext(), "ru")
    }

    @Test
    fun `unknown codes fall back to russian`() {
        AppLanguageCode.set("de")
        assertThat(AppLanguageCode.isRussian()).isTrue()
        AppLanguageCode.set("en")
        assertThat(AppLanguageCode.isRussian()).isFalse()
    }

    @Test
    fun `load restores the last persisted code`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLanguageCode.persist(context, "en")
        AppLanguageCode.set("ru")
        assertThat(AppLanguageCode.isRussian()).isTrue()
        AppLanguageCode.load(context)
        assertThat(AppLanguageCode.isRussian()).isFalse()
    }
}
