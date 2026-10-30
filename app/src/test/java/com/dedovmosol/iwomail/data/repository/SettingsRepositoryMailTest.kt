package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.domain.UndoSend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Настройки почты: картинки из интернета, доверенные отправители, отмена отправки, свайпы. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryMailTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repo = SettingsRepository.createForTesting(
        context,
        PreferenceDataStoreFactory.create(scope = scope) { context.dataStoreFile("mail_${System.nanoTime()}.preferences_pb") },
        startCacheCollector = false
    )

    @After
    fun tearDown() {
        repo.cleanup()
        scope.cancel()
    }

    @Test
    fun `defaults are private by default`() = runBlocking<Unit> {
        val images = repo.remoteImagesPrefs.first()
        assertThat(images.alwaysLoad).isFalse()
        assertThat(images.trustedSenders).isEmpty()
        assertThat(repo.undoSendSeconds.first()).isEqualTo(UndoSend.DEFAULT_SECONDS)
        assertThat(repo.swipeActions.first()).isEqualTo(SwipeAction.DEFAULT_START to SwipeAction.DEFAULT_END)
    }

    @Test
    fun `trusted senders accumulate and reset`() = runBlocking<Unit> {
        repo.trustImagesFrom("a@corp.example")
        repo.trustImagesFrom("b@corp.example")
        repo.trustImagesFrom("a@corp.example")
        repo.trustImagesFrom("")
        assertThat(repo.remoteImagesPrefs.first().trustedSenders).containsExactly("a@corp.example", "b@corp.example")

        repo.clearTrustedImageSenders()
        assertThat(repo.remoteImagesPrefs.first().trustedSenders).isEmpty()

        repo.setRemoteImagesAlways(true)
        assertThat(repo.remoteImagesPrefs.first().alwaysLoad).isTrue()
    }

    @Test
    fun `undo delay and swipe actions are saved`() = runBlocking<Unit> {
        repo.setUndoSendSeconds(0)
        assertThat(repo.undoSendSeconds.first()).isEqualTo(0)
        repo.setUndoSendSeconds(13)
        assertThat(repo.undoSendSeconds.first()).isEqualTo(UndoSend.DEFAULT_SECONDS)

        repo.setSwipeAction(start = true, action = SwipeAction.FLAG)
        repo.setSwipeAction(start = false, action = SwipeAction.NONE)
        assertThat(repo.swipeActions.first()).isEqualTo(SwipeAction.FLAG to SwipeAction.NONE)
    }
}
