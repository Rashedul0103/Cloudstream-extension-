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
        val again = { filtersMenu(ctx) }
        val items = listOf(
            SettingItem("Resolutions",
                RES_OPTIONS.filter { it.first in f.allowedResolutions }.joinToString(" ") { it.second }.ifBlank { "Any (no restriction)" },
                SettingItemType.Value,
                onClick = {
                    multi(ctx, "Allowed resolutions", RES_OPTIONS.map { it.second },
                        RES_OPTIONS.map { it.first in f.allowedResolutions },
                        description = "Select which resolutions to allow. Leave every box unchecked to allow any resolution.",
                        onCancel = again) { selected ->
                        updateAndReopen({ again() }) {
                            f.allowedResolutions = RES_OPTIONS.filterIndexed { index, _ -> selected[index] }
                                .map { it.first }.toMutableList()
                        }
                    }
                }, searchKeywords = listOf("resolution", "4K", "1080p", "720p")),
            SettingItem("Hide CAM / telesync", onOff(f.hideCam), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.hideCam = !f.hideCam } },
                searchKeywords = listOf("cam", "telesync", "recorded cinema")),
            SettingItem("Hide magnet (torrent) links", onOff(f.hideMagnets), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.hideMagnets = !f.hideMagnets } },
                searchKeywords = listOf("magnet", "torrent")),
            SettingItem("Hide uncached debrid links", onOff(f.hideUncachedDebrid), SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.hideUncachedDebrid = !f.hideUncachedDebrid } },
                searchKeywords = listOf("uncached", "debrid", "torrent")),
            SettingItem("HEVC / x265", modeLabel(f.hevcMode), SettingItemType.Value,
                onClick = { choose(ctx, "HEVC / x265", listOf("Show any", "Hide HEVC", "Only HEVC"), f.hevcMode, onCancel = again) {
                    updateAndReopen({ again() }) { f.hevcMode = it }
                } }, searchKeywords = listOf("HEVC", "x265", "video codec")),
            SettingItem("HDR / Dolby Vision", modeLabel(f.hdrMode), SettingItemType.Value,
                onClick = { choose(ctx, "HDR / Dolby Vision", listOf("Show any", "Hide HDR/DV", "Only HDR/DV"), f.hdrMode, onCancel = again) {
                    updateAndReopen({ again() }) { f.hdrMode = it }
                } }, searchKeywords = listOf("HDR", "Dolby Vision", "DV")),
            SettingItem("Minimum file size", limit(f.minSizeMb, "MB"), SettingItemType.Value,
                onClick = { number(ctx, "Min size (MB, 0 = off)", f.minSizeMb, onCancel = again) {
                    updateAndReopen({ again() }) { f.minSizeMb = it }
                } }, searchKeywords = listOf("minimum", "min", "size", "MB")),
            SettingItem("Maximum file size", limit(f.maxSizeGb, "GB"), SettingItemType.Value,
                onClick = { number(ctx, "Max size (GB, 0 = off)", f.maxSizeGb, onCancel = again) {
                    updateAndReopen({ again() }) { f.maxSizeGb = it }
                } }, searchKeywords = listOf("maximum", "max", "size", "GB")),
            SettingItem("Minimum seeders", limit(f.minSeeders, ""), SettingItemType.Value,
                onClick = { number(ctx, "Min seeders (0 = off). Streams without seeder info are kept.", f.minSeeders, onCancel = again) {
                    updateAndReopen({ again() }) { f.minSeeders = it }
                } }, searchKeywords = listOf("seeders", "torrent")),
            SettingItem("Exclude keywords", csv(f.excludeKeywords), SettingItemType.Value,
                onClick = {
                    keywordList(ctx, "Exclude keywords",
                        "Comma separated, case-insensitive. Wrap in slashes for a regex, e.g. /x26[45]/. Invalid regex patterns are rejected.",
                        f.excludeKeywords.joinToString(", "), onCancel = again) { values ->
                        updateAndReopen({ again() }) { f.excludeKeywords = values }
                    }
                }, searchKeywords = listOf("exclude", "block", "keywords", "regex")),
            SettingItem("Require keywords (any)", csv(f.requireKeywords), SettingItemType.Value,
                onClick = {
                    keywordList(ctx, "Require keywords",
                        "A stream must contain at least one of these. Leave empty to disable. Wrap regex patterns in slashes, e.g. /x26[45]/.",
                        f.requireKeywords.joinToString(", "), onCancel = again) { values ->
                        updateAndReopen({ again() }) { f.requireKeywords = values }
                    }
                }, searchKeywords = listOf("require", "include", "keywords", "regex")),
            SettingItem("Required languages", csv(f.requireLanguages), SettingItemType.Value,
                onClick = {
                    prompt(ctx, "Required languages",
                        "Codes, regional tags or names, e.g. en, eng, en-US, pt-BR, Bengali. Streams with no detectable language are kept unless strict mode is on. Leave empty to disable.",
                        f.requireLanguages.joinToString(", "), false, onCancel = again) {
                        updateAndReopen({ again() }) { f.requireLanguages = parseCsv(it) }
                    }
                }, searchKeywords = listOf("language", "audio language", "Bengali", "English")),
            SettingItem("Strict language", "Drop streams with unknown language: ${onOff(f.strictLanguage)}", SettingItemType.Switch,
                onClick = { updateAndReopen({ again() }) { f.strictLanguage = !f.strictLanguage } },
                searchKeywords = listOf("strict", "unknown language")),
            SettingItem("Maximum results per addon", limit(f.maxPerAddon, ""), SettingItemType.Value,
                onClick = { number(ctx, "Max results per addon (0 = off)", f.maxPerAddon, onCancel = again) {
                    updateAndReopen({ again() }) { f.maxPerAddon = it }
                } }, searchKeywords = listOf("limit", "per addon", "maximum")),
            SettingItem("Maximum results total", limit(f.maxTotal, ""), SettingItemType.Value,
                onClick = { number(ctx, "Max results total (0 = off)", f.maxTotal, onCancel = again) {
                    updateAndReopen({ again() }) { f.maxTotal = it }
                } }, searchKeywords = listOf("limit", "total", "maximum")),
        )
        settingMenu(ctx, "Stream filters", items, { MainMenuSettingsScreen.show(ctx) },
            "Remove unwanted sources and narrow results by quality, size, language and keywords.")
    }

}
