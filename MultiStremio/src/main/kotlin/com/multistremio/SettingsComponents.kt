package com.multistremio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/** Share of the screen height a settings page may use before its content scrolls. */
internal const val PAGE_MAX_HEIGHT_FRACTION = 0.62f

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

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

/** A titled row with a switch on the right. The whole row toggles. */
internal fun switchRow(
    ctx: Context,
    colors: Palette,
    title: String,
    summary: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
): View {
    val toggle = Switch(ctx).apply {
        isChecked = checked
        isFocusable = true
        contentDescription = title
        thumbTintList = ColorStateList.valueOf(if (checked) colors.accent else colors.secondary)
        trackTintList = ColorStateList.valueOf(withAlpha(if (checked) colors.accent else colors.secondary, 90))
        setOnCheckedChangeListener { _, value -> onToggle(value) }
    }
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 56)
        setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        background = plainRipple(ctx, colors)
        isClickable = true
        isFocusable = true
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
    background = plainRipple(ctx, colors)
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
        if (!value.isNullOrBlank()) addView(text(ctx, value, 12f, colors.secondary))
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
    orientation = LinearLayout.HORIZONTAL
    setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
    background = GradientDrawable().apply {
        setColor(withAlpha(colors.secondary, 30))
        cornerRadius = dp(ctx, 12).toFloat()
    }
    options.forEachIndexed { index, label ->
        val selected = index == selectedIndex
        addView(text(ctx, label, 12.5f, if (selected) colors.accent else colors.secondary, selected).apply {
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 40)
            maxLines = 1
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
            background = if (selected) {
                GradientDrawable().apply {
                    setColor(colors.background)
                    cornerRadius = dp(ctx, 10).toFloat()
                    setStroke(dp(ctx, 1), colors.accent)
                }
            } else plainRipple(ctx, colors)
            isClickable = true
            isFocusable = true
            contentDescription = if (selected) "$label, selected" else label
            setOnClickListener { onSelect(index) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
}
