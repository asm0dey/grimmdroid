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
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.irurueta.android.gl.curl.CurlPage
import com.irurueta.android.gl.curl.CurlTextureView
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/**
 * Wraps the navigator container [content] and plays a page curl over it: for finger drags and
 * flicks (it owns touch routing), and for [turn]s that play by themselves (keys, edge taps).
 *
 * Main thread only, like the views and the [TurnSession] it drives ([scope] must be main-thread).
 *
 * The curl view is the cover: it sits on top, transparent while idle, and is shown holding the
 * current picture before anything moves. Pages are indexed by [Side] ordinal: 0 previous,
 * 1 current, 2 next. [jump], [awaitPainted] and [onSettled] go to [TurnSession] as they are.
 *
 * Touch: events go to [content] until a move is clearly horizontal (beyond touch slop, more
 * horizontal than vertical, [curlActive], not [selecting]); then [content] gets ACTION_CANCEL and
 * the curl gets the drag, left = forward. A gesture that starts while a turn runs is swallowed.
 */
@SuppressLint("ViewConstructor")
class TurnOverlay(
    context: Context,
    private val content: View,
    scope: CoroutineScope,
    jump: suspend (forward: Boolean) -> Boolean,
    awaitPainted: suspend () -> Unit,
    onSettled: (turned: Boolean) -> Unit,
    private val curlActive: () -> Boolean,
    private val selecting: () -> Boolean,
) : FrameLayout(context) {
    /** Duration of a [turn] that plays by itself. */
    var speedMs: Int = 450

    /** A turn is running, or the curl is still finishing one: new turns and touches are ignored. */
    val turning: Boolean get() = session.busy || curlRunning

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

    private val session =
        TurnSession(scope, jump, awaitPainted, onCover = ::cover, onSettled = { turned ->
            snapshot()
            onSettled(turned)
        })

    private var curlRunning = false // the curl has started and its turnListener hasn't fired
    private var forward = true
    private var auto = false
    private var fromIndex = Side.CURRENT.ordinal // the page the turn starts from
    private var snapshotPending = false // the turn jumped first: picture the new position before curling

    private enum class Touch { PASS, DRAG, SWALLOW }

    private var touch = Touch.PASS
    private var vertical = false // this gesture went vertical first: it stays with the navigator
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var takeoverAt = 0L
    private var released = false // the finger lifted before the curl started (jump-first turn)
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
            curlRunning = false
            fingerDown = false
            session.curlFinished(completed)
            idle()
        }
    }

    /** An automatic turn. False when the curl is off, so the caller turns some other way; a turn running already swallows it. */
    fun turn(forward: Boolean): Boolean {
        if (!curlActive()) return false
        if (turning) return true
        return begin(forward, auto = true)
    }

    /** Redraws the neighbour pictures; ignored mid-turn, when Readium may be on the target page. */
    fun refreshPictures() {
        if (!session.busy) snapshot()
    }

    fun invalidatePictures() {
        pictures.invalidate()
        copies.fill(null)
    }

    /** Ends any turn now; the reader stays where it is. */
    fun abort() {
        flick?.cancel()
        session.abort()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // The last gesture never ended (a flick still moving the curl has ended: it lifts by itself).
            if (fingerDown && flick == null) sendToCurl(MotionEvent.ACTION_CANCEL, lastX, lastY)
            touch = if (turning) Touch.SWALLOW else Touch.PASS
            vertical = false
            downX = ev.x
            downY = ev.y
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(ev)
        when (touch) {
            Touch.SWALLOW -> Unit
            Touch.PASS -> if (ev.actionMasked == MotionEvent.ACTION_MOVE && takesOver(ev)) takeOver(ev) else content.dispatchTouchEvent(ev)
            Touch.DRAG -> drag(ev)
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
        if (dy > slop && dy >= dx) vertical = true
        return !vertical && ev.pointerCount == 1 && dx > slop && dx > dy && curlActive() && !selecting()
    }

    private fun takeOver(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        content.dispatchTouchEvent(cancel)
        cancel.recycle()
        touch = Touch.DRAG
        takeoverAt = ev.eventTime
        lastX = ev.x
        lastY = ev.y
        released = false
        if (!begin(ev.x < downX, auto = false)) touch = Touch.SWALLOW
    }

    private fun drag(ev: MotionEvent) {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_MOVE) {
            lastX = ev.x
            lastY = ev.y
        }
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_MOVE) return
        if (!fingerDown) {
            // The curl hasn't started yet (it waits for a jump-first picture): remember how the finger left.
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
     * A flick plays the rest of the turn by moving the finger to the completed position over the
     * release duration, then lifts it. Not [CurlTextureView.autoTurn]: the finger's curl is still
     * running, and a new curl must not start before its turnListener fires.
     */
    private fun playFlick() {
        val endX = if (forward) -width.toFloat() else width.toFloat()
        val startX = lastX
        val y = lastY
        flick =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = curl.releaseDurationMs.toLong()
                addUpdateListener {
                    val x = startX + (endX - startX) * (it.animatedValue as Float)
                    sendToCurl(MotionEvent.ACTION_MOVE, x, y)
                    session.passedMiddle(passedMiddle(x))
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { // also after cancel
                            flick = null
                            sendToCurl(MotionEvent.ACTION_UP, endX, y)
                        }
                    },
                )
                start()
            }
    }

    private fun passedMiddle(x: Float) = if (forward) x < width / 2f else x > width / 2f

    private fun begin(
        forward: Boolean,
        auto: Boolean,
    ): Boolean {
        if (turning) return false
        val target = if (forward) Side.NEXT else Side.PREVIOUS
        val hasPicture = pictures[Side.CURRENT] != null && pictures[target] != null
        this.forward = forward
        this.auto = auto
        // Jumping first leaves the old page one step behind the new current one.
        fromIndex =
            if (hasPicture) {
                Side.CURRENT.ordinal
            } else if (forward) {
                Side.PREVIOUS.ordinal
            } else {
                Side.NEXT.ordinal
            }
        snapshotPending = !hasPicture
        return session.start(forward, jumpNow = auto, hasPicture = hasPicture, onReady = ::play)
    }

    private fun cover(on: Boolean) {
        if (on) {
            if (snapshotPending) {
                snapshotPending = false
                snapshot()
            }
            if (curl.currentIndex != fromIndex) curl.setCurrentIndex(fromIndex)
        }
        curl.alpha = if (on) 1f else 0f
    }

    /** The cover is up: move the curl. */
    private fun play() {
        val to = fromIndex + if (forward) 1 else -1
        // Never curl a page that has no picture (a blank page is the black-screen risk): end the turn instead.
        if (pictures[Side.entries[fromIndex]] == null || pictures[Side.entries[to]] == null) return session.abort()
        curlRunning = true
        when {
            auto -> {
                curl.autoTurn(forward, speedMs)
            }

            released && releaseCompletes -> {
                curl.autoTurn(forward, curl.releaseDurationMs)
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
            // No GL surface yet, or nothing to curl: the curl will never report, so end the turn.
            sendToCurl(MotionEvent.ACTION_CANCEL, lastX, lastY)
            fingerDown = false
            curlRunning = false
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
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) fingerDown = false
        val event = MotionEvent.obtain(curlDownTime, SystemClock.uptimeMillis(), action, x, y, 0)
        curl.dispatchTouchEvent(event)
        event.recycle()
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

    /** Puts the idle curl on the current page, so the cover shows it from the first frame. */
    private fun idle() {
        if (!turning && curl.currentIndex != Side.CURRENT.ordinal) curl.setCurrentIndex(Side.CURRENT.ordinal)
    }

    private companion object {
        const val FLICK_MS = 200
    }
}
