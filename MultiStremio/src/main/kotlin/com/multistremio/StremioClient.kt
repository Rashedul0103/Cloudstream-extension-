package com.multistremio

import com.lagradost.cloudstream3.app
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/** Talks to Stremio addons over HTTP. Every call isolates failures so one bad addon never breaks the rest. */
object StremioClient {
    // Reject oversized addon payloads before reading/parsing whenever Content-Length is known.
    private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024

    private data class FetchResult(val body: String?, val status: String)

    private suspend fun fetch(url: String): FetchResult =
        try {
            val res = app.get(url, timeout = Config.settings.timeoutSec.toLong())
            try {
                val contentLength = res.size
                when {
                    !res.isSuccessful -> FetchResult(null, "HTTP_ERROR")
                    // NiceHttp exposes Content-Length without consuming the response body. Avoid
                    // materializing large payloads when the server provides this header.
                    contentLength != null && contentLength > MAX_RESPONSE_BYTES -> FetchResult(null, "BODY_TOO_LARGE")
                    else -> {
                        val body = res.text
                        when {
                            body.length > MAX_RESPONSE_CHARS -> FetchResult(null, "BODY_TOO_LARGE")
                            body.isBlank() -> FetchResult(body, "EMPTY_BODY")
                            else -> FetchResult(body, "RECEIVED")
                        }
                    }
                }
            } finally {
                // Error and oversized responses do not consume .text, so close them explicitly.
                // Closing an already-consumed body is safe and keeps connection resources bounded.
                runCatching { res.okhttpResponse.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            FetchResult(null, "TIMEOUT")
        } catch (_: Exception) {
            // Avoid logging the exception message: HTTP failures can include private addon URLs
            // and access tokens in their request details.
            FetchResult(null, "NETWORK_ERROR")
        }

    suspend fun catalog(addon: AddonConfig, type: String, catalogId: String, extra: String?): List<Meta> {
        val response = fetch(Urls.resource(addon.url, "catalog", type, catalogId, extra))
        return Json.parseArray<Meta>(response.body, "metas")
    }

    suspend fun meta(addon: AddonConfig, type: String, id: String): Meta? {
        val response = fetch(Urls.resource(addon.url, "meta", type, id))
        return Json.parse<MetaResponse>(response.body)?.meta
    }

    suspend fun streams(
        addon: AddonConfig,
        type: String,
        id: String,
        diagnosticRunId: Long? = null,
        queueWaitMs: Long = 0L,
    ): List<RawStream> {
        val started = System.nanoTime()
        val response = fetch(Urls.resource(addon.url, "stream", type, id))
        diagnosticRunId?.let(StreamDiagnostics::markFirstStreamRequestCompleted)
        val parsed = if (response.body == null) ArrayParseResult<RawStream>(emptyList(), response.status)
            else Json.parseArrayDetailed<RawStream>(response.body, "streams")
        StreamDiagnostics.record(
            diagnosticRunId, addon.name, "Streams",
            (System.nanoTime() - started) / 1_000_000L, parsed.status, parsed.items.size,
            queueWaitMs = queueWaitMs,
        )
        return parsed.items
    }

    suspend fun subtitles(
        addon: AddonConfig,
        type: String,
        id: String,
        diagnosticRunId: Long? = null,
    ): List<RawSubtitle> {
        val started = System.nanoTime()
        val response = fetch(Urls.resource(addon.url, "subtitles", type, id))
        val parsed = if (response.body == null) ArrayParseResult<RawSubtitle>(emptyList(), response.status)
            else Json.parseArrayDetailed<RawSubtitle>(response.body, "subtitles")
        StreamDiagnostics.record(
            diagnosticRunId, addon.name, "Subtitles",
            (System.nanoTime() - started) / 1_000_000L, parsed.status, parsed.items.size,
        )
        return parsed.items
    }
}
