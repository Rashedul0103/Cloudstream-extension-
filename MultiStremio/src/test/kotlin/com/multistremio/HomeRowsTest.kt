package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    private fun manifest(
        resources: List<Any> = listOf("catalog"),
        catalogs: List<ManifestCatalog> = listOf(
            ManifestCatalog(type = "movie", id = "popular", name = "Popular"),
            ManifestCatalog(
                type = "movie",
                id = "search-only",
                name = "Search only",
                extra = listOf(ExtraProp(name = "search", isRequired = true)),
            ),
        ),
    ) = Manifest(name = "Fixture", resources = resources, catalogs = catalogs)

    @Test
    fun homepageVisibilityUsesOneSharedRuleForAddonAndCatalogState() {
        val active = AddonConfig(
            id = "active",
            name = "Active",
            enabled = true,
            useCatalogs = true,
        )
        val hidden = AddonConfig(
            id = "hidden",
            name = "Hidden",
            enabled = true,
            useCatalogs = true,
            hiddenCatalogs = mutableListOf("movie/popular"),
        )
        val disabled = AddonConfig(
            id = "disabled",
            name = "Disabled",
            enabled = false,
            useCatalogs = true,
        )
        val catalogsOff = AddonConfig(
            id = "catalogs-off",
            name = "Catalogs off",
            enabled = true,
            useCatalogs = false,
        )
        val settings = Settings(addons = mutableListOf(active, hidden, disabled, catalogsOff))
        val manifests = mapOf(
            "active" to manifest(),
            "hidden" to manifest(),
            "disabled" to manifest(),
            "catalogs-off" to manifest(),
        )

        val rows = HomeRows.resolve(settings) { manifests[it.id] }

        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.visibleOnHome })
        assertEquals("Visible", rows.single { it.addon === active }.status)
        assertEquals("Hidden", rows.single { it.addon === hidden }.status)
        assertEquals("Addon is off", rows.single { it.addon === disabled }.status)
        assertEquals("Catalog use is off", rows.single { it.addon === catalogsOff }.status)
        assertFalse(rows.any { it.name == "Search only" })
        assertEquals(1, HomeRows.visibleCount(settings.copy(addons = mutableListOf(active))) { manifests[it.id] })
    }

    @Test
    fun homepageVisibilityRequiresCatalogResourceButSettingsStillShowsBrokenRows() {
        val addon = AddonConfig(id = "no-resource", name = "No resource")
        val rows = HomeRows.resolve(Settings(addons = mutableListOf(addon))) {
            manifest(resources = listOf("meta"))
        }

        assertEquals(1, rows.size)
        assertEquals("Addon doesn't provide this", rows.single().status)
        assertFalse(rows.single().visibleOnHome)
    }

    @Test
    fun configuredOrderIsSharedByVisibleRowsAndSettingsRows() {
        val addon = AddonConfig(id = "ordered", name = "Ordered")
        val fixture = manifest(catalogs = listOf(
            ManifestCatalog(type = "movie", id = "first", name = "First"),
            ManifestCatalog(type = "series", id = "second", name = "Second"),
        ))
        val firstKey = catalogOrderKey(addon.id, "movie", "first")
        val secondKey = catalogOrderKey(addon.id, "series", "second")
        val settings = Settings(
            addons = mutableListOf(addon),
            catalogOrder = mutableListOf(secondKey, firstKey),
        )

        val rows = HomeRows.resolve(settings) { fixture }

        assertEquals(listOf(secondKey, firstKey), rows.map { it.key })
        assertTrue(rows.all { it.visibleOnHome })
    }


    @Test
    fun homepageTitleIsSharedAndSupportsCustomNameAndAddonPrefixOrSuffix() {
        val addon = AddonConfig(id = "title-addon", name = "Example Addon")
        val settings = Settings(addons = mutableListOf(addon))
        val row = HomeRows.resolve(settings) { manifest() }.single()

        assertEquals("Popular - Movies", HomeRows.homeTitle(row, settings))
        settings.rowTitleMode = "PREFIX"
        assertEquals("Example Addon - Popular - Movies", HomeRows.homeTitle(row, settings))
        settings.rowTitleMode = "SUFFIX"
        assertEquals("Popular - Example Addon - Movies", HomeRows.homeTitle(row, settings))
        settings.customCatalogNames[row.key] = "My Picks"
        assertEquals("My Picks - Example Addon - Movies", HomeRows.homeTitle(row, settings))
    }

    @Test
    fun dragInsertionIndexTracksPointerWithoutMutatingList() {
        val original = listOf("A", "B", "C", "D")
        val centers = listOf(10f, 30f, 50f)
        assertEquals(0, DragReorderMath.insertionIndex(centers, 5f))
        assertEquals(2, DragReorderMath.insertionIndex(centers, 40f))
        assertEquals(3, DragReorderMath.insertionIndex(centers, 80f))
        assertEquals(listOf("A", "B", "C", "D"), original)
    }

    @Test
    fun missingImportedExtensionRowsRemainVisibleInSettingsButNotOnHomepage() {
        val external = ExternalRowConfig(
            key = "external:missing",
            providerName = "Other extension",
            providerClass = "example.Provider",
            sourcePlugin = "example.plugin",
            rowName = "Imported row",
            enabled = true,
        )

        val rows = HomeRows.resolve(
            Settings(externalRows = mutableListOf(external)),
            installedExternalRows = emptyList(),
        )

        assertEquals(1, rows.size)
        assertEquals("Extension not installed", rows.single().status)
        assertTrue(rows.single().rowEnabled)
        assertFalse(rows.single().visibleOnHome)
    }

    @Test
    fun homepageSourceReorderMovesWholeGroupsButPreservesRowsWithinEachGroup() {
        val reordered = HomepageGroupOrderMath.reorderCatalogGroups(
            currentOrder = listOf("a1", "a2", "b1", "c1", "stale-row"),
            groupRows = linkedMapOf(
                "addon:a" to listOf("a1", "a2"),
                "addon:b" to listOf("b1"),
                "addon:c" to listOf("c1"),
            ),
            orderedGroupKeys = listOf("addon:c", "addon:a", "addon:b"),
        )

        assertEquals(listOf("c1", "a1", "a2", "b1", "stale-row"), reordered)
    }

    @Test
    fun homepageSourceReorderRejectsDuplicateOrMissingGroupKeys() {
        val groups = linkedMapOf("addon:a" to listOf("a1"), "addon:b" to listOf("b1"))
        assertEquals(
            null,
            HomepageGroupOrderMath.reorderCatalogGroups(emptyList(), groups, listOf("addon:a", "addon:a")),
        )
        assertEquals(
            null,
            HomepageGroupOrderMath.reorderCatalogGroups(emptyList(), groups, listOf("addon:a")),
        )
    }

    @Test
    fun homepageSourceReorderChangesRepresentedAddonSlotsOnly() {
        assertEquals(
            listOf("no-home-row", "addon-c", "addon-b", "also-no-home-row"),
            HomepageGroupOrderMath.reorderAddonSlots(
                listOf("no-home-row", "addon-b", "addon-c", "also-no-home-row"),
                listOf("addon-c", "addon-b"),
            ),
        )
    }

}
