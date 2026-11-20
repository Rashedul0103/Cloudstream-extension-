package com.multistremio

import android.content.Context

internal object DangerZoneSettingsScreen {
    fun show(ctx: Context) {
        val items = listOf(
            SettingItem(
                title = "Reset filters and sorting",
                summary = "Restore default filter and sort settings. Addons and homepage rows stay saved.",
                type = SettingItemType.Navigate,
                danger = true,
                section = "Narrow resets",
                onClick = {
                    confirm(ctx, "Reset filters and sorting?", "Your addons, profiles and homepage rows are kept.", onCancel = { show(ctx) }) {
                        if (AutomaticBackupStore(ctx).create("Before filters and sorting reset", Config.snapshot()) == null) {
                            toast(ctx, "Reset cancelled because the automatic backup could not be saved.")
                            show(ctx)
                            return@confirm
                        }
                        Config.update {
                            filter = FilterSettings()
                            sort = SortSettings()
                            filterPreset = "BALANCED"
                        }
                        toast(ctx, "Filters and sorting reset.")
                        show(ctx)
                    }
                },
            ),
            SettingItem(
                title = "Reset homepage row order",
                summary = "Return rows to their default order. Visibility and names are kept.",
                type = SettingItemType.Navigate,
                danger = true,
                section = "Narrow resets",
                onClick = {
                    confirm(ctx, "Reset homepage row order?", "Row visibility and custom names remain saved.", onCancel = { show(ctx) }) {
                        if (AutomaticBackupStore(ctx).create("Before homepage row order reset", Config.snapshot()) == null) {
                            toast(ctx, "Reset cancelled because the automatic backup could not be saved.")
                            show(ctx)
                            return@confirm
                        }
                        Config.update { catalogOrder.clear() }
                        HomeRefreshNotice.mark()
                        toast(ctx, "Homepage row order reset.")
                        show(ctx)
                    }
                },
            ),
            SettingItem(
                title = "Remove ALL addons and settings",
                summary = "Clear every addon, homepage row, filter, profile, preference and custom link design.",
                type = SettingItemType.Value,
                valueLabel = "Delete",
                section = "Danger zone",
                danger = true,
                searchKeywords = listOf("remove", "delete", "clear", "all", "factory reset", "danger"),
                onClick = {
                    prompt(
                        ctx,
                        "Confirm full reset",
                        "An automatic backup must be saved first. Type RESET to remove everything from this device.",
                        "",
                        false,
                        onCancel = { show(ctx) },
                    ) { confirmation ->
                        if (confirmation.trim() != "RESET") {
                            toast(ctx, "Reset cancelled. Type RESET exactly to continue.")
                            show(ctx)
                        } else {
                            val backup = AutomaticBackupStore(ctx).create("Before full reset", Config.snapshot())
                            if (backup == null) {
                                toast(ctx, "Reset cancelled because the automatic backup could not be saved.")
                                show(ctx)
                                return@prompt
                            }
                            Config.replace(Settings(seeded = true))
                            HomeRefreshNotice.mark()
                            toast(ctx, "MultiStremio was reset. Use Backup and restore to recover settings if needed.")
                            MainMenuSettingsScreen.show(ctx)
                        }
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
