package com.multistremio

/** Pure option mapping for the Behavior page, kept apart from views so it can be unit-tested. */
internal object BehaviorOptions {
    val TIMEOUT_LABELS = listOf("Fast", "Normal", "Patient", "Custom")
    private val TIMEOUT_PRESET_SECONDS = listOf(10, 30, 60)
    const val CUSTOM_TIMEOUT_INDEX = 3

    /** Index of the segment for [seconds]; any non-preset value is "Custom". */
    fun timeoutIndex(seconds: Int): Int {
        val preset = TIMEOUT_PRESET_SECONDS.indexOf(seconds)
        return if (preset >= 0) preset else CUSTOM_TIMEOUT_INDEX
    }

    /** Seconds for a preset segment, or null for "Custom" (the user must enter a value). */
    fun timeoutSeconds(index: Int): Int? = TIMEOUT_PRESET_SECONDS.getOrNull(index)

    fun timeoutValueLabel(seconds: Int): String = "$seconds seconds"

    val ROW_TITLE_LABELS = listOf("Off", "Prefix", "Suffix")
    private val ROW_TITLE_MODES = listOf("OFF", "PREFIX", "SUFFIX")

    /** Index of the segment for a stored mode; unknown values fall back to "Off". */
    fun rowTitleIndex(mode: String): Int =
        ROW_TITLE_MODES.indexOf(mode.uppercase()).let { if (it >= 0) it else 0 }

    fun rowTitleMode(index: Int): String = ROW_TITLE_MODES.getOrElse(index) { "OFF" }

    fun subtitleLanguagesValue(languages: List<String>): String =
        if (languages.isEmpty()) "All" else "${languages.size} selected"
}
