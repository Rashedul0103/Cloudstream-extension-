package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveStreamEmissionTest {
    @Test
    fun doesNotEmitTheSameDirectLinkAgainWhenAnotherAddonReturnsItLater() {
        val filter = FilterSettings()
        val sort = SortSettings()
        val tracker = StreamEmissionTracker(filter)
        val fastAddon = stream(1, "https://cdn.example.test/movie.mkv", "1080p")
        val slowAddonDuplicate = stream(0, "https://cdn.example.test/movie.mkv", "2160p")

        val firstBatch = StreamPipeline.process(
            listOf(fastAddon), filter, sort, applyLimits = false,
        )
        assertEquals(1, tracker.takeNew(firstBatch).size)

        val cumulativeBatch = StreamPipeline.process(
            listOf(fastAddon, slowAddonDuplicate), filter, sort, applyLimits = false,
        )
        assertTrue(tracker.takeNew(cumulativeBatch).isEmpty())
        assertEquals(1, tracker.emittedCount)
    }

    @Test
    fun enforcesGlobalLimitAcrossProgressiveBatches() {
        val filter = FilterSettings(maxTotal = 2)
        val tracker = StreamEmissionTracker(filter)
        val sort = SortSettings()
        val first = stream(0, "https://cdn.example.test/one.mkv", "1080p")
        val second = stream(1, "https://cdn.example.test/two.mkv", "720p")
        val third = stream(2, "https://cdn.example.test/three.mkv", "2160p")

        val firstBatch = StreamPipeline.process(
            listOf(first, second), filter, sort, applyLimits = false,
        )
        assertEquals(2, tracker.takeNew(firstBatch).size)

        val laterBatch = StreamPipeline.process(
            listOf(first, second, third), filter, sort, applyLimits = false,
        )
        assertTrue(tracker.takeNew(laterBatch).isEmpty())
        assertEquals(2, tracker.emittedCount)
    }

    @Test
    fun enforcesPerAddonLimitAcrossBatchesWithoutBlockingOtherAddons() {
        val filter = FilterSettings(maxPerAddon = 1)
        val tracker = StreamEmissionTracker(filter)
        val sort = SortSettings()
        val firstFromAddon = stream(0, "https://cdn.example.test/first.mkv", "1080p")
        val secondFromSameAddon = stream(0, "https://cdn.example.test/second.mkv", "2160p")
        val fromOtherAddon = stream(1, "https://cdn.example.test/other.mkv", "720p")

        val firstBatch = StreamPipeline.process(
            listOf(firstFromAddon), filter, sort, applyLimits = false,
        )
        assertEquals(1, tracker.takeNew(firstBatch).size)

        val cumulativeBatch = StreamPipeline.process(
            listOf(firstFromAddon, secondFromSameAddon, fromOtherAddon),
            filter,
            sort,
            applyLimits = false,
        )
        val additions = tracker.takeNew(cumulativeBatch)
        assertEquals(listOf("https://cdn.example.test/other.mkv"), additions.map { it.raw.url })
        assertEquals(2, tracker.emittedCount)
    }

    private fun stream(addonIndex: Int, url: String, resolution: String): ParsedStream =
        StreamParser.parsed(
            addonIndex,
            "Addon $addonIndex",
            RawStream(
                name = "Test.Movie.$resolution.WEB-DL",
                title = "Test Movie $resolution",
                url = url,
            ),
        )
}
