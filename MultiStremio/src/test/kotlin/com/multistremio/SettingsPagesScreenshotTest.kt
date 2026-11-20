package com.multistremio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** Renders the non-stream settings pages for visual review in the CI screenshot artifact. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPagesScreenshotTest {
    private val colors = Palette(
        background = 0xfff7f7f8.toInt(),
        primary = 0xff1b1b1f.toInt(),
        secondary = 0xff5f6368.toInt(),
        accent = 0xff1a73e8.toInt(),
        outline = 0x1f000000,
        ripple = 0x331a73e8,
    )

    private fun render(name: String, widthDp: Int = 360, build: (Context) -> Unit): Bitmap {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        SettingsDialogHost.dismiss()
        try {
            Config.replace(
                Settings(
                    seeded = true,
                    addons = mutableListOf(
                        AddonConfig(
                            id = "screenshot-addon",
                            url = "https://addon.example.test",
                            name = "Example addon",
                        ),
                    ),
                ),
            )
            build(ctx)
            val root = SettingsDialogHost.currentScreenRoot()
                ?: error("$name screen did not open")
            val widthPx = dp(ctx, widthDp)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val bitmap = Bitmap.createBitmap(widthPx, root.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(colors.background)
                root.draw(this)
            }
            val directory = File("build/screenshots").apply { mkdirs() }
            FileOutputStream(File(directory, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("$name rendered too little visible content", pixels.count { it != colors.background } > 1_000)
            assertTrue("$name is unexpectedly short", bitmap.height > 180)
            return bitmap
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun everyIconProducesVisiblePathPixels() {
        SettingsIcons.ALL.forEach { (name, pathData) ->
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            IconDrawable(pathData, colors.accent).apply {
                setBounds(0, 0, 48, 48)
                draw(Canvas(bitmap))
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("$name icon did not draw", pixels.count { Color.alpha(it) > 0 } > 4)
        }
    }

    @Test
    fun rendersHubSourcesProfilesBackupDiagnosticsAndResetPages() {
        render("settings-hub") { MainMenuSettingsScreen.show(it) }
        render("settings-addons") { AddonsSettingsScreen.show(it) }
        render("settings-addon-detail") {
            AddonsSettingsScreen.showAddon(it, Config.snapshot().addons.single())
        }
        render("settings-profiles") { ProfileSettingsScreen.show(it) }
        render("settings-backup") { BackupSettingsScreen.show(it) }
        render("settings-diagnostics-empty") { DiagnosticsSettingsScreen.show(it) }
        render("settings-danger-zone") { DangerZoneSettingsScreen.show(it) }
    }
}
