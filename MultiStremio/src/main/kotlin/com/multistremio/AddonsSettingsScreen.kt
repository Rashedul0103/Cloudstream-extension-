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
import kotlinx.coroutines.runBlocking

internal object AddonsSettingsScreen {
    fun show(ctx: Context) = addonsMenu(ctx)
    fun showAddon(ctx: Context, addon: AddonConfig) = addonMenu(ctx, addon)

    private fun addonsMenu(ctx: Context) {
        val initialQuery = SettingsDialogHost.consumeNextMenuSearch()
        val settings = Config.snapshot()
        val addons = settings.addons
        val addonRows = linkedMapOf<String, LinearLayout>()
        val colors = palette(ctx)
        val root = shell(ctx, "Addons", "Drag a grip to change priority. Tap an addon for details; switches update availability immediately.")
        HomeRefreshNotice.addTo(ctx, root)
        val pageSize = 50
        var renderedCount = 0
        lateinit var appendNextPage: () -> Unit
        val scroll = IncrementalScrollView(ctx, dp(ctx, 240)) { appendNextPage() }.apply { clipToPadding = false }
        val list = vertical(ctx)
        if (addons.isEmpty()) {
            list.addView(text(ctx, "No addons yet. Add a Stremio manifest URL to get started.", 13f, colors.secondary).apply {
                setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            })
        }
        appendNextPage = {
          val pageEnd = minOf(renderedCount + pageSize, addons.size)
          while (renderedCount < pageEnd) {
            val addon = addons[renderedCount++]
            val handle = text(ctx, "☰", 19f, colors.accent, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "Drag to change priority for ${addon.name}"
                isFocusable = true
            }
            val state = text(ctx, addonSummary(addon), 11.5f, colors.secondary).apply {
                setPadding(0, dp(ctx, 3), 0, 0)
                tag = "addon-health:${addon.id}"
                setTextColor(addonHealthColor(ctx, addon))
            }
            val labels = vertical(ctx).apply {
                addView(text(ctx, addon.name, 14f, colors.primary, true))
                addView(state)
                setOnClickListener {
                    commitAddonListOrder(ctx, list)
                    addonMenu(ctx, Config.snapshot().addons.firstOrNull { it.id == addon.id } ?: addon)
                }
                isClickable = true
                isFocusable = true
                contentDescription = "${addon.name}, ${addonSummary(addon)}"
            }
            val toggle = android.widget.Switch(ctx).apply {
                isChecked = addon.enabled
                isFocusable = true
                contentDescription = "${addon.name}, ${if (addon.enabled) "enabled" else "disabled"}"
                setOnCheckedChangeListener { _, enabled ->
                    Config.update { this.setAddonEnabled(addon.id, enabled) }
                    HomeRefreshNotice.mark()
                    contentDescription = "${addon.name}, ${if (enabled) "enabled" else "disabled"}"
                    val current = Config.snapshot().addons.firstOrNull { it.id == addon.id } ?: addon.copy(enabled = enabled)
                    state.text = addonSummary(current)
                    state.setTextColor(addonHealthColor(ctx, current))
                }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 6), dp(ctx, 7), dp(ctx, 7), dp(ctx, 7))
                background = rounded(ctx, colors.background, colors.outline)
                minimumHeight = dp(ctx, 64)
                tag = addon.id
                addView(handle, LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 48)))
                addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addonRows[addon.id] = row
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(ctx, 5)
            })
            installDragReordering(ctx, handle, row, list, scroll, colors,
                onDrop = { saveAddonPriority(ctx, list) }, onMoved = {})
          }
        }

        appendNextPage()
        if (initialQuery.isNotBlank()) while (renderedCount < addons.size) appendNextPage()
        fun matchesQuery(addon: AddonConfig, query: String): Boolean = query.isBlank() ||
            query.trim().lowercase() in setOf("priority", "order", "reorder", "drag") ||
            listOf(addon.name, addon.url, addonHealthLabel(addon), addonCapabilities(addon))
                .any { it.contains(query.trim(), ignoreCase = true) }
        val search = searchBox(ctx, "Search addons", colors) { query ->
            if (query.isNotBlank()) while (renderedCount < addons.size) appendNextPage()
            addons.forEach { addon ->
                addonRows[addon.id]?.visibility = if (matchesQuery(addon, query)) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 42)).apply {
            bottomMargin = dp(ctx, 7)
        })
        if (initialQuery.isNotBlank()) search.setText(initialQuery)
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 440), dp(ctx, maxOf(100, addons.size * 72))),
        ).apply { topMargin = dp(ctx, 2) })
        addFooter(ctx, root, "Add addon", { addAddon(ctx) }, "Test all", {
            checkAddonConnections(ctx) {
                addons.forEach { addon ->
                    val summary = list.findViewWithTag<TextView>("addon-health:${addon.id}")
                    val current = Config.snapshot().addons.firstOrNull { it.id == addon.id } ?: addon
                    summary?.text = addonSummary(current)
                    summary?.setTextColor(addonHealthColor(ctx, current))
                }
            }
        })
        SettingsDialogHost.show(ctx, "menu:Addons", root) { MainMenuSettingsScreen.show(ctx) }
    }

    private fun addonHealthLabel(addon: AddonConfig): String {
        val health = AddonHealthStore.get(addon.id)
        return health?.label(disabled = !addon.enabled) ?: if (addon.enabled) "Not checked" else "Disabled"
    }

    private fun addonCapabilities(addon: AddonConfig): String = listOfNotNull(
        if (addon.useCatalogs) "catalogs" else null,
        if (addon.useOther) "other" else null,
        if (addon.useMeta) "metadata" else null,
        if (addon.useStreams) "streams" else null,
        if (addon.useSubtitles) "subtitles" else null,
    ).joinToString(" / ").ifBlank { "No capabilities enabled" }

    private fun addonSummary(addon: AddonConfig): String = buildString {
        append("● ").append(addonHealthLabel(addon))
        AddonHealthStore.get(addon.id)?.let { append(" · ${it.elapsedMs} ms") }
        append(" · ").append(addonCapabilities(addon))
    }

    private fun addonHealthColor(ctx: Context, addon: AddonConfig): Int = when (addonHealthLabel(addon)) {
        "OK" -> palette(ctx).accent
        "Slow" -> 0xffffa000.toInt()
        "Failing" -> palette(ctx).danger
        else -> palette(ctx).secondary
    }

    private fun commitAddonListOrder(ctx: Context, list: LinearLayout) {
        val ids = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
        val currentIds = Config.snapshot().addons.map { it.id }
        if (ids.isNotEmpty() && ids.size <= currentIds.size && ids.toSet() == currentIds.take(ids.size).toSet()) {
            Config.update {
                val byId = addons.associateBy { it.id }
                val loaded = ids.toSet()
                addons = (ids + currentIds.filter { it !in loaded }).mapNotNull { byId[it] }.toMutableList()
            }
        }
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
        val currentIds = Config.snapshot().addons.map { it.id }
        if (ids.isEmpty() || ids.size > currentIds.size || ids.toSet() != currentIds.take(ids.size).toSet()) {
            toast(ctx, "Could not save addon priority.")
            return
        }
        Config.update {
            val byId = addons.associateBy { it.id }
            val loaded = ids.toSet()
            addons = (ids + currentIds.filter { it !in loaded }).mapNotNull { byId[it] }.toMutableList()
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
    private fun checkAddonConnections(ctx: Context, onComplete: (() -> Unit)? = null) {
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
                        AddonHealthStore.record(addon.id, success, elapsed)

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
                    if (SettingsDialogHost.isCurrent("menu:Addons")) onComplete?.invoke() ?: addonsMenu(ctx)
                } else {
                    val usable = results.count { it.success }
                    toast(ctx, "Addon check complete: $usable of ${results.size} returned usable manifests.")
                    onComplete?.invoke() ?: addonsMenu(ctx)
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
            val existing = Config.snapshot().addons.firstOrNull { it.url == url }
            if (existing != null) {
                choose(ctx, "Addon already added", listOf("Open ${existing.name}", "Cancel"), 0,
                    onCancel = { addonsMenu(ctx) }) { action ->
                    if (action == 0) addonMenu(ctx, existing) else addonsMenu(ctx)
                }
                return@prompt
            }

            var hidden = false
            val startedAt = System.nanoTime()
            val progressDialog = progress(
                ctx,
                "Checking addon",
                "Fetching and validating its manifest. You will review the result before it is saved.",
            ) { hidden = true }

            thread(start = true, name = "multistremio-add-addon") {
                val manifestText = SimpleHttp.get(Urls.manifest(url))
                val manifest = Json.parse<Manifest>(manifestText)
                settingsMainHandler.post {
                    progressDialog.dismiss()
                    if (manifestText == null || manifest == null || !manifest.isUsableByMultiStremio()) {
                        toast(ctx, "Couldn't reach that address. Check the link and try again.")
                        if (!hidden) addonsMenu(ctx)
                        return@post
                    }

                    val addon = AddonConfig(
                        id = Config.idFor(url),
                        url = url,
                        name = manifest.name?.takeIf { it.isNotBlank() } ?: host(url),
                        useCatalogs = manifest.hasResource("catalog") && manifest.catalogs.orEmpty().isNotEmpty(),
                        useOther = manifest.hasResource("catalog") && manifest.catalogs.orEmpty().any { it.isSearchable() },
                        useMeta = manifest.hasResource("meta"),
                        useStreams = manifest.hasResource("stream"),
                        useSubtitles = manifest.hasResource("subtitles"),
                        manifest = manifestText,
                        manifestFetchedAt = System.currentTimeMillis(),
                    )
                    if (hidden) {
                        toast(ctx, "Manifest checked. Reopen Add addon to review and save it.")
                        return@post
                    }
                    reviewAddon(ctx, addon, manifest, ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0))
                }
            }
        }
    }

    private fun reviewAddon(ctx: Context, addon: AddonConfig, manifest: Manifest, elapsedMs: Long) {
        val colors = palette(ctx)
        val root = shell(ctx, "Review addon", "Check the manifest details and capabilities before adding this source.")
        val card = settingsCard(ctx, colors)
        listOf(
            "Name" to addon.name,
            "Version" to (manifest.version?.takeIf { it.isNotBlank() } ?: "Not provided"),
            "Response" to "${elapsedMs} ms",
            "Catalogs" to manifest.catalogs.orEmpty().size.toString(),
        ).forEach { (label, value) ->
            addCardRow(ctx, colors, card, LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
                addView(text(ctx, label, 11f, colors.secondary, true))
                addView(text(ctx, value, 13f, colors.primary).apply { setPadding(0, dp(ctx, 2), 0, 0) })
            })
        }
        addCardRow(ctx, colors, card, sectionLabel(ctx, colors, "Use this addon for"))
        listOf(
            Triple("Catalogs and search", "Show catalog rows on the homepage and use them in search", "useCatalogs"),
            Triple("Separate search rows", "Give searchable catalogs their own search section", "useOther"),
            Triple("Metadata", "Use this addon for title details and episode lists", "useMeta"),
            Triple("Streams", "Use this addon to find playable links", "useStreams"),
            Triple("Subtitles", "Use this addon to find subtitles", "useSubtitles"),
        ).forEach { (title, summary, capability) ->
            val checked = when (capability) {
                "useCatalogs" -> addon.useCatalogs
                "useOther" -> addon.useOther
                "useMeta" -> addon.useMeta
                "useStreams" -> addon.useStreams
                else -> addon.useSubtitles
            }
            addCardRow(ctx, colors, card, switchRow(ctx, colors, title, summary, checked) { enabled ->
                when (capability) {
                    "useCatalogs" -> addon.useCatalogs = enabled
                    "useOther" -> addon.useOther = enabled
                    "useMeta" -> addon.useMeta = enabled
                    "useStreams" -> addon.useStreams = enabled
                    else -> addon.useSubtitles = enabled
                }
            })
        }
        root.addView(card)
        addFooter(ctx, root, "Add addon", {
            var added = false
            Config.update {
                if (addons.none { it.url == addon.url }) {
                    addons.add(addon)
                    added = true
                }
            }
            if (added) {
                HomeRefreshNotice.mark()
                toast(ctx, "Added ${addon.name}.")
                addonsMenu(ctx)
            } else {
                val existing = Config.snapshot().addons.firstOrNull { it.url == addon.url }
                if (existing != null) {
                    choose(ctx, "Addon already added", listOf("Open ${existing.name}", "Close"), 0,
                        onCancel = { addonsMenu(ctx) }) { action ->
                        if (action == 0) addonMenu(ctx, existing) else addonsMenu(ctx)
                    }
                } else addonsMenu(ctx)
            }
        }, "Cancel", { addonsMenu(ctx) })
        SettingsDialogHost.show(ctx, "addon-review", root) { addonsMenu(ctx) }
    }

    private fun addonMenu(ctx: Context, addon: AddonConfig) {
        // Refresh from persisted settings each time this detail screen is reopened.
        val a = Config.snapshot().addons.firstOrNull { it.id == addon.id } ?: addon
        val again = { addonMenu(ctx, a) }
        val items = listOf(
            SettingItem(
                "Addon enabled",
                "Keep this addon saved; change only whether it is used",
                SettingItemType.Switch,
                onToggle = { enabled ->
                    Config.update { this.setAddonEnabled(a.id, enabled) }
                    HomeRefreshNotice.mark()
                },
                checked = a.enabled,
                searchKeywords = listOf("enable", "disable", "active", "addon"),
            ),
            SettingItem(
                "Catalogs and search", "Use this addon for homepage catalogs and search", SettingItemType.Switch,
                onToggle = { enabled -> updateCapability(a) { useCatalogs = enabled } },
                checked = a.useCatalogs, searchKeywords = listOf("catalogs", "search", "homepage"),
            ),
            SettingItem(
                "Separate search rows", "Include this addon's catalogs in its own search section", SettingItemType.Switch,
                onToggle = { enabled -> updateCapability(a) { useOther = enabled } },
                checked = a.useOther, searchKeywords = listOf("other", "separate", "search rows"),
            ),
            SettingItem(
                "Metadata", "Use this addon for titles, details and episode lists", SettingItemType.Switch,
                onToggle = { enabled -> updateCapability(a) { useMeta = enabled } },
                checked = a.useMeta, searchKeywords = listOf("metadata", "details", "episodes"),
            ),
            SettingItem(
                "Streams", "Use this addon to find playable links", SettingItemType.Switch,
                onToggle = { enabled -> updateCapability(a) { useStreams = enabled } },
                checked = a.useStreams, searchKeywords = listOf("streams", "links", "playback"),
            ),
            SettingItem(
                "Subtitles", "Use this addon to find subtitles", SettingItemType.Switch,
                onToggle = { enabled -> updateCapability(a) { useSubtitles = enabled } },
                checked = a.useSubtitles, searchKeywords = listOf("subtitles", "captions"),
            ),
            SettingItem(
                "Manifest URL", "Hidden by default because it may contain private keys", SettingItemType.Value,
                valueLabel = "••••${host(a.url)}",
                onClick = {
                    choose(ctx, "Manifest URL", listOf("Show URL", "Copy URL"), 0, onCancel = again) { action ->
                        if (action == 0) {
                            confirm(ctx, "Private manifest URL", a.url, onCancel = again) { again() }
                        } else {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Addon manifest URL", a.url))
                            toast(ctx, "Manifest URL copied. Keep it private.")
                            again()
                        }
                    }
                },
                searchKeywords = listOf("manifest", "URL", "copy", "private key", "endpoint"),
            ),
            SettingItem(
                "Request timeout",
                "Use the global timeout or override it for this addon",
                SettingItemType.Value,
                valueLabel = a.timeoutSec?.let { "$it seconds" } ?: "Global (${Config.snapshot().timeoutSec}s)",
                onClick = {
                    val values = listOf<Int?>(null, 10, 30, 60)
                    val labels = listOf("Global (${Config.snapshot().timeoutSec}s)", "10 seconds", "30 seconds", "60 seconds")
                    val selected = values.indexOf(a.timeoutSec).coerceAtLeast(0)
                    choose(ctx, "Addon request timeout", labels, selected, onCancel = again) { index ->
                        Config.update { addons.firstOrNull { it.id == a.id }?.timeoutSec = values[index] }
                        again()
                    }
                },
                searchKeywords = listOf("timeout", "slow", "request", "seconds", "global"),
            ),
            SettingItem(
                "Maximum links from this addon",
                "0 means no per-addon limit",
                SettingItemType.Value,
                valueLabel = limit(a.maxLinks, ""),
                onClick = {
                    boundedNumber(
                        ctx, "Maximum links from ${a.name}", a.maxLinks, 0, 50_000,
                        "Enter a whole number from 0 to 50,000. 0 means no per-addon limit.",
                        onCancel = again,
                    ) { value ->
                        Config.update { addons.firstOrNull { it.id == a.id }?.maxLinks = value }
                        again()
                    }
                },
                searchKeywords = listOf("maximum", "links", "limit", "results", "per-addon"),
            ),
            SettingItem(
                "Home catalogs to show",
                "Select the catalog rows this addon contributes to the home page. Other creates separate search sections for searchable catalogs, even when Catalogs is off.",
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
                "Test with an IMDb ID",
                "Run a stream lookup against this addon only",
                SettingItemType.Navigate,
                onClick = { testAddonWithTitle(ctx, a, again) },
                searchKeywords = listOf("test", "title", "IMDb", "lookup", "streams"),
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
                        val beforeRemoval = Config.snapshot()
                        if (AutomaticBackupStore(ctx).create("Before removing ${a.name}", beforeRemoval) == null) {
                            toast(ctx, "Addon removal cancelled because an automatic backup could not be saved.")
                            again()
                            return@confirm
                        }
                        Config.update { removeAddon(a.id) }
                        HomeRefreshNotice.mark()
                        addonsMenu(ctx)
                        showUndoSnackbar(ctx, "${a.name} removed") {
                            Config.replace(beforeRemoval)
                            HomeRefreshNotice.mark()
                            toast(ctx, "${a.name} restored.")
                            addonsMenu(ctx)
                        }
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

    private fun updateCapability(addon: AddonConfig, update: AddonConfig.() -> Unit) {
        Config.update { addons.firstOrNull { it.id == addon.id }?.update() }
        HomeRefreshNotice.mark()
    }

    private fun testAddonWithTitle(ctx: Context, addon: AddonConfig, onBack: () -> Unit) {
        prompt(
            ctx,
            "Test ${addon.name} with an IMDb ID",
            "Enter a movie or episode ID such as tt1234567. This sends one stream request to this addon only.",
            "",
            false,
            onCancel = onBack,
        ) { rawId ->
            val id = rawId.trim()
            if (!id.matches(Regex("tt[0-9]{5,12}"))) {
                toast(ctx, "Enter a valid IMDb ID beginning with tt.")
                addonMenu(ctx, addon)
                return@prompt
            }
            val settings = Config.snapshot()
            val current = settings.addons.firstOrNull { it.id == addon.id && it.enabled && it.useStreams }
            if (current == null || Config.manifestOf(current)?.supports("stream", "movie", id) != true) {
                toast(ctx, "This addon is disabled or does not support movie streams for that ID.")
                addonMenu(ctx, addon)
                return@prompt
            }
            var hidden = false
            val dialog = progress(ctx, "Testing addon", "Requesting streams for $id.") { hidden = true }
            thread(start = true, name = "multistremio-addon-title-test") {
                val result = runCatching {
                    runBlocking {
                        StremioClient.streams(
                            current,
                            "movie",
                            id,
                            timeoutSec = current.timeoutSec ?: settings.timeoutSec,
                            retryOnce = settings.retryFailedAddonOnce,
                        )
                    }
                }
                settingsMainHandler.post {
                    dialog.dismiss()
                    val summary = result.fold(
                        onSuccess = { streams ->
                            val playable = streams.count { !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank() }
                            "Received ${streams.size} stream response(s), including $playable playable candidate(s)."
                        },
                        onFailure = { "The stream request failed: ${it.javaClass.simpleName}." },
                    )
                    if (hidden) toast(ctx, summary) else confirm(ctx, "${addon.name} test", summary, onCancel = onBack) { onBack() }
                }
            }
        }
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
