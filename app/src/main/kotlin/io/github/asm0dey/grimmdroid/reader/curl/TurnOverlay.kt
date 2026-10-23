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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.irurueta.android.gl.curl.CurlPage
import com.irurueta.android.gl.curl.CurlTextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What [TurnOverlay.turn] did. Only [UNSUPPORTED] means the caller should turn some other way. */
enum class TurnResult {
    /** A curl turn is under way; [TurnOverlay]'s `onSettled` will report it. */
    STARTED,

    /** A turn is running already: do nothing (a held key turns once). */
    BUSY,

    /** The curl is off, or there is no picture of the current page: fall back. */
    UNSUPPORTED,
}

/**
 * Wraps the navigator container [content] and plays a page curl over it: for finger drags and
 * flicks (it owns touch routing), and for [turn]s that play by themselves (keys, edge taps).
 *
 * Main thread only, like the views and the [TurnSession] it drives ([scope] must be main-thread).
 * [content] must fill the overlay: events reach it in the overlay's coordinates.
 *
 * The curl view is the cover: it sits on top, transparent while idle. A turn first raises it
 * holding the current page, once a frame drawn with that page has reached the screen, and only
 * then lets Readium jump underneath. Pages are indexed by [Side] ordinal: 0 previous, 1 current,
 * 2 next. [jump] and [awaitPainted] go to [TurnSession]; [onSettled] fires once per started turn.
 *
 * Touch: events go to [content] until a move is clearly horizontal (beyond touch slop, more
 * horizontal than vertical, [curlActive], not [selecting]); then [content] gets ACTION_CANCEL and
 * the curl gets the drag, left = forward. A gesture that starts while a turn runs is swallowed.
 */
