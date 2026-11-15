package com.multistremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.*
import java.util.concurrent.atomic.AtomicReference

/** A Stremio homepage catalog exposed as its own CloudStream search row. */
internal data class OtherSearchRow(
    val key: String,
    val addonId: String,
    val addonName: String,
    val type: String,
    val catalogId: String,
    val title: String,
)

internal object OtherSearchRows {
    /**
     * All eligible catalog rows, including add-ons where Other is currently off.
     * These are used to reserve provider instances before CloudStream creates its search view model.
     */
    fun candidates(
        settings: Settings,
        manifestOf: (AddonConfig) -> Manifest? = { Config.manifestOf(it) },
    ): List<OtherSearchRow> =
        HomeRows.resolve(settings, manifestOf = manifestOf).mapNotNull { homeRow ->
            val addon = homeRow.addon ?: return@mapNotNull null
            val type = homeRow.type ?: return@mapNotNull null
            val catalogId = homeRow.catalogId ?: return@mapNotNull null
            val manifest = manifestOf(addon) ?: return@mapNotNull null
            val catalog = manifest.catalogs.orEmpty().firstOrNull {
                it.type == type && it.id == catalogId
            } ?: return@mapNotNull null

            if (!manifest.supports("catalog", type, null) || !catalog.isSearchable()) {
                return@mapNotNull null
            }

            OtherSearchRow(
                key = homeRow.key,
                addonId = addon.id,
                addonName = addon.name,
                type = type,
                catalogId = catalogId,
                title = HomeRows.homeTitle(homeRow, settings),
            )
        }

    /** Only enabled add-ons with Other selected are routed to their own search sections. */
    fun resolve(
        settings: Settings,
        manifestOf: (AddonConfig) -> Manifest? = { Config.manifestOf(it) },
    ): List<OtherSearchRow> {
        val eligibleAddonIds = settings.addons
            .filter { it.enabled && it.useOther }
            .mapTo(HashSet()) { it.id }
        return candidates(settings, manifestOf).filter { it.addonId in eligibleAddonIds }
    }

    fun dedicatedCatalogKeys(settings: Settings): Set<String> =
        resolve(settings).mapTo(HashSet()) { it.key }
}

/**
 * A provider slot is registered once when the plugin loads. Its row/name/type are reassigned
 * in-place as settings change. CloudStream keeps an activity-scoped SearchViewModel with a cached
 * APIRepository list, so adding/removing provider instances after startup would leave that list
 * stale. Reserving slots avoids that lifecycle bug.
 */
private class OtherSearchMainAPI(
    private val slotIndex: Int,
) : MainAPI() {
    private val inactiveName = "MultiStremio · Other slot ${slotIndex + 1}"
    private val currentRow = AtomicReference<OtherSearchRow?>(null)

    override var name: String = inactiveName
    override var mainUrl: String = "https://other-search-${slotIndex + 1}.multistremio.invalid"
    override var lang: String = "en"
    override val hasMainPage: Boolean = false
    override val hasQuickSearch: Boolean = false
    // An unused slot must not appear in CloudStream's provider filter or preferred-media list.
    override var supportedTypes: Set<TvType> = emptySet()

    private val delegate = MultiStremio()

    fun inactiveDisplayName(): String = inactiveName

    @Synchronized
    fun configure(row: OtherSearchRow?, displayName: String) {
        val oldName = name
        currentRow.set(row)
        supportedTypes = row?.let { setOf(StremioMediaTypes.toCloudStream(it.type)) } ?: emptySet()

        if (oldName != displayName) {
            // Keep APIHolder's name -> provider lookup in sync without replacing the provider
            // object referenced by CloudStream's already-created SearchViewModel.
            APIHolder.removePluginMapping(this)
            name = displayName
            APIHolder.addPluginMapping(this)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val selected = currentRow.get() ?: return emptyList()
        val settings = Config.snapshot()
        val current = OtherSearchRows.resolve(settings).firstOrNull { it.key == selected.key }
            ?: return emptyList()
        val addon = settings.addons.firstOrNull {
            it.id == current.addonId && it.enabled && it.useOther
        } ?: return emptyList()
        val manifest = Config.manifestOf(addon) ?: return emptyList()
        val catalog = manifest.catalogs.orEmpty().firstOrNull {
            it.type == current.type && it.id == current.catalogId
        } ?: return emptyList()
        if (!manifest.supports("catalog", current.type, null) || !catalog.isSearchable()) {
            return emptyList()
        }

        val metas = StremioClient.catalog(
            addon,
            current.type,
            current.catalogId,
            "search=${Urls.encodeComponent(query)}",
        )
        val unique = SearchMetaDeduplication.unique(
            metas.map { Triple(addon.id, current.type, it) },
        )
        return unique.mapNotNull { (addonId, fallbackType, meta) ->
            delegate.run {
                meta.toSearchResponse(addonId, fallbackType, otherSource = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? = delegate.load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = delegate.loadLinks(data, isCasting, subtitleCallback, callback)
}

internal object OtherSearchProviders {
    private const val RESERVED_NEW_ROW_SLOTS = 32
    private val slots = mutableListOf<OtherSearchMainAPI>()
    @Volatile private var capacityExceeded: Int = 0

    /**
     * Register all slots exactly once during plugin load, before CloudStream builds search repos.
     * Existing configured catalogs plus spare slots support toggles/additions without late APIs.
     */
    @Synchronized
    fun attach(settings: Settings, register: (MainAPI) -> Unit) {
        if (slots.isNotEmpty()) return
        val initialCandidates = OtherSearchRows.candidates(settings).size
        val slotCount = initialCandidates + RESERVED_NEW_ROW_SLOTS
        repeat(slotCount) { index ->
            val provider = OtherSearchMainAPI(index)
            slots += provider
            register(provider)
        }
        refresh()
    }

    @Synchronized
    fun refresh() {
        if (slots.isEmpty()) return
        val settings = Config.snapshot()
        val desiredRows = OtherSearchRows.resolve(settings)
        val slotSet = slots.toSet()
        val usedNames = APIHolder.apis.withLock { APIHolder.apis.toList() }
            .filterNot { it in slotSet }
            .mapTo(HashSet()) { it.name }

        capacityExceeded = (desiredRows.size - slots.size).coerceAtLeast(0)
        val assignedNames = mutableSetOf<String>()
        val assignments = desiredRows.take(slots.size).map { row ->
            val name = uniqueName(row.title, row.addonName, usedNames + assignedNames)
            assignedNames.add(name)
            row to name
        }

        slots.forEachIndexed { index, provider ->
            val assignment = assignments.getOrNull(index)
            provider.configure(
                row = assignment?.first,
                displayName = assignment?.second ?: provider.inactiveDisplayName(),
            )
        }
    }

    @Synchronized
    fun detach() {
        slots.clear()
        capacityExceeded = 0
    }

    private fun uniqueName(base: String, addonName: String, used: Set<String>): String {
        val cleanBase = base.ifBlank { "Other" }
        if (cleanBase !in used) return cleanBase
        val source = addonName.ifBlank { "Addon" }
        var candidate = "$cleanBase · $source"
        var suffix = 2
        while (candidate in used) {
            candidate = "$cleanBase · $source ($suffix)"
            suffix++
        }
        return candidate
    }
}
