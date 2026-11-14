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
import java.util.concurrent.ConcurrentHashMap

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
    private val skipOffsets = ConcurrentHashMap<String, Int>()

    private val noAddonsMessage =
        "No addons configured. Open Settings > Extensions > MultiStremio (gear icon) and add an addon."

    // Built from the cached manifests every time the home page loads, so settings changes show up on refresh.
    override val mainPage: List<MainPageData>
        get() {
            val settings = Config.settings
            val rows = mutableListOf<Pair<String, MainPageData>>()
            for (addon in settings.addons) {
                if (!addon.enabled || !addon.useCatalogs) continue
                val manifest = Config.manifestOf(addon) ?: continue
                for (c in manifest.catalogs.orEmpty()) {
                    val type = c.type ?: continue
                    val id = c.id ?: continue
                    if (!c.isHomeCatalog() || "$type/$id" in addon.hiddenCatalogs) continue
                    val title = "${c.name ?: id} · ${typeLabel(type)}"
                    val key = catalogOrderKey(addon.id, type, id)
                    rows += key to MainPageData(title, Json.toJson(CatalogRef(addon.id, type, id)), false)
                }
            }

            // Rows selected from other installed CloudStream extensions share this same order list.
            val installedExternalRows = ExternalHomeRows.installedRows(this).associateBy { it.config.key }
            for (selected in settings.externalRows) {
                if (!selected.enabled) continue
                val installed = installedExternalRows[selected.key] ?: continue
                val original = installed.row
                rows += selected.key to MainPageData(
                    original.name,
                    Json.toJson(ExternalPageRef(selected.key)),
                    original.horizontalImages,
                )
            }

            val positions = settings.catalogOrder.withIndex().associate { it.value to it.index }
            val orderedRows = rows.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<Pair<String, MainPageData>>> {
                        positions[it.value.first] ?: Int.MAX_VALUE
                    }.thenBy { it.index },
                )
                .map { it.value.second }

            return if (orderedRows.isEmpty()) listOf(MainPageData("MultiStremio", "{}", false)) else orderedRows
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // External rows delegate to the provider that originally owns the selected homepage row.
        val externalRef = Json.parse<ExternalPageRef>(request.data)
        if (!externalRef?.key.isNullOrBlank()) {
            val selected = Config.settings.externalRows.firstOrNull {
                it.key == externalRef?.key && it.enabled
            } ?: return newHomePageResponse(request, emptyList(), false)
            val installed = ExternalHomeRows.resolve(selected, this)
                ?: return newHomePageResponse(request, emptyList(), false)

            return try {
                installed.provider.getMainPage(
                    page,
                    MainPageRequest(installed.row.name, installed.row.data, installed.row.horizontalImages),
                ) ?: newHomePageResponse(request, emptyList(), false)
            } catch (e: kotlinx.coroutines.CancellationException) {
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
        val addon = Config.settings.addons.firstOrNull { it.id == ref.a && it.enabled }
            ?: throw ErrorLoadingException("This addon was removed or disabled. Pull to refresh the home page.")

        val catalog = Config.manifestOf(addon)?.catalogs?.firstOrNull { it.type == type && it.id == catalogId }
        val supportsSkip = catalog?.supportsExtra("skip") ?: true
        if (page > 1 && !supportsSkip) return newHomePageResponse(request, emptyList(), false)

        val key = request.data
        if (page <= 1) skipOffsets.remove(key)
        // Page > 1 without a known offset (e.g. after a process restart) would repeat page 1.
        val skip = if (page <= 1) 0 else skipOffsets[key] ?: return newHomePageResponse(request, emptyList(), false)
        val metas = StremioClient.catalog(addon, type, catalogId, if (skip > 0) "skip=$skip" else null)
        skipOffsets[key] = skip + metas.size

        val items = metas.mapNotNull { it.toSearchResponse(addon.id) }
        return newHomePageResponse(request, items, supportsSkip && metas.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = Urls.encodeComponent(query)
        val jobs = mutableListOf<Triple<AddonConfig, String, String>>() // addon, type, catalog id
        for (addon in Config.settings.addons) {
            if (!addon.enabled || !addon.useCatalogs) continue
            val manifest = Config.manifestOf(addon) ?: continue
            for (c in manifest.catalogs.orEmpty()) {
                val type = c.type ?: continue
                val id = c.id ?: continue
                if (c.isSearchable()) jobs += Triple(addon, type, id)
            }
        }
        if (jobs.isEmpty()) return emptyList()

        val results = jobs.amap { (addon, type, id) ->
            StremioClient.catalog(addon, type, id, "search=$q").map { addon.id to it }
        }
        val seen = HashSet<String>()
        return results.flatten()
            .filter { (_, m) -> seen.add("${m.type}:${m.id}") }
            .mapNotNull { (addonId, m) -> m.toSearchResponse(addonId) }
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
        if (type == "movie" || videos.isEmpty()) {
            val movieType = if (tvType == TvType.Anime) TvType.AnimeMovie else tvType
            return newMovieLoadResponse(title, url, movieType, Json.toJson(StreamRef(type, metaId, metaId))) { decorate() }
        }

        val episodes = videos.mapIndexed { idx, v ->
            val data = StreamRef(type, v.id, metaId, v.season, v.episode ?: v.number)
            newEpisode(data) {
                val upcoming = isUpcoming(v.released?.take(10))
                this.name = (v.name ?: v.title) + if (upcoming) " [UPCOMING]" else ""
                this.season = v.season
                this.episode = v.episode ?: v.number ?: (idx + 1)
                this.posterUrl = v.thumbnail
                this.description = v.overview ?: v.description
                addDate(v.released?.take(10))
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, tvType, episodes) { decorate() }
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
        val settings = Config.settings

        val streamAddons = settings.addons.withIndex()
            .filter { (_, a) -> a.enabled && a.useStreams && Config.manifestOf(a)?.supports("stream", type, id) == true }
        val subAddons = if (settings.fetchSubtitles) {
            settings.addons.filter { a ->
                a.enabled && a.useSubtitles && Config.manifestOf(a)?.supports("subtitles", type, id) == true
            }
        } else emptyList()

        var parsed: List<ParsedStream> = emptyList()
        var subtitles: List<RawSubtitle> = emptyList()
        runAllAsync(
            {
                parsed = streamAddons.amap { (index, addon) ->
                    StremioClient.streams(addon, type, id).map { StreamParser.parsed(index, addon.name, it) }
                }.flatten()
            },
            {
                subtitles = subAddons.amap { StremioClient.subtitles(it, type, id) }.flatten()
            },
        )

        // Subtitles from subtitle addons (optionally limited to chosen languages)
        val wantedSubs = settings.subtitleLanguages.map { subtitleKey(it) }.toSet()
        val sentSubsEarly = HashSet<String>()
        for (s in subtitles) {
            val url = s.url ?: continue
            val code = s.lang ?: "und"
            if (wantedSubs.isNotEmpty() && subtitleKey(code) !in wantedSubs) continue
            if (!sentSubsEarly.add(url)) continue
            subtitleCallback(newSubtitleFile(subtitleName(code), url))
        }

        // YouTube/external-only entries are handled below and must not use up filter/limit slots.
        val playable = parsed.filter { it.isDirect || it.isMagnet }
        val processed = StreamPipeline.process(playable, settings.filter, settings.sort)
        val sentSubs = HashSet<String>()
        var emitted = false
        for (p in processed) {
            emitStream(p, settings, sentSubs, subtitleCallback, callback)
            emitted = true
        }

        // YouTube / external links are not filterable; hand them to CloudStream's extractors.
        for (p in parsed) {
            val yt = p.raw.ytId
            val ext = p.raw.externalUrl
            val target = when {
                !yt.isNullOrBlank() -> "https://www.youtube.com/watch?v=$yt"
                p.raw.url.isNullOrBlank() && p.raw.infoHash.isNullOrBlank() && !ext.isNullOrBlank() -> ext
                else -> null
            } ?: continue
            if (loadExtractor(target, subtitleCallback, callback)) emitted = true
        }
        return emitted
    }

    private suspend fun emitStream(
        p: ParsedStream,
        settings: Settings,
        sentSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val label = StreamLabels.build(p, settings.detailedLabels)
        val quality = StreamLabels.linkQuality(p.info.resolution, settings.sort.overrideAppQuality)
            ?: Qualities.Unknown.value
        val hints = p.raw.behaviorHints
        val headers = (hints?.proxyHeaders?.request ?: hints?.headers).orEmpty()

        val url = p.raw.url
        if (!url.isNullOrBlank()) {
            callback(
                newExtractorLink(p.addonName, label, url) {
                    this.quality = quality
                    this.headers = headers
                    this.referer = ""
                },
            )
        } else if (!p.raw.infoHash.isNullOrBlank()) {
            val magnet = Urls.magnet(p.raw.infoHash, hints?.filename ?: p.raw.title?.lineSequence()?.firstOrNull(), p.raw.sources)
            callback(
                newExtractorLink(p.addonName, label, magnet, ExtractorLinkType.MAGNET) {
                    this.quality = quality
                },
            )
        }
        for (s in p.raw.subtitles.orEmpty()) {
            val su = s.url ?: continue
            if (!sentSubs.add(su)) continue
            subtitleCallback(newSubtitleFile(subtitleName(s.lang ?: "und"), su))
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Origin addon first, then every other addon that can serve meta for this id. */
    private suspend fun findMeta(ref: ItemRef, type: String, id: String): Meta? {
        val candidates = Config.settings.addons
            .filter { it.enabled && it.useMeta && Config.manifestOf(it)?.supports("meta", type, id) == true }
            .sortedBy { if (it.id == ref.a) 0 else 1 }
        for (addon in candidates) {
            StremioClient.meta(addon, type, id)?.let { return it }
        }
        return null
    }

    private fun Meta.toSearchResponse(addonId: String): SearchResponse? {
        val metaId = id ?: return null
        val title = name ?: return null
        val t = type ?: "movie"
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

    /** Comparable key so "en", "eng" and "English" all match each other. */
    private fun subtitleKey(code: String): String = subtitleName(code.trim()).lowercase()
}
