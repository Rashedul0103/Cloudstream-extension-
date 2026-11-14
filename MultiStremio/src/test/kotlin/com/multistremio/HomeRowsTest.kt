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
        assertEquals("ON", rows.single { it.addon === active }.status)
        assertEquals("HIDDEN", rows.single { it.addon === hidden }.status)
        assertEquals("ADDON OFF", rows.single { it.addon === disabled }.status)
        assertEquals("CATALOGS OFF", rows.single { it.addon === catalogsOff }.status)
        assertFalse(rows.any { it.name == "Search only" })
        assertEquals(1, HomeRows.visibleCount(settings.copy(addons = mutableListOf(active))))
    }

    @Test
    fun homepageVisibilityRequiresCatalogResourceButSettingsStillShowsBrokenRows() {
        val addon = AddonConfig(id = "no-resource", name = "No resource")
        val rows = HomeRows.resolve(Settings(addons = mutableListOf(addon))) {
            manifest(resources = listOf("meta"))
        }

        assertEquals(1, rows.size)
        assertEquals("RESOURCE MISSING", rows.single().status)
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
        assertEquals("EXTENSION MISSING", rows.single().status)
        assertTrue(rows.single().rowEnabled)
        assertFalse(rows.single().visibleOnHome)
    }
}
