package com.multistremio

import java.net.URLEncoder
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------
// Parsing, filtering, deduplicating and sorting of Stremio streams.
// Pure Kotlin (no Android / CloudStream classes) so it can be unit tested on a plain JVM.
// ---------------------------------------------------------------------------------------------

const val KB = 1024L
const val MB = 1024L * 1024L
const val GB = 1024L * 1024L * 1024L

data class StreamInfo(
    /** Resolution bucket: 2160, 1440, 1080, 720, 576, 480, 360, 240 or 0 when unknown. */
    val resolution: Int,
    val sizeBytes: Long?,
    val seeders: Int?,
    val codec: String?, // HEVC, AVC, AV1
    val hdr: String?, // DV, HDR10+, HDR10, HDR
    val source: String?, // REMUX, BluRay, WEB-DL, WEBRip, HDTV, DVD, CAM
    val audio: String?,
    /** true = cached on a debrid service, false = needs a download first, null = unknown/not debrid. */
    val cached: Boolean?,
    val isCam: Boolean,
    /** Lower-case 2-letter codes, plus "multi". Empty when nothing could be detected. */
    val languages: Set<String>,
)

class ParsedStream(
    val addonIndex: Int,
    val addonName: String,
    val raw: RawStream,
    /** Lower-case text of name/title/description/filename that keyword filters run against. */
    val text: String,
    val info: StreamInfo,
) {
    val isMagnet: Boolean get() = raw.url.isNullOrBlank() && !raw.infoHash.isNullOrBlank()
    val isDirect: Boolean get() = !raw.url.isNullOrBlank()
}

// ---------------------------------------------------------------------------------------------
// Languages
// ---------------------------------------------------------------------------------------------

object Langs {
    private val FLAG_TO_LANG = mapOf(
        "GB" to "en", "US" to "en", "AU" to "en", "CA" to "en", "FR" to "fr", "ES" to "es",
        "MX" to "es", "AR" to "es", "DE" to "de", "AT" to "de", "IT" to "it", "PT" to "pt",
        "BR" to "pt", "RU" to "ru", "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh",
        "HK" to "zh", "IN" to "hi", "TR" to "tr", "SA" to "ar", "EG" to "ar", "AE" to "ar",
        "PL" to "pl", "NL" to "nl", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi",
        "GR" to "el", "UA" to "uk", "TH" to "th", "ID" to "id", "VN" to "vi", "IL" to "he",
        "CZ" to "cs", "HU" to "hu", "RO" to "ro", "BD" to "bn", "PK" to "ur", "IR" to "fa",
    )

    /** English name / 3-letter code / 2-letter code -> 2-letter code. */
    private val ALIASES: Map<String, String> = buildMap {
        val rows = listOf(
            Triple("en", "eng", "english"), Triple("hi", "hin", "hindi"), Triple("fr", "fra", "french"),
            Triple("es", "spa", "spanish"), Triple("de", "deu", "german"), Triple("it", "ita", "italian"),
            Triple("pt", "por", "portuguese"), Triple("ru", "rus", "russian"), Triple("ja", "jpn", "japanese"),
            Triple("ko", "kor", "korean"), Triple("zh", "zho", "chinese"), Triple("tr", "tur", "turkish"),
            Triple("ar", "ara", "arabic"), Triple("bn", "ben", "bengali"), Triple("ta", "tam", "tamil"),
            Triple("te", "tel", "telugu"), Triple("pl", "pol", "polish"), Triple("nl", "nld", "dutch"),
            Triple("sv", "swe", "swedish"), Triple("uk", "ukr", "ukrainian"), Triple("th", "tha", "thai"),
            Triple("id", "ind", "indonesian"), Triple("vi", "vie", "vietnamese"), Triple("he", "heb", "hebrew"),
            Triple("ur", "urd", "urdu"), Triple("fa", "fas", "persian"),
        )
        for ((two, three, name) in rows) {
            put(two, two); put(three, two); put(name, two)
        }
        put("fre", "fr"); put("ger", "de"); put("chi", "zh"); put("multi", "multi")
    }

