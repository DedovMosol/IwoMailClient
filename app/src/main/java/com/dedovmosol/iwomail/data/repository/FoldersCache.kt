package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.FolderEntity

/**
 * Thread-safe LRU cache of folder lists, keyed by accountId.
 * Shared across UI and background workers so every component
 * sees the same folder snapshot without hitting the database.
 */
object FoldersCache {
    private const val MAX_ACCOUNTS = 10
    private val cache = object : LinkedHashMap<Long, List<FolderEntity>>(MAX_ACCOUNTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<FolderEntity>>?) = size > MAX_ACCOUNTS
    }

    fun get(accountId: Long): List<FolderEntity> = synchronized(cache) { cache[accountId] } ?: emptyList()

    fun set(accountId: Long, folders: List<FolderEntity>) {
        synchronized(cache) { cache[accountId] = folders }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    fun clearAccount(accountId: Long) {
        synchronized(cache) { cache.remove(accountId) }
    }
}
