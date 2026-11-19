package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks on the icon data; drawing needs the Android runtime and is verified on a device. */
class SettingsIconsTest {
    @Test
    fun everyIconHasPathDataThatStartsWithAMoveCommand() {
        assertTrue(SettingsIcons.ALL.isNotEmpty())
        SettingsIcons.ALL.forEach { (name, data) ->
            assertTrue("$name has no path data", data.isNotBlank())
            assertTrue("$name must start with an absolute move command", data.trimStart().startsWith("M"))
        }
    }

    @Test
    fun iconNamesAreUniqueAndKebabCase() {
        val names = SettingsIcons.ALL.keys.toList()
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue("$it is not kebab-case", Regex("[a-z]+(-[a-z]+)*").matches(it)) }
    }

    @Test
    fun pathDataUsesOnlySupportedCommandsAndNumbers() {
        val allowed = Regex("[MmLlHhVvCcSsQqTtAaZz0-9.,\\s-]+")
        SettingsIcons.ALL.forEach { (name, data) ->
            assertTrue("$name contains unsupported characters", allowed.matches(data))
        }
    }
}
