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
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.irurueta.android.gl.curl.CurlTextureView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Touch routing and the turn's life cycle. Robolectric has no GL: frames are delivered by hand
 * ([frame]), and the curl itself never moves (it reports `false` or nothing at all).
 * The chapter is on page 0 of two: next has a picture, previous doesn't (a jump-first turn).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real pixels, so drawn pages aren't blank
class TurnOverlayTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private val received = mutableListOf<Int>()
    private val jumps = mutableListOf<Boolean>()
    private val settled = mutableListOf<Boolean>()
    private val web = TwoPageWebView(activity)
    private val content = Recorder(activity, received).apply { addView(web) }
    private val scope = TestScope()
    private var curlOn = true
    private var jumpOk = true
    private var selectingNow = false

    private val overlay =
        TurnOverlay(
            context = activity,
            content = content,
            scope = scope,
            jump = {
                jumps += it
                jumpOk
            },
            awaitPainted = {},
            onSettled = { settled += it },
            curlActive = { curlOn },
            selecting = { selectingNow },
        ).also {
            activity.setContentView(it)
            it.measure(exactly(WIDTH), exactly(HEIGHT))
            it.layout(0, 0, WIDTH, HEIGHT)
            // WebView hands its frame to the WebView provider, which Robolectric stubs out; set it directly.
            web.right = WIDTH
            web.bottom = HEIGHT
            it.refreshPictures()
        }

    private val curl = overlay.getChildAt(1) as CurlTextureView

    private fun frame() = curl.surfaceTextureListener!!.onSurfaceTextureUpdated(SurfaceTexture(0))

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
        send(MotionEvent.ACTION_MOVE, x0 - slop / 2, y0 - 6 * slop) // drifting sideways, within the slop
        send(MotionEvent.ACTION_UP, x0 - slop / 2, y0 - 6 * slop)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            received,
        )
        assertFalse(overlay.turning)
    }

    @Test
    fun diagonalMoveTakesOver() {
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0 + 3 * slop) // as much down as left: still a turn
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), received)
        assertTrue(overlay.turning)
    }

    @Test
    fun verticalThenFarHorizontalCurlsInsteadOfSliding() {
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0, y0 + 3 * slop) // down first: the navigator has it
        send(MotionEvent.ACTION_MOVE, x0 - 8 * slop, y0 + 3 * slop) // then clearly left
        send(MotionEvent.ACTION_MOVE, x0 - 9 * slop, y0 + 3 * slop)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), received)
        assertTrue(overlay.turning)
    }

    @Test
    fun horizontalMoveTakesOverAndCancelsTheNavigator() {
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 6 * slop, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), received)
        assertTrue(overlay.turning)
    }

    /** What the curl is sent: (action, x). */
    private fun recordCurl(): MutableList<Pair<Int, Float>> {
        val sent = mutableListOf<Pair<Int, Float>>()
        curl.setOnTouchListener { _, e ->
            sent += e.actionMasked to e.x
            false
        }
        return sent
    }

    @Test
    fun backwardDragIsAMirroredForwardCurl() {
        val sent = recordCurl()
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 + 3 * slop, y0)
        assertTrue(overlay.turning)
        assertEquals(-1f, curl.scaleX) // before the cover's first frame
        assertEquals(3, curl.currentIndex) // the mirrored current page
        frame()
        frame()
        scope.runCurrent() // jump first (no picture of the previous page), then the curl starts
        assertEquals(MotionEvent.ACTION_DOWN to WIDTH - 1f, sent[0]) // the right edge, as forward
        assertEquals(MotionEvent.ACTION_MOVE to WIDTH - (x0 + 3 * slop), sent[1])
    }

    @Test
    fun backwardTurnRestoresTheCurlAfterSettling() {
        overlay.turn(false)
        assertEquals(-1f, curl.scaleX)
        overlay.abort()
        assertEquals(listOf(false), settled)
        assertEquals(1f, curl.scaleX)
        assertEquals(1, curl.currentIndex)
    }

    @Test
    fun forwardDragIsNotMirrored() {
        val sent = recordCurl()
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        assertEquals(1f, curl.scaleX)
        assertEquals(1, curl.currentIndex)
        frame()
        frame()
        scope.runCurrent()
        assertEquals(MotionEvent.ACTION_DOWN to WIDTH - 1f, sent[0])
        assertEquals(MotionEvent.ACTION_MOVE to x0 - 3 * slop, sent[1])
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
        assertEquals(TurnResult.UNSUPPORTED, overlay.turn(true))
    }

    @Test
    fun noPictureOfTheCurrentPageFallsBack() {
        web.right = 0 // nothing can be drawn
        overlay.invalidatePictures()
        assertEquals(TurnResult.UNSUPPORTED, overlay.turn(true))
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), received)
        assertFalse(overlay.turning)
    }

    @Test
    fun touchDuringATurnIsSwallowedUntilTheNextDown() {
        assertEquals(TurnResult.STARTED, overlay.turn(true))
        send(MotionEvent.ACTION_DOWN, x0, y0)
        send(MotionEvent.ACTION_MOVE, x0 - 3 * slop, y0)
        send(MotionEvent.ACTION_UP, x0 - 3 * slop, y0)
        assertEquals(emptyList<Int>(), received)
        assertEquals(TurnResult.BUSY, overlay.turn(true))
        curlOn = false
        assertEquals(TurnResult.BUSY, overlay.turn(true)) // busy wins: the caller must not fall back
        overlay.abort()
        assertFalse(overlay.turning)
        assertEquals(listOf(false), settled)
        send(MotionEvent.ACTION_DOWN, x0, y0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN), received)
    }

    @Test
    fun coverWaitsForTwoFramesThenReadiumJumpsUnderIt() {
        overlay.turn(true)
        frame()
        assertEquals(0f, curl.alpha)
        frame()
        assertEquals(1f, curl.alpha)
        assertEquals(emptyList<Boolean>(), jumps)
        scope.runCurrent()
        assertEquals(listOf(true), jumps)
    }

    @Test
    fun noFrameMeansAnInstantTurn() {
        overlay.turn(true)
        scope.advanceTimeBy(101)
        scope.runCurrent()
        assertEquals(listOf(true), jumps)
        assertEquals(listOf(true), settled)
        assertEquals(0f, curl.alpha)
        assertFalse(overlay.turning)
    }

    @Test
    fun jumpFirstShowsTheCurrentPageBeforeTheJump() {
        assertEquals(TurnResult.STARTED, overlay.turn(false)) // no picture of the previous page
        frame()
        frame()
        assertEquals(1f, curl.alpha)
        assertEquals(emptyList<Boolean>(), jumps)
        scope.runCurrent()
        assertEquals(listOf(false), jumps)
        assertEquals(1f, curl.alpha)
    }

    @Test
    fun jumpFirstAtTheBooksEdgeDropsTheCover() {
        jumpOk = false
        overlay.turn(false)
        frame()
        frame()
        scope.runCurrent()
        assertEquals(listOf(false), settled)
        assertEquals(0f, curl.alpha)
        assertFalse(overlay.turning)
    }

    @Test
    fun curlThatNeverReportsIsFinishedByTheWatchdog() {
        overlay.turn(true)
        frame()
        frame()
        scope.runCurrent()
        scope.advanceTimeBy(overlay.speedMs + 1001L)
        scope.runCurrent()
        assertEquals(listOf(true), settled)
        assertFalse(overlay.turning)
        shadowOf(Looper.getMainLooper()).idle() // the curl's own late report is dropped
        assertEquals(listOf(true), settled)
    }

    @Test
    fun abortEndsARunningCurl() {
        overlay.turn(true)
        frame()
        frame()
        assertTrue(overlay.turning)
        overlay.abort()
        assertFalse(overlay.turning)
        assertEquals(0f, curl.alpha)
        assertEquals(listOf(false), settled)
    }

    @Test
    fun pauseAbortsTheTurn() {
        overlay.turn(true)
        overlay.onPause()
        assertFalse(overlay.turning)
        assertEquals(listOf(false), settled)
        overlay.onResume()
    }

    /** The navigator container: records what it is sent and keeps it from the WebView. */
    private class Recorder(
        context: Context,
        val received: MutableList<Int>,
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            received += ev.actionMasked
            return true
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 480

        fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
