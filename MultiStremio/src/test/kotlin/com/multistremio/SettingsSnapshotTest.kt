package com.multistremio

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [snapshot] against a classic mistake: adding a mutable list or map to [Settings] (or a
 * nested settings class) and forgetting to copy it. A shared collection would be mutated by the
 * settings UI while provider threads iterate it.
 */
class SettingsSnapshotTest {
    private fun populated(): Settings = Settings(
        addons = mutableListOf(
            AddonConfig(
                id = "a", url = "https://example.invalid/a", name = "A",
                hiddenCatalogs = mutableListOf("movie/top"),
            ),
        ),
        catalogOrder = mutableListOf("a|movie/top"),
        externalRows = mutableListOf(ExternalRowConfig(key = "ext1", rowName = "Row")),
        customCatalogNames = mutableMapOf("a|movie/top" to "Mine"),
        subtitleLanguages = mutableListOf("en"),
        profiles = mutableMapOf(
            "Home" to ProfileSnapshot(
                filter = FilterSettings(excludeKeywords = mutableListOf("CAM")),
                subtitleLanguages = mutableListOf("en"),
            ),
        ),
        activeProfile = "Home",
    )

    private fun isOurDataClass(type: Class<*>): Boolean =
        type.name.startsWith("com.multistremio.") && !type.isEnum && !type.isInterface

    /** Asserts that no mutable collection or settings object is shared between the two graphs. */
    private fun assertNothingShared(path: String, original: Any?, copy: Any?, visited: MutableSet<String>) {
        if (original == null || copy == null) return
        when (original) {
            is Collection<*> -> {
                assertNotSame("$path is shared with its snapshot", original, copy)
                val copyItems = (copy as Collection<*>).toList()
                original.toList().forEachIndexed { index, item ->
                    assertNothingShared("$path[$index]", item, copyItems[index], visited)
                }
            }
            is Map<*, *> -> {
                assertNotSame("$path is shared with its snapshot", original, copy)
                val copyMap = copy as Map<*, *>
                original.forEach { (key, value) -> assertNothingShared("$path[$key]", value, copyMap[key], visited) }
            }
            else -> {
                if (!isOurDataClass(original.javaClass)) return
                assertNotSame("$path is shared with its snapshot", original, copy)
                var type: Class<*>? = original.javaClass
                while (type != null && isOurDataClass(type)) {
                    for (field in type.declaredFields) {
                        if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                        field.isAccessible = true
                        visited += "${type.simpleName}.${field.name}"
                        assertNothingShared(
                            "$path.${field.name}",
                            field.get(original),
                            field.get(copy),
                            visited,
                        )
                    }
                    type = type.superclass
                }
            }
        }
    }

    @Test
    fun snapshotSharesNoMutableCollectionsWithTheOriginal() {
        val original = populated()
        val visited = mutableSetOf<String>()
        assertNothingShared("settings", original, original.snapshot(), visited)
        // The walk must actually have reached the nested classes, or the test proves nothing.
        assertTrue("Settings fields were not visited", visited.any { it.startsWith("Settings.") })
        assertTrue("AddonConfig fields were not visited", visited.any { it.startsWith("AddonConfig.") })
        assertTrue("FilterSettings fields were not visited", visited.any { it.startsWith("FilterSettings.") })
        assertTrue("SortSettings fields were not visited", visited.any { it.startsWith("SortSettings.") })
        assertTrue(
            "StreamFormatSettings fields were not visited",
            visited.any { it.startsWith("StreamFormatSettings.") },
        )
        assertTrue("ExternalRowConfig fields were not visited", visited.any { it.startsWith("ExternalRowConfig.") })
    }

    @Test
    fun changingTheOriginalAfterSnapshotDoesNotChangeTheSnapshot() {
        val original = populated()
        val snapshot = original.snapshot()

        original.addons[0].hiddenCatalogs.add("series/top")
        original.addons[0].name = "Renamed"
        original.addons.add(AddonConfig(id = "b", url = "https://example.invalid/b"))
        original.catalogOrder.clear()
        original.customCatalogNames["x"] = "y"
        original.filter.allowedResolutions.clear()
        original.sort.preferCodecs.add("HEVC")
        original.streamFormat.fields.clear()
        original.subtitleLanguages.add("bn")

        assertEquals(1, snapshot.addons.size)
        assertEquals("A", snapshot.addons[0].name)
        assertEquals(listOf("movie/top"), snapshot.addons[0].hiddenCatalogs)
        assertEquals(listOf("a|movie/top"), snapshot.catalogOrder)
        assertEquals(mapOf("a|movie/top" to "Mine"), snapshot.customCatalogNames)
        assertFalse(snapshot.filter.allowedResolutions.isEmpty())
        assertTrue(snapshot.sort.preferCodecs.isEmpty())
        assertEquals(SortCriteria.defaults(), snapshot.sort.customOrder)
        assertFalse(snapshot.streamFormat.fields.isEmpty())
        assertEquals(listOf("en"), snapshot.subtitleLanguages)
        assertEquals(listOf("CAM"), snapshot.profiles["Home"]!!.filter.excludeKeywords)
    }

    @Test
    fun sanitizingPartialCustomSettingsPreservesChoicesAndFillsMissingCriteria() {
        val settings = Settings(
            sort = SortSettings(
                preset = "CUSTOM",
                customOrder = mutableListOf(SortCriterionSetting("size", false)),
            ),
            streamFormat = StreamFormatSettings(designPreset = "not-a-preset"),
        ).sanitized()

        assertEquals(
            listOf("size", "resolution", "source", "hdr", "seeders", "addon"),
            settings.sort.customOrder.map { it.id },
        )
        assertFalse(settings.sort.customOrder.first().descending)
        assertEquals("AUTO", settings.streamFormat.designPreset)
    }

    @Test
    fun newLinkDesignPresetDefaultsToLegacyCompatibleAuto() {
        assertEquals("AUTO", StreamFormatSettings().designPreset)
    }

    @Test
    fun legacySettingsGainOneDefaultProfileWithoutLosingCurrentPreferences() {
        val legacy = Settings(
            filter = FilterSettings(minSeeders = 7),
            subtitleLanguages = mutableListOf("en", "bn"),
        ).sanitized()

        assertEquals("Default", legacy.activeProfile)
        assertEquals(setOf("Default"), legacy.profiles.keys)
        assertEquals(7, legacy.profiles.getValue("Default").filter.minSeeders)
        assertEquals(listOf("en", "bn"), legacy.profiles.getValue("Default").subtitleLanguages)

        val copy = legacy.snapshot()
        copy.profiles.getValue("Default").filter.minSeeders = 99
        assertEquals(7, legacy.profiles.getValue("Default").filter.minSeeders)
    }
}
