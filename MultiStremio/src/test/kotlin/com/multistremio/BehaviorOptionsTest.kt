package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BehaviorOptionsTest {
    @Test
    fun presetTimeoutsMapToTheirSegments() {
        assertEquals(0, BehaviorOptions.timeoutIndex(10))
        assertEquals(1, BehaviorOptions.timeoutIndex(30))
        assertEquals(2, BehaviorOptions.timeoutIndex(60))
    }

    @Test
    fun anyOtherTimeoutIsCustom() {
        assertEquals(BehaviorOptions.CUSTOM_TIMEOUT_INDEX, BehaviorOptions.timeoutIndex(45))
        assertEquals(BehaviorOptions.CUSTOM_TIMEOUT_INDEX, BehaviorOptions.timeoutIndex(5))
        assertEquals(BehaviorOptions.CUSTOM_TIMEOUT_INDEX, BehaviorOptions.timeoutIndex(180))
    }

    @Test
    fun presetSegmentsGiveSecondsAndCustomGivesNone() {
        assertEquals(10, BehaviorOptions.timeoutSeconds(0))
        assertEquals(30, BehaviorOptions.timeoutSeconds(1))
        assertEquals(60, BehaviorOptions.timeoutSeconds(2))
        assertNull(BehaviorOptions.timeoutSeconds(BehaviorOptions.CUSTOM_TIMEOUT_INDEX))
        assertNull(BehaviorOptions.timeoutSeconds(-1))
    }

    @Test
    fun everyPresetRoundTripsThroughItsSegment() {
        for (seconds in listOf(10, 30, 60)) {
            assertEquals(seconds, BehaviorOptions.timeoutSeconds(BehaviorOptions.timeoutIndex(seconds)))
        }
    }

    @Test
    fun segmentCountsMatchTheLabels() {
        assertEquals(4, BehaviorOptions.TIMEOUT_LABELS.size)
        assertEquals(3, BehaviorOptions.ROW_TITLE_LABELS.size)
    }

    @Test
    fun rowTitleModesRoundTrip() {
        for (index in BehaviorOptions.ROW_TITLE_LABELS.indices) {
            assertEquals(index, BehaviorOptions.rowTitleIndex(BehaviorOptions.rowTitleMode(index)))
        }
    }

    @Test
    fun storedRowTitleModesMatchTheSanitizedValues() {
        // Settings.sanitized() only accepts these three upper-case values.
        assertEquals("OFF", BehaviorOptions.rowTitleMode(0))
        assertEquals("PREFIX", BehaviorOptions.rowTitleMode(1))
        assertEquals("SUFFIX", BehaviorOptions.rowTitleMode(2))
    }

    @Test
    fun unknownOrLowerCaseRowTitleModesAreHandled() {
        assertEquals(1, BehaviorOptions.rowTitleIndex("prefix"))
        assertEquals(0, BehaviorOptions.rowTitleIndex("garbage"))
        assertEquals("OFF", BehaviorOptions.rowTitleMode(99))
    }

    @Test
    fun subtitleLanguageSummary() {
        assertEquals("All", BehaviorOptions.subtitleLanguagesValue(emptyList()))
        assertEquals("2 selected", BehaviorOptions.subtitleLanguagesValue(listOf("en", "bn")))
    }
}
