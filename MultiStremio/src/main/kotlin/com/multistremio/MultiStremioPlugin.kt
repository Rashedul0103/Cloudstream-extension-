package com.multistremio

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiStremioPlugin : Plugin() {
    override fun load(context: Context) {
        Config.init(context)
        registerMainAPI(MultiStremio())
        // Install the listener before attaching providers. A manifest refresh may finish during
        // plugin startup; attach() then reads the latest snapshot without losing that update.
        Config.setOnSettingsChanged { OtherSearchProviders.refreshDebounced() }
        OtherSearchProviders.attach { provider -> registerMainAPI(provider) }
        // The gear icon next to the plugin in Settings > Extensions opens this.
        openSettings = { ctx -> SettingsUi.show(ctx) }
    }

    override fun beforeUnload() {
        Config.setOnSettingsChanged(null)
        OtherSearchProviders.detach()
    }
}
