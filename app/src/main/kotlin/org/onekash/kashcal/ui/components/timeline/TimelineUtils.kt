package org.onekash.kashcal.ui.components.timeline

import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure logic for the Timeline view (single-day time grid with week strip and
 * dual-timezone labels).
 *
 * The defining feature is the *grid timezone*: the day boundaries, hour rows,
 * current-time line, and "today" are all computed in [resolveGridZone]'s result
 * rather than implicitly in the device zone. With "use home timezone" on
 * (default), a traveler's grid stays anchored to home time — like iOS's
 * travel-mode day view — and events authored elsewhere pick up a dual-timezone
 * annotation via [dualTimezoneAnnotation].
 *
 * Everything here is free of Android and Compose dependencies so it can run as
 * plain JVM unit tests.
 */
object TimelineUtils {

    // ==================== Grid timezone ====================

    /**
     * Resolve the timezone the Timeline grid lays out in.
     *
     * @param useHomeTz the "Timeline in home time zone" preference (default true)
     * @param homeTimezone stored home zone ID; blank = not set
     * @param deviceZone injectable for tests; the device zone otherwise
     * @return the home zone when the preference is on and the stored ID is a
     *   valid IANA zone; the device zone in every other case (preference off,
     *   home zone unset, or an unparseable stored value)
     */
    fun resolveGridZone(
        useHomeTz: Boolean,
        homeTimezone: String,
        deviceZone: ZoneId = ZoneId.systemDefault()
    ): ZoneId {
        if (!useHomeTz || homeTimezone.isBlank()) return deviceZone
        return try {
            ZoneId.of(homeTimezone)
        } catch (_: Exception) {
            deviceZone
        }
    }

    // ==================== Page <-> date mapping ====================

