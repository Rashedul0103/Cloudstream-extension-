package com.multistremio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Draws the Behavior page to PNG files so the layout can be reviewed without installing the
 * plugin. CI uploads `build/screenshots` as the `settings-screenshots` artifact.
 *
 * Not a pixel-exact regression test: it checks that the page draws something and writes files.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
class BehaviorScreenshotTest {
    private val light = Palette(
        background = 0xfff7f7f8.toInt(),
        primary = 0xff1b1b1f.toInt(),
        secondary = 0xff5f6368.toInt(),
        accent = 0xff1a73e8.toInt(),
        outline = 0x1f000000,
        ripple = 0x331a73e8,
    )

    private val dark = Palette(
        background = 0xff121316.toInt(),
        primary = 0xffe6e6ea.toInt(),
        secondary = 0xff9aa0a6.toInt(),
        accent = 0xff8ab4f8.toInt(),
        outline = 0x33ffffff,
        ripple = 0x338ab4f8,
    )

    private fun render(name: String, colors: Palette, settings: Settings): Bitmap {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val widthPx = dp(ctx, 360)
        val body = vertical(ctx, dp(ctx, 12)).apply { setBackgroundColor(colors.background) }
        behaviorBody(ctx, colors, settings, body) {}

        body.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)

        val bitmap = Bitmap.createBitmap(widthPx, body.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(colors.background)
            body.draw(this)
        }

        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "behavior-$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return bitmap
    }

    /** Counts pixels that differ from the background, to prove something was actually drawn. */
    private fun inkPixels(bitmap: Bitmap, background: Int): Int {
        var count = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) if (pixel != background) count++
        return count
    }

    @Test
    fun rendersDefaultsInLightTheme() {
        val bitmap = render("light-defaults", light, Settings())
        assertTrue("light page drew no content", inkPixels(bitmap, light.background) > 1_000)
    }

    @Test
    fun rendersCustomValuesInDarkTheme() {
        val settings = Settings(
            timeoutSec = 45,
            rowTitleMode = "PREFIX",
            subtitleLanguages = mutableListOf("en", "bn"),
        )
        val bitmap = render("dark-custom", dark, settings)
        assertTrue("dark page drew no content", inkPixels(bitmap, dark.background) > 1_000)
    }
}
