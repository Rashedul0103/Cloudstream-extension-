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

internal object FiltersSettingsScreen {
    fun show(ctx: Context) = filtersMenu(ctx)

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
        menu(ctx, "Stream filters", rows, { MainMenuSettingsScreen.show(ctx) }, "Remove unwanted sources and narrow results by quality, size, language and keywords.") { which ->
            when (which) {
                0 -> multi(
                    ctx,
                    "Allowed resolutions",
                    RES_OPTIONS.map { it.second },
                    RES_OPTIONS.map { it.first in f.allowedResolutions },
                    description = "Select which resolutions to allow. Leave every box unchecked to allow any resolution.",
                    onCancel = again,
                ) { v ->
                    updateAndReopen({ again() }) { f.allowedResolutions = RES_OPTIONS.filterIndexed { i, _ -> v[i] }.map { it.first }.toMutableList() }
                }
                1 -> { updateAndReopen({ again() }) { f.hideCam = !f.hideCam } }
                2 -> { updateAndReopen({ again() }) { f.hideMagnets = !f.hideMagnets } }
                3 -> { updateAndReopen({ again() }) { f.hideUncachedDebrid = !f.hideUncachedDebrid } }
                4 -> choose(ctx, "HEVC / x265", listOf("Show any", "Hide HEVC", "Only HEVC"), f.hevcMode, onCancel = again) {
                    updateAndReopen({ again() }) { f.hevcMode = it }
                }
                5 -> choose(ctx, "HDR / Dolby Vision", listOf("Show any", "Hide HDR/DV", "Only HDR/DV"), f.hdrMode, onCancel = again) {
                    updateAndReopen({ again() }) { f.hdrMode = it }
                }
                6 -> number(ctx, "Min size (MB, 0 = off)", f.minSizeMb, onCancel = again) { updateAndReopen({ again() }) { f.minSizeMb = it } }
                7 -> number(ctx, "Max size (GB, 0 = off)", f.maxSizeGb, onCancel = again) { updateAndReopen({ again() }) { f.maxSizeGb = it } }
                8 -> number(ctx, "Min seeders (0 = off). Streams without seeder info are kept.", f.minSeeders, onCancel = again) {
                    updateAndReopen({ again() }) { f.minSeeders = it }
                }
                9 -> keywordList(
                    ctx,
                    "Exclude keywords",
                    "Comma separated, case-insensitive. Wrap in slashes for a regex, e.g. /x26[45]/. Invalid regex patterns are rejected.",
                    f.excludeKeywords.joinToString(", "),
                    onCancel = again,
                ) { values -> updateAndReopen({ again() }) { f.excludeKeywords = values } }
                10 -> keywordList(
                    ctx,
                    "Require keywords",
                    "A stream must contain at least one of these. Leave empty to disable. Wrap regex patterns in slashes, e.g. /x26[45]/.",
                    f.requireKeywords.joinToString(", "),
                    onCancel = again,
                ) { values -> updateAndReopen({ again() }) { f.requireKeywords = values } }
                11 -> prompt(ctx, "Required languages",
                    "Codes, regional tags or names, e.g. en, eng, en-US, pt-BR, Bengali. Streams with no detectable language are kept unless strict mode is on. Leave empty to disable.",
                    f.requireLanguages.joinToString(", "), false, onCancel = again) { updateAndReopen({ again() }) { f.requireLanguages = parseCsv(it) } }
                12 -> { updateAndReopen({ again() }) { f.strictLanguage = !f.strictLanguage } }
                13 -> number(ctx, "Max results per addon (0 = off)", f.maxPerAddon, onCancel = again) { updateAndReopen({ again() }) { f.maxPerAddon = it } }
                14 -> number(ctx, "Max results total (0 = off)", f.maxTotal, onCancel = again) { updateAndReopen({ again() }) { f.maxTotal = it } }
            }
        }
    }
}
