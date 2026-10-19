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
package io.github.asm0dey.grimmdroid.ui.library

import androidx.compose.ui.graphics.luminance
import io.github.asm0dey.grimmdroid.api.authorizeCover
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import okhttp3.Request

class CoversSpec :
    FunSpec({
        test("cover URL is built relative to the stored base URL, sub-path included") {
            coverUrl("https://h.example/grim/", 5, 99) shouldBe "https://h.example/grim/api/v1/media/book/5/thumbnail?t=99"
            coverUrl("https://h.example", 5, null) shouldBe "https://h.example/api/v1/media/book/5/thumbnail"
        }

        test("bearer goes only to the account's own scheme, host and port") {
            val base = "https://h.example:8443/grim"

            suspend fun auth(url: String) = authorizeCover(Request.Builder().url(url).build(), base) { "tok" }.header("Authorization")
            auth("https://h.example:8443/grim/api/v1/media/book/1/thumbnail") shouldBe "Bearer tok"
            auth("https://other.example:8443/x").shouldBeNull()
            auth("https://h.example/x").shouldBeNull()
            auth("http://h.example:8443/x").shouldBeNull()
            authorizeCover(Request.Builder().url("https://h.example:8443/x").build(), null) { "tok" }.header("Authorization").shouldBeNull()
            authorizeCover(Request.Builder().url("https://h.example:8443/x").build(), base) { null }.header("Authorization").shouldBeNull()
        }

        test("every placeholder tone keeps white text at 4.5:1 or more") {
            PlaceholderTones.forEach { (1.05 / (it.luminance() + 0.05)) shouldBeGreaterThanOrEqual 4.5 }
            placeholderColor("Moby Dick") shouldBe placeholderColor("Moby Dick")
        }
    })
