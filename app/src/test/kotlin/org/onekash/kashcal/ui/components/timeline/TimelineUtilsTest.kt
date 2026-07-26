package org.onekash.kashcal.ui.components.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Pure-JVM tests for the Timeline view's zone math: grid-zone resolution,
 * page↔date anchoring, cross-zone day bucketing, and the dual-timezone
 * annotation rules.
 *
 * The running example mirrors the feature's motivating scenario: a New York
 * ("home") user in Las Vegas, with a flight that departs in EDT and lands in
 * PDT (Aug 5, 2026 — EDT is UTC-4, PDT is UTC-7).
 */
class TimelineUtilsTest {

    private val newYork = ZoneId.of("America/New_York")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val toronto = ZoneId.of("America/Toronto")
    private val pattern12h = "h:mm a"

    private fun ts(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun displayEvent(
        startTs: Long,
        endTs: Long,
        timezone: String? = null,
        endTimezone: String? = null,
        startDay: Int = 20260805,
        endDay: Int = 20260805,
        title: String = "Event"
    ): DisplayEvent {
        val event = Event(
            id = 1L,
            uid = "$title@test",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            endTimezone = endTimezone,
            dtstamp = 0L
        )
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = endDay
        )
        return DisplayEvent.Room(event, occurrence, null)
    }

    // ==================== resolveGridZone ====================

    @Test
    fun `resolveGridZone returns home zone when set and enabled`() {
        assertEquals(
            newYork,
            TimelineUtils.resolveGridZone(true, "America/New_York", losAngeles)
        )
    }

    @Test
    fun `resolveGridZone falls back to device when toggle off`() {
        assertEquals(
            losAngeles,
            TimelineUtils.resolveGridZone(false, "America/New_York", losAngeles)
        )
    }

    @Test
    fun `resolveGridZone falls back to device when home unset`() {
        assertEquals(losAngeles, TimelineUtils.resolveGridZone(true, "", losAngeles))
    }

    @Test
    fun `resolveGridZone falls back to device on invalid zone id`() {
        assertEquals(
            losAngeles,
            TimelineUtils.resolveGridZone(true, "Not/AZone", losAngeles)
        )
    }

    // ==================== page mapping ====================

    @Test
    fun `center page maps to today in grid zone`() {
        val today = LocalDate.of(2026, 8, 5)
        assertEquals(
            today,
            TimelineUtils.pageToDate(WeekViewUtils.CENTER_DAY_PAGE, newYork, today)
        )
    }

    @Test
    fun `pageToDate and dateToPage round-trip`() {
        val today = LocalDate.of(2026, 8, 5)
        val date = LocalDate.of(2026, 9, 14)
        val page = TimelineUtils.dateToPage(date, newYork, today)
        assertEquals(date, TimelineUtils.pageToDate(page, newYork, today))
        assertEquals(WeekViewUtils.CENTER_DAY_PAGE + 40, page)
    }

    // ==================== timed-event bucketing ====================

    @Test
    fun `event buckets on its grid-zone date`() {
        // Flight departs JFK 6:15 PM EDT, lands 11:59 PM EDT (8:59 PM PDT) on Aug 5.
        val flight = displayEvent(
            startTs = ts(newYork, 2026, 8, 5, 18, 15),
            endTs = ts(newYork, 2026, 8, 5, 23, 59),
            timezone = "America/New_York",
            endTimezone = "America/Los_Angeles"
        )
        val byDate = TimelineUtils.groupTimedEventsByZonedDate(listOf(flight), newYork)
        assertEquals(setOf(LocalDate.of(2026, 8, 5)), byDate.keys)
    }

    @Test
    fun `late-evening PDT event lands on the next day in an EDT grid`() {
        // 9 PM – 11 PM PDT on Aug 5 is 12 AM – 2 AM EDT on Aug 6: home-time grid
        // must show it on the 6th, device-time (Vegas) grid on the 5th.
        val dinner = displayEvent(
            startTs = ts(losAngeles, 2026, 8, 5, 21, 0),
            endTs = ts(losAngeles, 2026, 8, 5, 23, 0),
            timezone = "America/Los_Angeles"
        )
        val homeGrid = TimelineUtils.groupTimedEventsByZonedDate(listOf(dinner), newYork)
        val deviceGrid = TimelineUtils.groupTimedEventsByZonedDate(listOf(dinner), losAngeles)
        assertEquals(setOf(LocalDate.of(2026, 8, 6)), homeGrid.keys)
        assertEquals(setOf(LocalDate.of(2026, 8, 5)), deviceGrid.keys)
    }

