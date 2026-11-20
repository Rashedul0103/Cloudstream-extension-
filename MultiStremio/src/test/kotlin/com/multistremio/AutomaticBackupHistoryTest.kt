package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticBackupHistoryTest {
    @Test
    fun historyKeepsOnlyTheFiveNewestSnapshots() {
        val entries = (1L..7L).map { AutomaticBackupEntry(it, "reason $it", "payload $it") }
        val kept = entries.fold(emptyList(), AutomaticBackupHistory::append)

        assertEquals(5, kept.size)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), kept.map { it.createdAt })
    }
}
