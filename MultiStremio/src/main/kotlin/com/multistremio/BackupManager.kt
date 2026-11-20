package com.multistremio

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.fasterxml.jackson.core.type.TypeReference

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
            snapshot.customCatalogNames.clear()
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
    fun parse(text: String): SettingsBackup? =
        runCatching { parseUnchecked(text.removePrefix("\uFEFF")) }.getOrNull()

    private fun parseUnchecked(text: String): SettingsBackup? {
        if (text.isBlank()) return null
        val root = Json.mapper.readTree(text) ?: return null
        val isEnvelope = root.get("format")?.asText() == FORMAT
        if (isEnvelope) {
            val envelope = Json.parse<SettingsBackup>(text) ?: return null
            if (envelope.version !in 1..CURRENT_VERSION || envelope.settings == null) return null

            // Determine sensitivity before normalization so malformed/duplicate source entries
            // cannot be erased before the privacy warning is selected.
            val rawSettings = envelope.settings
            val containsSourceData = envelope.includesAddonUrls ||
                rawSettings.addons.isNotEmpty() ||
                rawSettings.externalRows.isNotEmpty() ||
                rawSettings.catalogOrder.isNotEmpty()
            return envelope.copy(
                includesAddonUrls = containsSourceData,
                settings = rawSettings.sanitized(),
            )
        }

        // Older releases stored the Settings object directly. Require its defining root
        // field so arbitrary JSON objects cannot be mistaken for a valid backup.
        if (!root.isObject || !root.has("addons")) return null
        val legacy = Json.parse<Settings>(text) ?: return null
        return SettingsBackup(
            format = FORMAT,
            version = 0,
            includesAddonUrls = true,
            settings = legacy.sanitized(),
        )
    }
}

data class AutomaticBackupEntry(
    val createdAt: Long,
    val reason: String,
    val payload: String,
)

object AutomaticBackupHistory {
    const val MAX_BACKUPS = 5
    fun append(current: List<AutomaticBackupEntry>, entry: AutomaticBackupEntry): List<AutomaticBackupEntry> =
        (current + entry).takeLast(MAX_BACKUPS)
}

/** Private on-device rolling snapshots, created immediately before high-impact settings changes. */
class AutomaticBackupStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("multistremio_auto_backups", MODE_PRIVATE)

    fun list(): List<AutomaticBackupEntry> {
        val json = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            Json.mapper.readValue(json, object : TypeReference<List<AutomaticBackupEntry>>() {})
        }.getOrDefault(emptyList())
    }

    fun create(reason: String, settings: Settings): AutomaticBackupEntry? = runCatching {
        val entry = AutomaticBackupEntry(
            createdAt = System.currentTimeMillis(),
            reason = reason.take(80),
            payload = SettingsBackupManager.export(settings, includeAddonUrls = true),
        )
        preferences.edit().putString(KEY, Json.toJson(AutomaticBackupHistory.append(list(), entry))).apply()
        entry
    }.getOrNull()

    companion object {
        private const val KEY = "snapshots"
    }
}
