package com.multistremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData

/** A currently installed provider homepage row and the stable reference used to save it. */
data class InstalledExternalHomeRow(
    val config: ExternalRowConfig,
    val provider: MainAPI,
    val row: MainPageData,
)

object ExternalHomeRows {
    /**
     * Enumerates homepage rows registered by other installed extensions.
     * The row's opaque data is hashed for identity and is not copied into persistent settings.
     */
    fun installedRows(exclude: MainAPI? = null): List<InstalledExternalHomeRow> {
        val providers = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val rows = mutableListOf<InstalledExternalHomeRow>()
        val occurrences = HashMap<String, Int>()

        for (provider in providers) {
            if (provider === exclude || provider is MultiStremio) continue
            val source = provider.sourcePlugin?.takeIf { it.isNotBlank() } ?: continue
            if (!provider.hasMainPage) continue

            val mainRows = runCatching { provider.mainPage }.getOrNull() ?: continue
            val providerClass = provider.javaClass.name
            for (row in mainRows) {
                val dataHash = Hashing.sha256(row.data)
                val identity = ExternalRowIdentity.descriptor(
                    source, providerClass, row.name, row.horizontalImages
                )
                val occurrence = occurrences[identity] ?: 0
                occurrences[identity] = occurrence + 1
                val key = ExternalRowIdentity.key(identity, occurrence)
                rows += InstalledExternalHomeRow(
                    ExternalRowConfig(
                        key = key,
                        providerName = provider.name,
                        providerClass = providerClass,
                        sourcePlugin = source,
                        rowName = row.name,
                        dataHash = dataHash,
                        occurrence = occurrence,
                        horizontalImages = row.horizontalImages,
                    ),
                    provider,
                    row,
                )
            }
        }
        return rows.distinctBy { it.config.key }
    }

    /**
     * Resolve exact new keys first. For older saved rows, fall back to the stable provider/row
     * identity; dataHash is used only to disambiguate duplicate row titles, never as identity.
     */
    fun resolve(config: ExternalRowConfig, exclude: MainAPI? = null): InstalledExternalHomeRow? =
        resolve(config, installedRows(exclude))

    fun resolve(
        config: ExternalRowConfig,
        availableRows: List<InstalledExternalHomeRow>,
    ): InstalledExternalHomeRow? {
        availableRows.firstOrNull { it.config.key == config.key }?.let { return it }

        val matches = availableRows.filter { ExternalRowIdentity.matches(config, it.config) }
        if (matches.isEmpty()) return null
        if (config.dataHash.isNotBlank()) {
            matches.firstOrNull { it.config.dataHash == config.dataHash }?.let { return it }
        }
        // A legacy row with no exact data match can only be safely migrated when unambiguous.
        return matches.singleOrNull()
    }



}
