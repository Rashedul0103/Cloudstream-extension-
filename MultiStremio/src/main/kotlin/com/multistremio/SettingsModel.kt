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

/** Updates an addon's persisted enabled state without mutating a menu snapshot. */
internal fun Settings.setAddonEnabled(addonId: String, enabled: Boolean): Boolean {
    val addon = this.addons.firstOrNull { it.id == addonId } ?: return false
    addon.enabled = enabled
    return true
}

/** Removes an addon and only the homepage configuration owned by that addon. */
internal fun Settings.removeAddon(addonId: String): Boolean {
    if (!addons.removeAll { it.id == addonId }) return false
    val prefix = "$addonId|"
    catalogOrder.removeAll { it.startsWith(prefix) }
    customCatalogNames.keys.removeAll { it.startsWith(prefix) }
    return true
}

/** Removes an imported row from MultiStremio without touching its source extension. */
internal fun Settings.removeExternalHomeRow(key: String): Boolean {
    if (!externalRows.removeAll { it.key == key }) return false
    catalogOrder.removeAll { it == key }
    customCatalogNames.remove(key)
    return true
}

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
    /** Preferred codec order, e.g. HEVC, AV1, AVC. Empty means do not apply an extra codec preference. */
    var preferCodecs: MutableList<String> = mutableListOf(),
    /** Preferred audio format order, e.g. Atmos, TrueHD, DTS-HD, DD+, AAC. */
    var preferAudio: MutableList<String> = mutableListOf(),
    /**
     * CloudStream orders links by its own quality profile first and only keeps arrival order for
     * ties. Turning this on reports every link as "unknown quality" so OUR order wins; the
     * resolution is still shown in the link name.
     */
    var overrideAppQuality: Boolean = false,
)

/**
 * Computes the insertion slot among the remaining (non-dragged) row centers.
 * The result is directly usable with LinearLayout.addView after removing the dragged row.
 */
object DragReorderMath {
    fun insertionIndex(otherItemCenters: List<Float>, pointerY: Float): Int =
        otherItemCenters.count { pointerY >= it }.coerceIn(0, otherItemCenters.size)
}

/** Stable list operations shared by the catalog-row UI and its JVM regression tests. */
object CatalogOrderMath {
    /**
     * Applies serial numbers to one addon section without moving rows across other sections.
     *
     * Positive positions may repeat. Rows tied on a position keep their current relative order,
     * making ties predictable and preventing an arbitrary jump. Gaps are allowed. Blank, zero,
     * negative or missing positions are rejected. The original group slots are retained.
     */
    fun reorderGroup(
        currentOrder: List<String>,
        groupKeys: List<String>,
        positions: Map<String, Int>,
    ): List<String>? {
        if (groupKeys.isEmpty()) return currentOrder.toList()
        val groupKeySet = groupKeys.toSet()
        if (groupKeySet.size != groupKeys.size || positions.keys != groupKeySet) return null

        val requested = groupKeys.map { key -> positions[key] ?: return null }
        if (requested.any { it <= 0 }) return null

        val groupIndexes = currentOrder.indices.filter { currentOrder[it] in groupKeySet }
        if (groupIndexes.size != groupKeys.size) return null

        // The explicit original index is the tie-breaker: duplicate positions never cause a
        // row to jump unpredictably, even if the sorting implementation changes.
        val sortedGroup = groupKeys.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { positions.getValue(it.value) }
                    .thenBy { it.index },
            )
            .map { it.value }