    @Test
    fun `cross-midnight event appears on both days it touches`() {
        val redEye = displayEvent(
            startTs = ts(newYork, 2026, 8, 5, 22, 0),
            endTs = ts(newYork, 2026, 8, 6, 2, 0)
        )
        val byDate = TimelineUtils.groupTimedEventsByZonedDate(listOf(redEye), newYork)
        assertEquals(
            setOf(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 6)),
            byDate.keys
        )
    }

    @Test
    fun `event ending exactly at midnight stays on the prior day`() {
        val evening = displayEvent(
            startTs = ts(newYork, 2026, 8, 5, 20, 0),
            endTs = ts(newYork, 2026, 8, 6, 0, 0)
        )
        val byDate = TimelineUtils.groupTimedEventsByZonedDate(listOf(evening), newYork)
        assertEquals(setOf(LocalDate.of(2026, 8, 5)), byDate.keys)
    }

    // ==================== all-day bucketing ====================

    @Test
    fun `all-day events keep their day-code identity across zones`() {
        val recyclingNight = displayEvent(
            startTs = 0L,
            endTs = 0L,
            startDay = 20260805,
            endDay = 20260806
        )
        val byDate = TimelineUtils.groupAllDayEventsByDate(listOf(recyclingNight))
        assertEquals(
            setOf(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 6)),
            byDate.keys
        )
    }

    // ==================== dual-timezone annotation ====================

    @Test
    fun `no annotation without an authored timezone`() {
        assertNull(
            TimelineUtils.dualTimezoneAnnotation(
                startTs = ts(newYork, 2026, 8, 5, 18, 15),
                endTs = ts(newYork, 2026, 8, 5, 19, 0),
                timezone = null,
                endTimezone = null,
                gridZone = newYork,
                timePattern = pattern12h,
                locale = Locale.US
            )
        )
    }

    @Test
    fun `no annotation when event zone matches grid zone`() {
        assertNull(
            TimelineUtils.dualTimezoneAnnotation(
                startTs = ts(newYork, 2026, 8, 5, 18, 15),
                endTs = ts(newYork, 2026, 8, 5, 19, 0),
                timezone = "America/New_York",
                endTimezone = null,
                gridZone = newYork,
                timePattern = pattern12h,
                locale = Locale.US
            )
        )
    }

    @Test
    fun `no annotation for same-offset zone with a different id`() {
        // Toronto and New York share EDT — an annotation would be noise.
        assertNull(
            TimelineUtils.dualTimezoneAnnotation(
                startTs = ts(toronto, 2026, 8, 5, 18, 15),
                endTs = ts(toronto, 2026, 8, 5, 19, 0),
                timezone = "America/Toronto",
                endTimezone = null,
                gridZone = newYork,
                timePattern = pattern12h,
                locale = Locale.US
            )
        )
    }

    @Test
    fun `single-zone event on a foreign grid gets one trailing abbreviation`() {
        // Hotel reservation authored in Vegas time, viewed on the home (EDT) grid.
        val annotation = TimelineUtils.dualTimezoneAnnotation(
            startTs = ts(losAngeles, 2026, 8, 5, 16, 0),
            endTs = ts(losAngeles, 2026, 8, 5, 18, 0),
            timezone = "America/Los_Angeles",
            endTimezone = null,
            gridZone = newYork,
            timePattern = pattern12h,
            locale = Locale.US
        )
        assertEquals("4:00 PM – 6:00 PM PDT", annotation)
    }

    @Test
    fun `flight with different start and end zones labels each side`() {
        // AA 2440: departs JFK 6:15 PM EDT, lands LAS 8:59 PM PDT.
        val annotation = TimelineUtils.dualTimezoneAnnotation(
            startTs = ts(newYork, 2026, 8, 5, 18, 15),
            endTs = ts(losAngeles, 2026, 8, 5, 20, 59),
            timezone = "America/New_York",
            endTimezone = "America/Los_Angeles",
            gridZone = newYork,
            timePattern = pattern12h,
            locale = Locale.US
        )
        assertEquals("6:15 PM EDT – 8:59 PM PDT", annotation)
    }

    @Test
    fun `invalid authored zone id yields no annotation`() {
        assertNull(
            TimelineUtils.dualTimezoneAnnotation(
                startTs = 0L,
                endTs = 1L,
                timezone = "Garbage/Zone",
                endTimezone = null,
                gridZone = newYork,
                timePattern = pattern12h,
                locale = Locale.US
            )
        )
    }

    // ==================== grid time label ====================

    @Test
    fun `grid time label renders the range in the grid zone`() {
        val label = TimelineUtils.gridTimeLabel(
            startTs = ts(newYork, 2026, 8, 5, 18, 15),
            endTs = ts(newYork, 2026, 8, 5, 23, 59),
            gridZone = newYork,
            timePattern = pattern12h,
            withZoneAbbreviation = false,
            locale = Locale.US
        )
        assertEquals("6:15 PM – 11:59 PM", label)
    }

    @Test
    fun `grid time label appends grid abbreviation when requested`() {
        val label = TimelineUtils.gridTimeLabel(
            startTs = ts(newYork, 2026, 8, 5, 18, 15),
            endTs = ts(newYork, 2026, 8, 5, 23, 59),
            gridZone = newYork,
            timePattern = pattern12h,
            withZoneAbbreviation = true,
            locale = Locale.US
        )
        assertEquals("6:15 PM – 11:59 PM EDT", label)
    }

    // ==================== day header / day code ====================

    @Test
    fun `day header combines weekday and localized date`() {
        val header = TimelineUtils.formatDayHeader(
            date = LocalDate.of(2026, 8, 5),
            datePattern = "MMM d, y",
            locale = Locale.US
        )
        assertEquals("Wednesday – Aug 5, 2026", header)
    }

    @Test
    fun `localDateToDayCode packs YYYYMMDD and inverts dayCodeToLocalDate`() {
        val date = LocalDate.of(2026, 8, 5)
        assertEquals(20260805, DayPagerUtils.localDateToDayCode(date))
        assertEquals(date, DayPagerUtils.dayCodeToLocalDate(DayPagerUtils.localDateToDayCode(date)))
    }

    // ==================== block line budget ====================

    @Test
    fun `tiny block shows only a one-line title`() {
        val lines = TimelineUtils.blockLineBudget(heightDp = 22f, hasLocation = true)
        assertEquals(TimelineUtils.BlockLines(title = 1, location = 0, time = 0), lines)
    }

    @Test
    fun `medium block adds time then location`() {
        assertEquals(
            TimelineUtils.BlockLines(title = 1, location = 0, time = 1),
            TimelineUtils.blockLineBudget(heightDp = 40f, hasLocation = true)
        )
        assertEquals(
            TimelineUtils.BlockLines(title = 1, location = 1, time = 1),
            TimelineUtils.blockLineBudget(heightDp = 55f, hasLocation = true)
        )
    }

    @Test
    fun `tall block wraps title, location, and the dual-timezone string`() {
        // ~100dp (a 1h40m event at default zoom): 6 lines → 2/2/2.
        assertEquals(
            TimelineUtils.BlockLines(title = 2, location = 2, time = 2),
            TimelineUtils.blockLineBudget(heightDp = 100f, hasLocation = true)
        )
    }

    @Test
    fun `missing location hands its lines to title and time`() {
        assertEquals(
            TimelineUtils.BlockLines(title = 2, location = 0, time = 1),
            TimelineUtils.blockLineBudget(heightDp = 55f, hasLocation = false)
        )
    }

    @Test
    fun `line counts are capped for very tall blocks`() {
        // Time caps at 3 so a half-width dual-timezone string never loses its
        // end time to an ellipsis.
        val lines = TimelineUtils.blockLineBudget(heightDp = 600f, hasLocation = true)
        assertEquals(TimelineUtils.BlockLines(title = 4, location = 2, time = 3), lines)
    }

}
