package com.multistremio

import android.content.Context

/**
 * The Behavior page: subtitles, request timeout and homepage row titles on one screen, using
 * inline controls instead of a popup per choice. The object keeps its old name so existing
 * callers (the main menu and the search index) do not change.
 */
internal object GeneralSettingsScreen {
    fun show(ctx: Context) = behaviorPage(ctx)

    /** Subtitles now live on the Behavior page, so the old sub-menu entry points open it. */
    fun showSubtitles(ctx: Context) = behaviorPage(ctx)

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

    private fun behaviorPage(ctx: Context) {
        val again = { behaviorPage(ctx) }
        settingsPage(
            ctx,
            key = "page:behavior",
            title = "Behavior",
            description = "Subtitles, request limits and homepage titles. Changes save instantly.",
            back = { MainMenuSettingsScreen.show(ctx) },
            showRefreshNotice = true,
        ) { body, colors ->
            // Read inside the builder so a rebuild after a change always shows current values.
            val settings = Config.snapshot()

            body.addView(sectionLabel(ctx, colors, "Subtitles").apply { setPadding(dp(ctx, 8), 0, 0, dp(ctx, 6)) })
            val subtitles = settingsCard(ctx, colors)
            addCardRow(ctx, colors, subtitles, switchRow(
                ctx, colors, "Fetch subtitles", "Ask subtitle addons for captions",
                settings.fetchSubtitles,
            ) { enabled ->
                if (enabled != Config.settings.fetchSubtitles) {
                    updateAndReopen({ again() }) { fetchSubtitles = enabled }
                }
            })
            addCardRow(ctx, colors, subtitles, valueRow(
                ctx, colors, "Languages", "Leave empty to accept every language",
                BehaviorOptions.subtitleLanguagesValue(settings.subtitleLanguages),
            ) {
                stringListPicker(
                    ctx, "Subtitle languages",
                    "Select the subtitle languages to keep. Leave all unchecked to accept every language. Add custom for a regional tag.",
                    SettingsChoiceLists.languages, settings.subtitleLanguages,
                    onCancel = again,
                ) { values -> updateAndReopen({ again() }) { subtitleLanguages = values } }
            })
            body.addView(subtitles)

            body.addView(sectionLabel(ctx, colors, "Requests"))
            val requests = settingsCard(ctx, colors)
            val timeoutIndex = BehaviorOptions.timeoutIndex(settings.timeoutSec)
            addCardRow(ctx, colors, requests, controlBlock(
                ctx, colors, "Addon timeout", BehaviorOptions.timeoutValueLabel(settings.timeoutSec),
                "How long each addon may take to answer",
                segmentedControl(ctx, colors, BehaviorOptions.TIMEOUT_LABELS, timeoutIndex) { index ->
                    val preset = BehaviorOptions.timeoutSeconds(index)
                    if (preset != null) {
                        if (preset != Config.settings.timeoutSec) {
                            updateAndReopen({ again() }) { timeoutSec = preset }
                        }
                    } else {
                        boundedNumber(
                            ctx, "Custom request timeout", settings.timeoutSec, 5, 180,
                            "Enter a value from 5 to 180 seconds.",
                            onCancel = again,
                        ) { value -> updateAndReopen({ again() }) { timeoutSec = value } }
                    }
                },
            ))
            body.addView(requests)

            body.addView(sectionLabel(ctx, colors, "Homepage"))
            val homepage = settingsCard(ctx, colors)
            addCardRow(ctx, colors, homepage, controlBlock(
                ctx, colors, "Addon name in row titles", null,
                "Off keeps current titles. Prefix or Suffix adds the addon name.",
                segmentedControl(
                    ctx, colors, BehaviorOptions.ROW_TITLE_LABELS,
                    BehaviorOptions.rowTitleIndex(settings.rowTitleMode),
                ) { index ->
                    val mode = BehaviorOptions.rowTitleMode(index)
                    if (mode != Config.settings.rowTitleMode.uppercase()) {
                        Config.update { rowTitleMode = mode }
                        HomeRefreshNotice.mark()
                    }
                    again()
                },
            ))
            body.addView(homepage)
        }
    }
}
