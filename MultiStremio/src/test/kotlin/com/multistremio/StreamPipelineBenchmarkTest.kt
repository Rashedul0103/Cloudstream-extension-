package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Repeatable JVM-side workload for comparing stream parsing and pipeline costs between commits.
 * It reports timings but deliberately has no speed threshold: shared CI runners are noisy.
 */
class StreamPipelineBenchmarkTest {
    private data class Sample(
        val parseMs: Double,
        val pipelineMs: Double,
        val outputCount: Int,
    )

    @Test
    fun reportsRepeatableStreamPipelineBenchmark() {
        val raw = buildList {
            for (index in 0 until 600) {
                val resolution = if (index % 5 == 0) "2160p" else "1080p"
                val codec = when (index % 3) {
                    0 -> "HEVC"
                    1 -> "AV1"
                    else -> "AVC"
                }
                val audio = when (index % 3) {
                    0 -> "Atmos"
                    1 -> "DD+"
                    else -> "AAC"
                }
                val url = "https://cdn.example.test/video/$index.m3u8?signature=$index"
                val release = "Benchmark.Movie.$index.$resolution.WEB-DL.HDR10.$codec.$audio.ENG"
                add(
                    0 to RawStream(
                        name = release,
                        title = release,
                        url = url,
                        subtitles = listOf(RawSubtitle(url = "https://subs.example.test/en/$index.vtt", lang = "eng")),
                        behaviorHints = BehaviorHints(
                            filename = "$release.mkv",
                            videoSize = (index + 1L) * MB * 2L,
                        ),
                    ),
                )
                if (index % 4 == 0) {
                    add(
                        1 to RawStream(
                            name = "Benchmark.Movie.$index.BENG",
                            url = url,
                            subtitles = listOf(RawSubtitle(url = "https://subs.example.test/bn/$index.vtt", lang = "ben")),
                            behaviorHints = BehaviorHints(
                                proxyHeaders = ProxyHeaders(
                                    request = mapOf("Referer" to "https://origin.example.test/$index"),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        val filter = FilterSettings(
            allowedResolutions = mutableListOf(2160, 1080),
            hideCam = true,
        )
        val sort = SortSettings(
            preferCodecs = mutableListOf("HEVC", "AV1", "AVC"),
            preferAudio = mutableListOf("Atmos", "DD+", "AAC"),
            cachedFirst = true,
        )

        repeat(3) {
            val warmParsed = raw.map { (addon, stream) -> StreamParser.parsed(addon, "Addon $addon", stream) }
            StreamPipeline.process(warmParsed, filter, sort)
        }

        val samples = ArrayList<Sample>(15)
        repeat(15) {
            val parseStart = System.nanoTime()
            val parsed = raw.map { (addon, stream) -> StreamParser.parsed(addon, "Addon $addon", stream) }
            val parseMs = (System.nanoTime() - parseStart) / 1_000_000.0

            val pipelineStart = System.nanoTime()
            val output = StreamPipeline.process(parsed, filter, sort)
            val pipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000.0
            samples += Sample(parseMs, pipelineMs, output.size)
        }

        val expectedOutput = 600
        samples.forEach { assertEquals(expectedOutput, it.outputCount) }
        val parseMedian = median(samples.map { it.parseMs })
        val pipelineMedian = median(samples.map { it.pipelineMs })
        val parseP95 = percentile95(samples.map { it.parseMs })
        val pipelineP95 = percentile95(samples.map { it.pipelineMs })

        println(
            String.format(
                Locale.US,
                "MULTISTREMIO_BENCHMARK schema=1 input=%d unique=%d warmups=3 iterations=%d " +
                    "parse_median_ms=%.3f parse_p95_ms=%.3f pipeline_median_ms=%.3f pipeline_p95_ms=%.3f output=%d",
                raw.size,
                expectedOutput,
                samples.size,
                parseMedian,
                parseP95,
                pipelineMedian,
                pipelineP95,
                samples.last().outputCount,
            ),
        )
        assertTrue(samples.isNotEmpty())
    }

    private fun median(values: List<Double>): Double =
        values.sorted()[values.size / 2]

    private fun percentile95(values: List<Double>): Double {
        val sorted = values.sorted()
        val index = kotlin.math.ceil(sorted.size * 0.95).toInt() - 1
        return sorted[index.coerceIn(sorted.indices)]
    }
}
