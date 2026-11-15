package com.multistremio

import com.lagradost.cloudstream3.app
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/** Talks to Stremio addons over HTTP. Every call isolates failures so one bad addon never breaks the rest. */
object StremioClient {
    // Enforce the cap from Content-Length when available and again while streaming the body.
    private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L

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
                        val body = res.body.byteStream().use { input ->
                            readBoundedUtf8(input, MAX_RESPONSE_BYTES)
                        } ?: return FetchResult(null, "BODY_TOO_LARGE")
                        if (body.isBlank()) FetchResult(body, "EMPTY_BODY")
                        else FetchResult(body, "RECEIVED")
                    }
                }
            } finally {
                // Also close responses rejected before the bounded reader consumes their body.
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
        if (diagnosticRunId != null) {
            StreamDiagnostics.markFirstStreamRequestCompleted(diagnosticRunId)
        }
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
        queueWaitMs: Long = 0L,
    ): List<RawSubtitle> {
        val started = System.nanoTime()
        val response = fetch(Urls.resource(addon.url, "subtitles", type, id))
        val parsed = if (response.body == null) ArrayParseResult<RawSubtitle>(emptyList(), response.status)
            else Json.parseArrayDetailed<RawSubtitle>(response.body, "subtitles")
        StreamDiagnostics.record(
            diagnosticRunId, addon.name, "Subtitles",
            (System.nanoTime() - started) / 1_000_000L, parsed.status, parsed.items.size,
            queueWaitMs = queueWaitMs,
        )
        return parsed.items
    }
}


/**
 * Reads UTF-8 response bytes without ever buffering more than [maxBytes].
 * Returns null as soon as the body crosses the limit, including chunked/unknown-length bodies.
 * The caller owns and closes [input].
 */
internal fun readBoundedUtf8(input: InputStream, maxBytes: Long): String? {
    val limit = maxBytes.coerceAtLeast(0L)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0L

    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue

        if (count.toLong() > limit - totalBytes) return null
        totalBytes += count
        output.write(buffer, 0, count)
    }

    // JSON addons should send UTF-8. Remove an optional UTF-8 BOM just as text readers do.
    return String(output.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
}
