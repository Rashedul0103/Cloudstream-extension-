package com.multistremio

import android.content.Context
import android.app.AlertDialog

internal object MainMenuSettingsScreen {
    fun show(ctx: Context) = mainMenu(ctx)

    private fun mainMenu(ctx: Context) {
        val settings = Config.snapshot()
        val installedRows = ExternalHomeRowsCache.cachedOrRefresh()
        if (ExternalHomeRowsCache.snapshotIfFresh() == null) {
            ExternalHomeRowsCache.loadAsync {
                if (SettingsDialogHost.isCurrent("menu:MultiStremio")) mainMenu(ctx)
            }
        }
        val homeRowCount = HomeRows.visibleCount(settings, installedExternalRows = installedRows)
        val enabledAddons = settings.addons.count { it.enabled }
        val allowed = settings.filter.allowedResolutions
        val highest = allowed.filter { it > 0 }.maxOrNull()
        val filterSummary = if (allowed.isEmpty() || allowed.size == 9) "Any resolution"
            else highest?.let { "up to ${it}p" } ?: "Unknown resolutions only"
        val healthWarnings = settings.addons.count { addon ->
            AddonHealthStore.get(addon.id)?.label(disabled = !addon.enabled) in setOf("Slow", "Failing")
        }
        val presetSummary = when (settings.filterPreset) {
            "BEST_QUALITY" -> "Best"
            "DATA_SAVER" -> "Saver"
            "CUSTOM" -> "Custom"
            else -> "Balanced"
        }
        val lastLookup = StreamDiagnostics.latest()

        val items = listOf(
            SettingItem(
                title = "$enabledAddons enabled addons · $homeRowCount rows visible",
                summary = buildString {
                    append("Profile: ${settings.activeProfile} · $presetSummary · $filterSummary")
                    if (lastLookup != null) {
                        val hidden = (lastLookup.playableCandidates - lastLookup.keptAfterFilters).coerceAtLeast(0)
                        append(" · Last lookup hides $hidden of ${lastLookup.playableCandidates}")
                    }
                    if (healthWarnings > 0) append(" · ⚠ $healthWarnings addon(s) need attention")
                },
                type = SettingItemType.Value,
                valueLabel = if (healthWarnings > 0) "${healthWarnings} warning(s)" else "$homeRowCount rows",
                onClick = {
                    if (healthWarnings > 0) AddonsSettingsScreen.show(ctx)
                    else DiagnosticsSettingsScreen.show(ctx)
                },
                section = "Overview",
                searchKeywords = listOf("status", "addons", "rows visible", "filters", "profile", "switch profile"),
                icon = "diagnostics",
            ),
            SettingItem(
                title = "Active profile",
                summary = "Switch filters, sorting, link design and subtitle preferences",
                type = SettingItemType.Value,
                valueLabel = settings.activeProfile,
                onClick = { ProfileSettingsScreen.chooseFromHub(ctx) },
                section = "Overview",
                searchKeywords = listOf("profile", "switch profile", settings.activeProfile),
                icon = "profile",
            ),
            SettingItem(
                title = "Addons",
                summary = "${settings.addons.size} installed · manage availability, capabilities and priority",
                onClick = { AddonsSettingsScreen.show(ctx) },
                section = "Sources",
                searchKeywords = listOf("addons", "sources", "priority", "manifests"),
                icon = "addons",
            ),
            SettingItem(
                title = "Homepage rows",
                summary = "Choose which rows appear and arrange their order",
                onClick = { CatalogRowsSettingsScreen.show(ctx) },
                section = "Sources",
                searchKeywords = listOf("catalog", "homepage", "rows", "visibility", "order", "extensions"),
                icon = "rows",
            ),
            SettingItem(
                title = "Filters",
                summary = "Choose which streams are allowed",
                onClick = { FiltersSettingsScreen.show(ctx) },
                section = "Streams",
                searchKeywords = listOf("filters", "resolution", "size", "language", "keywords", "HDR", "CAM"),
                icon = "filter",
            ),
            SettingItem(
                title = "Sorting",
                summary = "Control quality, language, codec and audio ranking",
                onClick = { SortingSettingsScreen.show(ctx) },
                section = "Streams",
                searchKeywords = listOf("sorting", "ranking", "quality", "seeders", "codec", "audio"),
                icon = "sort",
            ),
            SettingItem(
                title = "Link design",
                summary = "Choose Simple, Detailed or Custom design",
                onClick = { FormatterSettingsScreen.show(ctx) },
                section = "Streams",
                searchKeywords = listOf("formatter", "format", "labels", "link name", "layout", "design", "simple", "detailed", "custom design", "detailed default names"),
                icon = "caption",
            ),
            SettingItem(
                title = "Profiles",
                summary = "Save and switch filters, sorting, link design and subtitle preferences",
                onClick = { ProfileSettingsScreen.show(ctx) },
                section = "Streams",
                searchKeywords = listOf("profile", "home", "mobile data", "presets", "switch settings"),
                icon = "profile",
            ),
            SettingItem(
                title = "Behavior",
                summary = "Subtitles, request timeout and homepage titles",
                onClick = { GeneralSettingsScreen.show(ctx) },
                section = "Behavior",
                searchKeywords = listOf("behavior", "general", "timeout", "labels", "display", "subtitle", "language"),
                icon = "bolt",
            ),
            SettingItem(
                title = "Backup and restore",
                summary = "Export or restore your configuration",
                onClick = { BackupSettingsScreen.show(ctx) },
                section = "Data",
                searchKeywords = listOf("backup", "restore", "export", "import"),
                icon = "backup",
            ),
            SettingItem(
                title = "Diagnostics",
                summary = "Inspect recent source responses and filtered-link counts",
                onClick = { DiagnosticsSettingsScreen.show(ctx) },
                section = "Data",
                searchKeywords = listOf("diagnostics", "debug", "requests", "performance"),
                icon = "diagnostics",
            ),
            SettingItem(
                title = "Danger zone",
                summary = "Destructive actions, separate from backup and restore",
                onClick = { DangerZoneSettingsScreen.show(ctx) },
                section = "Data",
                danger = true,
                searchKeywords = listOf("remove all", "factory reset", "delete", "danger"),
                icon = "trash",
            ),
        )

        settingMenu(
            ctx,
            "MultiStremio",
            items,
            null,
            "Search reaches settings across sections. Sources · Streams · Behavior · Data.",
            forceSearch = true,
            globalSearchItems = SettingsSearchIndex.items(ctx),
            showRefreshNotice = true,
            helpAction = {
                AlertDialog.Builder(ctx)
                    .setTitle("MultiStremio settings")
                    .setMessage("Search for a setting, then choose a result to open its page. Changes save as you make them. Use Profiles to switch stream preferences, Backup and restore to move settings, and Diagnostics to inspect the last lookup.")
                    .setPositiveButton("Got it", null)
                    .show()
            },
        )
    }
}
