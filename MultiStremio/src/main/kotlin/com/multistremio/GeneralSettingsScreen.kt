package com.multistremio

import android.content.Context

internal object GeneralSettingsScreen {
    fun show(ctx: Context) = generalMenu(ctx)
    fun showSubtitles(ctx: Context) = subtitlesMenu(ctx)

    private fun subtitlesMenu(ctx: Context) {
        val settings = Config.settings
        val again = { subtitlesMenu(ctx) }
        val items = listOf(
            SettingItem(
                "Fetch from subtitle addons",
                "Query enabled addons that provide subtitles",
                SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { fetchSubtitles = !fetchSubtitles } },
                searchKeywords = listOf("subtitle", "captions", "fetch", "addons"),
                checked = settings.fetchSubtitles,
            ),
            SettingItem(
                "Subtitle languages",
                csv(settings.subtitleLanguages),
                SettingItemType.Value,
                onClick = {
                    stringListPicker(
                        ctx, "Subtitle languages",
                        "Select the subtitle languages to keep. Leave all unchecked to accept every language. Add custom for a regional tag.",
                        SettingsChoiceLists.languages, settings.subtitleLanguages,
                        onCancel = again,
                    ) { values -> updateAndReopen({ again() }) { subtitleLanguages = values } }
                },
                searchKeywords = listOf("subtitle", "language", "English", "Bengali"),
                valueLabel = if (settings.subtitleLanguages.isEmpty()) "All" else "${settings.subtitleLanguages.size} selected",
            ),
        )
        settingMenu(ctx, "Subtitles", items, { generalMenu(ctx) },
            "Choose whether subtitle addons are queried and which languages are allowed.",
            forceSearch = true)
    }

    fun showLinkNameDesign(ctx: Context) {
        // This is a choice dialog rather than a searchable settings menu; consume the routed query.
        SettingsDialogHost.consumeNextMenuSearch()
        val settings = Config.snapshot()
        val currentStyle = when {
            settings.streamFormat.enabled -> "Custom design"
            settings.detailedLabels -> "Detailed"
            else -> "Simple"
        }
        val choices = listOf("Simple", "Detailed", "Custom design")
        val selected = when (currentStyle) {
            "Detailed" -> 1
            "Custom design" -> 2
            else -> 0
        }
        choose(ctx, "Link name design", choices, selected, onCancel = {
            MainMenuSettingsScreen.show(ctx)
        }) { index ->
            Config.update {
                when (index) {
                    0 -> {
                        detailedLabels = false
                        streamFormat.enabled = false
                    }
                    1 -> {
                        detailedLabels = true
                        streamFormat.enabled = false
                    }
                    else -> {
                        detailedLabels = false
                        streamFormat.enabled = true
                    }
                }
            }
            if (index == 2) FormatterSettingsScreen.show(ctx)
            else MainMenuSettingsScreen.show(ctx)
        }
    }

    private fun generalMenu(ctx: Context) {
        val settings = Config.settings
        val again = { generalMenu(ctx) }
        val timeoutPreset = when (settings.timeoutSec) {
            10 -> "Fast"
            30 -> "Normal"
            60 -> "Patient"
            else -> "Custom"
        }
        val rowTitle = when (settings.rowTitleMode.uppercase()) {
            "PREFIX" -> "Addon name first"
            "SUFFIX" -> "Addon name last"
            else -> "Off"
        }
        val items = listOf(
            SettingItem(
                "Subtitles",
                if (settings.fetchSubtitles) "Fetching enabled · ${if (settings.subtitleLanguages.isEmpty()) "all languages" else settings.subtitleLanguages.size.toString() + " selected"}"
                else "Fetching disabled",
                SettingItemType.Navigate,
                onClick = { showSubtitles(ctx) },
                section = "Subtitles",
                searchKeywords = listOf("subtitle", "captions", "fetch", "language"),
            ),
            SettingItem(
                "Addon request timeout",
                "Each addon can take up to this long to respond",
                SettingItemType.Value,
                onClick = {
                    val choices = listOf("Fast · 10 seconds", "Normal · 30 seconds", "Patient · 60 seconds", "Custom · 5–180 seconds")
                    val selected = when (settings.timeoutSec) {
                        10 -> 0
                        30 -> 1
                        60 -> 2
                        else -> 3
                    }
                    choose(ctx, "Request timeout", choices, selected, onCancel = again) { index ->
                        when (index) {
                            0 -> updateAndReopen({ again() }) { timeoutSec = 10 }
                            1 -> updateAndReopen({ again() }) { timeoutSec = 30 }
                            2 -> updateAndReopen({ again() }) { timeoutSec = 60 }
                            else -> boundedNumber(
                                ctx, "Custom request timeout", settings.timeoutSec, 5, 180,
                                "Enter a value from 5 to 180 seconds.",
                                onCancel = again,
                            ) { value -> updateAndReopen({ again() }) { timeoutSec = value } }
                        }
                    }
                },
                searchKeywords = listOf("timeout", "request", "seconds", "network"),
                valueLabel = "${settings.timeoutSec}s · $timeoutPreset",
            ),

            SettingItem(
                "Show addon name in homepage row titles",
                "Off keeps current titles; Prefix or Suffix adds the addon name",
                SettingItemType.Value,
                onClick = {
                    val choices = listOf("Off", "Prefix", "Suffix")
                    val index = when (settings.rowTitleMode.uppercase()) {
                        "PREFIX" -> 1
                        "SUFFIX" -> 2
                        else -> 0
                    }
                    choose(ctx, "Addon name in row titles", choices, index, onCancel = again) { selected ->
                        Config.update {
                            rowTitleMode = when (selected) {
                                1 -> "PREFIX"
                                2 -> "SUFFIX"
                                else -> "OFF"
                            }
                        }
                        HomeRefreshNotice.mark()
                        again()
                    }
                },
                searchKeywords = listOf("row title", "addon name", "prefix", "suffix", "homepage"),
                valueLabel = rowTitle,
            ),
        )
        settingMenu(ctx, "General", items, { MainMenuSettingsScreen.show(ctx) },
            "Tune request limits and homepage row labels. Link name design is under Streams.",
            forceSearch = true)
    }
}
