package com.multistremio

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class ArrayParseResult<T>(val items: List<T>, val status: String)

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

    /** Parses element by element and reports malformed responses without discarding valid entries. */
    inline fun <reified T> parseArrayDetailed(text: String?, field: String): ArrayParseResult<T> {
        if (text.isNullOrBlank()) return ArrayParseResult(emptyList(), "EMPTY_BODY")
        val root = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            return ArrayParseResult(emptyList(), "INVALID_JSON")
        }
        val arr = root?.get(field) ?: return ArrayParseResult(emptyList(), "MISSING_ARRAY")
        if (!arr.isArray) return ArrayParseResult(emptyList(), "MISSING_ARRAY")
        if (arr.size() == 0) return ArrayParseResult(emptyList(), "EMPTY")
        val out = ArrayList<T>(arr.size())
        var rejected = 0
        for (node in arr) {
            try { out.add(mapper.treeToValue(node, T::class.java)) }
            catch (e: Exception) { rejected++ }
        }
        val status = when {
            out.isEmpty() -> "INVALID_ITEMS"
            rejected > 0 -> "PARTIAL"
            else -> "OK"
        }
        return ArrayParseResult(out, status)
    }

    inline fun <reified T> parseArray(text: String?, field: String): List<T> =
        parseArrayDetailed<T>(text, field).items
}

/** Blocking HTTP used only by settings/background threads (the provider uses CloudStream's `app`). */
enum class SimpleHttpStatus {
    OK,
    EMPTY_BODY,
    HTTP_ERROR,
    TIMEOUT,
    DNS_ERROR,
    INVALID_URL,
    NETWORK_ERROR,
}

data class SimpleHttpResult(
    val body: String?,
    val status: SimpleHttpStatus,
    val httpCode: Int? = null,
)

/** Blocking HTTP used only by settings/background threads (the provider uses CloudStream's `app`). */
object SimpleHttp {
    /** Backwards-compatible body-only helper for addon creation and manifest refresh. */
    fun get(url: String, timeoutMs: Int = 20000): String? =
        getDetailed(url, timeoutMs).body

    /** Returns transport outcome and HTTP status without exposing the requested URL. */
    fun getDetailed(url: String, timeoutMs: Int = 20000): SimpleHttpResult {
        val parsedUrl = try {
            URL(url)
        } catch (_: java.net.MalformedURLException) {
            return SimpleHttpResult(null, SimpleHttpStatus.INVALID_URL)
        }

        val conn = try {
            parsedUrl.openConnection() as? HttpURLConnection
        } catch (_: Exception) {
            null
        } ?: return SimpleHttpResult(null, SimpleHttpStatus.INVALID_URL)

        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CloudStream-MultiStremio")
            val code = conn.responseCode
            if (code !in 200..299) {
                SimpleHttpResult(null, SimpleHttpStatus.HTTP_ERROR, code)
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (body.isBlank()) SimpleHttpResult(body, SimpleHttpStatus.EMPTY_BODY, code)
                else SimpleHttpResult(body, SimpleHttpStatus.OK, code)
            }
        } catch (_: java.net.SocketTimeoutException) {
            SimpleHttpResult(null, SimpleHttpStatus.TIMEOUT)
        } catch (_: java.net.UnknownHostException) {
            SimpleHttpResult(null, SimpleHttpStatus.DNS_ERROR)
        } catch (_: java.net.MalformedURLException) {
            SimpleHttpResult(null, SimpleHttpStatus.INVALID_URL)
        } catch (_: Exception) {
            SimpleHttpResult(null, SimpleHttpStatus.NETWORK_ERROR)
        } finally {
            conn.disconnect()
        }
    }
}
object Hashing {
    fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val unsigned = byte.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
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

    /** Collision-resistant ID for newly added addons. Existing saved IDs are not rewritten. */
    fun idFor(url: String): String = Hashing.sha256(url)

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
        val text = SimpleHttp.get(Urls.manifest(addon.url)) ?: return false
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
