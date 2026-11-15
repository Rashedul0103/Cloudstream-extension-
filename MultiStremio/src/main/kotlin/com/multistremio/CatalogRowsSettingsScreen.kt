package com.multistremio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
            "Each addon has its own collapsible section. Drag a grip to preview the new position; the row moves only when you lift your finger. Reordering is within the addon section; addon priority is managed separately in Addons.",
        )
        HomeRefreshNotice.addTo(ctx, root)
        val scroll = ScrollView(ctx).apply { clipToPadding = false }
        val content = vertical(ctx)
        val rowViewsByGroup = linkedMapOf<String, MutableList<View>>()
        val dragHandlesByGroup = linkedMapOf<String, MutableList<View>>()
        val rowViewsByKey = linkedMapOf<String, View>()
        val groupHeaderViews = linkedMapOf<String, View>()
        val groupLists = linkedMapOf<String, LinearLayout>()
        val groupExpanded = mutableMapOf<String, Boolean>()
        val groupForKey = rows.associate { row ->
            row.key to (row.addon?.name ?: row.external?.providerName ?: row.sourceLabel)
        }
        var activeQuery = ""
        val rowNames = rows.associate { it.key to it.displayName }

        fun visualOrder(): List<String> {
            val updated = rows.map { it.key }.toMutableList()
            rowViewsByGroup.keys.forEach { groupName ->
                val groupKeys = (0 until (groupLists[groupName]?.childCount ?: 0))
                    .mapNotNull { groupLists[groupName]?.getChildAt(it)?.tag as? String }
                val positions = rows.indices.filter { groupForKey[rows[it].key] == groupName }
                positions.forEachIndexed { index, position ->
                    groupKeys.getOrNull(index)?.let { updated[position] = it }
                }
            }
            return updated
        }

        fun refreshRowNumbers() {
            visualOrder().forEachIndexed { position, key ->
                val item = rowViewsByKey[key] as? LinearLayout ?: return@forEachIndexed
                val description = item.getChildAt(0) as? LinearLayout ?: return@forEachIndexed
                val title = description.getChildAt(0) as? TextView ?: return@forEachIndexed
                title.text = "${position + 1}. " + rowNames[key].orEmpty()
            }
        }

        rows.forEachIndexed { index, row ->
            val group = row.addon?.name ?: row.external?.providerName ?: row.sourceLabel
            val description = vertical(ctx).apply {
                addView(text(ctx, (index + 1).toString() + ". " + row.displayName, 14f, colors.primary, true))
                addView(text(ctx, row.status, 11f, colors.secondary).apply {
                    setPadding(0, dp(ctx, 3), 0, 0)
                })
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
                setOnClickListener { catalogRowMenu(ctx, row) }
                isClickable = true
            }
            val visibility = Switch(ctx).apply {
                isChecked = row.rowEnabled
                thumbTintList = android.content.res.ColorStateList.valueOf(if (isChecked) colors.accent else colors.secondary)
                trackTintList = android.content.res.ColorStateList.valueOf(withAlpha(if (isChecked) colors.accent else colors.secondary, 90))
                contentDescription = "Show homepage row ${row.displayName}"
                setOnCheckedChangeListener { _, checked ->
                    setRowVisibility(ctx, row, checked)
                }
            }
            val handle = text(ctx, "☰", 22f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 5), 0, dp(ctx, 5), 0)
                contentDescription = "Drag to reorder " + row.displayName
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 9), dp(ctx, 8), dp(ctx, 5), dp(ctx, 8))
                background = rounded(ctx, colors.background, colors.outline)
                minimumHeight = dp(ctx, 64)
                tag = row.key
                addView(description, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(visibility, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 46)))
            }
            rowViewsByGroup.getOrPut(group) { mutableListOf() }.add(item)
            dragHandlesByGroup.getOrPut(group) { mutableListOf() }.add(handle)
            rowViewsByKey[row.key] = item
        }

        if (rows.isEmpty()) {
            content.addView(text(
                ctx,
                "No homepage rows are available yet. Add a Stremio addon or import a row from another installed extension.",
                13f,
                colors.secondary,
            ).apply {
                setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
            })
        } else {
            val groups = rowViewsByGroup.toList()
            groups.forEach { (groupName, groupRows) ->
                val groupList = vertical(ctx)
                groupLists[groupName] = groupList
                groupRows.forEach { rowView ->
                    groupList.addView(rowView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(ctx, 6) })
                }
                var expanded = true
                groupExpanded[groupName] = true
                val header = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 9), dp(ctx, 8), dp(ctx, 9), dp(ctx, 8))
                    background = ripple(ctx, withAlpha(colors.accent, 8), colors)
                    addView(text(ctx, groupName, 13f, colors.primary, true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(ctx, "${groupRows.size} rows   ▾", 11f, colors.accent, true))
                    setOnClickListener {
                        if (activeQuery.isNotBlank()) return@setOnClickListener
                        expanded = !expanded
                        groupExpanded[groupName] = expanded
                        groupList.visibility = if (expanded) View.VISIBLE else View.GONE
                        dragHandlesByGroup[groupName].orEmpty().forEach { handle ->
                            handle.isEnabled = expanded
                            handle.alpha = if (expanded) 1f else 0.35f
                        }
                        (getChildAt(1) as? TextView)?.text =
                            "${groupRows.size} rows   ${if (expanded) "▾" else "▸"}"
                    }
                }
                groupHeaderViews[groupName] = header
                content.addView(header, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 4) })
                content.addView(groupList)

                dragHandlesByGroup[groupName].orEmpty().forEachIndexed { rowIndex, handle ->
                    val item = groupRows[rowIndex]
                    installDragReordering(
                        ctx, handle, item, groupList, scroll, colors,
                        onDrop = {
                            val orderedKeys = visualOrder()
                            if (orderedKeys.size == rows.size && orderedKeys.toSet() == rows.map { it.key }.toSet()) {
                                Config.update { catalogOrder = orderedKeys.toMutableList() }
                                HomeRefreshNotice.mark()
                                catalogRowsMenu(ctx, installedRows)
                            } else {
                                toast(ctx, "Could not save the new row order.")
                            }
                        },
                        onMoved = { refreshRowNumbers() },
                    )
                }
            }
        }

        content.addView(text(ctx, "MORE ACTIONS", 10f, colors.accent, true).apply {
            letterSpacing = 0.10f
            setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 7))
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
                addView(text(ctx, "›", 24f, colors.secondary).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.MATCH_PARENT))
                setOnClickListener { action() }
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
                "Reset homepage row order?",
                "Rows will return to Stremio addon order, followed by imported extension rows.",
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
            rows.forEach { row ->
                val group = groupForKey[row.key].orEmpty()
                val rowMatches = activeQuery.isBlank() ||
                    row.displayName.contains(activeQuery, true) ||
                    row.sourceLabel.contains(activeQuery, true) ||
                    row.status.contains(activeQuery, true) ||
                    group.contains(activeQuery, true)
                rowViewsByKey[row.key]?.visibility =
                    if (activeQuery.isBlank() || rowMatches) View.VISIBLE else View.GONE
                val expanded = groupExpanded[group] ?: true
                val groupList = groupLists[group]
                if (activeQuery.isNotBlank()) {
                    groupList?.visibility = View.VISIBLE
                    dragHandlesByGroup[group].orEmpty().forEach { handle ->
                        handle.isEnabled = false
                        handle.alpha = 0.35f
                    }
                } else {
                    groupList?.visibility = if (expanded) View.VISIBLE else View.GONE
                    dragHandlesByGroup[group].orEmpty().forEach { handle ->
                        handle.isEnabled = expanded
                        handle.alpha = if (expanded) 1f else 0.35f
                    }
                }
            }
            groupHeaderViews.forEach { (group, header) ->
                val groupMatches = activeQuery.isBlank() || rows.any { row ->
                    val rowGroup = row.addon?.name ?: row.external?.providerName ?: row.sourceLabel
                    rowGroup == group && (
                        row.displayName.contains(activeQuery, true) ||
                        row.sourceLabel.contains(activeQuery, true) ||
                        row.status.contains(activeQuery, true) ||
                        group.contains(activeQuery, true)
                    )
                }
                header.visibility = if (groupMatches) View.VISIBLE else View.GONE
                header.isEnabled = activeQuery.isBlank()
            }
        }
        root.addView(rowSearch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 44),
        ).apply { bottomMargin = dp(ctx, 7) })
        val initialSearch = SettingsDialogHost.consumeNextMenuSearch()
        if (initialSearch.isNotBlank()) rowSearch.setText(initialSearch)

        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 460), dp(ctx, maxOf(190, rows.size * 76 + 230))),
        ))
        addFooter(ctx, root, "Done", { MainMenuSettingsScreen.show(ctx) })
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
        catalogRowsMenu(ctx)
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
            HomeRefreshNotice.mark()
            toast(ctx, "Homepage row order updated.")
        }
        catalogRowsMenu(ctx)
    }

    private fun catalogRowMenu(ctx: Context, row: CatalogRow) {
        val external = row.external
        val items = listOf(
            SettingItem(
                if (row.rowEnabled) "Hide this row" else "Show this row",
                if (row.rowEnabled) "Shown on the home page" else "Hidden from the home page",
                SettingItemType.Switch,
                onClick = { setRowVisibility(ctx, row, !row.rowEnabled) },
                checked = row.rowEnabled,
                searchKeywords = listOf("hide", "show", "visibility", "enable", "disable"),
            ),
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
                "Move one position up",
                "Place this row before the previous row",
                SettingItemType.Navigate,
                onClick = { moveCatalogRow(ctx, row, -1) },
                searchKeywords = listOf("move", "up", "order", "reorder"),
            ),
            SettingItem(
                "Move one position down",
                "Place this row after the next row",
                SettingItemType.Navigate,
                onClick = { moveCatalogRow(ctx, row, 1) },
                searchKeywords = listOf("move", "down", "order", "reorder"),
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
                            Config.update {
                                externalRows.removeAll { it.key == external.key }
                                catalogOrder.removeAll { it == external.key }
                                customCatalogNames.remove(external.key)
                                customCatalogNames.remove(external.key)
                            }
                            HomeRefreshNotice.mark()
                            toast(ctx, "Homepage row removed.")
                            catalogRowsMenu(ctx)
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
            "Control the visibility and position of this homepage row.",
            screenKey = "catalog-row:${row.key}",
            showRefreshNotice = true,
        )
    }

}
