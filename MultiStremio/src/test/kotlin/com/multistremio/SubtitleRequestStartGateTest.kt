package com.multistremio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRequestStartGateTest {
    @Test
    fun waitsUntilAPlayableLinkHasBeenHandedToCloudStream() {
        val gate = SubtitleRequestStartGate()

        assertFalse(gate.tryStart(firstPlayableLinkEmitted = false))
        assertTrue(gate.tryStart(firstPlayableLinkEmitted = true))
        assertFalse(gate.tryStart(firstPlayableLinkEmitted = true))
    }

    @Test
    fun doesNotStartSubtitleRequestsWhenAllStreamAddonsReturnNoPlayableLink() {
        val gate = SubtitleRequestStartGate()

        // Stream completion alone is not enough: subtitles cannot make an unplayable item play.
        assertFalse(gate.tryStart(firstPlayableLinkEmitted = false))
        assertFalse(gate.tryStart(firstPlayableLinkEmitted = false))
    }
}
