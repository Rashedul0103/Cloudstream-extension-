package com.multistremio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object SortingSettingsScreen {
    fun show(ctx: Context) = sortingPage(ctx)

    private fun sortingPage(ctx: Context) {
        settingsPage(
            ctx,
            key = "page:sorting",
            title = "Sorting",
            description = "Choose which links appear first. Preferences break ties within the main order.",
            back = { MainMenuSettingsScreen.show(ctx) },
        ) { body, colors ->
            sortingBody(ctx, colors, Config.snapshot(), body)
        }
    }

    internal fun body(ctx: Context, colors: Palette, settings: Settings, body: LinearLayout) =
        sortingBody(ctx, colors, settings, body)

    private fun sortingBody(ctx: Context, colors: Palette, settings: Settings, body: LinearLayout) {
        val s = settings.sort
        val again = { sortingPage(ctx) }
        fun section(label: String, card: LinearLayout) {
            body.addView(sectionLabel(ctx, colors, label))
            body.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) })
        }

        val isCustom = s.preset.equals("CUSTOM", ignoreCase = true)
        val orderCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, orderCard, controlBlock(
            ctx, colors, "Ordering mode", if (isCustom) "Custom" else "Preset",
            "Preset is simpler. Custom lets you rank each comparison rule.",
            segmentedControl(ctx, colors, listOf("Preset", "Custom"), if (isCustom) 1 else 0) { index ->
                saveSettings(ctx) {
                    sort.preset = if (index == 1) "CUSTOM" else SortPreset.BEST_QUALITY.name
                }
                again()
            },
        ))
        if (isCustom) {
            addCardRow(ctx, colors, orderCard, valueRow(
                ctx, colors, "Custom priority",
                s.customOrder.joinToString(" → ") { SortCriteria.all.firstOrNull { d -> d.id == it.id }?.title ?: it.id },
                "${s.customOrder.size} rules",
            ) { customOrderDialog(ctx, s.customOrder) })
        } else {
            val presetNames = SortPreset.values().map { it.label.substringBefore(" (") }
            val selected = SortPreset.values().indexOf(SortPreset.from(s.preset))
            addCardRow(ctx, colors, orderCard, controlBlock(
                ctx, colors, "Sort preset", presetNames.getOrElse(selected) { "Best quality" },
                "Choose a predefined ranking.",
                choiceChipGrid(ctx, colors, presetNames, setOf(selected), columns = 2) { index ->
                    saveSettings(ctx) {
                        val preset = SortPreset.values()[index]
                        sort.preset = preset.name
                        sort.customOrder = SortCriteria.forPreset(preset)
                    }
                    again()
                },
            ))
        }
        section("Order", orderCard)

        val preferenceCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, preferenceCard, switchRow(
            ctx, colors, "Cached debrid links first", "Prefer links already cached by a debrid service", s.cachedFirst,
        ) { enabled -> saveSettings(ctx) { sort.cachedFirst = enabled } })
        addCardRow(ctx, colors, preferenceCard, valueRow(
            ctx, colors, "Prefer keywords", "Matching release names move higher",
            if (s.preferKeywords.isEmpty()) "Off" else "${s.preferKeywords.size} terms",
        ) {
            keywordList(
                ctx, "Prefer keywords",
                "Streams containing any term are ranked higher. Comma separated; slash-delimited regex is supported with safety limits.",
                s.preferKeywords.joinToString(", "), onCancel = again,
            ) { values -> saveSettings(ctx) { sort.preferKeywords = values }; again() }
        })
        addCardRow(ctx, colors, preferenceCard, valueRow(
            ctx, colors, "Preferred languages", "Earlier languages have higher priority",
            if (s.preferLanguages.isEmpty()) "Off" else "${s.preferLanguages.size} selected",
        ) {
            stringListPicker(
                ctx, "Preferred languages",
                "The first language has the highest priority. Choose common languages or add a regional tag.",
                SettingsChoiceLists.languages, s.preferLanguages, onCancel = again, orderSensitive = true,
            ) { values -> saveSettings(ctx) { sort.preferLanguages = values }; again() }
        })
        addCardRow(ctx, colors, preferenceCard, valueRow(
            ctx, colors, "Preferred video codecs", "Rank codecs such as AV1, HEVC and AVC",
            if (s.preferCodecs.isEmpty()) "Off" else "${s.preferCodecs.size} selected",
        ) {
            stringListPicker(
                ctx, "Preferred video codecs",
                "Earlier codecs have higher priority. Unlisted codecs follow these.",
                SettingsChoiceLists.codecs, s.preferCodecs, onCancel = again, orderSensitive = true,
            ) { values -> saveSettings(ctx) { sort.preferCodecs = values }; again() }
        })
        addCardRow(ctx, colors, preferenceCard, valueRow(
            ctx, colors, "Preferred audio formats", "Rank Atmos, TrueHD, DTS-HD, DD+ and AAC",
            if (s.preferAudio.isEmpty()) "Off" else "${s.preferAudio.size} selected",
        ) {
            stringListPicker(
                ctx, "Preferred audio formats",
                "Earlier formats have higher priority.",
                SettingsChoiceLists.audioFormats, s.preferAudio, onCancel = again, orderSensitive = true,
            ) { values -> saveSettings(ctx) { sort.preferAudio = values }; again() }
        })
        section("Preferences", preferenceCard)

        val appCard = settingsCard(ctx, colors)
        addCardRow(ctx, colors, appCard, switchRow(
            ctx, colors,
            "Use MultiStremio's order over CloudStream's",
            "Let MultiStremio rank across resolutions instead of CloudStream's quality profile",
            s.overrideAppQuality,
        ) { enabled -> saveSettings(ctx) { sort.overrideAppQuality = enabled } })
        section("CloudStream", appCard)
    }

    private fun directionLabel(id: String, descending: Boolean): String =
        if (id == "addon") {
            if (descending) "Later first" else "Earlier first"
        } else {
            if (descending) "High first" else "Low first"
        }

    private fun customOrderDialog(ctx: Context, initial: List<SortCriterionSetting>) {
        val definitions = SortCriteria.all.associateBy { it.id }
        val ordered = initial.map { it.copy() }.toMutableList()
            .ifEmpty { SortCriteria.defaults() }
        val directions = ordered.associate { it.id to it.descending }.toMutableMap()
        val colors = palette(ctx)
        val root = shell(
            ctx,
            "Custom sort order",
            "Drag the grip to rank criteria. Use the direction control to reverse a criterion. Earlier rules win ties.",
        )
        val scroll = ScrollView(ctx).apply { clipToPadding = false; tag = "settings.scroll" }
        val list = vertical(ctx)
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val numberViews = mutableMapOf<String, TextView>()
        val directionViews = mutableMapOf<String, TextView>()
        lateinit var refreshNumbers: () -> Unit
        refreshNumbers = {
            for (position in 0 until list.childCount) {
                val item = list.getChildAt(position) as? LinearLayout ?: continue
                val id = item.tag as? String ?: continue
                numberViews[id]?.text = "${position + 1}. ${definitions[id]?.title ?: id}"
                directionViews[id]?.text = directionLabel(id, directions[id] ?: true)
            }
        }

        ordered.forEach { criterion ->
            val id = criterion.id
            val definition = definitions[id] ?: return@forEach
            val handle = text(ctx, "☰", 19f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(ctx, 34)
                contentDescription = "Drag to reorder ${definition.title}"
            }
            val title = text(ctx, definition.title, 13.5f, colors.primary, true)
            val description = vertical(ctx).apply {
                addView(title)
                addView(text(ctx, definition.description, 10.5f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
            val direction = text(ctx, directionLabel(id, directions[id] ?: true), 10.5f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(ctx, 40)
                setPadding(dp(ctx, 7), dp(ctx, 4), dp(ctx, 7), dp(ctx, 4))
                background = rounded(ctx, withAlpha(colors.accent, 16), colors.outline)
                isClickable = true
                isFocusable = true
                contentDescription = "Change sort direction for ${definition.title}"
                setOnClickListener {
                    directions[id] = !(directions[id] ?: true)
                    text = directionLabel(id, directions[id] ?: true)
                }
            }
            directionViews[id] = direction
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7))
                background = rounded(ctx, colors.background, colors.outline)
                tag = id
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 34), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(description, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(direction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            numberViews[id] = title
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 5) })
            installDragReordering(ctx, handle, row, list, scroll, colors) { refreshNumbers() }
        }
        refreshNumbers()
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(170, ordered.size * 66))),
        ))
        addFooter(ctx, root, "Save order", {
            val ids = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
            val result = ids.mapNotNull { id ->
                directions[id]?.let { SortCriterionSetting(id, it) }
            }
            if (result.size == SortCriteria.all.size && result.map { it.id }.toSet() == definitions.keys) {
                saveSettings(ctx) {
                    sort.customOrder = result.toMutableList()
                    sort.preset = "CUSTOM"
                }
                sortingPage(ctx)
            } else {
                toast(ctx, "The custom order is incomplete. Nothing was saved.")
            }
        }, "Cancel", { sortingPage(ctx) })
        SettingsDialogHost.show(ctx, "sorting:custom-order", root)
    }
}
