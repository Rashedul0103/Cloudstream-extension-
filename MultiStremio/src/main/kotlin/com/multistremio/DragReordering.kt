package com.multistremio

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Reorders rows only on ACTION_UP. While the finger moves, the real row is translated under the
 * finger and the prospective insertion row is outlined; siblings keep their original positions.
 * This avoids the confusing live reshuffling that made it difficult to see what was being moved.
 */
internal fun installDragReordering(
    ctx: Context,
    handle: View,
    item: View,
    list: LinearLayout,
    scroll: ScrollView,
    colors: Palette,
    onDrop: () -> Unit = {},
    onMoved: () -> Unit,
) {
    val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    var tracking = false
    var dragging = false
    var downRawY = 0f
    var lastPointerY = 0f
    var startScrollY = 0
    var destination = -1
    var edgeDirection = 0
    var edgeScroller: Runnable? = null
    var highlighted: View? = null
    var highlightedBackground: android.graphics.drawable.Drawable? = null
    val originalBackground = item.background
    val originalElevation = item.elevation

    fun otherRows(): List<View> =
        (0 until list.childCount).map { list.getChildAt(it) }
            .filter { it !== item && it.visibility == View.VISIBLE }

    fun updateInsertionMarker(pointerY: Float) {
        val rows = otherRows()
        val centers = rows.map { row ->
            val position = IntArray(2)
            row.getLocationOnScreen(position)
            position[1] + row.height / 2f
        }
        destination = DragReorderMath.insertionIndex(centers, pointerY)
        val nextTarget = rows.getOrNull(destination) ?: rows.lastOrNull()
        if (nextTarget !== highlighted) {
            highlighted?.background = highlightedBackground
            highlighted = nextTarget
            highlightedBackground = nextTarget?.background
            nextTarget?.background = rounded(ctx, withAlpha(colors.accent, 20), colors.accent)
        }
    }

    fun updateDraggedPosition() {
        // Compensate for scroll movement so the translated row stays under the finger.
        val scrollDelta = scroll.scrollY - startScrollY
        item.translationY = lastPointerY - downRawY + scrollDelta
        updateInsertionMarker(lastPointerY)
    }

    fun restoreMarker() {
        highlighted?.background = highlightedBackground
        highlighted = null
        highlightedBackground = null
    }

    fun finishDrag(commit: Boolean) {
        tracking = false
        edgeScroller?.let(handle::removeCallbacks)
        if (dragging) {
            restoreMarker()
            if (commit) {
                val slot = destination.coerceIn(0, otherRows().size)
                list.removeView(item)
                list.addView(item, slot.coerceIn(0, list.childCount))
                onMoved()
                onDrop()
            }
            item.translationY = 0f
            item.alpha = 1f
            item.elevation = originalElevation
            if (!commit) item.background = originalBackground
        }
        dragging = false
        destination = -1
        scroll.requestDisallowInterceptTouchEvent(false)
    }

    edgeScroller = object : Runnable {
        override fun run() {
            if (!tracking || !dragging || edgeDirection == 0) return
            val before = scroll.scrollY
            scroll.scrollBy(0, edgeDirection * dp(ctx, 8))
            if (scroll.scrollY != before) {
                updateDraggedPosition()
                handle.postDelayed(this, 16L)
            } else {
                edgeDirection = 0
            }
        }
    }

    // The direction is updated on every move and reused while the list auto-scrolls.
    handle.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                downRawY = event.rawY
                lastPointerY = event.rawY
                startScrollY = scroll.scrollY
                destination = -1
                edgeDirection = 0
                scroll.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    lastPointerY = event.rawY
                    if (!dragging && abs(event.rawY - downRawY) >= touchSlop && item.height > 0) {
                        dragging = true
                        item.alpha = 0.9f
                        item.elevation = dp(ctx, 8).toFloat()
                        handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    if (dragging) {
                        updateDraggedPosition()
                        val position = IntArray(2)
                        scroll.getLocationOnScreen(position)
                        val edge = dp(ctx, 48)
                        edgeDirection = when {
                            event.rawY < position[1] + edge -> -1
                            event.rawY > position[1] + scroll.height - edge -> 1
                            else -> 0
                        }
                        edgeScroller?.let(handle::removeCallbacks)
                        if (edgeDirection != 0) edgeScroller?.let { handle.postDelayed(it, 16L) }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (tracking && dragging) {
                    lastPointerY = event.rawY
                    updateDraggedPosition()
                }
                finishDrag(commit = true)
                true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                finishDrag(commit = false)
                true
            }
            else -> true
        }
    }
}
