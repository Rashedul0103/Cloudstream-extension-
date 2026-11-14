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

internal object GeneralSettingsScreen {
    fun show(ctx: Context) = generalMenu(ctx)
    fun showSubtitles(ctx: Context) = subtitlesMenu(ctx)

    private fun subtitlesMenu(ctx: Context) {
        val s = Config.settings
        val rows = listOf(
            "Fetch from subtitle addons: ${onOff(s.fetchSubtitles)}",
            "Language filter: ${s.subtitleLanguages.takeIf { it.isNotEmpty() }?.let { csv(it) } ?: "All languages"}",
        )
        menu(ctx, "Subtitles", rows, { MainMenuSettingsScreen.show(ctx) }, "Choose whether subtitle addons are queried and which languages are preferred.") { which ->
            when (which) {
                0 -> { updateAndReopen({ subtitlesMenu(ctx) }) { fetchSubtitles = !fetchSubtitles } }
                1 -> prompt(ctx, "Subtitle languages", "Codes, regional tags or names, e.g. en, eng, en-US, pt-BR, Bengali. Leave empty for all languages.",
                    s.subtitleLanguages.joinToString(", "), false, onCancel = { subtitlesMenu(ctx) }) {
                    updateAndReopen({ subtitlesMenu(ctx) }) { subtitleLanguages = parseCsv(it) }
                }
            }
        }
    }

    private fun generalMenu(ctx: Context) {
        val s = Config.settings
        val rows = listOf(
            "Request timeout: ${s.timeoutSec} s",
            "Detailed default names (when custom layout is OFF): ${onOff(s.detailedLabels)}",
        )
        menu(ctx, "General", rows, { MainMenuSettingsScreen.show(ctx) }, "Tune request limits and the amount of technical detail shown on stream links.") { which ->
            when (which) {
                0 -> number(
                    ctx,
                    "Timeout per addon request (seconds)",
                    s.timeoutSec,
                    description = "Enter 5–180 seconds. This controls how long each addon request can wait before timing out.",
                    onCancel = { generalMenu(ctx) },
                ) {
                    updateAndReopen({ generalMenu(ctx) }) { timeoutSec = it.coerceIn(5, 180) }
                }
                1 -> { updateAndReopen({ generalMenu(ctx) }) { detailedLabels = !detailedLabels } }
            }
        }
    }
}
