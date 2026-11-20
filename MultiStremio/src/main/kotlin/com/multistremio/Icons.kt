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

    val ALL: Map<String, String> = linkedMapOf(
        "chevron-right" to CHEVRON_RIGHT,
        "arrow-left" to ARROW_LEFT,
        "check" to CHECK,
        "plus" to PLUS,
        "close" to CLOSE,
        "search" to SEARCH,
        "trash" to TRASH,
        "grip" to GRIP,
    )
}

/**
 * Draws one [SettingsIcons] path. Returns an empty drawable if the path cannot be parsed, so a
 * bad icon never crashes a settings screen.
 */
internal class IconDrawable(pathData: String, color: Int) : Drawable() {
    private val path: Path? = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull()
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

    private companion object {
        const val VIEWPORT = 24f
    }
}
