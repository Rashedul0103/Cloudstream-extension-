package com.multistremio

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.concurrent.thread

internal object FormatterSettingsScreen {
    fun show(ctx: Context) = streamFormatterMenu(ctx)

    private fun streamFormatterMenu(ctx: Context) {
        val format = Config.settings.streamFormat
        val fields = StreamFormatFields.all
        val validFieldIds = fields.map { it.id }.toSet()
        val selected = format.fields.filter { it in validFieldIds }.toSet()
        val separatorLabels = listOf(
            "Middle dot   ·",
            "Vertical bar   |",
            "Bullet   •",
            "Long dash   —",
            "Slash   /",
            "New line",
        )
        val separators = listOf(" · ", " | ", " • ", " — ", " / ", "\n")
        val separatorIndex = separators.indexOf(format.separator).takeIf { it >= 0 } ?: 0
        val rows = listOf(
            "Custom layout: ${onOff(format.enabled)}",
            "Choose information to show (${selected.size} selected)",
            "Reorder visible information",
            "Separator: ${separatorLabels[separatorIndex]}",
            "Show full field names: ${onOff(format.showLabels)}",
            "Preview your design",
            "Reset to recommended layout",
        )
        menu(
            ctx,
            "Stream formatter",
            rows,
            { MainMenuSettingsScreen.show(ctx) },
            "Design how links look in the stream list. No codes or templates are needed. Choose the details, put them in your preferred order, and preview the result.",
            listOf(
                "Turn your custom design on or off. Off uses the normal MultiStremio labels.",
                "Pick details such as quality, codec, language and file size.",
                "Drag the grip to reorder. No long-press is needed; swipe the detail text to scroll.",
                "Choose what appears between details.",
                "Use names such as “Quality:” and “Video codec:” before each value.",
                "See an example using a made-up movie; no addon is contacted.",
                "Restore all recommended fields and spacing.",
            ),
        ) { which ->
            when (which) {
                0 -> {
                    Config.update { streamFormat.enabled = !streamFormat.enabled }
                    streamFormatterMenu(ctx)
                }
                1 -> multi(
                    ctx,
                    "Information to display",
                    fields.map { "${it.title} — ${it.description}" },
                    fields.map { it.id in selected },
                    onCancel = { streamFormatterMenu(ctx) },
                ) { chosen ->
                    val wanted = fields.filterIndexed { index, _ -> chosen.getOrElse(index) { false } }.map { it.id }.toSet()
                    val existingOrder = format.fields.filter { it in wanted }
                    val newlySelected = fields.map { it.id }.filter { it in wanted && it !in existingOrder }
                    Config.update { streamFormat.fields = (existingOrder + newlySelected).toMutableList() }
                    streamFormatterMenu(ctx)
                }
                2 -> streamFormatReorderDialog(ctx)
                3 -> choose(ctx, "Separate each detail with", separatorLabels, separatorIndex, onCancel = { streamFormatterMenu(ctx) }) {
                    Config.update { streamFormat.separator = separators[it] }
                    streamFormatterMenu(ctx)
                }
                4 -> {
                    Config.update { streamFormat.showLabels = !streamFormat.showLabels }
                    streamFormatterMenu(ctx)
                }
                5 -> {
                    val preview = StreamLabels.preview(format)
                    menu(
                        ctx,
                        "Your stream-name preview",
                        listOf(preview),
                        { streamFormatterMenu(ctx) },
                        "Example only. Real links show whichever information each addon provides. Missing details are automatically skipped.",
                    ) { }
                }
                6 -> confirm(
                    ctx,
                    "Reset stream formatter?",
                    "This restores the recommended field order, middle-dot separator and compact labels. Your custom layout will be turned off.",
                    onCancel = { streamFormatterMenu(ctx) },
                ) {
                    Config.update { streamFormat = StreamFormatSettings() }
                    streamFormatterMenu(ctx)
                }
            }
        }
    }

    /** Drag selected information blocks into the exact order wanted in the stream list. */
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
            "Arrange stream details",
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
            val handle = text(ctx, "☰", 22f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 4), 0, dp(ctx, 10), 0)
                contentDescription = "Drag to reorder ${titles[id].orEmpty()}"
            }
            val detail = StreamFormatFields.all.first { it.id == id }.description
            val description = vertical(ctx).apply {
                addView(text(ctx, "${index + 1}. ${titles[id].orEmpty()}", 14f, colors.primary, true))
                addView(text(ctx, detail, 11f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 3), 0, 0)
                })
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 7), dp(ctx, 12))
                background = rounded(ctx, colors.background, colors.outline)
                tag = id
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 48)))
                addView(description, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Drag remains the fastest option; buttons make ordering possible without a gesture.
            val moveControls = vertical(ctx).apply {
                gravity = Gravity.CENTER
                val up = text(ctx, "↑", 19f, colors.secondary, true).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Move ${titles[id].orEmpty()} up"
                    minimumWidth = dp(ctx, 32)
                    minimumHeight = dp(ctx, 30)
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
                val down = text(ctx, "↓", 19f, colors.secondary, true).apply {
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
            item.addView(moveControls, LinearLayout.LayoutParams(dp(ctx, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 7) })
            installDragReordering(ctx, handle, item, list, scroll, colors) { refreshNumbers() }
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(100, orderedIds.size * 70))),
        ))
        addFooter(ctx, root, "Save row order", {
            val order = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
            if (order.size == orderedIds.size && order.toSet() == orderedIds.toSet()) {
                Config.update { streamFormat.fields = order.toMutableList() }
                        streamFormatterMenu(ctx)
            } else {
                toast(ctx, "Could not save the format order. Please try again.")
            }
        }, "Cancel", {
                streamFormatterMenu(ctx)
        })
        SettingsDialogHost.show(ctx, "formatter:reorder", root)
    }
}
