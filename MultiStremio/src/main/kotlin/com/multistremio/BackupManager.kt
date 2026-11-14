package com.multistremio

/**
 * Portable settings backup envelope.
 *
 * A preferences-only export deliberately excludes addon and imported-extension identities:
 * those can contain private endpoints, access keys or private repository URLs.
 */
data class SettingsBackup(
    val format: String = "multistremio-backup",
    val version: Int = 1,
    val includesAddonUrls: Boolean = false,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: Settings? = null,
)

object SettingsBackupManager {
    private const val FORMAT = "multistremio-backup"
    private const val CURRENT_VERSION = 1

    fun export(settings: Settings, includeAddonUrls: Boolean): String {
        val snapshot = Json.parse<Settings>(Json.toJson(settings))
            ?: throw IllegalStateException("Could not create a settings snapshot")

        if (!includeAddonUrls) {
            snapshot.addons.clear()
            snapshot.externalRows.clear()
            snapshot.catalogOrder.clear()
        }

        return Json.toJson(
            SettingsBackup(
                format = FORMAT,
                version = CURRENT_VERSION,
                includesAddonUrls = includeAddonUrls,
                settings = snapshot,
            ),
        )
    }

    /**
     * Reads current backup envelopes and the raw Settings JSON format used by older releases.
     * The old format always contains addon URLs, so it is treated as a sensitive full backup.
     */
    fun import(text: String): SettingsBackup? {
        val envelope = Json.parse<SettingsBackup>(text)
        if (envelope?.format == FORMAT) {
            if (envelope.version !in 1..CURRENT_VERSION || envelope.settings == null) return null
            return envelope
        }

        val legacy = Json.parse<Settings>(text) ?: return null
        return SettingsBackup(
            format = FORMAT,
            version = 0,
            includesAddonUrls = true,
            settings = legacy,
        )
    }
}
