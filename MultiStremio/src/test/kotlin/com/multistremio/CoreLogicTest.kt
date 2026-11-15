package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lagradost.cloudstream3.TvType

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
    fun normalizedLanguageDetectionMatchesThePublicDetector() {
        val release = "Example.Movie.1080p.WEB-DL.BENG.ENG.🇧🇩"
        assertEquals(
            Langs.detect(release),
            Langs.detectNormalized(release, release.lowercase(java.util.Locale.ROOT)),
        )
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
    fun duplicatePrimaryNormalHeadersBeatSecondaryProxyHeaders() {
        val primary = StreamParser.parsed(
            0,
            "Primary addon",
            RawStream(
                url = "https://example.com/cross-map.m3u8?sig=same",
                behaviorHints = BehaviorHints(
                    headers = mapOf("X-Session" to "primary", "Referer" to "https://primary.example/"),
                ),
            ),
        )
        val secondary = StreamParser.parsed(
            1,
            "Secondary addon",
            RawStream(
                url = "https://EXAMPLE.com:443/a/../cross-map.m3u8?sig=same",
                behaviorHints = BehaviorHints(
                    proxyHeaders = ProxyHeaders(
                        request = mapOf("x-session" to "secondary", "referer" to "https://secondary.example/"),
                    ),
                ),
            ),
        )

        val merged = StreamPipeline.process(listOf(primary, secondary), FilterSettings(), SortSettings()).single()
        val headers = StreamRequestHeaders.from(merged.raw.behaviorHints)
        assertEquals("primary", headers.entries.single { it.key.equals("x-session", ignoreCase = true) }.value)
        assertEquals("https://primary.example/", StreamRequestHeaders.referer(headers))
    }

    @Test
    fun dragInsertionMathSupportsTopMiddleAndBottomDropTargets() {
        val remainingCenters = listOf(30f, 80f, 130f)
        assertEquals(0, DragReorderMath.insertionIndex(remainingCenters, 10f))
        assertEquals(1, DragReorderMath.insertionIndex(remainingCenters, 55f))
        assertEquals(2, DragReorderMath.insertionIndex(remainingCenters, 120f))
        assertEquals(3, DragReorderMath.insertionIndex(remainingCenters, 180f))
        assertEquals(0, DragReorderMath.insertionIndex(emptyList(), 180f))
    }

    @Test
    fun magnetPreservesAnExplicitTorrentFileIndex() {
        val selected = Urls.magnet("abcdef123456", "Episode 2.mkv", emptyList(), 3)
        assertTrue(selected.contains("&index=3"))
        assertTrue(selected.startsWith("magnet:?xt=urn:btih:abcdef123456"))
        val defaultSelection = Urls.magnet("abcdef123456", null, emptyList())
        assertFalse(defaultSelection.contains("&index="))
        assertFalse(Urls.magnet("abcdef123456", null, emptyList(), -1).contains("&index="))
    }

    @Test
    fun externalOnlyTargetsDeduplicateWithoutLosingSignedUrlDifferences() {
        val firstYoutube = StreamParser.parsed(0, "First addon", RawStream(ytId = "AbC123"))
        val duplicateYoutube = StreamParser.parsed(1, "Second addon", RawStream(ytId = "AbC123"))
        val otherYoutube = StreamParser.parsed(2, "Third addon", RawStream(ytId = "AbC124"))
        assertEquals(
            StreamDeduplication.externalKey(firstYoutube),
            StreamDeduplication.externalKey(duplicateYoutube),
        )
        assertNotEquals(
            StreamDeduplication.externalKey(firstYoutube),
            StreamDeduplication.externalKey(otherYoutube),
        )

        val signedA = StreamParser.parsed(
            0, "First addon", RawStream(externalUrl = "https://example.com/watch?token=one"),
        )
        val signedB = StreamParser.parsed(
            1, "Second addon", RawStream(externalUrl = "https://example.com/watch?token=two"),
        )
        assertNotEquals(
            StreamDeduplication.externalKey(signedA),
            StreamDeduplication.externalKey(signedB),
        )
    }

    @Test
    fun invalidRegexFiltersAreDetectedBeforeTheyCanBeSaved() {
        assertEquals(
            listOf("/[/"),
            StreamFilter.invalidRegexPatterns(listOf("WEB-DL", "/[/", "/x26[45]/", "/[/")),
        )
        assertTrue(StreamFilter.invalidRegexPatterns(listOf("WEB-DL", "/x26[45]/")).isEmpty())
        assertTrue(StreamFilter.matchesKeyword("example release x265", "/x26[45]/"))
    }

    @Test
    fun regexFiltersRejectUnsafeAndUnsupportedExpressions() {
        assertEquals(
            listOf("/(?=web)web/", "/(a{10}){10}/", "/a{999999}/"),
            StreamFilter.invalidRegexPatterns(
                listOf("/(?=web)web/", "/(a{10}){10}/", "/a{999999}/"),
            ),
        )
        val tooLong = "/" + "a".repeat(257) + "/"
        assertEquals(listOf(tooLong), StreamFilter.invalidRegexPatterns(listOf(tooLong)))
    }

    @Test
    fun regexFiltersHandleBacktrackingShapedInputWithLinearEngine() {
        val hostileInput = "a".repeat(50_000) + "!"
        assertFalse(StreamFilter.matchesKeyword(hostileInput, "/(a+)+$/"))
    }

    @Test
    fun regexCacheHasAFixedUpperBound() {
        repeat(200) { index ->
            StreamFilter.matchesKeyword("no match", "/token$index/")
        }
        assertTrue(StreamFilter.regexCacheSizeForTests() <= 128)
    }

    @Test
    fun invalidLegacyRequireRegexDoesNotHideEveryStream() {
        val raw = RawStream(name = "Example.Movie.1080p.WEB-DL")
        val (info, text) = StreamParser.parse(raw)
        val parsed = ParsedStream(0, "Test addon", raw, text, info)
        assertTrue(
            StreamFilter.passes(
                parsed,
                FilterSettings(
                    allowedResolutions = mutableListOf(1080),
                    requireKeywords = mutableListOf("/[/"),
                ),
            ),
        )
    }

    @Test
    fun emptyResolutionSelectionMeansAnyResolution() {
        val raw = RawStream(name = "Example.Movie.1080p.WEB-DL")
        val (info, text) = StreamParser.parse(raw)
        val parsed = ParsedStream(0, "Test addon", raw, text, info)
        assertTrue(StreamFilter.passes(parsed, FilterSettings(allowedResolutions = mutableListOf())))
    }

    @Test
    fun catalogOrderSerialNumbersReorderRowsOnlyWithinTheirAddonSection() {
        val original = listOf("a1", "a2", "b1", "a3", "b2")
        val reordered = CatalogOrderMath.reorderGroup(
            currentOrder = original,
            groupKeys = listOf("a1", "a2", "a3"),
            positions = mapOf("a1" to 3, "a2" to 1, "a3" to 2),
        )

        assertEquals(listOf("a2", "a3", "b1", "a1", "b2"), reordered)
    }

    @Test
    fun catalogOrderSerialNumbersAllowDuplicatePositionsWithStableTieOrdering() {
        val original = listOf("first", "second", "third")
        assertEquals(
            listOf("second", "first", "third"),
            CatalogOrderMath.reorderGroup(
                currentOrder = original,
                groupKeys = original,
                positions = mapOf("first" to 20, "second" to 10, "third" to 20),
            ),
        )
        assertEquals(
            listOf("second", "third", "first"),
            CatalogOrderMath.reorderGroup(
                currentOrder = original,
                groupKeys = original,
                positions = mapOf("first" to 30, "second" to 10, "third" to 20),
            ),
        )
    }

    @Test
    fun catalogOrderSerialNumbersStillRejectBlankOrNonPositivePositions() {
        val original = listOf("first", "second", "third")
        assertEquals(
            null,
            CatalogOrderMath.reorderGroup(original, original, mapOf("first" to 0, "second" to 1, "third" to 2)),
        )
        assertEquals(
            null,
            CatalogOrderMath.reorderGroup(original, original, mapOf("first" to -1, "second" to 1, "third" to 2)),
        )
        assertEquals(
            null,
            CatalogOrderMath.reorderGroup(original, original, mapOf("first" to 1, "second" to 2)),
        )
        assertEquals(
            null,
            CatalogOrderMath.reorderGroup(original, listOf("first", "missing"), mapOf("first" to 1, "missing" to 2)),
        )
    }

    @Test
    fun importedRowsAppendAfterExistingEffectiveOrder() {
        val currentOrder = listOf("stremio-a", "stremio-b", "external-existing")
        assertEquals(
            listOf("stremio-a", "stremio-b", "external-existing", "external-new"),
            CatalogOrderMath.append(currentOrder, "external-new"),
        )
        assertEquals(currentOrder, CatalogOrderMath.append(currentOrder, "stremio-b"))
    }

    @Test
    fun addonHealthClassificationDistinguishesTransportAndManifestFailures() {
        val cases = listOf(
            SimpleHttpStatus.TIMEOUT to "Connection timed out after 8 seconds",
            SimpleHttpStatus.DNS_ERROR to "Could not resolve the addon host",
            SimpleHttpStatus.INVALID_URL to "Addon URL is invalid",
            SimpleHttpStatus.BODY_TOO_LARGE to "Manifest response exceeds the 2 MB safety limit",
            SimpleHttpStatus.NETWORK_ERROR to "Network connection failed",
            SimpleHttpStatus.EMPTY_BODY to "Server responded but returned an empty manifest",
        )
        cases.forEach { (status, detail) ->
            val result = AddonHealthClassifier.assess(SimpleHttpResult(null, status), null)
            assertFalse(result.success)
            assertEquals(detail, result.detail)
        }

        val http = AddonHealthClassifier.assess(
            SimpleHttpResult(null, SimpleHttpStatus.HTTP_ERROR, 503),
            null,
        )
        assertFalse(http.success)
        assertEquals("Server returned HTTP 503", http.detail)

        val malformed = AddonHealthClassifier.assess(
            SimpleHttpResult("{broken", SimpleHttpStatus.OK, 200),
            null,
        )
        assertFalse(malformed.success)
        assertEquals("Server response is not valid JSON", malformed.detail)

        val invalidManifest = AddonHealthClassifier.assess(
            SimpleHttpResult("{}", SimpleHttpStatus.OK, 200),
            Manifest(name = "Incomplete addon", resources = emptyList()),
        )
        assertFalse(invalidManifest.success)
        assertEquals("Manifest must include a name and at least one resource", invalidManifest.detail)

        val validManifest = AddonHealthClassifier.assess(
            SimpleHttpResult("{\"name\":\"Working addon\",\"resources\":[\"stream\"]}", SimpleHttpStatus.OK, 200),
            Manifest(name = "Working addon", resources = listOf("stream")),
        )
        assertTrue(validManifest.success)
    }

    @Test
    fun stremioAudiobookTypesMapToCloudStreamAudioBook() {
        listOf("audiobook", "AudioBook", "audio_book", "audio-book", "book", "books").forEach { type ->
            assertEquals(TvType.AudioBook, StremioMediaTypes.toCloudStream(type))
        }

        assertEquals(TvType.Movie, StremioMediaTypes.toCloudStream("movie"))
        assertEquals(TvType.TvSeries, StremioMediaTypes.toCloudStream("series"))
        assertEquals(TvType.Others, StremioMediaTypes.toCloudStream("unrecognized-type"))
    }

    @Test
    fun catalogResourceDeclarationsGateHomepageAndSearchTypes() {
        val stringResource = Manifest(
            resources = listOf("catalog"),
            types = listOf("movie"),
            idPrefixes = listOf("tt"),
        )
        assertTrue(stringResource.supports("catalog", "movie", null))
        assertFalse(stringResource.supports("catalog", "series", null))

        val scopedResource = Manifest(
            resources = listOf(mapOf("name" to "catalog", "types" to listOf("series"))),
            types = listOf("movie"),
        )
        assertTrue(scopedResource.supports("catalog", "series", null))
        assertFalse(scopedResource.supports("catalog", "movie", null))

        val streamOnly = Manifest(resources = listOf("stream"), types = listOf("movie"))
        assertFalse(streamOnly.supports("catalog", "movie", null))
    }

    @Test
    fun legacySettingsJsonUsesDefaultsForNewSettings() {
        val settings = Json.parse<Settings>("""{"addons":[],"filter":{},"sort":{}}""")
        assertNotNull(settings)
        assertFalse(settings!!.streamFormat.enabled)
        assertTrue(settings.streamFormat.fields.isNotEmpty())
        assertTrue(settings.sort.preferCodecs.isEmpty())
        assertTrue(settings.sort.preferAudio.isEmpty())
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
    fun directUrlIdentityTakesPriorityOverAnAccompanyingTorrentHash() {
        val first = StreamParser.parsed(
            0, "First addon",
            RawStream(url = "https://example.com/one.mkv", infoHash = "samehash", fileIdx = 0),
        )
        val second = StreamParser.parsed(
            1, "Second addon",
            RawStream(url = "https://example.com/two.mkv", infoHash = "samehash", fileIdx = 0),
        )
        val duplicate = StreamParser.parsed(
            2, "Third addon",
            RawStream(url = "https://example.com/one.mkv", infoHash = "differenthash", fileIdx = 4),
        )
        assertNotEquals(StreamDeduplication.key(first), StreamDeduplication.key(second))
        assertEquals(StreamDeduplication.key(first), StreamDeduplication.key(duplicate))

        val torrentFileOne = StreamParser.parsed(
            3, "Torrent one",
            RawStream(infoHash = "samehash", fileIdx = 0),
        )
        val torrentFileTwo = StreamParser.parsed(
            4, "Torrent two",
            RawStream(infoHash = "samehash", fileIdx = 1),
        )
        assertNotEquals(StreamDeduplication.key(torrentFileOne), StreamDeduplication.key(torrentFileTwo))
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
    fun unusableSearchMetadataDoesNotHideAValidDuplicateFromAnotherAddon() {
        val unusable = Triple(
            "addon-a",
            "movie",
            Meta(id = "tt12345", name = null, type = "movie"),
        )
        val valid = Triple(
            "addon-b",
            "movie",
            Meta(id = "tt12345", name = "A Valid Title", type = "movie"),
        )
        val duplicate = Triple(
            "addon-c",
            "movie",
            Meta(id = "tt12345", name = "Duplicate Title", type = "movie"),
        )
        val otherType = Triple(
            "addon-d",
            "series",
            Meta(id = "tt12345", name = "Same ID, Different Type", type = "series"),
        )

        val unique = SearchMetaDeduplication.unique(listOf(unusable, valid, duplicate, otherType))
        assertEquals(listOf("addon-b", "addon-d"), unique.map { it.first })
    }

    @Test
    fun preferredLanguageListOrderControlsStreamRanking() {
        // The English stream has the earlier addon index, so only the explicit preference order
        // should decide which language appears first.
        val english = StreamParser.parsed(
            0, "English addon",
            RawStream(name = "Example.Movie.1080p.WEB-DL.ENG"),
        )
        val bangla = StreamParser.parsed(
            1, "Bangla addon",
            RawStream(name = "Example.Movie.1080p.WEB-DL.BENG"),
        )

        val banglaFirst = StreamPipeline.process(
            listOf(english, bangla),
            FilterSettings(),
            SortSettings(preferLanguages = mutableListOf("bn", "en")),
        )
        assertEquals(listOf("bn", "en"), banglaFirst.map { it.info.languages.single() })

        val englishFirst = StreamPipeline.process(
            listOf(english, bangla),
            FilterSettings(),
            SortSettings(preferLanguages = mutableListOf("English", "Bengali")),
        )
        assertEquals(listOf("en", "bn"), englishFirst.map { it.info.languages.single() })
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
    fun subtitleCompletionIsNotRecordedWhenNoSubtitleAddonWasQueried() {
        val run = StreamDiagnostics.begin()
        StreamDiagnostics.markSubtitleCompletion(run, addonCount = 0)
        StreamDiagnostics.finish(run, 0, 0, 0, 0, 0)

        assertNull(StreamDiagnostics.latest()!!.timeToSubtitleCompletionMs)
    }

    @Test
    fun subtitleCompletionIsRecordedWhenSubtitleAddonsWereQueried() {
        val run = StreamDiagnostics.begin()
        StreamDiagnostics.markSubtitleCompletion(run, addonCount = 1)
        StreamDiagnostics.finish(run, 0, 1, 0, 0, 0)

        assertNotNull(StreamDiagnostics.latest()!!.timeToSubtitleCompletionMs)
    }

    @Test
    fun olderSlowLookupCannotReplaceNewerDiagnosticsSnapshot() {
        val older = StreamDiagnostics.begin()
        val newer = StreamDiagnostics.begin()

        StreamDiagnostics.finish(newer, 20, 0, 18, 16, 16)
        StreamDiagnostics.finish(older, 10, 0, 8, 6, 6)

        assertEquals(20, StreamDiagnostics.latest()!!.rawStreams)
        assertEquals(16, StreamDiagnostics.latest()!!.emittedLinks)
    }

    @Test
    fun diagnosticAddonNamesRedactUrlsCredentialsAndNewlines() {
        assertEquals(
            "Private addon [URL redacted]",
            StreamDiagnostics.safeAddonName("Private addon https://example.com/manifest?token=secret"),
        )
        assertEquals(
            "Addon?token=[redacted]&region=bd",
            StreamDiagnostics.safeAddonName("Addon?token=secret&region=bd"),
        )
        assertEquals("Addon [URL redacted]", StreamDiagnostics.safeAddonName("Addon stremio://example.com/config?access_token=private"))
        assertEquals("api_key=[redacted]", StreamDiagnostics.safeAddonName("api_key=private"))
        assertEquals("First Second", StreamDiagnostics.safeAddonName("First\nSecond"))
    }

    @Test
    fun addonArrayParsingSkipsNullItemsAndReportsPartialResults() {
        val parsed = Json.parseArrayDetailed<RawStream>(
            """{"streams":[null,{"url":"https://example.com/video.mkv"}]}""",
            "streams",
        )
        assertEquals("PARTIAL", parsed.status)
        assertEquals(1, parsed.items.size)
        assertEquals("https://example.com/video.mkv", parsed.items.single().url)
    }

    @Test
    fun addonArrayParsingRejectsAnArrayContainingOnlyNullItems() {
        val parsed = Json.parseArrayDetailed<RawStream>(
            """{"streams":[null,null]}""",
            "streams",
        )
        assertEquals("INVALID_ITEMS", parsed.status)
        assertTrue(parsed.items.isEmpty())
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
    fun visualStreamFormatterExtractsCncVerseInnerProviderSeparatelyFromReleaseSource() {
        val parsed = StreamParser.parsed(
            0,
            "CNCVerse",
            RawStream(
                name = "🌀 CNCVerse ❄️ 4K • MovieBoxIN",
                title = "Example.Movie.2026.2160p.WEB-DL\n🛰️ Source: MovieBoxIN • HubCloud",
                url = "https://example.com/movie.mkv",
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("addon", "provider", "source"),
            separator = " | ",
            showLabels = true,
        )

        val label = StreamLabels.build(parsed, format)
        assertTrue(label.contains("Addon: CNCVerse"))
        assertTrue(label.contains("Provider: MovieBoxIN"))
        assertTrue(label.contains("Source: WEB-DL"))
        assertFalse(label.contains("HubCloud"))
    }

    @Test
    fun visualStreamFormatterExtractsProviderFromCncVerseNameWhenDescriptionHasNoSourceMarker() {
        val parsed = StreamParser.parsed(
            0,
            "CNCVerse",
            RawStream(
                name = "🌀 CNCVerse | 1080p | VegaMoviesProvider",
                url = "https://example.com/movie.mkv",
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("provider"),
            showLabels = true,
        )

        assertEquals("Provider: VegaMoviesProvider", StreamLabels.build(parsed, format))
    }

    @Test
    fun visualStreamFormatterExtractsPenguPlayProviderFromDefaultNameTemplate() {
        val parsed = StreamParser.parsed(
            0,
            "PenguPlay",
            RawStream(
                name = "🐧 PenguPlay 1080p • MovieBox",
                url = "https://example.com/movie.mkv",
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("provider"),
            showLabels = true,
        )

        assertEquals("Provider: MovieBox", StreamLabels.build(parsed, format))
    }

    @Test
    fun visualStreamFormatterReadsSourceMarkerFromStreamName() {
        val parsed = StreamParser.parsed(
            0,
            "PenguPlay",
            RawStream(
                name = "🐧 PenguPlay 1080p • 🛰️ Source: VegaMovies",
                url = "https://example.com/movie.mkv",
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("provider"),
            showLabels = true,
        )

        assertEquals("Provider: VegaMovies", StreamLabels.build(parsed, format))
    }

    @Test
    fun providerExtractionSupportsGenericAddonMetadataMarkers() {
        val samples = listOf(
            RawStream(description = "Provider: MovieBox", url = "https://example.com/a.mkv"),
            RawStream(title = "Indexer - Torrentio", url = "https://example.com/b.mkv"),
            RawStream(name = "HDHub | 1080p | Vega Movies", url = "https://example.com/c.mkv"),
            RawStream(name = "4K • MovieBoxIN", url = "https://example.com/d.mkv"),
            RawStream(name = "DoodStreamProvider", url = "https://example.com/e.mkv"),
        )
        val addonNames = listOf("Generic Addon", "Generic Addon", "HDHub", "Generic Addon", "Generic Addon")
        val expected = listOf("MovieBox", "Torrentio", "Vega Movies", "MovieBoxIN", "DoodStreamProvider")
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("provider"),
            showLabels = true,
        )

        samples.forEachIndexed { index, raw ->
            val parsed = StreamParser.parsed(0, addonNames[index], raw)
            assertEquals("Provider: " + expected[index], StreamLabels.build(parsed, format))
        }
    }

    @Test
    fun providerExtractionRejectsTechnicalSourceLabelsAndOrdinaryTitles() {
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("provider"),
            showLabels = true,
        )
        val technicalSource = StreamParser.parsed(
            0, "Generic Addon",
            RawStream(description = "Source: WEB-DL", url = "https://example.com/a.mkv"),
        )
        val ordinaryTitle = StreamParser.parsed(
            0, "Generic Addon",
            RawStream(name = "The Batman", url = "https://example.com/b.mkv"),
        )
        val ordinarySegmentedTitle = StreamParser.parsed(
            0, "Generic Addon",
            RawStream(name = "The Batman • 1080p", url = "https://example.com/c.mkv"),
        )

        assertFalse(StreamLabels.build(technicalSource, format).contains("Provider:"))
        assertFalse(StreamLabels.build(ordinaryTitle, format).contains("Provider:"))
        assertFalse(StreamLabels.build(ordinarySegmentedTitle, format).contains("Provider:"))
    }

    @Test
    fun visualFormatterDoesNotRepeatResolutionInsideReleaseName() {
        val parsed = StreamParser.parsed(
            0,
            "Test Addon",
            RawStream(
                name = "1080p",
                url = "https://example.com/movie.mkv",
                behaviorHints = BehaviorHints(filename = "The.Movie.2026.1080p.WEB-DL.HDR10.HEVC.mkv"),
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("quality", "release"),
            separator = " | ",
            showLabels = true,
        )

        assertEquals(
            "Quality: 1080p | Release: The.Movie.2026.WEB-DL.HDR10.HEVC.mkv",
            StreamLabels.build(parsed, format),
        )
    }

    @Test
    fun visualFormatterKeepsResolutionInReleaseWhenQualityFieldIsNotSelected() {
        val parsed = StreamParser.parsed(
            0,
            "Test Addon",
            RawStream(
                url = "https://example.com/movie.mkv",
                behaviorHints = BehaviorHints(filename = "The.Movie.2026.1080p.WEB-DL.mkv"),
            ),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("release"),
            showLabels = true,
        )

        assertEquals("Release: The.Movie.2026.1080p.WEB-DL.mkv", StreamLabels.build(parsed, format))
    }

    @Test
    fun defaultDetailedFormatterDoesNotRepeatResolutionInsideReleaseName() {
        val parsed = StreamParser.parsed(
            0,
            "Test Addon",
            RawStream(
                url = "https://example.com/movie.mkv",
                behaviorHints = BehaviorHints(filename = "The.Movie.2026.4K.WEB-DL.mkv"),
            ),
        )

        val label = StreamLabels.build(parsed, detailed = true)
        assertTrue(label.contains("4K"))
        assertTrue(label.endsWith("The.Movie.2026.WEB-DL.mkv"))
    }

    @Test
    fun visualStreamFormatterDoesNotTreatQualityAsAProviderName() {
        val parsed = StreamParser.parsed(
            0,
            "CNCVerse",
            RawStream(name = "1080p", url = "https://example.com/movie.mkv"),
        )
        val format = StreamFormatSettings(
            enabled = true,
            fields = mutableListOf("provider"),
            showLabels = true,
        )

        assertFalse(StreamLabels.build(parsed, format).contains("Provider:"))
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
    fun backupImportAcceptsUtf8BomFromTextEditors() {
        val payload = SettingsBackupManager.export(sampleSettings(), includeAddonUrls = true)
        val parsed = SettingsBackupManager.parse("\uFEFF$payload")

        assertNotNull(parsed)
        assertTrue(parsed!!.includesAddonUrls)
        assertEquals("Private addon", parsed.settings!!.addons.single().name)
    }

    @Test
    fun arbitraryJsonCannotBeImportedAsAnEmptyLegacyBackup() {
        assertEquals(null, SettingsBackupManager.parse("{}"))
        assertEquals(null, SettingsBackupManager.parse("""{"unrelated":"value"}"""))
    }

    @Test
    fun catalogOrderOnlyBackupIsStillClassifiedAsSourceSensitive() {
        val forgedEnvelope = SettingsBackup(
            includesAddonUrls = false,
            settings = Settings(catalogOrder = mutableListOf("hashed-addon|movie|top")),
        )
        val parsed = SettingsBackupManager.parse(Json.toJson(forgedEnvelope))
        assertNotNull(parsed)
        assertTrue(parsed!!.includesAddonUrls)
    }

    @Test
    fun backupPrivacyFlagCannotHideEmbeddedAddonOrExtensionSources() {
        val forgedEnvelope = SettingsBackup(
            includesAddonUrls = false,
            settings = sampleSettings(),
        )
        val parsed = SettingsBackupManager.parse(Json.toJson(forgedEnvelope))
        assertNotNull(parsed)
        assertTrue(parsed!!.includesAddonUrls)

        val importedExtensionOnly = SettingsBackup(
            includesAddonUrls = false,
            settings = Settings(
                externalRows = mutableListOf(
                    ExternalRowConfig(
                        key = "external:private",
                        sourcePlugin = "https://private-plugin.example/repo.json",
                    ),
                ),
            ),
        )
        val parsedExtensionOnly = SettingsBackupManager.parse(Json.toJson(importedExtensionOnly))
        assertNotNull(parsedExtensionOnly)
        assertTrue(parsedExtensionOnly!!.includesAddonUrls)
    }

    @Test
    fun addonEnabledStateUpdatesTheTargetSettingsInstance() {
        val settings = Settings(
            addons = mutableListOf(
                AddonConfig(id = "first", enabled = true),
                AddonConfig(id = "second", enabled = true),
            ),
        )
        val menuSnapshot = settings.snapshot()

        assertTrue(settings.setAddonEnabled("first", false))
        assertFalse(settings.addons.first { it.id == "first" }.enabled)
        assertTrue(settings.addons.first { it.id == "second" }.enabled)
        assertTrue(menuSnapshot.addons.first { it.id == "first" }.enabled)
        assertFalse(settings.setAddonEnabled("missing", false))
    }

    @Test
    fun removingAddonAlsoRemovesOnlyItsHomepageOrderAndCustomNames() {
        val settings = Settings(
            addons = mutableListOf(
                AddonConfig(id = "addon-a"),
                AddonConfig(id = "addon-b"),
            ),
            catalogOrder = mutableListOf(
                "addon-a|movie|top",
                "addon-a|series|popular",
                "addon-b|movie|top",
                "external:row-1",
            ),
            externalRows = mutableListOf(ExternalRowConfig(key = "external:row-1")),
            customCatalogNames = mutableMapOf(
                "addon-a|movie|top" to "A custom title",
                "addon-b|movie|top" to "B custom title",
                "external:row-1" to "Imported title",
            ),
        )

        assertTrue(settings.removeAddon("addon-a"))
        assertEquals(listOf("addon-b"), settings.addons.map { it.id })
        assertEquals(listOf("addon-b|movie|top", "external:row-1"), settings.catalogOrder)
        assertEquals(setOf("addon-b|movie|top", "external:row-1"), settings.customCatalogNames.keys)
        assertEquals(listOf("external:row-1"), settings.externalRows.map { it.key })
        assertFalse(settings.removeAddon("missing"))
    }

    @Test
    fun removingImportedHomepageRowDoesNotRemoveItsSourceExtensionOrOtherRows() {
        val settings = Settings(
            externalRows = mutableListOf(
                ExternalRowConfig(key = "external:one"),
                ExternalRowConfig(key = "external:two"),
            ),
            catalogOrder = mutableListOf("external:one", "external:two", "addon|movie|top"),
            customCatalogNames = mutableMapOf(
                "external:one" to "First row",
                "external:two" to "Second row",
                "addon|movie|top" to "Catalog title",
            ),
        )

        assertTrue(settings.removeExternalHomeRow("external:one"))
        assertEquals(listOf("external:two"), settings.externalRows.map { it.key })
        assertEquals(listOf("external:two", "addon|movie|top"), settings.catalogOrder)
        assertEquals(setOf("external:two", "addon|movie|top"), settings.customCatalogNames.keys)
        assertFalse(settings.removeExternalHomeRow("missing"))
    }

    @Test
    fun settingsSnapshotsDoNotShareMutableNestedCollections() {
        val original = Settings(
            addons = mutableListOf(AddonConfig(
                id = "addon",
                url = "https://example.com",
                hiddenCatalogs = mutableListOf("movie/top"),
            )),
            catalogOrder = mutableListOf("addon|movie|top"),
            externalRows = mutableListOf(ExternalRowConfig(key = "external:one", rowName = "Original")),
            filter = FilterSettings(
                allowedResolutions = mutableListOf(1080),
                excludeKeywords = mutableListOf("CAM"),
            ),
            sort = SortSettings(preferLanguages = mutableListOf("bn", "en")),
            subtitleLanguages = mutableListOf("bn"),
            streamFormat = StreamFormatSettings(fields = mutableListOf("quality")),
        )
        val snapshot = original.snapshot()

        snapshot.addons.first().hiddenCatalogs += "series/top"
        snapshot.catalogOrder += "addon|series/top"
        snapshot.externalRows.first().rowName = "Changed"
        snapshot.filter.allowedResolutions += 720
        snapshot.filter.excludeKeywords += "TS"
        snapshot.sort.preferLanguages.reverse()
        snapshot.subtitleLanguages += "en"
        snapshot.streamFormat.fields += "codec"

        assertEquals(listOf("movie/top"), original.addons.first().hiddenCatalogs)
        assertEquals(listOf("addon|movie|top"), original.catalogOrder)
        assertEquals("Original", original.externalRows.first().rowName)
        assertEquals(listOf(1080), original.filter.allowedResolutions)
        assertEquals(listOf("CAM"), original.filter.excludeKeywords)
        assertEquals(listOf("bn", "en"), original.sort.preferLanguages)
        assertEquals(listOf("bn"), original.subtitleLanguages)
        assertEquals(listOf("quality"), original.streamFormat.fields)
        assertEquals(original.addons.first().manifest, snapshot.addons.first().manifest)
    }

    @Test
    fun importedSettingsAreSanitizedBeforeUse() {
        val settings = Settings(
            addons = mutableListOf(
                AddonConfig(url = "https://example.com/addon"),
                AddonConfig(url = "https://example.com/addon"),
                AddonConfig(url = ""),
            ),
            filter = FilterSettings(
                allowedResolutions = mutableListOf(999, 1080, 1080, -1),
                hevcMode = 99,
                hdrMode = -1,
                minSizeMb = -10,
                maxSizeGb = -5,
                minSeeders = -1,
                maxPerAddon = -2,
                maxTotal = -3,
            ),
            sort = SortSettings(preset = "REMOVED_PRESET"),
            timeoutSec = 999,
            streamFormat = StreamFormatSettings(
                fields = mutableListOf("unknown-field", "quality", "quality"),
                separator = "invalid separator",
            ),
        ).sanitized()

        assertEquals(180, settings.timeoutSec)
        assertEquals(listOf(1080), settings.filter.allowedResolutions)
        assertEquals(MODE_ONLY, settings.filter.hevcMode)
        assertEquals(MODE_ANY, settings.filter.hdrMode)
        assertEquals(0, settings.filter.minSizeMb)
        assertEquals(0, settings.filter.maxSizeGb)
        assertEquals(0, settings.filter.minSeeders)
        assertEquals(0, settings.filter.maxPerAddon)
        assertEquals(0, settings.filter.maxTotal)
        assertEquals(SortPreset.BEST_QUALITY.name, settings.sort.preset)
        assertEquals(listOf("quality"), settings.streamFormat.fields)
        assertEquals(" · ", settings.streamFormat.separator)
        assertEquals(1, settings.addons.size)
        assertFalse(settings.addons.single().id.isBlank())
    }

    @Test
    fun malformedBackupNestedSettingsAreRejectedWithoutThrowing() {
        val malformedEnvelope = """{"format":"multistremio-backup","version":1,"settings":{"addons":[],"filter":null}}"""
        val malformedLegacy = """{"addons":[],"sort":null}"""
        assertEquals(null, SettingsBackupManager.parse(malformedEnvelope))
        assertEquals(null, SettingsBackupManager.parse(malformedLegacy))
    }

    @Test
    fun validBackupValuesAreNormalizedDuringRestoreParsing() {
        val backup = SettingsBackup(
            settings = Settings(timeoutSec = 1, filter = FilterSettings(minSeeders = -1)),
        )
        val parsed = SettingsBackupManager.parse(Json.toJson(backup))
        assertNotNull(parsed)
        assertEquals(5, parsed!!.settings!!.timeoutSec)
        assertEquals(0, parsed.settings!!.filter.minSeeders)
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

    @Test
    fun globalCatalogOrderCanInterleaveRowsFromDifferentAddonsAndExtensions() {
        val original = listOf("addon-a-movie", "addon-a-series", "addon-b-movie", "external-row")
        val reordered = CatalogOrderMath.reorderAll(
            currentOrder = original,
            rowKeys = original,
            positions = mapOf(
                "addon-a-movie" to 1,
                "addon-a-series" to 3,
                "addon-b-movie" to 2,
                "external-row" to 4,
            ),
        )

        assertEquals(
            listOf("addon-a-movie", "addon-b-movie", "addon-a-series", "external-row"),
            reordered,
        )
    }

    @Test
    fun globalCatalogOrderKeepsCurrentRelativeOrderWhenPositionsAreTied() {
        val original = listOf("addon-a-first", "addon-b-first", "addon-a-second", "addon-b-second")
        val reordered = CatalogOrderMath.reorderAll(
            currentOrder = original,
            rowKeys = original,
            positions = mapOf(
                "addon-a-first" to 1,
                "addon-b-first" to 2,
                "addon-a-second" to 2,
                "addon-b-second" to 3,
            ),
        )

        assertEquals(
            listOf("addon-a-first", "addon-b-first", "addon-a-second", "addon-b-second"),
            reordered,
        )
    }

    @Test
    fun globalCatalogOrderRejectsInvalidPositionsAndKeepsUnavailableSavedRows() {
        val original = listOf("addon-a-first", "addon-a-second", "addon-b-first", "stale-row")
        assertEquals(
            listOf("addon-a-second", "addon-b-first", "addon-a-first", "stale-row"),
            CatalogOrderMath.reorderAll(
                currentOrder = original,
                rowKeys = listOf("addon-a-first", "addon-a-second", "addon-b-first"),
                positions = mapOf(
                    "addon-a-first" to 3,
                    "addon-a-second" to 1,
                    "addon-b-first" to 2,
                ),
            ),
        )
        assertNull(CatalogOrderMath.reorderAll(
            original,
            listOf("addon-a-first", "addon-a-second"),
            mapOf("addon-a-first" to 0, "addon-a-second" to 2),
        ))
        assertNull(CatalogOrderMath.reorderAll(
            original,
            listOf("addon-a-first", "addon-a-second"),
            mapOf("addon-a-first" to 1),
        ))
        assertNull(CatalogOrderMath.reorderAll(
            original,
            listOf("addon-a-first", "addon-a-first"),
            mapOf("addon-a-first" to 1),
        ))
    }

    @Test
    fun movingHomepageRowToEarlierPositionShiftsEveryFollowingRowDown() {
        val original = (1..50).map { "row-$it" }

        val moved = CatalogOrderMath.moveToPosition(
            currentOrder = original,
            rowKey = "row-50",
            requestedPosition = 5,
        )

        assertNotNull(moved)
        assertEquals(
            listOf("row-1", "row-2", "row-3", "row-4", "row-50", "row-5", "row-6"),
            moved!!.take(7),
        )
        assertEquals("row-49", moved.last())
        assertEquals(50, moved.size)
        assertEquals(50, moved.toSet().size)
    }

    @Test
    fun movingHomepageRowToLaterPositionShiftsRowsUpAndRenumbersUniquely() {
        val original = listOf("row-1", "row-2", "row-3", "row-4", "row-5", "row-6")

        val moved = CatalogOrderMath.moveToPosition(original, "row-2", 5)

        assertEquals(
            listOf("row-1", "row-3", "row-4", "row-5", "row-2", "row-6"),
            moved,
        )
    }

    @Test
    fun homepageRowInsertionRejectsUnknownRowsAndOutOfRangePositions() {
        val original = listOf("first", "second", "third")

        assertNull(CatalogOrderMath.moveToPosition(original, "missing", 2))
        assertNull(CatalogOrderMath.moveToPosition(original, "first", 0))
        assertNull(CatalogOrderMath.moveToPosition(original, "first", 4))
        assertNull(CatalogOrderMath.moveToPosition(listOf("same", "same"), "same", 1))
    }

    @Test
    fun streamDiagnosticsIncludeHttpAndSafeFailureClassWithoutRequestUrls() {
        val run = StreamDiagnostics.begin()
        StreamDiagnostics.record(
            run,
            "Private addon https://example.com/manifest?token=secret",
            "Streams",
            300L,
            "HTTP_ERROR",
            0,
            httpCode = 403,
        )
        StreamDiagnostics.record(
            run,
            "Unavailable addon",
            "Streams",
            30000L,
            "TIMEOUT",
            0,
            errorType = "SocketTimeoutException",
        )
        StreamDiagnostics.finish(run, 0, 0, 0, 0, 0)

        val requests = StreamDiagnostics.latest()!!.requests
        assertEquals(403, requests.first().httpCode ?: -1)
        assertEquals("HTTP_ERROR (HTTP 403)", requests.first().displayStatus())
        assertEquals("SocketTimeoutException", requests.last().errorType)
        assertTrue(requests.first().addonName.contains("[URL redacted]"))
        assertFalse(requests.first().addonName.contains("secret"))
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
