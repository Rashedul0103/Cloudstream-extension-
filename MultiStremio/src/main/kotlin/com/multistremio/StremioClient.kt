package com.multistremio

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/** Talks to Stremio addons over HTTP. Every call swallows its own errors so one bad addon never breaks the rest. */
object StremioClient {
    // Fan-out across many addons is useful, but unbounded requests can overwhelm devices and addon APIs.
    private val requestSlots = Semaphore(permits = 8)

    private suspend fun fetch(url: String): String? = requestSlots.withPermit {
        try {
            val res = app.get(url, timeout = Config.settings.timeoutSec.toLong())
            if (res.isSuccessful) res.text else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logError(e)
            null
        }
    }

    suspend fun catalog(addon: AddonConfig, type: String, catalogId: String, extra: String?): List<Meta> {
        val text = fetch(Urls.resource(addon.url, "catalog", type, catalogId, extra))
        return Json.parseArray<Meta>(text, "metas")
    }

    suspend fun meta(addon: AddonConfig, type: String, id: String): Meta? {
        val text = fetch(Urls.resource(addon.url, "meta", type, id))
        return Json.parse<MetaResponse>(text)?.meta
    }

    suspend fun streams(addon: AddonConfig, type: String, id: String): List<RawStream> {
        val text = fetch(Urls.resource(addon.url, "stream", type, id))
        return Json.parseArray<RawStream>(text, "streams")
    }

    suspend fun subtitles(addon: AddonConfig, type: String, id: String): List<RawSubtitle> {
        val text = fetch(Urls.resource(addon.url, "subtitles", type, id))
        return Json.parseArray<RawSubtitle>(text, "subtitles")
    }
}
