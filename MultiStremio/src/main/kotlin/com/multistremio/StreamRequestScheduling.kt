package com.multistremio

/**
 * Keeps subtitle requests out of the shared HTTP semaphore until at least one playable link
 * has been handed to CloudStream. If no playable link can be emitted, subtitle lookup starts
 * only after every stream addon has finished.
 */
internal class SubtitleRequestStartGate {
    private var started = false

    fun tryStart(
        firstPlayableLinkEmitted: Boolean,
        allStreamAddonsCompleted: Boolean,
    ): Boolean {
        if (started || (!firstPlayableLinkEmitted && !allStreamAddonsCompleted)) return false
        started = true
        return true
    }
}
