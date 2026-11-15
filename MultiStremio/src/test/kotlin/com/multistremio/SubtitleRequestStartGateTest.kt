package com.multistremio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRequestStartGateTest {
    @Test
    fun waitsForFirstPlayableLinkWhileStreamAddonsAreStillWorking() {
        val gate = SubtitleRequestStartGate()

        assertFalse(gate.tryStart(firstPlayableLinkEmitted = false, allStreamAddonsCompleted = false))
        assertTrue(gate.tryStart(firstPlayableLinkEmitted = true, allStreamAddonsCompleted = false))
        assertFalse(gate.tryStart(firstPlayableLinkEmitted = true, allStreamAddonsCompleted = false))
    }

    @Test
    fun startsAfterAllStreamAddonsCompleteWhenNoPlayableLinkWasEmitted() {
        val gate = SubtitleRequestStartGate()

        assertFalse(gate.tryStart(firstPlayableLinkEmitted = false, allStreamAddonsCompleted = false))
        assertTrue(gate.tryStart(firstPlayableLinkEmitted = false, allStreamAddonsCompleted = true))
        assertFalse(gate.tryStart(firstPlayableLinkEmitted = true, allStreamAddonsCompleted = true))
    }

    @Test
    fun doesNotStartTwiceWhenBothConditionsBecomeTrue() {
        val gate = SubtitleRequestStartGate()

        assertTrue(gate.tryStart(firstPlayableLinkEmitted = true, allStreamAddonsCompleted = true))
        assertFalse(gate.tryStart(firstPlayableLinkEmitted = true, allStreamAddonsCompleted = true))
    }
}
