package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object FormatterSettingsScreen {
    private val compactFields = mutableListOf("addon", "quality", "codec", "size", "cache")

    fun show(ctx: Context) = linkDesignPage(ctx)

    private fun linkDesignPage(ctx: Context) {
        settingsPage(
            ctx,
            key = "page:link-design",
            title = "Link design",
            description = "Choose how each stream appears in the link list. Preview uses an example release.",
            back = { MainMenuSettingsScreen.show(ctx) },
        ) { body, colors ->
            linkDesignBody(ctx, colors, Config.snapshot(), body)
        }
    }

    internal fun body(ctx: Context, colors: Palette, settings: Settings, body: LinearLayout) =
        linkDesignBody(ctx, colors, settings, body)

    private fun currentPreset(settings: Settings): String {
        val preset = settings.streamFormat.designPreset.uppercase()
        if (preset != "AUTO") return preset
        return when {
            settings.streamFormat.enabled -> "CUSTOM"
            settings.detailedLabels -> "DETAILED"
            else -> "SIMPLE"
        }
    }

    private fun selectPreset(preset: String) {
        Config.update {
            when (preset) {
                "SIMPLE" -> {
                    detailedLabels = false
                    streamFormat.enabled = false
                    streamFormat.designPreset = "SIMPLE"
                }
                "DETAILED" -> {
                    detailedLabels = true
                    streamFormat.enabled = false
                    streamFormat.designPreset = "DETAILED"
                }
                "COMPACT" -> {
                    detailedLabels = true
                    streamFormat.enabled = true
                    streamFormat.fields = compactFields.toMutableList()
                    streamFormat.separator = " · "
                    streamFormat.showLabels = false
                    streamFormat.designPreset = "COMPACT"
                }
                else -> {
                    detailedLabels = true
                    streamFormat.enabled = true
                    if (streamFormat.fields.isEmpty()) {
                        streamFormat.fields = StreamFormatFields.defaultOrder.toMutableList()
                    }
                    streamFormat.designPreset = "CUSTOM"
                }
            }
        }
    }

    private fun linkDesignBody(ctx: Context, colors: Palette, settings: Settings, body: LinearLayout) {
        val selected = currentPreset(settings)
        val choices = listOf(
            Triple("SIMPLE", "Simple", "Addon name and original release text"),
            Triple("DETAILED", "Detailed", "Quality, source, HDR, codec, audio and more"),
            Triple("COMPACT", "Compact", "A short summary: addon, quality, codec, size and cache"),
            Triple("CUSTOM", "Custom", "Choose fields, order, labels and separator"),
        )
        choices.forEach { (id, title, summary) ->
            val active = id == selected
            val preview = StreamLabels.previewDesign(id, settings.streamFormat)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                background = rounded(
                    ctx,
                    if (active) withAlpha(colors.accent, 17) else colors.background,
                    if (active) colors.accent else colors.outline,
                )
                isClickable = true
                isFocusable = true
                contentDescription = "$title link design" + if (active) ", selected" else ""
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(ctx, title, 14f, colors.primary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (active) addView(text(ctx, "SELECTED", 9.5f, colors.accent, true))
                })
                addView(text(ctx, summary, 11.5f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 3), 0, dp(ctx, 7))
                })
                addView(text(ctx, preview, 11.5f, colors.primary).apply {
                    maxLines = 3
                    setPadding(dp(ctx, 9), dp(ctx, 8), dp(ctx, 9), dp(ctx, 8))
                    background = rounded(ctx, withAlpha(colors.secondary, 18))
                })
                setOnClickListener {
                    if (id != selected) selectPreset(id)
                    linkDesignPage(ctx)
                }
            }
            body.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) })
        }

        if (selected == "CUSTOM") {
            body.addView(sectionLabel(ctx, colors, "Customize"))
            val card = settingsCard(ctx, colors)
            val format = settings.streamFormat
            val validFields = StreamFormatFields.all.map { it.id }.toSet()
            val selectedFields = format.fields.filter { it in validFields }.toSet()
            addCardRow(ctx, colors, card, valueRow(
                ctx, colors, "Information to show", "Choose the details included in each link",
                "${selectedFields.size} fields",
            ) {
                multi(
                    ctx, "Information to display",
                    StreamFormatFields.all.map { "${it.title} — ${it.description}" },
                    StreamFormatFields.all.map { it.id in selectedFields },
                    description = "Select the details to include. Empty values are skipped automatically.",
                    onCancel = { linkDesignPage(ctx) },
                ) { chosen ->
                    val wanted = StreamFormatFields.all.filterIndexed { index, _ -> chosen.getOrElse(index) { false } }
                        .map { it.id }.toSet()
                    val existingOrder = format.fields.filter { it in wanted }
                    val newlySelected = StreamFormatFields.all.map { it.id }.filter { it in wanted && it !in existingOrder }
                    Config.update {
                        streamFormat.fields = (existingOrder + newlySelected).toMutableList()
                        streamFormat.designPreset = "CUSTOM"
                        streamFormat.enabled = true
                    }
                    linkDesignPage(ctx)
                }
            })
            addCardRow(ctx, colors, card, valueRow(
                ctx, colors, "Field order", "Drag fields into the order they should appear", "Arrange",
            ) { streamFormatReorderDialog(ctx) })
            val separatorLabels = listOf("Dot ·", "Bar |", "Bullet •", "Dash —", "Slash /", "New line")
            val separators = listOf(" · ", " | ", " • ", " — ", " / ", "\n")
            val separatorIndex = separators.indexOf(format.separator).takeIf { it >= 0 } ?: 0
            addCardRow(ctx, colors, card, controlBlock(
                ctx, colors, "Separator", separatorLabels[separatorIndex], "Text placed between each detail.",
                choiceChipGrid(ctx, colors, separatorLabels, setOf(separatorIndex), columns = 3) { index ->
                    Config.update { streamFormat.separator = separators[index]; streamFormat.designPreset = "CUSTOM" }
                    linkDesignPage(ctx)
                },
            ))
            addCardRow(ctx, colors, card, switchRow(
                ctx, colors, "Show field names", "For example, Quality: 4K instead of 4K", format.showLabels,
            ) { enabled ->
                Config.update { streamFormat.showLabels = enabled; streamFormat.designPreset = "CUSTOM" }
                linkDesignPage(ctx)
            })
            addCardRow(ctx, colors, card, valueRow(
                ctx, colors, "Copy design", "Copy this layout as portable JSON", "Copy",
            ) { copyDesign(ctx) })
            addCardRow(ctx, colors, card, valueRow(
                ctx, colors, "Paste design", "Import a previously copied layout", "Paste",
            ) { pasteDesign(ctx) })
            addCardRow(ctx, colors, card, valueRow(
                ctx, colors, "Reset custom layout", "Restore the recommended custom field set", "Reset",
            ) {
                confirm(
                    ctx, "Reset link design?",
                    "This restores the default field order and separator. The Simple, Detailed and Compact presets are unchanged.",
                    onCancel = { linkDesignPage(ctx) },
                ) {
                    Config.update {
                        streamFormat = StreamFormatSettings(
                            enabled = true,
                            designPreset = "CUSTOM",
                        )
                    }
                    linkDesignPage(ctx)
                }
            })
            body.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) })
        }
    }

    private fun copyDesign(ctx: Context) {
        val format = Config.snapshot().streamFormat.copy(designPreset = "CUSTOM", enabled = true)
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            toast(ctx, "Clipboard is unavailable.")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("MultiStremio link design", Json.toJson(format)))
        toast(ctx, "Link design copied.")
    }

    private fun pasteDesign(ctx: Context) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val payload = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (payload.isNullOrBlank()) {
            toast(ctx, "Clipboard is empty.")
            return
        }
        val imported = runCatching { Json.parse<StreamFormatSettings>(payload) }.getOrNull()
        if (imported == null) {
            toast(ctx, "That text is not a MultiStremio link design.")
            return
        }
        val safe = Settings(streamFormat = imported).sanitized().streamFormat.apply {
            enabled = true
            designPreset = "CUSTOM"
        }
        Config.update { streamFormat = safe }
        toast(ctx, "Link design imported.")
        linkDesignPage(ctx)
    }

    /** Drag selected information blocks into the exact order wanted in the link list. */
    private fun streamFormatReorderDialog(ctx: Context) {
        val format = Config.snapshot().streamFormat
        val orderedIds = format.fields.distinct()
            .filter { id -> StreamFormatFields.all.any { it.id == id } }
            .toMutableList()
        if (orderedIds.size < 2) {
            toast(ctx, "Choose at least two fields before reordering.")
            linkDesignPage(ctx)
            return
        }

        val titles = StreamFormatFields.all.associate { it.id to it.title }
        val colors = palette(ctx)
        val root = shell(
            ctx,
            "Arrange link details",
            "Drag the grip or use the arrows. The first field appears first. Tap Save when finished.",
        )
        val scroll = ScrollView(ctx).apply { clipToPadding = false; tag = "settings.scroll" }
        val list = vertical(ctx)
        fun refreshNumbers() {
            for (position in 0 until list.childCount) {
                val item = list.getChildAt(position) as? LinearLayout ?: continue
                val id = item.tag as? String ?: continue
                val description = item.getChildAt(1) as? LinearLayout ?: continue
                val title = description.getChildAt(0) as? TextView ?: continue
                title.text = "${position + 1}. ${titles[id].orEmpty()}"
            }
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        orderedIds.forEachIndexed { index, id ->
            val handle = text(ctx, "☰", 19f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 3), 0, dp(ctx, 7), 0)
                contentDescription = "Drag to reorder ${titles[id].orEmpty()}"
                minWidth = dp(ctx, 34)
            }
            val detail = StreamFormatFields.all.first { it.id == id }.description
            val description = vertical(ctx).apply {
                addView(text(ctx, "${index + 1}. ${titles[id].orEmpty()}", 13.5f, colors.primary, true))
                addView(text(ctx, detail, 10.5f, colors.secondary).apply { setPadding(0, dp(ctx, 2), 0, 0) })
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 6), dp(ctx, 7))
                background = rounded(ctx, colors.background, colors.outline)
                tag = id
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 34), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(description, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val controls = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val up = text(ctx, "↑", 15f, colors.secondary, true).apply {
                gravity = Gravity.CENTER
                minimumWidth = dp(ctx, 28); minimumHeight = dp(ctx, 28)
                contentDescription = "Move ${titles[id].orEmpty()} up"
                background = ripple(ctx, colors.background, colors)
                setOnClickListener {
                    val current = list.indexOfChild(item)
                    if (current > 0) {
                        list.removeView(item); list.addView(item, current - 1); refreshNumbers()
                    }
                }
            }
            val down = text(ctx, "↓", 15f, colors.secondary, true).apply {
                gravity = Gravity.CENTER
                minimumWidth = dp(ctx, 28); minimumHeight = dp(ctx, 28)
                contentDescription = "Move ${titles[id].orEmpty()} down"
                background = ripple(ctx, colors.background, colors)
                setOnClickListener {
                    val current = list.indexOfChild(item)
                    if (current in 0 until list.childCount - 1) {
                        list.removeView(item); list.addView(item, current + 1); refreshNumbers()
                    }
                }
            }
            controls.addView(up); controls.addView(down)
            item.addView(controls)
            list.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 4) })
            installDragReordering(ctx, handle, item, list, scroll, colors) { refreshNumbers() }
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(120, orderedIds.size * 58))),
        ))
        addFooter(ctx, root, "Save order", {
            val order = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
            if (order.size == orderedIds.size && order.toSet() == orderedIds.toSet()) {
                Config.update {
                    streamFormat.fields = order.toMutableList()
                    streamFormat.designPreset = "CUSTOM"
                    streamFormat.enabled = true
                }
                linkDesignPage(ctx)
            } else {
                toast(ctx, "Could not save the field order. Please try again.")
            }
        }, "Cancel", { linkDesignPage(ctx) })
        SettingsDialogHost.show(ctx, "link-design:reorder", root)
    }
}
