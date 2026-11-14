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

internal object MainMenuSettingsScreen {
    fun show(ctx: Context) = mainMenu(ctx)

    private fun mainMenu(ctx: Context) {
        val settings = Config.settings
        val homeRowCount = HomeRows.visibleCount(settings)
        val items = listOf(
            SettingItem(
                title = "Addons (${settings.addons.count { it.enabled }}/${settings.addons.size} enabled)",
                summary = "${settings.addons.size} sources configured; manage their priority and capabilities",
                onClick = { AddonsSettingsScreen.show(ctx) },
                searchKeywords = listOf("addons", "sources", "priority", "manifests"),
            ),
            SettingItem(
                title = "Catalog rows (visibility & order)",
                summary = "$homeRowCount homepage rows currently enabled; manage visibility and order",
                onClick = { CatalogRowsSettingsScreen.show(ctx) },
                searchKeywords = listOf("catalog", "homepage", "rows", "visibility", "order", "extensions"),
            ),
            SettingItem(
                title = "Stream filters",
                summary = "Choose which streams are allowed",
                onClick = { FiltersSettingsScreen.show(ctx) },
                searchKeywords = listOf("filters", "resolution", "size", "language", "keywords", "HDR"),
            ),
            SettingItem(
                title = "Stream sorting",
                summary = "Control quality, language, codec and audio ranking",
                onClick = { SortingSettingsScreen.show(ctx) },
                searchKeywords = listOf("sorting", "ranking", "quality", "seeders", "codec", "audio"),
            ),
            SettingItem(
                title = "Stream formatter",
                summary = "Build your own link layout without writing code",
                onClick = { FormatterSettingsScreen.show(ctx) },
                searchKeywords = listOf("formatter", "format", "labels", "link name", "layout"),
            ),
            SettingItem(
                title = "Last streaming diagnostics",
                summary = "Inspect recent source responses and filtered-link counts",
                onClick = { DiagnosticsSettingsScreen.show(ctx) },
                searchKeywords = listOf("diagnostics", "debug", "requests", "performance"),
            ),
            SettingItem(
                title = "Subtitles",
                summary = "Control subtitle sources and languages",
                onClick = { GeneralSettingsScreen.showSubtitles(ctx) },
                searchKeywords = listOf("subtitles", "captions", "languages"),
            ),
            SettingItem(
                title = "General",
                summary = "Request timeout and link display options",
                onClick = { GeneralSettingsScreen.show(ctx) },
                searchKeywords = listOf("general", "timeout", "labels", "display"),
            ),
            SettingItem(
                title = "Backup / restore / reset",
                summary = "Protect, copy or restore your configuration",
                onClick = { BackupSettingsScreen.show(ctx) },
                searchKeywords = listOf("backup", "restore", "reset", "export", "import"),
            ),
        )
        settingMenu(
            ctx,
            "MultiStremio",
            items,
            null,
            "Manage sources, homepage layout and playback. Search is available inside longer settings lists.",
        )
    }
}
