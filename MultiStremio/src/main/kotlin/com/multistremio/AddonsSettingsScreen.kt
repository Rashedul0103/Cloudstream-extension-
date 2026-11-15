package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors
import kotlin.concurrent.thread

internal object AddonsSettingsScreen {
    fun show(ctx: Context) = addonsMenu(ctx)
    fun showAddon(ctx: Context, addon: AddonConfig) = addonMenu(ctx, addon)

    private fun addonsMenu(ctx: Context) {
        val addons = Config.snapshot().addons
        val items = addons.map { addon ->
            val capabilities = listOfNotNull(
                if (addon.useCatalogs) "catalogs" else null,
                if (addon.useMeta) "meta" else null,
                if (addon.useStreams) "streams" else null,
                if (addon.useSubtitles) "subs" else null,
            ).joinToString(" / ").ifBlank { "No capabilities enabled" }
            SettingItem(
                title = addon.name,
                summary = capabilities,
                type = SettingItemType.Switch,
                onClick = { addonMenu(ctx, Config.snapshot().addons.firstOrNull { it.id == addon.id } ?: addon) },
                onToggle = {
                    Config.update { addons.firstOrNull { it.id == addon.id }?.let { it.enabled = !it.enabled } }
                    HomeRefreshNotice.mark()
                    addonsMenu(ctx)
                },
                checked = addon.enabled,
                searchKeywords = listOf(addon.name, capabilities, "enabled", "disabled", "catalogs", "metadata", "streams", "subtitles"),
            )
        } + listOf(
            SettingItem(
                "Reorder addon priority",
                "Drag addons to change source priority; use arrows as an alternative",
                SettingItemType.Navigate,
                onClick = { addonPriorityDialog(ctx) },
                searchKeywords = listOf("priority", "reorder", "drag", "move", "order"),
            ),
            SettingItem(
                "Check all addon connections",
                "Test manifest reachability and response time",
                SettingItemType.Navigate,
                onClick = { checkAddonConnections(ctx) },
                searchKeywords = listOf("check", "connection", "test", "manifest", "status"),
            ),
            SettingItem(
                "Add addon",
                "Enter or paste a Stremio addon manifest URL",
                SettingItemType.Navigate,
                onClick = { addAddon(ctx) },
                searchKeywords = listOf("add", "new", "URL", "manifest", "paste"),
            ),
        )
        settingMenu(
            ctx,
            "Addons",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Manage addon availability, capabilities and priority.",
            showRefreshNotice = true,
            forceSearch = true,
        )
    }

