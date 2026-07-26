package org.onekash.kashcal.ui.components.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.AgendaWeekBar
import org.onekash.kashcal.ui.components.AgendaWeekBarLogic
import org.onekash.kashcal.ui.components.weekview.GridLines
import org.onekash.kashcal.ui.components.weekview.OverlapListSheet
import org.onekash.kashcal.ui.components.weekview.TimeLabel
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * The Timeline view: a single-day hour grid under a pinned week strip, with a
 * long-form day header, an all-day chip row, and dual-timezone event blocks.
 *
 * Structure (top to bottom):
 * - [AgendaWeekBar] — the week strip; tapping a day pages to it, swiping the
 *   grid updates the selection (and slides the strip when crossing weeks)
 * - Day header ("Wednesday – Aug 5, 2026"), plus a home-timezone pill when the
 *   grid is laid out in a timezone other than the device's
 * - All-day chip row (only when the shown day has all-day events)
 * - 24h time grid: pseudo-infinite day pager (1 page = 1 full-width day),
 *   shared vertical scroll, pinch-to-zoom, current-time line
 *
 * Everything date-like here — day boundaries, "today", the time indicator — is
 * computed in [gridZone] rather than the device zone. See
 * [TimelineUtils.resolveGridZone].
 */
@Composable
fun TimelineDayContent(
    timedEvents: ImmutableList<DisplayEvent>,
    allDayEvents: ImmutableList<DisplayEvent>,
    error: String?,
    gridZone: ZoneId,
    scrollPosition: Int,
    savedScrollMinutes: Int,
    hourHeight: Float,
    onHourHeightChange: (Float) -> Unit,
    showEventEmojis: Boolean,
    timePattern: String,
    firstDayOfWeek: Int,
    onEventClick: (DisplayEvent) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit,
    onScrollPositionChange: (Int) -> Unit,
    onScrollMinutesChange: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    pendingNavigateToPage: Int?,
    onNavigationConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (error != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    // Minute tick drives the current-time line and the grid-zone "today"
    // (which can roll over at a different moment than device midnight).
    var minuteTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            delay((60 - now.second) * 1000L)
            minuteTick++
        }
    }
    val today = remember(minuteTick, gridZone) { LocalDate.now(gridZone) }
    val nowMinutes = remember(minuteTick, gridZone) {
        val now = ZonedDateTime.now(gridZone)
        now.hour * 60 + now.minute
    }

    val pagerState = rememberPagerState(
        initialPage = WeekViewUtils.CENTER_DAY_PAGE,
        pageCount = { WeekViewUtils.TOTAL_DAY_PAGES }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> onPageChanged(page) }
    }

    // Programmatic navigation (Today button, date picker). Wait for any live
    // gesture to finish so the animation doesn't fight the user's finger.
    LaunchedEffect(pendingNavigateToPage) {
        pendingNavigateToPage?.let { targetPage ->
            snapshotFlow { pagerState.isScrollInProgress }
                .filter { !it }
                .first()
            pagerState.animateScrollToPage(targetPage)
            onNavigationConsumed()
        }
    }

    val currentDate = TimelineUtils.pageToDate(pagerState.currentPage, gridZone, today)

    val timedByDate = remember(timedEvents, gridZone) {
        TimelineUtils.groupTimedEventsByZonedDate(timedEvents, gridZone)
    }
    val allDayByDate = remember(allDayEvents) {
        TimelineUtils.groupAllDayEventsByDate(allDayEvents)
    }

    var overflowEvents by remember { mutableStateOf<List<DisplayEvent>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Week strip. Selection is the shown day; tapping pages to the day, and
        // an iOS-style horizontal swipe on the strip jumps a whole week.
        val weekDates = remember(currentDate, firstDayOfWeek) {
            AgendaWeekBarLogic.weekDates(currentDate, firstDayOfWeek)
        }
        val weekSwipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
        AgendaWeekBar(
            weekDates = weekDates,
            selectedDayCode = DayPagerUtils.localDateToDayCode(currentDate),
            todayDayCode = DayPagerUtils.localDateToDayCode(today),
            onDayClick = { tappedDayCode ->
                val tappedDate = DayPagerUtils.dayCodeToLocalDate(tappedDayCode)
                val targetPage = TimelineUtils.dateToPage(tappedDate, gridZone, today)
                coroutineScope.launch { pagerState.animateScrollToPage(targetPage) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(weekSwipeThresholdPx) {
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onHorizontalDrag = { _, dragAmount -> dragTotal += dragAmount },
                        onDragEnd = {
                            if (abs(dragTotal) > weekSwipeThresholdPx) {
                                // Swipe left = forward one week, right = back.
                                val step = if (dragTotal < 0) 7 else -7
                                val target = pagerState.currentPage + step
                                coroutineScope.launch { pagerState.animateScrollToPage(target) }
                            }
                        }
                    )
                }
        )

        TimelineDayHeader(
            date = currentDate,
            gridZone = gridZone,
        )

        // All-day chip row for the shown day.
        val allDayForDate = allDayByDate[currentDate].orEmpty()
        if (allDayForDate.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.width(TIME_COLUMN_WIDTH),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.label_all_day),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allDayForDate.forEach { event ->
                        TimelineAllDayChip(
                            displayEvent = event,
                            showEventEmojis = showEventEmojis,
                            onClick = { onEventClick(event) }
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // ==================== Time grid ====================
        val density = LocalDensity.current
        val initialScrollPx = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = scrollPosition,
            hourHeightDp = hourHeight,
            density = density.density,
            savedMinutes = savedScrollMinutes
        )
        val scrollState = rememberScrollState(initial = initialScrollPx)
        val is24Hour = timePattern.startsWith("H")
        val totalHeight = hourHeight.dp * WeekViewUtils.TOTAL_HOURS
        val hourHeightPx = with(density) { hourHeight.dp.toPx() }
        val currentHourHeight by rememberUpdatedState(hourHeight)
        val currentHourHeightPx by rememberUpdatedState(hourHeightPx)

        // In-session scroll position (fast debounce) + persisted clock-minutes
        // (slow debounce) — same two-tier persistence as the week view.
        LaunchedEffect(scrollState) {
            @OptIn(FlowPreview::class)
            snapshotFlow { scrollState.value }
                .debounce(100)
                .collect { position -> onScrollPositionChange(position) }
        }
        LaunchedEffect(scrollState) {
            @OptIn(FlowPreview::class)
            snapshotFlow { scrollState.value }
                .debounce(1000)
                .map { position ->
                    WeekViewUtils.pixelsToMinutesOfDay(position.toFloat(), currentHourHeightPx)
                }
                .distinctUntilChanged()
                .collect { minutes -> onScrollMinutesChange(minutes) }
        }

        Row(modifier = Modifier.weight(1f)) {
            // Fixed time-label gutter, scrolling with the grid. Both columns end
            // in the same FAB-clearance spacer so the grid can scroll the last
            // events up past the floating + button (and their heights — and so
            // their scroll ranges — stay identical).
            Column(
                modifier = Modifier
                    .width(TIME_COLUMN_WIDTH)
                    .verticalScroll(scrollState)
                    .height(totalHeight + FAB_CLEARANCE)
            ) {
                for (hour in WeekViewUtils.START_HOUR until WeekViewUtils.END_HOUR) {
                    TimeLabel(hour = hour, height = hourHeight.dp, is24Hour = is24Hour)
                }
                Spacer(modifier = Modifier.height(FAB_CLEARANCE))
            }

            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val columnWidth = this.maxWidth
                val viewportHeightPx = with(density) { this@BoxWithConstraints.maxHeight.toPx() }
                val touchSlop = LocalViewConfiguration.current.touchSlop * 0.5f

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // Two-finger pinch zooms the hour height; ported from the
                        // week view so both grids share the same zoom feel (and the
                        // same persisted zoom level).
                        .pointerInput(Unit) {
                            val pass = PointerEventPass.Initial
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = pass)
                                var pastSlop = false
                                do {
                                    val event = awaitPointerEvent(pass)
                                    if (event.changes.count { it.pressed } < 2) continue
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (!pastSlop) {
                                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                        val effectiveSize = centroidSize.coerceAtLeast(48f)
                                        if (abs(1 - zoom) * effectiveSize > touchSlop) {
                                            pastSlop = true
                                        } else continue
                                    }
                                    event.changes.forEach { it.consume() }
                                    if (abs(zoom - 1f) > 0.001f) {
                                        val oldHourHeightPx = currentHourHeightPx
                                        val newHourHeight = (currentHourHeight * zoom)
                                            .coerceIn(
                                                WeekViewUtils.MIN_HOUR_HEIGHT_DP,
                                                WeekViewUtils.MAX_HOUR_HEIGHT_DP
                                            )
                                        if (abs(newHourHeight - currentHourHeight) > 0.01f) {
                                            val newHourHeightPx = newHourHeight * density.density
                                            val viewportCenterTime =
                                                (scrollState.value + viewportHeightPx / 2) / oldHourHeightPx
                                            val newScrollPx =
                                                viewportCenterTime * newHourHeightPx - viewportHeightPx / 2
                                            onHourHeightChange(newHourHeight)
                                            scrollState.dispatchRawDelta(
                                                newScrollPx - scrollState.value - pan.y
                                            )
                                        }
                                    } else if (abs(pan.y) > 0.5f) {
                                        scrollState.dispatchRawDelta(-pan.y)
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .verticalScroll(scrollState)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(totalHeight)
                    ) {
                        GridLines(
                            hourHeight = hourHeight.dp,
                            totalHours = WeekViewUtils.TOTAL_HOURS
                        )

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            key = { page -> "timeline_$page" }
                        ) { page ->
                            val date = TimelineUtils.pageToDate(page, gridZone, today)
                            TimelineDayColumn(
                                date = date,
                                events = timedByDate[date].orEmpty(),
                                hourHeight = hourHeight.dp,
                                gridZone = gridZone,
                                isToday = date == today,
                                nowMinutes = nowMinutes,
                                showEventEmojis = showEventEmojis,
                                timePattern = timePattern,
                                columnWidth = columnWidth,
                                onEventClick = onEventClick,
                                onOverflowClick = { events -> overflowEvents = events },
                                onEmptyTap = onEmptyTap
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(FAB_CLEARANCE))
                }
            }
        }
    }

    overflowEvents?.let { events ->
        OverlapListSheet(
            events = events,
            showEventEmojis = showEventEmojis,
            timePattern = timePattern,
            onDismiss = { overflowEvents = null },
            onEventClick = onEventClick
        )
    }
}

/**
 * The long-form day header ("Wednesday – Aug 5, 2026"), sliding sideways as
 * the pager crosses days. When the grid timezone differs from the device's
 * current zone, a small pill labels it (e.g. "Home · EDT") so the traveler
 * always knows which wall clock the grid below is speaking.
 */
@Composable
private fun TimelineDayHeader(
    date: LocalDate,
    gridZone: ZoneId,
    modifier: Modifier = Modifier
) {
    val datePattern = remember { DateTimeUtils.localizedPattern("yMMMd") }
    val deviceZoneDiffers = remember(gridZone) {
        gridZone.rules.getOffset(Instant.now()) != ZoneId.systemDefault().rules.getOffset(Instant.now())
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = date,
            transitionSpec = {
                val forward = targetState >= initialState
                val dir = if (forward) 1 else -1
                (slideInHorizontally { w -> dir * w / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> -dir * w / 4 } + fadeOut())
            },
            label = "timelineDayHeader"
        ) { shownDate ->
            Text(
                text = TimelineUtils.formatDayHeader(shownDate, datePattern),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        if (deviceZoneDiffers) {
            Spacer(modifier = Modifier.width(8.dp))
            val abbreviation = remember(gridZone) {
                TimezoneUtils.getAbbreviation(gridZone.id, Instant.now())
            }
            val pillDescription = stringResource(R.string.cd_timeline_grid_zone, abbreviation)
            Text(
                text = stringResource(R.string.timeline_home_zone_pill, abbreviation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = pillDescription }
            )
        }
    }
}

/**
 * One full-width day page: positioned event blocks over a tap target, plus the
 * current-time line when this page is the grid-zone today.
 */
@Composable
private fun TimelineDayColumn(
    date: LocalDate,
    events: List<DisplayEvent>,
    hourHeight: Dp,
    gridZone: ZoneId,
    isToday: Boolean,
    nowMinutes: Int,
    showEventEmojis: Boolean,
    timePattern: String,
    columnWidth: Dp,
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // iOS-style containment nesting: a whole-day span becomes a background
    // layer; contained events draw on top, stepped right. Parents come first
    // in the list, so composition order doubles as z-order.
    val dayLayout = remember(events, date, hourHeight, gridZone) {
        TimelineLayout.layoutDay(
            events = events,
            date = date,
            zone = gridZone,
            hourHeight = hourHeight
        )
    }

    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Background tap target — below events in z-order.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(date, hourHeight) {
                    detectTapGestures(
                        onTap = { offset ->
                            val (hour, minute) = WeekViewUtils.offsetToTime(offset.y, hourHeightPx)
                            onEmptyTap(date, hour, minute)
                        }
                    )
                }
        )

        val usableWidth = columnWidth - GRID_END_PADDING
        dayLayout.positioned.forEach { positioned ->
            val eventWidth = usableWidth * positioned.widthFraction
            val eventLeft = usableWidth * positioned.leftFraction
            TimelineEventBlock(
                displayEvent = positioned.displayEvent,
                height = positioned.height,
                gridZone = gridZone,
                showEventEmojis = showEventEmojis,
                timePattern = timePattern,
                onClick = { onEventClick(positioned.displayEvent) },
                nestDepth = positioned.depth,
                modifier = Modifier
                    .offset(x = eventLeft, y = positioned.topOffset)
                    .width(eventWidth - 2.dp)
            )
        }

        if (dayLayout.overflow.isNotEmpty()) {
            org.onekash.kashcal.ui.components.weekview.OverflowBadge(
                count = dayLayout.overflow.size,
                onClick = { onOverflowClick(dayLayout.overflow) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 2.dp)
            )
        }

        // Current-time line (grid-zone clock), only on the grid-zone today.
        if (isToday) {
            val indicatorColor = MaterialTheme.colorScheme.error
            val yOffset = with(density) {
                (nowMinutes.toFloat() / WeekViewUtils.MINUTES_PER_HOUR * hourHeightPx).toDp()
            }
            Canvas(
                modifier = Modifier
                    .offset(y = yOffset - 1.dp)
                    .fillMaxWidth()
                    .height(2.dp)
            ) {
                drawLine(
                    color = indicatorColor,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 2f
                )
                drawCircle(
                    color = indicatorColor,
                    radius = 6f,
                    center = Offset(6f, size.height / 2)
                )
            }
        }
    }
}

private val TIME_COLUMN_WIDTH = 48.dp
private val GRID_END_PADDING = 8.dp

/** Extra scroll range past midnight so the FAB can't sit over the last events
 * (56dp FAB + 16dp margin — just enough, no dead gap). */
private val FAB_CLEARANCE = 72.dp
