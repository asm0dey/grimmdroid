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
package io.github.asm0dey.grimmdroid.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class FileNamesSpec :
    FunSpec({
        test("content-disposition filename star is decoded and sanitised") {
            val raw = parseContentDisposition("""attachment; filename="Moby_Dick.epub"; filename*=UTF-8''Moby%E2%80%93Dick%2F..%2Fx.epub""")
            safeFileName(raw!!, "book-1.epub") shouldBe "Moby–Dick__x.epub"
        }

        test("plain quoted filename is used without filename star") {
            parseContentDisposition("""attachment; filename="Dune.pdf"""") shouldBe "Dune.pdf"
            parseContentDisposition(null) shouldBe null
            parseContentDisposition("attachment") shouldBe null
        }

        test("blank or dot-only name falls back") {
            safeFileName("", "book-12.epub") shouldBe "book-12.epub"
            safeFileName("..", "book-12.epub") shouldBe "book-12.epub"
            safeFileName("...", "book-12.epub") shouldBe "book-12.epub"
        }

        test("the fallback is sanitised too, with a fixed last resort") {
            safeFileName("", "../../../x.epub") shouldBe "___x.epub"
            safeFileName("..", "..") shouldBe "book"
        }

        test("control chars and reserved chars are removed") {
            safeFileName("a\u0001b:c*d?e\"f<g>h|i\\j.epub", "x") shouldBe "abcdefghi_j.epub"
        }

        test("a 300-char name keeps .epub within 120 UTF-8 bytes") {
            val name = safeFileName("ж".repeat(300) + ".epub", "x")
            name shouldEndWith ".epub"
            name.encodeToByteArray().size shouldBe 119 // 57 two-byte chars + 5; never splits a character
        }
    })
