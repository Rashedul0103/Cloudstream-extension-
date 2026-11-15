package com.multistremio

import android.content.Context

internal object FiltersSettingsScreen {
    fun show(ctx: Context) = filtersMenu(ctx)

    private val RES_OPTIONS = listOf(
        2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p",
        576 to "576p", 480 to "480p", 360 to "360p", 240 to "240p", 0 to "Unknown",
    )

    private fun presetLabel(value: String): String = when (value) {
        "BEST_QUALITY" -> "Best quality"
        "DATA_SAVER" -> "Data saver"
        "CUSTOM" -> "Custom"
        else -> "Balanced"
    }

    private fun applyPreset(index: Int) {
        Config.update {
            when (index) {
                1 -> {
                    filter = FilterSettings(
                        allowedResolutions = RES_OPTIONS.map { it.first }.toMutableList(),
                        hideCam = true,
                    )
                    sort = SortSettings(preset = SortPreset.BEST_QUALITY.name)
                    filterPreset = "BEST_QUALITY"
                }
                2 -> {
                    filter = FilterSettings(
                        allowedResolutions = mutableListOf(720, 576, 480, 360, 240),
                        hideCam = true,
                    )
                    sort = SortSettings(preset = SortPreset.SMALLEST.name)
                    filterPreset = "DATA_SAVER"
                }
                else -> {
                    filter = FilterSettings()
                    sort = SortSettings()
                    filterPreset = "BALANCED"
                }
            }
        }
    }

