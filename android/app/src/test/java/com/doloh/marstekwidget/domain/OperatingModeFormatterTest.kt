package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatingModeFormatterTest {
    @Test
    fun localizesKnownModeNames() {
        assertEquals("Ручний", OperatingModeFormatter.format("Manual"))
        assertEquals("Автоматичний", OperatingModeFormatter.format("AUTO"))
        assertEquals("Власне споживання", OperatingModeFormatter.format("self_use"))
    }

    @Test
    fun preservesUnknownModesAndHandlesMissingValue() {
        assertEquals("Custom mode", OperatingModeFormatter.format("Custom mode"))
        assertEquals("—", OperatingModeFormatter.format(null))
        assertEquals("—", OperatingModeFormatter.format("  "))
    }
}
