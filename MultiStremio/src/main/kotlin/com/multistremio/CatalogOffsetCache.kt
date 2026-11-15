package com.multistremio

/**
 * Bounded, expiring offsets for Stremio catalog pagination.
 *
 * Entries are keyed by the homepage row request data. An LRU cap prevents old rows from
 * accumulating for the lifetime of the provider, while expiry makes stale page state harmless.
 */
internal class CatalogOffsetCache(
    capacity: Int = 256,
    ttlNanos: Long = 30L * 60L * 1_000_000_000L,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class Entry(val offset: Int, val writtenAtNanos: Long)

    private val maxEntries = capacity.coerceAtLeast(1)
    private val maxAgeNanos = ttlNanos.coerceAtLeast(1L)
    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(key: String): Int? {
        val entry = entries[key] ?: return null
        val now = nowNanos()
        if (isExpired(entry, now)) {
            entries.remove(key)
            return null
        }
        return entry.offset
    }

    @Synchronized
    fun put(key: String, offset: Int) {
        val now = nowNanos()
        entries.entries.removeAll { isExpired(it.value, now) }
        entries[key] = Entry(offset, now)
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    private fun isExpired(entry: Entry, now: Long): Boolean {
        val age = now - entry.writtenAtNanos
        return age < 0L || age > maxAgeNanos
    }

    @Synchronized
    internal fun sizeForTests(): Int = entries.size
}
