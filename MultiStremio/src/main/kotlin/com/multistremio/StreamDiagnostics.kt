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

    @Volatile
    private var latestCompletedRunId: Long = 0L

    /** Addon names come from remote manifests or user input, so redact URL and query credentials. */
    fun safeAddonName(value: String): String = value
        .replace(Regex("""(?i)https?://[^\s]+"""), "[URL redacted]")
        .replace(
            Regex("""(?i)([?&](?:token|key|api[_-]?key|auth|password|secret)=)[^&\s]+"""),
        ) { match -> match.groupValues[1] + "[redacted]" },
        )
        .replace(Regex("""[\r\n]+"""), " ")
        .trim()
        .take(80)
        .ifBlank { "Unnamed addon" }

    fun begin(): Long {
        val id = ids.incrementAndGet()
        active[id] = ActiveRun()
        if (active.size > 8) {
            active.entries.sortedBy { it.key }
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
                safeAddonName(addonName), resource, elapsedMs.coerceAtLeast(0L),
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
        val snapshot = StreamDiagnosticSnapshot(
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
        // A slow older lookup must not overwrite a newer lookup that completed first.
        synchronized(this) {
            if (runId > latestCompletedRunId) {
                latestSnapshot = snapshot
                latestCompletedRunId = runId
            }
        }
    }

    fun latest(): StreamDiagnosticSnapshot? = latestSnapshot
}
