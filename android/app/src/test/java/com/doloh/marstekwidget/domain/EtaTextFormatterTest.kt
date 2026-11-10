package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EtaTextFormatterTest {
    @Test
    fun roundsRemainingMinutesUp() {
        assertEquals("9 хв", EtaTextFormatter.formatSeconds(524L))
    }

    @Test
    fun formatsHoursAndMinutes() {
        assertEquals("1 год 2 хв", EtaTextFormatter.formatSeconds(3_670L))
    }

    @Test
    fun zeroMeansComplete() {
        assertEquals("Готово", EtaTextFormatter.formatSeconds(0L))
    }

    @Test
    fun longDurationsShowDaysAndHours() {
        assertEquals("2 д 3 год", EtaTextFormatter.formatSeconds(2 * 86_400L + 3 * 3_600L + 10L))
    }

}
