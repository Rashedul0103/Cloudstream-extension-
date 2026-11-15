package com.multistremio

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Short-lived cache for other installed extensions' homepage rows.
 *
 * Enumerating provider.mainPage can be expensive and some providers construct rows dynamically.
 * All enumeration and hashing happens on a worker thread. UI callers get a snapshot immediately
 * and can subscribe for the refreshed result without blocking the settings screen.
 */
object ExternalHomeRowsCache {
    private const val CACHE_TTL_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var cachedRows: List<InstalledExternalHomeRow> = emptyList()

    @Volatile
    private var loadedAtMs: Long = 0L

    private var loading = false
    private val callbacks = CopyOnWriteArrayList<(List<InstalledExternalHomeRow>) -> Unit>()

    fun snapshotIfFresh(): List<InstalledExternalHomeRow>? {
        val age = System.currentTimeMillis() - loadedAtMs
        return if (loadedAtMs > 0L && age in 0..CACHE_TTL_MS) cachedRows else null
    }

    /**
     * Returns the last cached snapshot immediately. A stale or empty cache triggers a refresh;
     * callers should use [loadAsync] when they need to wait for the first complete snapshot.
     */
    fun cachedOrRefresh(): List<InstalledExternalHomeRow> {
        val snapshot = cachedRows
        val age = System.currentTimeMillis() - loadedAtMs
        if (loadedAtMs == 0L || age > CACHE_TTL_MS) loadAsync()
        return snapshot
    }

    /**
     * Returns a fresh provider snapshot, suspending while a cold or stale cache is scanned.
     * Homepage requests use this instead of reading cachedRows immediately and accidentally
     * treating an unfinished first scan as an empty set of installed extensions.
     */
    suspend fun awaitRows(): List<InstalledExternalHomeRow> {
        snapshotIfFresh()?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            loadAsync { rows ->
                if (continuation.isActive) continuation.resume(rows)
            }
        }
    }

    fun loadAsync(callback: ((List<InstalledExternalHomeRow>) -> Unit)? = null) {
        val fresh = snapshotIfFresh()
        if (fresh != null) {
            if (callback != null) main.post { callback(fresh) }
            return
        }

        synchronized(lock) {
            if (callback != null) callbacks.add(callback)
            if (loading) return
            loading = true
        }

        thread(start = true, isDaemon = true, name = "multistremio-home-rows") {
            val result = runCatching { ExternalHomeRows.installedRows() }
                .getOrElse { emptyList() }

            val pending = synchronized(lock) {
                cachedRows = result
                loadedAtMs = System.currentTimeMillis()
                loading = false
                callbacks.toList().also { callbacks.clear() }
            }

            main.post {
                pending.forEach { listener ->
                    runCatching { listener(result) }
                }
            }
        }
    }

}
