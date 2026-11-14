package com.multistremio

/**
 * Resolved homepage row used by playback and by the Catalog rows settings screen.
 * This is deliberately free of Android views so visibility rules remain easy to test.
 */
data class HomeRowEntry(
    val key: String,
    val sourceLabel: String,
    val name: String,
    val status: String,
    val rowEnabled: Boolean,
    val visibleOnHome: Boolean,
    val addon: AddonConfig? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val external: ExternalRowConfig? = null,
    val installedExternal: InstalledExternalHomeRow? = null,
)

object HomeRows {
    /**
     * A home catalog must have an identity, must not require user input such as search text,
     * and must be backed by the catalog resource. This matches the catalog picker rules.
     */
    fun catalogCandidates(manifest: Manifest?): List<ManifestCatalog> {
        if (manifest == null) return emptyList()
        return manifest.catalogs.orEmpty().filter { catalog ->
            val type = catalog.type
            type != null && !catalog.id.isNullOrBlank() &&
                catalog.isHomeCatalog() && manifest.supports("catalog", type, null)
        }
    }

    /**
     * Resolve every configurable row, including hidden and unavailable rows needed by settings.
     *
     * Passing null for [installedExternalRows] means availability has not been checked yet. In
     * that case saved external rows are provisionally treated as available; callers that need
     * definitive status should pass the cached provider snapshot.
     */
    fun resolve(
        settings: Settings,
        installedExternalRows: List<InstalledExternalHomeRow>? = null,
        manifestOf: (AddonConfig) -> Manifest? = { Json.parse<Manifest>(it.manifest) },
    ): List<HomeRowEntry> {
        val rows = mutableListOf<HomeRowEntry>()

        for (addon in settings.addons) {
            val manifest = manifestOf(addon) ?: continue
            for (catalog in manifest.catalogs.orEmpty()) {
                val type = catalog.type ?: continue
                val id = catalog.id?.takeIf { it.isNotBlank() } ?: continue
                if (!catalog.isHomeCatalog()) continue

                val resourceAvailable = manifest.supports("catalog", type, null)
                val rowEnabled = "$type/$id" !in addon.hiddenCatalogs
                val visible = addon.enabled && addon.useCatalogs && resourceAvailable && rowEnabled
                val status = when {
                    !addon.enabled -> "ADDON OFF"
                    !addon.useCatalogs -> "CATALOGS OFF"
                    !resourceAvailable -> "RESOURCE MISSING"
                    rowEnabled -> "ON"
                    else -> "HIDDEN"
                }

                rows += HomeRowEntry(
                    key = catalogOrderKey(addon.id, type, id),
                    sourceLabel = addon.name,
                    name = catalog.name?.takeIf { it.isNotBlank() } ?: id,
                    status = status,
                    rowEnabled = rowEnabled,
                    visibleOnHome = visible,
                    addon = addon,
                    type = type,
                    catalogId = id,
                )
            }
        }

        for (external in settings.externalRows) {
            val installed = installedExternalRows?.let {
                ExternalHomeRows.resolve(external, it)
            }
            val available = installedExternalRows == null || installed != null
            val status = when {
                !available -> "EXTENSION MISSING"
                external.enabled -> "ON"
                else -> "HIDDEN"
            }
            rows += HomeRowEntry(
                key = external.key,
                sourceLabel = external.providerName,
                name = external.rowName,
                status = status,
                rowEnabled = external.enabled,
                visibleOnHome = available && external.enabled,
                external = external,
                installedExternal = installed,
            )
        }

        val positions = settings.catalogOrder.withIndex().associate { it.value to it.index }
        return rows.distinctBy { it.key }.withIndex()
            .sortedWith(
                compareBy<IndexedValue<HomeRowEntry>> { positions[it.value.key] ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** Number of rows expected to be visible using the currently known source configuration. */
    fun visibleCount(settings: Settings): Int =
        resolve(settings).count { it.visibleOnHome }
}
