package com.multistremio

import java.net.URLEncoder
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------
// Parsing, filtering, deduplicating and sorting of Stremio streams.
// Pure Kotlin (no Android / CloudStream classes) so it can be unit tested on a plain JVM.
// ---------------------------------------------------------------------------------------------

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
        "GB" to "en", "US" to "en", "AU" to "en", "CA" to "en", "NZ" to "en",
        "FR" to "fr", "BE" to "fr", "ES" to "es", "MX" to "es", "AR" to "es", "CL" to "es",
        "DE" to "de", "AT" to "de", "CH" to "de", "IT" to "it", "PT" to "pt", "BR" to "pt",
        "RU" to "ru", "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh",
        "IN" to "hi", "TR" to "tr", "SA" to "ar", "EG" to "ar", "AE" to "ar",
        "BD" to "bn", "PK" to "ur", "IR" to "fa", "PL" to "pl", "NL" to "nl",
        "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi", "GR" to "el",
        "UA" to "uk", "TH" to "th", "ID" to "id", "VN" to "vi", "IL" to "he",
        "CZ" to "cs", "HU" to "hu", "RO" to "ro", "MY" to "ms", "PH" to "tl",
    )

    /** ISO 639 codes, common subtitle tags and common release-name aliases -> ISO 639-1. */
    private val ALIASES: Map<String, String> = buildMap {
        val rows = listOf(
            "en" to listOf("eng", "english"),
            "hi" to listOf("hin", "hindi"),
            "fr" to listOf("fra", "fre", "french", "francais", "français", "truefrench", "vff", "vfq", "vfi", "vf2"),
            "es" to listOf("spa", "spanish", "espanol", "español", "castellano", "latino", "latam", "esp"),
            "de" to listOf("deu", "ger", "german", "deutsch"),
            "it" to listOf("ita", "italian", "italiano"),
            "pt" to listOf("por", "pob", "portuguese", "portugues", "português", "brazilian portuguese", "dublado"),
            "ru" to listOf("rus", "russian"),
            "ja" to listOf("jpn", "jap", "japanese", "japonais"),
            "ko" to listOf("kor", "korean"),
            "zh" to listOf("zho", "chi", "cmn", "yue", "chinese", "mandarin", "cantonese"),
            "tr" to listOf("tur", "turkish", "turkce", "türkçe"),
            "ar" to listOf("ara", "arabic", "العربية"),
            "bn" to listOf("ben", "beng", "bengali", "bangla", "বাংলা"),
            "ta" to listOf("tam", "tamil"),
            "te" to listOf("tel", "telugu"),
            "ur" to listOf("urd", "urdu", "اردو"),
            "fa" to listOf("fas", "per", "persian", "farsi", "فارسی"),
            "pl" to listOf("pol", "polish", "polski"),
            "nl" to listOf("nld", "dut", "dutch", "nederlands"),
            "sv" to listOf("swe", "swedish"),
            "no" to listOf("nor", "norwegian"),
            "da" to listOf("dan", "danish"),
            "fi" to listOf("fin", "finnish"),
            "el" to listOf("ell", "gre", "greek"),
            "uk" to listOf("ukr", "ukrainian"),
            "th" to listOf("tha", "thai"),
            "id" to listOf("ind", "indonesian", "bahasa"),
            "vi" to listOf("vie", "vietnamese"),
            "he" to listOf("heb", "hebrew"),
            "cs" to listOf("ces", "cze", "czech"),
            "hu" to listOf("hun", "hungarian"),
            "ro" to listOf("ron", "rum", "romanian"),
            "ms" to listOf("msa", "may", "malay"),
            "tl" to listOf("tgl", "fil", "filipino", "tagalog"),
            "multi" to listOf("dual audio", "dual-audio", "multiaudio", "multi audio"),
            "und" to listOf("unknown", "undefined", "undetermined"),
        )
        for ((twoLetter, aliases) in rows) {
            put(twoLetter, twoLetter)
            aliases.forEach { put(it, twoLetter) }
        }
    }

    fun normalize(input: String): String {
        val raw = input.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (raw.isEmpty()) return ""
        val label = raw.substringBefore('(').substringBefore('[').trim()
        ALIASES[label]?.let { return it }

        // BCP-47 forms such as en-US, pt-BR and zh-Hans share their primary language code.
        val primary = label.substringBefore('-').trim()
        ALIASES[primary]?.let { return it }

        val compact = label.filter { it.isLetterOrDigit() }
        ALIASES[compact]?.let { return it }
        return primary.ifEmpty { label }
    }

    // Release markers are deliberately limited to clear words / 3-letter tags to avoid
    // interpreting ordinary title words such as "it", "no" or "in" as language codes.
    private val WORD_LANGS = listOf(
        Regex("""\b(english|eng)\b""") to "en",
        Regex("""\b(hindi|hin)\b""") to "hi",
        Regex("""\b(french|français|francais|fra|fre|truefrench|vff|vfq|vfi)\b""") to "fr",
        Regex("""\b(spanish|español|espanol|castellano|latino|latam|spa|esp)\b""") to "es",
        Regex("""\b(german|deutsch|deu|ger)\b""") to "de",
        Regex("""\b(italian|italiano|ita)\b""") to "it",
        Regex("""\b(portuguese|português|portugues|dublado|por|pob)\b""") to "pt",
        Regex("""\b(russian|rus)\b""") to "ru",
        Regex("""\b(japanese|jpn|jap)\b""") to "ja",
        Regex("""\b(korean|kor)\b""") to "ko",
        Regex("""\b(chinese|mandarin|cantonese|zho|chi)\b""") to "zh",
        Regex("""\b(turkish|türkçe|tur)\b""") to "tr",
        Regex("""\b(arabic|ara)\b""") to "ar",
        Regex("""\b(bengali|bangla|beng|ben)\b""") to "bn",
        Regex("""\b(tamil|tam)\b""") to "ta",
        Regex("""\b(telugu|tel)\b""") to "te",
        Regex("""\b(urdu|urd)\b""") to "ur",
        Regex("""\b(persian|farsi|fas|per)\b""") to "fa",
        Regex("""\b(polish|polski|pol)\b""") to "pl",
        Regex("""\b(dutch|nederlands|nld|dut)\b""") to "nl",
        Regex("""\b(sweden|swedish|swe)\b""") to "sv",
        Regex("""\b(norwegian|nor)\b""") to "no",
        Regex("""\b(danish|dan)\b""") to "da",
        Regex("""\b(finnish|fin)\b""") to "fi",
        Regex("""\b(greek|ell|gre)\b""") to "el",
        Regex("""\b(ukrainian|ukr)\b""") to "uk",
        Regex("""\b(thai|tha)\b""") to "th",
        Regex("""\b(indonesian|bahasa|ind)\b""") to "id",
        Regex("""\b(vietnamese|vie)\b""") to "vi",
        Regex("""\b(hebrew|heb)\b""") to "he",
        Regex("""\b(czech|ces|cze)\b""") to "cs",
        Regex("""\b(hungarian|hun)\b""") to "hu",
        Regex("""\b(romanian|ron|rum)\b""") to "ro",
        Regex("""\b(malay|msa|may)\b""") to "ms",
        Regex("""\b(filipino|tagalog|fil|tgl)\b""") to "tl",
    )
    private val MULTI = Regex("""\b(multi|dual[ ._\-]?audio|multi[ ._\-]?audio)\b""")

    private val NATIVE_NAMES = listOf(
        "বাংলা" to "bn", "हिंदी" to "hi", "हिन्दी" to "hi", "اردو" to "ur",
        "العربية" to "ar", "فارسی" to "fa", "日本語" to "ja", "한국어" to "ko",
        "中文" to "zh", "français" to "fr", "español" to "es", "português" to "pt",
        "türkçe" to "tr", "русский" to "ru", "deutsch" to "de",
    )

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

    fun detect(text: String): Set<String> {
        val normalizedText = text.lowercase(Locale.ROOT)
        val found = linkedSetOf<String>()
        for (country in flagCountries(text)) FLAG_TO_LANG[country]?.let { found += it }
        for ((pattern, code) in WORD_LANGS) if (pattern.containsMatchIn(normalizedText)) found += code
        for ((nativeName, code) in NATIVE_NAMES) {
            if (normalizedText.contains(nativeName)) found += code
        }
        if (MULTI.containsMatchIn(normalizedText)) found += "multi"
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
    private val WEB_DL_STRICT = Regex("""\bweb[ .\-_]?dl\b""")
    private val WEBRIP = Regex("""\bweb[ .\-_]?rip\b""")
    private val ATMOS = Regex("""\batmos\b""")
    private val TRUEHD = Regex("""\btruehd\b""")
    private val DTS_HD = Regex("""\bdts[ .\-_]?(hd|x)\b""")
    private val DTS = Regex("""\bdts\b""")
    private val DD_PLUS = Regex("""\b(ddp|dd\+|e[ \-]?ac-?3)(?![a-z0-9])""")
    private val DD = Regex("""\b(dd[ .\-_]?5|ac-?3|dolby digital)\b""")
    private val AAC = Regex("""\baac\b""")
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
            WEB_DL_STRICT.containsMatchIn(t) || HDTV.containsMatchIn(t)
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
            ATMOS.containsMatchIn(t) -> "Atmos"
            TRUEHD.containsMatchIn(t) -> "TrueHD"
            DTS_HD.containsMatchIn(t) -> "DTS-HD"
            DTS.containsMatchIn(t) -> "DTS"
            DD_PLUS.containsMatchIn(t) -> "DD+"
            DD.containsMatchIn(t) -> "DD"
            AAC.containsMatchIn(t) -> "AAC"
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
            return compiledRegex(k).containsMatchIn(lowerText)
        }
        return lowerText.contains(k.lowercase(Locale.ROOT))
    }

    private fun compiledRegex(pattern: String): Regex = regexCache.getOrPut(pattern) {
        try {
            Regex(pattern.substring(1, pattern.length - 1), RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            NEVER
        }
    }

    fun anyMatch(lowerText: String, keywords: List<String>): Boolean =
        keywords.any { matchesKeyword(lowerText, it) }

    /** Finds slash-delimited regular expressions that cannot be compiled before settings are saved. */
    fun invalidRegexPatterns(keywords: List<String>): List<String> =
        keywords.map { it.trim() }
            .filter { it.length > 2 && it.startsWith("/") && it.endsWith("/") }
            .filter { pattern -> compiledRegex(pattern) === NEVER }
            .distinct()

    /** Remove malformed legacy required-regex entries once per pipeline, not once per stream. */
    fun validRequiredKeywords(keywords: List<String>): List<String> =
        keywords.filterNot { invalidRegexPatterns(listOf(it)).isNotEmpty() }

    fun passes(p: ParsedStream, f: FilterSettings): Boolean =
        passes(
            p,
            f,
            f.requireLanguages.map { Langs.normalize(it) }.toSet(),
            validRequiredKeywords(f.requireKeywords),
        )

    fun passes(p: ParsedStream, f: FilterSettings, wantedLanguages: Set<String>): Boolean =
        passes(p, f, wantedLanguages, validRequiredKeywords(f.requireKeywords))

    fun passes(
        p: ParsedStream,
        f: FilterSettings,
        wantedLanguages: Set<String>,
        validRequiredKeywords: List<String>,
    ): Boolean {
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
        // Invalid regexes may exist in older backups. Ignore them at runtime rather than allowing
        // one malformed "require" pattern to hide every stream after an upgrade.
        if (validRequiredKeywords.isNotEmpty() && !anyMatch(p.text, validRequiredKeywords)) return false

        val have = i.languages
        if (f.strictLanguage && have.isEmpty()) return false
        if (f.requireLanguages.isNotEmpty()) {
            val ok = when {
                "multi" in have -> true
                have.isEmpty() -> !f.strictLanguage
                else -> have.any { it in wantedLanguages }
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

    private fun canonicalCodec(value: String?): String? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "hevc", "h265", "h.265", "x265" -> "HEVC"
        "av1" -> "AV1"
        "avc", "h264", "h.264", "x264" -> "AVC"
        else -> null
    }

    private fun canonicalAudio(value: String?): String? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "atmos", "dolby atmos" -> "Atmos"
        "truehd", "dolby truehd" -> "TrueHD"
        "dts-hd", "dtshd", "dts:x", "dts-x" -> "DTS-HD"
        "dts" -> "DTS"
        "dd+", "ddp", "e-ac-3", "eac3", "dolby digital plus" -> "DD+"
        "dd5.1", "ac3", "ac-3", "dolby digital" -> "DD"
        "aac" -> "AAC"
        else -> null
    }

    private fun <T> preferenceRank(value: T?, preferences: List<String>, canonical: (String?) -> String?): Int {
        if (preferences.isEmpty()) return 0
        val wanted = preferences.mapNotNull { canonical(it) }
        val actual = canonical(value?.toString())
        if (actual == null) return wanted.size + 2
        val position = wanted.indexOf(actual)
        return if (position >= 0) position else wanted.size + 1
    }

    fun comparator(s: SortSettings): Comparator<ParsedStream> {
        val chain = mutableListOf<Comparator<ParsedStream>>()

        if (s.preferKeywords.isNotEmpty()) {
            chain += compareByDescending<ParsedStream> {
                if (StreamFilter.anyMatch(it.text, s.preferKeywords)) 1 else 0
            }
        }
        if (s.preferLanguages.isNotEmpty()) {
            // Preserve the user's language order instead of treating every preferred language
            // as an equal-priority member of a Set. When a release has multiple tracks, its best
            // (earliest) matching preference determines its rank.
            val wanted = s.preferLanguages.map { Langs.normalize(it) }
                .filter { it.isNotBlank() && it != "und" }
                .distinct()
            if (wanted.isNotEmpty()) {
                val positions = wanted.withIndex().associate { it.value to it.index }
                chain += compareBy<ParsedStream> {
                    val have = it.info.languages
                    when {
                        "multi" in have -> -1
                        have.isEmpty() -> wanted.size
                        else -> have.mapNotNull { language -> positions[language] }.minOrNull()
                            ?: wanted.size + 1
                    }
                }
            }
        }
        if (s.preferCodecs.isNotEmpty()) {
            chain += compareBy<ParsedStream> { preferenceRank(it.info.codec, s.preferCodecs, ::canonicalCodec) }
        }
        if (s.preferAudio.isNotEmpty()) {
            chain += compareBy<ParsedStream> { preferenceRank(it.info.audio, s.preferAudio, ::canonicalAudio) }
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
// URL normalization for safe duplicate detection.
// Query parameters and fragments are preserved because they may contain signed credentials
// or player-specific playback instructions.
// ---------------------------------------------------------------------------------------------

object StreamDeduplication {
    fun normalizeUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return raw
        val rawHost = uri.host ?: return raw
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val path = uri.normalize().rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$userInfo$renderedHost$port$path$query$fragment"
    }

    fun key(stream: ParsedStream): String? {
        // Match emission precedence: a direct URL wins when an addon mistakenly supplies both
        // a URL and an infoHash. Otherwise separate direct links can be collapsed by a shared hash.
        val url = normalizeUrl(stream.raw.url)
        if (url != null) return "u:$url"
        val hash = stream.raw.infoHash
        if (!hash.isNullOrBlank()) {
            return "h:${hash.lowercase(Locale.ROOT)}:${stream.raw.fileIdx ?: -1}"
        }
        return null
    }

    /** Stable identity for extractor-only targets that do not enter the playable stream pipeline. */
    fun externalKey(stream: ParsedStream): String? {
        stream.raw.ytId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "yt:${it}"
        }
        val url = normalizeUrl(stream.raw.externalUrl) ?: return null
        return "external:$url"
    }
}

// ---------------------------------------------------------------------------------------------
// Pipeline: filter -> dedupe -> sort -> limit
// ---------------------------------------------------------------------------------------------


/** Combines addon playback headers using HTTP's case-insensitive header-name rules. */
object StreamRequestHeaders {
    /**
     * Merge maps with [preferred] taking precedence over [fallback].
     * Header names are case-insensitive, and the winning entry keeps its original spelling.
     */
    fun merge(
        preferred: Map<String, String>?,
        fallback: Map<String, String>?,
    ): Map<String, String> {
        val merged = linkedMapOf<String, Pair<String, String>>()
        fallback.orEmpty().forEach { (name, value) ->
            val key = name.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty()) merged[key] = name.trim() to value
        }
        preferred.orEmpty().forEach { (name, value) ->
            val key = name.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty()) merged[key] = name.trim() to value
        }
        return merged.values.associate { (name, value) -> name to value }
    }

    /** Proxy request headers override addon headers, while missing addon headers are retained. */
    fun from(hints: BehaviorHints?): Map<String, String> =
        merge(hints?.proxyHeaders?.request, hints?.headers)

    fun referer(headers: Map<String, String>): String =
        headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value.orEmpty()
}

