package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.OutlookCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Гибрид цветов категорий на настоящей Room-БД: подменена только сетевая граница (ответ MCL).
 * Приоритет: ручной > сервер > (нет в списке сервера → без цвета) > стандартное имя > автоподбор.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryColorRepositoryTest {

    private lateinit var db: MailDatabase
    private var accountId = 0L
    private var serverReply: EasResult<List<OutlookCategory>?> = EasResult.Success(null)
    private var fetches = 0
    private var now = 1_000_000L
    private lateinit var repo: CategoryColorRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        accountId = runBlocking {
            db.accountDao().insert(AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me"))
        }
        repo = CategoryColorRepository(db, fetchFromServer = { fetches++; serverReply }, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private fun colors() = runBlocking { repo.observe(accountId).first() }

    @Test
    fun `server colors are applied and win over presets`() = runBlocking<Unit> {
        serverReply = EasResult.Success(listOf(OutlookCategory("Red Category", 7), OutlookCategory("Проект", 19)))

        assertThat(repo.refresh(accountId)).isEqualTo(CategoryRefreshResult.UPDATED)

        val colors = colors()
        // Пользователь перекрасил стандартную категорию в Outlook — показываем реальный цвет, не «красный».
        assertThat(colors.resolve("red category")).isEqualTo(ResolvedCategoryColor(7, CategoryColorSource.SERVER))
        assertThat(colors.resolve("Проект")).isEqualTo(ResolvedCategoryColor(19, CategoryColorSource.SERVER))
        // Список есть, а категории в нём нет — как в Outlook, без цвета.
        assertThat(colors.resolve("Чужая")).isEqualTo(ResolvedCategoryColor(-1, CategoryColorSource.NOT_IN_SERVER_LIST))
    }

    @Test
    fun `without a server list default names and auto colors are used`() = runBlocking<Unit> {
        serverReply = EasResult.Success(null) // Exchange 2007

        assertThat(repo.refresh(accountId)).isEqualTo(CategoryRefreshResult.NOT_SUPPORTED)

        val colors = colors()
        assertThat(colors.resolve("Синяя категория")).isEqualTo(ResolvedCategoryColor(7, CategoryColorSource.PRESET))
        val auto = colors.resolve("Проект")
        assertThat(auto.source).isEqualTo(CategoryColorSource.AUTO)
        assertThat(auto.color).isAtLeast(0)
    }

    @Test
    fun `user color overrides server and survives server removal, reset restores server`() = runBlocking<Unit> {
        serverReply = EasResult.Success(listOf(OutlookCategory("Проект", 19)))
        repo.refresh(accountId)
        repo.setUserColor(accountId, "  ПРОЕКТ ", 3)
        assertThat(colors().resolve("Проект")).isEqualTo(ResolvedCategoryColor(3, CategoryColorSource.USER))

        repo.clearUserColor(accountId, "проект")
        assertThat(colors().resolve("Проект")).isEqualTo(ResolvedCategoryColor(19, CategoryColorSource.SERVER))

        repo.setUserColor(accountId, "Проект", 3)
        serverReply = EasResult.Success(emptyList()) // категорию удалили в Outlook
        repo.refresh(accountId)
        assertThat(colors().resolve("Проект")).isEqualTo(ResolvedCategoryColor(3, CategoryColorSource.USER))
        assertThat(db.categoryColorDao().getAll(accountId).single().serverColor).isNull()
    }

    @Test
    fun `categories dropped from the server list lose their server color`() = runBlocking<Unit> {
        serverReply = EasResult.Success(listOf(OutlookCategory("A", 1), OutlookCategory("B", 2)))
        repo.refresh(accountId)
        serverReply = EasResult.Success(listOf(OutlookCategory("A", 5)))
        repo.refresh(accountId)

        val rows = db.categoryColorDao().getAll(accountId)
        assertThat(rows.map { it.name to it.serverColor }).containsExactly("A" to 5)
    }

    @Test
    fun `failed refresh keeps stored colors`() = runBlocking<Unit> {
        serverReply = EasResult.Success(listOf(OutlookCategory("A", 1)))
        repo.refresh(accountId)
        serverReply = EasResult.Error("timeout")

        assertThat(repo.refresh(accountId)).isEqualTo(CategoryRefreshResult.FAILED)
        assertThat(colors().resolve("A")).isEqualTo(ResolvedCategoryColor(1, CategoryColorSource.SERVER))
    }

    @Test
    fun `automatic refresh is throttled, manual refresh is not`() = runBlocking<Unit> {
        repo.refreshIfStale(accountId)
        repo.refreshIfStale(accountId)
        assertThat(fetches).isEqualTo(1)

        repo.refresh(accountId)
        assertThat(fetches).isEqualTo(2)

        now += CategoryColorRepository.AUTO_REFRESH_INTERVAL_MS
        repo.refreshIfStale(accountId)
        assertThat(fetches).isEqualTo(3)
    }

    @Test
    fun `deleting the account deletes its colors`() = runBlocking<Unit> {
        repo.setUserColor(accountId, "A", 1)
        db.accountDao().delete(accountId)
        assertThat(db.categoryColorDao().getAll(accountId)).isEmpty()
    }

    @Test
    fun `known categories merge stored and used names without case duplicates`() = runBlocking<Unit> {
        serverReply = EasResult.Success(listOf(OutlookCategory("Работа", 1)))
        repo.refresh(accountId)

        assertThat(colors().knownCategories(listOf("работа", "Личное", " ")))
            .containsExactly("Личное", "Работа").inOrder()
    }
}
