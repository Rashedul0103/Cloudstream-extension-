package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

internal object BackupSettingsScreen {
    private var fullBackupMode = false

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

        fun shareBackup(includeAddonUrls: Boolean) {
            val payload = createBackup(ctx, includeAddonUrls) ?: return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MultiStremio backup")
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            runCatching { ctx.startActivity(Intent.createChooser(send, "Share MultiStremio backup")) }
                .onFailure { toast(ctx, "Could not open the share sheet") }
        }

        settingsPage(
            ctx,
            key = "backup",
            title = "Backup and restore",
            description = "Export a backup, restore a file or clipboard copy, or recover an earlier snapshot.",
            back = { MainMenuSettingsScreen.show(ctx) },
        ) { body, colors ->
            val privacyNote = if (fullBackupMode) {
                "Everything includes addon URLs and cached manifests, which may contain private access keys. Share carefully."
            } else {
                "Preferences only excludes addon URLs, cached manifests, and imported homepage row references. Sources must be added again after restore."
            }
            body.addView(controlBlock(
                ctx, colors, "Backup contents", if (fullBackupMode) "Everything" else "Preferences only",
                privacyNote,
                segmentedControl(ctx, colors, listOf("Preferences only", "Everything"), if (fullBackupMode) 1 else 0) { index ->
                    fullBackupMode = index == 1
                    backupMenu(ctx)
                },
            ))
            val actions = settingsCard(ctx, colors)
            fun addAction(title: String, summary: String, value: String, action: () -> Unit) {
                addCardRow(ctx, colors, actions, valueRow(ctx, colors, title, summary, value, action))
            }
            val fullWarning = "This backup contains addon URLs or access keys. Save it somewhere private."
            addAction("Save to file", "Export a TXT backup through the system file picker", "Export") {
                if (fullBackupMode) confirm(ctx, "Export full backup?", fullWarning, onCancel = again) {
                    exportBackupFile(includeAddonUrls = true)
                } else exportBackupFile(includeAddonUrls = false)
            }
            addAction("Share backup", "Open the system share sheet", "Share") {
                if (fullBackupMode) confirm(ctx, "Share full backup?", fullWarning, onCancel = again) {
                    shareBackup(includeAddonUrls = true)
                } else shareBackup(includeAddonUrls = false)
            }
            addAction("Copy backup", "Copy the selected backup contents to the clipboard", "Copy") {
                if (fullBackupMode) confirm(ctx, "Copy full backup?", fullWarning, onCancel = again) {
                    copyBackup(includeAddonUrls = true)
                } else copyBackup(includeAddonUrls = false)
            }
            body.addView(actions)

            body.addView(sectionLabel(ctx, colors, "Restore"))
            val restore = settingsCard(ctx, colors)
            addCardRow(ctx, colors, restore, valueRow(
                ctx, colors, "Restore from file", "Choose a backup TXT or older settings JSON", "Open",
            ) { BackupFileTransfer.importText(ctx) { contents -> restoreBackupText(ctx, contents, again) } })
            addCardRow(ctx, colors, restore, valueRow(
                ctx, colors, "Paste backup", "Restore a copied backup or older settings JSON", "Paste",
            ) {
                prompt(ctx, "Restore backup from clipboard", "Paste a MultiStremio backup or settings JSON. This replaces current settings.", "", false,
                    onCancel = again) { pasted -> restoreBackupText(ctx, pasted, again) }
            })
            addCardRow(ctx, colors, restore, valueRow(
                ctx, colors, "Restore a previous version", "${AutomaticBackupStore(ctx).list().size} automatic snapshot(s) on this device", "History",
            ) { restoreAutomaticSnapshot(ctx, again) })
            body.addView(restore)
        }
    }

    private fun createBackup(ctx: Context, includeAddonUrls: Boolean): String? =
        runCatching {
            SettingsBackupManager.export(Config.snapshot(), includeAddonUrls)
        }.getOrElse {
            toast(ctx, "Could not create the backup")
            null
        }

    private fun restoreAutomaticSnapshot(ctx: Context, onCancel: () -> Unit) {
        val snapshots = AutomaticBackupStore(ctx).list().sortedByDescending { it.createdAt }
        if (snapshots.isEmpty()) {
            toast(ctx, "No automatic snapshots have been saved yet.")
            onCancel()
            return
        }
        val timeFormat = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        choose(ctx, "Restore previous version", snapshots.map {
            "${timeFormat.format(java.util.Date(it.createdAt))} · ${it.reason}"
        }, 0, onCancel = onCancel) { index ->
            val selected = snapshots[index]
            val backup = SettingsBackupManager.parse(selected.payload)
            val restored = backup?.settings
            if (restored == null) {
                toast(ctx, "That automatic snapshot could not be read.")
                onCancel()
            } else {
                confirm(ctx, "Restore this snapshot?", "This replaces current settings. An automatic backup of the current state will be saved first.", onCancel = onCancel) {
                    if (AutomaticBackupStore(ctx).create("Before automatic snapshot restore", Config.snapshot()) == null) {
                        toast(ctx, "Restore cancelled because a backup of the current settings could not be saved.")
                        onCancel()
                        return@confirm
                    }
                    restored.seeded = true
                    Config.replace(restored)
                    HomeRefreshNotice.mark()
                    toast(ctx, "Previous version restored.")
                    MainMenuSettingsScreen.show(ctx)
                }
            }
        }
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
            if (AutomaticBackupStore(ctx).create("Before backup restore", Config.snapshot()) == null) {
                toast(ctx, "Restore cancelled because a backup of the current settings could not be saved.")
                onCancel()
                return@confirm
            }
            restored.seeded = true
            Config.replace(restored)
            HomeRefreshNotice.mark()
            toast(ctx, "Backup restored.")
            MainMenuSettingsScreen.show(ctx)
        }
    }
}
