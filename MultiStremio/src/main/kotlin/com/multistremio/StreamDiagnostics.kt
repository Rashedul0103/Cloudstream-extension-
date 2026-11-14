package com.multistremio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Safe, in-memory diagnostics. URLs, request headers and addon credentials are never stored. */
data class StreamRequestDiagnostic(
    val addonName: String,
    val resource: String,
    val elapsedMs: Long,
    val status: String,
    val resultCount: Int,
)

data class StreamDiagnosticSnapshot(
    val startedAt: Long,
    val durationMs: Long,
    val parseWorkMs: Long,
    val pipelineMs: Long,
    val emissionMs: Long,
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
        var parseWorkMs: Long = 0,
        var pipelineMs: Long = 0,
        var emissionMs: Long = 0,
    )

    private val ids = AtomicLong()
    private val active = ConcurrentHashMap<Long, ActiveRun>()

    @Volatile
    private var latestSnapshot: StreamDiagnosticSnapshot? = null

    fun begin(): Long {
        val id = ids.incrementAndGet()
        active[id] = ActiveRun()
        if (active.size > 8) {
            active.entries.sortedBy { it.value.startedAt }
                .take(active.size - 8).forEach { active.remove(it.key) }
        }
        return id
    }

    fun record(
        runId: Long?,
        addonName: String,
        resource: String,
        elapsedMs: Long,
        status: String,
        resultCount: Int,
    ) {
        if (runId == null) return
        val run = active[runId] ?: return
        synchronized(run.requests) {
            run.requests += StreamRequestDiagnostic(
                addonName, resource, elapsedMs.coerceAtLeast(0L),
                status, resultCount.coerceAtLeast(0),
            )
        }
    }

    /** Records aggregate CPU/work time for a stage; parallel addon parsing times are summed. */
    fun recordStage(runId: Long, stage: String, elapsedMs: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            when (stage) {
                "parse" -> run.parseWorkMs += elapsedMs.coerceAtLeast(0L)
                "pipeline" -> run.pipelineMs += elapsedMs.coerceAtLeast(0L)
                "emission" -> run.emissionMs += elapsedMs.coerceAtLeast(0L)
            }
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
        val stageTimes = synchronized(run) { Triple(run.parseWorkMs, run.pipelineMs, run.emissionMs) }
        latestSnapshot = StreamDiagnosticSnapshot(
            startedAt = run.startedAt,
            durationMs = ((System.nanoTime() - run.startedNanos) / 1_000_000L).coerceAtLeast(0L),
            parseWorkMs = stageTimes.first,
            pipelineMs = stageTimes.second,
            emissionMs = stageTimes.third,
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
