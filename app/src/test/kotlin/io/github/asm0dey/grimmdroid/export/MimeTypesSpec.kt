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
package io.github.asm0dey.grimmdroid.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MimeTypesSpec :
    FunSpec({
        test("known extensions map case-insensitively") {
            mimeFor("A.EPUB") shouldBe "application/epub+zip"
            mimeFor("a.pdf") shouldBe "application/pdf"
            mimeFor("a.cbz") shouldBe "application/vnd.comicbook+zip"
            mimeFor("a.cbr") shouldBe "application/vnd.comicbook-rar"
            mimeFor("a.mobi") shouldBe "application/x-mobipocket-ebook"
            mimeFor("a.azw3") shouldBe "application/vnd.amazon.ebook"
            mimeFor("a.fb2") shouldBe "application/x-fictionbook+xml"
        }

        test("unknown or missing extension is octet-stream") {
            mimeFor("a.xyz") shouldBe "application/octet-stream"
            mimeFor("noext") shouldBe "application/octet-stream"
        }

        test("name collision gets numbered suffix") {
            uniqueName(setOf("Moby-Dick.epub", "Moby-Dick (1).epub"), "Moby-Dick.epub") shouldBe "Moby-Dick (2).epub"
            uniqueName(setOf("x.epub"), "Moby-Dick.epub") shouldBe "Moby-Dick.epub"
            uniqueName(setOf("README"), "README") shouldBe "README (1)"
        }
    })
