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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore

internal object DiagnosticsSettingsScreen {
    private data class TitleAddonResult(
        val addonName: String,
        val elapsedMs: Long,
        val streamCount: Int?,
        val playableCount: Int?,
        val error: String? = null,
    )

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
                "Subtitle requests started: ${snapshot.timeToSubtitleRequestsStartedMs?.let { "$it ms after lookup start" } ?: "—"}",
                "Subtitle addons completed: ${snapshot.timeToSubtitleCompletionMs?.let { "$it ms after lookup start" } ?: "—"}",
            ).forEach { addView(text(ctx, it, 13f, colors.primary).apply {
                setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
            }) }
        }
        val content = vertical(ctx, dp(ctx, 10)).apply { addView(summary) }
        val filterImpact = StreamDiagnostics.filterImpact(Config.snapshot().filter)
        if (filterImpact != null) {
            val reasonRows = filterImpact.reasons.entries.sortedByDescending { it.value }
            content.addView(text(ctx, "Why links are hidden", 13f, colors.primary, true).apply {
                setPadding(dp(ctx, 2), dp(ctx, 8), 0, 0)
            })
            val reasonsCard = vertical(ctx, dp(ctx, 7)).apply {
                background = rounded(ctx, colors.background, colors.outline)
                if (reasonRows.isEmpty()) {
                    addView(text(ctx, "No playable candidates are hidden by the current filter rules.", 12f, colors.secondary))
                } else {
                    reasonRows.forEach { (reason, count) ->
                        addView(text(ctx, "$reason · $count${if (filterImpact.sampled) " (sample)" else ""}", 12f, colors.primary))
                    }
                }
                if (filterImpact.sampled) {
                    addView(text(ctx, "Estimated from ${filterImpact.sampledCandidates} of ${filterImpact.totalCandidates} candidates.", 11f, colors.secondary).apply {
                        setPadding(0, dp(ctx, 3), 0, 0)
                    })
                }
            }
            content.addView(reasonsCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val list = vertical(ctx)
        if (snapshot.requests.isEmpty()) {
            list.addView(text(ctx, "No addon request details were recorded.", 13f, colors.secondary))
        } else {
            val slowThresholdMs = 3_000L
            val maxElapsedMs = snapshot.requests.maxOfOrNull { it.elapsedMs }?.coerceAtLeast(1L) ?: 1L
            snapshot.requests.sortedWith(compareBy<StreamRequestDiagnostic> { it.status == "OK" }.thenByDescending { it.elapsedMs })
                .forEach { item ->
                    val status = item.displayStatus()
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
                        addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 1_000
                            progress = ((item.elapsedMs.coerceAtMost(maxElapsedMs) * 1_000L) / maxElapsedMs).toInt()
                            progressTintList = ColorStateList.valueOf(if (item.elapsedMs >= slowThresholdMs) colors.danger else colors.accent)
                            progressBackgroundTintList = ColorStateList.valueOf(withAlpha(colors.secondary, 50))
                            contentDescription = "Response time bar, ${item.elapsedMs} milliseconds"
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6)).apply {
                            topMargin = dp(ctx, 7)
                        })
                    }
                    list.addView(card, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 7) })
                }
        }
        content.addView(valueRow(
            ctx, colors, "Test a title", "Query enabled stream addons and compare their responses", "Run",
        ) { testTitle(ctx) })
        content.addView(text(ctx, "Addon request timings", 13f, colors.primary, true).apply {
            setPadding(0, dp(ctx, 8), 0, 0)
        })
        content.addView(list)
        val scrollHeight = minOf(
            dp(ctx, 440),
            (ctx.resources.displayMetrics.heightPixels - dp(ctx, 190)).coerceAtLeast(dp(ctx, 180)),
        )
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            scrollHeight,
        ).apply { topMargin = dp(ctx, 2) })
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
            appendLine("Subtitle requests started after lookup start: ${snapshot.timeToSubtitleRequestsStartedMs?.let { "$it ms" } ?: "not recorded"}")
            appendLine("Subtitle completion after lookup start: ${snapshot.timeToSubtitleCompletionMs?.let { "$it ms" } ?: "not recorded"}")
            snapshot.requests.forEach { item ->
                appendLine("${item.addonName} | ${item.resource} | ${item.displayStatus()} | ${item.elapsedMs} ms | queue wait ${item.queueWaitMs} ms")
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

    private fun testTitle(ctx: Context) {
        val settings = Config.snapshot()
        val addons = settings.addons.filter { addon ->
            addon.enabled && addon.useStreams && Config.manifestOf(addon)?.supports("stream", "movie", "tt1234567") == true
        }
        if (addons.isEmpty()) {
            toast(ctx, "No enabled addon supports movie streams.")
            return
        }
        prompt(ctx, "Test a title", "Enter an IMDb ID for a movie, such as tt1234567.", "", false,
            onCancel = { streamDiagnosticsMenu(ctx) }) { raw ->
            val id = raw.trim()
            if (!id.matches(Regex("tt[0-9]{5,12}"))) {
                toast(ctx, "Enter a valid IMDb ID beginning with tt.")
                streamDiagnosticsMenu(ctx)
                return@prompt
            }
            var hidden = false
            val progressDialog = progress(ctx, "Testing title", "Requesting streams from ${addons.size} enabled addon(s).") {
                hidden = true
            }
            thread(start = true, name = "multistremio-diagnostics-title-test") {
                val results = runBlocking {
                    addons.boundedConcurrentMap(Semaphore(settings.maxParallelAddonRequests)) { addon ->
                        val started = System.nanoTime()
                        val outcome = runCatching {
                            StremioClient.streams(
                                addon, "movie", id,
                                timeoutSec = addon.timeoutSec ?: settings.timeoutSec,
                                retryOnce = settings.retryFailedAddonOnce,
                            )
                        }
                        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                        outcome.fold(
                            onSuccess = { streams -> TitleAddonResult(
                                StreamDiagnostics.safeAddonName(addon.name), elapsed, streams.size,
                                streams.count { !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank() },
                            ) },
                            onFailure = { error -> TitleAddonResult(
                                StreamDiagnostics.safeAddonName(addon.name), elapsed, null, null,
                                error.javaClass.simpleName.take(80),
                            ) },
                        )
                    }
                }
                settingsMainHandler.post {
                    progressDialog.dismiss()
                    if (hidden) {
                        toast(ctx, "Title test finished. Reopen Diagnostics to run it again.")
                    } else showTitleTestResults(ctx, id, results)
                }
            }
        }
    }

    private fun showTitleTestResults(ctx: Context, id: String, results: List<TitleAddonResult>) {
        val colors = palette(ctx)
        val root = shell(ctx, "Title test", "$id · ${results.size} enabled addon(s) checked")
        val list = vertical(ctx, dp(ctx, 8))
        results.forEach { result ->
            val successful = result.error == null
            val card = vertical(ctx, dp(ctx, 10)).apply {
                background = rounded(ctx, colors.background, colors.outline)
                addView(text(ctx, result.addonName, 13f, colors.primary, true))
                addView(text(
                    ctx,
                    result.error?.let { "Request error: $it · ${result.elapsedMs} ms" }
                        ?: "${result.streamCount} stream response(s) · ${result.playableCount} playable · ${result.elapsedMs} ms",
                    12f,
                    if (successful && result.playableCount.orZero() > 0) colors.accent else colors.secondary,
                ).apply { setPadding(0, dp(ctx, 3), 0, 0) })
            }
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val scroll = ScrollView(ctx).apply {
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(ctx, 400), dp(ctx, maxOf(140, results.size * 74))),
        ))
        val report = buildString {
            appendLine("MultiStremio title test · $id")
            results.forEach { result ->
                appendLine("${result.addonName} | ${result.error ?: "${result.streamCount} streams, ${result.playableCount} playable"} | ${result.elapsedMs} ms")
            }
        }
        addFooter(ctx, root, "Back", { streamDiagnosticsMenu(ctx) }, "Copy report", {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MultiStremio title test", report))
            toast(ctx, "Title test report copied. It contains no addon URLs.")
        })
        SettingsDialogHost.show(ctx, "diagnostics:title-results", root) { streamDiagnosticsMenu(ctx) }
    }

    private fun Int?.orZero(): Int = this ?: 0
}
