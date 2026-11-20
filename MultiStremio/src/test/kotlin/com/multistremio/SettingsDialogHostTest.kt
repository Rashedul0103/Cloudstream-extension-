package com.multistremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
class SettingsDialogHostTest {
    private fun screen(ctx: android.content.Context, bodyText: String): Pair<LinearLayout, ScrollView> {
        val root = vertical(ctx)
        root.addView(text(ctx, "Stable header", 18f, 0xff111111.toInt()))
        val scroll = ScrollView(ctx).apply {
            tag = "test-scroll"
            addView(vertical(ctx).apply {
                addView(text(ctx, bodyText, 14f, 0xff111111.toInt()).apply {
                    contentDescription = bodyText
                    isFocusable = true
                })
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 180),
        ))
        root.addView(text(ctx, "Stable footer", 12f, 0xff111111.toInt()))
        return root to scroll
    }

    @Test
    fun sameScreenRefreshReusesRootAndScrollAndReplacesContent() {
        val ctx = RuntimeEnvironment.getApplication()
        val (oldRoot, oldScroll) = screen(ctx, "Old value")
        val (newRoot, _) = screen(ctx, "Updated value")
        try {
            SettingsDialogHost.show(ctx, "test:in-place", oldRoot)
            SettingsDialogHost.show(ctx, "test:in-place", newRoot)

            assertSame(oldRoot, SettingsDialogHost.currentScreenRoot())
            val refreshedScroll = oldRoot.findViewWithTag<ScrollView>("test-scroll")
            assertSame(oldScroll, refreshedScroll)
            assertTrue("The attached screen should now display the updated content", containsText(oldRoot, "Updated value"))
        } finally {
            SettingsDialogHost.dismiss()
        }
    }

