package com.multistremio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamsSettingsScreenshotTest {
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

    private fun render(
        name: String,
        colors: Palette,
        build: (Context, LinearLayout) -> Unit,
    ): Bitmap {
        val ctx = RuntimeEnvironment.getApplication()
        val widthPx = dp(ctx, 360)
        val body = vertical(ctx, dp(ctx, 12)).apply { setBackgroundColor(colors.background) }
        build(ctx, body)
        body.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)
        if (ctx.resources.displayMetrics.scaledDensity >= ctx.resources.displayMetrics.density * 1.3f) {
            assertSegmentLabelsFit(body)
        }
        val bitmap = Bitmap.createBitmap(widthPx, body.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(colors.background)
            body.draw(this)
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun assertVisible(bitmap: Bitmap, background: Int) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Settings page rendered too little visible content", pixels.count { it != background } > 1_000)
        assertTrue("Settings page is unexpectedly short", bitmap.height > 220)
    }

    private fun assertSegmentLabelsFit(view: View) {
        if (view is TextView && (view.tag as? String)?.startsWith("segmented-option:") == true) {
            val requiredWidth = view.paint.measureText(view.text.toString()) + view.paddingLeft + view.paddingRight
            assertTrue("Segment label '${view.text}' is clipped", view.width >= requiredWidth)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertSegmentLabelsFit(view.getChildAt(index))
    }

    @Test
    fun rendersFilterSortingAndLinkDesignPagesInLightTheme() {
        val settings = Settings()
        val filters = render("streams-filters-light", light) { ctx, body ->
            FiltersSettingsScreen.body(ctx, light, settings, body)
        }
        val sorting = render("streams-sorting-light", light) { ctx, body ->
            SortingSettingsScreen.body(ctx, light, settings, body)
        }
        val design = render("streams-link-design-light", light) { ctx, body ->
            FormatterSettingsScreen.body(ctx, light, settings, body)
        }
        assertVisible(filters, light.background)
        assertVisible(sorting, light.background)
        assertVisible(design, light.background)
    }

    @Test
    fun rendersFilterSortingAndCustomLinkDesignPagesInDarkTheme() {
        val settings = Settings(
            sort = SortSettings(preset = "CUSTOM"),
            streamFormat = StreamFormatSettings(enabled = true, designPreset = "CUSTOM"),
        )
        val filters = render("streams-filters-dark", dark) { ctx, body ->
            FiltersSettingsScreen.body(ctx, dark, settings, body)
        }
        val sorting = render("streams-sorting-dark", dark) { ctx, body ->
            SortingSettingsScreen.body(ctx, dark, settings, body)
        }
        val design = render("streams-link-design-dark", dark) { ctx, body ->
            FormatterSettingsScreen.body(ctx, dark, settings, body)
        }
        assertVisible(filters, dark.background)
        assertVisible(sorting, dark.background)
        assertVisible(design, dark.background)
    }

    @Test
    fun rendersStreamSettingsAtLargeFontScales() {
        val ctx = RuntimeEnvironment.getApplication()
        val originalScaledDensity = ctx.resources.displayMetrics.scaledDensity
        try {
            listOf(1.3f, 2.0f).forEach { scale ->
                ctx.resources.displayMetrics.scaledDensity = ctx.resources.displayMetrics.density * scale
                val settings = Settings()
                val filters = render("streams-filters-font-${(scale * 100).toInt()}", light) { context, body ->
                    FiltersSettingsScreen.body(context, light, settings, body)
                }
                val sorting = render("streams-sorting-font-${(scale * 100).toInt()}", light) { context, body ->
                    SortingSettingsScreen.body(context, light, settings, body)
                }
                val design = render("streams-link-design-font-${(scale * 100).toInt()}", light) { context, body ->
                    FormatterSettingsScreen.body(context, light, settings, body)
                }
                assertVisible(filters, light.background)
                assertVisible(sorting, light.background)
                assertVisible(design, light.background)
            }
        } finally {
            ctx.resources.displayMetrics.scaledDensity = originalScaledDensity
        }
    }
}
