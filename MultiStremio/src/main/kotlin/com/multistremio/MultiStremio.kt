package com.multistremio

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

/** Use a fresh request gate for independent fan-outs such as search and metadata fallback. */
private suspend fun <T, R> List<T>.boundedAmap(
    maxConcurrent: Int = 8,
    transform: suspend (T) -> R,
): List<R> = boundedConcurrentMap(Semaphore(maxConcurrent.coerceAtLeast(1)), transform)

/** Share one gate across related branches so unused slots can be taken by other addon requests. */
private suspend fun <T, R> List<T>.boundedAmap(
    semaphore: Semaphore,
    transform: suspend (T) -> R,
): List<R> = boundedConcurrentMap(semaphore, transform)

/**
 * One CloudStream provider that fronts any number of Stremio addons.
 * Addons, filters and sorting are configured in the plugin settings (see SettingsUi).
 */
class MultiStremio : MainAPI() {
    override var name = "MultiStremio"
    override var mainUrl = Config.CINEMETA_URL // never requested directly; only needed by the API
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live, TvType.Others)

    /** Running item count per catalog, used as Stremio's `skip` for the next page. */
    private val skipOffsets = CatalogOffsetCache()

    private val noAddonsMessage =
        "No addons configured. Open Settings > Extensions > MultiStremio (gear icon) and add an addon."

    // Row visibility and ordering are shared with Settings > Catalog rows.
    // External extension providers are scanned on a worker thread and served from a short-lived cache.
    override val mainPage: List<MainPageData>
        get() {
            val settings = Config.snapshot()
            val installedExternalRows = ExternalHomeRowsCache.cachedOrRefresh()
            val rows = HomeRows.resolve(settings, installedExternalRows) { Config.manifestOf(it) }
                .asSequence()
                .filter { it.visibleOnHome }
                .mapNotNull { homeRow ->
                    val external = homeRow.external
                    if (external != null) {
                        val installed = homeRow.installedExternal ?: return@mapNotNull null
                        homeRow.key to MainPageData(
                            HomeRows.homeTitle(homeRow, settings),
                            Json.toJson(ExternalPageRef(homeRow.key)),
                            installed.row.horizontalImages,
                        )
                    } else {
                        val addon = homeRow.addon ?: return@mapNotNull null
                        val type = homeRow.type ?: return@mapNotNull null
                        val id = homeRow.catalogId ?: return@mapNotNull null
                        homeRow.key to MainPageData(
                            HomeRows.homeTitle(homeRow, settings),
                            Json.toJson(CatalogRef(addon.id, type, id)),
                            false,
                        )
                    }
                }
                .toList()

            return if (rows.isEmpty()) listOf(MainPageData("MultiStremio", "{}", false)) else rows.map { it.second }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val settings = Config.snapshot()
        // External rows delegate to the provider that originally owns the selected homepage row.
        val externalKey = Json.parse<ExternalPageRef>(request.data)?.key
        if (!externalKey.isNullOrBlank()) {
            val selected = settings.externalRows.firstOrNull {
                it.key == externalKey && it.enabled
            } ?: return newHomePageResponse(request, emptyList(), false)
            val installed = ExternalHomeRows.resolve(selected, ExternalHomeRowsCache.cachedOrRefresh())
                ?: return newHomePageResponse(request, emptyList(), false)

            return try {
                installed.provider.getMainPage(
                    page,
                    MainPageRequest(installed.row.name, installed.row.data, installed.row.horizontalImages),
                ) ?: newHomePageResponse(request, emptyList(), false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A broken external provider row must not take down MultiStremio's other rows.
                newHomePageResponse(request, emptyList(), false)
            }
        }

        val ref = Json.parse<CatalogRef>(request.data)
        val type = ref?.t
        val catalogId = ref?.c
        if (ref == null || type == null || catalogId == null) throw ErrorLoadingException(noAddonsMessage)
        val addon = settings.addons.firstOrNull { it.id == ref.a && it.enabled && it.useCatalogs }
            ?: throw ErrorLoadingException("This addon was removed, disabled or no longer used for catalogs. Pull to refresh the home page.")

        val manifest = Config.manifestOf(addon)
        if (manifest?.supports("catalog", type, null) != true) {
            throw ErrorLoadingException("This addon no longer provides the catalog resource for this content type. Refresh the home page.")
        }
        val catalog = manifest.catalogs?.firstOrNull { it.type == type && it.id == catalogId }
            ?: throw ErrorLoadingException("This catalog is no longer available. Refresh the home page.")
        val supportsSkip = catalog.supportsExtra("skip")
        if (page > 1 && !supportsSkip) return newHomePageResponse(request, emptyList(), false)

        val key = request.data
        if (page <= 1) skipOffsets.remove(key)
        // Page > 1 without a known offset (e.g. after a process restart) would repeat page 1.
        val skip = if (page <= 1) 0 else skipOffsets.get(key) ?: return newHomePageResponse(request, emptyList(), false)
        val metas = StremioClient.catalog(addon, type, catalogId, if (skip > 0) "skip=$skip" else null)
        val hasNext = supportsSkip && metas.isNotEmpty()
        if (hasNext) skipOffsets.put(key, skip + metas.size) else skipOffsets.remove(key)

        val items = metas.mapNotNull { it.toSearchResponse(addon.id, type) }
        return newHomePageResponse(request, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = Urls.encodeComponent(query)
        val settings = Config.snapshot()
        val jobs = mutableListOf<Triple<AddonConfig, String, String>>() // addon, type, catalog id
        for (addon in settings.addons) {
            if (!addon.enabled || !addon.useCatalogs) continue
            val manifest = Config.manifestOf(addon) ?: continue
            for (c in manifest.catalogs.orEmpty()) {
                val type = c.type ?: continue
                val id = c.id ?: continue
                if (manifest.supports("catalog", type, null) && c.isSearchable()) {
                    jobs += Triple(addon, type, id)
                }
            }
        }
        if (jobs.isEmpty()) return emptyList()

        val results = jobs.boundedAmap(8) { (addon, type, id) ->
            StremioClient.catalog(addon, type, id, "search=$q").map { Triple(addon.id, type, it) }
        }
        return SearchMetaDeduplication.unique(results.flatten())
            .mapNotNull { (addonId, fallbackType, meta) ->
                meta.toSearchResponse(addonId, fallbackType)
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = Json.parse<ItemRef>(url) ?: throw ErrorLoadingException("Invalid item")
        val type = ref.t ?: "movie"
        val id = ref.i ?: throw ErrorLoadingException("Invalid item id")

        val meta = findMeta(ref, type, id)
        val title = meta?.name ?: ref.n ?: id
        val poster = meta?.poster ?: ref.p
        val metaId = meta?.id ?: id
        val tvType = tvTypeOf(type)
        val videos = meta?.videos.orEmpty().filter { !it.id.isNullOrBlank() }
        val yr = yearOf(meta)

        val decorate: suspend LoadResponse.() -> Unit = {
            posterUrl = poster
            backgroundPosterUrl = meta?.background
            logoUrl = meta?.logo
            plot = meta?.description
            this.year = yr
            tags = (meta?.genres ?: meta?.genre)?.filter { it.isNotBlank() }
            addScore(meta?.imdbRating?.toString())
            addDuration(meta?.runtime)
            addActors(meta?.cast?.filter { it.isNotBlank() })
            if (metaId.startsWith("tt")) addImdbId(metaId)
            val trailers = meta?.trailers.orEmpty().mapNotNull { t -> t.source?.takeIf { it.isNotBlank() } } +
                meta?.trailerStreams.orEmpty().mapNotNull { t -> t.ytId?.takeIf { it.isNotBlank() } }
            if (trailers.isNotEmpty()) addTrailer(trailers.distinct().map { "https://www.youtube.com/watch?v=$it" })
        }

        if (tvType == TvType.Live) {
            return newLiveStreamLoadResponse(title, url, Json.toJson(StreamRef(type, metaId, metaId))) { decorate() }
        }
        if (type == "movie" || (type != "series" && videos.isEmpty())) {
            val movieType = if (tvType == TvType.Anime) TvType.AnimeMovie else tvType
            return newMovieLoadResponse(title, url, movieType, Json.toJson(StreamRef(type, metaId, metaId))) { decorate() }
        }

        val episodes = videos.mapIndexed { idx, v ->
            val episodeNumber = v.episode ?: v.number ?: (idx + 1)
            val data = StreamRef(type, v.id, metaId, v.season, episodeNumber)
            newEpisode(data) {
                val upcoming = isUpcoming(v.released?.take(10))
                val episodeTitle = v.name?.takeIf { it.isNotBlank() }
                    ?: v.title?.takeIf { it.isNotBlank() }
                    ?: "Episode $episodeNumber"
                this.name = episodeTitle + if (upcoming) " [UPCOMING]" else ""
                this.season = v.season
                this.episode = episodeNumber
                this.posterUrl = v.thumbnail
                this.description = v.overview ?: v.description
                addDate(v.released?.take(10))
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            decorate()
            // A missing metadata response is not evidence that a series is upcoming.
            if (meta == null && type == "series") comingSoon = false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = Json.parse<StreamRef>(data) ?: return false
        val type = ref.t ?: "movie"
        val id = ref.i ?: return false
        val settings = Config.snapshot()
        val diagnosticRunId = StreamDiagnostics.begin()

        val streamAddons = settings.addons.withIndex()
            .filter { (_, a) -> a.enabled && a.useStreams && Config.manifestOf(a)?.supports("stream", type, id) == true }
        val subAddons = if (settings.fetchSubtitles) {
            settings.addons.filter { a ->
                a.enabled && a.useSubtitles && Config.manifestOf(a)?.supports("subtitles", type, id) == true
            }
        } else emptyList()

        // Stream and subtitle providers share one cap. A slow addon no longer holds an entire
        // fixed-size batch hostage, and an idle branch can lend capacity to the busy branch.
        val requestLimiter = Semaphore(8)
        return coroutineScope {
            // Start stream work first so it gets first access to the shared permits. This
            // prevents a large subtitle-addon list from occupying every connection before any
            // playable stream request has started.
            val streamDeferred = async {
                streamAddons.boundedAmap(requestLimiter) { (index, addon) ->
                    try {
                        val raw = StremioClient.streams(addon, type, id, diagnosticRunId)
                        val parseStarted = System.nanoTime()
                        val converted = raw.mapNotNull { stream ->
                            try {
                                StreamParser.parsed(index, addon.name, stream)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        StreamDiagnostics.recordStage(
                            diagnosticRunId, "parse",
                            (System.nanoTime() - parseStarted) / 1_000_000L,
                        )
                        converted
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One malformed addon must not discard valid results from other addons.
                        emptyList()
                    }
                }.flatten()
            }

            // Run subtitle requests concurrently, but don't let them delay playable callbacks.
            // CloudStream updates the link list progressively as each callback arrives.
            val subtitleDeferred = async {
                subAddons.boundedAmap(requestLimiter) { addon ->
                    try {
                        StremioClient.subtitles(addon, type, id, diagnosticRunId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }.flatten()
            }

            val parsed = streamDeferred.await()

            // YouTube/external-only entries do not use the playable filter or its limits.
            val playable = parsed.filter { it.isDirect || it.isMagnet }
            val pipelineStarted = System.nanoTime()
            val processed = StreamPipeline.process(playable, settings.filter, settings.sort)
            StreamDiagnostics.recordStage(
                diagnosticRunId, "pipeline",
                (System.nanoTime() - pipelineStarted) / 1_000_000L,
            )

            var emitted = false
            var emittedLinks = 0
            val emissionStarted = System.nanoTime()
            val wantedSubs = settings.subtitleLanguages.map { subtitleKey(it) }.toSet()
            val sentSubs = HashSet<String>()

            // Emit sorted direct/magnet links as soon as all stream addons have answered.
            // Sorting remains global and deterministic; only the independent subtitle wait moves later.
            for (stream in processed) {
                emitStream(stream, settings, sentSubs, wantedSubs, subtitleCallback, callback)
                emitted = true
                emittedLinks++
            }

            // YouTube/external links are not filterable; hand them to CloudStream's extractors.
            // Resolve them after direct links so a slow extractor cannot delay playable callbacks.
            val seenExternalTargets = HashSet<String>()
            for (stream in parsed) {
                val yt = stream.raw.ytId
                val external = stream.raw.externalUrl
                // Prefer direct URL or magnet when a malformed addon supplies multiple target fields.
                val hasPrimaryTarget = !stream.raw.url.isNullOrBlank() || !stream.raw.infoHash.isNullOrBlank()
                val target = when {
                    hasPrimaryTarget -> null
                    !yt.isNullOrBlank() -> "https://www.youtube.com/watch?v=$yt"
                    !external.isNullOrBlank() -> external
                    else -> null
                } ?: continue
                val key = StreamDeduplication.externalKey(stream)
                if (key != null && !seenExternalTargets.add(key)) continue
                if (loadExtractor(target, subtitleCallback, callback)) {
                    emitted = true
                    emittedLinks++
                }
            }

            StreamDiagnostics.recordStage(
                diagnosticRunId, "emission",
                (System.nanoTime() - emissionStarted) / 1_000_000L,
            )

            // Additional subtitle endpoints can be much slower than stream endpoints. Their delay
            // must not hold back direct links or extractor links that have already been emitted.
            val subtitles = subtitleDeferred.await()
            for (subtitle in subtitles) {
                val url = subtitle.url?.takeIf { it.isNotBlank() } ?: continue
                val code = subtitle.lang ?: "und"
                if (wantedSubs.isNotEmpty() && subtitleKey(code) !in wantedSubs) continue
                if (!sentSubs.add(url)) continue
                subtitleCallback(newSubtitleFile(subtitleName(code), url))
            }
            StreamDiagnostics.finish(
                diagnosticRunId,
                rawStreams = parsed.size,
                addonSubtitles = subtitles.size,
                playableCandidates = playable.size,
                keptAfterFilters = processed.size,
                emittedLinks = emittedLinks,
            )
            emitted
        }
    }

    private suspend fun emitStream(
        p: ParsedStream,
        settings: Settings,
        sentSubs: MutableSet<String>,
        wantedSubs: Set<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val label = StreamLabels.build(p, settings.detailedLabels, settings.streamFormat)
        val quality = StreamLabels.linkQuality(p.info.resolution, settings.sort.overrideAppQuality)
            ?: Qualities.Unknown.value
        val hints = p.raw.behaviorHints
        val headers = StreamRequestHeaders.from(hints)
        val referer = StreamRequestHeaders.referer(headers)

        val url = p.raw.url
        if (!url.isNullOrBlank()) {
            callback(
                newExtractorLink(p.addonName, label, url) {
                    this.quality = quality
                    this.headers = headers
                    this.referer = referer
                },
            )
        } else if (!p.raw.infoHash.isNullOrBlank()) {
            val magnet = Urls.magnet(
                p.raw.infoHash,
                hints?.filename ?: p.raw.title?.lineSequence()?.firstOrNull(),
                p.raw.sources,
                p.raw.fileIdx,
            )
            callback(
                newExtractorLink(p.addonName, label, magnet, ExtractorLinkType.MAGNET) {
                    this.quality = quality
                },
            )
        }
        for (s in p.raw.subtitles.orEmpty()) {
            val su = s.url?.takeIf { it.isNotBlank() } ?: continue
            val language = s.lang ?: "und"
            if (wantedSubs.isNotEmpty() && subtitleKey(language) !in wantedSubs) continue
            if (!sentSubs.add(su)) continue
            subtitleCallback(newSubtitleFile(subtitleName(language), su))
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Origin addon first, then every other addon that can serve meta for this id. */
    private suspend fun findMeta(ref: ItemRef, type: String, id: String): Meta? {
        val candidates = Config.snapshot().addons
            .filter { it.enabled && it.useMeta && Config.manifestOf(it)?.supports("meta", type, id) == true }
        val origin = candidates.firstOrNull { it.id == ref.a }
        val originMeta = origin?.let { StremioClient.meta(it, type, id) }
        if (!originMeta?.name.isNullOrBlank()) return originMeta

        // Metadata addons are queried in parallel after the source addon fails to give a named item.
        val alternatives = candidates.filter { it.id != origin?.id }
            .boundedAmap(8) { addon -> StremioClient.meta(addon, type, id) }
        alternatives.firstOrNull { !it?.name.isNullOrBlank() }?.let { return it }

        // Preserve useful sparse metadata (e.g. videos) instead of throwing it away.
        return originMeta ?: alternatives.firstOrNull { it != null }
    }

    private fun Meta.toSearchResponse(addonId: String, fallbackType: String): SearchResponse? {
        val metaId = id ?: return null
        val title = name ?: return null
        val t = type?.takeIf { it.isNotBlank() } ?: fallbackType
        val url = Json.toJson(ItemRef(addonId, t, metaId, title, poster))
        val tvType = tvTypeOf(t)
        return if (tvType == TvType.Live) {
            newLiveSearchResponse(title, url, tvType, fix = false) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, tvType, fix = false) {
                posterUrl = poster
                year = yearOf(this@toSearchResponse)
                score = Score.from10(imdbRating?.toString())
            }
        }
    }

    private fun tvTypeOf(t: String): TvType = when (t.lowercase()) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "tv", "channel" -> TvType.Live
        else -> TvType.Others
    }

    private fun typeLabel(t: String): String = when (t.lowercase()) {
        "movie" -> "Movies"
        "series" -> "Series"
        "anime" -> "Anime"
        "tv" -> "TV"
        "channel" -> "Channels"
        else -> t.replaceFirstChar { it.uppercase() }
    }

    private fun yearOf(m: Meta?): Int? {
        if (m == null) return null
        val fromYear = m.year?.toString()?.let { Regex("""\d{4}""").find(it)?.value }
        val fromInfo = m.releaseInfo?.let { Regex("""\d{4}""").find(it)?.value }
        return (fromYear ?: fromInfo)?.toIntOrNull()
    }

    private fun subtitleName(code: String): String =
        SubtitleHelper.fromTagToLanguageName(code)
            ?: SubtitleHelper.fromTwoLettersToLanguage(code)
            ?: SubtitleHelper.fromThreeLettersToLanguage(code)
            ?: code

    /** Canonicalize ISO codes, BCP-47 tags, display names and CloudStream language aliases. */
    private fun subtitleKey(code: String): String {
        val raw = code.trim()
        val displayName = SubtitleHelper.fromTagToLanguageName(raw)
            ?: SubtitleHelper.fromTwoLettersToLanguage(raw)
            ?: SubtitleHelper.fromThreeLettersToLanguage(raw)
        return Langs.normalize(displayName ?: raw)
    }
}
