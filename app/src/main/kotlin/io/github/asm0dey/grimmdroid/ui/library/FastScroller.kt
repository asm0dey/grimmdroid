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
package io.github.asm0dey.grimmdroid.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TouchSize = 48.dp

/** A draggable thumb on the grid's end edge; while dragging, a bubble shows [label] for the item under the thumb. */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    label: (index: Int) -> String?,
    modifier: Modifier = Modifier,
) {
    if (itemCount < 2) return
    val currentLabel by rememberUpdatedState(label)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var bubble by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val track = constraints.maxHeight - with(LocalDensity.current) { TouchSize.roundToPx() }
        val last = itemCount - 1

        // Read in the layout phase so scrolling moves the thumb without recomposing.
        fun fraction() = if (dragging) dragFraction else state.firstVisibleItemIndex.toFloat() / last
        if (dragging) {
            bubble?.let {
                Surface(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-TouchSize.roundToPx(), (fraction() * track).roundToInt()) }
                        .size(TouchSize),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction() * track).roundToInt()) }
                .size(TouchSize)
                .pointerInput(itemCount, track) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFraction = fraction()
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { _, dy ->
                        dragFraction = (dragFraction + dy / track).coerceIn(0f, 1f)
                        val index = (dragFraction * last).roundToInt()
                        currentLabel(index)?.let { bubble = it }
                        scope.launch { state.scrollToItem(index) }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.padding(end = 4.dp).size(6.dp, 40.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}
