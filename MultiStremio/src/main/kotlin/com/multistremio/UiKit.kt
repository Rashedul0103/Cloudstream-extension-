package com.multistremio

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.net.URL

internal val settingsMainHandler = Handler(Looper.getMainLooper())

internal enum class SettingItemType { Navigate, Switch, Value, InlineChoice }

internal data class SettingItem(
    val title: String,
    val summary: String? = null,
    val type: SettingItemType = SettingItemType.Navigate,
    val onClick: (() -> Unit)? = null,
    val searchKeywords: List<String> = emptyList(),
    val checked: Boolean? = null,
    val valueLabel: String? = null,
    val section: String? = null,
    val onToggle: ((Boolean) -> Unit)? = null,
    val danger: Boolean = false,
    val icon: String? = null,
    val choiceLabels: List<String> = emptyList(),
    val selectedChoice: Int = -1,
    val onChoice: ((Int) -> Unit)? = null,
)

internal fun toast(ctx: Context, message: String) =
        settingsMainHandler.post { Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }

internal object HomeRefreshNotice {
    @Volatile private var pending = false
    fun mark() { pending = true }
    fun clear() { pending = false }
    fun addTo(ctx: Context, root: LinearLayout) {
        if (!pending) return
        val colors = palette(ctx)
        lateinit var banner: LinearLayout
        banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 8), dp(ctx, 9))
            background = rounded(ctx, withAlpha(colors.accent, 16), colors.accent)
            addView(text(ctx, "Refresh the home page to apply changes.", 12f, colors.primary),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(ctx, "Dismiss", 11f, colors.accent, true).apply {
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
                setOnClickListener {
                    clear()
                    (banner.parent as? ViewGroup)?.removeView(banner)
                }
            })
        }
        root.addView(banner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 10) })
    }
}

internal fun dp(ctx: Context, value: Int) =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

internal fun color(ctx: Context, attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!ctx.theme.resolveAttribute(attribute, value, true)) return fallback
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
        return if (value.resourceId != 0) {
            try { ctx.resources.getColor(value.resourceId) } catch (_: Exception) { fallback }
        } else fallback
    }

internal fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

internal fun rounded(ctx: Context, fill: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, 14).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1), stroke)
        }

internal fun ripple(ctx: Context, fill: Int, colors: Palette): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(colors.ripple), rounded(ctx, fill, colors.outline), null)

internal fun text(ctx: Context, value: String, size: Float, tint: Int, bold: Boolean = false) =
        TextView(ctx).apply {
            text = value
            textSize = size
            setTextColor(tint)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = true
        }

internal fun vertical(ctx: Context, padding: Int = 0) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (padding > 0) setPadding(padding, padding, padding, padding)
        }

internal fun shell(
    ctx: Context,
    title: String,
    description: String? = null,
    helpAction: (() -> Unit)? = null,
): LinearLayout {
        val colors = palette(ctx)
        val root = vertical(ctx, dp(ctx, 16)).apply { background = rounded(ctx, colors.background) }
        val header = vertical(ctx).apply {
            addView(text(ctx, "MULTISTREMIO  /  SETTINGS", 10f, colors.accent, true).apply {
                letterSpacing = 0.11f
            })
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(ctx, title, 20f, colors.primary, true).apply {
                    setPadding(0, dp(ctx, 4), 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                helpAction?.let { action ->
                    addView(text(ctx, "?", 16f, colors.accent, true).apply {
                        gravity = Gravity.CENTER
                        contentDescription = "Help for $title"
                        isFocusable = true
                        minimumWidth = dp(ctx, 48)
                        minimumHeight = dp(ctx, 48)
                        setPadding(dp(ctx, 11), dp(ctx, 4), dp(ctx, 11), dp(ctx, 4))
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, withAlpha(colors.accent, 14), colors.accent).apply {
                                setStroke(dp(ctx, 2), colors.accent)
                            })
                            addState(intArrayOf(), rounded(ctx, withAlpha(colors.accent, 14), colors.outline))
                        }
                        setOnClickListener { action() }
                    })
                }
            }
            addView(titleRow)
            if (!description.isNullOrBlank()) {
                addView(text(ctx, description, 12f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
                })
            }
        }
        root.addView(header)
        root.addView(View(ctx).apply {
            setBackgroundColor(colors.outline)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                topMargin = dp(ctx, 11)
                bottomMargin = dp(ctx, 10)
            }
        })
        return root
    }

