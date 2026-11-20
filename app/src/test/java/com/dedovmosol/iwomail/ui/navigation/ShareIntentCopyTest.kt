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

    @Test fun `own provider and file uris are refused and counted (NAV-11)`() {
        // Файл самого приложения (база) — через свой FileProvider, file:// и напрямую
        val database = java.io.File(context.filesDir.parentFile, "databases/mail.db")
        database.parentFile?.mkdirs()
        database.writeBytes(ByteArray(64) { 1 })
        val own = Uri.parse("content://${context.packageName}.fileprovider/root/databases/mail.db")
        val ownMixedCase = Uri.parse("content://${context.packageName.uppercase()}.FILEPROVIDER/x")
        assertThat(com.dedovmosol.iwomail.util.isForeignContentUri(context,
            Uri.parse("content://0@${context.packageName}.fileprovider/file"))).isFalse()
        val file = Uri.fromFile(database)
        shadowOf(context.contentResolver).registerInputStream(own, ByteArrayInputStream(ByteArray(64)))
        shadowOf(context.contentResolver).registerInputStream(file, ByteArrayInputStream(ByteArray(64)))

        val copied = ShareIntentData.copyToLocal(context, listOf(own, ownMixedCase, file, shared("ok", 10)), fileUri)

        assertThat(copied).hasSize(1)
        assertThat(ShareIntentData.skippedCount).isEqualTo(3)
    }

    @Test fun `a provider file name cannot leave the share directory`() {
        assertThat(com.dedovmosol.iwomail.util.safeAttachmentFileName("../../shared_prefs/app.xml"))
            .isEqualTo(".._.._shared_prefs_app.xml")
        assertThat(com.dedovmosol.iwomail.util.safeAttachmentFileName("..")).isEqualTo("file")
        assertThat(com.dedovmosol.iwomail.util.safeAttachmentFileName(" . ")).isEqualTo("file")
        assertThat(com.dedovmosol.iwomail.util.safeAttachmentFileName("a\\b\u0000c.pdf")).isEqualTo("a_b_c.pdf")
        assertThat(com.dedovmosol.iwomail.util.safeAttachmentFileName(null)).isEqualTo("file")
        val long = com.dedovmosol.iwomail.util.safeAttachmentFileName("x".repeat(500) + ".docx")
        assertThat(long.length).isAtMost(120)
        assertThat(long).endsWith(".docx")
        // Копия — прямо в каталоге «Поделиться»
        val dir = java.io.File(context.cacheDir, com.dedovmosol.iwomail.util.SHARE_ATTACHMENTS_DIR)
        val child = java.io.File(dir, com.dedovmosol.iwomail.util.safeAttachmentFileName("../../evil"))
        assertThat(child.canonicalFile.parentFile).isEqualTo(dir.canonicalFile)
    }

    @Test fun `clear resets the skipped counter`() {
        ShareIntentData.copyToLocal(context, listOf(Uri.parse("content://com.example.cloud/files/missing")))
        ShareIntentData.clear()
        assertThat(ShareIntentData.skippedCount).isEqualTo(0)
    }
}
