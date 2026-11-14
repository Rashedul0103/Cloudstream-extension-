package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test
    fun languageTagsNormalizeAcrossCodesAndRegions() {
        assertEquals("en", Langs.normalize("English (US)"))
        assertEquals("en", Langs.normalize("eng"))
        assertEquals("pt", Langs.normalize("pt-BR"))
        assertEquals("zh", Langs.normalize("zh-Hans"))
        assertEquals("bn", Langs.normalize("বাংলা"))
        assertEquals("und", Langs.normalize("unknown"))
    }

    @Test
    fun releaseLanguageDetectionRecognizesCommonTagsAndBangla() {
        val detected = Langs.detect("Example.Movie.1080p.WEB-DL.BENG.ENG.বাংলা")
        assertTrue("Bengali should be detected", "bn" in detected)
        assertTrue("English should be detected", "en" in detected)
    }

    @Test
    fun detectionDoesNotTreatOrdinaryTwoLetterWordsAsLanguages() {
        val detected = Langs.detect("It Is In No Way a Language Marker")
        assertFalse("it" in detected)
        assertFalse("in" in detected)
        assertFalse("no" in detected)
    }

    @Test
    fun subtitleLanguageFilterUsesCanonicalCodes() {
        val raw = RawStream(name = "Example.1080p.WEB-DL.ENG")
        val (info, text) = StreamParser.parse(raw)
        val parsed = ParsedStream(0, "Test addon", raw, text, info)
        val filter = FilterSettings(
            allowedResolutions = mutableListOf(0, 1080),
            requireLanguages = mutableListOf("English (US)"),
        )
        assertTrue(StreamFilter.passes(parsed, filter))
    }

    @Test
    fun strictLanguageModeRejectsUndetectedLanguageWithoutTargetList() {
        val raw = RawStream(name = "Example.Movie.1080p.WEB-DL")
        val (info, text) = StreamParser.parse(raw)
        val parsed = ParsedStream(0, "Test addon", raw, text, info)
        val filter = FilterSettings(
            allowedResolutions = mutableListOf(0, 1080),
            strictLanguage = true,
        )
        assertFalse(StreamFilter.passes(parsed, filter))
    }

    @Test
    fun externalRowIdentityDoesNotDependOnRequestDataOrDisplayName() {
        val descriptor = ExternalRowIdentity.descriptor(
            sourcePlugin = "https://plugins.example/repo.json",
            providerClass = "example.Provider",
            rowName = "Trending",
            horizontalImages = true,
        )
        val keyBeforeDataChange = ExternalRowIdentity.key(descriptor, 0)
        val keyAfterDataChange = ExternalRowIdentity.key(descriptor, 0)
        val secondDuplicateKey = ExternalRowIdentity.key(descriptor, 1)

        assertEquals(keyBeforeDataChange, keyAfterDataChange)
        assertNotEquals(keyBeforeDataChange, secondDuplicateKey)

        val saved = ExternalRowConfig(
            key = "legacy-key",
            providerName = "Old display name",
            providerClass = "example.Provider",
            sourcePlugin = "https://plugins.example/repo.json",
            rowName = "Trending",
            dataHash = "old-request-hash",
            horizontalImages = true,
        )
        val current = saved.copy(
            key = keyAfterDataChange,
            providerName = "Renamed provider",
            dataHash = "new-request-hash",
        )
        assertTrue(ExternalRowIdentity.matches(saved, current))
    }

    @Test
    fun preferencesOnlyBackupDoesNotContainAddonSecrets() {
        val settings = sampleSettings()
        val safe = SettingsBackupManager.export(settings, includeAddonUrls = false)

        assertFalse(safe.contains("secret-api-key"))
        assertFalse(safe.contains("private-addon.example"))
        val restored = SettingsBackupManager.parse(safe)
        assertNotNull(restored)
        assertFalse(restored!!.includesAddonUrls)
        assertTrue(restored.settings!!.addons.isEmpty())
        assertTrue(restored.settings!!.externalRows.isEmpty())
    }

    @Test
    fun fullAndLegacyBackupsPreserveAddonUrls() {
        val settings = sampleSettings()
        val full = SettingsBackupManager.export(settings, includeAddonUrls = true)
        assertTrue(full.contains("secret-api-key"))

        val restoredFull = SettingsBackupManager.parse(full)
        assertNotNull(restoredFull)
        assertTrue(restoredFull!!.includesAddonUrls)
        assertEquals(settings.addons.single().url, restoredFull.settings!!.addons.single().url)

        val legacy = SettingsBackupManager.parse(Json.toJson(settings))
        assertNotNull(legacy)
        assertTrue("Legacy raw settings contain addon URLs", legacy!!.includesAddonUrls)
        assertEquals(settings.addons.single().url, legacy.settings!!.addons.single().url)
    }

    @Test
    fun addonUrlsRetainQueryCredentialsAfterPathConstruction() {
        val base = Urls.normalizeAddonUrl(
            "stremio://EXAMPLE.com/addons/manifest.json?token=secret-api-key",
        )
        assertEquals("https://example.com/addons?token=secret-api-key", base)
        assertEquals(
            "https://example.com/addons/stream/movie/tt123.json?token=secret-api-key",
            Urls.resource(base, "stream", "movie", "tt123"),
        )
    }

    private fun sampleSettings() = Settings(
        addons = mutableListOf(
            AddonConfig(
                id = "hashed-addon-id",
                url = "https://private-addon.example/manifest?token=secret-api-key",
                name = "Private addon",
                manifest = """{"name":"Private addon"}""",
            ),
        ),
        externalRows = mutableListOf(
            ExternalRowConfig(
                key = "external:private",
                providerName = "Private extension",
                sourcePlugin = "https://private-plugin.example/repo.json",
                rowName = "Imported row",
            ),
        ),
        catalogOrder = mutableListOf("hashed-addon-id|movie|top", "external:private"),
    )
}
