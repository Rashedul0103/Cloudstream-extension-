package com.multistremio

import android.content.Context

internal object GeneralSettingsScreen {
    fun show(ctx: Context) = generalMenu(ctx)
    fun showSubtitles(ctx: Context) = subtitlesMenu(ctx)

    private fun subtitlesMenu(ctx: Context) {
        val settings = Config.settings
        val again = { subtitlesMenu(ctx) }
        val items = listOf(
            SettingItem("Fetch from subtitle addons", onOff(settings.fetchSubtitles), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { fetchSubtitles = !fetchSubtitles } },
                searchKeywords = listOf("subtitle", "captions", "fetch", "addons")),
            SettingItem("Language filter",
                settings.subtitleLanguages.takeIf { it.isNotEmpty() }?.let { csv(it) } ?: "All languages",
                SettingItemType.Value,
                onClick = {
                    prompt(ctx, "Subtitle languages",
                        "Codes, regional tags or names, e.g. en, eng, en-US, pt-BR, Bengali. Leave empty for all languages.",
                        settings.subtitleLanguages.joinToString(", "), false, onCancel = again) {
                        updateAndReopen({ again() }) { subtitleLanguages = parseCsv(it) }
                    }
                }, searchKeywords = listOf("subtitle", "language", "English", "Bengali")),
        )
        settingMenu(ctx, "Subtitles", items, { MainMenuSettingsScreen.show(ctx) },
            "Choose whether subtitle addons are queried and which languages are preferred.")
    }

    private fun generalMenu(ctx: Context) {
        val settings = Config.settings
        val again = { generalMenu(ctx) }
        val items = listOf(
            SettingItem("Request timeout", "${settings.timeoutSec} seconds", SettingItemType.Value,
                onClick = {
                    number(ctx, "Timeout per addon request (seconds)", settings.timeoutSec,
                        description = "Enter 5–180 seconds. This controls how long each addon request can wait before timing out.",
                        onCancel = again) {
                        updateAndReopen({ again() }) { timeoutSec = it.coerceIn(5, 180) }
                    }
                }, searchKeywords = listOf("timeout", "request", "seconds", "network")),
            SettingItem("Detailed default names", onOff(settings.detailedLabels), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { detailedLabels = !detailedLabels } },
                searchKeywords = listOf("labels", "details", "link names", "display")),
        )
        settingMenu(ctx, "General", items, { MainMenuSettingsScreen.show(ctx) },
            "Tune request limits and the amount of technical detail shown on stream links.")
    }

}
