package com.multistremio

import android.content.Context

/**
 * Cross-screen index for the main settings search. Each result routes to its owning screen and
 * seeds that screen's own search field with the same query so the user lands on the real control.
 */
internal object SettingsSearchIndex {
    fun items(ctx: Context): List<SettingItem> {
        val results = mutableListOf<SettingItem>()

        fun add(
            title: String,
            section: String,
            query: String,
            keywords: List<String> = emptyList(),
            seedQuery: Boolean = true,
            navigate: () -> Unit,
        ) {
            results += SettingItem(
                title = title,
                summary = breadcrumb(section, title),
                type = SettingItemType.Navigate,
                onClick = {
                    if (seedQuery) SettingsDialogHost.setNextMenuSearch(query)
                    else SettingsDialogHost.consumeNextMenuSearch()
                    navigate()
                },
                searchKeywords = (listOf(title, section, query) + keywords).distinct(),
            )
        }

        val settings = Config.snapshot()
        settings.addons.forEach { addon ->
            add(
                addon.name,
                "Addons · ${if (addon.enabled) "Enabled" else "Disabled"}",
                addon.name,
                listOf("addon", "source", "catalogs", "streams", "metadata", "subtitles"),
                seedQuery = false,
            ) { AddonsSettingsScreen.showAddon(ctx, addon) }
        }
        add("Add addon", "Sources", "add", listOf("manifest", "URL", "paste")) {
            AddonsSettingsScreen.show(ctx)
        }
        add("Addon priority", "Sources", "priority", listOf("reorder", "drag")) {
            AddonsSettingsScreen.show(ctx)
        }
        add("Homepage rows", "Sources", "", listOf("catalog", "visibility", "rename", "row title")) {
            CatalogRowsSettingsScreen.show(ctx)
        }
        add("Rename homepage row", "Sources · Homepage rows", "", listOf("custom title", "row name")) {
            CatalogRowsSettingsScreen.show(ctx)
        }

        HomeRows.resolve(settings, ExternalHomeRowsCache.cachedOrRefresh()).forEach { row ->
            val title = HomeRows.homeTitle(row, settings)
            add(title, "Homepage row · ${row.sourceLabel}", title,
                listOf("homepage", "row", "rename", "visibility")) {
                CatalogRowsSettingsScreen.show(ctx)
            }
        }
        add("Show addon name in row titles", "Behavior · Homepage", "row title", listOf("prefix", "suffix")) {
            GeneralSettingsScreen.show(ctx)
        }

        val filters = listOf(
            Triple("Quick filter preset", "preset", listOf("balanced", "best quality", "data saver")),
            Triple("Allowed resolutions", "resolution", listOf("4K", "2160p", "1440p", "1080p", "720p")),
            Triple("Hide CAM / telesync", "CAM", listOf("cinema recording", "telesync")),
            Triple("Hide magnet links", "magnet", listOf("torrent")),
            Triple("Hide uncached debrid links", "uncached", listOf("debrid", "premium")),
            Triple("HEVC / x265", "HEVC", listOf("codec", "x265")),
            Triple("HDR / Dolby Vision", "HDR", listOf("Dolby Vision", "DV")),
            Triple("Minimum file size", "min", listOf("MB")),
            Triple("Maximum file size", "max", listOf("GB")),
            Triple("Minimum seeders", "seeders", listOf("torrent")),
            Triple("Exclude keywords", "exclude", listOf("block", "keywords", "regex")),
            Triple("Require keywords", "require", listOf("include", "keywords", "regex")),
            Triple("Required languages", "language", listOf("audio language", "Bengali", "English")),
            Triple("Strict language", "strict language", listOf("unknown language")),
            Triple("Maximum results per addon", "per addon", listOf("limit")),
            Triple("Maximum results total", "maximum", listOf("limit")),
        )
        filters.forEach { (title, query, keywords) ->
            add(title, "Filters", query, keywords) { FiltersSettingsScreen.show(ctx) }
        }

        val sorting = listOf(
            Triple("Sort order", "sort", listOf("quality", "seeders", "largest", "smallest")),
            Triple("Cached links first", "cached", listOf("debrid", "premium")),
            Triple("Prefer keywords", "keywords", listOf("priority", "regex")),
            Triple("Prefer languages", "language", listOf("priority", "Bangla", "English")),
            Triple("Prefer video codecs", "codec", listOf("HEVC", "AV1", "AVC")),
            Triple("Prefer audio formats", "audio", listOf("Atmos", "TrueHD", "DTS-HD", "DD+", "AAC")),
            Triple("Use MultiStremio's order instead of CloudStream's quality sorting", "CloudStream quality", listOf("resolution", "override")),
        )
        sorting.forEach { (title, query, keywords) ->
            add(title, "Sorting", query, keywords) { SortingSettingsScreen.show(ctx) }
        }

        add("Request timeout", "Behavior · 5–180 seconds", "timeout", listOf("Fast 10s", "Normal 30s", "Patient 60s")) {
            GeneralSettingsScreen.show(ctx)
        }
        add("Link design", "Streams · Simple / Detailed / Custom", "link name", listOf("formatter", "labels", "custom layout", "simple", "detailed", "custom design", "detailed default names")) {
            GeneralSettingsScreen.showLinkNameDesign(ctx)
        }
        add("Profiles", "Streams · Active profile", "profile", listOf("home", "mobile data", "switch settings", "save snapshot")) {
            ProfileSettingsScreen.show(ctx)
        }
        add("Fetch subtitles", "Behavior · Subtitles", "subtitle", listOf("captions", "addons")) {
            GeneralSettingsScreen.showSubtitles(ctx)
        }
        add("Subtitle languages", "Behavior · Subtitles", "language", listOf("captions", "English", "Bengali")) {
            GeneralSettingsScreen.showSubtitles(ctx)
        }
        add("Choose formatter fields", "Custom link design", "fields", listOf("quality", "source", "HDR", "codec")) {
            FormatterSettingsScreen.show(ctx)
        }
        add("Reorder formatter fields", "Custom link design", "reorder", listOf("drag", "field order")) {
            FormatterSettingsScreen.show(ctx)
        }
        add("Formatter separator", "Custom link design", "separator", listOf("dot", "bullet", "dash")) {
            FormatterSettingsScreen.show(ctx)
        }
        add("Show full field names", "Custom link design", "names", listOf("labels")) {
            FormatterSettingsScreen.show(ctx)
        }
        add("Preview stream names", "Custom link design", "preview", listOf("example")) {
            FormatterSettingsScreen.show(ctx)
        }
        add("Backup and restore", "Data", "backup", listOf("copy", "export", "import", "restore")) {
            BackupSettingsScreen.show(ctx)
        }
        add("Diagnostics", "Data", "diagnostics", listOf("debug", "requests", "performance")) {
            DiagnosticsSettingsScreen.show(ctx)
        }
        add("Danger zone", "Data · Destructive actions", "danger", listOf("remove all", "factory reset", "delete")) {
            DangerZoneSettingsScreen.show(ctx)
        }
        return results
    }

    private fun breadcrumb(section: String, title: String): String = when {
        section == "Filters" -> "Streams › Filters › $title"
        section == "Sorting" -> "Streams › Sorting › $title"
        section.startsWith("Custom link design") -> "Streams › Link names › $title"
        section.startsWith("Streams") -> "Streams › $title"
        section.startsWith("Homepage row") || section == "Sources" -> "Sources › ${if (section.startsWith("Homepage row")) "Homepage rows › " else ""}$title"
        section.startsWith("Behavior") -> "Behavior › $title"
        section == "App" -> "Data › $title"
        else -> "$section › $title"
    }
}
