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
package com.irurueta.android.gl.curl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.sign

class CurlGeometrySpec :
    FunSpec({
        val left = -1f
        val right = 1f

        test("forward fold is halfway between edge and finger") {
            CurlGeometry.fold(-0.2f, 0f, 1f, 0f, 0.0, left).x shouldBe (0.4f plusOrMinus 1e-4f)
        }

        test("backward fold mirrors forward") {
            CurlGeometry.fold(0.2f, 0f, right, 0f, 0.0, left).x shouldBe (0.6f plusOrMinus 1e-4f)
        }

        test("fold leans with a diagonal drag") {
            val fold = CurlGeometry.fold(0f, -0.6f, 1f, -0.8f, 0.3, left)
            fold.dirY shouldNotBe 0f
            fold.dirY.sign shouldBe (-0.6f - -0.8f).sign
        }

        test("radius never pushes the cylinder past the left edge") {
            for (x in listOf(-0.95f, -0.8f, -0.5f, 0.5f, 0.9f)) {
                val fold = CurlGeometry.fold(x, 0f, 1f, 0f, 0.5, left)
                fold.radius shouldBeLessThanOrEqual (fold.x - left).toDouble()
            }
        }

        test("release past the middle completes forward, before it cancels") {
            CurlGeometry.release(-0.1f, true, left, right).completed shouldBe true
            CurlGeometry.release(0.1f, true, left, right).completed shouldBe false
            CurlGeometry.release(-0.1f, true, left, right).targetX shouldBe -3f
            CurlGeometry.release(0.1f, true, left, right).targetX shouldBe right
        }

        test("release past the middle completes backward, before it cancels") {
            CurlGeometry.release(0.1f, false, left, right).completed shouldBe true
            CurlGeometry.release(-0.1f, false, left, right).completed shouldBe false
            CurlGeometry.release(0.1f, false, left, right).targetX shouldBe right
            CurlGeometry.release(-0.1f, false, left, right).targetX shouldBe -3f
        }

        test("complete target puts the fold on the left edge") {
            val target = CurlGeometry.release(-0.5f, true, left, right).targetX
            CurlGeometry.fold(target, 0.2f, 1f, 0.2f, 0.3, left).x shouldBe (left plusOrMinus 1e-3f)
        }
        test("finishProgress starts at 0 and ends at 1") {
            CurlGeometry.finishProgress(0, 150) shouldBe 0f
            CurlGeometry.finishProgress(150_000_000, 150) shouldBe 1f
        }
        test("finishProgress is halfway at half the duration") {
            CurlGeometry.finishProgress(75_000_000, 150) shouldBe (0.5f plusOrMinus 1e-4f)
        }
        test("finishProgress is monotonic") {
            val values = (0..10).map { CurlGeometry.finishProgress(it * 15_000_000L, 150) }
            values.zipWithNext().forEach { (a, b) -> (b >= a) shouldBe true }
        }
        test("finishProgress with no duration is complete") {
            CurlGeometry.finishProgress(5, 0) shouldBe 1f
        }
        test("finishProgress clamps past the end") {
            CurlGeometry.finishProgress(300_000_000, 150) shouldBe 1f
        }
    })