internal fun addFooter(
        ctx: Context,
        root: LinearLayout,
        label: String,
        onClick: () -> Unit,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null,
    ) {
        val colors = palette(ctx)
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 7), 0, 0)
        }
        if (secondaryLabel != null && onSecondary != null) {
            footer.addView(text(ctx, secondaryLabel.uppercase(), 12f, colors.secondary, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
                background = ripple(ctx, colors.background, colors)
                setOnClickListener { onSecondary() }
            })
        }
        footer.addView(text(ctx, label.uppercase(), 12f, colors.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
            background = ripple(ctx, colors.background, colors)
            setOnClickListener { onClick() }
        })
        root.addView(footer)
    }

internal fun window(dialog: AlertDialog, ctx: Context) {
        dialog.show()
        // These settings screens have explicit Back/Cancel controls. Avoid accidental outside taps
        // dismissing an in-progress edit or silently navigating to its parent screen.
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(minOf(dp(ctx, 560), ctx.resources.displayMetrics.widthPixels - dp(ctx, 24)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * A background settings task can outlive its dialog. Hide lets the user leave that flow;
     * completion callbacks must then report via a toast instead of reopening a screen.
     */
internal fun progress(
        ctx: Context,
        title: String,
        message: String,
        onHide: () -> Unit,
    ): AlertDialog {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Hide") { _, _ -> onHide() }
            .create()
        dialog.setOnCancelListener { onHide() }
        window(dialog, ctx)
        return dialog
    }


internal fun palette(ctx: Context): Palette {
    val background = color(ctx, android.R.attr.colorBackground, Color.rgb(250, 250, 250))
    val primary = color(ctx, android.R.attr.textColorPrimary, Color.rgb(28, 28, 30))
    val secondary = color(ctx, android.R.attr.textColorSecondary, Color.rgb(112, 112, 118))
    // CloudStream skins frequently expose app-level attributes rather than Android's legacy accent.
    val accent = namedThemeColor(ctx, "colorAccent")
        ?: namedThemeColor(ctx, "colorPrimary")
        ?: color(ctx, android.R.attr.colorAccent, Color.rgb(70, 110, 230))
    val statusColors = StatusColors.forBackground(background)
    return Palette(
        background, primary, secondary, accent, withAlpha(secondary, 54), withAlpha(accent, 42),
        danger = statusColors.danger, success = statusColors.success, warning = statusColors.warning,
    )
}

internal data class Palette(
    val background: Int,
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val outline: Int,
    val ripple: Int,
    /** Destructive actions and failing status. Not taken from the theme, so it stays recognizable. */
    val danger: Int = StatusColors.LIGHT.danger,
    /** Healthy status. */
    val success: Int = StatusColors.LIGHT.success,
    /** Slow or needs-attention status. */
    val warning: Int = StatusColors.LIGHT.warning,
)

/**
 * Fixed status colors chosen for readable contrast on light and dark backgrounds. CloudStream
 * skins do not expose semantic status colors, so these are not read from the theme.
 */
internal data class StatusColors(val danger: Int, val success: Int, val warning: Int) {
    companion object {
        val LIGHT = StatusColors(0xffd32f2f.toInt(), 0xff2e7d32.toInt(), 0xffb26a00.toInt())
        val DARK = StatusColors(0xffff8a80.toInt(), 0xff81c784.toInt(), 0xffffb74d.toInt())

        /**
         * Perceived luminance below the midpoint counts as a dark background. Whole-number math
         * avoids floating-point error: the three weights sum to exactly 1000, so mid-gray
         * (128, 128, 128) is exactly on the boundary and counts as light.
         */
        fun isDark(background: Int): Boolean {
            val red = (background shr 16) and 0xff
            val green = (background shr 8) and 0xff
            val blue = background and 0xff
            return (299 * red + 587 * green + 114 * blue) < 128_000
        }

        fun forBackground(background: Int): StatusColors = if (isDark(background)) DARK else LIGHT
    }
}

private fun namedThemeColor(ctx: Context, name: String): Int? {
    val attribute = ctx.resources.getIdentifier(name, "attr", ctx.packageName)
        .takeIf { it != 0 } ?: return null
    val value = TypedValue()
    if (!ctx.theme.resolveAttribute(attribute, value, true)) return null
    if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
    if (value.resourceId != 0) {
        return runCatching { ctx.resources.getColor(value.resourceId) }.getOrNull()
    }
    return null
}

internal fun searchBox(
    ctx: Context,
    hint: String,
    colors: Palette,
    onQueryChanged: (String) -> Unit,
): EditText = EditText(ctx).apply {
    this.hint = hint
    textSize = 14f
    setTextColor(colors.primary)
    setHintTextColor(colors.secondary)
    setSingleLine(true)
    inputType = InputType.TYPE_CLASS_TEXT
    setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
    background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
    tag = "settings.search"
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onQueryChanged(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
}

private data class SettingsScreenEntry(
    val key: String,
    var root: View,
    var onBack: (() -> Unit)?,
    var scrollY: Int = 0,
    var searchText: String = "",
)

/**
 * Owns one visible settings dialog. Navigating swaps its content and keeps a small in-memory
 * back stack, so moving between menus no longer dismisses and recreates Android dialogs.
 */
internal object SettingsDialogHost {
    private val stack = mutableListOf<SettingsScreenEntry>()
    private var nextMenuSearch: String? = null

    fun setNextMenuSearch(query: String) { nextMenuSearch = query }
    fun consumeNextMenuSearch(): String = nextMenuSearch.orEmpty().also { nextMenuSearch = null }
    private var dialog: Dialog? = null
    private var container: FrameLayout? = null

    internal fun currentScreenRoot(): View? = stack.lastOrNull()?.root

    private fun findScroll(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findScroll(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findSearch(root: View): EditText? {
        if (root is EditText && root.tag == "settings.search") return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findSearch(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findFocusedDescription(root: View): CharSequence? {
        if (root.isFocused && root.contentDescription != null) return root.contentDescription
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findFocusedDescription(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findFocusedTag(root: View): Any? {
        if (root.isFocused && root.tag != null) return root.tag
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findFocusedTag(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findByDescription(root: View, description: CharSequence): View? {
        if (root.contentDescription == description) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findByDescription(root.getChildAt(index), description)?.let { return it }
        }
        return null
    }

    private fun findByTag(root: View, tag: Any): View? {
        if (root.tag == tag) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findByTag(root.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    /** Replace a screen's content while retaining its attached root and scroll container. */
    private fun updateInPlace(entry: SettingsScreenEntry, newRoot: View): Boolean {
        val oldRoot = entry.root as? LinearLayout ?: return false
        val replacementRoot = newRoot as? LinearLayout ?: return false
        val oldScroll = findScroll(oldRoot) ?: return false
        val newScroll = findScroll(replacementRoot) ?: return false
        val focusDescription = findFocusedDescription(oldRoot)
        val focusTag = findFocusedTag(oldRoot)
        val savedScrollY = entry.scrollY
        findSearch(replacementRoot)?.let { search ->
            if (search.text.toString() != entry.searchText) search.setText(entry.searchText)
        }

        val newContent = newScroll.getChildAt(0)
        if (newContent != null) {
            newScroll.removeView(newContent)
            oldScroll.removeAllViews()
            oldScroll.addView(newContent, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        val replacements = (0 until replacementRoot.childCount).map { replacementRoot.getChildAt(it) }
        oldRoot.removeAllViews()
        replacements.forEach { child ->
            val params = child.layoutParams
            replacementRoot.removeView(child)
            oldRoot.addView(if (child === newScroll) oldScroll else child, params)
        }
        oldScroll.post { oldScroll.scrollTo(0, savedScrollY.coerceAtLeast(0)) }
        if (focusTag != null || focusDescription != null) {
            oldRoot.post {
                (focusTag?.let { findByTag(oldRoot, it) }
                    ?: focusDescription?.let { findByDescription(oldRoot, it) })?.requestFocus()
            }
        }
        return true
    }

    private fun capture(entry: SettingsScreenEntry?) {
        if (entry == null) return
        entry.scrollY = findScroll(entry.root)?.scrollY ?: entry.scrollY
        entry.searchText = findSearch(entry.root)?.text?.toString().orEmpty()
    }

    private fun ensureDialog(ctx: Context) {
        if (dialog?.isShowing == true && container != null) return
        val frame = FrameLayout(ctx)
        val hostDialog = Dialog(ctx)
        hostDialog.setContentView(frame)
        hostDialog.setCancelable(false)
        hostDialog.setCanceledOnTouchOutside(false)
        hostDialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == KeyEvent.ACTION_UP) back()
                true
            }
        }
        dialog = hostDialog
        container = frame
    }

    private fun render(ctx: Context) {
        val entry = stack.lastOrNull() ?: return
        ensureDialog(ctx)
        val frame = container ?: return
        frame.removeAllViews()
        entry.root.parent?.let { parent ->
            (parent as? ViewGroup)?.removeView(entry.root)
        }
        frame.addView(entry.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        findSearch(entry.root)?.let { search ->
            if (search.text.toString() != entry.searchText) search.setText(entry.searchText)
        }
        findScroll(entry.root)?.let { scroll ->
            scroll.post { scroll.scrollTo(0, entry.scrollY.coerceAtLeast(0)) }
        }
        val hostDialog = dialog
        if (hostDialog != null && !hostDialog.isShowing) {
            hostDialog.show()
            hostDialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                val density = ctx.resources.displayMetrics.density
                val width = minOf((560 * density + 0.5f).toInt(), ctx.resources.displayMetrics.widthPixels - (24 * density + 0.5f).toInt())
                window.setLayout(width.coerceAtLeast(1), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    fun show(ctx: Context, key: String, root: View, onBack: (() -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            settingsMainHandler.post { show(ctx, key, root, onBack) }
            return
        }
        val requestedSearch = findSearch(root)?.text?.toString().orEmpty()
        val existingIndex = stack.indexOfLast { it.key == key }
        if (existingIndex >= 0) {
            val existing = stack[existingIndex]
            capture(existing)
            if (requestedSearch.isNotBlank() && requestedSearch != existing.searchText) {
                existing.searchText = requestedSearch
                existing.scrollY = 0
            }
            val canUpdateInPlace = existingIndex == stack.lastIndex &&
                existing.root !== root && updateInPlace(existing, root)
            if (!canUpdateInPlace) existing.root = root
            existing.onBack = onBack
            while (stack.size > existingIndex + 1) stack.removeAt(stack.lastIndex)
            if (canUpdateInPlace) return
        } else {
            capture(stack.lastOrNull())
            val entry = SettingsScreenEntry(key, root, onBack)
            entry.searchText = requestedSearch
            stack += entry
        }
        render(ctx)
    }

    fun back() {
        if (stack.size > 1) {
            capture(stack.lastOrNull())
            stack.removeAt(stack.lastIndex)
            // A screen's context is stable for one plugin settings session.
            val ctx = container?.context ?: return
            render(ctx)
            return
        }
        val callback = stack.lastOrNull()?.onBack
        if (callback != null) callback() else dismiss()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        container = null
        stack.clear()
    }

    fun isCurrent(key: String): Boolean =
        dialog?.isShowing == true && stack.lastOrNull()?.key == key
}

/** Preferred typed menu API. Existing screens can migrate without changing the shared renderer. */
internal fun settingMenu(
    ctx: Context,
    title: String,
    items: List<SettingItem>,
    back: (() -> Unit)?,
    description: String? = null,
    screenKey: String? = null,
    forceSearch: Boolean = false,
    globalSearchItems: List<SettingItem> = emptyList(),
    showRefreshNotice: Boolean = false,
    helpAction: (() -> Unit)? = null,
) {
    val colors = palette(ctx)
    val root = shell(ctx, title, description ?: "Choose an item to manage this section.", helpAction)
    if (showRefreshNotice) HomeRefreshNotice.addTo(ctx, root)
    val list = vertical(ctx)
    val scroll = ScrollView(ctx).apply {
        isFillViewport = false
        tag = "settings.scroll"
        addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    fun makeRow(item: SettingItem): View {
        val body = vertical(ctx).apply {
            val dangerColor = 0xffd32f2f.toInt()
            addView(text(ctx, item.title, 14f, if (item.danger) dangerColor else colors.primary, true))
            if (!item.summary.isNullOrBlank()) {
                addView(text(ctx, item.summary, 11.5f, if (item.danger) dangerColor else colors.secondary).apply {
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
        }
        lateinit var row: LinearLayout
        row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(ctx, 12),
                dp(ctx, if (item.section == "Overview") 11 else 8),
                dp(ctx, 8),
                dp(ctx, if (item.section == "Overview") 11 else 8),
            )
            val normalBackground = when {
                item.section == "Overview" -> rounded(ctx, withAlpha(colors.accent, 14), colors.outline)
                item.danger -> ripple(ctx, withAlpha(0xffd32f2f.toInt(), 10), colors)
                else -> ripple(ctx, colors.background, colors)
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, colors.background).apply {
                    setStroke(dp(ctx, 2), colors.accent)
                })
                addState(intArrayOf(), normalBackground)
            }
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            contentDescription = buildString {
                append(item.title)
                item.valueLabel?.let { append(", ").append(it) }
                if (item.type == SettingItemType.Switch) append(if (item.checked == true) ", on" else ", off")
                if (item.type == SettingItemType.InlineChoice) {
                    item.choiceLabels.getOrNull(item.selectedChoice)?.let { append(", ").append(it) }
                }
            }
            minimumHeight = dp(ctx, if (item.section == "Overview") 66 else 48)
            item.icon?.let { iconName ->
                SettingsIcons.ALL[iconName]?.let { path ->
                    val tile = FrameLayout(ctx).apply {
                        background = rounded(ctx, withAlpha(colors.accent, 17), colors.outline)
                        isFocusable = false
                        addView(ImageView(ctx).apply {
                            setImageDrawable(IconDrawable(path, colors.accent))
                            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, FrameLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34), Gravity.CENTER))
                    }
                    addView(tile, LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)).apply {
                        marginEnd = dp(ctx, 10)
                    })
                }
            }
            addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (item.type) {
                SettingItemType.Switch -> {
                    val switchView = Switch(ctx).apply {
                        isChecked = item.checked ?: false
                        isClickable = true
                        isFocusable = true
                        thumbTintList = ColorStateList.valueOf(if (isChecked) colors.accent else colors.secondary)
                        trackTintList = ColorStateList.valueOf(withAlpha(if (isChecked) colors.accent else colors.secondary, 90))
                        contentDescription = "${item.title}, ${if (isChecked) "on" else "off"}"
                        setOnCheckedChangeListener { _, checked ->
                            thumbTintList = ColorStateList.valueOf(if (checked) colors.accent else colors.secondary)
                            trackTintList = ColorStateList.valueOf(withAlpha(if (checked) colors.accent else colors.secondary, 90))
                            contentDescription = "${item.title}, ${if (checked) "on" else "off"}"
                            row.contentDescription = "${item.title}, ${if (checked) "on" else "off"}"
                            val onToggle = item.onToggle
                            if (onToggle != null) onToggle(checked) else item.onClick?.invoke()
                        }
                    }
                    if (item.onClick != null && item.onToggle != null) {
                        addView(text(ctx, "›", 22f, colors.secondary).apply {
                            gravity = Gravity.CENTER
                            contentDescription = "Open ${item.title} options"
                            setOnClickListener { item.onClick.invoke() }
                        }, LinearLayout.LayoutParams(dp(ctx, 20), ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                    addView(switchView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    setOnClickListener {
                        if (item.onToggle != null) switchView.toggle() else item.onClick?.invoke()
                    }
                }
                SettingItemType.Value -> {
                    if (!item.valueLabel.isNullOrBlank()) {
                        addView(text(ctx, item.valueLabel, 13f, if (item.danger) 0xffd32f2f.toInt() else colors.accent, true).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(ctx, 6), dp(ctx, 3), dp(ctx, 4), dp(ctx, 3))
                        })
                    } else {
                        addView(text(ctx, "›", 24f, colors.secondary).apply { gravity = Gravity.CENTER },
                            LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                    setOnClickListener { item.onClick?.invoke() }
                }
                SettingItemType.Navigate -> {
                    addView(text(ctx, "›", 24f, colors.secondary).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.MATCH_PARENT))
                    setOnClickListener { item.onClick?.invoke() }
                }
                SettingItemType.InlineChoice -> {
                    removeView(body)
                    orientation = LinearLayout.VERTICAL
                    addView(body, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    val choices = segmentedControl(
                        ctx,
                        colors,
                        item.choiceLabels,
                        item.selectedChoice,
                    ) { index -> item.onChoice?.invoke(index) }
                    addView(choices, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 7) })
                    setOnClickListener(null)
                }
            }
        }
        return row
    }

    val rowViews = items.map { item -> makeRow(item) to item }
    val globalRows = globalSearchItems.map { item -> makeRow(item) to item }

    fun render(filter: String = "") {
        list.removeAllViews()
        val searching = filter.isNotBlank()
        val source = if (searching && globalRows.isNotEmpty()) rowViews + globalRows else rowViews
        val matching = source.filter { (_, item) ->
            val keywords = item.searchKeywords.ifEmpty { listOfNotNull(item.title, item.summary, item.valueLabel) }
            keywords.any { it.contains(filter, ignoreCase = true) } &&
                (!searching || globalRows.isEmpty() || item in items || globalRows.any { it.second === item })
        }
        if (matching.isEmpty()) {
            list.addView(text(ctx, "No matching settings.", 13f, colors.secondary).apply {
                setPadding(dp(ctx, 14), dp(ctx, 18), dp(ctx, 14), dp(ctx, 18))
            })
            return
        }
        var previousSection: String? = null
        matching.forEachIndexed { visibleIndex, (row, item) ->
            val section = if (searching && globalRows.isNotEmpty() && item !in items) "MATCHING SETTINGS" else item.section
            if (!section.isNullOrBlank() && section != "Overview" && section != previousSection) {
                list.addView(text(ctx, section.uppercase(), 10f,
                    if (section.contains("danger", true)) 0xffd32f2f.toInt() else colors.accent, true).apply {
                    letterSpacing = 0.08f
                    setPadding(dp(ctx, 8), dp(ctx, 10), 0, dp(ctx, 5))
                })
                previousSection = section
            }
            if (visibleIndex > 0) {
                list.addView(View(ctx).apply {
                    setBackgroundColor(colors.outline)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(ctx, 1),
                    ).apply {
                        leftMargin = dp(ctx, 12)
                        rightMargin = dp(ctx, 12)
                    }
                })
            }
            list.addView(row)
        }
    }

    val initialSearch = SettingsDialogHost.consumeNextMenuSearch()
    if (forceSearch || items.size >= 8 || globalSearchItems.isNotEmpty()) {
        val search = searchBox(ctx, "Search settings", colors, ::render)
        if (initialSearch.isNotBlank()) search.setText(initialSearch)
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 42),
        ).apply { bottomMargin = dp(ctx, 6) })
    }

    render(initialSearch)
    root.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        minOf(dp(ctx, 420), dp(ctx, maxOf(88, items.size * 56))),
    ))
    addFooter(ctx, root, if (back != null) "Back" else "Close", {
        if (back != null) back() else SettingsDialogHost.dismiss()
    })
    SettingsDialogHost.show(ctx, screenKey ?: "menu:$title", root, back)
}

internal fun prompt(
    ctx: Context, title: String, message: String?, initial: String, numeric: Boolean,
    onCancel: (() -> Unit)? = null,
    showPasteButton: Boolean = false,
    validate: ((String) -> String?)? = null,
    onOk: (String) -> Unit,
) {
    val colors = palette(ctx)
    val root = shell(ctx, title, message ?: "Enter a value below.")
    val input = EditText(ctx).apply {
        setText(initial)
        textSize = 16f
        setTextColor(colors.primary)
        setHintTextColor(colors.secondary)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
        tag = "settings.input"
    }
    root.addView(input, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(ctx, 6) })
    val validationError = text(ctx, "", 12f, colors.danger).apply {
        visibility = View.GONE
        tag = "settings.validation-error"
        setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 2))
    }
    root.addView(validationError)
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            validationError.visibility = View.GONE
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
    if (showPasteButton) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipboardText = runCatching {
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
        }.getOrDefault("")
        val manifestSuggestion = clipboardText.isNotBlank() &&
            Urls.isValidAddonUrl(clipboardText) &&
            (clipboardText.startsWith("stremio://", ignoreCase = true) ||
                clipboardText.substringBefore('?').substringBefore('#').endsWith("/manifest.json", ignoreCase = true))
        root.addView(text(
            ctx,
            if (manifestSuggestion) "Manifest link found in clipboard · Use" else "Paste from clipboard",
            12f,
            colors.accent,
            true,
        ).apply {
            isFocusable = true
            contentDescription = if (manifestSuggestion) "Use detected addon manifest link from clipboard" else "Paste from clipboard"
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            minHeight = dp(ctx, 40)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, colors.background).apply {
                    setStroke(dp(ctx, 2), colors.accent)
                })
                addState(intArrayOf(), ripple(ctx, colors.background, colors))
            }
            setOnClickListener {
                val pasted = if (manifestSuggestion) clipboardText else runCatching {
                    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                }.getOrDefault("")
                if (pasted.isNotBlank()) input.setText(pasted) else toast(ctx, "Clipboard is empty.")
            }
        })
    }
    addFooter(ctx, root, "Save", {
        val value = input.text.toString().trim()
        val error = validate?.invoke(value)
        if (error != null) {
            validationError.text = error
            validationError.visibility = View.VISIBLE
            input.requestFocus()
        } else onOk(value)
    }, "Cancel", {
        if (onCancel != null) onCancel() else SettingsDialogHost.back()
    })
    SettingsDialogHost.show(ctx, "prompt:$title", root, onCancel)
    input.requestFocus()
    input.post {
        input.setSelection(input.text.length)
        val manager = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
}

internal fun confirm(
    ctx: Context,
    title: String,
    message: String,
    onCancel: (() -> Unit)? = null,
    onYes: () -> Unit,
) {
    val root = shell(ctx, title, message)
    addFooter(ctx, root, "Continue", onYes, "Cancel", {
        if (onCancel != null) onCancel() else SettingsDialogHost.back()
    })
    SettingsDialogHost.show(ctx, "confirm:$title", root, onCancel)
}

internal fun choose(
    ctx: Context,
    title: String,
    labels: List<String>,
    checked: Int,
    onCancel: (() -> Unit)? = null,
    onPick: (Int) -> Unit,
) {
    val items = labels.mapIndexed { index, label ->
        SettingItem(
            title = if (index == checked) "✓  $label  ·  SELECTED" else label,
            type = SettingItemType.Value,
            onClick = { onPick(index) },
            searchKeywords = listOf(label),
        )
    }
    settingMenu(ctx, title, items, onCancel, "Select one option. Your choice is saved immediately.")
}

internal fun multi(
    ctx: Context,
    title: String,
    labels: List<String>,
    initial: List<Boolean>,
    description: String = "Select all options that should remain enabled.",
    onCancel: (() -> Unit)? = null,
    onSave: (List<Boolean>) -> Unit,
) {
    val colors = palette(ctx)
    val state = BooleanArray(labels.size) { initial.getOrElse(it) { false } }
    val root = shell(ctx, title, description)
    val list = vertical(ctx)
    val checks = labels.mapIndexed { index, label ->
        val check = CheckBox(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(colors.primary)
            isChecked = state[index]
            buttonTintList = ColorStateList.valueOf(colors.accent)
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            minHeight = dp(ctx, 48)
            setOnCheckedChangeListener { _, checked -> state[index] = checked }
        }
        check to label
    }

    fun render(query: String = "") {
        list.removeAllViews()
        val matching = checks.filter { (_, label) -> label.contains(query, ignoreCase = true) }
        if (matching.isEmpty()) {
            list.addView(text(ctx, "No matching options.", 13f, colors.secondary).apply {
                setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
            })
        } else {
            matching.forEach { (check, _) -> list.addView(check) }
        }
    }

    if (labels.size >= 8) {
        root.addView(searchBox(ctx, "Search options", colors, ::render), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 46),
        ).apply { bottomMargin = dp(ctx, 8) })
    }

    render()
    val scroll = ScrollView(ctx).apply {
        tag = "settings.scroll"
        addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    root.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        minOf(dp(ctx, 430), dp(ctx, maxOf(72, labels.size * 54))),
    ))
    addFooter(ctx, root, "Save", { onSave(state.toList()) }, "Cancel", {
        if (onCancel != null) onCancel() else SettingsDialogHost.back()
    })
    SettingsDialogHost.show(ctx, "multi:$title", root, onCancel)
}

