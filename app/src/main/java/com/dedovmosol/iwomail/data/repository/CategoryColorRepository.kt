package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.CategoryColorEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.OutlookCategory
import com.dedovmosol.iwomail.util.CalendarCategories
import com.dedovmosol.iwomail.util.OutlookCategoryPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Итог обновления цветов с сервера. */
enum class CategoryRefreshResult { UPDATED, NOT_SUPPORTED, FAILED }

/**
 * Цвета категорий календаря (гибрид): реальные цвета из Master Category List на Exchange 2010+,
 * стандартные имена и ручные назначения — везде (единственный путь на Exchange 2007).
 *
 * Список категорий на сервере не меняется: запись MCL (UpdateUserConfiguration) рискует испортить
 * категории Outlook, поэтому ручные цвета — локальные.
 */
class CategoryColorRepository internal constructor(
    private val database: MailDatabase,
    private val fetchFromServer: suspend (accountId: Long) -> EasResult<List<OutlookCategory>?>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val dao = database.categoryColorDao()
    private val lastRefreshAt = ConcurrentHashMap<Long, Long>()
    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()

    fun observe(accountId: Long): Flow<CategoryColors> =
        dao.observe(accountId).distinctUntilChanged().map { CategoryColors(it) }

    /** Автообновление при открытии календаря: не чаще [AUTO_REFRESH_INTERVAL_MS] на аккаунт. */
    suspend fun refreshIfStale(accountId: Long) {
        val last = lastRefreshAt[accountId]
        if (last != null && clock() - last < AUTO_REFRESH_INTERVAL_MS) return
        refresh(accountId)
    }

    /**
     * Загружает MCL и сводит с локальными данными. Ручные назначения не трогаются; категории, которых
     * больше нет в списке сервера, теряют серверный цвет. При сбое данные остаются прежними.
     */
    suspend fun refresh(accountId: Long): CategoryRefreshResult =
        refreshLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            lastRefreshAt[accountId] = clock()
            val result = try {
                fetchFromServer(accountId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                EasResult.Error(e.message ?: "fetch failed")
            }
            when (result) {
                is EasResult.Error -> {
                    android.util.Log.w(TAG, "Category list refresh failed: ${result.message}")
                    CategoryRefreshResult.FAILED
                }
                is EasResult.Success -> {
                    val categories = result.data ?: return@withLock CategoryRefreshResult.NOT_SUPPORTED
                    try {
                        applyServerList(accountId, categories)
                        CategoryRefreshResult.UPDATED
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        android.util.Log.w(TAG, "Category list save failed: ${e.message}")
                        CategoryRefreshResult.FAILED
                    }
                }
            }
        }

    private suspend fun applyServerList(accountId: Long, categories: List<OutlookCategory>) {
        database.withTransaction {
            val existing = dao.getAll(accountId).associateBy { it.nameKey }
            val incoming = categories.associateBy { CalendarCategories.key(it.name) }
            for ((key, category) in incoming) {
                val color = OutlookCategoryPalette.normalize(category.color)
                val current = existing[key]
                if (current == null || current.serverColor != color || current.name != category.name) {
                    dao.upsert(
                        current?.copy(name = category.name, serverColor = color)
                            ?: CategoryColorEntity(accountId, key, category.name, serverColor = color)
                    )
                }
            }
            for ((key, entry) in existing) {
                if (key in incoming || entry.serverColor == null) continue
                if (entry.userColor == null) dao.delete(accountId, key) else dao.upsert(entry.copy(serverColor = null))
            }
        }
    }

    /**
     * Ручной цвет категории (перекрывает серверный). Запись не отменяется вместе с экраном
     * (NonCancellable): выбор, сделанный перед закрытием диалога, не теряется.
     */
    suspend fun setUserColor(accountId: Long, name: String, color: Int) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name is empty" }
        val key = CalendarCategories.key(trimmed)
        val normalized = OutlookCategoryPalette.normalize(color)
        withContext(NonCancellable) {
            database.withTransaction {
                val current = dao.get(accountId, key)
                dao.upsert(current?.copy(userColor = normalized) ?: CategoryColorEntity(accountId, key, trimmed, userColor = normalized))
            }
        }
    }

    /** Сброс ручного цвета: снова действует серверный / стандартный / автоподбор. */
    suspend fun clearUserColor(accountId: Long, name: String) {
        val key = CalendarCategories.key(name)
        withContext(NonCancellable) {
            database.withTransaction {
                val current = dao.get(accountId, key) ?: return@withTransaction
                if (current.serverColor == null) dao.delete(accountId, key) else dao.upsert(current.copy(userColor = null))
            }
        }
    }

    companion object {
        private const val TAG = "CategoryColorRepository"
        /** Категории меняют редко; ручное «Обновить» на экране цветов идёт без ограничения. */
        const val AUTO_REFRESH_INTERVAL_MS = 60L * 60 * 1000

        /** Прод-сборка: MCL запрашивается EAS-клиентом аккаунта (только Exchange-аккаунты). */
        fun create(context: Context): CategoryColorRepository {
            val app = context.applicationContext
            val accountRepo = RepositoryProvider.getAccountRepository(app)
            return CategoryColorRepository(MailDatabase.getInstance(app), fetchFromServer = { accountId ->
                val account = accountRepo.getAccount(accountId)
                when {
                    account == null -> EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                    account.accountType != AccountType.EXCHANGE.name -> EasResult.Success(null)
                    else -> accountRepo.createEasClient(accountId)?.fetchCategoryList()
                        ?: EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                }
            })
        }
    }
}