object StreamPipeline {
    private fun dedupeKey(p: ParsedStream): String? = StreamDeduplication.key(p)

    private fun mergeText(first: String?, second: String?): String? {
        val values = listOfNotNull(first?.takeIf { it.isNotBlank() }, second?.takeIf { it.isNotBlank() })
            .distinct()
        return values.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun mergeSubtitles(first: List<RawSubtitle>?, second: List<RawSubtitle>?): List<RawSubtitle>? {
        val values = (first.orEmpty() + second.orEmpty())
            .filter { !it.url.isNullOrBlank() }
            .distinctBy { it.url }
        return values.takeIf { it.isNotEmpty() }
    }

    private fun mergeDuplicate(first: ParsedStream, second: ParsedStream): ParsedStream {
        val a = first.raw
        val b = second.raw
        val firstHints = a.behaviorHints
        val secondHints = b.behaviorHints
        // Flatten each stream's effective headers first (proxy overrides normal within
        // that stream), then let the primary stream win across both sources.
        // This prevents a secondary proxy map from overriding primary normal headers.
        val headers = StreamRequestHeaders.merge(
            preferred = StreamRequestHeaders.from(firstHints),
            fallback = StreamRequestHeaders.from(secondHints),
        ).takeIf { it.isNotEmpty() }
        val hints = if (firstHints == null && secondHints == null) null else BehaviorHints(
            filename = firstHints?.filename?.takeIf { it.isNotBlank() } ?: secondHints?.filename,
            videoSize = firstHints?.videoSize ?: secondHints?.videoSize,
            bingeGroup = firstHints?.bingeGroup ?: secondHints?.bingeGroup,
            notWebReady = firstHints?.notWebReady ?: secondHints?.notWebReady,
            proxyHeaders = null,
            headers = headers,
        )
        val mergedRaw = a.copy(
            name = mergeText(a.name, b.name),
            title = mergeText(a.title, b.title),
            description = mergeText(a.description, b.description),
            url = a.url?.takeIf { it.isNotBlank() } ?: b.url,
            ytId = a.ytId?.takeIf { it.isNotBlank() } ?: b.ytId,
            externalUrl = a.externalUrl?.takeIf { it.isNotBlank() } ?: b.externalUrl,
            infoHash = a.infoHash?.takeIf { it.isNotBlank() } ?: b.infoHash,
            fileIdx = a.fileIdx ?: b.fileIdx,
            sources = (a.sources.orEmpty() + b.sources.orEmpty()).distinct().takeIf { it.isNotEmpty() },
            subtitles = mergeSubtitles(a.subtitles, b.subtitles),
            behaviorHints = hints,
        )
        val (info, text) = StreamParser.parse(mergedRaw)
        // Keep the first addon's identity and priority, but enrich its metadata and subtitles.
        return ParsedStream(first.addonIndex, first.addonName, mergedRaw, text, info)
    }

    fun process(items: List<ParsedStream>, filter: FilterSettings, sort: SortSettings): List<ParsedStream> {
        // Merge duplicate records before filtering so a richer copy from another addon can fill
        // missing resolution, language, filename, headers or subtitle information.
        val positions = HashMap<String, Int>()
        val unique = ArrayList<ParsedStream>(items.size)
        for (stream in items.sortedBy { it.addonIndex }) {
            val key = dedupeKey(stream)
            if (key == null) {
                unique += stream
                continue
            }
            val existingIndex = positions[key]
            if (existingIndex == null) {
                positions[key] = unique.size
                unique += stream
            } else {
                unique[existingIndex] = mergeDuplicate(unique[existingIndex], stream)
            }
        }

        val wantedLanguages = filter.requireLanguages.map { Langs.normalize(it) }.toSet()
        val validRequiredKeywords = StreamFilter.validRequiredKeywords(filter.requireKeywords)
        val filtered = unique.filter {
            StreamFilter.passes(it, filter, wantedLanguages, validRequiredKeywords)
        }
        val sorted = filtered.sortedWith(StreamSorter.comparator(sort))
        if (filter.maxPerAddon <= 0 && filter.maxTotal <= 0) return sorted

        // Apply both limits in one pass and stop as soon as the total cap is reached.
        val perAddon = HashMap<Int, Int>()
        val result = ArrayList<ParsedStream>(minOf(sorted.size, filter.maxTotal.takeIf { it > 0 } ?: sorted.size))
        for (stream in sorted) {
            if (filter.maxPerAddon > 0) {
                val count = (perAddon[stream.addonIndex] ?: 0) + 1
                if (count > filter.maxPerAddon) continue
                perAddon[stream.addonIndex] = count
            }
            result += stream
            if (filter.maxTotal > 0 && result.size >= filter.maxTotal) break
        }
        return result
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
        val detectedLanguages = i.languages
            .filter { it != "und" }
            .map { if (it == "multi") "MULTI" else it.uppercase(Locale.ROOT) }
            .distinct()
            .sorted()
            .joinToString("/")
        val subtitleLanguages = p.raw.subtitles.orEmpty()
            .mapNotNull { it.lang?.takeIf { lang -> lang.isNotBlank() } }
            .map { Langs.normalize(it) }
            .filter { it != "und" }
            .map { it.uppercase(Locale.ROOT) }
            .distinct()
            .sorted()
            .joinToString("/")
        val tags = listOfNotNull(
            resolutionLabel(i.resolution).ifEmpty { null },
            i.source, i.hdr, i.codec, i.audio,
            detectedLanguages.takeIf { it.isNotBlank() }?.let { "Lang $it" },
            subtitleLanguages.takeIf { it.isNotBlank() }?.let { "Subs $it" },
            i.sizeBytes?.let { formatSize(it) },
            i.seeders?.let { "$EMOJI_SEEDER $it" },
            if (i.cached == true) "$EMOJI_BOLT Cached" else if (i.cached == false) "Uncached" else null,
            if (p.isMagnet) "magnet" else null,
        )
        val head = if (tags.isEmpty()) p.addonName else p.addonName + " · " + tags.joinToString(" · ")
        val title = releaseName(p)
        return if (title.isBlank()) head else "$head | $title"
    }

    private fun languageList(p: ParsedStream): String = p.info.languages
        .filter { it != "und" }
        .map { if (it == "multi") "MULTI" else it.uppercase(Locale.ROOT) }
        .distinct()
        .sorted()
        .joinToString("/")

    private fun subtitleLanguageList(p: ParsedStream): String = p.raw.subtitles.orEmpty()
        .mapNotNull { it.lang?.takeIf { lang -> lang.isNotBlank() } }
        .map { Langs.normalize(it) }
        .filter { it != "und" }
        .map { it.uppercase(Locale.ROOT) }
        .distinct()
        .sorted()
        .joinToString("/")

    private fun valueFor(p: ParsedStream, field: String): String? {
        val info = p.info
        return when (field) {
            "addon" -> p.addonName
            "quality" -> resolutionLabel(info.resolution).ifBlank { null }
            "source" -> info.source
            "hdr" -> info.hdr
            "codec" -> info.codec
            "audio" -> info.audio
            "language" -> languageList(p).ifBlank { null }
            "subtitles" -> subtitleLanguageList(p).ifBlank { null }
            "size" -> info.sizeBytes?.let { formatSize(it) }
            "seeders" -> info.seeders?.let { "$it seeders" }
            "cache" -> when (info.cached) { true -> "Cached"; false -> "Uncached"; else -> null }
            "link_type" -> when { p.isMagnet -> "Magnet"; p.isDirect -> "Direct"; else -> null }
            "release" -> releaseName(p).ifBlank { null }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun labelFor(field: String): String = when (field) {
        "addon" -> "Addon"
        "quality" -> "Quality"
        "source" -> "Source"
        "hdr" -> "HDR"
        "codec" -> "Video codec"
        "audio" -> "Audio"
        "language" -> "Language"
        "subtitles" -> "Subtitles"
        "size" -> "File size"
        "seeders" -> "Seeders"
        "cache" -> "Availability"
        "link_type" -> "Link type"
        "release" -> "Release"
        else -> field
    }

    fun build(p: ParsedStream, format: StreamFormatSettings): String =
        build(p, detailed = true, format = format)

    fun build(p: ParsedStream, detailed: Boolean, format: StreamFormatSettings): String {
        if (!format.enabled) return build(p, detailed = detailed)
        val pieces = format.fields.asSequence()
            .distinct()
            .mapNotNull { field ->
                val value = valueFor(p, field) ?: return@mapNotNull null
                if (format.showLabels) "${labelFor(field)}: $value"
                else when (field) {
                    "language" -> "Lang $value"
                    "subtitles" -> "Subs $value"
                    else -> value
                }
            }
            .toList()
        return pieces.joinToString(format.separator.take(8)).ifBlank { p.addonName }
    }

    fun preview(format: StreamFormatSettings): String {
        val example = StreamParser.parsed(
            0,
            "Example Addon",
            RawStream(
                name = "Example.Movie.2026.2160p.WEB-DL.HDR10.HEVC.Atmos.BENG.ENG",
                title = "Example.Movie.2026.2160p.WEB-DL.HDR10.HEVC.Atmos.BENG.ENG",
                url = "https://example.invalid/movie.mkv",
                subtitles = listOf(
                    RawSubtitle(url = "https://example.invalid/en.vtt", lang = "eng"),
                    RawSubtitle(url = "https://example.invalid/bn.vtt", lang = "ben"),
                ),
                behaviorHints = BehaviorHints(
                    filename = "Example.Movie.2026.2160p.WEB-DL.HDR10.HEVC.Atmos.BENG.ENG.mkv",
                    videoSize = 1_500_000_000L,
                ),
            ),
        )
        return build(example, format.copy(enabled = true))
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
        val rawAuthority = uri.rawAuthority ?: return u.trimEnd('/')
        val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
        val host = uri.host
        val authority = if (host.isNullOrBlank()) {
            rawAuthority
        } else {
            val normalizedHost = host.lowercase(Locale.ROOT).let {
                if (it.contains(':') && !it.startsWith("[")) "[$it]" else it
            }
            buildString {
                uri.rawUserInfo?.let { append(it).append('@') }
                append(normalizedHost)
                val isDefaultPort = (scheme == "https" && uri.port == 443) ||
                    (scheme == "http" && uri.port == 80)
                if (uri.port >= 0 && !isDefaultPort) append(':').append(uri.port)
            }
        }
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path = path.dropLast("/manifest.json".length)
        }
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

    fun magnet(
        infoHash: String,
        displayName: String?,
        sources: List<String>?,
        fileIdx: Int? = null,
    ): String {
        val addonTrackers = sources.orEmpty()
            .filter { it.startsWith("tracker:") }
            .map { it.removePrefix("tracker:") }
            .filter { it.isNotBlank() }
        val trackers = (addonTrackers + DEFAULT_TRACKERS).distinct()
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
        if (!displayName.isNullOrBlank()) sb.append("&dn=").append(encodeComponent(displayName))
        for (tracker in trackers) sb.append("&tr=").append(encodeComponent(tracker))
        // CloudStream's built-in TorrentStatus.streamUrl reads the "index" parameter from the
        // magnet URI and passes it to TorrServer's stream endpoint. This preserves Stremio fileIdx.
        if (fileIdx != null && fileIdx >= 0) sb.append("&index=").append(fileIdx)
        return sb.toString()
    }
}
