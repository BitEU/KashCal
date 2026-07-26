package org.onekash.kashcal.ui.components.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.components.eventStateDescription
import java.time.ZoneId

/**
 * Timed-event block for the Timeline view: a soft calendar-color-tinted card
 * with a solid color accent bar, title, location line (pin icon), and a time
 * line (clock icon) that appends a dual-timezone annotation when the event's
 * authored timezone differs from the grid's — e.g.
 * "6:15 PM – 11:59 PM EDT (6:15 PM EDT – 8:59 PM PDT)".
 *
 * Deliberately softer than the week view's solid [EventBlock][org.onekash.kashcal.ui.components.weekview.EventBlock]
 * fills — inspired by (not cloned from) iOS's day view.
 */
@Composable
fun TimelineEventBlock(
    displayEvent: DisplayEvent,
    height: Dp,
    gridZone: ZoneId,
    showEventEmojis: Boolean,
    timePattern: String,
    onClick: () -> Unit,
    /** Containment depth (0 = root). Nested blocks tint slightly deeper and
     * carry a subtle hairline so they read as a layer above their parent. */
    nestDepth: Int = 0,
    modifier: Modifier = Modifier
) {
    val color = displayEvent.eventColor ?: displayEvent.calendarColor
    val calColor = Color(color)
    val isFree = displayEvent.isFree
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    // Tinted wash instead of a solid fill; free-time events go lighter still
    // and keep their outline convention from the week view. Each nesting level
    // tints a step deeper so a child stays visible on its parent even when
    // both share one calendar color.
    val backgroundColor = remember(color, isFree, surfaceColor, nestDepth) {
        val base = if (isFree) 0.10f else 0.22f
        lerp(surfaceColor, calColor, base + 0.06f * nestDepth.coerceAtMost(3))
    }

    val annotation = remember(displayEvent, gridZone, timePattern) {
        if (displayEvent.isAllDay) null else TimelineUtils.dualTimezoneAnnotation(
            startTs = displayEvent.startTs,
            endTs = displayEvent.endTs,
            timezone = displayEvent.timezone,
            endTimezone = displayEvent.endTimezone,
            gridZone = gridZone,
            timePattern = timePattern
        )
    }
    // All-day events normally render in the all-day chip row, but label one
    // correctly here too — this defends against any future path that places
    // one in the grid (same convention as the week view's EventBlock).
    val timeText = if (displayEvent.isAllDay) {
        stringResource(R.string.label_all_day)
    } else {
        val range = remember(displayEvent, gridZone, timePattern, annotation) {
            TimelineUtils.gridTimeLabel(
                startTs = displayEvent.startTs,
                endTs = displayEvent.endTs,
                gridZone = gridZone,
                timePattern = timePattern,
                withZoneAbbreviation = annotation != null
            )
        }
        if (annotation != null) "$range ($annotation)" else range
    }

    // Fill the block's height iOS-style: taller blocks show more lines of
    // title, wrapped location, and the full dual-timezone string instead of
    // ellipsizing everything to one line.
    val lines = remember(height, displayEvent.location) {
        TimelineUtils.blockLineBudget(
            heightDp = height.value,
            hasLocation = !displayEvent.location.isNullOrBlank()
        )
    }
    val showTime = lines.time > 0
    val showLocation = lines.location > 0
    val titleMaxLines = lines.title
    val timeMaxLines = lines.time

    val stateLabel = eventStateDescription(
        isPast = false,
        isDeclined = displayEvent.isDeclinedByMe,
        isCancelled = displayEvent.isCancelled
    )
    val titleDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled)

    Box(
        modifier = modifier
            .height(height)
            .alpha(
                declinedCardAlpha(
                    isPast = false,
                    isDeclined = displayEvent.isDeclinedByMe,
                    isCancelled = displayEvent.isCancelled
                )
            )
            .then(
                if (stateLabel != null) {
                    Modifier.semantics(mergeDescendants = true) { stateDescription = stateLabel }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(
                if (isFree) Modifier.border(1.5.dp, calColor, RoundedCornerShape(8.dp))
                else if (nestDepth > 0) Modifier.border(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(8.dp)
                )
                else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Solid accent bar carrying the calendar color.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(calColor)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurfaceColor,
                    textDecoration = titleDecoration,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )

                if (showLocation) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Outlined.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = displayEvent.location!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = lines.location.coerceAtLeast(1),
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (showTime) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = titleDecoration,
                            maxLines = timeMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * All-day event chip for the Timeline's pinned all-day row: a rounded pill
 * washed with the calendar color, single-line title.
 */
@Composable
fun TimelineAllDayChip(
    displayEvent: DisplayEvent,
    showEventEmojis: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = displayEvent.eventColor ?: displayEvent.calendarColor
    val calColor = Color(color)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backgroundColor = remember(color, surfaceColor) { lerp(surfaceColor, calColor, 0.28f) }
    val stateLabel = eventStateDescription(
        isPast = false,
        isDeclined = displayEvent.isDeclinedByMe,
        isCancelled = displayEvent.isCancelled
    )

    Row(
        modifier = modifier
            .alpha(
                declinedCardAlpha(
                    isPast = false,
                    isDeclined = displayEvent.isDeclinedByMe,
                    isCancelled = displayEvent.isCancelled
                )
            )
            .then(if (stateLabel != null) Modifier.semantics { stateDescription = stateLabel } else Modifier)
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(calColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