    private fun filtersMenu(ctx: Context) {
        val f = Config.settings.filter
        val again = { filtersMenu(ctx) }
        val items = listOf(
            SettingItem(
                "Quick preset",
                "Applies a complete filter and sorting profile",
                SettingItemType.Value,
                onClick = {
                    val choices = listOf("Balanced · current defaults", "Best quality · all resolutions", "Data saver · up to 720p")
                    val current = when (Config.settings.filterPreset) {
                        "BEST_QUALITY" -> 1
                        "DATA_SAVER" -> 2
                        else -> 0
                    }
                    choose(ctx, "Filter preset", choices, current, onCancel = again) {
                        applyPreset(it)
                        again()
                    }
                },
                searchKeywords = listOf("preset", "balanced", "best quality", "data saver"),
                valueLabel = presetLabel(Config.settings.filterPreset),
                section = "Quick presets",
            ),
            SettingItem(
                "Allowed resolutions",
                RES_OPTIONS.filter { it.first in f.allowedResolutions }.joinToString(", ") { it.second }.ifBlank { "Any resolution" },
                SettingItemType.Value,
                onClick = {
                    multi(ctx, "Allowed resolutions", RES_OPTIONS.map { it.second },
                        RES_OPTIONS.map { it.first in f.allowedResolutions },
                        description = "Select the resolutions to keep. Leave every box unchecked to allow any resolution.",
                        onCancel = again) { selected ->
                        updateAndReopen({ again() }) {
                            f.allowedResolutions = RES_OPTIONS.filterIndexed { index, _ -> selected[index] }
                                .map { it.first }.toMutableList()
                        }
                    }
                }, searchKeywords = listOf("resolution", "4K", "2160p", "1440p", "1080p", "720p"),
                valueLabel = if (f.allowedResolutions.isEmpty()) "Any" else "${f.allowedResolutions.size} selected",
                section = "Quality",
            ),
            SettingItem(
                "Hide CAM / telesync",
                "Remove cinema recordings",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.hideCam = !f.hideCam } },
                searchKeywords = listOf("cam", "telesync", "recorded cinema"),
                checked = f.hideCam,
                section = "Quality",
            ),
            SettingItem(
                "HEVC / x265",
                modeLabel(f.hevcMode),
                SettingItemType.Value,
                onClick = { choose(ctx, "HEVC / x265", listOf("Show any", "Hide HEVC", "Only HEVC"), f.hevcMode, onCancel = again) {
                    updateAndReopen({ again() }) { f.hevcMode = it }
                } },
                searchKeywords = listOf("HEVC", "x265", "video codec"),
                valueLabel = modeLabel(f.hevcMode),
                section = "Quality",
            ),
            SettingItem(
                "HDR / Dolby Vision",
                modeLabel(f.hdrMode),
                SettingItemType.Value,
                onClick = { choose(ctx, "HDR / Dolby Vision", listOf("Show any", "Hide HDR/DV", "Only HDR/DV"), f.hdrMode, onCancel = again) {
                    updateAndReopen({ again() }) { f.hdrMode = it }
                } },
                searchKeywords = listOf("HDR", "Dolby Vision", "DV"),
                valueLabel = modeLabel(f.hdrMode),
                section = "Quality",
            ),
            SettingItem(
                "Hide magnet links",
                "Remove torrent magnet links",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.hideMagnets = !f.hideMagnets } },
                searchKeywords = listOf("magnet", "torrent"),
                checked = f.hideMagnets,
                section = "Sources",
            ),
            SettingItem(
                "Hide uncached debrid links",
                "Keep only cached debrid links",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.hideUncachedDebrid = !f.hideUncachedDebrid } },
                searchKeywords = listOf("uncached", "debrid", "premium"),
                checked = f.hideUncachedDebrid,
                section = "Sources",
            ),
            SettingItem(
                "Minimum file size",
                limit(f.minSizeMb, "MB"),
                SettingItemType.Value,
                onClick = { number(ctx, "Min size (MB, 0 = off)", f.minSizeMb, onCancel = again) {
                    updateAndReopen({ again() }) { f.minSizeMb = it }
                } }, searchKeywords = listOf("minimum", "min", "size", "MB"),
                valueLabel = limit(f.minSizeMb, "MB"),
                section = "Size & seeders",
            ),
            SettingItem(
                "Maximum file size",
                limit(f.maxSizeGb, "GB"),
                SettingItemType.Value,
                onClick = { number(ctx, "Max size (GB, 0 = off)", f.maxSizeGb, onCancel = again) {
                    updateAndReopen({ again() }) { f.maxSizeGb = it }
                } }, searchKeywords = listOf("maximum", "max", "size", "GB"),
                valueLabel = limit(f.maxSizeGb, "GB"),
                section = "Size & seeders",
            ),
            SettingItem(
                "Minimum seeders",
                limit(f.minSeeders, ""),
                SettingItemType.Value,
                onClick = { number(ctx, "Min seeders (0 = off). Streams without seeder info are kept.", f.minSeeders, onCancel = again) {
                    updateAndReopen({ again() }) { f.minSeeders = it }
                } }, searchKeywords = listOf("seeders", "torrent"),
                valueLabel = limit(f.minSeeders, ""),
                section = "Size & seeders",
            ),
            SettingItem(
                "Exclude keywords",
                csv(f.excludeKeywords),
                SettingItemType.Value,
                onClick = {
                    keywordList(ctx, "Exclude keywords",
                        "Comma separated, case-insensitive. Wrap in slashes for a regex, e.g. /x26[45]/. Invalid regex patterns are rejected.",
                        f.excludeKeywords.joinToString(", "), onCancel = again) { values ->
                        updateAndReopen({ again() }) { f.excludeKeywords = values }
                    }
                }, searchKeywords = listOf("exclude", "block", "keywords", "regex"),
                valueLabel = if (f.excludeKeywords.isEmpty()) "Off" else "${f.excludeKeywords.size} terms",
                section = "Keywords & language",
            ),
            SettingItem(
                "Require keywords (any)",
                csv(f.requireKeywords),
                SettingItemType.Value,
                onClick = {
                    keywordList(ctx, "Require keywords",
                        "A stream must contain at least one of these. Leave empty to disable. Wrap regex patterns in slashes, e.g. /x26[45]/.",
                        f.requireKeywords.joinToString(", "), onCancel = again) { values ->
                        updateAndReopen({ again() }) { f.requireKeywords = values }
                    }
                }, searchKeywords = listOf("require", "include", "keywords", "regex"),
                valueLabel = if (f.requireKeywords.isEmpty()) "Off" else "${f.requireKeywords.size} terms",
                section = "Keywords & language",
            ),
            SettingItem(
                "Required languages",
                csv(f.requireLanguages),
                SettingItemType.Value,
                onClick = {
                    stringListPicker(
                        ctx, "Required languages",
                        "A stream must match one selected language. Use Add custom for a regional tag or another language code.",
                        SettingsChoiceLists.languages, f.requireLanguages, onCancel = again,
                    ) { values -> updateAndReopen({ again() }) { f.requireLanguages = values } }
                },
                searchKeywords = listOf("language", "audio language", "Bengali", "English"),
                valueLabel = if (f.requireLanguages.isEmpty()) "Any" else "${f.requireLanguages.size} selected",
                section = "Keywords & language",
            ),
            SettingItem(
                "Strict language",
                "Drop streams with unknown language",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.strictLanguage = !f.strictLanguage } },
                searchKeywords = listOf("strict", "unknown language"),
                checked = f.strictLanguage,
                section = "Keywords & language",
            ),
            SettingItem(
                "Maximum results per addon",
                limit(f.maxPerAddon, ""),
                SettingItemType.Value,
                onClick = { number(ctx, "Max results per addon (0 = off)", f.maxPerAddon, onCancel = again) {
                    updateAndReopen({ again() }) { f.maxPerAddon = it }
                } }, searchKeywords = listOf("limit", "per addon", "maximum"),
                valueLabel = limit(f.maxPerAddon, ""),
                section = "Limits",
            ),
            SettingItem(
                "Maximum results total",
                limit(f.maxTotal, ""),
                SettingItemType.Value,
                onClick = { number(ctx, "Max results total (0 = off)", f.maxTotal, onCancel = again) {
                    updateAndReopen({ again() }) { f.maxTotal = it }
                } }, searchKeywords = listOf("limit", "total", "maximum"),
                valueLabel = limit(f.maxTotal, ""),
                section = "Limits",
            ),
        )
        settingMenu(ctx, "Filters", items, { MainMenuSettingsScreen.show(ctx) },
            "Quick presets first, followed by grouped controls. Any manual edit changes the preset label to Custom.",
            forceSearch = true)
    }
}
