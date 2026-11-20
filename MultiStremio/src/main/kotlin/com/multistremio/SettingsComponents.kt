package com.multistremio

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Building blocks for the redesigned settings pages. They are plain code-built views, like the
 * rest of [UiKit], and read every color from [palette] so they follow the CloudStream theme.
 */

/** A [ScrollView] that grows with its content up to [maxHeightPx], then scrolls. */
internal class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}

/** Scroll container that asks its owner to bind another page before the user reaches the end. */
internal class IncrementalScrollView(
    ctx: Context,
    private val prefetchPx: Int,
    private val onNearEnd: () -> Unit,
) : ScrollView(ctx) {
    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        requestMoreIfNeeded()
    }

    fun requestMoreIfNeeded() {
        post {
            val content = getChildAt(0) ?: return@post
            if (scrollY + height >= content.height - prefetchPx) onNearEnd()
        }
    }
}

/** Share of the screen height a settings page may use before its content scrolls. */
internal const val PAGE_MAX_HEIGHT_FRACTION = 0.62f

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Brief in-app undo action for destructive changes. */
internal fun showUndoSnackbar(ctx: Context, message: String, undo: () -> Unit) {
    var current = ctx
    val visited = HashSet<Context>()
    while (visited.add(current) && current !is android.app.Activity && current is ContextWrapper) {
        current = current.baseContext
    }
    val activity = current as? android.app.Activity ?: return
    val colors = palette(ctx)
    val popup = PopupWindow(ctx).apply {
        contentView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            background = rounded(ctx, colors.primary, colors.primary)
            addView(text(ctx, message, 13f, colors.background), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(text(ctx, "UNDO", 12f, colors.accent, true).apply {
                isFocusable = true
                contentDescription = "Undo $message"
                setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
                setOnClickListener { dismiss(); undo() }
            })
        }
        width = minOf(dp(ctx, 420), ctx.resources.displayMetrics.widthPixels - dp(ctx, 32))
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isFocusable = true
        elevation = dp(ctx, 8).toFloat()
    }
    runCatching { popup.showAtLocation(activity.window.decorView, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 24)) }
        .onSuccess { settingsMainHandler.postDelayed({ runCatching { popup.dismiss() } }, 10_000) }
}

/**
 * Shows a scrolling settings page in the shared dialog host. [build] fills the page body; call
 * the same function again to rebuild after a change (scroll position is kept by the host).
 */
internal fun settingsPage(
    ctx: Context,
    key: String,
    title: String,
    description: String?,
    back: (() -> Unit)?,
    showRefreshNotice: Boolean = false,
    build: (body: LinearLayout, colors: Palette) -> Unit,
) {
    // A search routed here from the hub is meaningless on a page without a search box. Consume it
    // so it cannot leak into the next searchable menu.
    SettingsDialogHost.consumeNextMenuSearch()
    val colors = palette(ctx)
    val root = shell(ctx, title, description)
    if (showRefreshNotice) HomeRefreshNotice.addTo(ctx, root)
    val body = vertical(ctx)
    build(body, colors)
    val maxHeight = (ctx.resources.displayMetrics.heightPixels * PAGE_MAX_HEIGHT_FRACTION).toInt()
    val scroll = MaxHeightScrollView(ctx, maxHeight).apply {
        isFillViewport = false
        tag = "settings.scroll"
        addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
    }
    root.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
    addFooter(ctx, root, if (back != null) "Back" else "Close", {
        if (back != null) back() else SettingsDialogHost.dismiss()
    })
    SettingsDialogHost.show(ctx, key, root, back)
}

/** Small all-caps label above a group of rows. */
internal fun sectionLabel(ctx: Context, colors: Palette, label: String, danger: Boolean = false): TextView =
    text(ctx, label.uppercase(), 10f, if (danger) colors.danger else colors.accent, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(ctx, 8), dp(ctx, 14), 0, dp(ctx, 6))
    }

/** A rounded card that holds rows separated by hairlines. */
internal fun settingsCard(ctx: Context, colors: Palette): LinearLayout =
    vertical(ctx).apply { background = rounded(ctx, withAlpha(colors.accent, 8), colors.outline) }

/** Adds [row] to [card], with a divider above it unless it is the first row. */
internal fun addCardRow(ctx: Context, colors: Palette, card: LinearLayout, row: View) {
    if (card.childCount > 0) {
        card.addView(View(ctx).apply {
            setBackgroundColor(colors.outline)
        }, LinearLayout.LayoutParams(MATCH, dp(ctx, 1)))
    }
    card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
}

private fun plainRipple(ctx: Context, colors: Palette, radiusDp: Int = 10): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(ctx, radiusDp).toFloat()
    }
    return RippleDrawable(ColorStateList.valueOf(colors.ripple), null, mask)
}

private fun focusAwareBackground(ctx: Context, colors: Palette, normal: Drawable): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, colors.background).apply {
            setStroke(dp(ctx, 2), colors.accent)
        })
        addState(intArrayOf(), normal)
    }