    fun normalize(input: String): String {
        val k = input.trim().lowercase(Locale.ROOT)
        return ALIASES[k] ?: k
    }

    // Words in release names that reveal the audio language.
    private val WORD_LANGS = listOf(
        Regex("""\b(english|eng)\b""") to "en",
        Regex("""\b(hindi|hin)\b""") to "hi",
        Regex("""\b(french|vff|vfq|vfi|truefrench)\b""") to "fr",
        Regex("""\b(spanish|castellano|latino)\b""") to "es",
        Regex("""\b(german|deutsch)\b""") to "de",
        Regex("""\b(italian|ita)\b""") to "it",
        Regex("""\b(portuguese|dublado)\b""") to "pt",
        Regex("""\b(russian|rus)\b""") to "ru",
        Regex("""\b(japanese|jpn)\b""") to "ja",
        Regex("""\b(korean|kor)\b""") to "ko",
        Regex("""\b(chinese|mandarin|cantonese)\b""") to "zh",
        Regex("""\b(turkish)\b""") to "tr",
        Regex("""\b(arabic)\b""") to "ar",
        Regex("""\b(tamil)\b""") to "ta",
        Regex("""\b(telugu)\b""") to "te",
        Regex("""\b(bengali|bangla)\b""") to "bn",
    )
    private val MULTI = Regex("""\b(multi|dual[ .\-_]?audio|multi[ .\-_]?audio)\b""")

    /** Decode regional-indicator flag emoji (two code points) into ISO country codes. */
    fun flagCountries(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && next < text.length) {
                val cp2 = text.codePointAt(next)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    out += "" + ('A' + (cp - 0x1F1E6)) + ('A' + (cp2 - 0x1F1E6))
                    i = next + Character.charCount(cp2)
                    continue
                }
            }
            i = next
        }
        return out
    }

    fun detect(lowerText: String): Set<String> {
        val found = linkedSetOf<String>()
        for (c in flagCountries(lowerText)) FLAG_TO_LANG[c]?.let { found += it }
        for ((re, code) in WORD_LANGS) if (re.containsMatchIn(lowerText)) found += code
        if (MULTI.containsMatchIn(lowerText)) found += "multi"
        return found
    }
}

// ---------------------------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------------------------

// Emoji written as escapes so the file survives any source encoding.
const val EMOJI_SEEDER = "\uD83D\uDC64" // person silhouette
const val EMOJI_DISK = "\uD83D\uDCBE" // floppy disk
const val EMOJI_BOLT = "\u26A1" // high voltage

object StreamParser {

    private val RES = Regex("""(?<![0-9])(2160|1440|1080|720|576|480|360|240)[pPiI]""")
    private val FOUR_K = Regex("""\b(4k|uhd)\b""")
    private val FHD = Regex("""\bfhd\b""")
    private val SIZE_AFTER_DISK = Regex("""$EMOJI_DISK\s*(\d+(?:[.,]\d+)?)\s?(tb|gb|gib|mb|mib)\b""")
    private val SIZE_ANY = Regex("""(\d+(?:[.,]\d+)?)\s?(tb|gb|gib|mb|mib)\b""")
    private val SEEDERS_A = Regex("""(?:$EMOJI_SEEDER|seeders?\s*:?|seeds?\s*:?)\s*(\d{1,6})""")
    private val SEEDERS_B = Regex("""(\d{1,6})\s*(?:seeders|seeds)\b""")
    private val HEVC = Regex("""\b(x265|h\.?265|hevc)\b""")
    private val AVC = Regex("""\b(x264|h\.?264|avc)\b""")
    private val AV1 = Regex("""\bav1\b""")
    private val DV = Regex("""\b(dolby[ .\-_]?vision|dovi|dv)\b""")
    private val HDR10P = Regex("""\bhdr10(\+|plus)""")
    private val HDR10 = Regex("""\bhdr10\b""")
    private val HDR = Regex("""\bhdr\b""")
    private val REMUX = Regex("""\b(remux|bdremux)\b""")
    private val BLURAY = Regex("""\b(blu[ .\-_]?ray|bdrip|brrip)\b""")
    private val WEBDL = Regex("""\b(web[ .\-_]?dl|web)\b""")
    private val WEBRIP = Regex("""\bweb[ .\-_]?rip\b""")
    private val HDTV = Regex("""\b(hdtv|pdtv|tvrip)\b""")
    private val DVD = Regex("""\b(dvdrip|dvd[ .\-_]?r|dvd)\b""")
    // Unambiguous tags vs. short words that can also appear in titles ("Cam", "TS", "R5").
    private val CAM_STRONG = Regex(
        """\b(camrip|hd[ .\-_]?cam|hdts|hd[ .\-_]?tc|telesync|telecine|dvdscr|screener|workprint|predvd|pdvd)\b""",
    )
    private val CAM_WEAK = Regex(
        """(?<=[\s._\-\[(])(cam|ts|scr|r5)(?=[\s._\-\])])""",
    )
    private val CACHED_TAG = Regex("""\[[a-z]{2,4}\+\]""")
    private val UNCACHED_TAG = Regex("""\[[a-z]{2,4}\s?download\]""")

