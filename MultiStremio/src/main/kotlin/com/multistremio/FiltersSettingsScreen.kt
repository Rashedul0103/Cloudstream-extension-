package com.multistremio

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout

internal object FiltersSettingsScreen {
    fun show(ctx: Context) = filtersPage(ctx)

    private val RES_OPTIONS = listOf(
        2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p",
        576 to "576p", 480 to "480p", 360 to "360p", 240 to "240p", 0 to "Unknown",
    )

    private fun filtersPage(ctx: Context) {
        settingsPage(
            ctx,
            key = "page:filters",
            title = "Filters",
            description = "Decide which streams are allowed. Changes apply to the next lookup.",
            back = { MainMenuSettingsScreen.show(ctx) },
        ) { body, colors ->
            filtersBody(ctx, colors, Config.snapshot(), body)
        }
    }

    internal fun body(
        ctx: Context,
        colors: Palette,
        settings: Settings,
        body: LinearLayout,
    ) = filtersBody(ctx, colors, settings, body)

    private fun filtersBody(ctx: Context, colors: Palette, settings: Settings, body: LinearLayout) {
        val f = settings.filter
        val again = { filtersPage(ctx) }
        fun section(label: String, card: LinearLayout) {
            body.addView(sectionLabel(ctx, colors, label))
            body.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) })
        }

        val presetCard = settingsCard(ctx, colors)
        val presetIndex = when (settings.filterPreset.uppercase()) {
            "BEST_QUALITY" -> 1
            "DATA_SAVER" -> 2
            "CUSTOM" -> 3
            else -> 0
        }
        addCardRow(ctx, colors, presetCard, controlBlock(
            ctx, colors, "Filter preset", listOf("Balanced", "Best", "Saver", "Custom")[presetIndex],
            "A preset changes both filtering and sorting. Manual edits switch to Custom.",
            segmentedControl(ctx, colors, listOf("Balanced", "Best", "Saver", "Custom"), presetIndex) { index ->
                when (index) {
                    0 -> applyPreset(ctx, 0)
                    1 -> applyPreset(ctx, 1)
                    2 -> applyPreset(ctx, 2)
                    else -> Unit
                }
                again()
            },
        ))
        section("Quick preset", presetCard)

        val qualityCard = settingsCard(ctx, colors)
        val allResolutions = RES_OPTIONS.map { it.first }
        val selectedResolutions = if (f.allowedResolutions.isEmpty()) allResolutions.toSet()
            else f.allowedResolutions.toSet()
        addCardRow(ctx, colors, qualityCard, controlBlock(
            ctx, colors,
            "Allowed resolutions",
            if (selectedResolutions.size == allResolutions.size) "Any" else "${selectedResolutions.size} selected",
            "Choose every resolution you want to keep.",
            choiceChipGrid(
                ctx, colors, RES_OPTIONS.map { it.second },
                RES_OPTIONS.indices.filterTo(mutableSetOf()) { RES_OPTIONS[it].first in selectedResolutions },
                columns = 3,
            ) { index ->
                val current = Config.snapshot().filter.allowedResolutions
                val allowed = (if (current.isEmpty()) allResolutions.toMutableSet() else current.toMutableSet())
                val resolution = RES_OPTIONS[index].first
                if (resolution in allowed) allowed.remove(resolution) else allowed.add(resolution)
                Config.update {
                    filter.allowedResolutions = if (allowed.size == allResolutions.size) {
                        mutableListOf()
                    } else {
                        allResolutions.filter { it in allowed }.toMutableList()
                    }
                }
                again()
            },
        ))
        addCardRow(ctx, colors, qualityCard, switchRow(
            ctx, colors, "Hide CAM / telesync", "Remove cinema recordings", f.hideCam,
        ) { enabled -> Config.update { filter.hideCam = enabled }; again() })
        addCardRow(ctx, colors, qualityCard, controlBlock(
            ctx, colors, "HEVC / x265", listOf("Any", "Hide", "Only")[f.hevcMode.coerceIn(0, 2)],
            "Control streams encoded with HEVC.",
            segmentedControl(ctx, colors, listOf("Any", "Hide", "Only"), f.hevcMode.coerceIn(0, 2)) { mode ->
                Config.update { filter.hevcMode = mode }
                again()
            },
        ))
        addCardRow(ctx, colors, qualityCard, controlBlock(
            ctx, colors, "HDR / Dolby Vision", listOf("Any", "Hide", "Only")[f.hdrMode.coerceIn(0, 2)],
            "Control HDR and Dolby Vision streams.",
            segmentedControl(ctx, colors, listOf("Any", "Hide", "Only"), f.hdrMode.coerceIn(0, 2)) { mode ->
                Config.update { filter.hdrMode = mode }
                again()
            },
        ))
        section("Quality", qualityCard)

        val sourceCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, sourceCard, switchRow(
            ctx, colors, "Hide magnet links", "Remove torrent magnet links", f.hideMagnets,
        ) { enabled -> Config.update { filter.hideMagnets = enabled }; again() })
        addCardRow(ctx, colors, sourceCard, switchRow(
            ctx, colors, "Hide uncached debrid links", "Keep cached debrid links and other sources", f.hideUncachedDebrid,
        ) { enabled -> Config.update { filter.hideUncachedDebrid = enabled }; again() })
        section("Sources", sourceCard)

        val moreCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Minimum file size", "Smaller files are hidden", limit(f.minSizeMb, "MB"),
        ) {
            number(ctx, "Minimum file size (MB; 0 = off)", f.minSizeMb, onCancel = again) { value ->
                Config.update { filter.minSizeMb = value }; again()
            }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Maximum file size", "Larger files are hidden", limit(f.maxSizeGb, "GB"),
        ) {
            number(ctx, "Maximum file size (GB; 0 = off)", f.maxSizeGb, onCancel = again) { value ->
                Config.update { filter.maxSizeGb = value }; again()
            }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Minimum seeders", "Streams without seeder data are kept", limit(f.minSeeders, ""),
        ) {
            number(ctx, "Minimum seeders (0 = off)", f.minSeeders, onCancel = again) { value ->
                Config.update { filter.minSeeders = value }; again()
            }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Exclude keywords",
            if (f.excludeKeywords.isEmpty()) "No excluded terms" else f.excludeKeywords.joinToString(", "),
            if (f.excludeKeywords.isEmpty()) "Off" else "${f.excludeKeywords.size} terms",
        ) {
            keywordList(
                ctx, "Exclude keywords",
                "Comma separated and case-insensitive. Wrap a regular expression in slashes, for example /x26[45]/. Unsafe regex patterns are rejected.",
                f.excludeKeywords.joinToString(", "), onCancel = again,
            ) { values -> Config.update { filter.excludeKeywords = values }; again() }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Require keywords (any)",
            if (f.requireKeywords.isEmpty()) "No required terms" else f.requireKeywords.joinToString(", "),
            if (f.requireKeywords.isEmpty()) "Off" else "${f.requireKeywords.size} terms",
        ) {
            keywordList(
                ctx, "Require keywords",
                "A stream must match at least one term. Leave empty to disable. Wrap a regular expression in slashes, for example /x26[45]/.",
                f.requireKeywords.joinToString(", "), onCancel = again,
            ) { values -> Config.update { filter.requireKeywords = values }; again() }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Required languages", "Keep streams matching at least one language",
            if (f.requireLanguages.isEmpty()) "Any" else "${f.requireLanguages.size} selected",
        ) {
            stringListPicker(
                ctx, "Required languages",
                "A stream must match one selected language. Add a custom regional tag when needed.",
                SettingsChoiceLists.languages, f.requireLanguages, onCancel = again,
            ) { values -> Config.update { filter.requireLanguages = values }; again() }
        })
        addCardRow(ctx, colors, moreCard, switchRow(
            ctx, colors, "Strict language", "Hide streams whose audio language is unknown", f.strictLanguage,
        ) { enabled -> Config.update { filter.strictLanguage = enabled }; again() })
        section("Keywords & languages", moreCard)

        val limitsCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, limitsCard, valueRow(
            ctx, colors, "Maximum results per addon", "0 means no per-addon limit", limit(f.maxPerAddon, ""),
        ) {
            number(ctx, "Maximum results per addon (0 = off)", f.maxPerAddon, onCancel = again) { value ->
                Config.update { filter.maxPerAddon = value }; again()
            }
        })
        addCardRow(ctx, colors, limitsCard, valueRow(
            ctx, colors, "Maximum results total", "Applied after filtering and sorting", limit(f.maxTotal, ""),
        ) {
            number(ctx, "Maximum results total (0 = off)", f.maxTotal, onCancel = again) { value ->
                Config.update { filter.maxTotal = value }; again()
            }
        })
        section("Result limits", limitsCard)
    }

    private fun applyPreset(ctx: Context, index: Int) {
        val all = RES_OPTIONS.map { it.first }
        Config.update {
            when (index) {
                1 -> {
                    filter = FilterSettings(
                        allowedResolutions = all.toMutableList(),
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
}
