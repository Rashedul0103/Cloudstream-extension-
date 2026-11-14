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
        val s = Config.settings
        val activeRows = HomeRows.visibleCount(s)
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
                0 -> AddonsSettingsScreen.show(ctx)
                1 -> CatalogRowsSettingsScreen.show(ctx)
                2 -> FiltersSettingsScreen.show(ctx)
                3 -> SortingSettingsScreen.show(ctx)
                4 -> FormatterSettingsScreen.show(ctx)
                5 -> DiagnosticsSettingsScreen.show(ctx)
                6 -> GeneralSettingsScreen.showSubtitles(ctx)
                7 -> GeneralSettingsScreen.show(ctx)
                8 -> BackupSettingsScreen.show(ctx)
            }
        }
    }
}
