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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.net.URL

internal val settingsMainHandler = Handler(Looper.getMainLooper())

internal enum class SettingItemType { Navigate, Switch, Value }

internal data class SettingItem(
    val title: String,
    val summary: String? = null,
    val type: SettingItemType = SettingItemType.Navigate,
    val onClick: (() -> Unit)? = null,
    val searchKeywords: List<String> = emptyList(),
    val checked: Boolean? = null,
    val valueLabel: String? = null,
    val section: String? = null,
    val onToggle: (() -> Unit)? = null,
    val danger: Boolean = false,
)

internal inline fun updateAndReopen(
    reopen: () -> Unit,
    crossinline update: Settings.() -> Unit,
) {
    Config.update { update() }
    reopen()
}

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
            addView(text(ctx, "Changes saved. Refresh the home page to apply.", 12f, colors.primary),
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
            cornerRadius = dp(ctx, 16).toFloat()
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

internal fun shell(ctx: Context, title: String, description: String? = null): LinearLayout {
        val colors = palette(ctx)
        val root = vertical(ctx, dp(ctx, 18)).apply { background = rounded(ctx, colors.background) }
        val header = vertical(ctx).apply {
            addView(text(ctx, "MULTISTREMIO  /  SETTINGS", 10f, colors.accent, true).apply {
                letterSpacing = 0.11f
            })
            addView(text(ctx, title, 22f, colors.primary, true).apply {
                setPadding(0, dp(ctx, 5), 0, 0)
            })
            if (!description.isNullOrBlank()) {
                addView(text(ctx, description, 13f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 5), 0, dp(ctx, 3))
                })
            }
        }
        root.addView(header)
        root.addView(View(ctx).apply {
            setBackgroundColor(colors.outline)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                topMargin = dp(ctx, 14)
                bottomMargin = dp(ctx, 12)
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
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        if (secondaryLabel != null && onSecondary != null) {
            footer.addView(text(ctx, secondaryLabel.uppercase(), 12f, colors.secondary, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11))
                background = ripple(ctx, colors.background, colors)
                setOnClickListener { onSecondary() }
            })
        }
        footer.addView(text(ctx, label.uppercase(), 12f, colors.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 11), dp(ctx, 16), dp(ctx, 11))
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
            window.setLayout(minOf(dp(ctx, 520), ctx.resources.displayMetrics.widthPixels - dp(ctx, 36)), ViewGroup.LayoutParams.WRAP_CONTENT)
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
    return Palette(background, primary, secondary, accent, withAlpha(secondary, 54), withAlpha(accent, 42))
}

internal data class Palette(
    val background: Int,
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val outline: Int,
    val ripple: Int,
)

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
    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
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
                val width = minOf((520 * density + 0.5f).toInt(), ctx.resources.displayMetrics.widthPixels - (36 * density + 0.5f).toInt())
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
            existing.root = root
            existing.onBack = onBack
            if (requestedSearch.isNotBlank()) existing.searchText = requestedSearch
            while (stack.size > existingIndex + 1) stack.removeAt(stack.lastIndex)
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
) {
    val colors = palette(ctx)
    val root = shell(ctx, title, description ?: "Choose an item to manage this section.")
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
            addView(text(ctx, item.title, 15f, if (item.danger) dangerColor else colors.primary, true))
            if (!item.summary.isNullOrBlank()) {
                addView(text(ctx, item.summary, 12f, if (item.danger) dangerColor else colors.secondary).apply {
                    setPadding(0, dp(ctx, 3), 0, 0)
                })
            }
            setOnClickListener { item.onClick?.invoke() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            background = ripple(ctx, if (item.danger) withAlpha(0xffd32f2f.toInt(), 10) else colors.background, colors)
            minimumHeight = dp(ctx, 54)
            addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (item.type) {
                SettingItemType.Switch -> {
                    val toggle = Switch(ctx).apply {
                        isChecked = item.checked ?: false
                        isClickable = true
                        isFocusable = true
                        thumbTintList = ColorStateList.valueOf(if (isChecked) colors.accent else colors.secondary)
                        trackTintList = ColorStateList.valueOf(withAlpha(if (isChecked) colors.accent else colors.secondary, 90))
                        contentDescription = item.title
                        setOnCheckedChangeListener { _, _ -> (item.onToggle ?: item.onClick)?.invoke() }
                    }
                    addView(toggle, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    setOnClickListener { (item.onToggle ?: item.onClick)?.invoke() }
                }
                SettingItemType.Value -> {
                    if (!item.valueLabel.isNullOrBlank()) {
                        addView(text(ctx, item.valueLabel, 13f, if (item.danger) 0xffd32f2f.toInt() else colors.accent, true).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
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
            if (!section.isNullOrBlank() && section != previousSection) {
                list.addView(text(ctx, section.uppercase(), 10f,
                    if (section.contains("danger", true)) 0xffd32f2f.toInt() else colors.accent, true).apply {
                    letterSpacing = 0.08f
                    setPadding(dp(ctx, 8), dp(ctx, 12), 0, dp(ctx, 6))
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
                        leftMargin = dp(ctx, 14)
                        rightMargin = dp(ctx, 14)
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
            dp(ctx, 46),
        ).apply { bottomMargin = dp(ctx, 8) })
    }

    render(initialSearch)
    root.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        minOf(dp(ctx, 430), dp(ctx, maxOf(88, items.size * 62))),
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
    if (showPasteButton) {
        root.addView(text(ctx, "Paste from clipboard", 12f, colors.accent, true).apply {
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            setOnClickListener {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                val pasted = runCatching { clip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() }.getOrDefault("")
                if (pasted.isNotBlank()) input.setText(pasted) else toast(ctx, "Clipboard is empty.")
            }
        })
    }
    addFooter(ctx, root, "Save", {
        onOk(input.text.toString().trim())
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
