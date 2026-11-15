package com.multistremio

import android.content.Context

internal object DangerZoneSettingsScreen {
    fun show(ctx: Context) {
        val items = listOf(
            SettingItem(
                title = "Remove ALL addons and settings",
                summary = "Permanently clear every addon, homepage row, filter, preference and custom link design from this device.",
                type = SettingItemType.Value,
                valueLabel = "Delete",
                section = "Danger zone",
                danger = true,
                searchKeywords = listOf("remove", "delete", "clear", "all", "factory reset", "danger"),
                onClick = {
                    confirm(
                        ctx,
                        "Remove everything?",
                        "This deletes all MultiStremio addons and settings from this device. It cannot be undone unless you made a backup. Cinemeta will not be added again automatically.",
                        onCancel = { show(ctx) },
                    ) {
                        Config.replace(Settings(seeded = true))
                        HomeRefreshNotice.mark()
                        MainMenuSettingsScreen.show(ctx)
                    }
                },
            ),
        )
        settingMenu(
            ctx,
            "Danger zone",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Destructive actions are kept separate from backup and restore.",
        )
    }
}
