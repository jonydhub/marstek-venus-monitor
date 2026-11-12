package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SignedWattsFormatterTest {
    @Test
    fun preservesRawApiSignWithoutAssigningImportOrExportMeaning() {
        assertEquals("−330\u00A0Вт", BatteryDisplayFormatter.formatSignedWatts(-330.0))
        assertEquals("+250\u00A0Вт", BatteryDisplayFormatter.formatSignedWatts(250.0))
        assertEquals("0\u00A0Вт", BatteryDisplayFormatter.formatSignedWatts(0.0))
    }

    @Test
    fun missingOrNonFinitePowerIsUnavailableRatherThanZero() {
        assertEquals("—", BatteryDisplayFormatter.formatSignedWatts(null))
        assertEquals("—", BatteryDisplayFormatter.formatSignedWatts(Double.NaN))
    }
}
