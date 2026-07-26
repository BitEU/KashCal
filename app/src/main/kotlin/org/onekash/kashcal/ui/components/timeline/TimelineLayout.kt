package org.onekash.kashcal.ui.components.timeline

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Timeline view's block-layout engine: iOS-style *containment nesting*
 * instead of the week view's flat column packing.
 *
 * Model (mirroring Apple Calendar's day view, per the reference screenshots):
 * - An event whose (day-clamped) span fully contains another's becomes a
 *   near-full-width *background layer*; the contained events draw on top of
 *   it, stepped slightly right ([NEST_INSET_FRACTION] per level). A hotel stay
 *   covering the whole day reads as a backdrop, and the day's meetings sit on
 *   it at full width.
 * - Events that partially overlap (neither contains the other) split their
 *   window side-by-side, like a flight and a check-in that merely intersect.
 * - Free width is never wasted: siblings that don't overlap in time each take
 *   the whole window, and a column whose right-hand slots are empty expands
 *   into them.
 *
 * Pure JVM (no Compose runtime, Dp is just a value class) so the whole layout
 * is unit-testable.
 */
object TimelineLayout {

    /** Left step applied to each nesting level, as a fraction of column width. */
    const val NEST_INSET_FRACTION = 0.05f

    /** Max side-by-side columns within one sibling group before overflow. */
    const val MAX_SIBLING_COLUMNS = 4

    /**
     * Minimum unobscured header height (dp) a background parent needs to be
     * worth rendering as a backdrop. A parent whose nested child starts within
     * this zone would have its title buried (the "DEFCON ate the hotel" case:
     * two continuing events both clamped to 12 AM) — the burying child
     * detaches and the pair splits side-by-side instead, titles visible on
     * both, matching Apple Calendar's treatment of two continuing events.
     */
    const val MIN_PARENT_HEADER_DP = 52f

    /**
     * One laid-out block. The list returned by [layoutDay] is ordered
     * parents-first (depth ascending), so rendering in list order stacks
     * children on top of their background parents.
     */
    data class Positioned(
        val displayEvent: DisplayEvent,
        val topOffset: Dp,
        val height: Dp,
        val leftFraction: Float,
        val widthFraction: Float,
        /** Containment depth: 0 = root, 1 = drawn on top of one background parent, ... */
        val depth: Int,
        val startMinutes: Int,
        val endMinutes: Int,
    )

    /** The day's blocks plus any events that overflowed a crowded sibling group. */
    data class DayLayout(
        val positioned: List<Positioned>,
        val overflow: List<DisplayEvent>,
    )

    private data class Span(
        val event: DisplayEvent,
        val startMinutes: Int,
        val endMinutes: Int,
    ) {
        val duration: Int get() = endMinutes - startMinutes
        fun overlaps(other: Span): Boolean =
            startMinutes < other.endMinutes && endMinutes > other.startMinutes
        fun contains(other: Span): Boolean =
            startMinutes <= other.startMinutes &&
                endMinutes >= other.endMinutes &&
                duration > other.duration
    }

    fun layoutDay(
        events: List<DisplayEvent>,
        date: LocalDate,
        zone: ZoneId,
        hourHeight: Dp,
    ): DayLayout {
        if (events.isEmpty()) return DayLayout(emptyList(), emptyList())

        val spans = buildClampedSpans(events, date, zone, hourHeight)
        if (spans.isEmpty()) return DayLayout(emptyList(), emptyList())

        // Containment parent: the *smallest* span that fully contains this one
        // (deepest nesting, like iOS). Ties break to the earlier-sorted span.
        val parentOf = IntArray(spans.size) { -1 }
        for (i in spans.indices) {
            var best = -1
            for (j in spans.indices) {
                if (i == j) continue
                if (spans[j].contains(spans[i])) {
                    if (best == -1 || spans[j].duration < spans[best].duration) best = j
                }
            }
            parentOf[i] = best
        }

        // Buried-header detach: a nested child that starts within its parent's
        // header zone (e.g. two events continuing across this day, both
        // clamped to 12 AM) would cover the parent's title completely. Detach
        // such children — the pair becomes overlapping siblings and splits
        // side-by-side with both titles visible, matching Apple Calendar's
        // rendering of two continuing events. Fixed-point: each detach moves
        // the child strictly up the containment chain, so this terminates.
        val headerMinutes = (MIN_PARENT_HEADER_DP / hourHeight.value * 60).toInt()
        var changed = true
        while (changed) {
            changed = false
            for (i in spans.indices) {
                val p = parentOf[i]
                if (p != -1 && spans[i].startMinutes - spans[p].startMinutes < headerMinutes) {
                    parentOf[i] = parentOf[p]
                    changed = true
                }
            }
        }

        val depthOf = IntArray(spans.size) { -1 }
        fun depth(i: Int): Int {
            depthOf[i].takeIf { it >= 0 }?.let { return it }
            val d = if (parentOf[i] == -1) 0 else depth(parentOf[i]) + 1
            depthOf[i] = d
            return d
        }
        spans.indices.forEach { depth(it) }

        // Lay out sibling groups top-down so each child window derives from an
        // already-placed parent.
        val windows = arrayOfNulls<Pair<Float, Float>>(spans.size) // (left, width)
        val overflow = mutableListOf<DisplayEvent>()
        val byParent = spans.indices.groupBy { parentOf[it] }

        fun layoutGroup(parentIndex: Int) {
            val members = byParent[parentIndex] ?: return
            val (areaLeft, areaWidth) = if (parentIndex == -1) {
                0f to 1f
            } else {
                val (pl, pw) = windows[parentIndex] ?: (0f to 1f)
                // Children step right past the parent's accent bar; never below
                // half the parent's width so deep nesting stays readable.
                val inset = minOf(NEST_INSET_FRACTION, pw / 2f)
                (pl + inset) to (pw - inset)
            }

            // Slot-pack the siblings (first slot that has no time conflict).
            val slotEnds = mutableListOf<MutableList<Span>>()
            val slotOf = HashMap<Int, Int>()
            for (i in members.sortedWith(compareBy({ spans[it].startMinutes }, { -spans[it].duration }))) {
                val span = spans[i]
                val free = slotEnds.indexOfFirst { slot -> slot.none { it.overlaps(span) } }
                val slot = if (free >= 0) free else {
                    slotEnds.add(mutableListOf())
                    slotEnds.size - 1
                }
                slotEnds[slot].add(span)
                slotOf[i] = slot
            }

            // Cluster transitively-overlapping siblings so column math only
            // divides among events that actually share screen time.
            val clusters = findClusters(members.map { spans[it] })
            for (cluster in clusters) {
                val clusterIndices = cluster.map { members[it] }
                // Rank the cluster's slots so columns are dense even when the
                // group's slot numbering has gaps (a slot may be occupied only
                // by another cluster's events).
                val rankedSlots = clusterIndices.map { slotOf.getValue(it) }.distinct().sorted()
                val columnOf = rankedSlots.withIndex().associate { (rank, slot) -> slot to rank }
                val effectiveCols = minOf(rankedSlots.size, MAX_SIBLING_COLUMNS)
                val colWidth = areaWidth / effectiveCols
                for (i in clusterIndices) {
                    val column = columnOf.getValue(slotOf.getValue(i))
                    if (column >= MAX_SIBLING_COLUMNS) {
                        overflow.add(spans[i].event)
                        continue
                    }
                    // Expand rightward across columns free of conflicting siblings.
                    val conflictCols = clusterIndices
                        .filter { it != i && spans[it].overlaps(spans[i]) }
                        .map { columnOf.getValue(slotOf.getValue(it)) }
                    val expandTo = conflictCols.filter { it > column }.minOrNull() ?: effectiveCols
                    val spanCols = (minOf(expandTo, effectiveCols) - column).coerceAtLeast(1)
                    windows[i] = (areaLeft + column * colWidth) to (colWidth * spanCols)
                }
            }
            // Recurse into each member's own children.
            for (i in members) {
                if (windows[i] != null) layoutGroup(i)
            }
        }
        layoutGroup(-1)

        val positioned = spans.indices
            .filter { windows[it] != null }
            .sortedWith(compareBy({ depthOf[it] }, { spans[it].startMinutes }))
            .map { i ->
                val span = spans[i]
                val (left, width) = windows[i]!!
                val topMinutes = span.startMinutes.coerceIn(0, MINUTES_PER_DAY)
                val endMinutes = span.endMinutes.coerceIn(0, MINUTES_PER_DAY)
                Positioned(
                    displayEvent = span.event,
                    topOffset = (topMinutes.toFloat() / 60f * hourHeight.value).dp,
                    height = maxOf(
                        ((endMinutes - topMinutes).toFloat() / 60f * hourHeight.value).dp,
                        WeekViewUtils.MIN_EVENT_HEIGHT
                    ),
                    leftFraction = left,
                    widthFraction = width,
                    depth = depthOf[i],
                    startMinutes = span.startMinutes,
                    endMinutes = span.endMinutes,
                )
            }
        return DayLayout(positioned, overflow)
    }

    /**
     * Day-clamped spans in minutes, with the same small-event handling as the
     * week grid: sub-minimum events get a layout window at least as tall as
     * their rendered block, and a multi-day event's zero-length midnight
     * sliver drops off the day entirely.
     *
     * Zone-parameterized sibling of Step 2 in
     * [WeekViewUtils.positionEventsForDay] — these clamp rules encode
     * hard-won fixes (issues #175/#209-adjacent); keep the two in sync.
     */
    private fun buildClampedSpans(
        events: List<DisplayEvent>,
        date: LocalDate,
        zone: ZoneId,
        hourHeight: Dp,
    ): List<Span> {
        val defaultMinHeightMinutes =
            (WeekViewUtils.MIN_EVENT_HEIGHT.value / WeekViewUtils.HOUR_HEIGHT.value * 60).toInt()
        val minHeightMinutes = (WeekViewUtils.MIN_EVENT_HEIGHT.value / hourHeight.value * 60)
            .toInt().coerceIn(1, defaultMinHeightMinutes)

        return events.mapIndexedNotNull { index, event ->
            val start = Instant.ofEpochMilli(event.startTs).atZone(zone)
            val end = Instant.ofEpochMilli(event.endTs).atZone(zone)
            val startDate = start.toLocalDate()
            val endDate = end.toLocalDate()

            val startMinutes = if (startDate < date) 0 else start.hour * 60 + start.minute
            val rawEndMinutes = if (endDate > date) MINUTES_PER_DAY else end.hour * 60 + end.minute

            val isMidnightSliver = startDate < date && rawEndMinutes == startMinutes
            val endMinutes = if (!isMidnightSliver && rawEndMinutes - startMinutes < minHeightMinutes) {
                startMinutes + minHeightMinutes
            } else {
                rawEndMinutes
            }
            if (endMinutes <= startMinutes) return@mapIndexedNotNull null
            Span(event, startMinutes, endMinutes)
        }
    }

    /**
     * Transitive-overlap clusters over a sibling group (indices into the group
     * list). Mirrors [WeekViewUtils]'s private findConnectedClusters; sharing
     * would mean genericizing over both files' private span types for a
     * 25-line algorithm — keep fixes in sync instead.
     */
    private fun findClusters(spans: List<Span>): List<Set<Int>> {
        val clusters = mutableListOf<MutableSet<Int>>()
        for (i in spans.indices) {
            val overlapping = clusters.filter { cluster ->
                cluster.any { j -> spans[i].overlaps(spans[j]) }
            }
            when (overlapping.size) {
                0 -> clusters.add(mutableSetOf(i))
                1 -> overlapping[0].add(i)
                else -> {
                    val merged = mutableSetOf(i)
                    overlapping.forEach { merged.addAll(it) }
                    clusters.removeAll(overlapping.toSet())
                    clusters.add(merged)
                }
            }
        }
        return clusters
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
