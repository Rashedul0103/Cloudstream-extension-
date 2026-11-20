package com.multistremio

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * The Behavior page: subtitles, request timeout and homepage row titles on one screen, using
 * inline controls instead of a popup per choice. The object keeps its old name so existing
 * callers (the main menu and the search index) do not change.
 */
internal object GeneralSettingsScreen {
    fun show(ctx: Context) = behaviorPage(ctx)

    /** Subtitles now live on the Behavior page, so the old sub-menu entry points open it. */
    fun showSubtitles(ctx: Context) = behaviorPage(ctx)

    fun showLinkNameDesign(ctx: Context) = FormatterSettingsScreen.show(ctx)


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
            behaviorBody(ctx, colors, Config.snapshot(), body, again)
        }
    }
}

/**
 * Fills [body] with the Behavior controls for [settings]. Click handlers write through [Config]
 * and call [again] to rebuild. Kept free of the Config singleton for rendering, so tests can
 * draw it with any settings value.
 */
internal fun behaviorBody(
    ctx: Context,
    colors: Palette,
    settings: Settings,
    body: LinearLayout,
    again: () -> Unit,
) {
    body.addView(sectionLabel(ctx, colors, "Subtitles").apply { setPadding(dp(ctx, 8), 0, 0, dp(ctx, 6)) })
    val subtitles = settingsCard(ctx, colors)
    addCardRow(ctx, colors, subtitles, switchRow(
        ctx, colors, "Fetch subtitles", "Ask subtitle addons for captions",
        settings.fetchSubtitles,
    ) { enabled ->
        if (enabled != Config.settings.fetchSubtitles) {
            saveSettings(ctx) { fetchSubtitles = enabled }
        }
    })
    lateinit var subtitleLanguagesRow: View
    subtitleLanguagesRow = valueRow(
        ctx, colors, "Languages", "Leave empty to accept every language",
        BehaviorOptions.subtitleLanguagesValue(settings.subtitleLanguages),
    ) {
        stringListPicker(
            ctx, "Subtitle languages",
            "Select the subtitle languages to keep. Leave all unchecked to accept every language. Add custom for a regional tag.",
            SettingsChoiceLists.languages, settings.subtitleLanguages,
            onCancel = again,
        ) { values ->
            saveSettings(ctx) { subtitleLanguages = values }
            updateValueRow(subtitleLanguagesRow, "Languages", BehaviorOptions.subtitleLanguagesValue(values))
        }
    }
    addCardRow(ctx, colors, subtitles, subtitleLanguagesRow)
    body.addView(subtitles)

    body.addView(sectionLabel(ctx, colors, "Requests"))
    val requests = settingsCard(ctx, colors)
    val timeoutIndex = BehaviorOptions.timeoutIndex(settings.timeoutSec)
    val timeoutOptions = BehaviorOptions.TIMEOUT_LABELS
    lateinit var timeoutSegment: View
    lateinit var timeoutBlock: View
    timeoutSegment = segmentedControl(ctx, colors, timeoutOptions, timeoutIndex) { index ->
        val preset = BehaviorOptions.timeoutSeconds(index)
        if (preset != null) {
            saveSettings(ctx) { timeoutSec = preset }
            updateControlValue(timeoutBlock, "Addon timeout", BehaviorOptions.timeoutValueLabel(preset))
            updateSegmentedControl(ctx, colors, timeoutSegment, timeoutOptions, index)
        } else {
            boundedNumber(
                ctx, "Custom request timeout", Config.snapshot().timeoutSec, 5, 180,
                "Enter a value from 5 to 180 seconds.",
                onCancel = again,
            ) { value ->
                saveSettings(ctx) { timeoutSec = value }
                updateControlValue(timeoutBlock, "Addon timeout", BehaviorOptions.timeoutValueLabel(value))
                updateSegmentedControl(ctx, colors, timeoutSegment, timeoutOptions, BehaviorOptions.timeoutIndex(value))
            }
        }
    }
    timeoutBlock = controlBlock(
        ctx, colors, "Addon timeout", BehaviorOptions.timeoutValueLabel(settings.timeoutSec),
        "How long each addon may take to answer",
        timeoutSegment,
    )
    addCardRow(ctx, colors, requests, timeoutBlock)
    addCardRow(ctx, colors, requests, switchRow(
        ctx, colors, "Retry a failed addon once", "Retry timeouts and failed requests once", settings.retryFailedAddonOnce,
    ) { enabled -> saveSettings(ctx) { retryFailedAddonOnce = enabled } })
    addCardRow(ctx, colors, requests, switchRow(
        ctx, colors, "Show links as they arrive", "Progressively send playable links before every addon finishes", settings.progressiveLinks,
    ) { enabled -> saveSettings(ctx) { progressiveLinks = enabled } })
    val parallelOptions = listOf(1, 2, 4, 8, 16)
    lateinit var parallelGrid: View
    lateinit var parallelBlock: View
    parallelGrid = choiceChipGrid(
        ctx, colors, parallelOptions.map(Int::toString),
        setOf(parallelOptions.indexOf(settings.maxParallelAddonRequests).coerceAtLeast(0)),
        columns = 5,
    ) { index ->
        saveSettings(ctx) { maxParallelAddonRequests = parallelOptions[index] }
        updateControlValue(parallelBlock, "Parallel addon requests", "${parallelOptions[index]} at a time")
        updateChoiceChipGrid(ctx, colors, parallelGrid, parallelOptions.map(Int::toString), setOf(index))
    }
    parallelBlock = controlBlock(
        ctx, colors, "Parallel addon requests", "${settings.maxParallelAddonRequests} at a time",
        "Limit simultaneous stream and search requests.",
        parallelGrid,
    )
    addCardRow(ctx, colors, requests, parallelBlock)
    val refreshLabels = listOf("Manual", "Weekly", "Daily")
    val refreshValues = listOf("MANUAL", "WEEKLY", "DAILY")
    val refreshIndex = refreshValues.indexOf(settings.manifestRefreshSchedule).coerceAtLeast(0)
    lateinit var refreshBlock: View
    lateinit var refreshSegment: View
    refreshSegment = segmentedControl(ctx, colors, refreshLabels, refreshIndex) { index ->
        saveSettings(ctx) { manifestRefreshSchedule = refreshValues[index] }
        Config.refreshManifestsIfDue()
        updateControlValue(refreshBlock, "Refresh addon manifests", refreshLabels[index])
        updateSegmentedControl(ctx, colors, refreshSegment, refreshLabels, index)
    }
    refreshBlock = controlBlock(
        ctx, colors, "Refresh addon manifests", refreshLabels[refreshIndex],
        "Automatic refresh runs when CloudStream opens.",
        refreshSegment,
    )
    addCardRow(ctx, colors, requests, refreshBlock)
    body.addView(requests)

    body.addView(sectionLabel(ctx, colors, "Homepage"))
    val homepage = settingsCard(ctx, colors)
    val rowTitleOptions = BehaviorOptions.ROW_TITLE_LABELS
    lateinit var rowTitleSegment: View
    rowTitleSegment = segmentedControl(
        ctx, colors, rowTitleOptions, BehaviorOptions.rowTitleIndex(settings.rowTitleMode),
    ) { index ->
        val mode = BehaviorOptions.rowTitleMode(index)
        if (mode != Config.settings.rowTitleMode.uppercase()) {
            saveSettings(ctx) { rowTitleMode = mode }
            HomeRefreshNotice.mark()
        }
        updateSegmentedControl(ctx, colors, rowTitleSegment, rowTitleOptions, index)
    }
    addCardRow(ctx, colors, homepage, controlBlock(
        ctx, colors, "Addon name in row titles", null,
        "Off keeps current titles. Prefix or Suffix adds the addon name.",
        rowTitleSegment,
    ))
    body.addView(homepage)
}
