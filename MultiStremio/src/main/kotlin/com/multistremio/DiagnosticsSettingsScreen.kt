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
    fun show(ctx: Context) {
        // Diagnostics uses a custom report view, not settingMenu's searchable list.
        SettingsDialogHost.consumeNextMenuSearch()
        streamDiagnosticsMenu(ctx)
    }

    private fun streamDiagnosticsMenu(ctx: Context) {
        val snapshot = StreamDiagnostics.latest()
        if (snapshot == null) {
            settingMenu(
                ctx,
                "Streaming diagnostics",
                listOf(SettingItem(
                    title = "No completed stream lookup recorded yet",
                    summary = "Open a movie or episode and load its links first",
                    type = SettingItemType.Value,
                )),
                { MainMenuSettingsScreen.show(ctx) },
                "The report is kept in memory and is cleared when CloudStream restarts.",
            )
            return
        }

        val colors = palette(ctx)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(snapshot.startedAt))
        val root = shell(
            ctx,
            "Streaming diagnostics",
            "Latest lookup · $time · ${snapshot.durationMs} ms until lookup completion. First-playable timing ends when a link is handed to CloudStream, not when the video displays its first frame. No stream URLs or addon access keys are stored.",
        )
        val summary = vertical(ctx, dp(ctx, 12)).apply {
            background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
            listOf(
                "Raw streams returned: ${snapshot.rawStreams}",
                "Addon subtitle tracks: ${snapshot.addonSubtitles}",
                "Playable candidates before filters: ${snapshot.playableCandidates}",
                "Streams kept after filters and deduplication: ${snapshot.keptAfterFilters}",
                "First stream request completed: ${snapshot.timeToFirstStreamRequestCompletedMs?.let { "$it ms" } ?: "—"}",
                "First playable result parsed: ${snapshot.timeToFirstPlayableResultMs?.let { "$it ms" } ?: "—"}",
                "First playable link sent to CloudStream: ${snapshot.timeToFirstPlayableLinkMs?.let { "$it ms" } ?: "—"}",
                "First batch links: ${snapshot.firstBatchLinks}",
                "Stream addons completed: ${snapshot.completedStreamAddons}/${snapshot.expectedStreamAddons}",
                "All stream addons completed: ${snapshot.timeToAllStreamAddonsCompletedMs?.let { "$it ms" } ?: "—"}",
                "All playable links emitted: ${snapshot.timeToAllPlayableLinksEmittedMs?.let { "$it ms" } ?: "—"}",
                "Addon requests recorded: ${snapshot.requests.size}",
                "Parsing work (parallel totals): ${snapshot.parseWorkMs} ms",
                "Filtering, merging & sorting work: ${snapshot.pipelineMs} ms",
                "Link callback emission work: ${snapshot.emissionMs} ms",
                "External extractor resolution: ${snapshot.extractorResolutionMs} ms",
                "Additional subtitle wait: ${snapshot.subtitleWaitMs} ms",
                "Subtitle addons completed: ${snapshot.timeToSubtitleCompletionMs?.let { "$it ms after lookup start" } ?: "—"}",
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
                        addView(text(ctx, "$status · ${item.elapsedMs} ms · ${item.queueWaitMs} ms queued", 12f, colors.secondary).apply {
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
            appendLine("First stream request completed: ${snapshot.timeToFirstStreamRequestCompletedMs?.let { "$it ms" } ?: "not recorded"}")
            appendLine("First playable result parsed: ${snapshot.timeToFirstPlayableResultMs?.let { "$it ms" } ?: "not recorded"}")
            appendLine("First playable link handed to CloudStream: ${snapshot.timeToFirstPlayableLinkMs?.let { "$it ms" } ?: "not recorded"}")
            appendLine("First batch links: ${snapshot.firstBatchLinks}")
            appendLine("Stream addons completed: ${snapshot.completedStreamAddons}/${snapshot.expectedStreamAddons}")
            appendLine("All stream addons completed: ${snapshot.timeToAllStreamAddonsCompletedMs?.let { "$it ms" } ?: "not recorded"}")
            appendLine("All playable links emitted: ${snapshot.timeToAllPlayableLinksEmittedMs?.let { "$it ms" } ?: "not recorded"}")
            appendLine("Links handed to CloudStream: ${snapshot.emittedLinks}")
            appendLine("Parsing work (parallel totals): ${snapshot.parseWorkMs} ms")
            appendLine("Filter/dedup/sort work: ${snapshot.pipelineMs} ms")
            appendLine("Callback emission work: ${snapshot.emissionMs} ms")
            appendLine("External extractor resolution: ${snapshot.extractorResolutionMs} ms")
            appendLine("Additional subtitle wait: ${snapshot.subtitleWaitMs} ms")
            appendLine("Subtitle completion after lookup start: ${snapshot.timeToSubtitleCompletionMs?.let { "$it ms" } ?: "not recorded"}")
            snapshot.requests.forEach { item ->
                val outcome = if (item.resultCount > 0) "${item.status} (${item.resultCount} items)" else item.status
                appendLine("${item.addonName} | ${item.resource} | $outcome | ${item.elapsedMs} ms | queue wait ${item.queueWaitMs} ms")
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
