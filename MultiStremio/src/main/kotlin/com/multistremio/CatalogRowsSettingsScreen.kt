package com.multistremio

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object CatalogRowsSettingsScreen {
    fun show(ctx: Context) = catalogRowsMenu(ctx)

    // ---- global catalog row manager ---------------------------------------------------------

    private data class CatalogRow(
        val key: String,
        val sourceLabel: String,
        val name: String,
        val displayName: String,
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
    private fun catalogRows(installedRows: List<InstalledExternalHomeRow> = ExternalHomeRowsCache.cachedOrRefresh()): List<CatalogRow> =
        HomeRows.resolve(Config.settings, installedRows) { Config.manifestOf(it) }.map { row ->
            CatalogRow(
                key = row.key,
                sourceLabel = row.sourceLabel,
                name = row.name,
                displayName = HomeRows.homeTitle(row, Config.settings),
                status = row.status,
                rowEnabled = row.rowEnabled,
                addon = row.addon,
                type = row.type,
                id = row.catalogId,
                external = row.external,
            )
        }

    private fun catalogRowsMenu(ctx: Context) {
        val installedRows = ExternalHomeRowsCache.snapshotIfFresh()
        if (installedRows == null) {
            val loading = shell(ctx, "Catalog rows", "Checking homepage rows from installed extensions…")
            loading.addView(text(ctx, "Loading installed extension rows…", 13f, palette(ctx).secondary).apply {
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 14))
            })
            SettingsDialogHost.show(ctx, "catalog-rows", loading) { MainMenuSettingsScreen.show(ctx) }
            ExternalHomeRowsCache.loadAsync { available ->
                if (SettingsDialogHost.isCurrent("catalog-rows")) catalogRowsMenu(ctx, available)
            }
            return
        }
        catalogRowsMenu(ctx, installedRows)
    }

    private fun catalogRowsMenu(ctx: Context, installedRows: List<InstalledExternalHomeRow>) {
        val rows = catalogRows(installedRows)
        val colors = palette(ctx)
        val root = shell(
            ctx,
            "Homepage rows",
            "Every row has one global position across all sources. Edit a position, then leave the field or press Done: that row moves to the requested slot, rows in between shift automatically, and every row is renumbered. Stremio addon priority follows the first row from each addon."
        )
        HomeRefreshNotice.addTo(ctx, root)

        val orderInputsByKey = linkedMapOf<String, EditText>()
        val rowViewsByKey = linkedMapOf<String, View>()
        val dragHandlesByKey = linkedMapOf<String, View>()
        val rowsByKey = rows.associateBy { it.key }
        var orderedRowKeys = rows.map { it.key }.toMutableList()
        var commitFocusedPosition: (() -> Unit)? = null
        var activeQuery = ""
        var groupBySource = false
        val collapsedSources = rows.filter { it.external != null }.map { it.sourceLabel }.toMutableSet()
        lateinit var appendNextPage: () -> Unit
        var persistDragOrder: (() -> Unit)? = null
        val scroll = IncrementalScrollView(ctx, dp(ctx, 240)) { appendNextPage() }.apply {
            clipToPadding = false
        }

        val content = vertical(ctx, dp(ctx, 8))
        content.addView(text(ctx, "GLOBAL ORDER · ALL SOURCES", 10f, colors.accent, true).apply {
            letterSpacing = 0.10f
            setPadding(dp(ctx, 4), dp(ctx, 4), 0, dp(ctx, 2))
        })

        val rowList = vertical(ctx)
        val rowViewsInOrder = mutableListOf<Pair<CatalogRow, View>>()
        if (rows.isEmpty()) {
            rowList.addView(text(
                ctx,
                "No homepage rows are available yet. Add a Stremio addon or import a row from another installed extension.",
                13f,
                colors.secondary,
            ).apply {
                setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 14))
            })
        }
        fun createRow(index: Int, row: CatalogRow): View {
            val handle = text(ctx, "⋮⋮", 16f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(ctx, 48)
                contentDescription = "Drag to change position of ${row.displayName}"
                isFocusable = true
            }
            dragHandlesByKey[row.key] = handle
            val positionInput = EditText(ctx).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_DONE
                textSize = 13f
                gravity = Gravity.CENTER
                setText((index + 1).toString())
                setSelectAllOnFocus(true)
                setPadding(dp(ctx, 3), 0, dp(ctx, 3), 0)
                background = rounded(ctx, colors.background, colors.outline)
                contentDescription = "Global position of ${row.displayName}"
                isFocusable = true
                isFocusableInTouchMode = true
            }
            orderInputsByKey[row.key] = positionInput

            val name = text(ctx, row.displayName, 13.5f, colors.primary, true).apply {
                setPadding(dp(ctx, 2), 0, 0, 0)
                setOnClickListener { commitFocusedPosition?.invoke(); catalogRowMenu(ctx, row) }
                isClickable = true
            }
            val source = text(ctx, "From ${row.sourceLabel}", 11f, colors.secondary).apply {
                setPadding(dp(ctx, 2), dp(ctx, 2), 0, 0)
            }
            val details = vertical(ctx, dp(ctx, 1)).apply {
                addView(name)
                addView(source)
            }

            val manageImported = if (row.external != null) {
                text(ctx, "⋮", 20f, colors.secondary, true).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Manage or remove imported row ${row.displayName}"
                    setOnClickListener { commitFocusedPosition?.invoke(); catalogRowMenu(ctx, row) }
                }
            } else null

            val visibility = CheckBox(ctx).apply {
                isChecked = row.rowEnabled
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    if (isChecked) colors.accent else colors.secondary,
                )
                contentDescription = if (row.rowEnabled) {
                    "Hide homepage row ${row.displayName}"
                } else {
                    "Show homepage row ${row.displayName}"
                }
                setOnCheckedChangeListener { _, checked ->
                    commitFocusedPosition?.invoke()
                    setRowVisibility(ctx, row, checked)
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        if (checked) colors.accent else colors.secondary,
                    )
                    contentDescription = if (checked) {
                        "Hide homepage row ${row.displayName}"
                    } else {
                        "Show homepage row ${row.displayName}"
                    }
                }
            }

            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                background = rounded(ctx, colors.background, colors.outline)
                minimumHeight = dp(ctx, 58)
                tag = row.key
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 48)))
                addView(positionInput, LinearLayout.LayoutParams(dp(ctx, 46), dp(ctx, 38)).apply {
                    marginEnd = dp(ctx, 7)
                })
                addView(details, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                manageImported?.let {
                    addView(it, LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 40)))
                }
                addView(visibility, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
            }

            rowViewsByKey[row.key] = item
            return item
        }

        fun renderRows() {
            rowList.removeAllViews()
            dragHandlesByKey.values.forEach { handle ->
                handle.visibility = if (groupBySource || activeQuery.isNotBlank()) View.GONE else View.VISIBLE
            }
            if (!groupBySource) {
                orderedRowKeys.mapNotNull { rowViewsByKey[it] }.forEach { view ->
                    rowList.addView(view, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(ctx, 5) })
                }
                return
            }
            orderedRowKeys.mapNotNull { key -> rowsByKey[key]?.let { row -> rowViewsByKey[key]?.let { row to it } } }
                .groupBy { it.first.sourceLabel }.forEach { (source, sourceRows) ->
                val header = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(ctx, 44)
                    setPadding(dp(ctx, 8), 0, dp(ctx, 4), 0)
                    val collapsed = source in collapsedSources
                    val sourceCount = rows.count { it.sourceLabel == source }
                    addView(text(ctx, "${if (collapsed) "▸" else "▾"}  $source ($sourceCount)", 13f, colors.primary, true).apply {
                        isFocusable = true
                        contentDescription = "$source group, ${if (collapsed) "collapsed" else "expanded"}"
                        setOnClickListener {
                            if (collapsed && rows.any { it.sourceLabel == source && it.key !in rowViewsByKey }) {
                                while (rowViewsByKey.size < rows.size) appendNextPage()
                            }
                            if (!collapsedSources.add(source)) collapsedSources.remove(source)
                            renderRows()
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    addView(text(ctx, "Hide all", 11f, colors.accent, true).apply {
                        gravity = Gravity.CENTER
                        isFocusable = true
                        contentDescription = "Hide all rows from $source"
                        setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
                        setOnClickListener { setSourceRowsVisibility(ctx, rows.filter { it.sourceLabel == source }, false); catalogRowsMenu(ctx, installedRows) }
                    })
                    addView(text(ctx, "Show all", 11f, colors.accent, true).apply {
                        gravity = Gravity.CENTER
                        isFocusable = true
                        contentDescription = "Show all rows from $source"
                        setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
                        setOnClickListener { setSourceRowsVisibility(ctx, rows.filter { it.sourceLabel == source }, true); catalogRowsMenu(ctx, installedRows) }
                    })
                }
                rowList.addView(header)
                if (source !in collapsedSources) sourceRows.forEach { (_, view) ->
                    rowList.addView(view, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(ctx, 5) })
                }
            }
        }
        val pageSize = 50
        appendNextPage = {
            val nextKeys = orderedRowKeys.filter { it !in rowViewsByKey }.take(pageSize)
            nextKeys.forEach { key ->
                val row = rowsByKey[key] ?: return@forEach
                val view = createRow(orderedRowKeys.indexOf(key), row)
                rowViewsInOrder += row to view
                view.visibility = if (activeQuery.isBlank() || row.displayName.contains(activeQuery, true) || row.sourceLabel.contains(activeQuery, true) || row.status.contains(activeQuery, true)) View.VISIBLE else View.GONE
                dragHandlesByKey[key]?.let { handle ->
                    installDragReordering(ctx, handle, view, rowList, scroll, colors,
                        onDrop = { persistDragOrder?.invoke() }, onMoved = {})
                }
            }
            renderRows()
        }
        appendNextPage()
        val groupSwitch = CheckBox(ctx).apply {
            text = "Group by source"
            textSize = 13f
            isChecked = false
            contentDescription = "Group homepage rows by source"
            setOnCheckedChangeListener { _, checked -> groupBySource = checked; renderRows() }
        }
        content.addView(groupSwitch)
        content.addView(rowList)

        content.addView(text(ctx, "MORE ACTIONS", 10f, colors.accent, true).apply {
            letterSpacing = 0.10f
            setPadding(dp(ctx, 4), dp(ctx, 10), 0, dp(ctx, 6))
        })

        fun addAction(label: String, detail: String, action: () -> Unit) {
            val body = vertical(ctx).apply {
                addView(text(ctx, label, 13.5f, colors.primary, true))
                addView(text(ctx, detail, 11f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
                background = ripple(ctx, colors.background, colors)
                minimumHeight = dp(ctx, 50)
                addView(body, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(text(ctx, "›", 22f, colors.secondary).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(ctx, 22), ViewGroup.LayoutParams.MATCH_PARENT))
                setOnClickListener { commitFocusedPosition?.invoke(); action() }
            }
            content.addView(actionRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 4) })
        }

        fun renumberPositionInputs() {
            orderedRowKeys.forEachIndexed { index, key ->
                val input = orderInputsByKey[key] ?: return@forEachIndexed
                val expected = (index + 1).toString()
                if (input.text.toString() != expected) input.setText(expected)
            }
        }

        fun persistVisibleOrder(visibleOrder: List<String>): Boolean {
            val snapshot = Config.snapshot()
            val positions = visibleOrder.withIndex().associate { it.value to it.index + 1 }
            val orderedKeys = CatalogOrderMath.reorderAll(
                snapshot.catalogOrder,
                rows.map { it.key },
                positions,
            ) ?: return false
            val orderedAddonIds = orderedKeys.mapNotNull { rowsByKey[it]?.addon?.id }.distinct()
            val reorderedAddonIds = HomepageGroupOrderMath.reorderAddonSlots(
                snapshot.addons.map { it.id },
                orderedAddonIds,
            ) ?: return false

            Config.update {
                catalogOrder = orderedKeys.toMutableList()
                val byId = addons.associateBy { it.id }
                val reorderedAddons = reorderedAddonIds.mapNotNull { byId[it] }
                if (reorderedAddons.size == addons.size) {
                    addons = reorderedAddons.toMutableList()
                }
            }
            HomeRefreshNotice.mark()
            return true
        }

        persistDragOrder = {
            val loadedOrder = (0 until rowList.childCount).mapNotNull { rowList.getChildAt(it).tag as? String }
            val loaded = loadedOrder.toSet()
            val completeOrder = loadedOrder + orderedRowKeys.filter { it !in loaded }
            if (persistVisibleOrder(completeOrder)) {
                orderedRowKeys = completeOrder.toMutableList()
                renumberPositionInputs()
                renderRows()
            } else {
                renderRows()
                toast(ctx, "Could not save the homepage row order.")
            }
        }

        fun applyRequestedPosition(key: String) {
            val input = orderInputsByKey[key] ?: return
            val rawPosition = input.text.toString().trim()
            if (rawPosition.isEmpty()) {
                renumberPositionInputs()
                return
            }
            val requestedPosition = rawPosition.toIntOrNull()
            if (requestedPosition == null || requestedPosition !in 1..orderedRowKeys.size) {
                renumberPositionInputs()
                toast(ctx, "Choose a position from 1 to ${orderedRowKeys.size}.")
                return
            }

            val newOrder = CatalogOrderMath.moveToPosition(
                orderedRowKeys,
                key,
                requestedPosition,
            )
            if (newOrder == null) {
                renumberPositionInputs()
                toast(ctx, "Could not change the row position.")
                return
            }
            if (newOrder == orderedRowKeys) {
                renumberPositionInputs()
                return
            }
            if (!persistVisibleOrder(newOrder)) {
                renumberPositionInputs()
                toast(ctx, "Could not save the homepage row order.")
                return
            }

            orderedRowKeys = newOrder.toMutableList()
            renderRows()
            renumberPositionInputs()
            toast(ctx, "Moved to position $requestedPosition. Other rows renumbered.")
        }

        commitFocusedPosition = {
            orderInputsByKey.entries.firstOrNull { it.value.hasFocus() }?.key?.let {
                applyRequestedPosition(it)
            }
        }

        orderInputsByKey.forEach { (key, input) ->
            input.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) applyRequestedPosition(key)
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_NEXT
                ) {
                    applyRequestedPosition(key)
                    input.clearFocus()
                    val keyboard = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    keyboard?.hideSoftInputFromWindow(input.windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }

        addAction(
            "Reset row order",
            "Return to the default addon/source order. Visibility and custom row names are unchanged.",
        ) {
            confirm(
                ctx,
                "Reset homepage row order?",
                "Rows will return to their default addon/source order. Visibility settings are unchanged.",
                onCancel = { catalogRowsMenu(ctx) },
            ) {
                Config.update { catalogOrder.clear() }
                HomeRefreshNotice.mark()
                catalogRowsMenu(ctx, installedRows)
            }
        }
        addAction(
            "Unhide all Stremio homepage rows",
            "Make every Stremio catalog row visible again.",
        ) {
            confirm(
                ctx,
                "Show every Stremio homepage row?",
                "This clears row-level hiding. Disabled addons and disabled catalog use remain inactive. Imported extension rows are unchanged.",
                onCancel = { catalogRowsMenu(ctx) },
            ) {
                Config.update { addons.forEach { it.hiddenCatalogs.clear() } }
                HomeRefreshNotice.mark()
                catalogRowsMenu(ctx, installedRows)
            }
        }

        val rowSearch = searchBox(ctx, "Search homepage rows", colors) { query ->
            activeQuery = query.trim()
            if (activeQuery.isNotBlank()) while (rowViewsByKey.size < rows.size) appendNextPage()
            if (activeQuery.isNotBlank()) {
                collapsedSources.clear()
                renderRows()
            }
            rows.forEach { row ->
                val matches = activeQuery.isBlank() ||
                    row.displayName.contains(activeQuery, true) ||
                    row.sourceLabel.contains(activeQuery, true) ||
                    row.status.contains(activeQuery, true)
                rowViewsByKey[row.key]?.visibility =
                    if (activeQuery.isBlank() || matches) View.VISIBLE else View.GONE
            }
        }
        val searchBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(rowSearch, LinearLayout.LayoutParams(0, dp(ctx, 48), 1f).apply {
                marginEnd = dp(ctx, 6)
            })
            addView(text(ctx, "+", 23f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(ctx, 48)
                minHeight = dp(ctx, 48)
                contentDescription = "Add rows from installed extensions"
                background = ripple(ctx, colors.background, colors)
                isFocusable = true
                setOnClickListener { addExternalRowsMenu(ctx) }
            }, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)))
        }
        root.addView(searchBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 48),
        ).apply { bottomMargin = dp(ctx, 6) })
        val initialSearch = SettingsDialogHost.consumeNextMenuSearch()
        if (initialSearch.isNotBlank()) rowSearch.setText(initialSearch)

        scroll.apply {
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(180, minOf(rows.size, pageSize) * 72 + 170))),
        ))
        if (initialSearch.isNotBlank()) while (rowViewsByKey.size < rows.size) appendNextPage()
        addFooter(ctx, root, "Done", {
            commitFocusedPosition?.invoke()
            MainMenuSettingsScreen.show(ctx)
        })
        SettingsDialogHost.show(ctx, "catalog-rows", root) { MainMenuSettingsScreen.show(ctx) }
    }

    private fun setRowVisibility(
        ctx: Context,
        row: CatalogRow,
        visible: Boolean,
    ) {
        Config.update {
            val external = row.external
            if (external != null) {
                externalRows.firstOrNull { it.key == external.key }?.let { it.enabled = visible }
            } else {
                val addon = row.addon
                val type = row.type
                val id = row.id
                if (addon != null && type != null && id != null) {
                    val key = "$type/$id"
                    if (visible) addon.hiddenCatalogs.remove(key)
                    else if (key !in addon.hiddenCatalogs) addon.hiddenCatalogs.add(key)
                }
            }
        }
        HomeRefreshNotice.mark()
    }

    private fun setSourceRowsVisibility(ctx: Context, rows: List<CatalogRow>, visible: Boolean) {
        val beforeChange = Config.snapshot()
        Config.update {
            rows.forEach { row ->
                if (row.external != null) {
                    externalRows.firstOrNull { it.key == row.external.key }?.enabled = visible
                } else {
                    val addon = addons.firstOrNull { it.id == row.addon?.id } ?: return@forEach
                    val key = "${row.type}/${row.id}"
                    if (visible) addon.hiddenCatalogs.remove(key)
                    else if (key !in addon.hiddenCatalogs) addon.hiddenCatalogs.add(key)
                }
            }
        }
        HomeRefreshNotice.mark()
        showUndoSnackbar(ctx, if (visible) "Rows shown" else "Rows hidden") {
            Config.replace(beforeChange)
            HomeRefreshNotice.mark()
            catalogRowsMenu(ctx)
        }
    }

    private fun addExternalRowsMenu(ctx: Context) {
        val installed = ExternalHomeRowsCache.snapshotIfFresh()
        if (installed == null) {
            val loading = shell(ctx, "Add rows from installed extensions", "Checking installed extensions…")
            loading.addView(text(ctx, "Loading available homepage rows…", 13f, palette(ctx).secondary).apply {
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 14))
            })
            SettingsDialogHost.show(ctx, "external-row-picker:loading", loading)
            ExternalHomeRowsCache.loadAsync { available ->
                if (SettingsDialogHost.isCurrent("external-row-picker:loading")) {
                    SettingsDialogHost.back()
                    addExternalRowsMenu(ctx, available)
                }
            }
            return
        }
        addExternalRowsMenu(ctx, installed)
    }

    private fun addExternalRowsMenu(ctx: Context, installed: List<InstalledExternalHomeRow>) {
        val selected = Config.settings.externalRows.map { it.key }.toSet()
        val candidates = installed.filter { it.config.key !in selected }
        if (candidates.isEmpty()) {
            val message = if (installed.isEmpty()) {
                "No other installed extensions with homepage rows were found."
            } else {
                "All available extension homepage rows have already been added."
            }
            settingMenu(
                ctx,
                "Add rows from installed extensions",
                listOf(SettingItem(
                    message,
                    "Return to Catalog rows to manage your existing rows",
                    SettingItemType.Navigate,
                    onClick = { catalogRowsMenu(ctx) },
                )),
                { catalogRowsMenu(ctx) },
            )
            return
        }

        val items = candidates.map { candidate ->
            SettingItem(
                title = "${candidate.config.providerName} · ${candidate.row.name}",
                summary = "Add this installed extension row to your MultiStremio homepage",
                type = SettingItemType.Navigate,
                onClick = {
                    val chosen = candidate.config
                    // Preserve the effective order, including rows that were not previously persisted.
                    val currentOrder = catalogRows(installed).map { it.key }
                    Config.update {
                        if (externalRows.none { it.key == chosen.key }) externalRows.add(chosen)
                        catalogOrder = CatalogOrderMath.append(currentOrder, chosen.key).toMutableList()
                    }
                    HomeRefreshNotice.mark()
                    toast(ctx, "Added ${chosen.rowName} to homepage rows.")
                    catalogRowsMenu(ctx)
                },
                searchKeywords = listOf(candidate.config.providerName, candidate.row.name, candidate.config.sourcePlugin),
            )
        }
        settingMenu(
            ctx,
            "Choose an installed extension homepage row",
            items,
            { catalogRowsMenu(ctx) },
            "Select a row to add it. The original extension remains unchanged.",
        )
    }

    private fun catalogRowMenu(ctx: Context, row: CatalogRow) {
        val external = row.external
        val items = listOf(
            SettingItem(
                "Rename homepage row",
                "Choose a title that will appear on the home page",
                SettingItemType.Value,
                valueLabel = Config.settings.customCatalogNames[row.key]?.takeIf { it.isNotBlank() } ?: row.name,
                onClick = {
                    val current = Config.settings.customCatalogNames[row.key] ?: row.name
                    prompt(ctx, "Rename homepage row", "Leave blank to restore the original title.", current, false,
                        onCancel = { catalogRowMenu(ctx, row) }) { newName ->
                        Config.update {
                            if (newName.isBlank()) customCatalogNames.remove(row.key)
                            else customCatalogNames[row.key] = newName
                        }
                        HomeRefreshNotice.mark()
                        catalogRowsMenu(ctx)
                    }
                },
                searchKeywords = listOf("rename", "custom title", "row name", "homepage"),
            ),
            SettingItem(
                if (external != null) "Remove row from MultiStremio" else "Manage addon settings",
                if (external != null) "Remove only the imported row; keep its source extension installed"
                else "Open this row's source addon settings",
                SettingItemType.Navigate,
                onClick = {
                    if (external != null) {
                        confirm(
                            ctx,
                            "Remove extension row?",
                            "This only removes the row from MultiStremio. The source extension remains installed.",
                            onCancel = { catalogRowsMenu(ctx) },
                        ) {
                            val beforeRemoval = Config.snapshot()
                            Config.update { removeExternalHomeRow(external.key) }
                            HomeRefreshNotice.mark()
                            catalogRowsMenu(ctx)
                            showUndoSnackbar(ctx, "Homepage row removed") {
                                Config.replace(beforeRemoval)
                                HomeRefreshNotice.mark()
                                catalogRowsMenu(ctx)
                            }
                        }
                    } else {
                        row.addon?.let { AddonsSettingsScreen.showAddon(ctx, it) } ?: catalogRowsMenu(ctx)
                    }
                },
                searchKeywords = listOf("remove", "addon", "extension", "source", "settings"),
            ),
        )
        settingMenu(
            ctx,
            row.sourceLabel + " · " + row.displayName,
            items,
            { catalogRowsMenu(ctx) },
            "Rename this row or remove it from MultiStremio. Its source extension remains installed.",
            screenKey = "catalog-row:${row.key}",
            showRefreshNotice = true,
        )
    }

}
