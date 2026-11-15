package com.multistremio

import android.content.Context

internal object SortingSettingsScreen {
    fun show(ctx: Context) = sortMenu(ctx)

    private fun sortMenu(ctx: Context) {
        val s = Config.settings.sort
        val preset = SortPreset.from(s.preset)
        val again = { sortMenu(ctx) }
        val items = listOf(
            SettingItem(
                "Sort order",
                preset.label,
                SettingItemType.Value,
                onClick = { choose(ctx, "Sort order", SortPreset.values().map { it.label }, preset.ordinal, onCancel = again) {
                    updateAndReopen({ again() }) { s.preset = SortPreset.values()[it].name }
                } },
                searchKeywords = listOf("sort", "order", "quality", "seeders", "size"),
                valueLabel = preset.label.substringBefore(" ("),
            ),
            SettingItem(
                "Cached debrid links first",
                "Move cached links ahead of uncached links",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { s.cachedFirst = !s.cachedFirst } },
                searchKeywords = listOf("cached", "debrid", "premium"),
                checked = s.cachedFirst,
            ),
            SettingItem(
                "Prefer keywords",
                csv(s.preferKeywords),
                SettingItemType.Value,
                onClick = {
                    keywordList(ctx, "Prefer keywords",
                        "Streams containing any of these go first. Comma separated. Wrap regular expressions in slashes, e.g. /x26[45]/.",
                        s.preferKeywords.joinToString(", "), onCancel = again) { values ->
                        updateAndReopen({ again() }) { s.preferKeywords = values }
                    }
                },
                searchKeywords = listOf("keywords", "priority", "regex"),
                valueLabel = if (s.preferKeywords.isEmpty()) "Off" else "${s.preferKeywords.size} terms",
            ),
            SettingItem(
                "Prefer languages",
                csv(s.preferLanguages),
                SettingItemType.Value,
                onClick = {
                    stringListPicker(
                        ctx, "Preferred languages",
                        "Earlier languages have higher priority. Choose from the language list or add a regional language tag.",
                        SettingsChoiceLists.languages, s.preferLanguages,
                        onCancel = again, orderSensitive = true,
                    ) { values -> updateAndReopen({ again() }) { s.preferLanguages = values } }
                },
                searchKeywords = listOf("language", "priority", "Bangla", "English"),
                valueLabel = if (s.preferLanguages.isEmpty()) "Off" else "${s.preferLanguages.size} selected",
            ),
            SettingItem(
                "Prefer video codecs",
                csv(s.preferCodecs),
                SettingItemType.Value,
                onClick = {
                    stringListPicker(
                        ctx, "Preferred video codecs",
                        "Choose supported codecs, add a custom value if needed, then arrange their priority. Unlisted codecs follow these.",
                        SettingsChoiceLists.codecs, s.preferCodecs,
                        onCancel = again, orderSensitive = true,
                    ) { values -> updateAndReopen({ again() }) { s.preferCodecs = values } }
                },
                searchKeywords = listOf("video codec", "HEVC", "AV1", "AVC"),
                valueLabel = if (s.preferCodecs.isEmpty()) "Off" else "${s.preferCodecs.size} selected",
            ),
            SettingItem(
                "Prefer audio formats",
                csv(s.preferAudio),
                SettingItemType.Value,
                onClick = {
                    stringListPicker(
                        ctx, "Preferred audio formats",
                        "Choose audio formats, add a custom value if needed, then arrange their priority.",
                        SettingsChoiceLists.audioFormats, s.preferAudio,
                        onCancel = again, orderSensitive = true,
                    ) { values -> updateAndReopen({ again() }) { s.preferAudio = values } }
                },
                searchKeywords = listOf("audio", "Atmos", "TrueHD", "DTS-HD", "DD+", "AAC"),
                valueLabel = if (s.preferAudio.isEmpty()) "Off" else "${s.preferAudio.size} selected",
            ),
            SettingItem(
                "Use MultiStremio's order instead of CloudStream's quality sorting",
                "When enabled, your selected order takes priority across different resolutions",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { s.overrideAppQuality = !s.overrideAppQuality } },
                searchKeywords = listOf("CloudStream quality", "ranking", "sort override", "resolution", "quality"),
                checked = s.overrideAppQuality,
            ),
        )
        settingMenu(
            ctx,
            "Sorting",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "The first item in each preference list has the highest priority. Leave a list empty to disable that preference.",
            forceSearch = true,
        )
    }
}
