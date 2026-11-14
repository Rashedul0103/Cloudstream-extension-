package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.app.AlertDialog
import java.net.URL
import kotlin.concurrent.thread

/** Settings screens built from plain AlertDialogs, so the plugin needs no XML resources. */
object SettingsUi {
    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context) = mainMenu(ctx)

    // ---- small building blocks ------------------------------------------------------------

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun menu(ctx: Context, title: String, items: List<String>, back: (() -> Unit)?, pick: (Int) -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> pick(which) }
            .setNegativeButton(if (back != null) "Back" else "Close") { _, _ -> back?.invoke() }
            .show()
    }

    private fun prompt(
        ctx: Context, title: String, message: String?, initial: String, numeric: Boolean,
        onOk: (String) -> Unit,
    ) {
        val input = EditText(ctx)
        input.setText(initial)
        if (numeric) input.inputType = InputType.TYPE_CLASS_NUMBER
        val box = FrameLayout(ctx)
        val pad = dp(ctx, 20)
        box.setPadding(pad, dp(ctx, 8), pad, 0)
        box.addView(input)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(ctx: Context, title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choose(
        ctx: Context, title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit,
    ) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> onPick(which); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun multi(
        ctx: Context, title: String, labels: List<String>, initial: List<Boolean>, onSave: (List<Boolean>) -> Unit,
    ) {
        val state = initial.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), state) { _, which, isChecked -> state[which] = isChecked }
            .setPositiveButton("Save") { _, _ -> onSave(state.toList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun csv(list: List<String>) = if (list.isEmpty()) "none" else list.joinToString(", ")
    private fun parseCsv(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"
    private fun limit(n: Int, unit: String) = if (n <= 0) "off" else "$n $unit"
    private fun modeLabel(m: Int) = when (m) { MODE_HIDE -> "hide"; MODE_ONLY -> "only"; else -> "any" }

    private fun host(url: String) = try { URL(url).host } catch (e: Exception) { url }

    // ---- main menu -------------------------------------------------------------------------

    private fun mainMenu(ctx: Context) {
        val s = Config.settings
        menu(
            ctx, "MultiStremio",
            listOf(
                "Addons (${s.addons.count { it.enabled }}/${s.addons.size} enabled)",
                "Stream filters",
                "Stream sorting",
                "Subtitles",
                "General",

                "Backup / restore / reset",
            ),
            null,
        ) {
            when (it) {
                0 -> addonsMenu(ctx)
                1 -> filtersMenu(ctx)
                2 -> sortMenu(ctx)
                3 -> subtitlesMenu(ctx)
                4 -> generalMenu(ctx)
                5 -> backupMenu(ctx)
            }
        }
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
        } + "+ Add addon"
        menu(ctx, "Addons (order = priority)", rows, { mainMenu(ctx) }) { which ->
            if (which == addons.size) addAddon(ctx) else addonMenu(ctx, addons[which])
        }
    }

    private fun addAddon(ctx: Context) {
        prompt(
            ctx, "Add addon",
            "Paste the addon's manifest link (https://…/manifest.json or stremio://…). " +
                "Links often contain your personal keys, so keep them private.",
            "", false,
        ) { raw ->
            val url = Urls.normalizeAddonUrl(raw)
            if (url.isEmpty()) return@prompt
            if (Config.settings.addons.any { it.url.equals(url, ignoreCase = true) }) {
                toast(ctx, "That addon is already added"); return@prompt
            }
            toast(ctx, "Checking addon…")
            thread(start = true) {
                val text = SimpleHttp.get("$url/manifest.json")
                val m = Json.parse<Manifest>(text)
                if (text == null || m == null || (m.name == null && m.resources == null)) {
                    toast(ctx, "Could not read a manifest from that link"); return@thread
                }
                val addon = AddonConfig(
                    id = Config.idFor(url), url = url, name = m.name ?: host(url),
                    useCatalogs = m.catalogs.orEmpty().isNotEmpty(),
                    useMeta = m.hasResource("meta"),
                    useStreams = m.hasResource("stream"),
                    useSubtitles = m.hasResource("subtitles"),
                    manifest = text, manifestFetchedAt = System.currentTimeMillis(),
                )
                Config.update { addons.add(addon) }
                toast(ctx, "Added ${addon.name}. Refresh the home page to see its catalogs.")
                main.post { addonsMenu(ctx) }
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
        ) { which ->
            when (which) {
                0 -> { Config.update { a.enabled = !a.enabled }; addonMenu(ctx, a) }
                1 -> multi(
                    ctx, "${a.name} is used for",
                    listOf("Catalogs & search", "Metadata (details/episodes)", "Streams", "Subtitles"),
                    listOf(a.useCatalogs, a.useMeta, a.useStreams, a.useSubtitles),
                ) { v ->
                    Config.update { a.useCatalogs = v[0]; a.useMeta = v[1]; a.useStreams = v[2]; a.useSubtitles = v[3] }
                    addonMenu(ctx, a)
                }
                2 -> catalogPicker(ctx, a)
                3 -> { move(a, -1); addonsMenu(ctx) }
                4 -> { move(a, +1); addonsMenu(ctx) }
                5 -> prompt(ctx, "Rename addon", null, a.name, false) { n ->
                    if (n.isNotEmpty()) Config.update { a.name = n }
                    addonMenu(ctx, a)
                }
                6 -> {
                    toast(ctx, "Refreshing…")
                    thread(start = true) {
                        val ok = Config.refreshManifest(a)
                        val m = Config.manifestOf(a)
                        val resources = m?.resources.orEmpty().mapNotNull {
                            if (it is String) it else (it as? Map<*, *>)?.get("name") as? String

                        }.distinct()
                        toast(
                            ctx,
                            if (ok) "OK: ${m?.name ?: a.name} v${m?.version ?: "?"}, ${m?.catalogs.orEmpty().size} catalogs, " +
                                "provides ${resources.joinToString(", ").ifEmpty { "nothing" }}"
                            else "Could not download the manifest",
                        )
                    }
                }
                7 -> confirm(ctx, "Remove ${a.name}?", "This only removes it from MultiStremio.") {
                    Config.update { addons.remove(a) }
                    addonsMenu(ctx)
                }
            }
        }
    }

    private fun move(a: AddonConfig, delta: Int) {
        val list = Config.settings.addons
        val i = list.indexOf(a)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        Config.update { list[i] = list[j].also { list[j] = list[i] } }
    }

    private fun catalogPicker(ctx: Context, a: AddonConfig) {
        val cats = Config.manifestOf(a)?.catalogs.orEmpty()
            .filter { it.type != null && it.id != null && it.isHomeCatalog() }
        if (cats.isEmpty()) { toast(ctx, "This addon has no browsable catalogs"); addonMenu(ctx, a); return }
        val keys = cats.map { "${it.type}/${it.id}" }
        multi(
            ctx, "Catalogs on the home page",
            cats.map { "${it.name ?: it.id} (${it.type})" },
            keys.map { it !in a.hiddenCatalogs },
        ) { v ->
            Config.update {
                a.hiddenCatalogs = keys.filterIndexed { i, _ -> !v[i] }.toMutableList()
            }
            toast(ctx, "Saved. Refresh the home page to apply.")
            addonMenu(ctx, a)
        }
    }

    // ---- filters ---------------------------------------------------------------------------

    private val RES_OPTIONS = listOf(2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p",
        576 to "576p", 480 to "480p", 360 to "360p", 240 to "240p", 0 to "Unknown")

    private fun filtersMenu(ctx: Context) {
        val f = Config.settings.filter
        val rows = listOf(
            "Resolutions: " + RES_OPTIONS.filter { it.first in f.allowedResolutions }.joinToString(" ") { it.second },
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
        menu(ctx, "Stream filters", rows, { mainMenu(ctx) }) { which ->
            when (which) {
                0 -> multi(ctx, "Allowed resolutions", RES_OPTIONS.map { it.second },
                    RES_OPTIONS.map { it.first in f.allowedResolutions }) { v ->
                    Config.update { f.allowedResolutions = RES_OPTIONS.filterIndexed { i, _ -> v[i] }.map { it.first }.toMutableList() }
                    again()
                }
                1 -> { Config.update { f.hideCam = !f.hideCam }; again() }
                2 -> { Config.update { f.hideMagnets = !f.hideMagnets }; again() }
                3 -> { Config.update { f.hideUncachedDebrid = !f.hideUncachedDebrid }; again() }
                4 -> choose(ctx, "HEVC / x265", listOf("Show any", "Hide HEVC", "Only HEVC"), f.hevcMode) {
                    Config.update { f.hevcMode = it }; again()
                }
                5 -> choose(ctx, "HDR / Dolby Vision", listOf("Show any", "Hide HDR/DV", "Only HDR/DV"), f.hdrMode) {
                    Config.update { f.hdrMode = it }; again()
                }
                6 -> number(ctx, "Min size (MB, 0 = off)", f.minSizeMb) { Config.update { f.minSizeMb = it }; again() }
                7 -> number(ctx, "Max size (GB, 0 = off)", f.maxSizeGb) { Config.update { f.maxSizeGb = it }; again() }
                8 -> number(ctx, "Min seeders (0 = off). Streams without seeder info are kept.", f.minSeeders) {
                    Config.update { f.minSeeders = it }; again()
                }
                9 -> prompt(ctx, "Exclude keywords",
                    "Comma separated, case-insensitive. Wrap in slashes for a regex, e.g. /x26[45]/",
                    f.excludeKeywords.joinToString(", "), false) { Config.update { f.excludeKeywords = parseCsv(it) }; again() }
                10 -> prompt(ctx, "Require keywords",
                    "A stream must contain at least one of these. Leave empty to disable.",
                    f.requireKeywords.joinToString(", "), false) { Config.update { f.requireKeywords = parseCsv(it) }; again() }
                11 -> prompt(ctx, "Required languages",
                    "Codes or names, e.g. en, hi, french. Streams with no detectable language are kept unless strict mode is on. Leave empty to disable.",
                    f.requireLanguages.joinToString(", "), false) { Config.update { f.requireLanguages = parseCsv(it) }; again() }
                12 -> { Config.update { f.strictLanguage = !f.strictLanguage }; again() }
                13 -> number(ctx, "Max results per addon (0 = off)", f.maxPerAddon) { Config.update { f.maxPerAddon = it }; again() }
                14 -> number(ctx, "Max results total (0 = off)", f.maxTotal) { Config.update { f.maxTotal = it }; again() }
            }
        }
    }


    private fun number(ctx: Context, title: String, current: Int, onOk: (Int) -> Unit) =
        prompt(ctx, title, null, current.toString(), true) { onOk(it.toIntOrNull()?.coerceAtLeast(0) ?: 0) }

    // ---- sorting ---------------------------------------------------------------------------

    private fun sortMenu(ctx: Context) {
        val s = Config.settings.sort
        val preset = SortPreset.from(s.preset)
        val rows = listOf(
            "Order: ${preset.label}",
            "Cached debrid links first: ${onOff(s.cachedFirst)}",
            "Prefer keywords: ${csv(s.preferKeywords)}",
            "Prefer languages: ${csv(s.preferLanguages)}",
            "Use my order across resolutions: ${onOff(s.overrideAppQuality)}",
        )
        val again = { sortMenu(ctx) }
        menu(ctx, "Stream sorting", rows, { mainMenu(ctx) }) { which ->
            when (which) {
                0 -> choose(ctx, "Sort order", SortPreset.values().map { it.label }, preset.ordinal) {
                    Config.update { s.preset = SortPreset.values()[it].name }; again()
                }
                1 -> { Config.update { s.cachedFirst = !s.cachedFirst }; again() }
                2 -> prompt(ctx, "Prefer keywords", "Streams containing any of these go first. Comma separated.",
                    s.preferKeywords.joinToString(", "), false) { Config.update { s.preferKeywords = parseCsv(it) }; again() }
                3 -> prompt(ctx, "Prefer languages", "e.g. en, hi. Streams in these languages go first.",
                    s.preferLanguages.joinToString(", "), false) { Config.update { s.preferLanguages = parseCsv(it) }; again() }
                4 -> confirm(
                    ctx, "Use my order across resolutions?",
                    "CloudStream normally ranks links by its own quality profile and only keeps this plugin's " +
                        "order between links of equal quality. Turning this ON makes every link report " +
                        "'unknown quality' so the order chosen here is shown exactly. Resolution still appears " +
                        "in each link's name. Currently ${onOff(s.overrideAppQuality)}; switch it?",
                ) { Config.update { s.overrideAppQuality = !s.overrideAppQuality }; again() }
            }
        }
    }

    // ---- subtitles / general / backup ------------------------------------------------------

    private fun subtitlesMenu(ctx: Context) {
        val s = Config.settings
        val rows = listOf(
            "Fetch from subtitle addons: ${onOff(s.fetchSubtitles)}",
            "Only these languages: ${csv(s.subtitleLanguages)}",
        )
        menu(ctx, "Subtitles", rows, { mainMenu(ctx) }) { which ->
            when (which) {
                0 -> { Config.update { fetchSubtitles = !fetchSubtitles }; subtitlesMenu(ctx) }
                1 -> prompt(ctx, "Subtitle languages", "e.g. en, es, bengali. Leave empty for all languages.",
                    s.subtitleLanguages.joinToString(", "), false) {
                    Config.update { subtitleLanguages = parseCsv(it) }; subtitlesMenu(ctx)
                }
            }
        }
    }

    private fun generalMenu(ctx: Context) {
        val s = Config.settings
        val rows = listOf(
            "Request timeout: ${s.timeoutSec} s",
            "Detailed link names (resolution, size, seeders…): ${onOff(s.detailedLabels)}",
        )
        menu(ctx, "General", rows, { mainMenu(ctx) }) { which ->
            when (which) {
                0 -> number(ctx, "Timeout per addon request (seconds)", s.timeoutSec) {
                    Config.update { timeoutSec = it.coerceIn(5, 180) }; generalMenu(ctx)
                }
                1 -> { Config.update { detailedLabels = !detailedLabels }; generalMenu(ctx) }
            }
        }
    }

    private fun backupMenu(ctx: Context) {
        menu(
            ctx, "Backup / restore / reset",
            listOf("Copy settings to clipboard", "Restore from pasted settings", "Reset filters & sorting", "Remove ALL addons and settings"),
            { mainMenu(ctx) },
        ) { which ->
            when (which) {
                0 -> confirm(
                    ctx, "Copy settings?",
                    "The copy includes your addon links. Those can contain private keys (debrid API keys, etc.), so don't share it.",
                ) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("MultiStremio settings", Json.toJson(Config.settings)))
                    toast(ctx, "Copied")
                }
                1 -> prompt(ctx, "Restore settings", "Paste settings copied earlier. This replaces everything.", "", false) { text ->
                    val parsed = Json.parse<Settings>(text)
                    if (parsed == null) toast(ctx, "That is not valid MultiStremio settings")
                    else { parsed.seeded = true; Config.replace(parsed); toast(ctx, "Restored. Refresh the home page.") }
                }
                2 -> confirm(ctx, "Reset filters & sorting?", "Addons are kept.") {
                    Config.update { filter = FilterSettings(); sort = SortSettings() }
                    toast(ctx, "Reset")
                }
                3 -> confirm(ctx, "Remove everything?", "All addons and settings are deleted (Cinemeta is not re-added).") {
                    Config.replace(Settings(seeded = true))
                    toast(ctx, "Cleared")
                }
            }
        }
    }
}
