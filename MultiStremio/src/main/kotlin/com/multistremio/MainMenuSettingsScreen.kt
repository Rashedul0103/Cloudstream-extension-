package com.multistremio

import android.content.Context

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

        val items = listOf(
            SettingItem(
                title = "${enabledAddons} addons · ${homeRowCount} rows visible",
                summary = "Filters currently allow $filterSummary",
                type = SettingItemType.Value,
                valueLabel = "Status",
                section = "Overview",
                searchKeywords = listOf("status", "addons", "rows visible", "filters"),
            ),
            SettingItem(
                title = "Addons",
                summary = "${settings.addons.size} installed · manage availability, capabilities and priority",
                onClick = { AddonsSettingsScreen.show(ctx) },
                section = "Sources",
                searchKeywords = listOf("addons", "sources", "priority", "manifests"),
            ),
            SettingItem(
                title = "Homepage rows",
                summary = "Choose which rows appear and arrange their order",
                onClick = { CatalogRowsSettingsScreen.show(ctx) },
                section = "Sources",
                searchKeywords = listOf("catalog", "homepage", "rows", "visibility", "order", "extensions"),
            ),
            SettingItem(
                title = "Filters",
                summary = "Choose which streams are allowed",
                onClick = { FiltersSettingsScreen.show(ctx) },
                section = "Streams",
                searchKeywords = listOf("filters", "resolution", "size", "language", "keywords", "HDR", "CAM"),
            ),
            SettingItem(
                title = "Sorting",
                summary = "Control quality, language, codec and audio ranking",
                onClick = { SortingSettingsScreen.show(ctx) },
                section = "Streams",
                searchKeywords = listOf("sorting", "ranking", "quality", "seeders", "codec", "audio"),
            ),
            SettingItem(
                title = "Link design",
                summary = "Choose Simple, Detailed or Custom design",
                onClick = { GeneralSettingsScreen.showLinkNameDesign(ctx) },
                section = "Streams",
                searchKeywords = listOf("formatter", "format", "labels", "link name", "layout", "design", "simple", "detailed", "custom design", "detailed default names"),
            ),
            SettingItem(
                title = "General",
                summary = "Request timeout, subtitles and homepage display options",
                onClick = { GeneralSettingsScreen.show(ctx) },
                section = "App",
                searchKeywords = listOf("general", "timeout", "labels", "display", "subtitle", "language"),
            ),
            SettingItem(
                title = "Backup and restore",
                summary = "Export or restore your configuration",
                onClick = { BackupSettingsScreen.show(ctx) },
                section = "App",
                searchKeywords = listOf("backup", "restore", "export", "import"),
            ),
            SettingItem(
                title = "Diagnostics",
                summary = "Inspect recent source responses and filtered-link counts",
                onClick = { DiagnosticsSettingsScreen.show(ctx) },
                section = "App",
                searchKeywords = listOf("diagnostics", "debug", "requests", "performance"),
            ),
            SettingItem(
                title = "Danger zone",
                summary = "Destructive actions, separate from backup and restore",
                onClick = { DangerZoneSettingsScreen.show(ctx) },
                section = "Danger zone",
                danger = true,
                searchKeywords = listOf("remove all", "factory reset", "delete", "danger"),
            ),
        )

        settingMenu(
            ctx,
            "MultiStremio",
            items,
            null,
            "Search reaches settings across sections. Sources · Streams · App.",
            forceSearch = true,
            globalSearchItems = SettingsSearchIndex.items(ctx),
            showRefreshNotice = true,
        )
    }
}
