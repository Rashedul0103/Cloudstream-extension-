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

internal object DiagnosticsSettingsScreen {
    fun show(ctx: Context) = streamDiagnosticsMenu(ctx)

    private fun streamDiagnosticsMenu(ctx: Context) {
        val snapshot = StreamDiagnostics.latest()
        if (snapshot == null) {
            menu(
                ctx,
                "Streaming diagnostics",
                listOf("No completed stream lookup recorded yet."),
                { MainMenuSettingsScreen.show(ctx) },
                "Open a movie or episode and load its links first. The report is kept in memory and is cleared when CloudStream restarts.",
            ) { }
            return
        }

        val colors = palette(ctx)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(snapshot.startedAt))
        val root = shell(
            ctx,
            "Streaming diagnostics",
            "Latest lookup · $time · ${snapshot.durationMs} ms total. This report contains addon names and counts only, never stream URLs or addon access keys.",
        )
        val summary = vertical(ctx, dp(ctx, 12)).apply {
            background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
            listOf(
                "Raw streams returned: ${snapshot.rawStreams}",
                "Addon subtitle tracks: ${snapshot.addonSubtitles}",
                "Playable candidates before filters: ${snapshot.playableCandidates}",
                "Streams kept after filters and deduplication: ${snapshot.keptAfterFilters}",
                "Links handed to CloudStream: ${snapshot.emittedLinks}",
                "Addon requests recorded: ${snapshot.requests.size}",
                "Parsing work (parallel totals): ${snapshot.parseWorkMs} ms",
                "Filtering, merging & sorting: ${snapshot.pipelineMs} ms",
                "Link emission & subtitle callbacks: ${snapshot.emissionMs} ms",
            ).forEach { addView(text(ctx, it, 13f, colors.primary).apply {
                setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
            }) }
        }
        root.addView(summary)
        val list = vertical(ctx)
        if (snapshot.requests.isEmpty()) {
            list.addView(text(ctx, "No addon request details were recorded.", 13f, colors.secondary))
        } else {
            snapshot.requests.sortedWith(compareBy<StreamRequestDiagnostic> { it.status == "OK" }.thenByDescending { it.elapsedMs })
                .forEach { item ->
                    val status = if (item.resultCount > 0) {
                        "${item.status} · ${item.resultCount} items"
                    } else item.status
                    val card = vertical(ctx, dp(ctx, 10)).apply {
                        background = rounded(ctx, colors.background, colors.outline)
                        addView(text(
                            ctx,
                            "${item.addonName} · ${item.resource}",
                            13f,
                            colors.primary,
                            true,
                        ))
                        addView(text(ctx, "$status · ${item.elapsedMs} ms", 12f, colors.secondary).apply {
                            setPadding(0, dp(ctx, 3), 0, 0)
                        })
                    }
                    list.addView(card, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 7) })
                }
        }
        val scroll = ScrollView(ctx).apply {
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(ctx, 300), dp(ctx, maxOf(72, snapshot.requests.size * 62))),
        ).apply { topMargin = dp(ctx, 10) })
        val report = buildString {
            appendLine("MultiStremio streaming diagnostics")
            appendLine("Time: $time")
            appendLine("Duration: ${snapshot.durationMs} ms")
            appendLine("Raw streams: ${snapshot.rawStreams}")
            appendLine("Addon subtitles: ${snapshot.addonSubtitles}")
            appendLine("Playable before filters: ${snapshot.playableCandidates}")
            appendLine("Kept after filters/deduplication: ${snapshot.keptAfterFilters}")
            appendLine("Links handed to CloudStream: ${snapshot.emittedLinks}")
            appendLine("Parsing work (parallel totals): ${snapshot.parseWorkMs} ms")
            appendLine("Filter/dedup/sort: ${snapshot.pipelineMs} ms")
            appendLine("Link emission: ${snapshot.emissionMs} ms")
            snapshot.requests.forEach { item ->
                val outcome = if (item.resultCount > 0) "${item.status} (${item.resultCount} items)" else item.status
                appendLine("${item.addonName} | ${item.resource} | $outcome | ${item.elapsedMs} ms")
            }
        }
        addFooter(ctx, root, "Back", {
                MainMenuSettingsScreen.show(ctx)
        }, "Copy report", {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MultiStremio streaming diagnostics", report))
            toast(ctx, "Diagnostics copied. The report contains no addon URLs.")
        })
        SettingsDialogHost.show(ctx, "diagnostics:report", root)
    }
}
