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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State machine for one page turn under the curl overlay. No Android types.
 *
 * Single-threaded: every method and every callback runs on [scope]'s dispatcher
 * (the main thread in production).
 *
 * A turn: [start] -> (jump under the curl) -> [curlFinished] -> cover removed once the
 * jump has painted (or [paintTimeoutMs] passed) -> [onSettled]. Every path, including a
 * timeout, a failing callback and [abort], removes the cover and ends with [busy] == false.
 * [onSettled] fires exactly once for every [start] that returned true, so the caller can
 * always release its locator gating there.
 *
 * [jump] moves Readium instantly and returns false at the book's edge. [onSettled]`(true)`
 * means the reader is on the new page and the user sees it; `false` means it is on the
 * original page. The value reflects where the reader actually is: it changes only when a
 * [jump] returned true.
 */
class TurnSession(
    private val scope: CoroutineScope,
    private val jump: suspend (forward: Boolean) -> Boolean,
    private val awaitPainted: suspend () -> Unit,
    private val onCover: (Boolean) -> Unit,
    private val onSettled: (turned: Boolean) -> Unit,
    private val paintTimeoutMs: Long = 1500,
) {
    var busy = false
        private set

    private var forward = true
    private var wantNew = false // the page the turn is heading for
    private var onNewPage = false // where the reader actually is
    private var turn: Job? = null // parent of every jump of this turn
    private var painted = true // the latest jump has painted
    private var noTarget = false // the book has no page in that direction
    private var curlDone = false
    private var completed = false
    private var covered = false
    private var readyPending: (() -> Unit)? = null
    private var gen = 0
    private var job: Job? = null

    /** Returns false (do nothing) while a turn is running. */
    fun start(
        forward: Boolean,
        jumpNow: Boolean,
        hasPicture: Boolean,
        onReady: () -> Unit,
    ): Boolean {
        if (busy) return false
        busy = true
        this.forward = forward
        wantNew = false
        onNewPage = false
        turn = Job(scope.coroutineContext[Job])
        painted = true
        noTarget = false
        curlDone = false
        completed = false
        if (hasPicture) {
            showCover()
            onReady()
            if (jumpNow) launchJump(true)
        } else {
            readyPending = onReady // the picture needs the new page, so jump first
            launchJump(true)
        }
        return true
    }

    fun passedMiddle(passed: Boolean) {
        if (!busy || curlDone || noTarget || wantNew == passed) return
        launchJump(passed)
    }

    fun curlFinished(completed: Boolean) {
        if (!busy || curlDone) return
        curlDone = true
        this.completed = completed && !noTarget
        if (this.completed != wantNew && !noTarget) launchJump(this.completed)
        tryFinish()
    }

    /** Ends any turn now; the reader stays wherever it is and the cover goes away. */
    fun abort() {
        if (!busy) return
        finish(onNewPage)
    }

    private fun launchJump(toNew: Boolean) {
        val prev = job
        val mine = ++gen
        wantNew = toNew
        painted = false
        job =
            scope.launch(turn!!) {
                prev?.join()
                try {
                    var returned = false
                    var ok = true
                    // jump and paint share one bound: Readium's go() can get stuck
                    withTimeoutOrNull(paintTimeoutMs) {
                        if (toNew != onNewPage) {
                            ok = jump(if (toNew) forward else !forward)
                            returned = true
                            if (ok) {
                                onNewPage = toNew
                                awaitPainted()
                            }
                        } else {
                            returned = true // already there
                        }
                    }
                    if (!returned) return@launch finish(onNewPage) // jump hung
                    if (!ok && toNew) {
                        noTarget = true
                        wantNew = false
                    }
                    if (mine != gen) return@launch
                    if (!ok && readyPending != null) return@launch finish(onNewPage)
                    painted = true
                    readyPending?.let {
                        readyPending = null
                        showCover()
                        it()
                    }
                    tryFinish()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    finish(onNewPage)
                }
            }
    }

    private fun tryFinish() {
        if (curlDone && painted) finish(onNewPage)
    }

    private fun finish(turned: Boolean) {
        if (!busy) return
        busy = false
        readyPending = null
        turn?.cancel()
        hideCover()
        onSettled(turned)
    }

    private fun showCover() {
        covered = true
        onCover(true)
    }

    private fun hideCover() {
        if (covered) onCover(false)
        covered = false
    }
}
