package com.multistremio

/**
 * Starts addon subtitle requests only after a playable link has been handed to CloudStream.
 * With no playable link there is no video to attach those tracks to, so subtitle calls are skipped.
 */
internal class SubtitleRequestStartGate {
    private var started = false

    fun tryStart(firstPlayableLinkEmitted: Boolean): Boolean {
        if (started || !firstPlayableLinkEmitted) return false
        started = true
        return true
    }
}
