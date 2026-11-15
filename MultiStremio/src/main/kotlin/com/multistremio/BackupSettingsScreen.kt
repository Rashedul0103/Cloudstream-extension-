package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal object BackupSettingsScreen {
    fun show(ctx: Context) = backupMenu(ctx)

    private fun backupMenu(ctx: Context) {
        fun copyBackup(includeAddonUrls: Boolean) {
            val payload = runCatching {
                SettingsBackupManager.export(Config.snapshot(), includeAddonUrls)
            }.getOrElse {
                toast(ctx, "Could not create the backup")
                return
            }
            runCatching {
                val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("MultiStremio backup", payload))
            }.onSuccess {
                toast(
                    ctx,
                    if (includeAddonUrls) {
                        "Full backup copied. The clipboard contains private addon URLs and may contain access keys."
                    } else {
                        "Preferences-only backup copied. Addon sources and imported extension rows were excluded."
                    },
                )
                settingsMainHandler.post { backupMenu(ctx) }
            }.onFailure {
                toast(ctx, "Could not copy the backup to the clipboard")
            }
        }

        val again = { backupMenu(ctx) }
        val items = listOf(
            SettingItem(
                "Copy preferences-only backup",
                "Excludes source URLs and imported extension rows",
                SettingItemType.Value,
                onClick = {
                    confirm(
                        ctx,
                        "Copy preferences-only backup?",
                        "This export excludes all Stremio addon URLs, manifests and imported homepage-row references. " +
                            "Restoring it will not restore your sources; you will need to add them again.",
                        onCancel = again,
                    ) { copyBackup(includeAddonUrls = false) }
                },
                searchKeywords = listOf("copy", "backup", "preferences", "private", "no URLs"),
            ),
            SettingItem(
                "Copy full backup",
                "Includes addon URLs and cached manifests; may contain access keys",
                SettingItemType.Value,
                onClick = {
                    confirm(
                        ctx,
                        "Copy full backup?",
                        "This includes addon endpoints and cached manifests. Addon URLs may contain debrid keys or other " +
                            "credentials. The clipboard is not encrypted. Do not share this backup publicly.",
                        onCancel = again,
                    ) { copyBackup(includeAddonUrls = true) }
                },
                searchKeywords = listOf("full", "backup", "source", "URLs", "access keys"),
            ),
            SettingItem(
                "Restore from backup",
                "Paste a MultiStremio backup or older settings JSON",
                SettingItemType.Value,
                onClick = {
                    prompt(
                        ctx,
                        "Restore backup",
                        "Paste a MultiStremio backup or settings JSON copied from an older version. This replaces the current configuration.",
                        "",
                        false,
                        onCancel = again,
                    ) { pasted ->
                        val backup = SettingsBackupManager.parse(pasted)
                        val restored = backup?.settings
                        if (backup == null || restored == null) {
                            toast(ctx, "That is not a supported MultiStremio backup")
                            again()
                        } else {
                            val warning = if (backup.includesAddonUrls) {
                                "This backup contains addon source URLs and/or imported-extension references. They may expose access keys or private repository addresses. It will replace all current settings."
                            } else {
                                "This preferences-only backup contains no addon sources or imported-extension references. Restoring it will remove your current addons and imported homepage rows."
                            }
                            confirm(ctx, "Restore this backup?", warning, onCancel = again) {
                                restored.seeded = true
                                Config.replace(restored)
                                toast(ctx, "Backup restored. Refresh the home page.")
                                MainMenuSettingsScreen.show(ctx)
                            }
                        }
                    }
                },
                searchKeywords = listOf("restore", "import", "paste", "JSON"),
            ),
            SettingItem(
                "Reset filters & sorting",
                "Addons and homepage rows are kept",
                SettingItemType.Value,
                onClick = {
                    confirm(ctx, "Reset filters & sorting?", "Addons are kept.", onCancel = again) {
                        Config.update { filter = FilterSettings(); sort = SortSettings() }
                        toast(ctx, "Filters and sorting reset.")
                        again()
                    }
                },
                searchKeywords = listOf("reset", "filters", "sorting", "defaults"),
            ),
            SettingItem(
                "Remove ALL addons and settings",
                "Delete every saved source and preference",
                SettingItemType.Value,
                onClick = {
                    confirm(ctx, "Remove everything?", "All addons and settings are deleted (Cinemeta is not re-added).", onCancel = again) {
                        Config.replace(Settings(seeded = true))
                        toast(ctx, "All MultiStremio settings cleared.")
                        MainMenuSettingsScreen.show(ctx)
                    }
                },
                searchKeywords = listOf("remove", "delete", "clear", "all", "factory reset"),
            ),
        )
        settingMenu(
            ctx,
            "Backup & maintenance",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Choose a privacy level before exporting. Full backups may contain private access keys in addon URLs.",
        )
    }

}