/** A titled row with a switch on the right. The whole row toggles. */
internal fun switchRow(
    ctx: Context,
    colors: Palette,
    title: String,
    summary: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
): View {
    lateinit var row: LinearLayout
    val toggle = Switch(ctx).apply {
        isChecked = checked
        isFocusable = true
        contentDescription = "$title, ${if (checked) "on" else "off"}"
        thumbTintList = ColorStateList.valueOf(if (checked) colors.accent else colors.secondary)
        trackTintList = ColorStateList.valueOf(withAlpha(if (checked) colors.accent else colors.secondary, 90))
        setOnCheckedChangeListener { _, value ->
            thumbTintList = ColorStateList.valueOf(if (value) colors.accent else colors.secondary)
            trackTintList = ColorStateList.valueOf(withAlpha(if (value) colors.accent else colors.secondary, 90))
            contentDescription = "$title, ${if (value) "on" else "off"}"
            row.contentDescription = "$title, ${if (value) "on" else "off"}"
            onToggle(value)
        }
    }
    row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 56)
        setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        background = focusAwareBackground(ctx, colors, plainRipple(ctx, colors))
        isClickable = true
        isFocusable = true
        contentDescription = "$title, ${if (checked) "on" else "off"}"
        setOnClickListener { toggle.toggle() }
        val labels = vertical(ctx).apply {
            addView(text(ctx, title, 14f, colors.primary, true))
            if (!summary.isNullOrBlank()) {
                addView(text(ctx, summary, 11.5f, colors.secondary).apply { setPadding(0, dp(ctx, 2), 0, 0) })
            }
        }
        addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(toggle, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    return row
}

/** A tappable row that shows a current value and opens a longer picker. */
internal fun valueRow(
    ctx: Context,
    colors: Palette,
    title: String,
    summary: String?,
    value: String,
    onClick: () -> Unit,
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(ctx, 56)
    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
    background = focusAwareBackground(ctx, colors, plainRipple(ctx, colors))
    isClickable = true
    isFocusable = true
    contentDescription = "$title, $value"
    setOnClickListener { onClick() }
    val labels = vertical(ctx).apply {
        addView(text(ctx, title, 14f, colors.primary, true))
        if (!summary.isNullOrBlank()) {
            addView(text(ctx, summary, 11.5f, colors.secondary).apply { setPadding(0, dp(ctx, 2), 0, 0) })
        }
    }
    addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
    addView(text(ctx, value, 13f, colors.accent, true).apply {
        tag = "value-row:$title"
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 8), 0, dp(ctx, 4), 0)
    })
    addView(text(ctx, "›", 22f, colors.secondary).apply { gravity = Gravity.CENTER })
}

/**
 * A title with a live value on the right and [content] (usually a [segmentedControl]) below it.
 * Use it for a choice with a handful of options so no popup is needed.
 */
internal fun controlBlock(
    ctx: Context,
    colors: Palette,
    title: String,
    value: String?,
    hint: String?,
    content: View,
): View = vertical(ctx).apply {
    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(ctx, title, 14f, colors.primary, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!value.isNullOrBlank()) addView(text(ctx, value, 12f, colors.secondary).apply {
            tag = "control-value:$title"
        })
    }
    addView(header)
    if (!hint.isNullOrBlank()) {
        addView(text(ctx, hint, 11.5f, colors.secondary).apply { setPadding(0, dp(ctx, 2), 0, 0) })
    }
    addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 8) })
}

/**
 * Equal-width segments for 2 to 4 short options. [selectedIndex] may be -1 for "none". Each
 * segment is focusable and announces whether it is selected.
 */
internal fun segmentedControl(
    ctx: Context,
    colors: Palette,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    val largeFont = ctx.resources.displayMetrics.scaledDensity / ctx.resources.displayMetrics.density >= 1.2f
    val columns = if (!largeFont) options.size.coerceAtLeast(1) else when (options.size) {
        4, 3 -> 2
        2 -> if (options.any { it.length > 12 }) 1 else 2
        else -> 1
    }
    setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
    background = GradientDrawable().apply {
        setColor(withAlpha(colors.secondary, 30))
        cornerRadius = dp(ctx, 12).toFloat()
    }
    options.chunked(columns).forEachIndexed { rowIndex, rowOptions ->
        val optionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (rowIndex > 0) setPadding(0, dp(ctx, 3), 0, 0)
        }
        rowOptions.forEach { label ->
            val index = options.indexOf(label)
            val selected = index == selectedIndex
            val option = text(ctx, label, 12.5f, if (selected) colors.accent else colors.secondary, selected).apply {
                gravity = Gravity.CENTER
                minHeight = dp(ctx, 40)
                maxLines = 2
                tag = "segmented-option:$label"
                setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
                val normalBackground = if (selected) {
                    GradientDrawable().apply {
                        setColor(colors.background)
                        cornerRadius = dp(ctx, 10).toFloat()
                        setStroke(dp(ctx, 1), colors.accent)
                    }
                } else plainRipple(ctx, colors)
                background = focusAwareBackground(ctx, colors, normalBackground)
                isClickable = true
                isFocusable = true
                contentDescription = if (selected) "$label, selected" else label
                setOnClickListener { onSelect(index) }
            }
            optionRow.addView(option, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (optionRow.childCount > 0) marginStart = dp(ctx, 3)
            })
        }
        repeat(columns - rowOptions.size) {
            optionRow.addView(View(ctx), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (optionRow.childCount > 0) marginStart = dp(ctx, 3)
            })
        }
        addView(optionRow, LinearLayout.LayoutParams(MATCH, WRAP))
    }
}

