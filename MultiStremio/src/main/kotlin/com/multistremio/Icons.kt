package com.multistremio

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/**
 * Icon path data on a 24x24 grid, drawn as 2-unit strokes with round caps. Stored as SVG path
 * strings so the plugin needs no resource files (the build has `requiresResources = false`).
 * Every settings row keeps its text label, so an icon that fails to parse only costs decoration.
 */
internal object SettingsIcons {
    const val CHEVRON_RIGHT = "M9 6l6 6l-6 6"
    const val ARROW_LEFT = "M5 12h14 M5 12l6 6 M5 12l6 -6"
    const val CHECK = "M5 12l5 5l10 -10"
    const val PLUS = "M12 5v14 M5 12h14"
    const val CLOSE = "M18 6l-12 12 M6 6l12 12"
    const val SEARCH = "M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0 M21 21l-6 -6"
    const val TRASH = "M4 7h16 M10 11v6 M14 11v6 M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12 M9 7v-3h6v3"
    const val GRIP = "M9 6h.01 M9 12h.01 M9 18h.01 M15 6h.01 M15 12h.01 M15 18h.01"
    const val ADDONS = "M8 8h-2a2 2 0 0 0-2 2v2a2 2 0 0 1 0 4v2a2 2 0 0 0 2 2h2a2 2 0 0 1 4 0h2a2 2 0 0 0 2-2v-2a2 2 0 0 1 0-4v-2a2 2 0 0 0-2-2h-2a2 2 0 0 1-4 0z"
    const val ROWS = "M4 5h16 M4 12h16 M4 19h16 M8 5v0 M8 12v0 M8 19v0"
    const val FILTER = "M4 6h16 M7 12h10 M10 18h4 M8 4v4 M16 10v4 M12 16v4"
    const val SORT = "M7 4v16 M4 17l3 3 3-3 M17 20V4 M14 7l3-3 3 3"
    const val CAPTION = "M3 6h18v12H3z M7 10h3 M14 10h3 M7 14h3 M14 14h3"
    const val PROFILE = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M4 20a8 8 0 0 1 16 0"
    const val BOLT = "M13 2 4 14h7l-1 8 10-13h-7z"
    const val BACKUP = "M12 16V4 M7 9l5-5 5 5 M4 15v5h16v-5"
    const val DIAGNOSTICS = "M3 12h4l3-8 4 16 3-8h4"
    const val REFRESH = "M20 7v5h-5 M4 17v-5h5 M5.5 9a7 7 0 0 1 12-2L20 12 M4 12l2.5 5a7 7 0 0 0 12-2"
    const val COPY = "M8 8h12v13H8z M4 16V3h12"
    const val SHARE = "M12 16V3 M7 8l5-5 5 5 M4 13v7h16v-7"
    const val HISTORY = "M3 12a9 9 0 1 0 3-7 M3 4v5h5 M12 7v5l4 2"
    const val PLAY = "M7 4l13 8-13 8z"
    const val EDIT = "M4 20h4l12-12-4-4L4 16z M13 7l4 4"
    const val HOME = "M3 11 12 4l9 7 M5 10v10h14V10 M9 20v-6h6v6"
    const val CLIPBOARD = "M8 4h8v4H8z M6 6H4v15h16V6h-2 M8 12h8 M8 16h8"
    const val DOWNLOAD = "M12 3v12 M7 10l5 5 5-5 M4 19h16"
    const val UPLOAD = "M12 15V3 M7 8l5-5 5 5 M4 15v5h16v-5"
    const val HELP = "M9 9a3 3 0 1 1 5 2c-1.5 1-2 1.5-2 3 M12 18v.01 M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20"

    val ALL: Map<String, String> = linkedMapOf(
        "chevron-right" to CHEVRON_RIGHT,
        "arrow-left" to ARROW_LEFT,
        "check" to CHECK,
        "plus" to PLUS,
        "close" to CLOSE,
        "search" to SEARCH,
        "trash" to TRASH,
        "grip" to GRIP,
        "addons" to ADDONS,
        "rows" to ROWS,
        "filter" to FILTER,
        "sort" to SORT,
        "caption" to CAPTION,
        "profile" to PROFILE,
        "bolt" to BOLT,
        "backup" to BACKUP,
        "diagnostics" to DIAGNOSTICS,
        "refresh" to REFRESH,
        "copy" to COPY,
        "share" to SHARE,
        "history" to HISTORY,
        "play" to PLAY,
        "edit" to EDIT,
        "home" to HOME,
        "clipboard" to CLIPBOARD,
        "download" to DOWNLOAD,
        "upload" to UPLOAD,
        "help" to HELP,
    )
}

/**
 * Draws one [SettingsIcons] path, using hand-drawn basics if AndroidX path parsing is unavailable.
 * A bad icon never crashes a settings screen, and its text label remains available.
 */
internal class IconDrawable(pathData: String, color: Int) : Drawable() {
    private val path: Path? = runCatching { PathParser.createPathFromPathData(pathData) }
        .getOrNull()
        ?: fallbackPath(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val icon = path ?: return
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val scale = size / VIEWPORT
        val save = canvas.save()
        canvas.translate(bounds.left + (bounds.width() - size) / 2f, bounds.top + (bounds.height() - size) / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(icon, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Required by Drawable; the alpha is always reported as translucent.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Basic controls stay recognizable on host apps that do not expose AndroidX PathParser. */
    private fun fallbackPath(data: String): Path? = Path().apply {
        when (data) {
            SettingsIcons.CHEVRON_RIGHT -> {
                moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
            }
            SettingsIcons.ARROW_LEFT -> {
                moveTo(5f, 12f); lineTo(19f, 12f)
                moveTo(5f, 12f); lineTo(11f, 18f)
                moveTo(5f, 12f); lineTo(11f, 6f)
            }
            SettingsIcons.CHECK -> {
                moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f)
            }
            SettingsIcons.PLUS -> {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
            SettingsIcons.CLOSE -> {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
            SettingsIcons.SEARCH -> {
                addCircle(10f, 10f, 7f, Path.Direction.CW)
                moveTo(15f, 15f); lineTo(21f, 21f)
            }
            SettingsIcons.TRASH -> {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(6f, 7f); lineTo(7f, 19f); lineTo(17f, 19f); lineTo(18f, 7f)
                moveTo(10f, 11f); lineTo(10f, 16f)
                moveTo(14f, 11f); lineTo(14f, 16f)
                moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
            }
            SettingsIcons.GRIP -> {
                listOf(6f, 12f, 18f).forEach { y ->
                    addCircle(9f, y, 0.7f, Path.Direction.CW)
                    addCircle(15f, y, 0.7f, Path.Direction.CW)
                }
            }
            SettingsIcons.ROWS -> {
                listOf(5f, 12f, 19f).forEach { y ->
                    moveTo(4f, y); lineTo(20f, y)
                }
            }
            SettingsIcons.FILTER -> {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(7f, 12f); lineTo(17f, 12f)
                moveTo(10f, 18f); lineTo(14f, 18f)
                moveTo(8f, 4f); lineTo(8f, 8f)
                moveTo(16f, 10f); lineTo(16f, 14f)
                moveTo(12f, 16f); lineTo(12f, 20f)
            }
            else -> return null
        }
    }

    private companion object {
        const val VIEWPORT = 24f
    }
}
