package com.multistremio

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlin.coroutines.cancellation.CancellationException

/** Talks to Stremio addons over HTTP. Every call swallows its own errors so one bad addon never breaks the rest. */
object StremioClient {
    private suspend fun fetch(url: String): String? =
        try {
            val res = app.get(url, timeout = Config.settings.timeoutSec.toLong())
            if (res.isSuccessful) res.text else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logError(e)
            null
        }

    suspend fun catalog(addon: AddonConfig, type: String, catalogId: String, extra: String?): List<Meta> {
        val text = fetch(Urls.resource(addon.url, "catalog", type, catalogId, extra))
        return Json.parseArray<Meta>(text, "metas")
    }

    suspend fun meta(addon: AddonConfig, type: String, id: String): Meta? {
        val text = fetch(Urls.resource(addon.url, "meta", type, id))
        return Json.parse<MetaResponse>(text)?.meta
    }

    suspend fun streams(
        addon: AddonConfig,
        type: String,
        id: String,
        diagnosticRunId: Long? = null,
    ): List<RawStream> {
        val started = System.nanoTime()
        val text = fetch(Urls.resource(addon.url, "stream", type, id))
        val result = Json.parseArray<RawStream>(text, "streams")
        StreamDiagnostics.record(
            diagnosticRunId,
            addon.name,
            "Streams",
            (System.nanoTime() - started) / 1_000_000L,
            text != null,
            result.size,
        )
        return result
    }

    suspend fun subtitles(
        addon: AddonConfig,
        type: String,
        id: String,
        diagnosticRunId: Long? = null,
    ): List<RawSubtitle> {
        val started = System.nanoTime()
        val text = fetch(Urls.resource(addon.url, "subtitles", type, id))
        val result = Json.parseArray<RawSubtitle>(text, "subtitles")
        StreamDiagnostics.record(
            diagnosticRunId,
            addon.name,
            "Subtitles",
            (System.nanoTime() - started) / 1_000_000L,
            text != null,
            result.size,
        )
        return result
    }
}
