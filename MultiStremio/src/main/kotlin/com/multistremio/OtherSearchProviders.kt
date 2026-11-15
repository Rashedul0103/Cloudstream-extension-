package com.multistremio

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
     * Provider instances and their names must exist before CloudStream creates SearchViewModel.
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

    /**
     * CloudStream uses supportedTypes to decide which provider to query for selected type chips;
     * it does not filter individual returned cards. Keep cross-media Other rows eligible under
     * the default Movie/Series chips while each SearchResponse retains its catalog's real type.
     */
    fun searchProviderTypes(type: String): Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries,
        StremioMediaTypes.toCloudStream(type),
    )
}

/**
 * One fixed provider per searchable homepage catalog. CloudStream snapshots both provider names
 * and repositories inside its activity-scoped SearchViewModel. The name therefore stays immutable
 * for this provider's lifetime; settings refresh only changes supportedTypes and the active row.
 */
private class OtherSearchMainAPI(
    private val catalogKey: String,
    displayName: String,
) : MainAPI() {
    override var name: String = displayName
    override var mainUrl: String =
        "https://other-search-${Hashing.sha256(catalogKey).take(24)}.multistremio.invalid"
    override var lang: String = "en"
    override val hasMainPage: Boolean = false
    override val hasQuickSearch: Boolean = false
    override var supportedTypes: Set<TvType> = emptySet()

    private val currentRow = AtomicReference<OtherSearchRow?>(null)
    private val delegate = MultiStremio()

    /** Activation/deactivation is safe after SearchViewModel creation because the API object and
     * its registered name are unchanged. The host reads supportedTypes from the live API object.
     */
    fun configure(row: OtherSearchRow?) {
        currentRow.set(row)
        supportedTypes = row?.let { OtherSearchRows.searchProviderTypes(it.type) } ?: emptySet()
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
    private val providers = linkedMapOf<String, OtherSearchMainAPI>()

    /**
     * Register every current searchable homepage catalog once at plugin load. The provider's
     * display name is fixed here because CloudStream copies APIRepository.name on first creation.
     */
    @Synchronized
    fun attach(settings: Settings, register: (MainAPI) -> Unit) {
        if (providers.isNotEmpty()) return

        val occupiedNames = APIHolder.apis.withLock {
            APIHolder.apis.mapTo(linkedSetOf()) { it.name }
        }
        val candidates = OtherSearchRows.candidates(settings)
        for (row in candidates) {
            val displayName = uniqueName(row.title, row.addonName, occupiedNames)
            occupiedNames.add(displayName)
            val provider = OtherSearchMainAPI(row.key, displayName)
            providers[row.key] = provider
            register(provider)
        }
        refresh()
    }

    /** Called after settings changes. It never adds, removes or renames providers at runtime. */
    @Synchronized
    fun refresh() {
        if (providers.isEmpty()) return
        val settings = Config.snapshot()
        val activeRows = OtherSearchRows.resolve(settings).associateBy { it.key }
        providers.forEach { (key, provider) ->
            provider.configure(activeRows[key])
        }
    }

    @Synchronized
    fun detach() {
        providers.clear()
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
