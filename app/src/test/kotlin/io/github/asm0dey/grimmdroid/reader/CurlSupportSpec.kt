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

class CurlSupportSpec :
    FunSpec({
        test("a single column of paginated left-to-right horizontal text curls") {
            curlSupported(scroll = false, rtl = false, verticalText = false, columns = 1) shouldBe true
        }

        test("scroll mode does not curl") {
            curlSupported(scroll = true, rtl = false, verticalText = false, columns = 1) shouldBe false
        }

        test("a right-to-left book does not curl") {
            curlSupported(scroll = false, rtl = true, verticalText = false, columns = 1) shouldBe false
        }

        test("vertical text does not curl") {
            curlSupported(scroll = false, rtl = false, verticalText = true, columns = 1) shouldBe false
        }

        test("two columns do not curl") {
            curlSupported(scroll = false, rtl = false, verticalText = false, columns = 2) shouldBe false
        }

        test("unknown columns do not curl") {
            curlSupported(scroll = false, rtl = false, verticalText = false, columns = 0) shouldBe false
        }

        test("no script result means unknown columns") {
            parseColumns(null) shouldBe 0
        }

        test("the script's column count, as WebView's JSON string or bare") {
            parseColumns("\"1\"") shouldBe 1
            parseColumns("\"2\"") shouldBe 2
            parseColumns("2") shouldBe 2
        }

        test("anything unparseable means unknown columns") {
            parseColumns("\"auto\"") shouldBe 0
            parseColumns("null") shouldBe 0
            parseColumns("\"0\"") shouldBe 0
            parseColumns("garbage") shouldBe 0
        }
    })