    fun parse(raw: RawStream): Pair<StreamInfo, String> {
        val rawText = listOfNotNull(
            raw.name, raw.title, raw.description, raw.behaviorHints?.filename,
        ).joinToString("\n")
        val t = rawText.lowercase(Locale.ROOT)

        val res = RES.find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (FOUR_K.containsMatchIn(t)) 2160 else if (FHD.containsMatchIn(t)) 1080 else 0

        val vs = raw.behaviorHints?.videoSize
        val hintSize = ((vs as? Number)?.toLong() ?: vs?.toString()?.trim()?.toLongOrNull())?.takeIf { it > 0 }
        val size = hintSize ?: parseSize(t)

        val seeders = (SEEDERS_A.find(t) ?: SEEDERS_B.find(t))?.groupValues?.get(1)?.toIntOrNull()

        val codec = when {
            HEVC.containsMatchIn(t) -> "HEVC"
            AV1.containsMatchIn(t) -> "AV1"
            AVC.containsMatchIn(t) -> "AVC"
            else -> null
        }
        val hdr = when {
            DV.containsMatchIn(t) -> "DV"
            HDR10P.containsMatchIn(t) -> "HDR10+"
            HDR10.containsMatchIn(t) -> "HDR10"
            HDR.containsMatchIn(t) -> "HDR"
            else -> null
        }
        val goodSource = REMUX.containsMatchIn(t) || BLURAY.containsMatchIn(t) || WEBRIP.containsMatchIn(t) ||
            Regex("""\bweb[ .\-_]?dl\b""").containsMatchIn(t) || HDTV.containsMatchIn(t)
        // A weak tag only counts when the release has no proper source tag next to it.
        val cam = CAM_STRONG.containsMatchIn(t) || (CAM_WEAK.containsMatchIn(t) && !goodSource)
        val source = when {
            cam -> "CAM"
            REMUX.containsMatchIn(t) -> "REMUX"
            BLURAY.containsMatchIn(t) -> "BluRay"
            WEBRIP.containsMatchIn(t) -> "WEBRip"
            WEBDL.containsMatchIn(t) -> "WEB-DL"
            HDTV.containsMatchIn(t) -> "HDTV"
            DVD.containsMatchIn(t) -> "DVD"
            else -> null
        }
        val audio = when {
            t.contains("atmos") -> "Atmos"
            t.contains("truehd") -> "TrueHD"
            Regex("""\bdts[ .\-_]?(hd|x)""").containsMatchIn(t) -> "DTS-HD"
            Regex("""\bdts\b""").containsMatchIn(t) -> "DTS"
            Regex("""\b(ddp|dd\+|e[ \-]?ac-?3)""").containsMatchIn(t) -> "DD+"
            Regex("""\b(dd[ .\-_]?5|ac-?3|dolby digital)""").containsMatchIn(t) -> "DD"
            Regex("""\baac\b""").containsMatchIn(t) -> "AAC"
            else -> null
        }
        val cached = when {
            UNCACHED_TAG.containsMatchIn(t) || t.contains("uncached") -> false
            CACHED_TAG.containsMatchIn(t) || t.contains(EMOJI_BOLT) || Regex("""\bcached\b""").containsMatchIn(t) -> true
            else -> null
        }
        val info = StreamInfo(
            resolution = res, sizeBytes = size, seeders = seeders, codec = codec, hdr = hdr,
            source = source, audio = audio, cached = cached, isCam = cam, languages = Langs.detect(t),
        )
        return info to t
    }

