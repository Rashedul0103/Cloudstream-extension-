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

internal object SortingSettingsScreen {
    fun show(ctx: Context) = sortMenu(ctx)

    private fun sortMenu(ctx: Context) {
        val s = Config.settings.sort
        val preset = SortPreset.from(s.preset)
        val rows = listOf(
            "Order: ${preset.label}",
            "Cached debrid links first: ${onOff(s.cachedFirst)}",
            "Prefer keywords: ${csv(s.preferKeywords)}",
            "Prefer languages: ${csv(s.preferLanguages)}",
            "Prefer video codecs: ${csv(s.preferCodecs)}",
            "Prefer audio formats: ${csv(s.preferAudio)}",
            "Use my order across resolutions: ${onOff(s.overrideAppQuality)}",
        )
        val again = { sortMenu(ctx) }
        menu(ctx, "Stream sorting", rows, { MainMenuSettingsScreen.show(ctx) }, "Choose how available streams are ranked. The first item in each preference list has the highest priority; leave a list empty to disable that preference.") { which ->
            when (which) {
                0 -> choose(ctx, "Sort order", SortPreset.values().map { it.label }, preset.ordinal, onCancel = again) {
                    updateAndReopen({ again() }) { s.preset = SortPreset.values()[it].name }
                }
                1 -> { updateAndReopen({ again() }) { s.cachedFirst = !s.cachedFirst } }
                2 -> keywordList(
                    ctx,
                    "Prefer keywords",
                    "Streams containing any of these go first. Comma separated. Wrap regular expressions in slashes, e.g. /x26[45]/.",
                    s.preferKeywords.joinToString(", "),
                    onCancel = again,
                ) { values -> updateAndReopen({ again() }) { s.preferKeywords = values } }
                3 -> prompt(ctx, "Prefer languages", "e.g. bn, en, hi. Streams matching an earlier language go first.",
                    s.preferLanguages.joinToString(", "), false, onCancel = again) { updateAndReopen({ again() }) { s.preferLanguages = parseCsv(it) } }
                4 -> prompt(ctx, "Prefer video codecs", "Enter a priority order, comma separated. Example: HEVC, AV1, AVC. Unlisted and unknown codecs go after listed codecs. This does not filter streams.",
                    s.preferCodecs.joinToString(", "), false, onCancel = again) { updateAndReopen({ again() }) { s.preferCodecs = parseCsv(it) } }
                5 -> prompt(ctx, "Prefer audio formats", "Enter a priority order, comma separated. Example: Atmos, TrueHD, DTS-HD, DD+, AAC. Unlisted and unknown formats go after listed formats.",
                    s.preferAudio.joinToString(", "), false, onCancel = again) { updateAndReopen({ again() }) { s.preferAudio = parseCsv(it) } }
                6 -> confirm(
                    ctx, "Use my order across resolutions?",
                    "CloudStream normally ranks links by its own quality profile first and only keeps this plugin's " +
                        "order between links of equal quality. Turning this ON makes every link report " +
                        "'unknown quality' so the order chosen here is shown exactly. Resolution still appears " +
                        "in each link's name. Currently ${onOff(s.overrideAppQuality)}; switch it?",
                    onCancel = again,
                ) { updateAndReopen({ again() }) { s.overrideAppQuality = !s.overrideAppQuality } }
            }
        }
    }
}