    private fun addonPriorityDialog(ctx: Context) {
        val ordered = Config.snapshot().addons
        if (ordered.size < 2) {
            toast(ctx, "Add at least two addons to change their priority.")
            addonsMenu(ctx)
            return
        }
        val colors = palette(ctx)
        val root = shell(
            ctx,
            "Addon priority",
            "Higher addons are queried first for stream tie-breaking and metadata. Drag the grip or use arrows.",
        )
        val scroll = ScrollView(ctx)
        val list = vertical(ctx)
        fun refreshNumbers() {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index) as? LinearLayout ?: continue
                val label = row.getChildAt(1) as? TextView ?: continue
                label.text = "${index + 1}. ${label.tag as? String ?: ""}"
            }
        }
        ordered.forEachIndexed { index, addon ->
            val handle = text(ctx, "☰", 21f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "Drag to change priority for ${addon.name}"
            }
            val label = text(ctx, "${index + 1}. ${addon.name}", 13.5f, colors.primary, true).apply {
                tag = addon.name
            }
            lateinit var row: LinearLayout
            row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 5), dp(ctx, 6))
                background = rounded(ctx, colors.background, colors.outline)
                tag = addon.id
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
                                refreshNumbers()
                                saveAddonPriority(ctx, list)
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
                                refreshNumbers()
                                saveAddonPriority(ctx, list)
                            }
                        }
                    }
                    addView(up)
                    addView(down)
                }
                addView(controls, LinearLayout.LayoutParams(dp(ctx, 34), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 4) })
            installDragReordering(
                ctx, handle, row, list, scroll, colors,
                onDrop = {
                    saveAddonPriority(ctx, list)
                    refreshNumbers()
                },
                onMoved = { refreshNumbers() },
            )
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 380), dp(ctx, maxOf(100, ordered.size * 52))),
        ))
        addFooter(ctx, root, "Done", { addonsMenu(ctx) })
        SettingsDialogHost.show(ctx, "addon-priority", root, { addonsMenu(ctx) })
    }

    private fun saveAddonPriority(ctx: Context, list: LinearLayout) {
        val ids = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
        if (ids.size != Config.settings.addons.size || ids.toSet() != Config.settings.addons.map { it.id }.toSet()) {
            toast(ctx, "Could not save addon priority.")
            return
        }
        Config.update {
            val byId = addons.associateBy { it.id }
            addons = ids.mapNotNull { byId[it] }.toMutableList()
        }
        toast(ctx, "Addon priority saved.")
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

            settingsMainHandler.post {
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
                addonsMenu(ctx)
        }, "Copy report", {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MultiStremio addon health", report))
            toast(ctx, "Connection report copied")
        })
        SettingsDialogHost.show(ctx, "addon-health", root)
    }

    private fun addAddon(ctx: Context) {
        prompt(
            ctx, "Add addon",
            "Paste the addon's manifest link (https://…/manifest.json or stremio://…). " +
                "Links often contain your personal keys, so keep them private.",
            "", false,
            onCancel = { addonsMenu(ctx) },
            showPasteButton = true,
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
                settingsMainHandler.post {
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
                    if (added) HomeRefreshNotice.mark()
                    toast(ctx, if (added) {
                        "Added ${addon.name}."
                    } else {
                        "That addon was added by another request."
                    })
                    if (!hidden) addonsMenu(ctx)
                }
            }
        }
    }

    private fun addonMenu(ctx: Context, addon: AddonConfig) {
        // Refresh from persisted settings each time this detail screen is reopened.
        val a = Config.snapshot().addons.firstOrNull { it.id == addon.id } ?: addon
        val again = { addonMenu(ctx, a) }
        val items = listOf(
            SettingItem(
                if (a.enabled) "Disable addon" else "Enable addon",
                "Keep this addon saved; change only whether it is used",
                SettingItemType.Switch,
                onClick = {
                    Config.update { addons.firstOrNull { it.id == a.id }?.let { it.enabled = !it.enabled } }
                    HomeRefreshNotice.mark()
                    again()
                },
                checked = a.enabled,
                searchKeywords = listOf("enable", "disable", "active", "addon"),
            ),
            SettingItem(
                "Choose addon capabilities",
                listOfNotNull(
                    if (a.useCatalogs) "Catalogs" else null,
                    if (a.useMeta) "Metadata" else null,
                    if (a.useStreams) "Streams" else null,
                    if (a.useSubtitles) "Subtitles" else null,
                ).joinToString(", ").ifBlank { "None enabled" },
                SettingItemType.Value,
                valueLabel = listOfNotNull(
                    if (a.useCatalogs) "Catalogs" else null,
                    if (a.useMeta) "Meta" else null,
                    if (a.useStreams) "Streams" else null,
                    if (a.useSubtitles) "Subs" else null,
                ).joinToString(" / ").ifBlank { "None" },
                onClick = {
                    multi(
                        ctx,
                        "${a.name} is used for",
                        listOf("Catalogs & search", "Metadata (details/episodes)", "Streams", "Subtitles"),
                        listOf(a.useCatalogs, a.useMeta, a.useStreams, a.useSubtitles),
                        onCancel = again,
                    ) { selected ->
                        Config.update {
                            addons.firstOrNull { it.id == a.id }?.let { saved ->
                                saved.useCatalogs = selected[0]
                                saved.useMeta = selected[1]
                                saved.useStreams = selected[2]
                                saved.useSubtitles = selected[3]
                            }
                        }
                        HomeRefreshNotice.mark()
                        again()
                    }
                },
                searchKeywords = listOf("catalogs", "search", "metadata", "streams", "subtitles", "capabilities"),
            ),
            SettingItem(
                "Home catalogs to show",
                "Select the catalog rows this addon contributes to the home page",
                SettingItemType.Value,
                valueLabel = "${HomeRows.resolve(Config.snapshot()).count { it.addon?.id == a.id && it.visibleOnHome }} visible",
                onClick = { catalogPicker(ctx, a) },
                searchKeywords = listOf("home", "catalog", "homepage", "rows", "visibility"),
            ),
            SettingItem(
                "Move up (higher source priority)",
                "Give this addon higher priority for matching results",
                SettingItemType.Navigate,
                onClick = {
                    if (move(a, -1)) toast(ctx, "Addon priority increased.")
                    else toast(ctx, "This addon is already at the top of the priority list.")
                    addonsMenu(ctx)
                },
                searchKeywords = listOf("priority", "move up", "ranking"),
            ),
            SettingItem(
                "Move down (lower source priority)",
                "Give this addon lower priority for matching results",
                SettingItemType.Navigate,
                onClick = {
                    if (move(a, +1)) toast(ctx, "Addon priority decreased.")
                    else toast(ctx, "This addon is already at the bottom of the priority list.")
                    addonsMenu(ctx)
                },
                searchKeywords = listOf("priority", "move down", "ranking"),
            ),
            SettingItem(
                "Rename addon",
                "Set the display name for this addon",
                SettingItemType.Value,
                valueLabel = a.name,
                onClick = {
                    prompt(ctx, "Rename addon", null, a.name, false, onCancel = again) { newName ->
                        if (newName.isNotEmpty()) {
                            Config.update {
                                val saved = addons.firstOrNull { it.id == a.id }
                                if (saved != null) saved.name = newName
                            }
                            HomeRefreshNotice.mark()
                            again()
                        } else again()
                    }
                },
                searchKeywords = listOf("rename", "display name"),
            ),
            SettingItem(
                "Refresh & test manifest",
                "Download the manifest again and update cached capabilities",
                SettingItemType.Navigate,
                onClick = {
                    var hidden = false
                    val progressDialog = progress(
                        ctx,
                        "Refreshing addon",
                        "Downloading and validating the manifest. Hide this dialog to leave settings; the refresh will continue.",
                    ) { hidden = true }
                    thread(start = true, name = "multistremio-refresh-addon") {
                        val ok = Config.refreshManifest(a)
                        val manifest = Config.manifestOf(a)
                        val resources = manifest?.resources.orEmpty().mapNotNull {
                            if (it is String) it else (it as? Map<*, *>)?.get("name") as? String
                        }.distinct()

                        settingsMainHandler.post {
                            progressDialog.dismiss()
                            toast(
                                ctx,
                                if (ok) "OK: ${manifest?.name ?: a.name} v${manifest?.version ?: "?"}, ${manifest?.catalogs.orEmpty().size} catalogs, " +
                                    "provides ${resources.joinToString(", ").ifEmpty { "nothing" }}"
                                else "Could not download the manifest",
                            )
                            if (!hidden) {
                                val current = Config.snapshot().addons.firstOrNull { it.id == a.id && it.url == a.url }
                                if (current != null) addonMenu(ctx, current) else addonsMenu(ctx)
                            }
                        }
                    }
                },
                searchKeywords = listOf("refresh", "manifest", "test", "connection", "capabilities"),
            ),
            SettingItem(
                "Remove addon",
                "Delete this addon and its saved homepage rows from MultiStremio",
                SettingItemType.Navigate,
                onClick = {
                    confirm(
                        ctx,
                        "Remove ${a.name}?",
                        "This only removes it from MultiStremio. Its catalog rows and saved row-order entries will also be removed.",
                        onCancel = again,
                    ) {
                        Config.update {
                            addons.removeAll { it.id == a.id }
                            val addonPrefix = a.id + "|"
                            catalogOrder.removeAll { it.startsWith(addonPrefix) }
                            customCatalogNames.keys.removeAll { it.startsWith(addonPrefix) }
                        }
                        HomeRefreshNotice.mark()
                        addonsMenu(ctx)
                    }
                },
                searchKeywords = listOf("remove", "delete", "source", "addon"),
            ),
        )
        settingMenu(
            ctx,
            a.name,
            items,
            { addonsMenu(ctx) },
            "Addon priority affects stream tie-breaking and metadata fallback. Homepage rows are arranged separately.",
            screenKey = "addon:${a.id}",
            showRefreshNotice = true,
        )
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
        val cats = HomeRows.catalogCandidates(manifest)
        if (cats.isEmpty()) { toast(ctx, "This addon has no browsable catalogs"); addonMenu(ctx, a); return }
        val keys = cats.map { "${it.type}/${it.id}" }
        multi(
            ctx, "Catalogs on the home page",
            cats.map { "${it.name ?: it.id} (${it.type})" },
            keys.map { it !in a.hiddenCatalogs },
            onCancel = { addonMenu(ctx, a) },
        ) { v ->
            Config.update {
                val saved = addons.firstOrNull { it.id == a.id }
                if (saved != null) {
                    saved.hiddenCatalogs = keys.filterIndexed { i, _ -> !v[i] }.toMutableList()
                }
            }
            HomeRefreshNotice.mark()
            toast(ctx, "Homepage row visibility saved.")
            addonMenu(ctx, a)
        }
    }
}
