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
package io.github.asm0dey.grimmdroid.reader.curl

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** Touch routing only: Robolectric has no GL surface, so the curl itself never moves here. */
@RunWith(AndroidJUnit4::class)
class TurnOverlayTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private val received = mutableListOf<Int>()
    private val content =
        View(activity).apply {
            setOnTouchListener { _, e ->
                received += e.actionMasked
                true
            }
        }
    private var curlOn = true
    private var selectingNow = false

    // The fake jump never runs: the test scope is never advanced, so a started turn stays busy.
    private val overlay =
        TurnOverlay(
            context = activity,
            content = content,
            scope = TestScope(),
            jump = { true },
            awaitPainted = {},
            onSettled = {},
            curlActive = { curlOn },
            selecting = { selectingNow },
        ).also {
            activity.setContentView(it)
            it.measure(exactly(WIDTH), exactly(HEIGHT))
            it.layout(0, 0, WIDTH, HEIGHT)
        }

    private var time = 0L

    private fun send(
        action: Int,
        x: Float,
        y: Float,
    ) {
        time += 16
        val event = MotionEvent.obtain(0, time, action, x, y, 0)
        overlay.dispatchTouchEvent(event)
        event.recycle()
    }

    private val x0 = WIDTH / 2f
    private val y0 = HEIGHT / 2f

    @Test
    fun tapPassesThrough() {
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_UP, x0, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), received)
        assertFalse(overlay.turning)
    }

    @Test
    fun verticalMovePassesThrough() {
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0, y0 - 3 * slop)
        send(MotionEvent.ACTION_MOVE, x0 - 8 * slop, y0 - 3 * slop) // horizontal overall now, but the gesture went vertical first
        send(MotionEvent.ACTION_UP, x0 - 8 * slop, y0 - 3 * slop)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            received,
        )
        assertFalse(overlay.turning)
    }

    @Test
    fun horizontalMoveTakesOverAndCancelsTheNavigator() {
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 6 * slop, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), received)
        assertTrue(overlay.turning)
    }

    @Test
    fun selectionBlocksTakeover() {
        selectingNow = true
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        assertFalse(MotionEvent.ACTION_CANCEL in received)
        assertFalse(overlay.turning)
    }

    @Test
    fun curlInactivePassesEverythingThrough() {
        curlOn = false
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        send(MotionEvent.ACTION_UP, x0 - 3 * slop, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), received)
        assertFalse(overlay.turning)
        assertFalse(overlay.turn(true))
    }

    @Test
    fun touchDuringATurnIsSwallowedUntilTheNextDown() {
        assertTrue(overlay.turn(true))
        assertTrue(overlay.turning)
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        send(MotionEvent.ACTION_UP, x0 - 3 * slop, y0)
        assertEquals(emptyList<Int>(), received)
        assertTrue(overlay.turn(true)) // ignored, but handled: the caller must not fall back
        overlay.abort()
        assertFalse(overlay.turning)
        send(MotionEvent.ACTION_DOWN, x0, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN), received)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400

        fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
