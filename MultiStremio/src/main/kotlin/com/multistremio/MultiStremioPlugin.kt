package com.multistremio

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiStremioPlugin : Plugin() {
    override fun load(context: Context) {
        Config.init(context)
        registerMainAPI(MultiStremio())
        OtherSearchProviders.attach(Config.snapshot()) { provider -> registerMainAPI(provider) }
        Config.setOnSettingsChanged { OtherSearchProviders.refresh() }
        // The gear icon next to the plugin in Settings > Extensions opens this.
        openSettings = { ctx -> SettingsUi.show(ctx) }
    }

    override fun beforeUnload() {
        Config.setOnSettingsChanged(null)
        OtherSearchProviders.detach()
    }
}
