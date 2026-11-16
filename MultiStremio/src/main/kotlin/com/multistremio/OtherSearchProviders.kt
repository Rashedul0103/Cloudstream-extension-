package com.multistremio

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.utils.ExtractorLink
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
    fun searchProviderTypeNames(type: String): Set<String> = setOf(
        "movie",
        "series",
        StremioMediaTypes.canonicalType(type),
    )

    fun searchProviderTypes(type: String): Set<TvType> =
        searchProviderTypeNames(type).mapTo(LinkedHashSet()) { StremioMediaTypes.toCloudStream(it) }
}

/**
 * One fixed provider per searchable homepage catalog. CloudStream snapshots both provider names
 * and repositories inside its activity-scoped SearchViewModel. The name therefore stays immutable
 * for this provider's lifetime; settings refresh only activates or deactivates its row.
 */
private class OtherSearchMainAPI(
    private val catalogKey: String,
    displayName: String,
    type: String,
) : MainAPI() {
    override var name: String = displayName
    override var mainUrl: String =
        "https://other-search-${Hashing.sha256(catalogKey).take(24)}.multistremio.invalid"
    override var lang: String = "en"
    // CloudStream's default saved search-provider list includes only providers with hasMainPage.
    // An empty mainPage keeps this search-only provider out of the actual homepage while allowing
    // it to be selected automatically by the default search-provider discovery logic.
    override val hasMainPage: Boolean = true
    override val mainPage: List<MainPageData> = emptyList()
    override val hasQuickSearch: Boolean = false
    // Keep the slot discoverable in CloudStream's default search-provider list even while Other
    // is off. The inactive search returns null, so it creates no empty result section.
    override val supportedTypes: Set<TvType> = OtherSearchRows.searchProviderTypes(type)

    private val currentRow = AtomicReference<OtherSearchRow?>(null)
    private val delegate = MultiStremio()

    /** Activation/deactivation is safe after SearchViewModel creation because the API object and
     * its registered name are unchanged. The host reads supportedTypes from the live API object.
     */
    fun configure(row: OtherSearchRow?) {
        // supportedTypes stays fixed for this provider's lifetime. CloudStream snapshots the
        // default provider-selection list; hiding these types while Other is off would prevent
        // a later toggle from participating automatically.
        currentRow.set(row)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val selected = currentRow.get() ?: return null
        val settings = Config.snapshot()
        val current = OtherSearchRows.resolve(settings).firstOrNull { it.key == selected.key }
            ?: return null
        val addon = settings.addons.firstOrNull {
            it.id == current.addonId && it.enabled && it.useOther
        } ?: return null
        val manifest = Config.manifestOf(addon) ?: return null
        val catalog = manifest.catalogs.orEmpty().firstOrNull {
            it.type == current.type && it.id == current.catalogId
        } ?: return null
        if (!manifest.supports("catalog", current.type, null) || !catalog.isSearchable()) {
            return null
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
    @Volatile private var registrar: ((MainAPI) -> Unit)? = null

    /**
     * Keep the plugin registrar so providers can also be added after the user installs an addon
     * or after a manifest finishes downloading. Settings changes call refresh().
     */
    @Synchronized
    fun attach(register: (MainAPI) -> Unit) {
        registrar = register
        // Read the snapshot after installing the registrar. Config.init() may already have
        // completed a background manifest refresh before the plugin finished attaching.
        reconcile(Config.snapshot())
    }

    /**
     * Reconcile registered slots with the latest manifests. The caller holds this object's lock.
     * Existing provider instances/names are retained because CloudStream may already have cached
     * them. A failed registration is not remembered, so a later refresh can retry it.
     */
    private fun reconcile(settings: Settings): Boolean {
        val register = registrar ?: return false
        val occupiedNames = APIHolder.apis.withLock {
            APIHolder.apis.mapTo(linkedSetOf()) { it.name }
        }
        var registeredAny = false

        for (row in OtherSearchRows.candidates(settings)) {
            if (row.key in providers) continue

            val displayName = uniqueName(row.title, row.addonName, occupiedNames)
            val provider = OtherSearchMainAPI(row.key, displayName, row.type)
            try {
                register(provider)
                providers[row.key] = provider
                occupiedNames.add(displayName)
                registeredAny = true
            } catch (error: Exception) {
                android.util.Log.e(
                    "MultiStremio",
                    "Could not register an Other search provider for ${row.addonName}",
                    error,
                )
            }
        }

        configureActiveRows(settings)
        return registeredAny
    }

    /** Called after settings changes, including addon addition and manifest refresh completion. */
    fun refresh() {
        val registeredAny = synchronized(this) {
            if (registrar == null) false else reconcile(Config.snapshot())
        }

        // Notify CloudStream only after releasing our registry lock. The host event refreshes
        // API repositories held by an already-open SearchViewModel.
        if (registeredAny) {
            try {
                afterPluginsLoadedEvent.invoke(false)
            } catch (error: Exception) {
                android.util.Log.e("MultiStremio", "Could not refresh CloudStream search providers", error)
            }
        }
    }

    private fun configureActiveRows(settings: Settings) {
        val activeRows = OtherSearchRows.resolve(settings).associateBy { it.key }
        providers.forEach { (key, provider) ->
            provider.configure(activeRows[key])
        }
    }

    @Synchronized
    fun detach() {
        providers.clear()
        registrar = null
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