internal fun updateSegmentedControl(
    ctx: Context,
    colors: Palette,
    control: View,
    options: List<String>,
    selectedIndex: Int,
) {
    fun visit(view: View) {
        if (view is TextView) {
            val label = (view.tag as? String)?.takeIf { it.startsWith("segmented-option:") }
                ?.removePrefix("segmented-option:")
            val index = label?.let(options::indexOf) ?: -1
            if (index >= 0) {
                val selected = index == selectedIndex
                view.isSelected = selected
                view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                view.setTextColor(if (selected) colors.accent else colors.secondary)
                view.contentDescription = if (selected) "$label, selected" else label
                view.background = focusAwareBackground(ctx, colors, if (selected) {
                    GradientDrawable().apply {
                        setColor(colors.background)
                        cornerRadius = dp(ctx, 10).toFloat()
                        setStroke(dp(ctx, 1), colors.accent)
                    }
                } else plainRipple(ctx, colors))
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
    }
    visit(control)
}

internal fun updateControlValue(root: View, title: String, value: String) {
    fun visit(view: View): Boolean {
        if (view is TextView && view.tag == "control-value:$title") {
            view.text = value
            return true
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (visit(view.getChildAt(index))) return true
        }
        return false
    }
    visit(root)
}

internal fun updateValueRow(root: View, title: String, value: String) {
    fun visit(view: View): Boolean {
        if (view is TextView && view.tag == "value-row:$title") {
            view.text = value
            view.contentDescription = "$title, $value"
            return true
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (visit(view.getChildAt(index))) return true
        }
        return false
    }
    visit(root)
}

/**
 * Compact, wrapping choice chips for option sets that are too wide for a segmented control.
 * Every chip remains a 44dp touch target and exposes its selected state to accessibility services.
 */
internal fun choiceChipGrid(
    ctx: Context,
    colors: Palette,
    options: List<String>,
    selectedIndices: Set<Int>,
    columns: Int = 3,
    onSelect: (Int) -> Unit,
): LinearLayout = vertical(ctx).apply {
    val safeColumns = columns.coerceIn(1, 4)
    options.forEachIndexed { index, label ->
        val rowIndex = index / safeColumns
        val columnIndex = index % safeColumns
        if (columnIndex == 0) {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = "choice-chip-row:$rowIndex"
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                if (rowIndex > 0) topMargin = dp(ctx, 6)
            })
        }
        val row = getChildAt(childCount - 1) as LinearLayout
        val selected = index in selectedIndices
        val chip = text(ctx, label, 12f, if (selected) colors.accent else colors.primary, selected).apply {
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 48)
            tag = "choice-chip:$index"
            setPadding(dp(ctx, 6), dp(ctx, 7), dp(ctx, 6), dp(ctx, 7))
            val normalBackground = GradientDrawable().apply {
                setColor(if (selected) withAlpha(colors.accent, 22) else colors.background)
                cornerRadius = dp(ctx, 10).toFloat()
                setStroke(dp(ctx, 1), if (selected) colors.accent else colors.outline)
            }
            background = focusAwareBackground(ctx, colors, normalBackground)
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = if (selected) "$label, selected" else label
            setOnClickListener { onSelect(index) }
        }
        row.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginEnd = dp(ctx, if (columnIndex < safeColumns - 1) 6 else 0)
        })
    }
    val lastRow = getChildAt(childCount - 1) as? LinearLayout
    val remainder = options.size % safeColumns
    if (lastRow != null && remainder != 0) {
        repeat(safeColumns - remainder) {
            lastRow.addView(View(ctx), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = dp(ctx, 6)
            })
        }
    }
}

internal fun updateChoiceChipGrid(
    ctx: Context,
    colors: Palette,
    grid: View,
    options: List<String>,
    selectedIndices: Set<Int>,
) {
    fun visit(view: View) {
        if (view is TextView) {
            val index = (view.tag as? String)?.removePrefix("choice-chip:")?.toIntOrNull()
            if (index != null && index in options.indices) {
                val selected = index in selectedIndices
                view.isSelected = selected
                view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                view.setTextColor(if (selected) colors.accent else colors.primary)
                view.contentDescription = if (selected) "${options[index]}, selected" else options[index]
                val normal = GradientDrawable().apply {
                    setColor(if (selected) withAlpha(colors.accent, 22) else colors.background)
                    cornerRadius = dp(ctx, 10).toFloat()
                    setStroke(dp(ctx, 1), if (selected) colors.accent else colors.outline)
                }
                view.background = focusAwareBackground(ctx, colors, normal)
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
    }
    visit(grid)
}
