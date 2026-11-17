package com.dedovmosol.iwomail.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** NAV-12: копирование файлов «Поделиться» останавливается на лимите письма и сообщает, сколько файлов не добавлено. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = ShareIntentData.clear()

    // Как в AttachmentLoaderTest: логика копирования не должна зависеть от FileProvider в Robolectric
    private val fileUri: (java.io.File) -> Uri = { Uri.fromFile(it) }

    private fun shared(name: String, bytes: Int): Uri {
        val uri = Uri.parse("content://com.example.cloud/files/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(bytes) { 7 }))
        return uri
    }

    @Test fun `files beyond the 10 MB message limit are not copied and are counted`() {
        val uris = listOf(shared("a", 4_000_000), shared("b", 4_000_000), shared("c", 4_000_000), shared("d", 100))

        val copied = ShareIntentData.copyToLocal(context, uris, fileUri)

        // «c» не помещается (8 МБ + 4 МБ > 10 МБ), «d» в остаток помещается
        assertThat(copied).hasSize(3)
        assertThat(ShareIntentData.skippedCount).isEqualTo(1)
    }

    @Test fun `unreadable file is counted, readable ones are copied`() {
        val missing = Uri.parse("content://com.example.cloud/files/missing")

        val copied = ShareIntentData.copyToLocal(context, listOf(shared("ok", 10), missing), fileUri)

        assertThat(copied).hasSize(1)
        assertThat(ShareIntentData.skippedCount).isEqualTo(1)
    }

    @Test fun `clear resets the skipped counter`() {
        ShareIntentData.copyToLocal(context, listOf(Uri.parse("content://com.example.cloud/files/missing")))
        ShareIntentData.clear()
        assertThat(ShareIntentData.skippedCount).isEqualTo(0)
    }
}
