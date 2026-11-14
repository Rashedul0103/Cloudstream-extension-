package com.multistremio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Safe, in-memory diagnostics for the most recent stream lookup. URLs and addon credentials are never stored. */
data class StreamRequestDiagnostic(
    val addonName: String,
    val resource: String,
    val elapsedMs: Long,
    val responseReceived: Boolean,
    val resultCount: Int,
)

data class StreamDiagnosticSnapshot(
    val startedAt: Long,
    val durationMs: Long,
    val rawStreams: Int,
    val addonSubtitles: Int,
    val playableCandidates: Int,
    val keptAfterFilters: Int,
    val emittedLinks: Int,
    val requests: List<StreamRequestDiagnostic>,
)

object StreamDiagnostics {
    private data class ActiveRun(
        val startedAt: Long = System.currentTimeMillis(),
        val startedNanos: Long = System.nanoTime(),
        val requests: MutableList<StreamRequestDiagnostic> = mutableListOf(),
    )

    private val ids = AtomicLong()
    private val active = ConcurrentHashMap<Long, ActiveRun>()

    @Volatile
    private var latestSnapshot: StreamDiagnosticSnapshot? = null

    fun begin(): Long {
        val id = ids.incrementAndGet()
        active[id] = ActiveRun()
        // A cancelled player lookup must not leave unbounded diagnostic state behind.
        if (active.size > 8) {
            active.entries
                .sortedBy { it.value.startedAt }
                .take(active.size - 8)
                .forEach { active.remove(it.key) }
        }
        return id
    }

    fun record(
        runId: Long?,
        addonName: String,
        resource: String,
        elapsedMs: Long,
        responseReceived: Boolean,
        resultCount: Int,
    ) {
        if (runId == null) return
        val run = active[runId] ?: return
        synchronized(run.requests) {
            run.requests += StreamRequestDiagnostic(
                addonName = addonName,
                resource = resource,
                elapsedMs = elapsedMs.coerceAtLeast(0L),
                responseReceived = responseReceived,
                resultCount = resultCount.coerceAtLeast(0),
            )
        }
    }

    fun finish(
        runId: Long,
        rawStreams: Int,
        addonSubtitles: Int,
        playableCandidates: Int,
        keptAfterFilters: Int,
        emittedLinks: Int,
    ) {
        val run = active.remove(runId) ?: return
        val requests = synchronized(run.requests) { run.requests.toList() }
        latestSnapshot = StreamDiagnosticSnapshot(
            startedAt = run.startedAt,
            durationMs = ((System.nanoTime() - run.startedNanos) / 1_000_000L).coerceAtLeast(0L),
            rawStreams = rawStreams.coerceAtLeast(0),
            addonSubtitles = addonSubtitles.coerceAtLeast(0),
            playableCandidates = playableCandidates.coerceAtLeast(0),
            keptAfterFilters = keptAfterFilters.coerceAtLeast(0),
            emittedLinks = emittedLinks.coerceAtLeast(0),
            requests = requests,
        )
    }

    fun latest(): StreamDiagnosticSnapshot? = latestSnapshot
}
