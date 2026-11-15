package com.multistremio

import android.content.Context
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType

internal fun number(
    ctx: Context,
    title: String,
    current: Int,
    description: String = "Enter a whole number. Use 0 to turn this limit off.",
    onCancel: (() -> Unit)? = null,
    onOk: (Int) -> Unit,
) {
    prompt(ctx, title, description, current.toString(), true, onCancel) { value ->
        val parsed = value.toIntOrNull()
        if (parsed == null && value.isNotBlank()) {
            toast(ctx, "That number is invalid or too large. Nothing was saved.")
            number(ctx, title, current, description, onCancel, onOk)
        } else {
            onOk((parsed ?: 0).coerceAtLeast(0))
        }
    }
}

/** Range-checked numeric input with inline feedback; invalid values are never silently clamped. */
internal fun boundedNumber(
    ctx: Context,
    title: String,
    current: Int,
    minimum: Int,
    maximum: Int,
    description: String,
    onCancel: (() -> Unit)? = null,
    onOk: (Int) -> Unit,
) {
    val colors = palette(ctx)
    val root = shell(ctx, title, description)
    val input = EditText(ctx).apply {
        setText(current.toString())
        textSize = 16f
        setTextColor(colors.primary)
        setHintTextColor(colors.secondary)
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        background = rounded(ctx, withAlpha(colors.accent, 12), colors.outline)
        selectAll()
    }
    val validation = text(ctx, "Allowed range: $minimum–$maximum.", 12f, colors.secondary)
    root.addView(input, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(ctx, 8) })
    root.addView(validation, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(ctx, 6) })

    fun validate(showError: Boolean): Int? {
        val value = input.text.toString().toIntOrNull()
        val valid = value != null && value in minimum..maximum
        validation.text = when {
            valid -> "Valid value."
            value != null -> "Value must be between $minimum and $maximum."
            showError -> "Enter a whole number from $minimum to $maximum."
            else -> "Allowed range: $minimum–$maximum."
        }
        validation.setTextColor(
            if (valid) colors.accent
            else if (showError || value != null) 0xffff5252.toInt()
            else colors.secondary
        )
        return value.takeIf { valid }
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { validate(false) }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    addFooter(ctx, root, "Save", {
        val parsed = validate(true)
        if (parsed != null) {
            val manager = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.hideSoftInputFromWindow(input.windowToken, 0)
            onOk(parsed)
        }
    }, "Cancel", {
        if (onCancel != null) onCancel() else SettingsDialogHost.back()
    })
    SettingsDialogHost.show(ctx, "bounded-number:$title", root, onCancel)
    input.requestFocus()
    input.post {
        input.selectAll()
        val manager = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
}
