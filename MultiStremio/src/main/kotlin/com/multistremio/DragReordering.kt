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

    /**
     * Shared drag controller for both reorder screens.
     *
     * The grip is a dedicated drag target: vertical movement starts reordering as soon as the
     * finger passes touch slop. No long-press timer is involved, so the gesture cannot be cancelled
     * by the ScrollView before the reorder controller starts. Swiping the row body still scrolls.
     */
    private fun installDragReordering(
        ctx: Context,
        handle: View,
        item: View,
        list: LinearLayout,
        scroll: ScrollView,
        colors: Palette,
        onMoved: () -> Unit,
    ) {
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        var tracking = false
        var dragging = false
        var downRawY = 0f
        var lastPointerY = 0f
        var edgeDirection = 0

        fun moveItem(pointerY: Float, updateEdgeScroll: Boolean = true) {
            val centers = (0 until list.childCount).mapNotNull { index ->
                val child = list.getChildAt(index)
                if (child === item) null else {
                    val position = IntArray(2)
                    child.getLocationOnScreen(position)
                    position[1] + child.height / 2f
                }
            }
            // The insertion slot is measured among the other rows, after excluding the dragged row.
            val destination = DragReorderMath.insertionIndex(centers, pointerY)
            val current = list.indexOfChild(item)
            if (destination != current) {
                list.removeView(item)
                list.addView(item, destination.coerceIn(0, list.childCount))
                onMoved()
            }

            if (updateEdgeScroll) {
                val scrollPosition = IntArray(2)
                scroll.getLocationOnScreen(scrollPosition)
                val edge = dp(ctx, 48)
                edgeDirection = when {
                    pointerY < scrollPosition[1] + edge -> -1
                    pointerY > scrollPosition[1] + scroll.height - edge -> 1
                    else -> 0
                }
            }
        }

        val edgeScroller = object : Runnable {
            override fun run() {
                if (!tracking || !dragging || edgeDirection == 0) return
                val before = scroll.scrollY
                scroll.scrollBy(0, edgeDirection * dp(ctx, 8))
                if (scroll.scrollY != before) {
                    // Re-evaluate the insertion point while the list moves under a stationary finger.
                    moveItem(lastPointerY, updateEdgeScroll = false)
                    handle.postDelayed(this, 16L)
                } else {
                    edgeDirection = 0
                }
            }
        }

        fun finishDrag() {
            tracking = false
            edgeDirection = 0
            handle.removeCallbacks(edgeScroller)
            if (dragging) {
                dragging = false
                item.background = rounded(ctx, colors.background, colors.outline)
                item.elevation = 0f
            }
            scroll.requestDisallowInterceptTouchEvent(false)
        }

        fun updateAutoScroll() {
            handle.removeCallbacks(edgeScroller)
            if (tracking && dragging && edgeDirection != 0) {
                handle.postDelayed(edgeScroller, 16L)
            }
        }

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    dragging = false
                    downRawY = event.rawY
                    lastPointerY = event.rawY
                    edgeDirection = 0
                    // Only the grip is a drag target. Keep ScrollView from stealing its gesture.
                    scroll.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        lastPointerY = event.rawY
                        if (!dragging && abs(event.rawY - downRawY) >= touchSlop) {
                            dragging = true
                            item.background = rounded(ctx, withAlpha(colors.accent, 28), colors.accent)
                            item.elevation = dp(ctx, 6).toFloat()
                            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (dragging) {
                            moveItem(lastPointerY)
                            updateAutoScroll()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    finishDrag()
                    true
                }
                else -> true
            }
        }
    }
