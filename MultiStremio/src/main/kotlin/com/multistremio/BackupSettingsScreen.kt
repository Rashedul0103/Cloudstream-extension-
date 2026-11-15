package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal object BackupSettingsScreen {
    fun show(ctx: Context) = backupMenu(ctx)

    private fun backupMenu(ctx: Context) {
        val again = { backupMenu(ctx) }

        fun copyBackup(includeAddonUrls: Boolean) {
            val payload = createBackup(ctx, includeAddonUrls) ?: return
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

        fun exportBackupFile(includeAddonUrls: Boolean) {
            val payload = createBackup(ctx, includeAddonUrls) ?: return
            BackupFileTransfer.exportText(ctx, payload) {
                settingsMainHandler.post { backupMenu(ctx) }
            }
        }

        val items = listOf(
            SettingItem(
                "Export preferences-only backup (.txt)",
                "Save a text file without addon URLs or imported extension rows",
                SettingItemType.Navigate,
                onClick = {
                    confirm(
                        ctx,
                        "Export preferences-only backup?",
                        "The TXT file excludes Stremio addon URLs, cached manifests and imported homepage-row references. " +
                            "Restoring it will not restore your sources; you will need to add them again.",
                        onCancel = again,
                    ) { exportBackupFile(includeAddonUrls = false) }
                },
                searchKeywords = listOf("export", "file", "txt", "text", "backup", "preferences"),
            ),
            SettingItem(
                "Export full backup (.txt)",
                "Save addon URLs and cached manifests too; the file may contain access keys",
                SettingItemType.Navigate,
                onClick = {
                    confirm(
                        ctx,
                        "Export full backup?",
                        "The TXT file includes addon endpoints and cached manifests. Addon URLs may contain debrid keys " +
                            "or other credentials. Save it somewhere private and do not share it publicly.",
                        onCancel = again,
                    ) { exportBackupFile(includeAddonUrls = true) }
                },
                searchKeywords = listOf("export", "file", "txt", "text", "full", "backup", "source", "URLs", "access keys"),
            ),
            SettingItem(
                "Copy preferences-only backup",
                "Copies a compact text backup without source URLs",
                SettingItemType.Navigate,
                onClick = {
                    confirm(
                        ctx,
                        "Copy preferences-only backup?",
                        "This excludes all Stremio addon URLs, manifests and imported homepage-row references. " +
                            "Restoring it will not restore your sources; you will need to add them again.",
                        onCancel = again,
                    ) { copyBackup(includeAddonUrls = false) }
                },
                searchKeywords = listOf("copy", "clipboard", "backup", "preferences", "private", "no URLs"),
            ),
            SettingItem(
                "Copy full backup",
                "Copies addon URLs and cached manifests too; may contain access keys",
                SettingItemType.Navigate,
                onClick = {
                    confirm(
                        ctx,
                        "Copy full backup?",
                        "This includes addon endpoints and cached manifests. Addon URLs may contain debrid keys or other " +
                            "credentials. The clipboard is not encrypted. Do not share this backup publicly.",
                        onCancel = again,
                    ) { copyBackup(includeAddonUrls = true) }
                },
                searchKeywords = listOf("copy", "clipboard", "full", "backup", "source", "URLs", "access keys"),
            ),
            SettingItem(
                "Import backup from TXT file",
                "Choose a saved MultiStremio backup or older settings JSON file",
                SettingItemType.Navigate,
                onClick = {
                    BackupFileTransfer.importText(ctx) { contents ->
                        restoreBackupText(ctx, contents, again)
                    }
                },
                searchKeywords = listOf("import", "file", "txt", "text", "restore", "backup", "JSON"),
            ),
            SettingItem(
                "Paste backup from clipboard",
                "Restore a copied MultiStremio backup or older settings JSON",
                SettingItemType.Navigate,
                onClick = {
                    prompt(
                        ctx,
                        "Restore backup from clipboard",
                        "Paste a MultiStremio backup or settings JSON copied from an older version. This replaces the current configuration.",
                        "",
                        false,
                        onCancel = again,
                    ) { pasted ->
                        restoreBackupText(ctx, pasted, again)
                    }
                },
                searchKeywords = listOf("restore", "paste", "clipboard", "import", "JSON"),
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
        )

        settingMenu(
            ctx,
            "Backup & restore",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Export a TXT file or use copy/paste. Full backups may contain private access keys in addon URLs.",
            forceSearch = true,
        )
    }

    private fun createBackup(ctx: Context, includeAddonUrls: Boolean): String? =
        runCatching {
            SettingsBackupManager.export(Config.snapshot(), includeAddonUrls)
        }.getOrElse {
            toast(ctx, "Could not create the backup")
            null
        }

    private fun restoreBackupText(ctx: Context, rawText: String, onCancel: () -> Unit) {
        val backup = SettingsBackupManager.parse(rawText)
        val restored = backup?.settings
        if (backup == null || restored == null) {
            toast(ctx, "That is not a supported MultiStremio backup")
            onCancel()
            return
        }

        val warning = if (backup.includesAddonUrls) {
            "This backup contains addon source URLs and/or imported-extension references. They may expose access keys " +
                "or private repository addresses. It will replace all current settings."
        } else {
            "This preferences-only backup contains no addon sources or imported-extension references. " +
                "Restoring it will remove your current addons and imported homepage rows."
        }
        confirm(ctx, "Restore this backup?", warning, onCancel = onCancel) {
            restored.seeded = true
            Config.replace(restored)
            HomeRefreshNotice.mark()
            toast(ctx, "Backup restored.")
            MainMenuSettingsScreen.show(ctx)
        }
    }
}
