/*
 * Grimmdroid - Android client for the Grimmory book server
 * Copyright (C) 2026 Grimmdroid contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.asm0dey.grimmdroid.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.asm0dey.grimmdroid.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Height of the reader's status bar; the page leaves this much room for it at the bottom. */
val STATUS_BAR_HEIGHT = 24.dp

/** Whether the page carries the status bar: on, with something to show. */
val ReaderSettings.statusBarShown: Boolean get() = statusBarVisible && statusItems.isNotEmpty()

/** What the status bar shows. Positions are Readium's (fixed-size chunks of text), not screen pages. */
data class StatusInputs(
    val totalProgression: Double,
    val position: Int?,
    val positionCount: Int?,
    val chapterPositionsLeft: Int?,
    val chapterTitle: String?,
    val nowMillis: Long,
    val batteryPercent: Int?,
    val msPerPosition: Double?,
    val chapterTickFractions: List<Double>,
)

/**
 * Inputs for [position] in a book whose reading order has [resourceSizes] positions per resource, numbered from 1
 * across the whole book. A resource stands in for a chapter.
 */
fun statusInputs(
    totalProgression: Double,
    position: Int?,
    resourceSizes: List<Int>,
    chapterTitle: String?,
    nowMillis: Long,
    batteryPercent: Int? = null,
    msPerPosition: Double? = null,
): StatusInputs {
    val count = resourceSizes.sum()
    // Last position of each resource.
    val ends = resourceSizes.runningReduce(Int::plus)
    return StatusInputs(
        totalProgression = totalProgression,
        position = position,
        positionCount = count.takeIf { it > 0 },
        chapterPositionsLeft = position?.let { p -> ends.firstOrNull { it >= p }?.minus(p) },
        chapterTitle = chapterTitle,
        nowMillis = nowMillis,
        batteryPercent = batteryPercent,
        msPerPosition = msPerPosition,
        // Readium's position k sits at (k - 1) / count; the next resource starts at the position after an end.
        chapterTickFractions = ends.dropLast(1).map { it / count.toDouble() },
    )
}

/** The text of [item], or null for the drawn progress bar and when its input is unknown. */
fun statusText(
    item: StatusItem,
    inputs: StatusInputs,
    locale: Locale,
): String? =
    with(inputs) {
        when (item) {
            StatusItem.PROGRESS_BAR -> {
                null
            }

            StatusItem.PERCENT -> {
                NumberFormat.getPercentInstance(locale).format(totalProgression)
            }

            StatusItem.PAGE_OF -> {
                if (position == null ||
                    positionCount == null
                ) {
                    null
                } else {
                    String.format(locale, "%d / %d", position, positionCount)
                }
            }

            StatusItem.PAGES_LEFT_IN_CHAPTER -> {
                chapterPositionsLeft?.let { String.format(locale, "%d left in chapter", it) }
            }

            StatusItem.CHAPTER_TITLE -> {
                chapterTitle
            }

            StatusItem.CLOCK -> {
                DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(nowMillis))
            }

            StatusItem.BATTERY -> {
                batteryPercent?.let { NumberFormat.getPercentInstance(locale).format(it / 100.0) }
            }

            StatusItem.TIME_LEFT_CHAPTER -> {
                timeLeft(chapterPositionsLeft, msPerPosition, locale)
            }

            StatusItem.TIME_LEFT_BOOK -> {
                timeLeft(position?.let { p -> positionCount?.minus(p) }, msPerPosition, locale)
            }
        }
    }

private fun timeLeft(
    positions: Int?,
    msPerPosition: Double?,
    locale: Locale,
): String? {
    if (positions == null || msPerPosition == null) return null
    val minutes = ceil((positions * msPerPosition).milliseconds / 1.minutes).toInt()
    return if (minutes < 60) {
        String.format(locale, "%d min left", minutes)
    } else {
        String.format(locale, "%d h %d min left", minutes / 60, minutes % 60)
    }
}

/**
 * Time per Readium position this session: the running average over forward moves of a few positions, leaving out
 * pauses over [MAX_GAP] and jumps (TOC, seek, back); unknown until [MIN_SAMPLES] moves.
 */
class ReadingPace(
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private var last: Int? = null
    private var since: TimeMark? = null
    private var time = Duration.ZERO
    private var positions = 0
    private var samples = 0

    val msPerPosition: Double?
        get() = if (samples < MIN_SAMPLES) null else time.inWholeMilliseconds.toDouble() / positions

    fun onPosition(position: Int) {
        val from = last
        if (from == position) return // still reading the same position
        val elapsed = since?.elapsedNow()
        if (from != null && elapsed != null && position - from in 1..MAX_STEP && elapsed <= MAX_GAP) {
            time += elapsed
            positions += position - from
            samples++
        }
        last = position
        since = clock.markNow()
    }

    private companion object {
        val MAX_GAP = 5.minutes
        const val MIN_SAMPLES = 3

        // shortcut: a screen page of more than this many positions (a tiny font on a tablet) counts as a jump.
        const val MAX_STEP = 10
    }
}

/**
 * The reader's status bar: [items] in order in one thin row, in [palette]'s colours. The progress bar takes the
 * row's free width.
 */
@Composable
fun StatusBar(
    items: List<StatusItem>,
    inputs: StatusInputs,
    palette: Palette,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier
            .fillMaxWidth()
            .height(STATUS_BAR_HEIGHT)
            .background(palette.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            if (item == StatusItem.PROGRESS_BAR) {
                ProgressLine(inputs.totalProgression, inputs.chapterTickFractions, palette, Modifier.weight(1f))
                return@forEach
            }
            val text = statusText(item, inputs, locale) ?: return@forEach
            if (item == StatusItem.BATTERY) {
                Icon(painterResource(R.drawable.ic_battery), null, Modifier.size(12.dp), tint = palette.fg)
            }
            Text(
                text,
                // Only the title may shrink; a lone title fills the row.
                if (item == StatusItem.CHAPTER_TITLE) Modifier.weight(1f, fill = false) else Modifier,
                color = palette.fg,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressLine(
    progression: Double,
    ticks: List<Double>,
    palette: Palette,
    modifier: Modifier,
) {
    val track = palette.fg.copy(alpha = 0.2f).compositeOver(palette.bg)
    Canvas(modifier.height(2.dp)) {
        drawRect(track)
        drawRect(palette.link, size = Size(size.width * progression.toFloat().coerceIn(0f, 1f), size.height))
        // Ticks stand out of the line, in the text colour, so they show over both the track and the fill.
        ticks.forEach { t ->
            drawRect(palette.fg, Offset(size.width * t.toFloat() - 0.5.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
        }
    }
}

/** The current time, updated on each minute. */
@Composable
fun minuteClock(): Long =
    produceState(System.currentTimeMillis()) {
        while (true) {
            delay((MINUTE_MS - value % MINUTE_MS).milliseconds)
            value = System.currentTimeMillis()
        }
    }.value

private const val MINUTE_MS = 60_000L

/** The battery level in percent from the sticky battery broadcast; null until known. */
@Composable
fun batteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    percent = intent.batteryPercent()
                }
            }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat
            .registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )?.let { percent = it.batteryPercent() }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * 100 / scale
}
