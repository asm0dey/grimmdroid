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
package io.github.asm0dey.grimmdroid.reader.cfi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CfiSpec :
    FunSpec({
        test("parses spine and path without offset") {
            parseCfi("epubcfi(/6/4!/4/2)") shouldBe Cfi(4, listOf(4, 2), null)
        }
        test("parses offset") {
            parseCfi("epubcfi(/6/12!/4/2:10)") shouldBe Cfi(12, listOf(4, 2), 10)
        }
        test("parses deep path with zero offset") {
            parseCfi("epubcfi(/6/2!/4/2/2/1:0)") shouldBe Cfi(2, listOf(4, 2, 2, 1), 0)
        }
        test("range uses parent plus start") {
            parseCfi("epubcfi(/6/2!/4/2,/2/1:0,/4/1:10)") shouldBe Cfi(2, listOf(4, 2, 2, 1), 0)
        }
        test("foliate-js range") {
            parseCfi("epubcfi(/6/8!/4/2,/2/1:0,/40/1:120)") shouldBe Cfi(8, listOf(4, 2, 2, 1), 0)
        }
        test("ignores id assertions") {
            parseCfi("epubcfi(/6/4[chap01]!/4[body01]/10[para05]:3)") shouldBe Cfi(4, listOf(4, 10), 3)
        }
        test("ignores spatial and temporal suffixes") {
            parseCfi("epubcfi(/6/4!/4/2:3@10:20)") shouldBe Cfi(4, listOf(4, 2), 3)
            parseCfi("epubcfi(/6/4!/4/2~12.5)") shouldBe Cfi(4, listOf(4, 2), null)
        }
        test("spine-only has empty path") {
            parseCfi("epubcfi(/6/10)") shouldBe Cfi(10, emptyList(), null)
        }
        test("garbage is null") {
            parseCfi("") shouldBe null
            parseCfi("epubcfi(/6/x)") shouldBe null
            parseCfi("/6/4") shouldBe null
            parseCfi("epubcfi(/4/2!/4)") shouldBe null
            parseCfi("epubcfi(/6/99999999999)") shouldBe null
            parseCfi("epubcfi(/6/4!/4:99999999999)") shouldBe null
        }
        test("spineIndex") {
            Cfi(4, emptyList(), null).spineIndex shouldBe 1
        }
        test("round trips") {
            listOf(
                "epubcfi(/6/4!/4/2)",
                "epubcfi(/6/12!/4/2:10)",
                "epubcfi(/6/2!/4/2/2/1:0)",
                "epubcfi(/6/10)",
            ).forEach { parseCfi(it)!!.format() shouldBe it }
            parseCfi("epubcfi(/6/4[chap01]!/4[body01]/10[para05]:3)")!!.format() shouldBe "epubcfi(/6/4!/4/10:3)"
        }
    })
