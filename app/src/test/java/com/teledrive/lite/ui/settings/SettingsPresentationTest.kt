package com.teledrive.lite.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test
    fun chunkChoicesStayVisibleInRowsOfAtMostThreeButtons() {
        val rows = SettingsPresentation.chunkSizeRows()

        assertEquals(listOf(listOf(4, 8, 12), listOf(18, 19)), rows)
        assertTrue(rows.all { it.size <= 3 })
        assertTrue(rows.flatten().contains(19))
    }
}