        return currentOrder.toMutableList().apply {
            groupIndexes.forEachIndexed { index, currentIndex ->
                this[currentIndex] = sortedGroup[index]
            }
        }
    }

    /**
     * Applies one global position sequence across every addon and imported extension row.
     *
     * Positive positions may repeat; tied rows keep their current relative order. Rows missing
     * from the current screen (for example, because an addon's manifest is temporarily unavailable)
     * retain their saved keys after the rows that can currently be edited.
     */
    fun reorderAll(
        currentOrder: List<String>,
        rowKeys: List<String>,
        positions: Map<String, Int>,
    ): List<String>? {
        if (rowKeys.isEmpty()) return currentOrder.distinct()
        val rowKeySet = rowKeys.toSet()
        if (rowKeySet.size != rowKeys.size || positions.keys != rowKeySet) return null
        if (positions.values.any { it <= 0 }) return null

        val orderedRows = rowKeys.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { positions.getValue(it.value) }
                    .thenBy { it.index },
            )
            .map { it.value }
        val unavailableKeys = currentOrder.filterNot { it in rowKeySet }.distinct()
        return orderedRows + unavailableKeys
    }

    /** Seed every currently visible row before appending a newly imported row. */
    fun append(keys: List<String>, newKey: String): List<String> =
        (keys + newKey).distinct()
}

/**
 * Reorders complete homepage source groups without changing the order of rows inside a group.
 * Existing order keys for temporarily unavailable rows are retained after the visible groups.
 */
object HomepageGroupOrderMath {
    fun reorderCatalogGroups(
        currentOrder: List<String>,
        groupRows: Map<String, List<String>>,
        orderedGroupKeys: List<String>,
    ): List<String>? {
        if (groupRows.keys != orderedGroupKeys.toSet() || orderedGroupKeys.size != groupRows.size) return null
        val allRowKeys = groupRows.values.flatten()
        if (allRowKeys.size != allRowKeys.toSet().size) return null

        val visibleOrder = orderedGroupKeys.flatMap { groupRows[it] ?: return null }
        val visibleKeys = allRowKeys.toSet()
        val unavailableKeys = currentOrder.filterNot { it in visibleKeys }
        return visibleOrder + unavailableKeys
    }

    /**
     * Reorders only addons represented by homepage sections, leaving addons without homepage
     * rows in their existing slots. This keeps their relative priority stable.
     */
    fun reorderAddonSlots(
        currentAddonIds: List<String>,
        orderedGroupAddonIds: List<String>,
    ): List<String>? {
        if (orderedGroupAddonIds.isEmpty()) return currentAddonIds.toList()
        val selected = orderedGroupAddonIds.toSet()
        if (selected.size != orderedGroupAddonIds.size) return null
        val currentSelected = currentAddonIds.filter { it in selected }
        if (currentSelected.size != selected.size || currentSelected.toSet() != selected) return null

        val iterator = orderedGroupAddonIds.iterator()
        return currentAddonIds.map { id -> if (id in selected) iterator.next() else id }
    }
}

/** Stable identity for a catalog row across app launches and addon manifest refreshes. */
fun catalogOrderKey(addonId: String, type: String, catalogId: String): String =
    "$addonId|$type|$catalogId"

/** A stable reference to a homepage row owned by another installed CloudStream extension. */
data class ExternalRowConfig(
    var key: String = "",
    var providerName: String = "",
    var providerClass: String = "",
    var sourcePlugin: String = "",
    var rowName: String = "",
    /** Used only to resolve legacy duplicate rows when their opaque data has not changed. */
    var dataHash: String = "",
    var horizontalImages: Boolean = false,
    var enabled: Boolean = true,
)

/**
 * Identity for imported rows deliberately excludes their opaque request data and display name.
 * Those values can change when an extension updates without changing the logical homepage row.
 */
object ExternalRowIdentity {
    fun descriptor(
        sourcePlugin: String,
        providerClass: String,
        rowName: String,
        horizontalImages: Boolean,
    ): String = listOf(sourcePlugin, providerClass, rowName, horizontalImages.toString())
        .joinToString("\u0000")

    fun key(descriptor: String, occurrence: Int): String =
        "external:" + Hashing.sha256("$descriptor\u0000$occurrence")

    fun matches(saved: ExternalRowConfig, current: ExternalRowConfig): Boolean =
        saved.sourcePlugin == current.sourcePlugin &&
            saved.providerClass == current.providerClass &&
            saved.rowName == current.rowName &&
            saved.horizontalImages == current.horizontalImages
}

