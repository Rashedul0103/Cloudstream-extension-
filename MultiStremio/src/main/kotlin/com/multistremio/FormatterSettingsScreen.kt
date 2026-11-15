package com.multistremio

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object FormatterSettingsScreen {
    fun show(ctx: Context) = streamFormatterMenu(ctx)

    private fun streamFormatterMenu(ctx: Context) {
        val format = Config.settings.streamFormat
        val fields = StreamFormatFields.all
        val validFieldIds = fields.map { it.id }.toSet()
        val selected = format.fields.filter { it in validFieldIds }.toSet()
        val separatorLabels = listOf("Middle dot   ·", "Vertical bar   |", "Bullet   •", "Long dash   —", "Slash   /", "New line")
        val separators = listOf(" · ", " | ", " • ", " — ", " / ", "\n")
        val separatorIndex = separators.indexOf(format.separator).takeIf { it >= 0 } ?: 0
        val again = { streamFormatterMenu(ctx) }
        val preview = StreamLabels.preview(format)
        val items = listOf(
            SettingItem(
                "Live preview",
                preview,
                SettingItemType.Value,
                valueLabel = "Example",
                searchKeywords = listOf("preview", "example", "stream name"),
            ),
            SettingItem("Choose information to show", "${selected.size} selected", SettingItemType.Value, valueLabel = "${selected.size}",
                onClick = {
                    multi(ctx, "Information to display",
                        fields.map { "${it.title} — ${it.description}" },
                        fields.map { it.id in selected }, onCancel = again) { chosen ->
                        val wanted = fields.filterIndexed { index, _ -> chosen.getOrElse(index) { false } }.map { it.id }.toSet()
                        val existingOrder = format.fields.filter { it in wanted }
                        val newlySelected = fields.map { it.id }.filter { it in wanted && it !in existingOrder }
                        updateAndReopen({ again() }) { streamFormat.fields = (existingOrder + newlySelected).toMutableList() }
                    }
                }, searchKeywords = listOf("fields", "quality", "source", "HDR", "codec", "language", "size")),
            SettingItem("Reorder visible information", "Arrange details in the order they appear", SettingItemType.Navigate,
                onClick = { streamFormatReorderDialog(ctx) },
                searchKeywords = listOf("reorder", "drag", "order", "fields")),
            SettingItem("Separator", separatorLabels[separatorIndex], SettingItemType.Value, valueLabel = separatorLabels[separatorIndex],
                onClick = {
                    choose(ctx, "Separate each detail with", separatorLabels, separatorIndex, onCancel = again) {
                        updateAndReopen({ again() }) { streamFormat.separator = separators[it] }
                    }
                }, searchKeywords = listOf("separator", "dot", "bullet", "dash", "slash", "newline")),
            SettingItem("Show full field names", onOff(format.showLabels), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { streamFormat.showLabels = !streamFormat.showLabels } },
                checked = format.showLabels,
                searchKeywords = listOf("labels", "names", "Quality:", "Video codec:")),
            SettingItem("Reset to recommended layout", "Restore the default fields, order and spacing", SettingItemType.Navigate,
                onClick = {
                    confirm(ctx, "Reset stream formatter?",
                        "This restores the recommended field order, middle-dot separator and compact labels. Your custom layout will be turned off.",
                        onCancel = again) {
                        updateAndReopen({ again() }) { streamFormat = StreamFormatSettings() }
                    }
                }, searchKeywords = listOf("reset", "default", "recommended")),
        )
        settingMenu(
            ctx,
            "Link design",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Design how links look in the link list. No codes or templates are needed. Choose the details and put them in your preferred order.",
            forceSearch = true,
        )
    }

    /** Drag selected information blocks into the exact order wanted in the link list. */
    private fun streamFormatReorderDialog(ctx: Context) {
        val format = Config.settings.streamFormat
        val orderedIds = format.fields.distinct()
            .filter { id -> StreamFormatFields.all.any { it.id == id } }
            .toMutableList()
        if (orderedIds.size < 2) {
            toast(ctx, "Choose at least two fields before reordering.")
            streamFormatterMenu(ctx)
            return
        }

        val titles = StreamFormatFields.all.associate { it.id to it.title }
        val colors = palette(ctx)
        val root = shell(
            ctx,
            "Arrange link details",
            "Drag the ☰ grip to move a field. No long-press is needed; swipe the field details to scroll. Tap Save when finished.",
        )
        val scroll = ScrollView(ctx).apply { clipToPadding = false }
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
            }
            val detail = StreamFormatFields.all.first { it.id == id }.description
            val description = vertical(ctx).apply {
                addView(text(ctx, "${index + 1}. ${titles[id].orEmpty()}", 13.5f, colors.primary, true))
                addView(text(ctx, detail, 10.5f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 6), dp(ctx, 8))
                background = rounded(ctx, colors.background, colors.outline)
                tag = id
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 40)))
                addView(description, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Drag remains the fastest option; buttons make ordering possible without a gesture.
            val moveControls = vertical(ctx).apply {
                gravity = Gravity.CENTER
                val up = text(ctx, "↑", 16f, colors.secondary, true).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Move ${titles[id].orEmpty()} up"
                    minimumWidth = dp(ctx, 32)
                    minimumHeight = dp(ctx, 28)
                    background = ripple(ctx, colors.background, colors)
                    setOnClickListener {
                        val current = list.indexOfChild(item)
                        if (current > 0) {
                            list.removeView(item)
                            list.addView(item, current - 1)
                            refreshNumbers()
                        } else {
                            toast(ctx, "This field is already first.")
                        }
                    }
                }
                val down = text(ctx, "↓", 16f, colors.secondary, true).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Move ${titles[id].orEmpty()} down"
                    minimumWidth = dp(ctx, 32)
                    minimumHeight = dp(ctx, 30)
                    background = ripple(ctx, colors.background, colors)
                    setOnClickListener {
                        val current = list.indexOfChild(item)
                        if (current in 0 until list.childCount - 1) {
                            list.removeView(item)
                            list.addView(item, current + 1)
                            refreshNumbers()
                        } else {
                            toast(ctx, "This field is already last.")
                        }
                    }
                }
                addView(up)
                addView(down)
            }
            item.addView(moveControls, LinearLayout.LayoutParams(dp(ctx, 32), ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 4) })
            installDragReordering(ctx, handle, item, list, scroll, colors) { refreshNumbers() }
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(100, orderedIds.size * 58))),
        ))
        addFooter(ctx, root, "Save row order", {
            val order = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
            if (order.size == orderedIds.size && order.toSet() == orderedIds.toSet()) {
                updateAndReopen({ streamFormatterMenu(ctx) }) { streamFormat.fields = order.toMutableList() }
            } else {
                toast(ctx, "Could not save the format order. Please try again.")
            }
        }, "Cancel", {
                streamFormatterMenu(ctx)
        })
        SettingsDialogHost.show(ctx, "formatter:reorder", root)
    }
}
