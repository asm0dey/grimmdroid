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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TurnSessionSpec :
    FunSpec({
        class Rig(
            scope: TestScope,
            jumpResult: Boolean = true,
            jumpFn: suspend (Boolean) -> Boolean = { jumpResult },
            paint: suspend () -> Unit = {},
        ) {
            val log = mutableListOf<String>()
            val session =
                TurnSession(
                    scope = scope,
                    jump = { fwd ->
                        log += "jump:${if (fwd) "fwd" else "back"}"
                        jumpFn(fwd)
                    },
                    awaitPainted = {
                        paint()
                        log += "painted"
                    },
                    onCover = { log += "cover:$it" },
                    onSettled = { log += "settled:$it" },
                )

            fun start(
                jumpNow: Boolean,
                hasPicture: Boolean = true,
            ) = session.start(true, jumpNow, hasPicture) { log += "ready" }
        }

        test("automatic turn jumps at once and uncovers after curl and paint") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = true) shouldBe true
                runCurrent()
                r.log shouldBe listOf("cover:true", "ready", "jump:fwd", "painted")
                r.session.busy shouldBe true
                r.session.curlFinished(true)
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
                r.session.busy shouldBe false
            }
        }

        test("curl finishing before paint keeps the cover until paint") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val r = Rig(this, paint = { gate.await() })
                r.start(jumpNow = true)
                runCurrent()
                r.session.curlFinished(true)
                r.log.contains("cover:false") shouldBe false
                gate.complete(Unit)
                runCurrent()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
            }
        }

        test("drag jumps only after passing the middle") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                runCurrent()
                r.log.none { it.startsWith("jump") } shouldBe true
                r.session.passedMiddle(true)
                runCurrent()
                r.log.count { it == "jump:fwd" } shouldBe 1
                r.session.passedMiddle(true)
                runCurrent()
                r.log.count { it == "jump:fwd" } shouldBe 1
                r.session.curlFinished(true)
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
            }
        }

        test("drag back before the middle jumps back") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                r.session.passedMiddle(true)
                runCurrent()
                r.session.passedMiddle(false)
                runCurrent()
                r.log.filter { it.startsWith("jump") } shouldBe listOf("jump:fwd", "jump:back")
                r.session.curlFinished(false)
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:false")
            }
        }

        test("completed drag that never passed the middle still jumps") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                r.session.curlFinished(true)
                runCurrent()
                r.log.takeLast(4) shouldBe listOf("jump:fwd", "painted", "cover:false", "settled:true")
            }
        }

        test("cancelled turn after a jump jumps back, waits for paint, then uncovers") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = true)
                runCurrent()
                r.session.curlFinished(false)
                r.log.contains("cover:false") shouldBe false
                runCurrent()
                r.log.takeLast(4) shouldBe listOf("jump:back", "painted", "cover:false", "settled:false")
                r.session.busy shouldBe false
            }
        }

        test("cancelled drag that never jumped just uncovers") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                r.session.curlFinished(false)
                r.log shouldBe listOf("cover:true", "ready", "cover:false", "settled:false")
            }
        }

        test("missing picture jumps first, then covers and readies after paint") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false, hasPicture = false)
                runCurrent()
                r.log shouldBe listOf("jump:fwd", "painted", "cover:true", "ready")
                r.session.curlFinished(true)
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
            }
        }

        test("no neighbour at the book's edge → nothing happens") {
            runTest {
                val r = Rig(this, jumpResult = false)
                r.start(jumpNow = true, hasPicture = false) shouldBe true
                runCurrent()
                r.log shouldBe listOf("jump:fwd", "settled:false")
                r.session.busy shouldBe false
            }
        }

        test("second turn while busy is ignored") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = true) shouldBe true
                r.start(jumpNow = true) shouldBe false
                r.session.abort()
            }
        }

        test("paint never arrives → curl removed after timeout") {
            runTest {
                val r = Rig(this, paint = { awaitCancellation() })
                r.start(jumpNow = true)
                runCurrent()
                r.session.curlFinished(true)
                advanceTimeBy(1499)
                r.log.contains("cover:false") shouldBe false
                advanceTimeBy(2)
                runCurrent()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
                r.session.busy shouldBe false
            }
        }

        test("abort removes the curl and keeps the navigator page") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                r.session.abort()
                r.log shouldBe listOf("cover:true", "ready", "cover:false", "settled:false")
                r.session.passedMiddle(true)
                runCurrent()
                r.log.none { it.startsWith("jump") } shouldBe true
                r.session.busy shouldBe false
            }
        }

        test("abort after the jump reports the reader on the new page") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = true)
                runCurrent()
                r.session.abort()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
                r.session.busy shouldBe false
            }
        }

        test("abort during forth/back toggling leaves no late jump") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                r.session.passedMiddle(true)
                r.session.passedMiddle(false)
                r.session.abort()
                runCurrent()
                r.log.none { it.startsWith("jump") } shouldBe true
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:false")
            }
        }

        test("abort while the forth jump is in flight cancels it") {
            runTest {
                val r = Rig(this, jumpFn = { awaitCancellation() })
                r.start(jumpNow = true)
                runCurrent()
                r.session.abort()
                runCurrent()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:false")
                r.session.busy shouldBe false
            }
        }

        test("forth, back, forth ends completed on the new page") {
            runTest {
                val r = Rig(this)
                r.start(jumpNow = false)
                r.session.passedMiddle(true)
                r.session.passedMiddle(false)
                r.session.passedMiddle(true)
                runCurrent()
                r.session.curlFinished(true)
                r.log.filter { it.startsWith("jump") } shouldBe listOf("jump:fwd", "jump:back", "jump:fwd")
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
            }
        }

        test("failed jump back settles on the new page") {
            runTest {
                val r = Rig(this, jumpFn = { fwd -> fwd })
                r.start(jumpNow = true)
                runCurrent()
                r.session.curlFinished(false)
                runCurrent()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
                r.session.busy shouldBe false
            }
        }

        test("hung jump ends the turn after the timeout") {
            runTest {
                val r = Rig(this, jumpFn = { awaitCancellation() })
                r.start(jumpNow = true)
                runCurrent()
                advanceTimeBy(1499)
                r.session.busy shouldBe true
                advanceTimeBy(2)
                runCurrent()
                r.log shouldBe listOf("cover:true", "ready", "jump:fwd", "cover:false", "settled:false")
                r.session.busy shouldBe false
                r.session.curlFinished(true)
                r.log.count { it.startsWith("settled") } shouldBe 1
            }
        }

        test("throwing jump ends the turn once") {
            runTest {
                val r = Rig(this, jumpFn = { error("boom") })
                r.start(jumpNow = true)
                runCurrent()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:false")
                r.session.busy shouldBe false
                r.session.curlFinished(true)
                r.log.count { it.startsWith("settled") } shouldBe 1
            }
        }

        test("throwing awaitPainted ends the turn once") {
            runTest {
                val r = Rig(this, paint = { error("boom") })
                r.start(jumpNow = true)
                runCurrent()
                r.log.takeLast(2) shouldBe listOf("cover:false", "settled:true")
                r.session.busy shouldBe false
            }
        }
    })
