package com.multistremio

import android.content.Context

internal object SortingSettingsScreen {
    fun show(ctx: Context) = sortMenu(ctx)

    private fun sortMenu(ctx: Context) {
        val s = Config.settings.sort
        val preset = SortPreset.from(s.preset)
        val again = { sortMenu(ctx) }
        val items = listOf(
            SettingItem("Sort order", preset.label, SettingItemType.Value,
                onClick = { choose(ctx, "Sort order", SortPreset.values().map { it.label }, preset.ordinal, onCancel = again) {
                    updateAndReopen({ again() }) { s.preset = SortPreset.values()[it].name }
                } }, searchKeywords = listOf("sort", "order", "quality", "seeders", "size")),
            SettingItem("Cached debrid links first", onOff(s.cachedFirst), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { s.cachedFirst = !s.cachedFirst } },
                searchKeywords = listOf("cached", "debrid", "premium")),
            SettingItem("Prefer keywords", csv(s.preferKeywords), SettingItemType.Value,
                onClick = {
                    keywordList(ctx, "Prefer keywords",
                        "Streams containing any of these go first. Comma separated. Wrap regular expressions in slashes, e.g. /x26[45]/.",
                        s.preferKeywords.joinToString(", "), onCancel = again) { values ->
                        updateAndReopen({ again() }) { s.preferKeywords = values }
                    }
                }, searchKeywords = listOf("keywords", "priority", "regex")),
            SettingItem("Prefer languages", csv(s.preferLanguages), SettingItemType.Value,
                onClick = {
                    prompt(ctx, "Prefer languages", "e.g. bn, en, hi. Streams matching an earlier language go first.",
                        s.preferLanguages.joinToString(", "), false, onCancel = again) {
                        updateAndReopen({ again() }) { s.preferLanguages = parseCsv(it) }
                    }
                }, searchKeywords = listOf("language", "priority", "Bangla")),
            SettingItem("Prefer video codecs", csv(s.preferCodecs), SettingItemType.Value,
                onClick = {
                    prompt(ctx, "Prefer video codecs", "Enter a priority order, comma separated. Example: HEVC, AV1, AVC. Unlisted and unknown codecs go after listed codecs. This does not filter streams.",
                        s.preferCodecs.joinToString(", "), false, onCancel = again) {
                        updateAndReopen({ again() }) { s.preferCodecs = parseCsv(it) }
                    }
                }, searchKeywords = listOf("video codec", "HEVC", "AV1", "AVC")),
            SettingItem("Prefer audio formats", csv(s.preferAudio), SettingItemType.Value,
                onClick = {
                    prompt(ctx, "Prefer audio formats", "Enter a priority order, comma separated. Example: Atmos, TrueHD, DTS-HD, DD+, AAC. Unlisted and unknown formats go after listed formats.",
                        s.preferAudio.joinToString(", "), false, onCancel = again) {
                        updateAndReopen({ again() }) { s.preferAudio = parseCsv(it) }
                    }
                }, searchKeywords = listOf("audio", "Atmos", "TrueHD", "DTS", "AAC")),
            SettingItem("Use my order across resolutions", onOff(s.overrideAppQuality), SettingItemType.Switch,
                onClick = {
                    confirm(ctx, "Use my order across resolutions?",
                        "CloudStream normally ranks links by its own quality profile first and only keeps this plugin's " +
                            "order between links of equal quality. Turning this ON makes every link report " +
                            "'unknown quality' so the order chosen here is shown exactly. Resolution still appears " +
                            "in each link's name. Currently ${onOff(s.overrideAppQuality)}; switch it?",
                        onCancel = again) {
                        updateAndReopen({ again() }) { s.overrideAppQuality = !s.overrideAppQuality }
                    }
                }, searchKeywords = listOf("CloudStream quality", "ranking", "sort override", "resolution")),
        )
        settingMenu(ctx, "Stream sorting", items, { MainMenuSettingsScreen.show(ctx) },
            "Choose how available streams are ranked. The first item in each preference list has the highest priority; leave a list empty to disable that preference.")
    }

}