    private fun parseSize(t: String): Long? {
        val m = SIZE_AFTER_DISK.find(t) ?: SIZE_ANY.find(t) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2]) {
            "tb" -> GB * 1024L
            "gb", "gib" -> GB
            else -> MB
        }
        return (value * unit).toLong()
    }

    fun parsed(addonIndex: Int, addonName: String, raw: RawStream): ParsedStream {
        val (info, text) = parse(raw)
        return ParsedStream(addonIndex, addonName, raw, text, info)
    }
}

// ---------------------------------------------------------------------------------------------
// Filtering
// ---------------------------------------------------------------------------------------------

object StreamFilter {
    private val regexCache = ConcurrentHashMap<String, Regex>()
    private val NEVER = Regex("(?!)")

    /** Plain keywords are case-insensitive substrings. Wrap in slashes for a regex: /x26[45]/ */
    fun matchesKeyword(lowerText: String, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return false
        if (k.length > 2 && k.startsWith("/") && k.endsWith("/")) {
            val re = regexCache.getOrPut(k) {
                try {
                    Regex(k.substring(1, k.length - 1), RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    NEVER
                }
            }
            return re.containsMatchIn(lowerText)
        }
        return lowerText.contains(k.lowercase(Locale.ROOT))
    }

    fun anyMatch(lowerText: String, keywords: List<String>): Boolean =
        keywords.any { matchesKeyword(lowerText, it) }

    fun passes(p: ParsedStream, f: FilterSettings): Boolean {
        val i = p.info
        if (p.isMagnet && f.hideMagnets) return false
        if (f.hideCam && i.isCam) return false
        if (f.hideUncachedDebrid && i.cached == false) return false
        if (f.allowedResolutions.isNotEmpty() && i.resolution !in f.allowedResolutions) return false

        if (f.hevcMode == MODE_HIDE && i.codec == "HEVC") return false
        if (f.hevcMode == MODE_ONLY && i.codec != "HEVC") return false
        val hasHdr = i.hdr != null
        if (f.hdrMode == MODE_HIDE && hasHdr) return false
        if (f.hdrMode == MODE_ONLY && !hasHdr) return false

        val size = i.sizeBytes
        if (size != null) {
            if (f.minSizeMb > 0 && size < f.minSizeMb * MB) return false
            if (f.maxSizeGb > 0 && size > f.maxSizeGb * GB) return false
        }
        val seeders = i.seeders
        if (f.minSeeders > 0 && seeders != null && seeders < f.minSeeders) return false

        if (f.excludeKeywords.isNotEmpty() && anyMatch(p.text, f.excludeKeywords)) return false
        if (f.requireKeywords.isNotEmpty() && !anyMatch(p.text, f.requireKeywords)) return false

        if (f.requireLanguages.isNotEmpty()) {
            val want = f.requireLanguages.map { Langs.normalize(it) }.toSet()
            val have = i.languages
            val ok = when {
                "multi" in have -> true
                have.isEmpty() -> !f.strictLanguage
                else -> have.any { it in want }
            }
            if (!ok) return false
        }
        return true
    }
}

// ---------------------------------------------------------------------------------------------
// Sorting
// ---------------------------------------------------------------------------------------------

object StreamSorter {
    private val SOURCE_RANK = mapOf(
        "REMUX" to 6, "BluRay" to 5, "WEB-DL" to 4, "WEBRip" to 3, "HDTV" to 2, "DVD" to 1, "CAM" to -1,
    )
    private val HDR_RANK = mapOf("DV" to 4, "HDR10+" to 3, "HDR10" to 2, "HDR" to 1)

