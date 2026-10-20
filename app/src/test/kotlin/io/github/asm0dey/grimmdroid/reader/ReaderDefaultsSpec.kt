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

class ReaderDefaultsSpec :
    FunSpec({
        test("default font size is the system font scale boosted by 25%") {
            defaultFontSize(1.0f) shouldBe 1.25
            defaultFontSize(0.85f) shouldBe 1.05
        }

        test("a boosted scale is rounded to the 0.05 stepper grid") {
            defaultFontSize(1.3f) shouldBe 1.6
        }

        test("the navigator configuration carries the default") {
            readerNavigatorConfiguration(1.0f).defaults.fontSize shouldBe 1.25
        }
    })
