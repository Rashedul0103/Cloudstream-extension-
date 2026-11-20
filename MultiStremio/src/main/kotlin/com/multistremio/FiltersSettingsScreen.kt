package com.multistremio

import android.content.Context
import android.view.View
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
        val presetOptions = listOf("Balanced", "Best", "Saver", "Custom")
        val modeOptions = listOf("Any", "Hide", "Only")
        val allResolutions = RES_OPTIONS.map { it.first }
        val resolutionLabels = RES_OPTIONS.map { it.second }
        lateinit var presetSegment: View
        lateinit var hevcSegment: View
        lateinit var hdrSegment: View
        lateinit var presetBlock: View
        lateinit var hevcBlock: View
        lateinit var hdrBlock: View
        lateinit var resolutionGrid: View
        lateinit var resolutionBlock: View
        lateinit var impactSummaryView: android.widget.TextView
        lateinit var impactDetailView: android.widget.TextView
        fun refreshFilterIndicators() {
            val current = Config.snapshot()
            val currentPresetIndex = when (current.filterPreset.uppercase()) {
                "BEST_QUALITY" -> 1
                "DATA_SAVER" -> 2
                "CUSTOM" -> 3
                else -> 0
            }
            updateControlValue(presetBlock, "Filter preset", presetOptions[currentPresetIndex])
            updateSegmentedControl(ctx, colors, presetSegment, presetOptions, currentPresetIndex)
            updateControlValue(hevcBlock, "HEVC / x265", modeOptions[current.filter.hevcMode.coerceIn(0, 2)])
            updateSegmentedControl(ctx, colors, hevcSegment, modeOptions, current.filter.hevcMode.coerceIn(0, 2))
            updateControlValue(hdrBlock, "HDR / Dolby Vision", modeOptions[current.filter.hdrMode.coerceIn(0, 2)])
            updateSegmentedControl(ctx, colors, hdrSegment, modeOptions, current.filter.hdrMode.coerceIn(0, 2))
            val selectedResolutions = if (current.filter.allowedResolutions.isEmpty()) allResolutions.toSet()
                else current.filter.allowedResolutions.toSet()
            updateControlValue(
                resolutionBlock,
                "Allowed resolutions",
                if (selectedResolutions.size == allResolutions.size) "Any" else "${selectedResolutions.size} selected",
            )
            updateChoiceChipGrid(
                ctx, colors, resolutionGrid, resolutionLabels,
                RES_OPTIONS.indices.filterTo(mutableSetOf()) { RES_OPTIONS[it].first in selectedResolutions },
            )
            val impact = StreamDiagnostics.filterImpact(current.filter)
            impactSummaryView.text = when {
                impact == null -> "Run a stream lookup to preview how these filters affect links."
                impact.totalCandidates == 0 -> "No playable links were found in the last lookup."
                else -> "About ${impact.hidden} of ${impact.totalCandidates} links hidden" +
                    if (impact.sampled) " (sampled)" else ""
            }
            impactDetailView.text = impact?.reasons.orEmpty().entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(" · ") { "${it.key}: ${it.value}" }
                .ifBlank { "Updates as you change filters" }
        }
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
        presetSegment = segmentedControl(ctx, colors, presetOptions, presetIndex) { index ->
            when (index) {
                0 -> { applyPreset(ctx, 0); again() }
                1 -> { applyPreset(ctx, 1); again() }
                2 -> { applyPreset(ctx, 2); again() }
                else -> { saveSettings(ctx) { filterPreset = "CUSTOM" }; refreshFilterIndicators() }
            }
        }
        presetBlock = controlBlock(
            ctx, colors, "Filter preset", presetOptions[presetIndex],
            "A preset changes both filtering and sorting. Manual edits switch to Custom.",
            presetSegment,
        )
        addCardRow(ctx, colors, presetCard, presetBlock)
        section("Quick preset", presetCard)

        val impact = StreamDiagnostics.filterImpact(f)
        body.addView(sectionLabel(ctx, colors, "Last lookup"))
        val impactCard = settingsCard(ctx, colors)
        val impactSummary = when {
            impact == null -> "Run a stream lookup to preview how these filters affect links."
            impact.totalCandidates == 0 -> "No playable links were found in the last lookup."
            else -> "About ${impact.hidden} of ${impact.totalCandidates} links hidden" +
                if (impact.sampled) " (sampled)" else ""
        }
        addCardRow(ctx, colors, impactCard, LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            impactSummaryView = text(ctx, impactSummary, 13f, colors.primary, true)
            addView(impactSummaryView)
            val reasons = impact?.reasons.orEmpty().entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(" · ") { "${it.key}: ${it.value}" }
            val detail = if (reasons.isBlank()) "Updates as you change filters" else reasons
            impactDetailView = text(ctx, detail, 11f, colors.secondary).apply { setPadding(0, dp(ctx, 3), 0, 0) }
            addView(impactDetailView)
        })
        body.addView(impactCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 8) })

        val qualityCard = settingsCard(ctx, colors)
        val selectedResolutions = if (f.allowedResolutions.isEmpty()) allResolutions.toSet()
            else f.allowedResolutions.toSet()
        resolutionGrid = choiceChipGrid(
            ctx, colors, resolutionLabels,
            RES_OPTIONS.indices.filterTo(mutableSetOf()) { RES_OPTIONS[it].first in selectedResolutions },
            columns = 3,
        ) { index ->
            val current = Config.snapshot().filter.allowedResolutions
            val allowed = (if (current.isEmpty()) allResolutions.toMutableSet() else current.toMutableSet())
            val resolution = RES_OPTIONS[index].first
            if (resolution in allowed) allowed.remove(resolution) else allowed.add(resolution)
            saveSettings(ctx) {
                filter.allowedResolutions = if (allowed.size == allResolutions.size) {
                    mutableListOf()
                } else {
                    allResolutions.filter { it in allowed }.toMutableList()
                }
            }
            refreshFilterIndicators()
        }
        resolutionBlock = controlBlock(
            ctx, colors,
            "Allowed resolutions",
            if (selectedResolutions.size == allResolutions.size) "Any" else "${selectedResolutions.size} selected",
            "Choose every resolution you want to keep.",
            resolutionGrid,
        )
        addCardRow(ctx, colors, qualityCard, resolutionBlock)
        addCardRow(ctx, colors, qualityCard, switchRow(
            ctx, colors, "Hide CAM / telesync", "Remove cinema recordings", f.hideCam,
        ) { enabled -> saveSettings(ctx) { filter.hideCam = enabled }; refreshFilterIndicators() })
        hevcSegment = segmentedControl(ctx, colors, modeOptions, f.hevcMode.coerceIn(0, 2)) { mode ->
            saveSettings(ctx) { filter.hevcMode = mode }
            refreshFilterIndicators()
        }
        hevcBlock = controlBlock(
            ctx, colors, "HEVC / x265", modeOptions[f.hevcMode.coerceIn(0, 2)],
            "Control streams encoded with HEVC.",
            hevcSegment,
        )
        addCardRow(ctx, colors, qualityCard, hevcBlock)
        hdrSegment = segmentedControl(ctx, colors, modeOptions, f.hdrMode.coerceIn(0, 2)) { mode ->
            saveSettings(ctx) { filter.hdrMode = mode }
            refreshFilterIndicators()
        }
        hdrBlock = controlBlock(
            ctx, colors, "HDR / Dolby Vision", modeOptions[f.hdrMode.coerceIn(0, 2)],
            "Control HDR and Dolby Vision streams.",
            hdrSegment,
        )
        addCardRow(ctx, colors, qualityCard, hdrBlock)
        section("Quality", qualityCard)

        val sourceCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, sourceCard, switchRow(
            ctx, colors, "Hide magnet links", "Remove torrent magnet links", f.hideMagnets,
        ) { enabled -> saveSettings(ctx) { filter.hideMagnets = enabled }; refreshFilterIndicators() })
        addCardRow(ctx, colors, sourceCard, switchRow(
            ctx, colors, "Hide uncached debrid links", "Keep cached debrid links and other sources", f.hideUncachedDebrid,
        ) { enabled -> saveSettings(ctx) { filter.hideUncachedDebrid = enabled }; refreshFilterIndicators() })
        section("Sources", sourceCard)

        val moreCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Minimum file size", "Smaller files are hidden", limit(f.minSizeMb, "MB"),
        ) {
            boundedNumber(ctx, "Minimum file size (MB; 0 = off)", f.minSizeMb, 0, 1_000_000,
                "Enter a whole number from 0 to 1,000,000 MB.", onCancel = again) { value ->
                saveSettings(ctx) { filter.minSizeMb = value }; again()
            }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Maximum file size", "Larger files are hidden", limit(f.maxSizeGb, "GB"),
        ) {
            boundedNumber(ctx, "Maximum file size (GB; 0 = off)", f.maxSizeGb, 0, 1_000_000,
                "Enter a whole number from 0 to 1,000,000 GB.", onCancel = again) { value ->
                saveSettings(ctx) { filter.maxSizeGb = value }; again()
            }
        })
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Minimum seeders", "Streams without seeder data are kept", limit(f.minSeeders, ""),
        ) {
            boundedNumber(ctx, "Minimum seeders (0 = off)", f.minSeeders, 0, 10_000_000,
                "Enter a whole number from 0 to 10,000,000.", onCancel = again) { value ->
                saveSettings(ctx) { filter.minSeeders = value }; again()
            }
        })
        addCardRow(ctx, colors, moreCard, keywordEditor(
            ctx, colors, "Exclude keywords", "Matching terms hide a stream",
            f.excludeKeywords,
            suggestions = listOf("TS", "Hardsub", "3D", "Dub"),
            editDescription = "Comma separated and case-insensitive. Wrap regex in slashes, such as /x26[45]/.",
            onEdit = { values -> saveSettings(ctx) { filter.excludeKeywords = values }; again() },
            onSuggestion = { value ->
                saveSettings(ctx) {
                    val current = filter.excludeKeywords.toMutableList()
                    val existing = current.firstOrNull { it.equals(value, ignoreCase = true) }
                    if (existing != null) current.remove(existing) else current.add(value)
                    filter.excludeKeywords = current
                }
                again()
            },
        ))
        addCardRow(ctx, colors, moreCard, keywordEditor(
            ctx, colors, "Require keywords (any)", "A stream must match at least one term",
            f.requireKeywords,
            editDescription = "Comma separated. Wrap regex in slashes, such as /x26[45]/. Leave empty to disable.",
            onEdit = { values -> saveSettings(ctx) { filter.requireKeywords = values }; again() },
            onSuggestion = {},
        ))
        addCardRow(ctx, colors, moreCard, valueRow(
            ctx, colors, "Required languages", "Keep streams matching at least one language",
            if (f.requireLanguages.isEmpty()) "Any" else "${f.requireLanguages.size} selected",
        ) {
            stringListPicker(
                ctx, "Required languages",
                "A stream must match one selected language. Add a custom regional tag when needed.",
                SettingsChoiceLists.languages, f.requireLanguages, onCancel = again,
            ) { values -> saveSettings(ctx) { filter.requireLanguages = values }; again() }
        })
        addCardRow(ctx, colors, moreCard, switchRow(
            ctx, colors, "Strict language", "Hide streams whose audio language is unknown", f.strictLanguage,
        ) { enabled -> saveSettings(ctx) { filter.strictLanguage = enabled }; refreshFilterIndicators() })
        section("Keywords & languages", moreCard)

        val limitsCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, limitsCard, valueRow(
            ctx, colors, "Maximum results per addon", "0 means no per-addon limit", limit(f.maxPerAddon, ""),
        ) {
            boundedNumber(ctx, "Maximum results per addon (0 = off)", f.maxPerAddon, 0, 50_000,
                "Enter a whole number from 0 to 50,000.", onCancel = again) { value ->
                saveSettings(ctx) { filter.maxPerAddon = value }; again()
            }
        })
        addCardRow(ctx, colors, limitsCard, valueRow(
            ctx, colors, "Maximum results total", "Applied after filtering and sorting", limit(f.maxTotal, ""),
        ) {
            boundedNumber(ctx, "Maximum results total (0 = off)", f.maxTotal, 0, 50_000,
                "Enter a whole number from 0 to 50,000.", onCancel = again) { value ->
                saveSettings(ctx) { filter.maxTotal = value }; again()
            }
        })
        section("Result limits", limitsCard)
    }

    private fun applyPreset(ctx: Context, index: Int) {
        val all = RES_OPTIONS.map { it.first }
        saveSettings(ctx) {
            when (index) {
                1 -> {
                    filter = FilterSettings(
                        allowedResolutions = all.toMutableList(),
                        hideCam = true,
                    )
                    sort = SortSettings(preset = SortPreset.BEST_QUALITY.name)
                    sort.customOrder = SortCriteria.defaults()
                    filterPreset = "BEST_QUALITY"
                }
                2 -> {
                    filter = FilterSettings(
                        allowedResolutions = mutableListOf(720, 576, 480, 360, 240),
                        hideCam = true,
                    )
                    sort = SortSettings(preset = SortPreset.SMALLEST.name)
                    sort.customOrder = SortCriteria.forPreset(SortPreset.SMALLEST)
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

    private fun keywordEditor(
        ctx: Context,
        colors: Palette,
        title: String,
        summary: String,
        values: List<String>,
        suggestions: List<String> = emptyList(),
        editDescription: String,
        onEdit: (MutableList<String>) -> Unit,
        onSuggestion: (String) -> Unit,
    ): android.view.View {
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (suggestions.isNotEmpty()) {
            controls.addView(choiceChipGrid(
                ctx,
                colors,
                suggestions,
                suggestions.indices.filterTo(mutableSetOf()) { suggestion ->
                    values.any { it.equals(suggestions[suggestion], ignoreCase = true) }
                },
                columns = 4,
            ) { index -> onSuggestion(suggestions[index]) })
        }
        controls.addView(valueRow(
            ctx, colors, "Edit custom terms", values.joinToString(", ").ifBlank { "No terms" },
            if (values.isEmpty()) "Add" else "${values.size} terms",
        ) {
            keywordList(ctx, title, editDescription, values.joinToString(", "), onCancel = { filtersPage(ctx) }, onSave = onEdit)
        })
        val invalid = StreamFilter.invalidRegexPatterns(values)
        if (invalid.isNotEmpty()) {
            controls.addView(text(
                ctx,
                "Invalid regular expression: ${invalid.joinToString(", ")}",
                11f,
                colors.danger,
            ).apply { setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 6)) })
        }
        return controlBlock(
            ctx, colors, title,
            if (values.isEmpty()) "Off" else "${values.size} terms",
            summary,
            controls,
        )
    }
}
