package com.doloh.marstekwidget.domain

import com.doloh.marstekwidget.domain.ScheduleTimeLabels.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTimeLabelsTest {
    // 240 px bar from x = 0; 6 px per character keeps the arithmetic readable («07:00» = 30 px).
    private fun run(vararg segments: ScheduleBarModel.Segment, now: Float? = null, nowText: String? = "10:42", width: Float = 240f) =
        ScheduleTimeLabels.layout(segments.toList(), now, nowText, 0f, width, width, 4f) { it.length * 6f }

    private fun seg(a: Int, b: Int, maybe: Boolean = false) =
        ScheduleBarModel.Segment(a / 24f, b / 24f, maybe, "%02d:00".format(a), if (b == 24) "24:00" else "%02d:00".format(b))

    private fun texts(labels: List<ScheduleTimeLabels.Label>) = labels.map { it.text }

    @Test
    fun noWindowsShowsOnlyNowCenteredOnTheMarker() {
        val labels = run(now = 0.5f, nowText = "12:00")
        assertEquals(listOf("12:00"), texts(labels))
        assertEquals(120f, labels[0].center, 0.01f)
        assertEquals(Kind.NOW, labels[0].kind)
    }

    @Test
    fun windowGetsStartAndEndLabelsAtItsEdges() {
        val labels = run(seg(7, 13), now = 0.75f, nowText = "18:00", width = 480f)
        assertEquals(listOf("07:00", "13:00", "18:00"), texts(labels))
        assertEquals(140f, labels[0].left, 0.01f)   // left edge of the block
        assertEquals(260f, labels[1].right, 0.01f)  // right edge of the block
        assertEquals(360f, labels[2].center, 0.01f)
    }

    @Test
    fun shortWindowMergesIntoOneRangeLabel() {
        val labels = run(seg(7, 9), now = null)
        assertEquals(listOf("07:00–09:00"), texts(labels))
        assertEquals(80f, labels[0].center, 0.01f)
    }

    @Test
    fun nowInsideAShownWindowIsHidden() {
        val labels = run(seg(7, 13), seg(15, 21), now = 10.7f / 24f, width = 480f)
        assertEquals(listOf("07:00", "13:00", "15:00", "21:00"), texts(labels))
        assertTrue(labels.none { it.kind == Kind.NOW })
    }

    @Test
    fun nowCollidingWithAWindowLabelIsHidden() {
        val labels = run(seg(11, 16), now = 10.5f / 24f)
        assertTrue(labels.none { it.kind == Kind.NOW })
    }

    @Test
    fun labelsAreClampedInsideTheBarEdges() {
        val labels = run(seg(0, 24), now = 0.01f)
        assertEquals(listOf("00:00", "24:00"), texts(labels))
        assertEquals(0f, labels.first().left, 0.01f)
        assertEquals(240f, labels.last().right, 0.01f)
    }

    @Test
    fun neighbouringWindowsNeverOverlap() {
        val labels = run(seg(7, 13), seg(14, 21), seg(22, 24, maybe = true), now = 0.3f)
        labels.zipWithNext().forEach { (a, b) -> assertTrue("$a / $b", a.right + 4f <= b.left + 0.01f) }
        assertTrue(labels.any { it.kind == Kind.MAYBE } || labels.size >= 3)
    }

    @Test
    fun crowdedWindowsMergeOrDropButNeverOverlap() {
        // 120 px per 12 h: 07–13 fits both labels only on 480 px; on 240 px it merges, 15–21 too.
        assertEquals(listOf("07:00–13:00", "15:00–21:00"), texts(run(seg(7, 13), seg(15, 21), now = null)))
        // a range label that would still collide is dropped (the text line under the bar lists every window)
        assertEquals(listOf("07:00–13:00"), texts(run(seg(7, 13), seg(14, 15), now = null)))
    }

    @Test
    fun maybeWindowsAreMarkedForTheLighterColour() {
        val labels = run(seg(16, 20, maybe = true), width = 480f)
        assertEquals(listOf(Kind.MAYBE, Kind.MAYBE), labels.map { it.kind })
    }
}