    private fun res() = compareByDescending<ParsedStream> { it.info.resolution }
    private fun source() = compareByDescending<ParsedStream> { SOURCE_RANK[it.info.source] ?: 0 }
    private fun hdr() = compareByDescending<ParsedStream> { HDR_RANK[it.info.hdr] ?: 0 }
    private fun sizeDesc() = compareByDescending<ParsedStream> { it.info.sizeBytes ?: -1L }
    // Unknown sizes go last when sorting ascending.
    private fun sizeAsc() = compareBy<ParsedStream> { it.info.sizeBytes ?: Long.MAX_VALUE }
    private fun seeders() = compareByDescending<ParsedStream> { it.info.seeders ?: -1 }
    private fun addon() = compareBy<ParsedStream> { it.addonIndex }

    fun comparator(s: SortSettings): Comparator<ParsedStream> {
        val chain = mutableListOf<Comparator<ParsedStream>>()

        if (s.preferKeywords.isNotEmpty()) {
            chain += compareByDescending<ParsedStream> {
                if (StreamFilter.anyMatch(it.text, s.preferKeywords)) 1 else 0
            }
        }
        if (s.preferLanguages.isNotEmpty()) {
            val want = s.preferLanguages.map { Langs.normalize(it) }.toSet()
            chain += compareByDescending<ParsedStream> {
                val have = it.info.languages
                when {
                    "multi" in have || have.any { l -> l in want } -> 2
                    have.isEmpty() -> 1
                    else -> 0
                }
            }
        }
        if (s.cachedFirst) {
            chain += compareByDescending<ParsedStream> {
                when (it.info.cached) {
                    true -> 2
                    null -> 1
                    false -> 0
                }
            }
        }

        when (SortPreset.from(s.preset)) {
            SortPreset.BEST_QUALITY -> chain.addAll(listOf(res(), source(), hdr(), sizeDesc()))
            SortPreset.QUALITY_SEEDERS -> chain.addAll(listOf(res(), seeders(), sizeDesc()))
            SortPreset.LARGEST -> chain.addAll(listOf(sizeDesc(), res()))
            SortPreset.SMALLEST -> chain.addAll(listOf(sizeAsc(), res()))
            SortPreset.MOST_SEEDERS -> chain.addAll(listOf(seeders(), res()))
            SortPreset.ADDON_ORDER -> chain.addAll(listOf(addon(), res(), sizeDesc()))
        }
        chain += addon() // final, deterministic tie-break
        return chain.reduce { a, b -> a.then(b) }
    }
}

// ---------------------------------------------------------------------------------------------
// Pipeline: filter -> dedupe -> sort -> limit
// ---------------------------------------------------------------------------------------------


object StreamPipeline {
    private fun dedupeKey(p: ParsedStream): String? {
        val hash = p.raw.infoHash
        if (!hash.isNullOrBlank()) return "h:" + hash.lowercase(Locale.ROOT) + ":" + (p.raw.fileIdx ?: -1)
        val url = p.raw.url
        if (!url.isNullOrBlank()) return "u:$url"
        return null
    }

    fun process(items: List<ParsedStream>, filter: FilterSettings, sort: SortSettings): List<ParsedStream> {
        // Earlier addons win when two addons return the same torrent/url.
        val seen = HashSet<String>()
        val unique = items
            .sortedBy { it.addonIndex }
            .filter { StreamFilter.passes(it, filter) }
            .filter { p -> dedupeKey(p)?.let { seen.add(it) } ?: true }

        var sorted = unique.sortedWith(StreamSorter.comparator(sort))

        if (filter.maxPerAddon > 0) {
            val perAddon = HashMap<Int, Int>()
            sorted = sorted.filter { p ->
                val n = (perAddon[p.addonIndex] ?: 0) + 1
                perAddon[p.addonIndex] = n
                n <= filter.maxPerAddon
            }
        }
        if (filter.maxTotal > 0) sorted = sorted.take(filter.maxTotal)
        return sorted
    }
}

// ---------------------------------------------------------------------------------------------
// Labels, qualities, magnets, urls
// ---------------------------------------------------------------------------------------------

object StreamLabels {
    fun resolutionLabel(res: Int): String = when (res) {
        0 -> ""
        2160 -> "4K"
        else -> "${res}p"
    }