internal fun csv(list: List<String>) = if (list.isEmpty()) "none" else list.joinToString(", ")
internal fun parseCsv(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    /** Reject invalid slash-delimited regex filters instead of silently saving rules that match nothing. */
internal fun keywordList(
        ctx: Context,
        title: String,
        description: String,
        initial: String,
        onCancel: (() -> Unit)? = null,
        onSave: (MutableList<String>) -> Unit,
    ) {
        prompt(ctx, title, description, initial, false, onCancel) { value ->
            val keywords = parseCsv(value)
            val invalid = StreamFilter.invalidRegexPatterns(keywords)
            if (invalid.isNotEmpty()) {
                toast(ctx, "Invalid regular expression: ${invalid.first()}. Nothing was saved.")
                keywordList(ctx, title, description, value, onCancel, onSave)
            } else {
                onSave(keywords)
            }
        }
    }
internal fun onOff(b: Boolean) = if (b) "ON" else "OFF"
internal fun limit(n: Int, unit: String) = if (n <= 0) "off" else if (unit.isEmpty()) n.toString() else "$n $unit"
internal fun modeLabel(m: Int) = when (m) { MODE_HIDE -> "hide"; MODE_ONLY -> "only"; else -> "any" }

internal fun host(url: String) = try { URL(url).host } catch (e: Exception) { url }
