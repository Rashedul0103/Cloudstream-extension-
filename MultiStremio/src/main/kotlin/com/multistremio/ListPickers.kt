package com.multistremio

import android.content.Context
import com.lagradost.cloudstream3.utils.SubtitleHelper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class StringListOption(val value: String, val label: String = value)

internal object SettingsChoiceLists {
    val languages: List<StringListOption> = Langs.supportedCodes().map { code ->
        val name = SubtitleHelper.fromTagToLanguageName(code)
            ?: SubtitleHelper.fromTwoLettersToLanguage(code)
            ?: SubtitleHelper.fromThreeLettersToLanguage(code)
            ?: code.uppercase()
        StringListOption(code, "$name ($code)")
    }
    val codecs = listOf("HEVC", "AV1", "AVC").map { StringListOption(it) }
    val audioFormats = listOf("Atmos", "TrueHD", "DTS-HD", "DD+", "AAC").map { StringListOption(it) }
}

internal fun stringListPicker(
    ctx: Context,
    title: String,
    description: String,
    options: List<StringListOption>,
    initial: List<String>,
    onCancel: (() -> Unit)? = null,
    orderSensitive: Boolean = false,
    onSave: (MutableList<String>) -> Unit,
) {
    val colors = palette(ctx)
    val selected = initial.distinct().toMutableList()
    var currentOptions = (options + selected.filter { value -> options.none { it.value.equals(value, true) } }
        .map { StringListOption(it) }).distinctBy { it.value.lowercase() }
    val root = shell(ctx, title, description)
    val list = vertical(ctx)
    val scroll = ScrollView(ctx).apply {
        tag = "settings.scroll"
        addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    fun render(query: String = "") {
        list.removeAllViews()
        val matching = currentOptions.filter { it.label.contains(query, true) || it.value.contains(query, true) }
        if (matching.isEmpty()) {
            list.addView(text(ctx, "No matching options.", 13f, colors.secondary).apply {
                setPadding(dp(ctx, 10), dp(ctx, 12), dp(ctx, 10), dp(ctx, 10))
            })
        }
        matching.forEach { option ->
            val check = CheckBox(ctx).apply {
                text = option.label
                textSize = 13.5f
                setTextColor(colors.primary)
                isChecked = selected.any { it.equals(option.value, true) }
                buttonTintList = android.content.res.ColorStateList.valueOf(colors.accent)
                setPadding(dp(ctx, 7), dp(ctx, 4), dp(ctx, 7), dp(ctx, 4))
                minHeight = dp(ctx, 42)
                setOnCheckedChangeListener { _, checked ->
                    selected.removeAll { it.equals(option.value, true) }
                    if (checked) selected.add(option.value)
                }
            }
            list.addView(check)
        }
    }

    val search = searchBox(ctx, "Search options", colors, ::render)
    root.addView(search, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(ctx, 42),
    ).apply { bottomMargin = dp(ctx, 5) })
    render()
    root.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        minOf(dp(ctx, 350), dp(ctx, maxOf(100, currentOptions.size * 48))),
    ))

    val actions = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    fun action(label: String, callback: () -> Unit) = text(ctx, label, 12f, colors.accent, true).apply {
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        background = ripple(ctx, colors.background, colors)
        setOnClickListener { callback() }
    }
    actions.addView(action("＋ Add custom", {
        prompt(ctx, "Add custom value", "Enter a language code or custom value.", "", false, onCancel = {
            SettingsDialogHost.show(ctx, "string-picker:$title", root, onCancel)
        }) { raw ->
            val value = raw.trim()
            if (value.isNotEmpty() && currentOptions.none { it.value.equals(value, true) }) {
                currentOptions = currentOptions + StringListOption(value)
                selected.add(value)
            }
            render(search.text.toString())
            SettingsDialogHost.show(ctx, "string-picker:$title", root, onCancel)
        }
    }))
    if (orderSensitive) {
        actions.addView(action("Reorder selected", {
            if (selected.size < 2) {
                toast(ctx, "Select at least two values to arrange their priority.")
            } else {
                reorderStringValues(ctx, title, selected, {
                    SettingsDialogHost.show(ctx, "string-picker:$title", root, onCancel)
                }) { reordered ->
                    selected.clear()
                    selected.addAll(reordered)
                    SettingsDialogHost.show(ctx, "string-picker:$title", root, onCancel)
                }
            }
        }))
    }
    root.addView(actions)
    addFooter(ctx, root, "Save", {
        onSave(selected.toMutableList())
    }, "Cancel", {
        if (onCancel != null) onCancel() else SettingsDialogHost.back()
    })
    SettingsDialogHost.show(ctx, "string-picker:$title", root, onCancel)
}

private fun reorderStringValues(
    ctx: Context,
    title: String,
    initial: List<String>,
    onCancel: (() -> Unit)?,
    onSave: (List<String>) -> Unit,
) {
    val order = initial.toMutableList()
    val colors = palette(ctx)
    val root = shell(ctx, "Arrange $title", "The first value has the highest priority. Drag the grip or use the arrow controls.")
    val scroll = ScrollView(ctx)
    val list = vertical(ctx)
    fun refresh() {
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? LinearLayout ?: continue
            val label = row.getChildAt(1) as? TextView ?: continue
            label.text = "${index + 1}. ${row.tag as? String ?: ""}"
        }
    }
    order.forEachIndexed { index, value ->
        val handle = text(ctx, "☰", 18f, colors.accent, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Drag to reorder $value"
        }
        val label = text(ctx, "${index + 1}. $value", 13.5f, colors.primary, true)
        lateinit var row: LinearLayout
        row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 5), dp(ctx, 6))
            background = rounded(ctx, colors.background, colors.outline)
            tag = value
            addView(handle, LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 40)))
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val controls = vertical(ctx).apply {
                val up = text(ctx, "↑", 16f, colors.secondary, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        val position = list.indexOfChild(row)
                        if (position > 0) {
                            list.removeView(row)
                            list.addView(row, position - 1)
                            refresh()
                        }
                    }
                }
                val down = text(ctx, "↓", 16f, colors.secondary, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        val position = list.indexOfChild(row)
                        if (position in 0 until list.childCount - 1) {
                            list.removeView(row)
                            list.addView(row, position + 1)
                            refresh()
                        }
                    }
                }
                addView(up)
                addView(down)
            }
            addView(controls, LinearLayout.LayoutParams(dp(ctx, 32), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        list.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 4) })
        installDragReordering(ctx, handle, row, list, scroll, colors) { refresh() }
    }
    scroll.addView(list, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ))
    root.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        minOf(dp(ctx, 350), dp(ctx, maxOf(100, order.size * 52))),
    ))
    addFooter(ctx, root, "Save order", {
        onSave((0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String })
    }, "Back", {
        if (onCancel != null) onCancel() else SettingsDialogHost.back()
    })
    SettingsDialogHost.show(ctx, "string-reorder:$title", root, onCancel)
}