    /**
     * Convert a day-pager page index to its date, anchored so that
     * [WeekViewUtils.CENTER_DAY_PAGE] is *today in the grid timezone*. Around
     * midnight this can differ by a day from the device-zone mapping used by
     * the other time-grid views — which is exactly the point: the Timeline's
     * Today lands on the home-timezone today. The arithmetic itself is
     * WeekViewUtils' — only the anchor differs.
     */
    fun pageToDate(
        page: Int,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone)
    ): LocalDate = WeekViewUtils.pageToDate(page, today)

    /** Inverse of [pageToDate]. */
    fun dateToPage(
        date: LocalDate,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone)
    ): Int = WeekViewUtils.dateToPage(date, today)

    // ==================== Event bucketing ====================

    /**
     * Group timed events by the grid-zone calendar days they occupy. A
     * cross-midnight event (in grid time) appears on every day it touches;
     * the per-day column clamps its block to that day's bounds. An event
     * ending at exactly 00:00 grid time belongs to the prior day only (the
     * midnight boundary is exclusive, mirroring RFC 5545 DTEND semantics).
     */
    fun groupTimedEventsByZonedDate(
        events: List<DisplayEvent>,
        zone: ZoneId
    ): Map<LocalDate, List<DisplayEvent>> {
        val result = mutableMapOf<LocalDate, MutableList<DisplayEvent>>()
        for (event in events) {
            val startDate = Instant.ofEpochMilli(event.startTs).atZone(zone).toLocalDate()
            val endZdt = Instant.ofEpochMilli(event.endTs).atZone(zone)
            var endDate = endZdt.toLocalDate()
            // Exclusive midnight end: 20:00–00:00 is a one-day event.
            if (endDate > startDate && endZdt.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
                endDate = endDate.minusDays(1)
            }
            if (endDate < startDate) endDate = startDate
            var day = startDate
            while (day <= endDate) {
                result.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
            }
        }
        return result
    }

    /**
     * Group all-day events by date. All-day events are date-identities (a
     * birthday is Aug 5 in every timezone), so this uses the pre-computed
     * startDay/endDay day codes — the week view's grouping — rather than
     * instant math in the grid zone.
     */
    fun groupAllDayEventsByDate(
        events: List<DisplayEvent>
    ): Map<LocalDate, List<DisplayEvent>> = WeekViewUtils.groupEventsByDate(events)

    // ==================== Time labels ====================

    /**
     * The block's primary time line: the event's range formatted in the *grid*
     * timezone. When [withZoneAbbreviation] is set (an annotation is shown
     * alongside), the grid zone's abbreviation is appended so the two lines
     * read unambiguously — "6:15 PM – 11:59 PM EDT" over "(6:15 PM EDT – 8:59 PM PDT)".
     *
     * Styling sibling of [WeekViewUtils.formatTimeRange], which stays
     * device-zone, lowercased, and hyphen-separated per the week view's look.
     */
    fun gridTimeLabel(
        startTs: Long,
        endTs: Long,
        gridZone: ZoneId,
        timePattern: String,
        withZoneAbbreviation: Boolean = false,
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DateTimeFormatter.ofPattern(timePattern, locale)
        val start = Instant.ofEpochMilli(startTs).atZone(gridZone).toLocalTime().format(formatter)
        val end = Instant.ofEpochMilli(endTs).atZone(gridZone).toLocalTime().format(formatter)
        val range = "$start – $end"
        return if (withZoneAbbreviation) {
            "$range ${TimezoneUtils.getAbbreviation(gridZone.id, Instant.ofEpochMilli(startTs))}"
        } else {
            range
        }
    }

    /**
     * The dual-timezone annotation for an event whose authored timezone(s)
     * differ from the grid's wall clock, or null when no annotation is needed.
     *
     * Rules:
     * - No authored start zone (floating/unknown) → null.
     * - Annotation is needed when the start zone's UTC offset at the start
     *   instant, or the end zone's offset at the end instant, differs from the
     *   grid zone's offset at that same instant. Comparing offsets (not zone
     *   IDs) keeps same-clock zones quiet — a New York event on a Toronto grid
     *   needs no annotation.
     * - Same start and end zone → one trailing abbreviation:
     *   "4:00 PM – 6:00 PM PDT".
     * - Different start/end zones (flights, issue #39) → each side labeled:
     *   "6:15 PM EDT – 8:59 PM PDT".
     */
    fun dualTimezoneAnnotation(
        startTs: Long,
        endTs: Long,
        timezone: String?,
        endTimezone: String?,
        gridZone: ZoneId,
        timePattern: String,
        locale: Locale = Locale.getDefault()
    ): String? {
        val startZone = parseZoneOrNull(timezone) ?: return null
        val endZone = parseZoneOrNull(endTimezone) ?: startZone

        val startInstant = Instant.ofEpochMilli(startTs)
        val endInstant = Instant.ofEpochMilli(endTs)
        val startDiffers =
            startZone.rules.getOffset(startInstant) != gridZone.rules.getOffset(startInstant)
        val endDiffers =
            endZone.rules.getOffset(endInstant) != gridZone.rules.getOffset(endInstant)
        if (!startDiffers && !endDiffers) return null

        val formatter = DateTimeFormatter.ofPattern(timePattern, locale)
        val startText = startInstant.atZone(startZone).toLocalTime().format(formatter)
        val endText = endInstant.atZone(endZone).toLocalTime().format(formatter)
        val startAbbr = TimezoneUtils.getAbbreviation(startZone.id, startInstant)
        val endAbbr = TimezoneUtils.getAbbreviation(endZone.id, endInstant)

        return if (startZone.id == endZone.id) {
            "$startText – $endText $endAbbr"
        } else {
            "$startText $startAbbr – $endText $endAbbr"
        }
    }

    private fun parseZoneOrNull(zoneId: String?): ZoneId? {
        if (zoneId.isNullOrBlank()) return null
        return try {
            ZoneId.of(zoneId)
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Day header ====================

    /**
     * The Timeline's inline day header, e.g. "Wednesday – Aug 5, 2026".
     * [datePattern] is the caller-resolved localized month-day-year pattern
     * (from DateTimeUtils.localizedPattern("yMMMd")) so this stays free of
     * Android dependencies.
     */
    fun formatDayHeader(
        date: LocalDate,
        datePattern: String,
        locale: Locale = Locale.getDefault()
    ): String {
        val weekday = date.format(DateTimeFormatter.ofPattern("EEEE", locale))
        val dateText = date.format(DateTimeFormatter.ofPattern(datePattern, locale))
        return "$weekday – $dateText"
    }

    // ==================== Block content budget ====================

    /** Per-field max line counts for a Timeline block at a given height. */
    data class BlockLines(val title: Int, val location: Int, val time: Int)

    /**
     * How many text lines each field of a Timeline block gets, filling the
     * block's height like iOS does ("show as much as fits") instead of
     * ellipsizing everything to one line.
     *
     * Grants lines in the order title → time → location → title → time →
     * location → title → time → title (caps: title 4, time 3, location 2),
     * skipping a location the event doesn't have so its lines flow to the
     * next field. Time's cap is 3 because the dual-timezone string
     * ("2:07 PM – 7:23 PM EDT (11:07 AM PDT – 7:23 PM EDT)") needs three
     * lines at half width — the end time must never ellipsize away.
     *
     * @param heightDp rendered block height in dp
     * @param hasLocation whether a location line will render
     */
    fun blockLineBudget(
        heightDp: Float,
        hasLocation: Boolean
    ): BlockLines {
        // ~15dp per labelSmall/bodySmall line plus the block's vertical padding.
        val budget = (((heightDp - 8f) / 15f).toInt()).coerceAtLeast(1)
        var title = 0
        var location = 0
        var time = 0
        val grants = listOf("title", "time", "location", "title", "time", "location", "title", "time", "title")
        var granted = 0
        for (field in grants) {
            if (granted >= budget) break
            when (field) {
                "title" -> if (title < 4) { title++; granted++ }
                "time" -> if (time < 3) { time++; granted++ }
                "location" -> if (hasLocation && location < 2) { location++; granted++ }
            }
        }
        return BlockLines(title = title.coerceAtLeast(1), location = location, time = time)
    }
}
