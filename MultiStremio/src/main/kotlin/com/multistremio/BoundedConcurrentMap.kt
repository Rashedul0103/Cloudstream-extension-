package com.multistremio

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Concurrent map with a shared request gate.
 *
 * Unlike chunked batches, a finished request releases its permit immediately, allowing the next
 * queued addon request to start without waiting for slower siblings in the same batch. amap keeps
 * the returned list in input order, which preserves addon priority and deterministic deduplication.
 */
internal suspend fun <T, R> List<T>.boundedConcurrentMap(
    semaphore: Semaphore,
    transform: suspend (T) -> R,
): List<R> = amap { item ->
    semaphore.withPermit { transform(item) }
}
