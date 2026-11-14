package com.multistremio

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ---------------------------------------------------------------------------------------------
// Stremio addon protocol models (https://github.com/Stremio/stremio-addon-sdk)
// Every field is nullable/defaulted because addons in the wild are sloppy.
// ---------------------------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class Manifest(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("version") val version: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    /** Either plain strings ("stream") or objects {name, types, idPrefixes}. */
    @JsonProperty("resources") val resources: List<Any>? = null,
    @JsonProperty("types") val types: List<String>? = null,
    @JsonProperty("idPrefixes") val idPrefixes: List<String>? = null,
    @JsonProperty("catalogs") val catalogs: List<ManifestCatalog>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestCatalog(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("extra") val extra: List<ExtraProp>? = null,
    /** Legacy form of `extra`. */
    @JsonProperty("extraSupported") val extraSupported: List<String>? = null,
    @JsonProperty("extraRequired") val extraRequired: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtraProp(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("isRequired") val isRequired: Boolean? = null,
    @JsonProperty("options") val options: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaResponse(
    @JsonProperty("meta") val meta: Meta? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Meta(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    /** Sometimes a string, sometimes a number. */
    @JsonProperty("imdbRating") val imdbRating: Any? = null,
    @JsonProperty("year") val year: Any? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("cast") val cast: List<String>? = null,
    @JsonProperty("trailers") val trailers: List<MetaTrailer>? = null,
    @JsonProperty("trailerStreams") val trailerStreams: List<MetaTrailerStream>? = null,
    @JsonProperty("videos") val videos: List<Video>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaTrailer(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("type") val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaTrailerStream(
    @JsonProperty("ytId") val ytId: String? = null,
    @JsonProperty("title") val title: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Video(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("description") val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RawSubtitle(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RawStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("ytId") val ytId: String? = null,
    @JsonProperty("externalUrl") val externalUrl: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    @JsonProperty("fileIdx") val fileIdx: Int? = null,
    @JsonProperty("sources") val sources: List<String>? = null,
    @JsonProperty("subtitles") val subtitles: List<RawSubtitle>? = null,
    @JsonProperty("behaviorHints") val behaviorHints: BehaviorHints? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BehaviorHints(
    @JsonProperty("filename") val filename: String? = null,
    @JsonProperty("videoSize") val videoSize: Any? = null, // number or numeric string
    @JsonProperty("bingeGroup") val bingeGroup: String? = null,
    @JsonProperty("notWebReady") val notWebReady: Boolean? = null,
    @JsonProperty("proxyHeaders") val proxyHeaders: ProxyHeaders? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxyHeaders(
    @JsonProperty("request") val request: Map<String, String>? = null,
)

// ---------------------------------------------------------------------------------------------
// References that travel through CloudStream as JSON strings (search url / episode data).
// Short keys keep the strings small.
// ---------------------------------------------------------------------------------------------

/** Points at a catalog entry; stored as the SearchResponse url. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemRef(
    @JsonProperty("a") val a: String? = null, // addon id the entry came from
    @JsonProperty("t") val t: String? = null, // stremio type
    @JsonProperty("i") val i: String? = null, // stremio id
    @JsonProperty("n") val n: String? = null, // name (fallback if no meta addon answers)
    @JsonProperty("p") val p: String? = null, // poster (fallback)
)

/** Points at something playable; stored as the loadLinks `data` string. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamRef(
    @JsonProperty("t") val t: String? = null, // stremio type
    @JsonProperty("i") val i: String? = null, // id to request streams for (video id for series)
    @JsonProperty("m") val m: String? = null, // parent meta id
    @JsonProperty("s") val s: Int? = null,
    @JsonProperty("e") val e: Int? = null,
)

/** Points at one catalog on one addon; stored as MainPageData.data. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogRef(
    @JsonProperty("a") val a: String? = null,
    @JsonProperty("t") val t: String? = null,
    @JsonProperty("c") val c: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Manifest helpers (extension functions so Jackson never sees them)
// ---------------------------------------------------------------------------------------------

private fun typeAndPrefixMatch(
    types: List<String>?,
    prefixes: List<String>?,
    type: String?,
    id: String?,
): Boolean {
    if (type != null && !types.isNullOrEmpty() && types.none { it.equals(type, ignoreCase = true) }) return false
    if (id != null && !prefixes.isNullOrEmpty() && prefixes.none { id.startsWith(it) }) return false
    return true
}

/** Does this addon offer [resource] ("stream", "meta", "subtitles", "catalog") for this type/id? */
fun Manifest.supports(resource: String, type: String?, id: String?): Boolean {
    val list = resources ?: return false
    for (r in list) {
        when (r) {
            is String ->
                if (r == resource && typeAndPrefixMatch(types, idPrefixes, type, id)) return true
            is Map<*, *> ->
                if (r["name"] == resource) {
                    val t = (r["types"] as? List<*>)?.filterIsInstance<String>() ?: types
                    val p = (r["idPrefixes"] as? List<*>)?.filterIsInstance<String>() ?: idPrefixes
                    if (typeAndPrefixMatch(t, p, type, id)) return true
                }
        }
    }
    return false
}

fun Manifest.hasResource(resource: String): Boolean {
    val list = resources ?: return false
    return list.any { (it is String && it == resource) || (it is Map<*, *> && it["name"] == resource) }
}

/** Names of all extras this catalog accepts (modern `extra` plus legacy `extraSupported`). */
fun ManifestCatalog.extraNames(): Set<String> =
    (extra.orEmpty().mapNotNull { it.name } + extraSupported.orEmpty()).toSet()

fun ManifestCatalog.supportsExtra(name: String): Boolean = name in extraNames()

/** Extras that MUST be sent (e.g. search-only catalogs). */
fun ManifestCatalog.requiredExtras(): Set<String> =
    (extra.orEmpty().filter { it.isRequired == true }.mapNotNull { it.name } + extraRequired.orEmpty()).toSet()

/** True when the catalog can be shown as a plain home-page row (no mandatory extras). */
fun ManifestCatalog.isHomeCatalog(): Boolean = requiredExtras().isEmpty()

/** True when the catalog can answer a free-text search. */
fun ManifestCatalog.isSearchable(): Boolean = supportsExtra("search") && (requiredExtras() - "search").isEmpty()
