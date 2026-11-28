package com.doloh.marstekwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** «Графіки відключень» widget: rows per cell height; labelled bars (times above) while at least one fits. */
class ScheduleListPlanTest {
    private fun bars(n: Int) = List(n) { true }

    @Test
    fun rowsFollowTheCellHeight() {
        // labelled row 15.8 + 12 + 12 = 39.8 dp, 6 dp between rows, padding 20 dp
        assertEquals(ScheduleListPlan.Plan(3, true), ScheduleListPlan.plan(160f, bars(5)))
        assertEquals(ScheduleListPlan.Plan(3, true), ScheduleListPlan.plan(197f, bars(7)))
        assertEquals(ScheduleListPlan.Plan(4, true), ScheduleListPlan.plan(200f, bars(7)))
        assertEquals(ScheduleListPlan.Plan(2, true), ScheduleListPlan.plan(200f, bars(2)))
        assertEquals(ScheduleListPlan.Plan(1, true), ScheduleListPlan.plan(61f, bars(3)))
    }

    @Test
    fun rowsWithoutBarTakeOnlyTheirHeader() {
        // 20 − 6 + 3 labelled (3·45.8) + «графік недоступний» (15.8 + 6) + one more labelled (45.8) = 219 dp
        assertEquals(ScheduleListPlan.Plan(5, true), ScheduleListPlan.plan(220f, listOf(true, true, true, false, true)))
        assertEquals(ScheduleListPlan.Plan(4, true), ScheduleListPlan.plan(218f, listOf(true, true, true, false, true)))
        assertEquals(ScheduleListPlan.Plan(4, true), ScheduleListPlan.plan(220f, bars(5)))
    }

    @Test
    fun tinyCellKeepsOnePlainRowAndEmptyListHasNone() {
        assertEquals(ScheduleListPlan.Plan(1, false), ScheduleListPlan.plan(40f, bars(3)))
        assertEquals(0, ScheduleListPlan.plan(160f, emptyList()).rows)
    }
}
