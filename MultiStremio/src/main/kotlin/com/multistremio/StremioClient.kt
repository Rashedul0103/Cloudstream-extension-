package com.multistremio

import com.lagradost.cloudstream3.app
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/** Talks to Stremio addons over HTTP. Every call isolates failures so one bad addon never breaks the rest. */
object StremioClient {
    private data class FetchResult(val body: String?, val status: String)

    private suspend fun fetch(url: String): FetchResult =
        try {
            val res = app.get(url, timeout = Config.settings.timeoutSec.toLong())
            if (!res.isSuccessful) FetchResult(null, "HTTP_ERROR")
            else {
                val body = res.text
                if (body.isBlank()) FetchResult(body, "EMPTY_BODY")
                else FetchResult(body, "RECEIVED")
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
    ): List<RawStream> {
        val started = System.nanoTime()
        val response = fetch(Urls.resource(addon.url, "stream", type, id))
        val parsed = if (response.body == null) ArrayParseResult<RawStream>(emptyList(), response.status)
            else Json.parseArrayDetailed<RawStream>(response.body, "streams")
        StreamDiagnostics.record(
            diagnosticRunId, addon.name, "Streams",
            (System.nanoTime() - started) / 1_000_000L, parsed.status, parsed.items.size,
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
