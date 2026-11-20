package com.multistremio

import android.content.Context

/** Named snapshots for stream filters, ordering, link labels and subtitle preferences. */
internal object ProfileSettingsScreen {
    fun chooseFromHub(ctx: Context) {
        val settings = Config.snapshot()
        val items = settings.profiles.keys.map { name ->
            val active = name == settings.activeProfile
            SettingItem(
                title = name,
                summary = if (active) "Currently active" else "Switch filters, sorting, link design and subtitle preferences",
                type = SettingItemType.Value,
                valueLabel = if (active) "Active" else "Use",
                onClick = {
                    if (!active) {
                        Config.update {
                            profiles[name]?.let { applyProfile(it) }
                            activeProfile = name
                        }
                        toast(ctx, "Using $name profile.")
                        MainMenuSettingsScreen.show(ctx)
                    }
                },
                section = "Profiles",
            )
        }
        settingMenu(
            ctx,
            "Switch profile",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Choose a saved profile. Addons and homepage rows remain shared.",
        )
    }

    fun show(ctx: Context) {
        val settings = Config.snapshot()
        val names = settings.profiles.keys.toList()
        val items = buildList {
            names.forEach { name ->
                val active = name == settings.activeProfile
                add(SettingItem(
                    title = name,
                    summary = if (active) "Active profile" else "Switch to this profile",
                    type = SettingItemType.Value,
                    valueLabel = if (active) "Active" else "Use",
                    onClick = {
                        if (!active) {
                            Config.update {
                                profiles[name]?.let { applyProfile(it) }
                                activeProfile = name
                            }
                            toast(ctx, "Using $name profile.")
                            MainMenuSettingsScreen.show(ctx)
                        }
                    },
                    section = "Profiles",
                ))
            }
            add(SettingItem(
                title = "Save current as new profile",
                summary = "Save filters, sort order, link design and subtitle preferences",
                onClick = { saveAs(ctx) },
                section = "Manage profiles",
            ))
            add(SettingItem(
                title = "Create Home starter",
                summary = "Start with your current settings as a reusable Home profile",
                onClick = { createStarter(ctx, "Home") },
                section = "Starter profiles",
                searchKeywords = listOf("create", "starter", "home"),
            ))
            add(SettingItem(
                title = "Create Mobile data starter",
                summary = "Prefer smaller links, cap resolution at 720p and skip subtitle requests",
                onClick = { createStarter(ctx, "Mobile data") },
                section = "Starter profiles",
                searchKeywords = listOf("create", "starter", "mobile", "data saver"),
            ))
            add(SettingItem(
                title = "Rename active profile",
                summary = settings.activeProfile,
                onClick = { rename(ctx, settings.activeProfile) },
                section = "Manage profiles",
            ))
            add(SettingItem(
                title = "Delete active profile",
                summary = "The live settings remain in effect",
                danger = true,
                onClick = { delete(ctx, settings.activeProfile) },
                section = "Manage profiles",
            ))
        }
        settingMenu(
            ctx,
            "Profiles",
            items,
            { MainMenuSettingsScreen.show(ctx) },
            "Profiles change stream filters, sorting, link design and subtitle preferences. Addons and homepage rows stay shared.",
            showRefreshNotice = true,
        )
    }

    private fun saveAs(ctx: Context) {
        val settings = Config.snapshot()
        if (settings.profiles.size >= 10) {
            toast(ctx, "You can save up to 10 profiles.")
            show(ctx)
            return
        }
        prompt(ctx, "Save current as profile", "Choose a name for this settings snapshot.", "", false, onCancel = { show(ctx) }) { raw ->
            val name = raw.trim().take(32)
            when {
                name.isBlank() -> toast(ctx, "Enter a profile name.")
                settings.profiles.keys.any { it.equals(name, ignoreCase = true) } -> toast(ctx, "A profile with that name already exists.")
                else -> {
                    Config.update {
                        profiles[name] = profileSnapshot()
                        activeProfile = name
                    }
                    toast(ctx, "Saved $name profile.")
                }
            }
            show(ctx)
        }
    }

    private fun createStarter(ctx: Context, name: String) {
        val settings = Config.snapshot()
        if (settings.profiles.keys.any { it.equals(name, ignoreCase = true) }) {
            toast(ctx, "$name profile already exists.")
            show(ctx)
            return
        }
        if (settings.profiles.size >= 10) {
            toast(ctx, "You can save up to 10 profiles.")
            show(ctx)
            return
        }
        Config.update {
            val starter = profileSnapshot()
            if (name == "Mobile data") {
                starter.filter = FilterSettings(
                    allowedResolutions = mutableListOf(720, 576, 480, 360, 240),
                    hideCam = true,
                    maxSizeGb = 4,
                )
                starter.sort = SortSettings(preset = SortPreset.SMALLEST.name)
                starter.fetchSubtitles = false
                starter.streamFormat = StreamFormatSettings(
                    enabled = true,
                    fields = mutableListOf("addon", "quality", "size"),
                    showLabels = false,
                    designPreset = "COMPACT",
                )
                starter.detailedLabels = true
            }
            profiles[name] = starter
            applyProfile(starter)
            activeProfile = name
        }
        toast(ctx, "Created $name profile.")
        MainMenuSettingsScreen.show(ctx)
    }

    private fun rename(ctx: Context, oldName: String) {
        val settings = Config.snapshot()
        if (settings.profiles.size <= 1) {
            toast(ctx, "Keep at least one profile.")
            show(ctx)
            return
        }
        prompt(ctx, "Rename profile", null, oldName, false, onCancel = { show(ctx) }) { raw ->
            val newName = raw.trim().take(32)
            if (newName.isBlank()) {
                toast(ctx, "Enter a profile name.")
            } else if (newName != oldName && settings.profiles.keys.any { it.equals(newName, ignoreCase = true) }) {
                toast(ctx, "A profile with that name already exists.")
            } else if (newName != oldName) {
                Config.update {
                    val value = profiles.remove(oldName)
                    if (value != null) profiles[newName] = value
                    if (activeProfile == oldName) activeProfile = newName
                }
                toast(ctx, "Renamed profile to $newName.")
            }
            show(ctx)
        }
    }

    private fun delete(ctx: Context, name: String) {
        val settings = Config.snapshot()
        if (settings.profiles.size <= 1) {
            toast(ctx, "Keep at least one profile.")
            show(ctx)
            return
        }
        confirm(ctx, "Delete $name profile?", "This removes the saved snapshot. The current playback settings stay in effect.", onCancel = { show(ctx) }) {
            val beforeDeletion = Config.snapshot()
            AutomaticBackupStore(ctx).create("Before deleting $name profile", beforeDeletion)
            Config.update {
                profiles.remove(name)
                if (activeProfile == name) activeProfile = profiles.keys.first()
            }
            show(ctx)
            showUndoSnackbar(ctx, "$name profile deleted") {
                Config.replace(beforeDeletion)
                toast(ctx, "$name profile restored.")
                show(ctx)
            }
        }
    }
}
