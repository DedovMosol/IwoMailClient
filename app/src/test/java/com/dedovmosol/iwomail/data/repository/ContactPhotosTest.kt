package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.eas.ContactPictures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Кэш фото из адресной книги: на диске настоящие файлы (временный каталог), сервер — подменённый запрос
 * ResolveRecipients, время — управляемые часы. Проверяются пакеты запросов, сроки, «фото нет», неподдерживаемый
 * сервер, пауза после сбоя, выключение и удаление аккаунта.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactPhotosTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val photo = byteArrayOf(1, 2, 3)
    private val start = 1_700_000_000_000L
    private var now = start

    /** Запросы к серверу: аккаунт и адреса. */
    private val asked = mutableListOf<Pair<Long, List<String>>>()
    private var answer: suspend (Long, List<String>) -> ContactPictures.Lookup = { _, addresses ->
        ContactPictures.Lookup.Found(addresses.associateWith { if (it.startsWith("boss")) photo else null })
    }

    private val enabled = MutableStateFlow(true)

    /** Бесконечные циклы кэша (настройка, очередь) — не в backgroundScope: его задачи advanceUntilIdle не выполняет. */
    private val jobs = mutableListOf<Job>()

    @After
    fun cancelLoops() = jobs.forEach { it.cancel() }

    private fun TestScope.photos(root: File = folder.root): ContactPhotos {
        val io = StandardTestDispatcher(testScheduler)
        return ContactPhotos(
            root = root,
            enabledFlow = enabled,
            lookup = { accountId, addresses ->
                asked += accountId to addresses
                answer(accountId, addresses)
            },
            scope = CoroutineScope(io + Job().also { jobs += it }),
            clock = { now },
            batchDelayMs = 300,
            io = io
        ).also { advanceUntilIdle() }
    }

    private fun ContactPhotos.photoOf(accountId: Long, address: String): File? = photos.value[ContactPhotos.key(accountId, address)]

    @Test
    fun `visible addresses are asked in one request and cached on disk`() = runTest {
        val photos = photos()

        photos.request(1, "Босс <Boss@Corp.Example>")
        photos.request(1, "new@corp.example")
        photos.request(1, "not an address")
        advanceUntilIdle()

        assertThat(asked).containsExactly(1L to listOf("boss@corp.example", "new@corp.example"))
        val file = requireNotNull(photos.photoOf(1, "boss@corp.example"))
        assertThat(file.readBytes()).isEqualTo(photo)
        assertThat(file.parentFile).isEqualTo(File(folder.root, "1"))
        // Адресов в именах файлов нет; «фото нет» — отметка без фото
        assertThat(File(folder.root, "1").list()!!.none { it.contains("corp") }).isTrue()
        assertThat(File(folder.root, "1").list()!!.count { it.endsWith(".none") }).isEqualTo(1)
        assertThat(photos.photoOf(1, "new@corp.example")).isNull()
    }

    @Test
    fun `checked addresses are not asked again until their time is up`() = runTest {
        val photos = photos()
        photos.request(1, "boss@corp.example")
        photos.request(1, "new@corp.example")
        advanceUntilIdle()
        asked.clear()

        photos.request(1, "boss@corp.example")
        photos.request(1, "new@corp.example")
        advanceUntilIdle()
        assertThat(asked).isEmpty()

        // «Фото нет» проверяется чаще, чем обновляется найденное фото
        now = start + ContactPhotos.NO_PHOTO_TTL_MS + 1
        photos.request(1, "boss@corp.example")
        photos.request(1, "new@corp.example")
        advanceUntilIdle()
        assertThat(asked).containsExactly(1L to listOf("new@corp.example"))

        asked.clear()
        now = start + ContactPhotos.PHOTO_TTL_MS + 1
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        assertThat(asked).containsExactly(1L to listOf("boss@corp.example"))
        // Старое фото видно до нового ответа и после него
        assertThat(photos.photoOf(1, "boss@corp.example")).isNotNull()
    }

    @Test
    fun `address the server did not answer is asked again`() = runTest {
        answer = { _, _ -> ContactPictures.Lookup.Found(emptyMap()) } // например, лимит MaxPictures
        val photos = photos()

        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()

        assertThat(asked).hasSize(2)
        assertThat(File(folder.root, "1").list().orEmpty()).isEmpty()
    }

    @Test
    fun `unsupported server is not asked again, other accounts are`() = runTest {
        answer = { accountId, addresses ->
            if (accountId == 1L) ContactPictures.Lookup.Unsupported
            else ContactPictures.Lookup.Found(addresses.associateWith { photo })
        }
        val photos = photos()

        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        now += ContactPhotos.PHOTO_TTL_MS * 2
        photos.request(1, "boss@corp.example")
        photos.request(1, "other@corp.example")
        photos.request(2, "boss@corp.example")
        advanceUntilIdle()

        assertThat(asked).containsExactly(1L to listOf("boss@corp.example"), 2L to listOf("boss@corp.example")).inOrder()
        assertThat(photos.photoOf(2, "boss@corp.example")).isNotNull()
    }

    @Test
    fun `after a failure the account waits before the next request`() = runTest {
        answer = { _, _ -> ContactPictures.Lookup.Failed("HTTP 503") }
        val photos = photos()

        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        assertThat(asked).hasSize(1)

        now += ContactPhotos.RETRY_AFTER_MS + 1
        answer = { _, addresses -> ContactPictures.Lookup.Found(addresses.associateWith { photo }) }
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        assertThat(asked).hasSize(2)
        assertThat(photos.photoOf(1, "boss@corp.example")).isNotNull()
    }

    @Test
    fun `turned off photos ask nothing and the cache is deleted`() = runTest {
        val photos = photos()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()
        assertThat(photos.photoOf(1, "boss@corp.example")).isNotNull()

        enabled.value = false
        advanceUntilIdle()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()

        assertThat(photos.enabled.value).isFalse()
        assertThat(photos.photos.value).isEmpty()
        assertThat(folder.root.exists()).isFalse()
        assertThat(asked).hasSize(1)
    }

    @Test
    fun `turned on again asks a server that was unsupported`() = runTest {
        answer = { _, _ -> ContactPictures.Lookup.Unsupported }
        val photos = photos()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()

        enabled.value = false
        advanceUntilIdle()
        enabled.value = true
        advanceUntilIdle()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()

        assertThat(asked).hasSize(2)
    }

    @Test
    fun `answer arriving after turning off is not written`() = runTest {
        val pending = CompletableDeferred<ContactPictures.Lookup>()
        answer = { _, _ -> pending.await() }
        val photos = photos()
        photos.request(1, "boss@corp.example")
        advanceUntilIdle()

        enabled.value = false
        advanceUntilIdle()
        pending.complete(ContactPictures.Lookup.Found(mapOf("boss@corp.example" to photo)))
        advanceUntilIdle()

        assertThat(photos.photos.value).isEmpty()
        assertThat(File(folder.root, "1").exists()).isFalse()
    }

    @Test
    fun `deleted account loses only its photos`() = runTest {
        answer = { _, addresses -> ContactPictures.Lookup.Found(addresses.associateWith { photo }) }
        val photos = photos()
        photos.request(1, "boss@corp.example")
        photos.request(2, "boss@corp.example")
        advanceUntilIdle()

        photos.forget(1)
        advanceUntilIdle()

        assertThat(photos.photoOf(1, "boss@corp.example")).isNull()
        assertThat(File(folder.root, "1").exists()).isFalse()
        assertThat(photos.photoOf(2, "boss@corp.example")).isNotNull()
    }

    @Test
    fun `cache on disk is used after restart, too old files are removed`() = runTest {
        photos().apply {
            request(1, "boss@corp.example")
            request(1, "old@corp.example")
            advanceUntilIdle()
        }
        val dir = File(folder.root, "1")
        val oldFile = dir.listFiles()!!.single { it.name.endsWith(".none") }
        oldFile.setLastModified(start - ContactPhotos.MAX_AGE_MS - 1)
        asked.clear()

        val restarted = photos()
        restarted.request(1, "boss@corp.example")
        advanceUntilIdle()

        assertThat(asked).isEmpty()
        assertThat(restarted.photoOf(1, "boss@corp.example")!!.readBytes()).isEqualTo(photo)
        assertThat(oldFile.exists()).isFalse()
    }

    @Test
    fun `photo key does not depend on display name or case`() {
        assertThat(ContactPhotos.key(1, "Босс <BOSS@corp.example>")).isEqualTo(ContactPhotos.key(1, "boss@corp.example"))
        assertThat(ContactPhotos.key(1, "boss@corp.example")).isNotEqualTo(ContactPhotos.key(2, "boss@corp.example"))
    }
}
