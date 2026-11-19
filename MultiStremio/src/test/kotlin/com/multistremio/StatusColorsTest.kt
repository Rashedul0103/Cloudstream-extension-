package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusColorsTest {
    @Test
    fun darkBackgroundsGetTheLighterStatusColors() {
        assertTrue(StatusColors.isDark(0xff000000.toInt()))
        assertTrue(StatusColors.isDark(0xff121212.toInt()))
        assertEquals(StatusColors.DARK, StatusColors.forBackground(0xff1c1c1e.toInt()))
    }

    @Test
    fun lightBackgroundsGetTheDarkerStatusColors() {
        assertFalse(StatusColors.isDark(0xffffffff.toInt()))
        assertFalse(StatusColors.isDark(0xfffafafa.toInt()))
        assertEquals(StatusColors.LIGHT, StatusColors.forBackground(0xfff5f5f5.toInt()))
    }

    @Test
    fun midpointLuminanceCountsAsLight() {
        // Exactly 128 on every channel gives luminance 128, which is not below the midpoint.
        assertFalse(StatusColors.isDark(0xff808080.toInt()))
        assertTrue(StatusColors.isDark(0xff7f7f7f.toInt()))
    }

    @Test
    fun statusColorsAreOpaqueAndDistinct() {
        for (set in listOf(StatusColors.LIGHT, StatusColors.DARK)) {
            val colors = listOf(set.danger, set.success, set.warning)
            assertEquals(3, colors.toSet().size)
            colors.forEach { assertEquals(0xff, (it ushr 24) and 0xff) }
        }
    }
}
