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
    /** Time spent waiting for a shared request permit before the HTTP call started. */
    val queueWaitMs: Long = 0L,
    /** HTTP response code for rejected responses; no response body or URL is retained. */
    val httpCode: Int? = null,
    /** Exception class only; exception messages can contain private URLs or credentials. */
    val errorType: String? = null,
) {
    fun displayStatus(): String = buildString {
        append(status)
        httpCode?.let { append(" (HTTP ").append(it).append(')') }
        errorType?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (resultCount > 0) append(" · ").append(resultCount).append(" items")
    }
}

data class StreamDiagnosticSnapshot(
    val startedAt: Long,
    /** Complete lookup duration, including the final subtitle wait. Not video startup time. */
    val durationMs: Long,
    val timeToFirstStreamRequestCompletedMs: Long?,
    val timeToFirstPlayableResultMs: Long?,
    /** Time until the first playable callback was handed to CloudStream. Not first video frame. */
    val timeToFirstPlayableLinkMs: Long?,
    val timeToAllStreamAddonsCompletedMs: Long?,
    val timeToAllPlayableLinksEmittedMs: Long?,
    val expectedStreamAddons: Int,
    val completedStreamAddons: Int,
    val firstBatchLinks: Int,
    val parseWorkMs: Long,
    val pipelineMs: Long,
    val emissionMs: Long,
    val extractorResolutionMs: Long,
    val subtitleWaitMs: Long,
    val timeToSubtitleRequestsStartedMs: Long?,
    val timeToSubtitleCompletionMs: Long?,
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
        var expectedStreamAddons: Int = 0,
        var completedStreamAddons: Int = 0,
        var firstStreamRequestCompletedMs: Long? = null,
        var firstPlayableResultMs: Long? = null,
        var firstPlayableLinkMs: Long? = null,
        var allStreamAddonsCompletedMs: Long? = null,
        var allPlayableLinksEmittedMs: Long? = null,
        var firstBatchLinks: Int? = null,
        var timeToSubtitleRequestsStartedMs: Long? = null,
        var timeToSubtitleCompletionMs: Long? = null,
        var parseWorkMs: Long = 0,
        var pipelineMs: Long = 0,
        var emissionMs: Long = 0,
        var extractorResolutionMs: Long = 0,
        var subtitleWaitMs: Long = 0,
    )

    private val ids = AtomicLong()
    private val active = ConcurrentHashMap<Long, ActiveRun>()

    @Volatile
    private var latestSnapshot: StreamDiagnosticSnapshot? = null

    @Volatile
    private var latestCompletedRunId: Long = 0L

    private fun elapsedMs(run: ActiveRun): Long =
        ((System.nanoTime() - run.startedNanos) / 1_000_000L).coerceAtLeast(0L)

    /** Addon names come from remote manifests or user input, so redact URL and query credentials. */
    fun safeAddonName(value: String): String = value
        .replace(Regex("""(?i)(?:https?|stremio)://[^\s]+"""), "[URL redacted]")
        .replace(
            Regex("""(?i)((?:^|[?&])(?:access[_-]?token|refresh[_-]?token|token|key|api[_-]?key|auth(?:orization)?|password|passwd|pass|secret|client[_-]?secret|credential|cookie)=)[^&\s]+"""),
        ) { match -> match.groupValues[1] + "[redacted]" }
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

    fun setExpectedStreamAddons(runId: Long, count: Int) {
        val run = active[runId] ?: return
        synchronized(run) {
            run.expectedStreamAddons = count.coerceAtLeast(0)
            if (run.expectedStreamAddons == 0 && run.allStreamAddonsCompletedMs == null) {
                run.allStreamAddonsCompletedMs = elapsedMs(run)
            }
        }
    }

    fun markFirstStreamRequestCompleted(runId: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.firstStreamRequestCompletedMs == null) {
                run.firstStreamRequestCompletedMs = elapsedMs(run)
            }
        }
    }

    fun markFirstPlayableResult(runId: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.firstPlayableResultMs == null) {
                run.firstPlayableResultMs = elapsedMs(run)
            }
        }
    }

    fun markFirstPlayableLink(runId: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.firstPlayableLinkMs == null) {
                run.firstPlayableLinkMs = elapsedMs(run)
            }
        }
    }

    fun recordStreamAddonCompleted(runId: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            run.completedStreamAddons++
            if (
                run.completedStreamAddons >= run.expectedStreamAddons &&
                run.allStreamAddonsCompletedMs == null
            ) {
                run.allStreamAddonsCompletedMs = elapsedMs(run)
            }
        }
    }

    fun markFirstBatchLinks(runId: Long, count: Int) {
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.firstBatchLinks == null) run.firstBatchLinks = count.coerceAtLeast(0)
        }
    }

    fun markAllPlayableLinksEmitted(runId: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.allPlayableLinksEmittedMs == null) {
                run.allPlayableLinksEmittedMs = elapsedMs(run)
            }
        }
    }

    fun markSubtitleRequestsStarted(runId: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.timeToSubtitleRequestsStartedMs == null) {
                run.timeToSubtitleRequestsStartedMs = elapsedMs(run)
            }
        }
    }

    fun markSubtitleCompletion(runId: Long, addonCount: Int) {
        if (addonCount <= 0) return
        val run = active[runId] ?: return
        synchronized(run) {
            if (run.timeToSubtitleCompletionMs == null) {
                run.timeToSubtitleCompletionMs = elapsedMs(run)
            }
        }
    }

    fun record(
        runId: Long?,
        addonName: String,
        resource: String,
        elapsedMs: Long,
        status: String,
        resultCount: Int,
        queueWaitMs: Long = 0L,
        httpCode: Int? = null,
        errorType: String? = null,
    ) {
        if (runId == null) return
        val run = active[runId] ?: return
        synchronized(run.requests) {
            run.requests += StreamRequestDiagnostic(
                safeAddonName(addonName), resource, elapsedMs.coerceAtLeast(0L),
                status, resultCount.coerceAtLeast(0), queueWaitMs.coerceAtLeast(0L),
                httpCode, errorType?.take(80),
            )
        }
    }

    /** Records CPU/work time. Parallel parse work and repeated incremental pipeline runs are summed. */
    fun recordStage(runId: Long, stage: String, elapsedMs: Long) {
        val run = active[runId] ?: return
        synchronized(run) {
            val duration = elapsedMs.coerceAtLeast(0L)
            when (stage) {
                "parse" -> run.parseWorkMs += duration
                "pipeline" -> run.pipelineMs += duration
                "emission" -> run.emissionMs += duration
                "extractors" -> run.extractorResolutionMs += duration
                "subtitle_wait" -> run.subtitleWaitMs += duration
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
        val snapshot = synchronized(run) {
            StreamDiagnosticSnapshot(
                startedAt = run.startedAt,
                durationMs = elapsedMs(run),
                timeToFirstStreamRequestCompletedMs = run.firstStreamRequestCompletedMs,
                timeToFirstPlayableResultMs = run.firstPlayableResultMs,
                timeToFirstPlayableLinkMs = run.firstPlayableLinkMs,
                timeToAllStreamAddonsCompletedMs = run.allStreamAddonsCompletedMs,
                timeToAllPlayableLinksEmittedMs = run.allPlayableLinksEmittedMs,
                expectedStreamAddons = run.expectedStreamAddons,
                completedStreamAddons = run.completedStreamAddons,
                firstBatchLinks = run.firstBatchLinks ?: 0,
                parseWorkMs = run.parseWorkMs,
                pipelineMs = run.pipelineMs,
                emissionMs = run.emissionMs,
                extractorResolutionMs = run.extractorResolutionMs,
                subtitleWaitMs = run.subtitleWaitMs,
                timeToSubtitleRequestsStartedMs = run.timeToSubtitleRequestsStartedMs,
                timeToSubtitleCompletionMs = run.timeToSubtitleCompletionMs,
                rawStreams = rawStreams.coerceAtLeast(0),
                addonSubtitles = addonSubtitles.coerceAtLeast(0),
                playableCandidates = playableCandidates.coerceAtLeast(0),
                keptAfterFilters = keptAfterFilters.coerceAtLeast(0),
                emittedLinks = emittedLinks.coerceAtLeast(0),
                requests = requests,
            )
        }
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