@SuppressLint("ViewConstructor")
class TurnOverlay(
    context: Context,
    private val content: View,
    private val scope: CoroutineScope,
    jump: suspend (forward: Boolean) -> Boolean,
    awaitPainted: suspend () -> Unit,
    onSettled: (turned: Boolean) -> Unit,
    private val curlActive: () -> Boolean,
    private val selecting: () -> Boolean,
) : FrameLayout(context) {
    /** Duration of a [turn] that plays by itself. */
    var speedMs: Int = 450

    /** A turn is running, or the curl is still finishing one: new turns and touches are ignored. */
    val turning: Boolean get() = raising || session.busy || curlRunning

    private val pictures = PagePictures(content)

    // Copies handed to the curl: PagePictures redraws its bitmaps in place while the GL thread may upload them.
    private val copies = arrayOfNulls<Bitmap>(Side.entries.size)

    private val curl =
        CurlTextureView(context).apply {
            alpha = 0f // not INVISIBLE: the TextureView must have its surface before the first turn
            pageProvider =
                object : CurlTextureView.PageProvider {
                    override val pageCount = Side.entries.size

                    override fun updatePage(
                        page: CurlPage,
                        width: Int,
                        height: Int,
                        index: Int,
                        backIndex: Int?,
                    ) = page.setTexture(copies.getOrNull(index), CurlPage.SIDE_BOTH)
                }
        }

    private val settled: (Boolean) -> Unit = { turned ->
        curl.alpha = 0f // the overlay raises the cover itself, so the session may never lower it
        snapshot()
        onSettled(turned)
    }

    private val session = TurnSession(scope, jump, awaitPainted, onCover = ::cover, onSettled = settled)

    private var curlRunning = false // the curl has started and its turnListener hasn't fired
    private var forward = true
    private var auto = false
    private var jumpFirst = false // no picture of the target page: Readium jumps under the cover first
    private var instant = false // the cover never came up: the turn runs with no curl
    private var raising = false // waiting for a frame with the current page before showing the cover
    private var framesToWait = 0
    private var raiseTimeout: Job? = null
    private var expected = false // where the session was told the curl ends: on the new page or not
    private var watchdog: Job? = null

    private enum class Touch { PASS, DRAG, SWALLOW }

    private var touch = Touch.PASS
    private var navigatorOwns = false // the navigator keeps this gesture (it went vertical first, or no curl)
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var takeoverAt = 0L
    private var released = false // the finger lifted before the curl started
    private var releaseCompletes = false
    private var fingerDown = false // the curl got a DOWN and no UP/CANCEL yet
    private var curlDownTime = 0L
    private var velocity: VelocityTracker? = null
    private var flick: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(curl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        curl.turnListener = { completed ->
            if (curlRunning) {
                curlRunning = false
                fingerDown = false
                watchdog?.cancel()
                session.curlFinished(completed)
                idle()
            }
        }
        // GL's own listener stays in charge; this only learns when a new frame has reached the view.
        curl.surfaceTextureListener?.let { gl ->
            curl.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener by gl {
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        gl.onSurfaceTextureUpdated(surface)
                        frameDrawn()
                    }
                }
        }
    }

    /** An automatic turn; see [TurnResult]. */
    fun turn(forward: Boolean): TurnResult =
        when {
            turning -> TurnResult.BUSY
            !curlActive() -> TurnResult.UNSUPPORTED
            begin(forward, auto = true) -> TurnResult.STARTED
            else -> TurnResult.UNSUPPORTED
        }

    /** Redraws the neighbour pictures; ignored mid-turn, when Readium may be on the target page. */
    fun refreshPictures() {
        if (!turning) snapshot()
    }

    fun invalidatePictures() {
        pictures.invalidate()
        copies.fill(null)
    }

    /** Ends any turn now, curl included; the reader stays where it is. */
    fun abort() {
        val wasRaising = raising
        stopRaising()
        resetCurl()
        curl.alpha = 0f
        session.abort()
        if (wasRaising) settled(false) // the session never started: report the turn here
    }

    /** Call from the activity's onPause: ends any turn and pauses the curl's GL thread. */
    fun onPause() {
        abort()
        curl.onPause()
    }

    fun onResume() = curl.onResume()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // The last gesture never ended (a flick still moving the curl has ended: it lifts by itself).
            if (fingerDown && flick == null) sendToCurl(MotionEvent.ACTION_CANCEL, lastX, lastY)
            touch = if (turning) Touch.SWALLOW else Touch.PASS
            navigatorOwns = false
            downX = ev.x
            downY = ev.y
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(ev)
        when (touch) {
            Touch.SWALLOW -> {
                Unit
            }

            Touch.PASS -> {
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && takesOver(ev) &&
                    takeOver(ev)
                ) {
                    Unit
                } else {
                    content.dispatchTouchEvent(ev)
                }
            }

            Touch.DRAG -> {
                drag(ev)
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle()
            velocity = null
        }
        return true // keep the gesture: a takeover needs its later moves even if the navigator ignored the DOWN
    }

    private fun takesOver(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        val dy = abs(ev.y - downY)
        if (dy > slop && dy >= dx) navigatorOwns = true
        return !navigatorOwns && ev.pointerCount == 1 && dx > slop && dx > dy && curlActive() && !selecting()
    }

    /** False when no turn can start: the navigator keeps the gesture. */
    private fun takeOver(ev: MotionEvent): Boolean {
        lastX = ev.x
        lastY = ev.y
        takeoverAt = ev.eventTime
        if (!begin(ev.x < downX, auto = false)) {
            navigatorOwns = true
            return false
        }
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        content.dispatchTouchEvent(cancel)
        cancel.recycle()
        touch = Touch.DRAG
        return true
    }

    private fun drag(ev: MotionEvent) {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_MOVE) {
            lastX = ev.x
            lastY = ev.y
        }
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_MOVE) return
        if (!fingerDown) {
            // The curl hasn't started yet (the cover is coming up, or Readium jumps first): remember how the finger left.
            if (action != MotionEvent.ACTION_MOVE && !released) {
                released = true
                releaseCompletes = action == MotionEvent.ACTION_UP && (isFlick(ev) || passedMiddle(lastX))
            }
            return
        }
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                sendToCurl(MotionEvent.ACTION_MOVE, ev.x, ev.y)
                session.passedMiddle(passedMiddle(ev.x))
            }

            MotionEvent.ACTION_UP -> {
                if (isFlick(ev)) playFlick() else sendToCurl(MotionEvent.ACTION_UP, ev.x, ev.y)
            }

            else -> {
                sendToCurl(MotionEvent.ACTION_CANCEL, lastX, lastY)
            }
        }
    }

    private fun isFlick(ev: MotionEvent): Boolean {
        if (ev.eventTime - takeoverAt > FLICK_MS) return false
        val tracker = velocity ?: return false
        tracker.computeCurrentVelocity(1000)
        val vx = tracker.xVelocity
        return abs(vx) > minFling && (vx < 0) == forward
    }

    /**
     * A flick plays the rest of the turn by moving the finger, decelerating from finger speed, to
     * the completed position: a page width past the left edge forward, the right edge back, at the
     * anchor's y. There it lifts with a zero-length finish, so the turn reports at once. Not
     * [CurlTextureView.autoTurn]: the finger's curl is still running, and a new curl must not
     * start before its turnListener fires.
     */
    private fun playFlick() {
        val endX = if (forward) -width.toFloat() else width.toFloat()
        val startX = lastX
        val startY = lastY
        val endY = downY
        flick =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = curl.releaseDurationMs.toLong()
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val v = it.animatedValue as Float
                    val x = startX + (endX - startX) * v
                    sendToCurl(MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * v)
                    session.passedMiddle(passedMiddle(x))
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            flick = null
                            val release = curl.releaseDurationMs
                            curl.releaseDurationMs = 0 // read once, by the UP's finish
                            sendToCurl(MotionEvent.ACTION_UP, endX, endY)
                            curl.releaseDurationMs = release
                        }
                    },
                )
                start()
            }
    }

    private fun passedMiddle(x: Float) = if (forward) x < width / 2f else x > width / 2f

    /** Raises the cover; false (nothing started) when there is no picture of the current page. */
    private fun begin(
        forward: Boolean,
        auto: Boolean,
    ): Boolean {
        if (turning) return false
        if (copies[Side.CURRENT.ordinal] == null) snapshot() // e.g. after invalidatePictures
        if (copies[Side.CURRENT.ordinal] == null) return false
        this.forward = forward
        this.auto = auto
        instant = false
        released = false
        jumpFirst = copies[target()] == null
        raising = true
        framesToWait = 2
        if (curl.currentIndex != Side.CURRENT.ordinal) curl.setCurrentIndex(Side.CURRENT.ordinal)
        curl.requestRender()
        raiseTimeout =
            scope.launch {
                delay(FRAME_WAIT_MS)
                Log.w(TAG, "no curl frame within $FRAME_WAIT_MS ms; turning without a curl")
                stopRaising()
                instant = true
                session.start(forward, jumpNow = false, hasPicture = true) { session.curlFinished(true) }
            }
        return true
    }

    private fun target() = if (forward) Side.NEXT.ordinal else Side.PREVIOUS.ordinal

    /**
     * The first update after [begin] may hold a frame started before the pages were set, so the
     * cover waits for a second one, requested after the first has arrived.
     */
    private fun frameDrawn() {
        if (!raising) return
        if (--framesToWait > 0) return curl.requestRender()
        stopRaising()
        curl.alpha = 1f
        session.start(forward, jumpNow = auto, hasPicture = !jumpFirst, onReady = ::play)
    }

    private fun stopRaising() {
        raising = false
        raiseTimeout?.cancel()
        raiseTimeout = null
    }

    private fun cover(on: Boolean) {
        if (!on) {
            curl.alpha = 0f
        } else if (!instant) {
            curl.alpha = 1f
        }
    }

    /** The cover is up and, after a jump-first, Readium has painted the target: move the curl. */
    private fun play() {
        if (jumpFirst) {
            // The target is now Readium's current page; the cover keeps showing the old one at index 1.
            pictures.refresh()
            val copy = pictures[Side.CURRENT]?.copy(Bitmap.Config.ARGB_8888, false) ?: return session.abort()
            copies[target()] = copy
            curl.setPage(target(), copy)
        }
        curlRunning = true
        when {
            auto -> {
                curl.autoTurn(forward, speedMs)
                watch(true)
            }

            released && releaseCompletes -> {
                curl.autoTurn(forward, curl.releaseDurationMs)
                watch(true)
            }

            released -> {
                curlRunning = false
                session.curlFinished(false)
            }

            else -> {
                startFingerCurl()
            }
        }
    }

    private fun startFingerCurl() {
        curlDownTime = SystemClock.uptimeMillis()
        // The curl picks its direction from the DOWN's half of the page; the drag picks it here.
        sendToCurl(MotionEvent.ACTION_DOWN, if (forward) width - 1f else 0f, downY)
        fingerDown = true
        sendToCurl(MotionEvent.ACTION_MOVE, lastX, lastY)
        if (curl.curlState == CurlTextureView.CURL_NONE) {
            // No GL surface, or nothing to curl: the curl will never report, so end the turn.
            curlRunning = false
            sendToCurl(MotionEvent.ACTION_CANCEL, lastX, lastY)
            touch = Touch.SWALLOW
            session.abort()
            return
        }
        session.passedMiddle(passedMiddle(lastX))
    }

    private fun sendToCurl(
        action: Int,
        x: Float,
        y: Float,
    ) {
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            fingerDown = false
            if (curlRunning) watch(passedMiddle(x))
        }
        val event = MotionEvent.obtain(curlDownTime, SystemClock.uptimeMillis(), action, x, y, 0)
        curl.dispatchTouchEvent(event)
        event.recycle()
    }

    /** From the curl's end or release: if turnListener never comes (no GL frame), finish the curl anyway. */
    private fun watch(completes: Boolean) {
        expected = completes
        watchdog?.cancel()
        watchdog =
            scope.launch {
                delay(speedMs + WATCHDOG_SLACK_MS)
                Log.w(TAG, "curl never reported its end; treating it as finished")
                val completed = expected
                resetCurl()
                session.curlFinished(completed)
            }
    }

    /** Drops the curl at once: no animation left, back on the current page, nothing in flight. */
    private fun resetCurl() {
        flick?.let {
            it.removeAllListeners()
            it.cancel()
        }
        flick = null
        watchdog?.cancel()
        watchdog = null
        if (curlRunning || fingerDown) {
            curl.reset(Side.CURRENT.ordinal)
            if (touch == Touch.DRAG) touch = Touch.SWALLOW // the rest of this gesture goes nowhere
        }
        curlRunning = false
        fingerDown = false
    }

    /** Redraws the pictures and hands the curl fresh copies; old copies are dropped, never recycled (the GL thread may hold them). */
    private fun snapshot() {
        pictures.refresh()
        for (side in Side.entries) {
            val copy = pictures[side]?.copy(Bitmap.Config.ARGB_8888, false)
            copies[side.ordinal] = copy
            if (copy != null) curl.setPage(side.ordinal, copy)
        }
        idle()
    }

    /** Puts the idle curl on the current page, so the next cover starts there. */
    private fun idle() {
        if (!turning && curl.currentIndex != Side.CURRENT.ordinal) curl.setCurrentIndex(Side.CURRENT.ordinal)
    }

    private companion object {
        const val TAG = "TurnOverlay"
        const val FLICK_MS = 200
        const val FRAME_WAIT_MS = 100L // about two frames, plus a texture upload
        const val WATCHDOG_SLACK_MS = 1000L
    }
}