    fun formatSize(bytes: Long): String =
        if (bytes >= GB) String.format(Locale.US, "%.2f GB", bytes.toDouble() / GB)
        else String.format(Locale.US, "%d MB", bytes / MB)

    private fun firstLine(s: String?): String? =
        s?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

    private fun releaseName(p: ParsedStream): String =
        (p.raw.behaviorHints?.filename?.takeIf { it.isNotBlank() }
            ?: firstLine(p.raw.title)
            ?: firstLine(p.raw.description)
            ?: "").take(90)

    fun build(p: ParsedStream, detailed: Boolean): String {
        if (!detailed) {
            val n = (p.raw.name ?: "").replace('\n', ' ').trim()
            val title = releaseName(p)
            return listOf(p.addonName, n, title).filter { it.isNotBlank() }.joinToString(" ")
        }
        val i = p.info
        val tags = listOfNotNull(
            resolutionLabel(i.resolution).ifEmpty { null },
            i.source, i.hdr, i.codec, i.audio,
            i.sizeBytes?.let { formatSize(it) },
            i.seeders?.let { "$EMOJI_SEEDER $it" },
            if (i.cached == true) EMOJI_BOLT else null,
            if (p.isMagnet) "magnet" else null,
        )
        val head = if (tags.isEmpty()) p.addonName else p.addonName + " · " + tags.joinToString(" · ")
        val title = releaseName(p)
        return if (title.isBlank()) head else "$head | $title"
    }

    /** Value for ExtractorLink.quality, or null for "unknown" (caller maps it to Qualities.Unknown). */
    fun linkQuality(res: Int, overrideAppOrder: Boolean): Int? {
        if (overrideAppOrder) return null
        return when (res) {
            2160, 1440, 1080, 720, 480, 360, 240 -> res
            576 -> 480
            else -> null
        }
    }
}

object Urls {
    /** encodeURIComponent-like, but keeps ':' readable because many addons use ids like tt123:1:2. */
    fun encodeId(id: String): String =
        URLEncoder.encode(id, "UTF-8").replace("+", "%20").replace("%3A", ":")

    fun encodeComponent(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun resource(base: String, resource: String, type: String, id: String, extra: String? = null): String {
        val seg = encodeId(id)
        val encodedType = encodeId(type)
        val path = if (extra.isNullOrBlank()) {
            "$resource/$encodedType/$seg.json"
        } else {
            "$resource/$encodedType/$seg/$extra.json"
        }
        return appendPath(base, path)
    }

    /**
     * Converts a pasted manifest URL into its addon base URL.
     * Query parameters are retained because addons may use them as access tokens.
     */
    fun normalizeAddonUrl(input: String): String {
        var u = input.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        if (u.isEmpty()) return u
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) u = "https://$u"

        val uri = runCatching { URI(u) }.getOrNull() ?: return u.trimEnd('/')
        val authority = uri.rawAuthority ?: return u.trimEnd('/')
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path = path.dropLast("/manifest.json".length)
        }
        val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authority${path.trimEnd('/')}$query"
    }

    /** Builds a manifest URL without placing its path after a query string. */
    fun manifest(base: String): String = appendPath(base, "manifest.json")

    private fun appendPath(base: String, path: String): String {
        val withoutFragment = base.substringBefore('#')
        val queryIndex = withoutFragment.indexOf('?')
        val basePath = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val query = if (queryIndex >= 0) withoutFragment.substring(queryIndex) else ""
        return "${basePath.trimEnd('/')}/$path$query"
    }

    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.stealth.si:80/announce",
    )

    fun magnet(infoHash: String, displayName: String?, sources: List<String>?): String {
        val addonTrackers = sources.orEmpty()
            .filter { it.startsWith("tracker:") }
            .map { it.removePrefix("tracker:") }
            .filter { it.isNotBlank() }
        val trackers = (addonTrackers + DEFAULT_TRACKERS).distinct()
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
        if (!displayName.isNullOrBlank()) sb.append("&dn=").append(encodeComponent(displayName))
        for (t in trackers) sb.append("&tr=").append(encodeComponent(t))
        return sb.toString()
    }
}
