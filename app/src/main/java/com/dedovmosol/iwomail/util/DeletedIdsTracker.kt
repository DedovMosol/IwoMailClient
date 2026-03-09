package com.dedovmosol.iwomail.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Anti-resurrection tracker: prevents deleted items from reappearing
 * when a background sync runs before the server confirms the deletion.
 *
 * Thread-safe (ConcurrentHashMap). Persists across restarts via SharedPreferences.
 *
 * @param prefsName SharedPreferences file name
 * @param prefsKey  key for the StringSet inside that file
 * @param maxSize   eviction threshold; oldest 25% removed when reached
 * @param ttlMs     per-entry time-to-live; 0 = entries never expire on their own
 */
class DeletedIdsTracker(
    private val prefsName: String,
    private val prefsKey: String,
    private val maxSize: Int = 500,
    private val ttlMs: Long = 0L
) {
    private val ids = ConcurrentHashMap<String, Long>()
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val saved = prefs.getStringSet(prefsKey, emptySet()) ?: emptySet()
                val now = System.currentTimeMillis()
                for (id in saved) {
                    ids[id] = now
                }
            } catch (_: Exception) { }
            initialized = true
        }
    }

    private fun save(context: Context) {
        try {
            val snapshot = if (ttlMs > 0) {
                val now = System.currentTimeMillis()
                ids.entries.filter { now - it.value < ttlMs }.map { it.key }.toSet()
            } else {
                ids.keys.toSet()
            }
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putStringSet(prefsKey, snapshot).apply()
        } catch (_: Exception) { }
    }

    private fun evictExpired() {
        if (ttlMs <= 0) return
        val now = System.currentTimeMillis()
        ids.entries.removeAll { now - it.value > ttlMs }
    }

    fun register(id: String, context: Context? = null) {
        if (id.isBlank()) return
        evictExpired()
        if (ids.size >= maxSize) {
            val evictCount = maxSize / 4
            ids.entries.sortedBy { it.value }.take(evictCount).forEach { ids.remove(it.key) }
        }
        ids[id] = System.currentTimeMillis()
        context?.let { save(it) }
    }

    fun isTracked(id: String): Boolean {
        val ts = ids[id] ?: return false
        if (ttlMs > 0 && System.currentTimeMillis() - ts > ttlMs) {
            ids.remove(id, ts)
            return false
        }
        return true
    }

    /**
     * Remove specific IDs that the server confirmed as deleted.
     * Email pattern: server no longer mentions these IDs in Sync responses.
     */
    fun confirmDeleted(confirmedIds: Set<String>, context: Context? = null) {
        var changed = false
        for (id in confirmedIds) {
            if (ids.remove(id) != null) changed = true
        }
        if (changed) context?.let { save(it) }
    }

    /**
     * Remove tracked IDs absent from the server's full list.
     * Calendar pattern: anything we track that the server still returns
     * keeps its protection; everything else is forgotten.
     */
    fun confirmByServerState(serverReturnedIds: Set<String>, context: Context? = null) {
        val removed = ids.keys.removeAll { it !in serverReturnedIds }
        if (removed) context?.let { save(it) }
    }

    val size: Int get() = ids.size

    fun remove(id: String, context: Context? = null) {
        if (ids.remove(id) != null) {
            context?.let { save(it) }
        }
    }
}
