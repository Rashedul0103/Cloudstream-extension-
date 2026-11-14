package com.multistremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import java.security.MessageDigest

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

        for (provider in providers) {
            if (provider === exclude || provider is MultiStremio) continue
            val source = provider.sourcePlugin?.takeIf { it.isNotBlank() } ?: continue
            if (!provider.hasMainPage) continue

            val mainRows = runCatching { provider.mainPage }.getOrNull() ?: continue
            val providerClass = provider.javaClass.name
            for (row in mainRows) {
                val dataHash = sha256(row.data)
                val key = externalHomeRowKey(
                    source, provider.name, providerClass, row.name, dataHash, row.horizontalImages
                )
                rows += InstalledExternalHomeRow(
                    ExternalRowConfig(
                        key = key,
                        providerName = provider.name,
                        providerClass = providerClass,
                        sourcePlugin = source,
                        rowName = row.name,
                        dataHash = dataHash,
                        horizontalImages = row.horizontalImages,
                    ),
                    provider,
                    row,
                )
            }
        }
        return rows.distinctBy { it.config.key }
    }

    /** Resolve the saved reference against the providers that are loaded right now. */
    fun resolve(config: ExternalRowConfig, exclude: MainAPI? = null): InstalledExternalHomeRow? =
        installedRows(exclude).firstOrNull { it.config.key == config.key }

    private fun externalHomeRowKey(
        sourcePlugin: String,
        providerName: String,
        providerClass: String,
        rowName: String,
        dataHash: String,
        horizontalImages: Boolean,
    ): String = "external:" + sha256(
        listOf(sourcePlugin, providerName, providerClass, rowName, dataHash, horizontalImages.toString())
            .joinToString("\u0000")
    )

    private fun sha256(value: String): String {
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
