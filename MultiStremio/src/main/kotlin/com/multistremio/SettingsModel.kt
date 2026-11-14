package com.multistremio

// Plain data that is persisted as JSON (see Config). No Android dependencies here.

const val MODE_ANY = 0
const val MODE_HIDE = 1
const val MODE_ONLY = 2

enum class SortPreset(val label: String) {
    BEST_QUALITY("Best quality (resolution, source, HDR, size)"),
    QUALITY_SEEDERS("Resolution, then most seeders"),
    LARGEST("Largest file first"),
    SMALLEST("Smallest file first (data saver)"),
    MOST_SEEDERS("Most seeders first"),
    ADDON_ORDER("Addon order (your priority list)");

    companion object {
        fun from(name: String?): SortPreset =
            values().firstOrNull { it.name == name } ?: BEST_QUALITY
    }
}

data class AddonConfig(
    var id: String = "",
    /** Base url without a trailing "/manifest.json" or "/". May contain the user's own config/keys. */
    var url: String = "",
    var name: String = "",
    var enabled: Boolean = true,
    var useCatalogs: Boolean = true,
    var useMeta: Boolean = true,
    var useStreams: Boolean = true,
    var useSubtitles: Boolean = true,
    /** "type/id" of catalogs the user switched off for the home page. */
    var hiddenCatalogs: MutableList<String> = mutableListOf(),
    /** Raw manifest.json text, cached so the home page can be built without a network call. */
    var manifest: String? = null,
    var manifestFetchedAt: Long = 0L,
)

data class FilterSettings(
    /** Allowed resolution buckets; 0 means "unknown resolution". */
    var allowedResolutions: MutableList<Int> = mutableListOf(2160, 1440, 1080, 720, 576, 480, 360, 240, 0),
    var hideCam: Boolean = true,
    var hideMagnets: Boolean = false,
    var hideUncachedDebrid: Boolean = false,
    var hevcMode: Int = MODE_ANY,
    var hdrMode: Int = MODE_ANY,
    var minSizeMb: Int = 0,
    var maxSizeGb: Int = 0,
    var minSeeders: Int = 0,
    var excludeKeywords: MutableList<String> = mutableListOf(),
    var requireKeywords: MutableList<String> = mutableListOf(),
    var requireLanguages: MutableList<String> = mutableListOf(),
    var strictLanguage: Boolean = false,
    var maxPerAddon: Int = 0,
    var maxTotal: Int = 0,
)

data class SortSettings(
    var preset: String = SortPreset.BEST_QUALITY.name,
    var cachedFirst: Boolean = false,
    var preferKeywords: MutableList<String> = mutableListOf(),
    var preferLanguages: MutableList<String> = mutableListOf(),
    /**
     * CloudStream orders links by its own quality profile first and only keeps arrival order for
     * ties. Turning this on reports every link as "unknown quality" so OUR order wins; the
     * resolution is still shown in the link name.
     */
    var overrideAppQuality: Boolean = false,
)

/** Stable identity for a catalog row across app launches and addon manifest refreshes. */
fun catalogOrderKey(addonId: String, type: String, catalogId: String): String =
    "$addonId|$type|$catalogId"

data class Settings(
    var addons: MutableList<AddonConfig> = mutableListOf(),
    /** Stable keys defining the user's global order for home-page catalog rows. */
    var catalogOrder: MutableList<String> = mutableListOf(),
    var filter: FilterSettings = FilterSettings(),
    var sort: SortSettings = SortSettings(),
    var timeoutSec: Int = 30,
    var fetchSubtitles: Boolean = true,
    var subtitleLanguages: MutableList<String> = mutableListOf(),
    var detailedLabels: Boolean = true,
    var seeded: Boolean = false,
)
