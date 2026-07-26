package org.onekash.kashcal.ui.components.timeline

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for the Timeline's iOS-style containment-nesting layout, pinned to the
 * user's DEFCON-trip reference screenshots:
 *
 * - Aug 6: a hotel stay covering the whole day becomes a full-width background
 *   layer (depth 0); DEFCON and a dinner draw ON TOP of it (depth 1) at the
 *   inset width — not squeezed into thirds with dead space on the right.
 * - A short meeting inside DEFCON's hours nests one level deeper (depth 2),
 *   like GW ROTC Zoom in the iOS shots.
 * - Aug 5: a flight and a check-in that merely intersect (neither contains the
 *   other) split the window side-by-side.
 */
class TimelineLayoutTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 8, 6)

    private fun ts(day: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()

    private var nextId = 1L
    private fun event(startTs: Long, endTs: Long, title: String): DisplayEvent {
        val id = nextId++
        return DisplayEvent.Room(
            Event(
                id = id,
                uid = "$title@test",
                calendarId = 1L,
                title = title,
                startTs = startTs,
                endTs = endTs,
                dtstamp = 0L
            ),
            Occurrence(
                eventId = id,
                calendarId = 1L,
                startTs = startTs,
                endTs = endTs,
                startDay = 20260806,
                endDay = 20260806
            ),
            null
        )
    }

    private fun TimelineLayout.DayLayout.byTitle(title: String): TimelineLayout.Positioned =
        positioned.first { it.displayEvent.title == title }

    @Test
    fun `whole-day stay becomes background and contained events draw on top full-width`() {
        val hotel = event(ts(day.minusDays(1), 19, 0), ts(day.plusDays(1), 15, 0), "Hotel")
        val defcon = event(ts(day, 11, 0), ts(day, 21, 0), "DEFCON")
        val dinner = event(ts(day, 22, 30), ts(day, 23, 59), "Piero's")

        val layout = TimelineLayout.layoutDay(listOf(hotel, defcon, dinner), day, zone, 60.dp)

        val hotelPos = layout.byTitle("Hotel")
        val defconPos = layout.byTitle("DEFCON")
        val dinnerPos = layout.byTitle("Piero's")

        // Hotel is the root background at full width.
        assertEquals(0, hotelPos.depth)
        assertEquals(0f, hotelPos.leftFraction, 1e-4f)
        assertEquals(1f, hotelPos.widthFraction, 1e-4f)

        // DEFCON and dinner nest on top, stepped right, at the full inset width
        // (they don't overlap each other, so neither splits into columns).
        assertEquals(1, defconPos.depth)
        assertEquals(1, dinnerPos.depth)
        assertEquals(TimelineLayout.NEST_INSET_FRACTION, defconPos.leftFraction, 1e-4f)
        assertEquals(1f - TimelineLayout.NEST_INSET_FRACTION, defconPos.widthFraction, 1e-4f)
        assertEquals(TimelineLayout.NEST_INSET_FRACTION, dinnerPos.leftFraction, 1e-4f)
        assertEquals(1f - TimelineLayout.NEST_INSET_FRACTION, dinnerPos.widthFraction, 1e-4f)

        // Parents render before children so children stack on top.
        val order = layout.positioned.map { it.displayEvent.title }
        assertTrue(order.indexOf("Hotel") < order.indexOf("DEFCON"))
        assertTrue(order.indexOf("Hotel") < order.indexOf("Piero's"))
    }

    @Test
    fun `meeting inside a nested event nests one level deeper`() {
        val hotel = event(ts(day.minusDays(1), 19, 0), ts(day.plusDays(1), 15, 0), "Hotel")
        val defcon = event(ts(day, 11, 0), ts(day, 21, 0), "DEFCON")
        val zoomCall = event(ts(day, 18, 30), ts(day, 19, 0), "GW ROTC Zoom")

        val layout = TimelineLayout.layoutDay(listOf(hotel, defcon, zoomCall), day, zone, 60.dp)

        assertEquals(2, layout.byTitle("GW ROTC Zoom").depth)
        // Two inset steps from the left edge.
        assertEquals(
            2 * TimelineLayout.NEST_INSET_FRACTION,
            layout.byTitle("GW ROTC Zoom").leftFraction,
            1e-4f
        )
    }

    @Test
    fun `partial overlap splits side-by-side instead of nesting`() {
        // Aug 5: flight 6:15 PM – 11:59 PM, check-in 7:00 PM onward (clamped to
        // midnight). Neither span contains the other.
        val flight = event(ts(day, 18, 15), ts(day, 23, 59), "Flight")
        val checkIn = event(ts(day, 19, 0), ts(day.plusDays(1), 15, 0), "Check-in")

        val layout = TimelineLayout.layoutDay(listOf(flight, checkIn), day, zone, 60.dp)

        val flightPos = layout.byTitle("Flight")
        val checkInPos = layout.byTitle("Check-in")
        assertEquals(0, flightPos.depth)
        assertEquals(0, checkInPos.depth)
        assertEquals(0.5f, flightPos.widthFraction, 1e-4f)
        assertEquals(0.5f, checkInPos.widthFraction, 1e-4f)
        assertTrue(
            (flightPos.leftFraction == 0f && checkInPos.leftFraction == 0.5f) ||
                (flightPos.leftFraction == 0.5f && checkInPos.leftFraction == 0f)
        )
    }

    @Test
    fun `non-overlapping events each take the full width`() {
        val morning = event(ts(day, 9, 0), ts(day, 10, 0), "Morning")
        val evening = event(ts(day, 18, 0), ts(day, 19, 0), "Evening")

        val layout = TimelineLayout.layoutDay(listOf(morning, evening), day, zone, 60.dp)

        layout.positioned.forEach {
            assertEquals(1f, it.widthFraction, 1e-4f)
            assertEquals(0f, it.leftFraction, 1e-4f)
        }
    }

    @Test
    fun `same-top contained event detaches and splits instead of burying the parent`() {
        // B starts at A's own top, so nesting it would cover A's title
        // completely ("DEFCON ate the hotel"). B detaches → A and B split
        // side-by-side, both titles visible. C starts well into A, so it still
        // nests inside A's (half-width) window.
        val a = event(ts(day, 9, 0), ts(day, 12, 0), "A")
        val b = event(ts(day, 9, 0), ts(day, 10, 0), "B")
        val c = event(ts(day, 10, 30), ts(day, 11, 30), "C")

        val layout = TimelineLayout.layoutDay(listOf(a, b, c), day, zone, 60.dp)

        val aPos = layout.byTitle("A")
        val bPos = layout.byTitle("B")
        val cPos = layout.byTitle("C")

        assertEquals(0, aPos.depth)
        assertEquals(0, bPos.depth)
        assertEquals(0.5f, aPos.widthFraction, 1e-4f)
        assertEquals(0.5f, bPos.widthFraction, 1e-4f)

        assertEquals(1, cPos.depth)
        assertEquals(aPos.leftFraction + TimelineLayout.NEST_INSET_FRACTION, cPos.leftFraction, 1e-4f)
        assertEquals(0.5f - TimelineLayout.NEST_INSET_FRACTION, cPos.widthFraction, 1e-4f)
    }

    @Test
    fun `two continuing events both clamped to midnight split side-by-side`() {
        // The Aug 9 bug: DEFCON (continuing, ends today) and the hotel stay
        // (continuing, ends tomorrow) both clamp to 12 AM. The hotel contains
        // DEFCON's span, but nesting would bury whichever is behind — they
        // must split half/half with both titles visible, like Apple Calendar.
        val hotel = event(ts(day.minusDays(4), 19, 0), ts(day.plusDays(1), 15, 0), "Hotel")
        val defcon = event(ts(day.minusDays(3), 11, 0), ts(day, 21, 0), "DEFCON")

        val layout = TimelineLayout.layoutDay(listOf(hotel, defcon), day, zone, 60.dp)

        val hotelPos = layout.byTitle("Hotel")
        val defconPos = layout.byTitle("DEFCON")
        assertEquals(0, hotelPos.depth)
        assertEquals(0, defconPos.depth)
        assertEquals(0.5f, hotelPos.widthFraction, 1e-4f)
        assertEquals(0.5f, defconPos.widthFraction, 1e-4f)
        assertTrue(
            (hotelPos.leftFraction == 0f && defconPos.leftFraction == 0.5f) ||
                (hotelPos.leftFraction == 0.5f && defconPos.leftFraction == 0f)
        )
        // Both keep their own spans: hotel covers the whole day, DEFCON ends at 9 PM.
        assertEquals(0, hotelPos.startMinutes)
        assertEquals(24 * 60, hotelPos.endMinutes)
        assertEquals(21 * 60, defconPos.endMinutes)
    }

    @Test
    fun `event positions follow the grid zone clock`() {
        val dinner = event(ts(day, 19, 0), ts(day, 20, 0), "Dinner")
        val layout = TimelineLayout.layoutDay(listOf(dinner), day, zone, 60.dp)
        assertEquals(19 * 60, layout.byTitle("Dinner").startMinutes)
        assertEquals(20 * 60, layout.byTitle("Dinner").endMinutes)
    }

    @Test
    fun `empty day lays out nothing`() {
        val layout = TimelineLayout.layoutDay(emptyList(), day, zone, 60.dp)
        assertTrue(layout.positioned.isEmpty())
        assertTrue(layout.overflow.isEmpty())
    }
}
