package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.components.timeline.TimelineUtils
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TopBarTitleFormatter {

    fun format(
        viewMode: ViewMode,
        viewingYear: Int,
        viewingMonth: Int,
        weekViewPagerPosition: Int,
        firstDayOfWeek: Int,
        weekPrefix: String,
        weekSuffixTemplate: String,
        yearLabel: String,
        locale: Locale = Locale.getDefault(),
        today: LocalDate = LocalDate.now(),
        // Read only by DAY_TIMELINE, whose page↔date mapping anchors to
        // "today in the grid zone" rather than the device zone.
        timelineGridZone: ZoneId = ZoneId.systemDefault(),
    ): String {
        return when (viewMode) {
            ViewMode.MONTH, ViewMode.MONTH_FULL, ViewMode.AGENDA -> {
                WeekViewUtils.formatMonthYear(LocalDate.of(viewingYear, viewingMonth + 1, 1))
            }
            ViewMode.YEAR -> yearLabel
            ViewMode.WEEK -> {
                val centerDate = WeekViewUtils.weekPageToStartDate(
                    weekViewPagerPosition,
                    firstDayOfWeek,
                )
                val monthYear = WeekViewUtils.formatMonthYear(centerDate)
                val weekLabel = WeekViewUtils.formatWeekLabel(
                    centerDate,
                    firstDayOfWeek,
                    weekPrefix,
                )
                weekSuffixTemplate.format(monthYear, weekLabel)
            }
            ViewMode.THREE_DAYS -> {
                val centerDate = WeekViewUtils.pageToDate(weekViewPagerPosition + 1)
                WeekViewUtils.formatMonthYear(centerDate)
            }
            ViewMode.DAY -> {
                val date = WeekViewUtils.pageToDate(weekViewPagerPosition)
                val skeleton = if (date.year == today.year) "EEEMMMd" else "yEEEMMMd"
                DateTimeFormatter.ofPattern(
                    DateTimeUtils.localizedPattern(skeleton),
                    locale,
                ).format(date)
            }
            ViewMode.DAY_TIMELINE -> {
                // The view renders its own long-form day header inline, so the
                // top bar shows only the month-year (like the 3-day view).
                val date = TimelineUtils.pageToDate(weekViewPagerPosition, timelineGridZone)
                WeekViewUtils.formatMonthYear(date)
            }
            ViewMode.INSIGHTS -> ""
        }
    }
}
