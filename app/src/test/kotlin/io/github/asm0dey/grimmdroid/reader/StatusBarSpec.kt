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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class StatusBarSpec :
    FunSpec({
        val inputs =
            StatusInputs(
                totalProgression = 0.61,
                position = 124,
                positionCount = 410,
                chapterPositionsLeft = 7,
                chapterTitle = "The Long Night",
                nowMillis = 1_700_000_000_000, // 2023-11-14 22:13:20 UTC
                batteryPercent = 83,
                msPerPosition = 6000.0,
                chapterTickFractions = listOf(0.25, 0.5),
            )

        fun text(
            item: StatusItem,
            i: StatusInputs = inputs,
        ) = statusText(item, i, Locale.US)

        test("percentage read") { text(StatusItem.PERCENT) shouldBe "61%" }
        test("position of positions") { text(StatusItem.PAGE_OF) shouldBe "124 / 410" }
        test("positions left in the chapter") { text(StatusItem.PAGES_LEFT_IN_CHAPTER) shouldBe "7 left in chapter" }
        test("chapter title") { text(StatusItem.CHAPTER_TITLE) shouldBe "The Long Night" }
        test("battery") { text(StatusItem.BATTERY) shouldBe "83%" }
        test("the progress bar is drawn, not written") { text(StatusItem.PROGRESS_BAR) shouldBe null }

        test("clock in the locale's short time") {
            val zone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try {
                text(StatusItem.CLOCK)!! shouldMatch Regex("10:13\\hPM")
            } finally {
                TimeZone.setDefault(zone)
            }
        }

        test("time left in the book: 100 positions at 6 s each") {
            text(StatusItem.TIME_LEFT_BOOK, inputs.copy(position = 310, positionCount = 410)) shouldBe "10 min left"
        }
        test("time left in the chapter: 7 positions at 6 s each round to 1 min") {
            text(StatusItem.TIME_LEFT_CHAPTER) shouldBe "1 min left"
        }
        test("hours once past an hour") {
            text(StatusItem.TIME_LEFT_BOOK, inputs.copy(position = 1, positionCount = 1_301)) shouldBe "2 h 10 min left"
        }

        test("missing inputs write nothing") {
            val none =
                inputs.copy(
                    position = null,
                    positionCount = null,
                    chapterPositionsLeft = null,
                    chapterTitle = null,
                    batteryPercent = null,
                    msPerPosition = null,
                )
            StatusItem.entries.filter { text(it, none) != null } shouldBe listOf(StatusItem.PERCENT, StatusItem.CLOCK)
        }

        test("positions per chapter give the chapter's end and the ticks") {
            val i = statusInputs(totalProgression = 0.3, position = 4, resourceSizes = listOf(2, 5, 3), chapterTitle = null, nowMillis = 0)
            i.positionCount shouldBe 10
            i.chapterPositionsLeft shouldBe 3 // chapter 2 holds positions 3..7
            i.chapterTickFractions shouldBe listOf(0.2, 0.7)
        }

        test("no positions known: no counts, no ticks") {
            val i = statusInputs(totalProgression = 0.3, position = null, resourceSizes = emptyList(), chapterTitle = null, nowMillis = 0)
            i.positionCount shouldBe null
            i.chapterPositionsLeft shouldBe null
            i.chapterTickFractions shouldBe emptyList()
        }

        context("reading pace") {
            test("null until three samples, then their average") {
                val clock = TestTimeSource()
                val pace = ReadingPace(clock)
                pace.onPosition(1)
                clock += 4.seconds
                pace.onPosition(2)
                clock += 6.seconds
                pace.onPosition(3)
                pace.msPerPosition shouldBe null
                clock += 8.seconds
                pace.onPosition(4)
                pace.msPerPosition shouldBe 6000.0
            }

            test("a pause over 5 min is not reading time") {
                val clock = TestTimeSource()
                val pace = ReadingPace(clock)
                pace.onPosition(1)
                listOf(6.seconds, 10.minutes, 6.seconds, 6.seconds).forEachIndexed { i, gap ->
                    clock += gap
                    pace.onPosition(2 + i)
                }
                pace.msPerPosition shouldBe 6000.0
            }

            test("a move of several positions counts per position; jumps and the same position don't count") {
                val clock = TestTimeSource()
                val pace = ReadingPace(clock)
                pace.onPosition(10)
                clock += 12.seconds
                pace.onPosition(12) // a page of two positions: 6 s each
                clock += 1.seconds
                pace.onPosition(12) // the same position: no sample
                clock += 2.seconds
                pace.onPosition(300) // a jump
                clock += 500.milliseconds
                pace.onPosition(5) // a jump back
                clock += 6.seconds
                pace.onPosition(6)
                clock += 6.seconds
                pace.onPosition(7)
                pace.msPerPosition shouldBe 6000.0
            }
        }
    })
