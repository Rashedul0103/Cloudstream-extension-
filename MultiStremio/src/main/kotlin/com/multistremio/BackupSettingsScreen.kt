package com.multistremio

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.concurrent.thread

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

        menu(
            ctx, "Backup & maintenance",
            listOf(
                "Copy preferences-only backup (no source URLs)",
                "Copy full backup (includes addon URLs)",
                "Restore from backup",
                "Reset filters & sorting",
                "Remove ALL addons and settings",
            ),
            { MainMenuSettingsScreen.show(ctx) },
            "Choose a privacy level before exporting. Full backups may contain private access keys in addon URLs.",
        ) { which ->
            when (which) {
                0 -> confirm(
                    ctx,
                    "Copy preferences-only backup?",
                    "This export excludes all Stremio addon URLs, manifests and imported homepage-row references. " +
                        "Restoring it will not restore your sources; you will need to add them again.",
                    onCancel = { backupMenu(ctx) },
                ) { copyBackup(includeAddonUrls = false) }
                1 -> confirm(
                    ctx,
                    "Copy full backup?",
                    "This includes addon endpoints and cached manifests. Addon URLs may contain debrid keys or other " +
                        "credentials. The clipboard is not encrypted. Do not share this backup publicly.",
                    onCancel = { backupMenu(ctx) },
                ) { copyBackup(includeAddonUrls = true) }
                2 -> prompt(
                    ctx,
                    "Restore backup",
                    "Paste a MultiStremio backup or settings JSON copied from an older version. This replaces the current configuration.",
                    "",
                    false,
                    onCancel = { backupMenu(ctx) },
                ) { pasted ->
                    val backup = SettingsBackupManager.parse(pasted)
                    val restored = backup?.settings
                    if (backup == null || restored == null) {
                        toast(ctx, "That is not a supported MultiStremio backup")
                        backupMenu(ctx)
                    } else {
                        val warning = if (backup.includesAddonUrls) {
                            "This backup contains addon source URLs and/or imported-extension references. They may expose access keys or private repository addresses. It will replace all current settings."
                        } else {
                            "This preferences-only backup contains no addon sources or imported-extension references. Restoring it will remove your current addons and imported homepage rows."
                        }
                        confirm(ctx, "Restore this backup?", warning, onCancel = { backupMenu(ctx) }) {
                            restored.seeded = true
                            Config.replace(restored)
                            toast(ctx, "Backup restored. Refresh the home page.")
                            MainMenuSettingsScreen.show(ctx)
                        }
                    }
                }
                3 -> confirm(ctx, "Reset filters & sorting?", "Addons are kept.", onCancel = { backupMenu(ctx) }) {
                    Config.update { filter = FilterSettings(); sort = SortSettings() }
                    toast(ctx, "Filters and sorting reset.")
                    backupMenu(ctx)
                }
                4 -> confirm(ctx, "Remove everything?", "All addons and settings are deleted (Cinemeta is not re-added).", onCancel = { backupMenu(ctx) }) {
                    Config.replace(Settings(seeded = true))
                    toast(ctx, "All MultiStremio settings cleared.")
                    MainMenuSettingsScreen.show(ctx)
                }
            }
        }
    }
}