data class StreamFormatField(val id: String, val title: String, val description: String)

object StreamFormatFields {
    val all = listOf(
        StreamFormatField("addon", "Addon name", "The outer extension supplying streams, e.g. CNCVerse"),
        StreamFormatField("provider", "Provider / source name", "The inner provider of an aggregator addon, e.g. MovieBox or Vega Movies"),
        StreamFormatField("quality", "Video quality", "4K, 1080p, 720p and similar"),
        StreamFormatField("source", "Video source", "WEB-DL, BluRay, REMUX and similar"),
        StreamFormatField("hdr", "HDR format", "HDR10, Dolby Vision and similar"),
        StreamFormatField("codec", "Video codec", "HEVC, AV1 or AVC"),
        StreamFormatField("audio", "Audio format", "Atmos, TrueHD, DTS, AAC and similar"),
        StreamFormatField("language", "Audio language", "Languages detected from the release name"),
        StreamFormatField("subtitles", "Subtitle languages", "Languages available as subtitle tracks"),
        StreamFormatField("size", "File size", "Approximate size reported by the addon"),
        StreamFormatField("seeders", "Seeders", "Seeder count for torrent streams"),
        StreamFormatField("cache", "Cache status", "Cached or uncached"),
        StreamFormatField("link_type", "Link type", "Direct link or magnet link"),
        StreamFormatField("release", "Release name", "The original filename or release title"),
    )
    // Provider is optional: many addons expose no separate inner-provider name.
    val defaultOrder = all.map { it.id }.filterNot { it == "provider" }
}

data class StreamFormatSettings(
    var enabled: Boolean = false,
    var fields: MutableList<String> = StreamFormatFields.defaultOrder.toMutableList(),
    var separator: String = " · ",
    var showLabels: Boolean = false,
)

data class Settings(
    var addons: MutableList<AddonConfig> = mutableListOf(),
    /** Stable keys defining the user's global order for all homepage rows. */
    var catalogOrder: MutableList<String> = mutableListOf(),
    /** Homepage rows selected from other installed CloudStream extensions. */
    var externalRows: MutableList<ExternalRowConfig> = mutableListOf(),
    var filter: FilterSettings = FilterSettings(),
    var sort: SortSettings = SortSettings(),
    var timeoutSec: Int = 30,
    var fetchSubtitles: Boolean = true,
    var subtitleLanguages: MutableList<String> = mutableListOf(),
    var detailedLabels: Boolean = true,
    var streamFormat: StreamFormatSettings = StreamFormatSettings(),
    /** Last selected quick filter profile; manual edits are marked CUSTOM by Config.update. */
    var filterPreset: String = "BALANCED",
    /** Optional user labels keyed by stable homepage-row identity. */
    var customCatalogNames: MutableMap<String, String> = mutableMapOf(),
    /** OFF, PREFIX or SUFFIX. */
    var rowTitleMode: String = "OFF",
    var seeded: Boolean = false,
)

/**
 * Copies every mutable settings collection while sharing immutable strings and cached manifest
 * text. Provider code uses this before iterating settings that the UI can update concurrently.
 */
fun Settings.snapshot(): Settings = copy(
    addons = addons.map { addon ->
        addon.copy(hiddenCatalogs = addon.hiddenCatalogs.toMutableList())
    }.toMutableList(),
    catalogOrder = catalogOrder.toMutableList(),
    externalRows = externalRows.map { it.copy() }.toMutableList(),
    customCatalogNames = customCatalogNames.toMutableMap(),
    filter = filter.copy(
        allowedResolutions = filter.allowedResolutions.toMutableList(),
        excludeKeywords = filter.excludeKeywords.toMutableList(),
        requireKeywords = filter.requireKeywords.toMutableList(),
        requireLanguages = filter.requireLanguages.toMutableList(),
    ),
    sort = sort.copy(
        preferKeywords = sort.preferKeywords.toMutableList(),
        preferLanguages = sort.preferLanguages.toMutableList(),
        preferCodecs = sort.preferCodecs.toMutableList(),
        preferAudio = sort.preferAudio.toMutableList(),
    ),
    subtitleLanguages = subtitleLanguages.toMutableList(),
    streamFormat = streamFormat.copy(fields = streamFormat.fields.toMutableList()),
)


