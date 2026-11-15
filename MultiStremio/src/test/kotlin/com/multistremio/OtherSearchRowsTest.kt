package com.multistremio

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherSearchRowsTest {
    private fun manifest(catalogs: List<ManifestCatalog>) = Manifest(
        name = "Fixture",
        resources = listOf("catalog"),
        catalogs = catalogs,
    )

    @Test
    fun otherCapabilityIsIndependentOfCatalogsAndHomepageVisibility() {
        val addon = AddonConfig(
            id = "audio-addon",
            name = "AudioBooks",
            enabled = true,
            useCatalogs = false,
            useOther = true,
            hiddenCatalogs = mutableListOf("audiobook/latest"),
        )
        val fixture = manifest(
            listOf(
                ManifestCatalog(
                    type = "audiobook",
                    id = "latest",
                    name = "Latest Audiobooks",
                    extra = listOf(ExtraProp(name = "search")),
                ),
            ),
        )
        val settings = Settings(addons = mutableListOf(addon))

        val rows = OtherSearchRows.resolve(settings) { fixture }

        assertEquals(1, rows.size)
        assertEquals(addon.id, rows.single().addonId)
        assertEquals("audiobook", rows.single().type)
        assertEquals("latest", rows.single().catalogId)
        assertEquals("Latest Audiobooks", rows.single().title)
    }

    @Test
    fun otherRoutingDoesNotSuppressTheMainExperienceCatalogAddon() {
        val experience = AddonConfig(
            id = "experience",
            name = "Experience Catalog",
            useCatalogs = true,
            useOther = false,
        )
        val audio = AddonConfig(
            id = "audio",
            name = "AudioBookBay",
            useCatalogs = true,
            useOther = true,
        )
        val settings = Settings(addons = mutableListOf(experience, audio))
        val manifests = mapOf(
            "experience" to manifest(
                listOf(
                    ManifestCatalog(
                        type = "movie",
                        id = "top",
                        name = "Popular Movies",
                        extra = listOf(ExtraProp(name = "search")),
                    ),
                    ManifestCatalog(
                        type = "series",
                        id = "top",
                        name = "Popular Series",
                        extra = listOf(ExtraProp(name = "search")),
                    ),
                ),
            ),
            "audio" to manifest(
                listOf(
                    ManifestCatalog(
                        type = "audiobook",
                        id = "latest",
                        name = "Latest Audiobooks",
                        extra = listOf(ExtraProp(name = "search")),
                    ),
                ),
            ),
        )

        val dedicated = OtherSearchRows.resolve(settings) { manifests[it.id] }
            .map { it.key }.toSet()

        assertEquals(
            setOf(catalogOrderKey("audio", "audiobook", "latest")),
            dedicated,
        )
        assertFalse(catalogOrderKey("experience", "movie", "top") in dedicated)
        assertFalse(catalogOrderKey("experience", "series", "top") in dedicated)
    }

    @Test
    fun documentaryCatalogKeepsItsDeclaredMovieType() {
        val addon = AddonConfig(
            id = "docs",
            name = "Documentary Library",
            useOther = true,
        )
        val fixture = manifest(
            listOf(
                ManifestCatalog(
                    type = "movie",
                    id = "documentaries",
                    name = "Documentary Library",
                    extra = listOf(ExtraProp(name = "search")),
                ),
            ),
        )

        val row = OtherSearchRows.resolve(Settings(addons = mutableListOf(addon))) { fixture }.single()

        assertEquals("movie", row.type)
        assertEquals(TvType.Movie, StremioMediaTypes.toCloudStream(row.type))
    }

    @Test
    fun audiobookOtherProviderRemainsEligibleWithDefaultMovieAndSeriesSearchChips() {
        val types = OtherSearchRows.searchProviderTypes("audiobook")

        assertTrue(TvType.AudioBook in types)
        assertTrue(TvType.Movie in types)
        assertTrue(TvType.TvSeries in types)
    }

    @Test
    fun catalogsWithoutSearchSupportCannotCreateOtherSearchRows() {
        val addon = AddonConfig(id = "no-search", name = "No Search", useOther = true)
        val fixture = manifest(
            listOf(ManifestCatalog(type = "movie", id = "popular", name = "Popular")),
        )

        assertTrue(OtherSearchRows.resolve(Settings(addons = mutableListOf(addon))) { fixture }.isEmpty())
        assertFalse(AddonConfig().useOther)
    }

    @Test
    fun otherCatalogPosterAndMetadataSurviveSerializationAsFallback() {
        val source = Meta(
            id = "book-1",
            type = "audiobook",
            name = "Example Audiobook",
            poster = "https://img.example/book.jpg",
            description = "A catalog-owned description",
            year = 2024,
            genres = listOf("Mystery"),
        )
        val item = ItemRef(
            a = "audio",
            t = "audiobook",
            i = "book-1",
            n = source.name,
            p = source.poster,
            otherSource = true,
            otherMeta = source.toPreview(),
        )
        val decoded = Json.parse<ItemRef>(Json.toJson(item))!!

        assertTrue(decoded.otherSource)
        assertEquals(source.poster, decoded.otherMeta?.poster)
        assertEquals(source.description, decoded.otherMeta?.description)
        assertEquals(source.genres, decoded.otherMeta?.genres)
        assertEquals(source.type, decoded.t)
    }
}
