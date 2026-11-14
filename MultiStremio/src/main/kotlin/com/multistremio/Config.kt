package com.multistremio

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object Json {
    val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    inline fun <reified T> parse(text: String?): T? {
        if (text.isNullOrBlank()) return null
        return try {
            mapper.readValue<T>(text)
        } catch (e: Exception) {
            null
        }
    }

    fun toJson(value: Any?): String = mapper.writeValueAsString(value)

    /** Parses `{ "<field>": [ ... ] }` element by element so one malformed entry doesn't drop the rest. */
    inline fun <reified T> parseArray(text: String?, field: String): List<T> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try { mapper.readTree(text)?.get(field) } catch (e: Exception) { null }
        if (arr == null || !arr.isArray) return emptyList()
        val out = ArrayList<T>(arr.size())
        for (node in arr) {
            try { out.add(mapper.treeToValue(node, T::class.java)) } catch (e: Exception) { /* skip bad entry */ }
        }
        return out
    }
}

/** Blocking HTTP used only by settings/background threads (the provider uses CloudStream's `app`). */
object SimpleHttp {
    fun get(url: String, timeoutMs: Int = 20000): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CloudStream-MultiStremio")
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

object Config {
    private const val PREFS = "multistremio"
    private const val KEY = "settings_v1"
    const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    private const val REFRESH_AFTER_MS = 24L * 60 * 60 * 1000

    // Small built-in copy so the home page works on first launch; replaced by the live manifest.
    private const val CINEMETA_MANIFEST =
        """{"id":"com.linvo.cinemeta","name":"Cinemeta","version":"3.0.0","resources":["catalog","meta"],""" +
            """"types":["movie","series"],"idPrefixes":["tt"],"catalogs":[""" +
            """{"type":"movie","id":"top","name":"Popular","extra":[{"name":"search"},{"name":"genre"},{"name":"skip"}]},""" +
            """{"type":"series","id":"top","name":"Popular","extra":[{"name":"search"},{"name":"genre"},{"name":"skip"}]}]}"""

    private var prefs: SharedPreferences? = null

    @Volatile
    var settings: Settings = Settings()
        private set

    private val parsedCache = ConcurrentHashMap<String, Pair<String, Manifest?>>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = Json.parse<Settings>(prefs?.getString(KEY, null)) ?: Settings()
        if (!settings.seeded) {
            settings.seeded = true
            if (settings.addons.isEmpty()) {
                settings.addons.add(
                    AddonConfig(
                        id = idFor(CINEMETA_URL), url = CINEMETA_URL, name = "Cinemeta",
                        useStreams = false, useSubtitles = false,
                        manifest = CINEMETA_MANIFEST, manifestFetchedAt = 0L,
                    ),
                )
            }
            save()
        }
        refreshStaleManifestsInBackground()
    }

    @Synchronized
    fun save() {
        prefs?.edit()?.putString(KEY, Json.toJson(settings))?.apply()
    }

    @Synchronized
    fun update(block: Settings.() -> Unit) {
        settings.block()
        save()
    }

    @Synchronized
    fun replace(newSettings: Settings) {
        settings = newSettings
        save()
    }

    fun idFor(url: String): String = Integer.toHexString(url.lowercase().hashCode())

    /** Parsed manifest of an addon, cached per manifest text. */
    fun manifestOf(addon: AddonConfig): Manifest? {
        val raw = addon.manifest ?: return null
        val cached = parsedCache[addon.id]
        if (cached != null && cached.first == raw) return cached.second
        val parsed = Json.parse<Manifest>(raw)
        parsedCache[addon.id] = raw to parsed
        return parsed
    }

    /** Downloads and stores a fresh manifest. Blocking; call off the main thread. */
    fun refreshManifest(addon: AddonConfig): Boolean {
        val text = SimpleHttp.get("${addon.url}/manifest.json") ?: return false
        val m = Json.parse<Manifest>(text) ?: return false
        if (m.name == null && m.resources == null) return false
        update {
            addon.manifest = text
            addon.manifestFetchedAt = System.currentTimeMillis()
            if (addon.name.isBlank()) addon.name = m.name ?: addon.url
        }
        return true
    }

    private fun refreshStaleManifestsInBackground() {
        val stale = settings.addons.filter { System.currentTimeMillis() - it.manifestFetchedAt > REFRESH_AFTER_MS }
        if (stale.isEmpty()) return
        thread(start = true, isDaemon = true, name = "multistremio-refresh") {
            stale.forEach { try { refreshManifest(it) } catch (_: Exception) { } }
        }
    }
}