    @Test
    fun filterSwitchUpdatesConfigAndKeepsItsViewAttached() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val clean = Settings()
        val root = vertical(ctx)
        val scroll = ScrollView(ctx)
        val body = vertical(ctx)
        FiltersSettingsScreen.body(ctx, palette(ctx), clean, body)
        scroll.addView(body)
        root.addView(scroll)
        try {
            Config.replace(clean)
            SettingsDialogHost.show(ctx, "test:filter-switch", root)
            val originalSwitch = findSwitch(root, "Hide magnet links, off")
            assertTrue("Filter switch should be visible", originalSwitch != null)
            originalSwitch!!.performClick()

            assertTrue("Filter change should persist immediately", Config.snapshot().filter.hideMagnets)
            assertSame("A simple filter toggle must not rebuild its view", root, SettingsDialogHost.currentScreenRoot())
            assertSame("The changed switch should remain attached", originalSwitch, findSwitch(root, "Hide magnet links, on"))
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun filterSwitchRowTogglesWhenItsLabelAreaIsClicked() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val clean = Settings()
        val root = vertical(ctx)
        val scroll = ScrollView(ctx)
        val body = vertical(ctx)
        FiltersSettingsScreen.body(ctx, palette(ctx), clean, body)
        scroll.addView(body)
        root.addView(scroll)
        try {
            Config.replace(clean)
            SettingsDialogHost.show(ctx, "test:filter-row", root)
            val row = findByDescription(root, "Hide magnet links, off")
            assertTrue("Switch row should be visible", row != null)
            row!!.performClick()

            assertTrue("Row click should toggle the setting", Config.snapshot().filter.hideMagnets)
            assertTrue("Row description should announce the updated state", row.contentDescription == "Hide magnet links, on")
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun behaviorSwitchUpdatesConfigWithoutRebuildingItsControl() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val body = vertical(ctx)
        behaviorBody(ctx, palette(ctx), previous, body) {}
        val root = screenWithBody(ctx, body)
        try {
            Config.replace(previous)
            SettingsDialogHost.show(ctx, "test:behavior-switch", root)
            val toggle = findSwitch(root, "Retry a failed addon once, off")
            assertTrue("Behavior switch should be visible", toggle != null)
            toggle!!.performClick()

            assertTrue("Behavior change should persist immediately", Config.snapshot().retryFailedAddonOnce)
            assertSame(root, SettingsDialogHost.currentScreenRoot())
            assertSame(toggle, findSwitch(root, "Retry a failed addon once, on"))
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun customLinkLabelSwitchUpdatesPreviewWithoutRebuildingItsControl() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val custom = previous.copy(
            streamFormat = previous.streamFormat.copy(enabled = true, designPreset = "CUSTOM", showLabels = false),
            detailedLabels = true,
        )
        val body = vertical(ctx)
        FormatterSettingsScreen.body(ctx, palette(ctx), custom, body)
        val root = screenWithBody(ctx, body)
        try {
            Config.replace(custom)
            SettingsDialogHost.show(ctx, "test:custom-label-switch", root)
            val toggle = findSwitch(root, "Show field names, off")
            assertTrue("Custom label switch should be visible", toggle != null)
            toggle!!.performClick()

            assertTrue("Custom label preference should persist immediately", Config.snapshot().streamFormat.showLabels)
            assertSame(root, SettingsDialogHost.currentScreenRoot())
            assertSame(toggle, findSwitch(root, "Show field names, on"))
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun addonCapabilitySwitchUpdatesWithoutRebuildingTheDetailScreen() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val addon = AddonConfig(id = "test-addon", url = "https://example.test", name = "Test addon")
        try {
            Config.replace(Settings(addons = mutableListOf(addon)))
            AddonsSettingsScreen.showAddon(ctx, addon)
            val root = SettingsDialogHost.currentScreenRoot()
            val toggle = root?.let { findSwitch(it, "Catalogs and search, on") }
            assertTrue("Addon capability switch should be visible", toggle != null)
            toggle!!.performClick()

            assertTrue("Capability change should persist immediately", !Config.snapshot().addons.single().useCatalogs)
            assertSame(root, SettingsDialogHost.currentScreenRoot())
            assertTrue("The updated switch should remain visible", root?.let { findSwitch(it, "Catalogs and search, off") } != null)
            assertTrue("Catalog visibility should disappear when catalogs are disabled", !containsText(root!!, "Home catalogs to show"))
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun addonTimeoutOptionsAreInlineAndSaveImmediately() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val addon = AddonConfig(id = "timeout-addon", url = "https://example.test", name = "Timeout addon")
        try {
            Config.replace(Settings(addons = mutableListOf(addon)))
            AddonsSettingsScreen.showAddon(ctx, addon)
            val root = SettingsDialogHost.currentScreenRoot()!!
            val tenSeconds = findText(root, "10s")
            assertTrue("The per-addon timeout option should be inline", tenSeconds != null)
            tenSeconds!!.performClick()

            assertEquals(10, Config.snapshot().addons.single().timeoutSec ?: -1)
            assertSame("Selecting an inline timeout should keep the same settings screen", root, SettingsDialogHost.currentScreenRoot())
            assertTrue("The selected option should be announced", findByDescription(root, "10s, selected") != null)
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun hubSearchForAnAddonOpensItsDetailScreenDirectly() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        val addon = AddonConfig(id = "deep-link-addon", url = "https://example.test", name = "Direct target")
        try {
            Config.replace(Settings(addons = mutableListOf(addon)))
            val result = SettingsSearchIndex.items(ctx).first { it.title == addon.name }
            result.onClick!!.invoke()

            val root = SettingsDialogHost.currentScreenRoot()!!
            assertTrue("Search result should open addon controls directly", containsText(root, "Request timeout"))
            assertTrue(containsText(root, "Manifest URL"))
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun settingsHubUsesTheSpecifiedTopLevelGroups() {
        val ctx = RuntimeEnvironment.getApplication()
        val previous = Config.snapshot()
        try {
            Config.replace(Settings())
            MainMenuSettingsScreen.show(ctx)
            val root = SettingsDialogHost.currentScreenRoot()!!

            assertTrue(containsText(root, "SOURCES"))
            assertTrue(containsText(root, "STREAMS"))
            assertTrue(containsText(root, "BEHAVIOR"))
            assertTrue(containsText(root, "DATA"))
            assertTrue("The reset entry belongs in Data", containsText(root, "Danger zone"))
            assertTrue("The old App group should not remain", !containsText(root, "APP"))
        } finally {
            SettingsDialogHost.dismiss()
            Config.replace(previous)
        }
    }

    @Test
    fun addonPromptSuggestsClipboardManifestAndShowsInlineUrlValidation() {
        val ctx = RuntimeEnvironment.getApplication()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("manifest", "stremio://example.test/addon/manifest.json?token=secret"))
        var saved: String? = null
        try {
            prompt(
                ctx,
                "Add addon",
                "Paste a manifest URL.",
                "",
                false,
                showPasteButton = true,
                validate = { value -> if (Urls.isValidAddonUrl(value)) null else "Enter a valid addon link." },
            ) { value -> saved = value }

            val root = SettingsDialogHost.currentScreenRoot()!!
            val suggestion = findText(root, "Manifest link found in clipboard · Use")
            assertTrue("A manifest-looking clipboard link should be offered without displaying it", suggestion != null)
            suggestion!!.performClick()
            val input = findTag<EditText>(root, "settings.input")!!
            assertEquals("stremio://example.test/addon/manifest.json?token=secret", input.text.toString())

            input.setText("ftp://example.test/addon")
            findText(root, "SAVE")!!.performClick()
            val validation = findTag<TextView>(root, "settings.validation-error")!!
            assertEquals(View.VISIBLE, validation.visibility)
            assertEquals("Enter a valid addon link.", validation.text.toString())

            input.setText("https://example.test/addon/manifest.json?token=secret")
            findText(root, "SAVE")!!.performClick()
            assertEquals(View.GONE, validation.visibility)
            assertEquals("https://example.test/addon/manifest.json?token=secret", saved)
        } finally {
            SettingsDialogHost.dismiss()
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun screenWithBody(ctx: android.content.Context, body: LinearLayout): LinearLayout {
        val root = vertical(ctx)
        val scroll = ScrollView(ctx)
        scroll.addView(body)
        root.addView(scroll)
        return root
    }

    private fun findSwitch(view: View, description: String): Switch? {
        if (view is Switch && view.contentDescription == description) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findSwitch(view.getChildAt(index), description)?.let { return it }
        return null
    }

    private fun findByDescription(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findByDescription(view.getChildAt(index), description)?.let { return it }
        return null
    }

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findText(view.getChildAt(index), value)?.let { return it }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findTag(view: View, value: String): T? {
        if (view.tag == value) return view as? T
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findTag<T>(view.getChildAt(index), value)?.let { return it }
        return null
    }

    private fun containsText(view: View, expected: String): Boolean {
        if (view is TextView && view.text.toString() == expected) return true
        if (view !is ViewGroup) return false
        return (0 until view.childCount).any { containsText(view.getChildAt(it), expected) }
    }
}