/**
 * Normalizes persisted/imported preferences before they reach network and playback code.
 * This keeps old backups and hand-edited JSON from creating invalid timeouts or filter states.
 */
fun Settings.sanitized(): Settings {
    // Older settings files predate the preset label. Preserve manually customised values as Custom.
    if (filterPreset == "BALANCED" && (filter != FilterSettings() || sort != SortSettings())) {
        filterPreset = "CUSTOM"
    }

    timeoutSec = timeoutSec.coerceIn(5, 180)

    val validResolutions = setOf(2160, 1440, 1080, 720, 576, 480, 360, 240, 0)
    filter.allowedResolutions = filter.allowedResolutions
        .filter { it in validResolutions }
        .distinct()
        .toMutableList()
    filter.hevcMode = filter.hevcMode.coerceIn(MODE_ANY, MODE_ONLY)
    filter.hdrMode = filter.hdrMode.coerceIn(MODE_ANY, MODE_ONLY)
    filter.minSizeMb = filter.minSizeMb.coerceAtLeast(0)
    filter.maxSizeGb = filter.maxSizeGb.coerceAtLeast(0)
    filter.minSeeders = filter.minSeeders.coerceAtLeast(0)
    filter.maxPerAddon = filter.maxPerAddon.coerceAtLeast(0)
    filter.maxTotal = filter.maxTotal.coerceAtLeast(0)
    filter.excludeKeywords = filter.excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    filter.requireKeywords = filter.requireKeywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    filter.requireLanguages = filter.requireLanguages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()

    sort.preset = SortPreset.from(sort.preset).name
    sort.preferKeywords = sort.preferKeywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    sort.preferLanguages = sort.preferLanguages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    sort.preferCodecs = sort.preferCodecs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    sort.preferAudio = sort.preferAudio.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()

    val validFieldIds = StreamFormatFields.all.map { it.id }.toSet()
    streamFormat.fields = streamFormat.fields.filter { it in validFieldIds }.distinct().toMutableList()
    if (streamFormat.separator !in setOf(" · ", " | ", " • ", " — ", " / ", "\n")) {
        streamFormat.separator = " · "
    }

    addons = addons.asSequence()
        .filter { it.url.isNotBlank() }
        .distinctBy { it.url }
        .onEach { addon ->
            if (addon.id.isBlank()) addon.id = Hashing.sha256(addon.url)
            if (addon.name.isBlank()) {
                addon.name = runCatching { java.net.URI(addon.url).host }.getOrNull().orEmpty().ifBlank { addon.url }
            }
            addon.hiddenCatalogs = addon.hiddenCatalogs.filter { it.isNotBlank() }.distinct().toMutableList()
        }
        .toMutableList()
    catalogOrder = catalogOrder.filter { it.isNotBlank() }.distinct().toMutableList()
    externalRows = externalRows.filter { it.key.isNotBlank() }.distinctBy { it.key }.toMutableList()
    subtitleLanguages = subtitleLanguages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    filterPreset = filterPreset.uppercase().takeIf { it in setOf("BALANCED", "BEST_QUALITY", "DATA_SAVER", "CUSTOM") } ?: "CUSTOM"
    rowTitleMode = rowTitleMode.uppercase().takeIf { it in setOf("OFF", "PREFIX", "SUFFIX") } ?: "OFF"
    customCatalogNames = customCatalogNames.mapValues { it.value.trim() }
        .filterValues { it.isNotBlank() }.toMutableMap()
    return this
}
