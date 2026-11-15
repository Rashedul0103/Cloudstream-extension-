package com.multistremio

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
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
            "Catalog rows",
            "Drag the ☰ grip to reorder rows; no long-press is needed. Swipe row text to scroll, or tap it to manage visibility. Move up/down is also available in each row's menu. Save order when finished.",
        )
        val scroll = ScrollView(ctx).apply { clipToPadding = false }
        val content = vertical(ctx)
        val list = vertical(ctx)
        val rowNames = rows.associate { it.key to it.name }

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
                        toast(ctx, "Row order saved. Refresh the home page to apply.")
                catalogRowsMenu(ctx)
            } else {
                toast(ctx, "Could not save the row order. Please try again.")
            }
        }, "Back", {
                MainMenuSettingsScreen.show(ctx)
        })
        SettingsDialogHost.show(ctx, "catalog-rows", root)
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
                    toast(ctx, "Added ${chosen.rowName} at the end of your rows. Refresh the home page to apply.")
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
            toast(ctx, "Row order updated. Refresh the home page to apply.")
        }
        catalogRowsMenu(ctx)
    }

    private fun catalogRowMenu(ctx: Context, row: CatalogRow) {
        val external = row.external
        val items = listOf(
            SettingItem(
                if (row.rowEnabled) "Hide this row" else "Show this row",
                "Current visibility: ${if (row.rowEnabled) "ON" else "OFF"}",
                SettingItemType.Switch,
                onClick = {
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
                },
                searchKeywords = listOf("hide", "show", "visibility", "enable", "disable"),
            ),
            SettingItem(
                "Move one position up",
                "Place this row before the previous row",
                SettingItemType.Value,
                onClick = { moveCatalogRow(ctx, row, -1) },
                searchKeywords = listOf("move", "up", "order", "reorder"),
            ),
            SettingItem(
                "Move one position down",
                "Place this row after the next row",
                SettingItemType.Value,
                onClick = { moveCatalogRow(ctx, row, 1) },
                searchKeywords = listOf("move", "down", "order", "reorder"),
            ),
            SettingItem(
                if (external != null) "Remove row from MultiStremio" else "Manage addon settings",
                if (external != null) "Remove only the imported row; keep its source extension installed"
                else "Open this row's source addon settings",
                SettingItemType.Value,
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
                            }
                            toast(ctx, "Extension row removed.")
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
            row.sourceLabel + " · " + row.name,
            items,
            { catalogRowsMenu(ctx) },
            "Control the visibility and position of this homepage row.",
            screenKey = "catalog-row:${row.key}",
        )
    }

}
