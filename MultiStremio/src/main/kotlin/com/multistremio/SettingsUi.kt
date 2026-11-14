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

/** Theme-aware settings UI built from Android views; no XML or extra UI dependency required. */
object SettingsUi {
    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context) = mainMenu(ctx)

    private fun toast(ctx: Context, message: String) =
        main.post { Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }

    private data class Palette(
        val background: Int,
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val outline: Int,
        val ripple: Int,
    )

    private fun dp(ctx: Context, value: Int) =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(ctx: Context, attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!ctx.theme.resolveAttribute(attribute, value, true)) return fallback
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
        return if (value.resourceId != 0) {
            try { ctx.resources.getColor(value.resourceId) } catch (_: Exception) { fallback }
        } else fallback
    }

    private fun palette(ctx: Context): Palette {
        val background = color(ctx, android.R.attr.colorBackground, Color.rgb(250, 250, 250))
        val primary = color(ctx, android.R.attr.textColorPrimary, Color.rgb(28, 28, 30))
        val secondary = color(ctx, android.R.attr.textColorSecondary, Color.rgb(112, 112, 118))
        val accent = color(ctx, android.R.attr.colorAccent, Color.rgb(70, 110, 230))
        return Palette(background, primary, secondary, accent, withAlpha(secondary, 54), withAlpha(accent, 42))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    private fun rounded(ctx: Context, fill: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, 16).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1), stroke)
        }

    private fun ripple(ctx: Context, fill: Int, colors: Palette): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(colors.ripple), rounded(ctx, fill, colors.outline), null)

    private fun text(ctx: Context, value: String, size: Float, tint: Int, bold: Boolean = false) =
        TextView(ctx).apply {
            text = value
            textSize = size
            setTextColor(tint)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = true
        }

    private fun vertical(ctx: Context, padding: Int = 0) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (padding > 0) setPadding(padding, padding, padding, padding)
        }

    private fun shell(ctx: Context, title: String, description: String? = null): LinearLayout {
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

    private fun addFooter(
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

    private fun window(dialog: AlertDialog, ctx: Context) {
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
    private fun progress(
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

    private fun splitItem(item: String): Pair<String, String?> {
        val separator = item.indexOf(": ")
        if (separator in 2..54) return item.substring(0, separator) to item.substring(separator + 2)
        return item to null
    }

    private fun menu(
        ctx: Context,
        title: String,
        items: List<String>,
        back: (() -> Unit)?,
        description: String? = null,
        itemDetails: List<String>? = null,
        pick: (Int) -> Unit,
    ) {
        val colors = palette(ctx)
        val root = shell(ctx, title, description ?: "Choose an item to manage this section.")
        val scroll = ScrollView(ctx).apply { isFillViewport = false }
        val list = vertical(ctx)
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rowViews = mutableListOf<Pair<View, String>>()

        items.forEachIndexed { index, value ->
            val (parsedTitle, parsedDetail) = splitItem(value)
            val detail = itemDetails?.getOrNull(index) ?: parsedDetail
            val body = vertical(ctx).apply {
                addView(text(ctx, parsedTitle, 15f, colors.primary, true))
                if (!detail.isNullOrBlank()) {
                    addView(text(ctx, detail, 12f, colors.secondary).apply {
                        setPadding(0, dp(ctx, 3), 0, 0)
                    })
                }
            }
            val arrow = text(ctx, "›", 25f, colors.secondary).apply { gravity = Gravity.CENTER }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
                background = ripple(ctx, colors.background, colors)
                minimumHeight = dp(ctx, 52)
                addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrow, LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.MATCH_PARENT))
                setOnClickListener {
                    (root.getTag() as? AlertDialog)?.dismiss()
                    pick(index)
                }
            }
            rowViews += row to listOfNotNull(value, detail).joinToString(" ")
        }

        fun render(filter: String = "") {
            list.removeAllViews()
            val matching = rowViews.filter { (_, label) -> label.contains(filter, ignoreCase = true) }
            if (matching.isEmpty()) {
                list.addView(text(ctx, "No matching settings.", 13f, colors.secondary).apply {
                    setPadding(dp(ctx, 14), dp(ctx, 18), dp(ctx, 14), dp(ctx, 18))
                })
            } else {
                matching.forEachIndexed { visibleIndex, (row, _) ->
                    if (visibleIndex > 0) {
                        list.addView(View(ctx).apply {
                            setBackgroundColor(colors.outline)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                                leftMargin = dp(ctx, 14)
                                rightMargin = dp(ctx, 14)
                            }
                        })
                    }
                    list.addView(row)
                }
            }
        }

        if (items.size >= 8) {
            val search = EditText(ctx).apply {
                hint = "Search settings"
                textSize = 14f
                setTextColor(colors.primary)
                setHintTextColor(colors.secondary)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
            }
            root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 46)).apply {
                bottomMargin = dp(ctx, 8)
            })
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        render()
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 430), dp(ctx, maxOf(88, items.size * 62))),
        ))
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        root.setTag(dialog)
        dialog.setOnCancelListener { back?.invoke() }
        addFooter(ctx, root, if (back != null) "Back" else "Close", {
            dialog.dismiss()
            back?.invoke()
        })
        window(dialog, ctx)
    }

    private fun prompt(
        ctx: Context, title: String, message: String?, initial: String, numeric: Boolean,
        onCancel: (() -> Unit)? = null,
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
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 6)
        })
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { onCancel?.invoke() }
        addFooter(ctx, root, "Save", {
            dialog.dismiss()
            onOk(input.text.toString().trim())
        }, "Cancel", {
            dialog.dismiss()
            onCancel?.invoke()
        })
        window(dialog, ctx)
    }

    private fun confirm(
        ctx: Context,
        title: String,
        message: String,
        onCancel: (() -> Unit)? = null,
        onYes: () -> Unit,
    ) {
        val root = shell(ctx, title, message)
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { onCancel?.invoke() }
        addFooter(ctx, root, "Continue", {
            dialog.dismiss()
            onYes()
        }, "Cancel", {
            dialog.dismiss()
            onCancel?.invoke()
        })
        window(dialog, ctx)
    }

    private fun choose(
        ctx: Context,
        title: String,
        labels: List<String>,
        checked: Int,
        onCancel: (() -> Unit)? = null,
        onPick: (Int) -> Unit,
    ) {
        val rows = labels.mapIndexed { index, label ->
            if (index == checked) "✓  $label  ·  SELECTED" else label
        }
        menu(ctx, title, rows, onCancel, "Select one option. Your choice is saved immediately.") { onPick(it) }
    }

    private fun multi(
        ctx: Context,
        title: String,
        labels: List<String>,
        initial: List<Boolean>,
        description: String = "Select all options that should remain enabled.",
        onCancel: (() -> Unit)? = null,
        onSave: (List<Boolean>) -> Unit,
    ) {
        val colors = palette(ctx)
        val state = initial.toBooleanArray()
        val root = shell(ctx, title, description)
        val list = vertical(ctx)
        val checks = labels.mapIndexed { index, label ->
            val check = CheckBox(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(colors.primary)
                isChecked = state.getOrElse(index) { false }
                buttonTintList = ColorStateList.valueOf(colors.accent)
                setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
                minHeight = dp(ctx, 48)
                setOnCheckedChangeListener { _, checked ->
                    if (index in state.indices) state[index] = checked
                }
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
            val search = EditText(ctx).apply {
                hint = "Search options"
                textSize = 14f
                setTextColor(colors.primary)
                setHintTextColor(colors.secondary)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
            }
            root.addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 46),
            ).apply { bottomMargin = dp(ctx, 8) })
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    render(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        render()

        val scroll = ScrollView(ctx).apply {
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 430), dp(ctx, maxOf(72, labels.size * 54))),
        ))
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { onCancel?.invoke() }
        addFooter(ctx, root, "Save", {
            dialog.dismiss()
            onSave(state.toList())
        }, "Cancel", {
            dialog.dismiss()
            onCancel?.invoke()
        })
        window(dialog, ctx)
    }

    private fun csv(list: List<String>) = if (list.isEmpty()) "none" else list.joinToString(", ")
    private fun parseCsv(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    /** Reject invalid slash-delimited regex filters instead of silently saving rules that match nothing. */
    private fun keywordList(
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
    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"
    private fun limit(n: Int, unit: String) = if (n <= 0) "off" else if (unit.isEmpty()) n.toString() else "$n $unit"
    private fun modeLabel(m: Int) = when (m) { MODE_HIDE -> "hide"; MODE_ONLY -> "only"; else -> "any" }

    private fun host(url: String) = try { URL(url).host } catch (e: Exception) { url }

    // ---- main menu -------------------------------------------------------------------------

    private fun mainMenu(ctx: Context) {
        val s = Config.settings
        val activeRows = s.addons.sumOf { addon ->
            if (!addon.enabled || !addon.useCatalogs) 0 else {
                val manifest = Config.manifestOf(addon)
                if (manifest == null) 0 else manifest.catalogs.orEmpty().count { catalog ->
                    val type = catalog.type
                    val id = catalog.id
                    type != null && id != null &&
                        manifest.supports("catalog", type, null) &&
                        catalog.isHomeCatalog() && "$type/$id" !in addon.hiddenCatalogs
                }
            }
        } + s.externalRows.count { it.enabled }
        menu(
            ctx, "MultiStremio",
            listOf(
                "Addons (${s.addons.count { it.enabled }}/${s.addons.size} enabled)",
                "Catalog rows (visibility & order)",
                "Stream filters",
                "Stream sorting",
                "Stream formatter (design link names)",
                "Last streaming diagnostics",
                "Subtitles",
                "General",
                "Backup / restore / reset",
            ),
            null,
            "Manage sources, homepage layout and playback. Search is available inside longer settings lists.",
            listOf(
                "${s.addons.size} sources configured · ${s.addons.count { it.enabled }} active",
                "$activeRows homepage rows configured as enabled; check Catalog rows for missing sources",
                "Choose which streams are allowed",
                "Control quality, language, codec and audio ranking",
                "Build your own link layout without writing code",
                "See the most recent source responses and filtered-link counts",
                "Control subtitle sources and languages",
                "Request timeout and link display options",
                "Protect, copy or restore your configuration",
            ),
        ) {
            when (it) {
                0 -> addonsMenu(ctx)
                1 -> catalogRowsMenu(ctx)
                2 -> filtersMenu(ctx)
                3 -> sortMenu(ctx)
                4 -> streamFormatterMenu(ctx)
                5 -> streamDiagnosticsMenu(ctx)
                6 -> subtitlesMenu(ctx)
                7 -> generalMenu(ctx)
                8 -> backupMenu(ctx)
            }
        }
    }

    // ---- visual stream formatter -----------------------------------------------------------

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
            { mainMenu(ctx) },
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
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { streamFormatterMenu(ctx) }
        addFooter(ctx, root, "Save row order", {
            val order = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
            if (order.size == orderedIds.size && order.toSet() == orderedIds.toSet()) {
                Config.update { streamFormat.fields = order.toMutableList() }
                dialog.dismiss()
                streamFormatterMenu(ctx)
            } else {
                toast(ctx, "Could not save the format order. Please try again.")
            }
        }, "Cancel", {
            dialog.dismiss()
            streamFormatterMenu(ctx)
        })
        window(dialog, ctx)
    }

    /**
     * Shared drag controller for both reorder screens.
     *
     * The grip is a dedicated drag target: vertical movement starts reordering as soon as the
     * finger passes touch slop. No long-press timer is involved, so the gesture cannot be cancelled
     * by the ScrollView before the reorder controller starts. Swiping the row body still scrolls.
     */
    private fun installDragReordering(
        ctx: Context,
        handle: View,
        item: View,
        list: LinearLayout,
        scroll: ScrollView,
        colors: Palette,
        onMoved: () -> Unit,
    ) {
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        var tracking = false
        var dragging = false
        var downRawY = 0f
        var lastPointerY = 0f
        var edgeDirection = 0

        fun moveItem(pointerY: Float, updateEdgeScroll: Boolean = true) {
            val centers = (0 until list.childCount).mapNotNull { index ->
                val child = list.getChildAt(index)
                if (child === item) null else {
                    val position = IntArray(2)
                    child.getLocationOnScreen(position)
                    position[1] + child.height / 2f
                }
            }
            // The insertion slot is measured among the other rows, after excluding the dragged row.
            val destination = DragReorderMath.insertionIndex(centers, pointerY)
            val current = list.indexOfChild(item)
            if (destination != current) {
                list.removeView(item)
                list.addView(item, destination.coerceIn(0, list.childCount))
                onMoved()
            }

            if (updateEdgeScroll) {
                val scrollPosition = IntArray(2)
                scroll.getLocationOnScreen(scrollPosition)
                val edge = dp(ctx, 48)
                edgeDirection = when {
                    pointerY < scrollPosition[1] + edge -> -1
                    pointerY > scrollPosition[1] + scroll.height - edge -> 1
                    else -> 0
                }
            }
        }

        val edgeScroller = object : Runnable {
            override fun run() {
                if (!tracking || !dragging || edgeDirection == 0) return
                val before = scroll.scrollY
                scroll.scrollBy(0, edgeDirection * dp(ctx, 8))
                if (scroll.scrollY != before) {
                    // Re-evaluate the insertion point while the list moves under a stationary finger.
                    moveItem(lastPointerY, updateEdgeScroll = false)
                    handle.postDelayed(this, 16L)
                } else {
                    edgeDirection = 0
                }
            }
        }

        fun finishDrag() {
            tracking = false
            edgeDirection = 0
            handle.removeCallbacks(edgeScroller)
            if (dragging) {
                dragging = false
                item.background = rounded(ctx, colors.background, colors.outline)
                item.elevation = 0f
            }
            scroll.requestDisallowInterceptTouchEvent(false)
        }

        fun updateAutoScroll() {
            handle.removeCallbacks(edgeScroller)
            if (tracking && dragging && edgeDirection != 0) {
                handle.postDelayed(edgeScroller, 16L)
            }
        }

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    dragging = false
                    downRawY = event.rawY
                    lastPointerY = event.rawY
                    edgeDirection = 0
                    // Only the grip is a drag target. Keep ScrollView from stealing its gesture.
                    scroll.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        lastPointerY = event.rawY
                        if (!dragging && abs(event.rawY - downRawY) >= touchSlop) {
                            dragging = true
                            item.background = rounded(ctx, withAlpha(colors.accent, 28), colors.accent)
                            item.elevation = dp(ctx, 6).toFloat()
                            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (dragging) {
                            moveItem(lastPointerY)
                            updateAutoScroll()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    finishDrag()
                    true
                }
                else -> true
            }
        }
    }


    private fun streamDiagnosticsMenu(ctx: Context) {
        val snapshot = StreamDiagnostics.latest()
        if (snapshot == null) {
            menu(
                ctx,
                "Streaming diagnostics",
                listOf("No completed stream lookup recorded yet."),
                { mainMenu(ctx) },
                "Open a movie or episode and load its links first. The report is kept in memory and is cleared when CloudStream restarts.",
            ) { }
            return
        }

        val colors = palette(ctx)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(snapshot.startedAt))
        val root = shell(
            ctx,
            "Streaming diagnostics",
            "Latest lookup · $time · ${snapshot.durationMs} ms total. This report contains addon names and counts only, never stream URLs or addon access keys.",
        )
        val summary = vertical(ctx, dp(ctx, 12)).apply {
            background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
            listOf(
                "Raw streams returned: ${snapshot.rawStreams}",
                "Addon subtitle tracks: ${snapshot.addonSubtitles}",
                "Playable candidates before filters: ${snapshot.playableCandidates}",
                "Streams kept after filters and deduplication: ${snapshot.keptAfterFilters}",
                "Links handed to CloudStream: ${snapshot.emittedLinks}",
                "Addon requests recorded: ${snapshot.requests.size}",
                "Parsing work (parallel totals): ${snapshot.parseWorkMs} ms",
                "Filtering, merging & sorting: ${snapshot.pipelineMs} ms",
                "Link emission & subtitle callbacks: ${snapshot.emissionMs} ms",
            ).forEach { addView(text(ctx, it, 13f, colors.primary).apply {
                setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
            }) }
        }
        root.addView(summary)
        val list = vertical(ctx)
        if (snapshot.requests.isEmpty()) {
            list.addView(text(ctx, "No addon request details were recorded.", 13f, colors.secondary))
        } else {
            snapshot.requests.sortedWith(compareBy<StreamRequestDiagnostic> { it.status == "OK" }.thenByDescending { it.elapsedMs })
                .forEach { item ->
                    val status = if (item.resultCount > 0) {
                        "${item.status} · ${item.resultCount} items"
                    } else item.status
                    val card = vertical(ctx, dp(ctx, 10)).apply {
                        background = rounded(ctx, colors.background, colors.outline)
                        addView(text(
                            ctx,
                            "${item.addonName} · ${item.resource}",
                            13f,
                            colors.primary,
                            true,
                        ))
                        addView(text(ctx, "$status · ${item.elapsedMs} ms", 12f, colors.secondary).apply {
                            setPadding(0, dp(ctx, 3), 0, 0)
                        })
                    }
                    list.addView(card, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 7) })
                }
        }
        val scroll = ScrollView(ctx).apply {
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 300), dp(ctx, maxOf(72, snapshot.requests.size * 62))),
        ).apply { topMargin = dp(ctx, 10) })
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { mainMenu(ctx) }
        val report = buildString {
            appendLine("MultiStremio streaming diagnostics")
            appendLine("Time: $time")
            appendLine("Duration: ${snapshot.durationMs} ms")
            appendLine("Raw streams: ${snapshot.rawStreams}")
            appendLine("Addon subtitles: ${snapshot.addonSubtitles}")
            appendLine("Playable before filters: ${snapshot.playableCandidates}")
            appendLine("Kept after filters/deduplication: ${snapshot.keptAfterFilters}")
            appendLine("Links handed to CloudStream: ${snapshot.emittedLinks}")
            appendLine("Parsing work (parallel totals): ${snapshot.parseWorkMs} ms")
            appendLine("Filter/dedup/sort: ${snapshot.pipelineMs} ms")
            appendLine("Link emission: ${snapshot.emissionMs} ms")
            snapshot.requests.forEach { item ->
                val outcome = if (item.resultCount > 0) "${item.status} (${item.resultCount} items)" else item.status
                appendLine("${item.addonName} | ${item.resource} | $outcome | ${item.elapsedMs} ms")
            }
        }
        addFooter(ctx, root, "Back", {
            dialog.dismiss()
            mainMenu(ctx)
        }, "Copy report", {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MultiStremio streaming diagnostics", report))
            toast(ctx, "Diagnostics copied. The report contains no addon URLs.")
        })
        window(dialog, ctx)
    }

    // ---- addons ----------------------------------------------------------------------------

    private fun addonsMenu(ctx: Context) {
        val addons = Config.settings.addons
        val rows = addons.mapIndexed { i, a ->
            val caps = listOfNotNull(
                if (a.useCatalogs) "catalogs" else null, if (a.useMeta) "meta" else null,
                if (a.useStreams) "streams" else null, if (a.useSubtitles) "subs" else null,
            ).joinToString("/")
            "${i + 1}. ${if (a.enabled) "✓" else "✗"} ${a.name} (${host(a.url)}) $caps"
        } + listOf("Check all addon connections", "+ Add addon")
        menu(ctx, "Addons", rows, { mainMenu(ctx) }, "Enable or disable sources, set their priority, and check whether addon manifests are reachable.") { which ->
            when {
                which < addons.size -> addonMenu(ctx, addons[which])
                which == addons.size -> checkAddonConnections(ctx)
                else -> addAddon(ctx)
            }
        }
    }

    private data class AddonHealthResult(
        val name: String,
        val success: Boolean,
        val elapsedMs: Long,
        val detail: String,
    )

    /** Checks addon manifests off the UI thread, with bounded concurrency and no URL/token exposure. */
    private fun checkAddonConnections(ctx: Context) {
        val installed = Config.snapshot().addons
        if (installed.isEmpty()) {
            toast(ctx, "Add an addon before running a connection check.")
            addonsMenu(ctx)
            return
        }
        var hidden = false
        val progressDialog = progress(
            ctx,
            "Checking addon connections",
            "Checking ${installed.size} addon(s). Hide this dialog to leave settings; the checks will continue.",
        ) { hidden = true }

        thread(start = true, name = "multistremio-health-check") {
            val executor = Executors.newFixedThreadPool(minOf(4, installed.size))
            val results = try {
                installed.map { addon ->
                    addon to executor.submit<AddonHealthResult> {
                        val started = System.nanoTime()
                        val response = SimpleHttp.getDetailed(Urls.manifest(addon.url), timeoutMs = 8000)
                        val manifest = Json.parse<Manifest>(response.body)
                        val assessment = AddonHealthClassifier.assess(response, manifest)
                        val success = assessment.success
                        val elapsed = (System.nanoTime() - started) / 1_000_000L

                        if (success && response.body != null && manifest != null) {
                            Config.update {
                                val saved = this.addons.firstOrNull {
                                    it.id == addon.id && it.url == addon.url
                                }
                                if (saved != null) {
                                    saved.manifest = response.body
                                    saved.manifestFetchedAt = System.currentTimeMillis()
                                    if (saved.name.isBlank()) saved.name = manifest.name.orEmpty()
                                }
                            }
                        }

                        val cached = Config.manifestOf(addon) != null
                        val detail = if (success) buildString {
                            append(StreamDiagnostics.safeAddonName(manifest?.name?.takeIf { it.isNotBlank() } ?: addon.name))
                            manifest?.version?.takeIf { it.isNotBlank() }?.let { append(" · v").append(it) }
                            append(" · ${manifest?.catalogs.orEmpty().size} catalogs")
                        } else assessment.detail + if (cached) " · saved manifest retained" else ""
                        AddonHealthResult(StreamDiagnostics.safeAddonName(addon.name), success, elapsed, detail)
                    }
                }.map { (addon, future) ->
                    runCatching { future.get() }.getOrElse {
                        AddonHealthResult(StreamDiagnostics.safeAddonName(addon.name), false, 0L, "The connection check could not be completed")
                    }
                }
            } finally {
                executor.shutdownNow()
            }

            main.post {
                progressDialog.dismiss()
                if (hidden) {
                    val usable = results.count { it.success }
                    toast(ctx, "Addon check complete: $usable of ${results.size} returned usable manifests.")
                } else {
                    showAddonHealthReport(ctx, results)
                }
            }
        }
    }

    private fun showAddonHealthReport(ctx: Context, results: List<AddonHealthResult>) {
        val colors = palette(ctx)
        val working = results.count { it.success }
        val root = shell(
            ctx,
            "Addon health",
            "$working of ${results.size} addons returned usable manifests. Checks use an 8-second timeout and never display addon URLs or access tokens.",
        )
        val list = vertical(ctx)
        results.forEach { result ->
            val card = vertical(ctx, dp(ctx, 12)).apply {
                background = rounded(ctx, withAlpha(if (result.success) colors.accent else colors.secondary, 12), colors.outline)
                addView(text(
                    ctx,
                    if (result.success) "✓  ${result.name} · ${result.elapsedMs} ms" else "✕  ${result.name}",
                    14f,
                    if (result.success) colors.accent else colors.primary,
                    true,
                ))
                addView(text(ctx, result.detail, 12f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 4), 0, 0)
                })
            }
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) })
        }
        val scroll = ScrollView(ctx).apply {
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 360), dp(ctx, maxOf(96, results.size * 76))),
        ))
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { addonsMenu(ctx) }
        val report = buildString {
            appendLine("MultiStremio addon health: $working/${results.size} usable manifests")
            results.forEach {
                append(if (it.success) "OK" else "FAILED")
                append(" · ").append(it.name)
                if (it.elapsedMs > 0) append(" · ").append(it.elapsedMs).append(" ms")
                append(" · ").append(it.detail).append('\n')
            }
        }
        addFooter(ctx, root, "Back", {
            dialog.dismiss()
            addonsMenu(ctx)
        }, "Copy report", {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MultiStremio addon health", report))
            toast(ctx, "Connection report copied")
        })
        window(dialog, ctx)
    }

    private fun addAddon(ctx: Context) {
        prompt(
            ctx, "Add addon",
            "Paste the addon's manifest link (https://…/manifest.json or stremio://…). " +
                "Links often contain your personal keys, so keep them private.",
            "", false,
            onCancel = { addonsMenu(ctx) },
        ) { raw ->
            val url = Urls.normalizeAddonUrl(raw)
            if (url.isEmpty()) {
                toast(ctx, "Enter a valid addon manifest link.")
                addonsMenu(ctx)
                return@prompt
            }
            if (Config.snapshot().addons.any { it.url == url }) {
                toast(ctx, "That addon is already added.")
                addonsMenu(ctx)
                return@prompt
            }

            var hidden = false
            val progressDialog = progress(
                ctx,
                "Checking addon",
                "Fetching and validating its manifest. Hide this dialog to leave settings; the add operation will continue.",
            ) { hidden = true }

            thread(start = true, name = "multistremio-add-addon") {
                val manifestText = SimpleHttp.get(Urls.manifest(url))
                val manifest = Json.parse<Manifest>(manifestText)
                main.post {
                    progressDialog.dismiss()
                    if (manifestText == null || manifest == null || !manifest.isUsableByMultiStremio()) {
                        toast(ctx, "Could not add this addon: its manifest is invalid or unreachable.")
                        if (!hidden) addonsMenu(ctx)
                        return@post
                    }

                    val addon = AddonConfig(
                        id = Config.idFor(url),
                        url = url,
                        name = manifest.name?.takeIf { it.isNotBlank() } ?: host(url),
                        useCatalogs = manifest.hasResource("catalog") && manifest.catalogs.orEmpty().isNotEmpty(),
                        useMeta = manifest.hasResource("meta"),
                        useStreams = manifest.hasResource("stream"),
                        useSubtitles = manifest.hasResource("subtitles"),
                        manifest = manifestText,
                        manifestFetchedAt = System.currentTimeMillis(),
                    )
                    var added = false
                    // Structural mutations happen on the UI thread, where the settings menus read
                    // this list. This avoids racing a menu iteration against a background append.
                    Config.update {
                        if (addons.none { it.url == url }) {
                            addons.add(addon)
                            added = true
                        }
                    }
                    toast(ctx, if (added) {
                        "Added ${addon.name}. Refresh the home page to see its catalogs."
                    } else {
                        "That addon was added by another request."
                    })
                    if (!hidden) addonsMenu(ctx)
                }
            }
        }
    }

    private fun addonMenu(ctx: Context, a: AddonConfig) {
        val addons = Config.settings.addons
        menu(
            ctx, a.name,
            listOf(
                if (a.enabled) "Disable" else "Enable",
                "Use for: catalogs / meta / streams / subtitles",
                "Home catalogs to show",
                "Move up (higher priority)",
                "Move down",
                "Rename",
                "Refresh & test manifest",
                "Remove",
            ),
            { addonsMenu(ctx) },
            "Addon priority affects source tie-breaking and metadata fallback. It does not change homepage row order; arrange homepage rows separately in Catalog rows.",
            listOf(
                "Enable or disable this addon without deleting its saved settings.",
                "Choose whether it supplies catalogs, metadata, streams or subtitles.",
                "Show or hide individual homepage catalog rows.",
                "Give this addon higher priority when multiple sources return matching links or metadata.",
                "Give this addon lower source priority.",
                "Change the display name shown inside MultiStremio.",
                "Download the manifest again and update its cached capabilities.",
                "Remove this addon and its saved homepage catalog rows from MultiStremio.",
            ),
        ) { which ->
            when (which) {
                0 -> { Config.update { a.enabled = !a.enabled }; addonMenu(ctx, a) }
                1 -> multi(
                    ctx, "${a.name} is used for",
                    listOf("Catalogs & search", "Metadata (details/episodes)", "Streams", "Subtitles"),
                    listOf(a.useCatalogs, a.useMeta, a.useStreams, a.useSubtitles),
                    onCancel = { addonMenu(ctx, a) },
                ) { v ->
                    Config.update { a.useCatalogs = v[0]; a.useMeta = v[1]; a.useStreams = v[2]; a.useSubtitles = v[3] }
                    addonMenu(ctx, a)
                }
                2 -> catalogPicker(ctx, a)
                3 -> {
                    if (move(a, -1)) toast(ctx, "Addon priority increased.")
                    else toast(ctx, "This addon is already at the top of the priority list.")
                    addonsMenu(ctx)
                }
                4 -> {
                    if (move(a, +1)) toast(ctx, "Addon priority decreased.")
                    else toast(ctx, "This addon is already at the bottom of the priority list.")
                    addonsMenu(ctx)
                }
                5 -> prompt(ctx, "Rename addon", null, a.name, false, onCancel = { addonMenu(ctx, a) }) { n ->
                    if (n.isNotEmpty()) Config.update { a.name = n }
                    addonMenu(ctx, a)
                }
                6 -> {
                    var hidden = false
                    val progressDialog = progress(
                        ctx,
                        "Refreshing addon",
                        "Downloading and validating the manifest. Hide this dialog to leave settings; the refresh will continue.",
                    ) { hidden = true }
                    thread(start = true, name = "multistremio-refresh-addon") {
                        val ok = Config.refreshManifest(a)
                        val m = Config.manifestOf(a)
                        val resources = m?.resources.orEmpty().mapNotNull {
                            if (it is String) it else (it as? Map<*, *>)?.get("name") as? String
                        }.distinct()

                        main.post {
                            progressDialog.dismiss()
                            toast(
                                ctx,
                                if (ok) "OK: ${m?.name ?: a.name} v${m?.version ?: "?"}, ${m?.catalogs.orEmpty().size} catalogs, " +
                                    "provides ${resources.joinToString(", ").ifEmpty { "nothing" }}"
                                else "Could not download the manifest",
                            )
                            if (!hidden) {
                                val current = Config.snapshot().addons.firstOrNull { it.id == a.id && it.url == a.url }
                                if (current != null) addonMenu(ctx, current) else addonsMenu(ctx)
                            }
                        }
                    }
                }
                7 -> confirm(ctx, "Remove ${a.name}?", "This only removes it from MultiStremio. Its catalog rows and saved row-order entries will also be removed.", onCancel = { addonMenu(ctx, a) }) {
                    Config.update {
                        addons.remove(a)
                        val addonPrefix = a.id + "|"
                        catalogOrder.removeAll { it.startsWith(addonPrefix) }
                    }
                    addonsMenu(ctx)
                }
            }
        }
    }

    private fun move(a: AddonConfig, delta: Int): Boolean {
        val list = Config.settings.addons
        val i = list.indexOf(a)
        val j = i + delta
        if (i < 0 || j !in list.indices) return false
        Config.update { list[i] = list[j].also { list[j] = list[i] } }
        return true
    }

    private fun catalogPicker(ctx: Context, a: AddonConfig) {
        val manifest = Config.manifestOf(a)
        val cats = manifest?.let { current ->
            current.catalogs.orEmpty().filter {
                val type = it.type
                type != null && it.id != null && it.isHomeCatalog() &&
                    current.supports("catalog", type, null)
            }
        }.orEmpty()
        if (cats.isEmpty()) { toast(ctx, "This addon has no browsable catalogs"); addonMenu(ctx, a); return }
        val keys = cats.map { "${it.type}/${it.id}" }
        multi(
            ctx, "Catalogs on the home page",
            cats.map { "${it.name ?: it.id} (${it.type})" },
            keys.map { it !in a.hiddenCatalogs },
            onCancel = { addonMenu(ctx, a) },
        ) { v ->
            Config.update {
                a.hiddenCatalogs = keys.filterIndexed { i, _ -> !v[i] }.toMutableList()
            }
            toast(ctx, "Saved. Refresh the home page to apply.")
            addonMenu(ctx, a)
        }
    }

    // ---- global catalog row manager ---------------------------------------------------------

    private data class CatalogRow(
        val key: String,
        val sourceLabel: String,
        val name: String,
        val status: String,
        val rowEnabled: Boolean,
        val addon: AddonConfig? = null,
        val type: String? = null,
        val id: String? = null,
        val external: ExternalRowConfig? = null,
    )

    /**
     * Combines Stremio catalogs and selected rows from installed CloudStream extensions.
     * Unavailable external rows remain visible here so users can remove stale references.
     */
    private fun catalogRows(): List<CatalogRow> {
        val rows = mutableListOf<CatalogRow>()
        for (addon in Config.settings.addons) {
            val manifest = Config.manifestOf(addon) ?: continue
            for (catalog in manifest.catalogs.orEmpty()) {
                val type = catalog.type ?: continue
                val id = catalog.id ?: continue
                if (!catalog.isHomeCatalog()) continue
                val providesCatalog = manifest.supports("catalog", type, null)
                val enabled = "$type/$id" !in addon.hiddenCatalogs
                val status = when {
                    !addon.enabled -> "ADDON OFF"
                    !addon.useCatalogs -> "CATALOGS OFF"
                    !providesCatalog -> "RESOURCE MISSING"
                    enabled -> "ON"
                    else -> "HIDDEN"
                }
                rows += CatalogRow(
                    catalogOrderKey(addon.id, type, id), addon.name, catalog.name ?: id,
                    status, enabled, addon, type, id,
                )
            }
        }

        val installed = ExternalHomeRows.installedRows()
        for (external in Config.settings.externalRows) {
            val available = ExternalHomeRows.resolve(external, installed) != null
            rows += CatalogRow(
                key = external.key,
                sourceLabel = external.providerName,
                name = external.rowName,
                status = when {
                    !available -> "EXTENSION MISSING"
                    external.enabled -> "ON"
                    else -> "HIDDEN"
                },
                rowEnabled = external.enabled,
                external = external,
            )
        }

        val positions = Config.settings.catalogOrder.withIndex().associate { it.value to it.index }
        return rows.distinctBy { it.key }.withIndex()
            .sortedWith(
                compareBy<IndexedValue<CatalogRow>> { positions[it.value.key] ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    private fun catalogRowsMenu(ctx: Context) {
        val rows = catalogRows()
        val colors = palette(ctx)
        val root = shell(
            ctx,
            "Catalog rows",
            "Drag the ☰ grip to reorder rows; no long-press is needed. Swipe row text to scroll, or tap it to manage visibility. Move up/down is also available in each row's menu. Save order when finished.",
        )
        val scroll = ScrollView(ctx).apply { clipToPadding = false }
        val content = vertical(ctx)
        val list = vertical(ctx)
        val rowNames = rows.associate { it.key to it.name }
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnCancelListener { mainMenu(ctx) }

        fun refreshRowNumbers() {
            for (position in 0 until list.childCount) {
                val item = list.getChildAt(position) as? LinearLayout ?: continue
                val key = item.tag as? String ?: continue
                val description = item.getChildAt(0) as? LinearLayout ?: continue
                val title = description.getChildAt(0) as? TextView ?: continue
                title.text = (position + 1).toString() + ". " + rowNames[key].orEmpty()
            }
        }

        rows.forEachIndexed { index, row ->
            val source = row.external?.let { "Extension · " + it.providerName }
                ?: row.sourceLabel + " · " + row.type
            val description = vertical(ctx).apply {
                addView(text(ctx, (index + 1).toString() + ". " + row.name, 14f, colors.primary, true))
                addView(text(
                    ctx,
                    source + " · " + row.status + " · Tap to manage",
                    11f,
                    colors.secondary,
                ).apply {
                    setPadding(0, dp(ctx, 3), 0, 0)
                })
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
                setOnClickListener {
                    dialog.dismiss()
                    catalogRowMenu(ctx, row)
                }
                isClickable = true
            }
            val handle = text(ctx, "☰", 23f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
                contentDescription = "Drag to reorder " + row.name
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 7), dp(ctx, 9))
                background = rounded(ctx, colors.background, colors.outline)
                minimumHeight = dp(ctx, 66)
                tag = row.key
                addView(description, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)))
            }
            list.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 7) })
            installDragReordering(ctx, handle, item, list, scroll, colors) {
                refreshRowNumbers()
            }
        }

        if (rows.isEmpty()) {
            content.addView(text(
                ctx,
                "No catalog rows are available yet. Add a Stremio addon or import a row from another installed extension.",
                13f,
                colors.secondary,
            ).apply {
                setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
            })
        } else {
            content.addView(list)
        }

        content.addView(text(ctx, "MORE ACTIONS", 10f, colors.accent, true).apply {
            letterSpacing = 0.10f
            setPadding(dp(ctx, 4), dp(ctx, 10), 0, dp(ctx, 7))
        })

        fun addAction(label: String, detail: String, action: () -> Unit) {
            val body = vertical(ctx).apply {
                addView(text(ctx, label, 14f, colors.primary, true))
                addView(text(ctx, detail, 11f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 3), 0, 0)
                })
            }
            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
                background = ripple(ctx, colors.background, colors)
                minimumHeight = dp(ctx, 58)
                addView(body, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(text(ctx, "›", 24f, colors.secondary).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.MATCH_PARENT))
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            content.addView(actionRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 5) })
        }

        addAction(
            "Add rows from installed extensions",
            "Import a homepage row from another CloudStream extension.",
        ) { addExternalRowsMenu(ctx) }
        addAction(
            "Reset row order",
            "Return to the default addon order.",
        ) {
            confirm(
                ctx,
                "Reset catalog row order?",
                "Rows will return to Stremio addon order, followed by newly added extension rows.",
                onCancel = { catalogRowsMenu(ctx) },
            ) {
                Config.update { catalogOrder.clear() }
                toast(ctx, "Catalog row order reset. Refresh the home page to apply.")
                catalogRowsMenu(ctx)
            }
        }
        addAction(
            "Unhide all Stremio catalog rows",
            "Make every Stremio catalog row visible again.",
        ) {
            confirm(
                ctx,
                "Unhide every Stremio catalog row?",
                "This clears row-level hiding. Disabled addons and disabled catalog use remain inactive. Extension rows are unchanged.",
                onCancel = { catalogRowsMenu(ctx) },
            ) {
                Config.update { addons.forEach { it.hiddenCatalogs.clear() } }
                toast(ctx, "Stremio catalog rows unhidden. Refresh the home page to apply.")
                catalogRowsMenu(ctx)
            }
        }

        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(190, rows.size * 76 + 230))),
        ))
        addFooter(ctx, root, "Save order", {
            val orderedKeys = (0 until list.childCount)
                .mapNotNull { list.getChildAt(it).tag as? String }
            if (orderedKeys.size == rows.size && orderedKeys.toSet() == rows.map { it.key }.toSet()) {
                Config.update { catalogOrder = orderedKeys.toMutableList() }
                dialog.dismiss()
                toast(ctx, "Row order saved. Refresh the home page to apply.")
                catalogRowsMenu(ctx)
            } else {
                toast(ctx, "Could not save the row order. Please try again.")
            }
        }, "Back", {
            dialog.dismiss()
            mainMenu(ctx)
        })
        window(dialog, ctx)
    }

    private fun addExternalRowsMenu(ctx: Context) {
        val selected = Config.settings.externalRows.map { it.key }.toSet()
        val installed = ExternalHomeRows.installedRows()
        val candidates = installed.filter { it.config.key !in selected }
        if (candidates.isEmpty()) {
            val message = if (installed.isEmpty()) {
                "No other installed extensions with homepage rows were found."
            } else {
                "All available extension homepage rows have already been added."
            }
            menu(ctx, "Add rows from installed extensions", listOf(message), { catalogRowsMenu(ctx) }) { }
            return
        }

        val labels = candidates.map { "${it.config.providerName} · ${it.row.name}" }
        menu(ctx, "Choose an installed extension homepage row", labels, { catalogRowsMenu(ctx) }) { which ->
            val chosen = candidates[which].config
            // Capture the current effective order before writing a new key. Appending only the
            // imported key to an empty order list would sort it ahead of every unsaved catalog.
            val currentOrder = catalogRows().map { it.key }
            Config.update {
                if (externalRows.none { it.key == chosen.key }) externalRows.add(chosen)
                catalogOrder = CatalogOrderMath.append(currentOrder, chosen.key).toMutableList()
            }
            toast(ctx, "Added ${chosen.rowName} at the end of your rows. Refresh the home page to apply.")
            catalogRowsMenu(ctx)
        }
    }

    private fun moveCatalogRow(ctx: Context, row: CatalogRow, delta: Int) {
        val currentOrder = catalogRows().map { it.key }
        if (row.key !in currentOrder) {
            toast(ctx, "This row is no longer available. Refresh the settings.")
            catalogRowsMenu(ctx)
            return
        }
        val updatedOrder = CatalogOrderMath.move(currentOrder, row.key, delta)
        if (updatedOrder == currentOrder) {
            toast(ctx, if (delta < 0) "This row is already first." else "This row is already last.")
        } else {
            Config.update { catalogOrder = updatedOrder.toMutableList() }
            toast(ctx, "Row order updated. Refresh the home page to apply.")
        }
        catalogRowsMenu(ctx)
    }

    private fun catalogRowMenu(ctx: Context, row: CatalogRow) {
        val external = row.external
        val labels = listOf(
            if (row.rowEnabled) "Hide this row" else "Show this row",
            "Move one position up",
            "Move one position down",
            if (external != null) "Remove row from MultiStremio" else "Manage addon settings",
        )
        menu(
            ctx,
            row.sourceLabel + " · " + row.name,
            labels,
            { catalogRowsMenu(ctx) },
        ) { which ->
            when (which) {
                0 -> {
                    if (external != null) {
                        Config.update {
                            externalRows.firstOrNull { it.key == external.key }?.enabled = !row.rowEnabled
                        }
                    } else {
                        val addon = row.addon
                        val type = row.type
                        val id = row.id
                        if (addon != null && type != null && id != null) {
                            Config.update {
                                val localKey = "$type/$id"
                                if (row.rowEnabled) addon.hiddenCatalogs.add(localKey)
                                else addon.hiddenCatalogs.remove(localKey)
                            }
                        }
                    }
                    toast(ctx, "Saved row visibility. Refresh the home page to apply.")
                    catalogRowsMenu(ctx)
                }
                1 -> moveCatalogRow(ctx, row, -1)
                2 -> moveCatalogRow(ctx, row, 1)
                3 -> {
                    if (external != null) {
                        confirm(ctx, "Remove extension row?", "This only removes the row from MultiStremio. The source extension remains installed.", onCancel = { catalogRowsMenu(ctx) }) {
                            Config.update {
                                externalRows.removeAll { it.key == external.key }
                                catalogOrder.removeAll { it == external.key }
                            }
                            toast(ctx, "Extension row removed.")
                            catalogRowsMenu(ctx)
                        }
                    } else {
                        row.addon?.let { addonMenu(ctx, it) } ?: catalogRowsMenu(ctx)
                    }
                }
            }
        }
    }

    // ---- filters ---------------------------------------------------------------------------

    private val RES_OPTIONS = listOf(2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p",
        576 to "576p", 480 to "480p", 360 to "360p", 240 to "240p", 0 to "Unknown")

    private fun filtersMenu(ctx: Context) {
        val f = Config.settings.filter
        val rows = listOf(
            "Resolutions: " + RES_OPTIONS.filter { it.first in f.allowedResolutions }.joinToString(" ") { it.second }
                .ifBlank { "Any (no restriction)" },
            "Hide CAM / telesync: ${onOff(f.hideCam)}",
            "Hide magnet (torrent) links: ${onOff(f.hideMagnets)}",
            "Hide uncached debrid links: ${onOff(f.hideUncachedDebrid)}",
            "HEVC / x265: ${modeLabel(f.hevcMode)}",
            "HDR / Dolby Vision: ${modeLabel(f.hdrMode)}",
            "Min size: ${limit(f.minSizeMb, "MB")}",
            "Max size: ${limit(f.maxSizeGb, "GB")}",
            "Min seeders: ${limit(f.minSeeders, "")}",
            "Exclude keywords: ${csv(f.excludeKeywords)}",
            "Require keywords (any): ${csv(f.requireKeywords)}",
            "Required languages: ${csv(f.requireLanguages)}",
            "Strict language (drop streams with unknown language): ${onOff(f.strictLanguage)}",
            "Max results per addon: ${limit(f.maxPerAddon, "")}",
            "Max results total: ${limit(f.maxTotal, "")}",
        )
        val again = { filtersMenu(ctx) }
        menu(ctx, "Stream filters", rows, { mainMenu(ctx) }, "Remove unwanted sources and narrow results by quality, size, language and keywords.") { which ->
            when (which) {
                0 -> multi(
                    ctx,
                    "Allowed resolutions",
                    RES_OPTIONS.map { it.second },
                    RES_OPTIONS.map { it.first in f.allowedResolutions },
                    description = "Select which resolutions to allow. Leave every box unchecked to allow any resolution.",
                    onCancel = again,
                ) { v ->
                    Config.update { f.allowedResolutions = RES_OPTIONS.filterIndexed { i, _ -> v[i] }.map { it.first }.toMutableList() }
                    again()
                }
                1 -> { Config.update { f.hideCam = !f.hideCam }; again() }
                2 -> { Config.update { f.hideMagnets = !f.hideMagnets }; again() }
                3 -> { Config.update { f.hideUncachedDebrid = !f.hideUncachedDebrid }; again() }
                4 -> choose(ctx, "HEVC / x265", listOf("Show any", "Hide HEVC", "Only HEVC"), f.hevcMode, onCancel = again) {
                    Config.update { f.hevcMode = it }; again()
                }
                5 -> choose(ctx, "HDR / Dolby Vision", listOf("Show any", "Hide HDR/DV", "Only HDR/DV"), f.hdrMode, onCancel = again) {
                    Config.update { f.hdrMode = it }; again()
                }
                6 -> number(ctx, "Min size (MB, 0 = off)", f.minSizeMb, onCancel = again) { Config.update { f.minSizeMb = it }; again() }
                7 -> number(ctx, "Max size (GB, 0 = off)", f.maxSizeGb, onCancel = again) { Config.update { f.maxSizeGb = it }; again() }
                8 -> number(ctx, "Min seeders (0 = off). Streams without seeder info are kept.", f.minSeeders, onCancel = again) {
                    Config.update { f.minSeeders = it }; again()
                }
                9 -> keywordList(
                    ctx,
                    "Exclude keywords",
                    "Comma separated, case-insensitive. Wrap in slashes for a regex, e.g. /x26[45]/. Invalid regex patterns are rejected.",
                    f.excludeKeywords.joinToString(", "),
                    onCancel = again,
                ) { values -> Config.update { f.excludeKeywords = values }; again() }
                10 -> keywordList(
                    ctx,
                    "Require keywords",
                    "A stream must contain at least one of these. Leave empty to disable. Wrap regex patterns in slashes, e.g. /x26[45]/.",
                    f.requireKeywords.joinToString(", "),
                    onCancel = again,
                ) { values -> Config.update { f.requireKeywords = values }; again() }
                11 -> prompt(ctx, "Required languages",
                    "Codes, regional tags or names, e.g. en, eng, en-US, pt-BR, Bengali. Streams with no detectable language are kept unless strict mode is on. Leave empty to disable.",
                    f.requireLanguages.joinToString(", "), false, onCancel = again) { Config.update { f.requireLanguages = parseCsv(it) }; again() }
                12 -> { Config.update { f.strictLanguage = !f.strictLanguage }; again() }
                13 -> number(ctx, "Max results per addon (0 = off)", f.maxPerAddon, onCancel = again) { Config.update { f.maxPerAddon = it }; again() }
                14 -> number(ctx, "Max results total (0 = off)", f.maxTotal, onCancel = again) { Config.update { f.maxTotal = it }; again() }
            }
        }
    }


    private fun number(
        ctx: Context,
        title: String,
        current: Int,
        description: String = "Enter a whole number. Use 0 to turn this limit off.",
        onCancel: (() -> Unit)? = null,
        onOk: (Int) -> Unit,
    ) {
        fun ask(initial: String) {
            prompt(ctx, title, description, initial, true, onCancel) { value ->
                val parsed = value.toIntOrNull()
                if (parsed == null && value.isNotBlank()) {
                    toast(ctx, "That number is too large or invalid. Nothing was saved.")
                    ask(value)
                } else {
                    onOk((parsed ?: 0).coerceAtLeast(0))
                }
            }
        }
        ask(current.toString())
    }

    // ---- sorting ---------------------------------------------------------------------------

    private fun sortMenu(ctx: Context) {
        val s = Config.settings.sort
        val preset = SortPreset.from(s.preset)
        val rows = listOf(
            "Order: ${preset.label}",
            "Cached debrid links first: ${onOff(s.cachedFirst)}",
            "Prefer keywords: ${csv(s.preferKeywords)}",
            "Prefer languages: ${csv(s.preferLanguages)}",
            "Prefer video codecs: ${csv(s.preferCodecs)}",
            "Prefer audio formats: ${csv(s.preferAudio)}",
            "Use my order across resolutions: ${onOff(s.overrideAppQuality)}",
        )
        val again = { sortMenu(ctx) }
        menu(ctx, "Stream sorting", rows, { mainMenu(ctx) }, "Choose how available streams are ranked. The first item in each preference list has the highest priority; leave a list empty to disable that preference.") { which ->
            when (which) {
                0 -> choose(ctx, "Sort order", SortPreset.values().map { it.label }, preset.ordinal, onCancel = again) {
                    Config.update { s.preset = SortPreset.values()[it].name }; again()
                }
                1 -> { Config.update { s.cachedFirst = !s.cachedFirst }; again() }
                2 -> keywordList(
                    ctx,
                    "Prefer keywords",
                    "Streams containing any of these go first. Comma separated. Wrap regular expressions in slashes, e.g. /x26[45]/.",
                    s.preferKeywords.joinToString(", "),
                    onCancel = again,
                ) { values -> Config.update { s.preferKeywords = values }; again() }
                3 -> prompt(ctx, "Prefer languages", "e.g. bn, en, hi. Streams matching an earlier language go first.",
                    s.preferLanguages.joinToString(", "), false, onCancel = again) { Config.update { s.preferLanguages = parseCsv(it) }; again() }
                4 -> prompt(ctx, "Prefer video codecs", "Enter a priority order, comma separated. Example: HEVC, AV1, AVC. Unlisted and unknown codecs go after listed codecs. This does not filter streams.",
                    s.preferCodecs.joinToString(", "), false, onCancel = again) { Config.update { s.preferCodecs = parseCsv(it) }; again() }
                5 -> prompt(ctx, "Prefer audio formats", "Enter a priority order, comma separated. Example: Atmos, TrueHD, DTS-HD, DD+, AAC. Unlisted and unknown formats go after listed formats.",
                    s.preferAudio.joinToString(", "), false, onCancel = again) { Config.update { s.preferAudio = parseCsv(it) }; again() }
                6 -> confirm(
                    ctx, "Use my order across resolutions?",
                    "CloudStream normally ranks links by its own quality profile first and only keeps this plugin's " +
                        "order between links of equal quality. Turning this ON makes every link report " +
                        "'unknown quality' so the order chosen here is shown exactly. Resolution still appears " +
                        "in each link's name. Currently ${onOff(s.overrideAppQuality)}; switch it?",
                    onCancel = again,
                ) { Config.update { s.overrideAppQuality = !s.overrideAppQuality }; again() }
            }
        }
    }

    // ---- subtitles / general / backup ------------------------------------------------------

    private fun subtitlesMenu(ctx: Context) {
        val s = Config.settings
        val rows = listOf(
            "Fetch from subtitle addons: ${onOff(s.fetchSubtitles)}",
            "Language filter: ${s.subtitleLanguages.takeIf { it.isNotEmpty() }?.let { csv(it) } ?: "All languages"}",
        )
        menu(ctx, "Subtitles", rows, { mainMenu(ctx) }, "Choose whether subtitle addons are queried and which languages are preferred.") { which ->
            when (which) {
                0 -> { Config.update { fetchSubtitles = !fetchSubtitles }; subtitlesMenu(ctx) }
                1 -> prompt(ctx, "Subtitle languages", "Codes, regional tags or names, e.g. en, eng, en-US, pt-BR, Bengali. Leave empty for all languages.",
                    s.subtitleLanguages.joinToString(", "), false, onCancel = { subtitlesMenu(ctx) }) {
                    Config.update { subtitleLanguages = parseCsv(it) }; subtitlesMenu(ctx)
                }
            }
        }
    }

    private fun generalMenu(ctx: Context) {
        val s = Config.settings
        val rows = listOf(
            "Request timeout: ${s.timeoutSec} s",
            "Detailed default names (when custom layout is OFF): ${onOff(s.detailedLabels)}",
        )
        menu(ctx, "General", rows, { mainMenu(ctx) }, "Tune request limits and the amount of technical detail shown on stream links.") { which ->
            when (which) {
                0 -> number(
                    ctx,
                    "Timeout per addon request (seconds)",
                    s.timeoutSec,
                    description = "Enter 5–180 seconds. This controls how long each addon request can wait before timing out.",
                    onCancel = { generalMenu(ctx) },
                ) {
                    Config.update { timeoutSec = it.coerceIn(5, 180) }; generalMenu(ctx)
                }
                1 -> { Config.update { detailedLabels = !detailedLabels }; generalMenu(ctx) }
            }
        }
    }

    private fun backupMenu(ctx: Context) {
        fun copyBackup(includeAddonUrls: Boolean) {
            val payload = runCatching {
                SettingsBackupManager.export(Config.settings, includeAddonUrls)
            }.getOrElse {
                toast(ctx, "Could not create the backup")
                return
            }
            runCatching {
                val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("MultiStremio backup", payload))
            }.onSuccess {
                toast(
                    ctx,
                    if (includeAddonUrls) {
                        "Full backup copied. The clipboard contains private addon URLs and may contain access keys."
                    } else {
                        "Preferences-only backup copied. Addon sources and imported extension rows were excluded."
                    },
                )
                main.post { backupMenu(ctx) }
            }.onFailure {
                toast(ctx, "Could not copy the backup to the clipboard")
            }
        }

        menu(
            ctx, "Backup & maintenance",
            listOf(
                "Copy preferences-only backup (no source URLs)",
                "Copy full backup (includes addon URLs)",
                "Restore from backup",
                "Reset filters & sorting",
                "Remove ALL addons and settings",
            ),
            { mainMenu(ctx) },
            "Choose a privacy level before exporting. Full backups may contain private access keys in addon URLs.",
        ) { which ->
            when (which) {
                0 -> confirm(
                    ctx,
                    "Copy preferences-only backup?",
                    "This export excludes all Stremio addon URLs, manifests and imported homepage-row references. " +
                        "Restoring it will not restore your sources; you will need to add them again.",
                    onCancel = { backupMenu(ctx) },
                ) { copyBackup(includeAddonUrls = false) }
                1 -> confirm(
                    ctx,
                    "Copy full backup?",
                    "This includes addon endpoints and cached manifests. Addon URLs may contain debrid keys or other " +
                        "credentials. The clipboard is not encrypted. Do not share this backup publicly.",
                    onCancel = { backupMenu(ctx) },
                ) { copyBackup(includeAddonUrls = true) }
                2 -> prompt(
                    ctx,
                    "Restore backup",
                    "Paste a MultiStremio backup or settings JSON copied from an older version. This replaces the current configuration.",
                    "",
                    false,
                    onCancel = { backupMenu(ctx) },
                ) { pasted ->
                    val backup = SettingsBackupManager.parse(pasted)
                    val restored = backup?.settings
                    if (backup == null || restored == null) {
                        toast(ctx, "That is not a supported MultiStremio backup")
                        backupMenu(ctx)
                    } else {
                        val warning = if (backup.includesAddonUrls) {
                            "This backup contains addon source URLs and/or imported-extension references. They may expose access keys or private repository addresses. It will replace all current settings."
                        } else {
                            "This preferences-only backup contains no addon sources or imported-extension references. Restoring it will remove your current addons and imported homepage rows."
                        }
                        confirm(ctx, "Restore this backup?", warning, onCancel = { backupMenu(ctx) }) {
                            restored.seeded = true
                            Config.replace(restored)
                            toast(ctx, "Backup restored. Refresh the home page.")
                            mainMenu(ctx)
                        }
                    }
                }
                3 -> confirm(ctx, "Reset filters & sorting?", "Addons are kept.", onCancel = { backupMenu(ctx) }) {
                    Config.update { filter = FilterSettings(); sort = SortSettings() }
                    toast(ctx, "Filters and sorting reset.")
                    backupMenu(ctx)
                }
                4 -> confirm(ctx, "Remove everything?", "All addons and settings are deleted (Cinemeta is not re-added).", onCancel = { backupMenu(ctx) }) {
                    Config.replace(Settings(seeded = true))
                    toast(ctx, "All MultiStremio settings cleared.")
                    mainMenu(ctx)
                }
            }
        }
    }
}
