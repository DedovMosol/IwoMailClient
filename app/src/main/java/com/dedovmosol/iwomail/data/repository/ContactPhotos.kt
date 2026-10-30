package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.eas.ContactPictures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Фото отправителей из глобальной адресной книги — кэш на диске и очередь запросов.
 *
 * - Включается в настройках; выключение удаляет кэш.
 * - Запрашиваются адреса видимых строк: накопленные за [batchDelayMs] — одним запросом на аккаунт (до
 *   [ContactPictures.BATCH_SIZE] адресов), по одному запросу за раз.
 * - Ответ хранится и для «фото нет»: фото обновляется раз в [PHOTO_TTL_MS], отсутствие проверяется раз в
 *   [NO_PHOTO_TTL_MS]; старое фото показывается, пока не придёт новое.
 * - Аккаунт без поддержки (Exchange 2007, 2010 без SP1) больше не спрашивается до перезапуска; после сбоя связи —
 *   пауза [RETRY_AFTER_MS].
 *
 * Файл фото — `<каталог>/<аккаунт>/<sha-256 адреса>.jpg`, отметка «фото нет» — `.none`; время записи файла — время
 * проверки. Адреса в именах файлов не хранятся.
 */
class ContactPhotos internal constructor(
    private val root: File,
    enabledFlow: Flow<Boolean>,
    private val lookup: suspend (accountId: Long, addresses: List<String>) -> ContactPictures.Lookup,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchDelayMs: Long = BATCH_DELAY_MS,
    private val io: CoroutineContext = Dispatchers.IO
) {

    private val _enabled = MutableStateFlow(false)

    /** Включены ли фото (настройка). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _photos = MutableStateFlow<Map<String, File>>(emptyMap())

    /** Известные фото по [key]. */
    val photos: StateFlow<Map<String, File>> = _photos.asStateFlow()

    /** Когда адрес проверяли (и с фото, и без) — по [key]. */
    private val checkedAt = ConcurrentHashMap<String, Long>()
    private val loadedAccounts = ConcurrentHashMap.newKeySet<Long>()
    private val unsupportedAccounts = ConcurrentHashMap.newKeySet<Long>()
    private val retryAfter = ConcurrentHashMap<Long, Long>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val requests = Channel<Pair<Long, String>>(Channel.UNLIMITED)

    /** Запись, удаление и чтение каталога — по очереди: выключение не столкнётся с записью ответа. */
    private val diskLock = Mutex()

    init {
        // Кэш стирается по значению из хранилища (а не по начальному false у [enabled]): иначе каждый запуск стирал бы
        // кэш. [enabled] сбрасывается до удаления: запись ответа под [diskLock] после него уже ничего не запишет
        scope.launch {
            enabledFlow.collect { on ->
                _enabled.value = on
                if (!on) clear()
            }
        }
        scope.launch {
            for (first in requests) {
                delay(batchDelayMs)
                val batch = mutableListOf(first)
                while (true) batch += requests.tryReceive().getOrNull() ?: break
                process(batch)
            }
        }
    }

    /**
     * Нужно фото [address] аккаунта [accountId] (строка видна на экране). Повторные вызовы для того же адреса,
     * проверенного недавно, ничего не делают.
     */
    fun request(accountId: Long, address: String) {
        if (!enabled.value || accountId <= 0 || accountId in unsupportedAccounts) return
        val normalized = normalize(address) ?: return
        val key = key(accountId, normalized)
        val checked = checkedAt[key]
        if (checked != null && !isStale(key, checked)) return
        if (queued.add(key)) requests.trySend(accountId to normalized)
    }

    /** Аккаунт удалён — его фото тоже. */
    suspend fun forget(accountId: Long) = diskLock.withLock {
        withContext(io) { File(root, accountId.toString()).deleteRecursively() }
        val prefix = "$accountId/"
        checkedAt.keys.removeAll { it.startsWith(prefix) }
        _photos.update { map -> map.filterKeys { !it.startsWith(prefix) } }
        loadedAccounts.remove(accountId)
        unsupportedAccounts.remove(accountId)
        retryAfter.remove(accountId)
    }

    private suspend fun process(batch: List<Pair<Long, String>>) {
        for ((accountId, addresses) in batch.groupBy({ it.first }, { it.second })) {
            try {
                processAccount(accountId, addresses.distinct())
            } finally {
                addresses.forEach { queued.remove(key(accountId, it)) }
            }
        }
    }

    private suspend fun processAccount(accountId: Long, addresses: List<String>) {
        if (!enabled.value || accountId in unsupportedAccounts) return
        if ((retryAfter[accountId] ?: 0L) > clock()) return
        loadIndex(accountId)
        val due = addresses.filter { address ->
            val key = key(accountId, address)
            checkedAt[key]?.let { isStale(key, it) } ?: true
        }
        for (chunk in due.chunked(ContactPictures.BATCH_SIZE)) {
            when (val result = lookup(accountId, chunk)) {
                ContactPictures.Lookup.Unsupported -> {
                    unsupportedAccounts += accountId
                    return
                }
                is ContactPictures.Lookup.Failed -> {
                    android.util.Log.w(TAG, "Contact photos unavailable: ${result.reason}")
                    retryAfter[accountId] = clock() + RETRY_AFTER_MS
                    return
                }
                is ContactPictures.Lookup.Found -> {
                    // Выключили, пока шёл запрос, — не записывать то, что уже удалено
                    if (!enabled.value) return
                    store(accountId, chunk, result.photos)
                }
            }
        }
    }

    private suspend fun store(accountId: Long, asked: List<String>, photos: Map<String, ByteArray?>) = diskLock.withLock {
        withContext(io) { write(accountId, asked, photos) }
    }

    private fun write(accountId: Long, asked: List<String>, photos: Map<String, ByteArray?>) {
        if (!enabled.value) return
        val dir = File(root, accountId.toString()).apply { mkdirs() }
        val now = clock()
        val added = HashMap<String, File>()
        val removed = HashSet<String>()
        for (address in asked) {
            if (address !in photos) continue // сервер не ответил об адресе — спросим позже
            val key = key(accountId, address)
            val name = hash(address)
            val photoFile = File(dir, "$name$PHOTO")
            val noneFile = File(dir, "$name$NONE")
            val bytes = photos[address]
            if (bytes != null) {
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(photoFile)) {
                    photoFile.delete()
                    if (!tmp.renameTo(photoFile)) {
                        tmp.delete()
                        continue
                    }
                }
                photoFile.setLastModified(now)
                noneFile.delete()
                added[key] = photoFile
            } else {
                photoFile.delete()
                noneFile.writeBytes(ByteArray(0))
                noneFile.setLastModified(now)
                removed += key
            }
            checkedAt[key] = now
        }
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            _photos.update { map -> (map - removed) + added }
        }
    }

    /** Что уже лежит на диске для аккаунта — один раз; старше [MAX_AGE_MS] — удаляется. */
    private suspend fun loadIndex(accountId: Long) = diskLock.withLock {
        if (accountId in loadedAccounts) return@withLock
        withContext(io) {
            val now = clock()
            val found = HashMap<String, File>()
            File(root, accountId.toString()).listFiles()?.forEach { file ->
                val age = now - file.lastModified()
                if (age > MAX_AGE_MS || file.name.endsWith(".tmp")) {
                    file.delete()
                    return@forEach
                }
                val key = "$accountId/${file.name.substringBeforeLast('.')}"
                when {
                    file.name.endsWith(PHOTO) -> {
                        found[key] = file
                        checkedAt[key] = file.lastModified()
                    }
                    file.name.endsWith(NONE) -> checkedAt.putIfAbsent(key, file.lastModified())
                }
            }
            if (found.isNotEmpty()) _photos.update { it + found }
            loadedAccounts += accountId
        }
    }

    private fun isStale(key: String, checked: Long): Boolean =
        clock() - checked > if (key in _photos.value) PHOTO_TTL_MS else NO_PHOTO_TTL_MS

    private suspend fun clear() = diskLock.withLock {
        withContext(io) { root.deleteRecursively() }
        checkedAt.clear()
        loadedAccounts.clear()
        // После повторного включения — спросить снова (сервер могли обновить до 2010 SP1)
        unsupportedAccounts.clear()
        retryAfter.clear()
        _photos.value = emptyMap()
    }

    companion object {
        private const val TAG = "ContactPhotos"
        private const val PHOTO = ".jpg"
        private const val NONE = ".none"
        private const val BATCH_DELAY_MS = 300L
        internal const val PHOTO_TTL_MS = 7L * 24 * 3_600_000
        internal const val NO_PHOTO_TTL_MS = 3L * 24 * 3_600_000
        internal const val MAX_AGE_MS = 30L * 24 * 3_600_000
        internal const val RETRY_AFTER_MS = 5L * 60_000

        /** Ключ фото: аккаунт и хэш адреса (адрес в нижнем регистре). */
        fun key(accountId: Long, address: String): String = "$accountId/${hash(normalize(address) ?: address)}"

        /** Адрес для запроса: без имени и пробелов, в нижнем регистре; не адрес — null. */
        internal fun normalize(address: String): String? =
            com.dedovmosol.iwomail.util.extractEmailAddress(address).trim().lowercase().takeIf { it.contains('@') }

        private fun hash(address: String): String =
            MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        @Volatile
        private var instance: ContactPhotos? = null

        fun get(context: Context): ContactPhotos = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): ContactPhotos {
            val accounts = RepositoryProvider.getAccountRepository(app)
            return ContactPhotos(
                root = File(app.filesDir, "contact_photos"),
                enabledFlow = SettingsRepository.getInstance(app).galPhotosEnabled,
                lookup = { accountId, addresses ->
                    val account = accounts.getAccount(accountId)
                    if (account == null || account.accountType != com.dedovmosol.iwomail.data.database.AccountType.EXCHANGE.name) {
                        ContactPictures.Lookup.Unsupported
                    } else {
                        accounts.createEasClient(accountId)?.lookupContactPictures(addresses)
                            ?: ContactPictures.Lookup.Failed(RepositoryErrors.CLIENT_CREATE_FAILED)
                    }
                },
                scope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO, TAG)
            )
        }
    }
}
