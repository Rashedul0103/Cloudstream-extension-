package com.multistremio

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiStremioPlugin : Plugin() {
    override fun load(context: Context) {
        Config.init(context)
        registerMainAPI(MultiStremio())
        // The gear icon next to the plugin in Settings > Extensions opens this.
        openSettings = { ctx -> SettingsUi.show(ctx) }
    }
}
