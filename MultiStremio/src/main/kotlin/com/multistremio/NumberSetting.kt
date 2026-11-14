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

    private fun number(
        ctx: Context,
        title: String,
        current: Int,
        description: String = "Enter a whole number. Use 0 to turn this limit off.",
        onCancel: (() -> Unit)? = null,
        onOk: (Int) -> Unit,
    ) {
        fun ask(initial: String) {
            prompt(ctx, title, description, initial, true, onCancel) { value ->
                val parsed = value.toIntOrNull()
                if (parsed == null && value.isNotBlank()) {
                    toast(ctx, "That number is too large or invalid. Nothing was saved.")
                    ask(value)
                } else {
                    onOk((parsed ?: 0).coerceAtLeast(0))
                }
            }
        }
        ask(current.toString())
    }
