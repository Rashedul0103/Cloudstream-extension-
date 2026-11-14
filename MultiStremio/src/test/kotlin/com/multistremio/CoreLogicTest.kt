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
    fun playbackHeadersMergeCaseInsensitivelyAndExposeRefererSeparately() {
        val headers = StreamRequestHeaders.from(
            BehaviorHints(
                headers = mapOf(
                    "User-Agent" to "AddonAgent",
                    "X-Addon" to "kept",
                    "referer" to "https://addon.example/",
                ),
                proxyHeaders = ProxyHeaders(
                    request = mapOf(
                        "user-agent" to "ProxyAgent",
                        "Referer" to "https://origin.example/",
                    ),
                ),
            ),
        )
        assertEquals("ProxyAgent", headers.entries.single { it.key.equals("USER-AGENT", ignoreCase = true) }.value)
        assertEquals("kept", headers["X-Addon"])
        assertEquals(1, headers.keys.count { it.equals("referer", ignoreCase = true) })
        assertEquals("https://origin.example/", StreamRequestHeaders.referer(headers))
        assertEquals("", StreamRequestHeaders.referer(mapOf("Authorization" to "token")))
    }

    @Test
    fun duplicateStreamsMergeHeaderNamesCaseInsensitivelyAndKeepPrimaryValues() {
        val first = StreamParser.parsed(
            0,
            "Primary addon",
            RawStream(
                url = "https://example.com/watch.m3u8?token=same",
                behaviorHints = BehaviorHints(
                    headers = mapOf("X-Session" to "primary", "Referer" to "https://primary.example/"),
                ),
            ),
        )
        val duplicate = StreamParser.parsed(
            1,
            "Secondary addon",
            RawStream(
                url = "https://EXAMPLE.com:443/a/../watch.m3u8?token=same",
                behaviorHints = BehaviorHints(
                    headers = mapOf("x-session" to "secondary", "referer" to "https://secondary.example/"),
                ),
            ),
        )

        val result = StreamPipeline.process(listOf(first, duplicate), FilterSettings(), SortSettings())
        assertEquals(1, result.size)
        val headers = StreamRequestHeaders.from(result.single().raw.behaviorHints)
        assertEquals("primary", headers.entries.single { it.key.equals("x-session", ignoreCase = true) }.value)
        assertEquals(1, headers.keys.count { it.equals("referer", ignoreCase = true) })
        assertEquals("https://primary.example/", StreamRequestHeaders.referer(headers))
    }

    @Test
    fun streamUrlNormalizationPreservesSignedQueriesAndPlaybackFragments() {
        assertEquals(
            "https://example.com/video.mkv?token=first#t=12",
            StreamDeduplication.normalizeUrl("HTTPS://EXAMPLE.COM:443/a/../video.mkv?token=first#t=12"),
        )
        assertNotEquals(
            StreamDeduplication.normalizeUrl("https://example.com/video.mkv?token=first"),
            StreamDeduplication.normalizeUrl("https://example.com/video.mkv?token=second"),
        )
        assertNotEquals(
            StreamDeduplication.normalizeUrl("https://example.com/video.mkv#t=12"),
            StreamDeduplication.normalizeUrl("https://example.com/video.mkv#t=24"),
        )
    }

    @Test
    fun streamPipelineDeduplicatesEquivalentUrlsButKeepsDifferentSignedLinks() {
        val first = StreamParser.parsed(
            0, "First addon",
            RawStream(url = "HTTPS://EXAMPLE.COM:443/a/../video.mkv?token=first#t=12"),
        )
        val duplicate = StreamParser.parsed(
            1, "Second addon",
            RawStream(url = "https://example.com/video.mkv?token=first#t=12"),
        )
        val separateCredential = StreamParser.parsed(
            2, "Third addon",
            RawStream(url = "https://example.com/video.mkv?token=second#t=12"),
        )

        val result = StreamPipeline.process(
            listOf(first, duplicate, separateCredential),
            FilterSettings(),
            SortSettings(),
        )
        assertEquals(2, result.size)
        assertEquals("First addon", result.first().addonName)
    }

    @Test
    fun duplicateStreamsMergeSubtitlesAndMissingMetadata() {
        val first = StreamParser.parsed(
            0,
            "Primary addon",
            RawStream(
                name = "Example.Movie.1080p.WEB-DL",
                url = "https://example.com/watch.m3u8?token=private",
                subtitles = listOf(RawSubtitle(url = "https://subs.example/en.vtt", lang = "eng")),
            ),
        )
        val duplicate = StreamParser.parsed(
            1,
            "Secondary addon",
            RawStream(
                name = "Example.Movie.BENG",
                url = "https://EXAMPLE.com:443/a/../watch.m3u8?token=private",
                subtitles = listOf(RawSubtitle(url = "https://subs.example/bn.vtt", lang = "ben")),
                behaviorHints = BehaviorHints(filename = "Example.Movie.1080p.WEB-DL.BENG.mkv"),
            ),
        )

        val result = StreamPipeline.process(
            listOf(first, duplicate),
            FilterSettings(),
            SortSettings(),
        )

        assertEquals(1, result.size)
        assertEquals("Primary addon", result.single().addonName)
        assertEquals(2, result.single().raw.subtitles?.size)
        assertTrue("bn" in result.single().info.languages)
        assertTrue(result.single().raw.behaviorHints?.filename?.contains("BENG") == true)
    }

    @Test
    fun customCodecAndAudioPreferencesControlStreamRanking() {
        val avc = StreamParser.parsed(0, "AVC addon", RawStream(name = "Movie.1080p.WEB-DL.x264.AAC"))
        val hevc = StreamParser.parsed(1, "HEVC addon", RawStream(name = "Movie.1080p.WEB-DL.x265.DD+"))
        val av1 = StreamParser.parsed(2, "AV1 addon", RawStream(name = "Movie.1080p.WEB-DL.AV1.Atmos"))

        val codecOrder = StreamPipeline.process(
            listOf(avc, hevc, av1),
            FilterSettings(),
            SortSettings(preferCodecs = mutableListOf("AV1", "HEVC", "AVC")),
        )
        assertEquals(listOf("AV1", "HEVC", "AVC"), codecOrder.map { it.info.codec })

        val audioOrder = StreamPipeline.process(
            listOf(avc, hevc, av1),
            FilterSettings(),
            SortSettings(preferAudio = mutableListOf("Atmos", "DD+", "AAC")),
        )
        assertEquals(listOf("Atmos", "DD+", "AAC"), audioOrder.map { it.info.audio })
    }

    @Test
    fun detailedStreamLabelsShowDetectedLanguagesAndSubtitleTracks() {
        val parsed = StreamParser.parsed(
            0,
            "Test addon",
            RawStream(
                name = "Example.Movie.1080p.WEB-DL.ENG.BENG",
                url = "https://example.com/video.mkv",
                subtitles = listOf(
                    RawSubtitle(url = "https://subs.example/en.vtt", lang = "eng"),
                    RawSubtitle(url = "https://subs.example/bn.vtt", lang = "ben"),
                ),
            ),
        )
        val label = StreamLabels.build(parsed, detailed = true)
        assertTrue(label.contains("Lang BN/EN"))
        assertTrue(label.contains("Subs BN/EN"))
    }

    @Test
    fun streamDiagnosticsRecordOnlySafeCountsAndTimings() {
        val run = StreamDiagnostics.begin()
        StreamDiagnostics.record(run, "Example addon", "Streams", 125L, "OK", 4)
        StreamDiagnostics.record(run, "Unavailable addon", "Subtitles", 8000L, "TIMEOUT", 0)
        StreamDiagnostics.recordStage(run, "parse", 7L)
        StreamDiagnostics.recordStage(run, "pipeline", 3L)
        StreamDiagnostics.recordStage(run, "emission", 2L)
        StreamDiagnostics.finish(
            run,
            rawStreams = 4,
            addonSubtitles = 0,
            playableCandidates = 3,
            keptAfterFilters = 2,
            emittedLinks = 2,
        )

        val snapshot = StreamDiagnostics.latest()
        assertNotNull(snapshot)
        assertEquals(4, snapshot!!.rawStreams)
        assertEquals(3, snapshot.playableCandidates)
        assertEquals(2, snapshot.keptAfterFilters)
        assertEquals(2, snapshot.emittedLinks)
        assertEquals(2, snapshot.requests.size)
        assertEquals("TIMEOUT", snapshot.requests[1].status)
        assertEquals(7L, snapshot.parseWorkMs)
        assertEquals(3L, snapshot.pipelineMs)
        assertEquals(2L, snapshot.emissionMs)
    }

    @Test
    fun addonArrayParsingDistinguishesFailureFromEmptyResults() {
        assertEquals("EMPTY_BODY", Json.parseArrayDetailed<RawStream>(null, "streams").status)
        assertEquals("INVALID_JSON", Json.parseArrayDetailed<RawStream>("{broken", "streams").status)
        assertEquals("MISSING_ARRAY", Json.parseArrayDetailed<RawStream>("{\"streams\":{}}", "streams").status)
        assertEquals("EMPTY", Json.parseArrayDetailed<RawStream>("{\"streams\":[]}", "streams").status)
        assertEquals("OK", Json.parseArrayDetailed<RawStream>("{\"streams\":[{\"url\":\"https://example.com/a.m3u8\"}]}", "streams").status)
    }

    @Test
    fun visualStreamFormatterUsesChosenFieldsInChosenOrder() {
        val parsed = StreamParser.parsed(
            0,
            "Example Addon",
            RawStream(
                name = "Example.Movie.2026.2160p.WEB-DL.HDR10.HEVC.Atmos.BENG.ENG",
                title = "Example.Movie.2026.2160p.WEB-DL.HDR10.HEVC.Atmos.BENG.ENG",
                url = "https://example.com/movie.mkv",
                subtitles = listOf(
                    RawSubtitle(url = "https://subs.example/en.vtt", lang = "eng"),
                    RawSubtitle(url = "https://subs.example/bn.vtt", lang = "ben"),
                ),
                behaviorHints = BehaviorHints(
                    filename = "Example.Movie.2026.2160p.WEB-DL.HDR10.HEVC.Atmos.BENG.ENG.mkv",
                    videoSize = 1_500_000_000L,
                ),
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("addon", "quality", "codec", "language", "subtitles", "release"),
            separator = " | ",
            showLabels = true,
        )
        val label = StreamLabels.build(parsed, format)
        assertTrue(label.indexOf("Addon: Example Addon") < label.indexOf("Quality: 4K"))
        assertTrue(label.contains("Video codec: HEVC"))
        assertTrue(label.contains("Language: BN/EN"))
        assertTrue(label.contains("Subtitles: BN/EN"))
        assertTrue(label.contains("Release:"))
        assertFalse(label.contains("Source:"))
        assertFalse(label.contains("Audio:"))
    }

    @Test
    fun visualStreamFormatterSkipsUnavailableFieldsAndHasSafePreview() {
        val parsed = StreamParser.parsed(
            0, "Example Addon",
            RawStream(url = "https://example.com/video.mkv", name = "Plain title"),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("addon", "seeders", "hdr", "made_up_field"),
        )
        assertEquals("Example Addon", StreamLabels.build(parsed, format))
        val preview = StreamLabels.preview(format.copy(fields = mutableListOf("addon", "quality", "codec")))
        assertTrue(preview.contains("Example Addon"))
        assertTrue(preview.contains("4K"))
        assertTrue(preview.contains("HEVC"))
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
