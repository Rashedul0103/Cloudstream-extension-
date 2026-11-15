package com.multistremio

import android.content.Context

/** Stable entry point used by CloudStream to open MultiStremio settings. */
object SettingsUi {
    fun show(ctx: Context) = MainMenuSettingsScreen.show(ctx)
}
