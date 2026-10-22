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
package io.github.asm0dey.grimmdroid.ui.nav

import io.github.asm0dey.grimmdroid.settings.LastBook
import io.github.asm0dey.grimmdroid.settings.StartScreen
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StartScreenSpec :
    FunSpec({
        val open = LastBook(accountId = 1, bookId = 7, file = "/b.epub", open = true)
        val closed = open.copy(open = false)

        fun launch(
            choice: StartScreen,
            book: LastBook? = open,
            tab: String? = "SHELVES",
            account: Long? = 1,
            exists: Boolean = true,
        ) = launchFor(choice, tab, book, account) { exists }

        test("home and library ignore the last place") {
            launch(StartScreen.HOME) shouldBe (Tab.HOME to null)
            launch(StartScreen.LIBRARY) shouldBe (Tab.LIBRARY to null)
        }

        test("last book reopens the book even after it was left") {
            launch(StartScreen.LAST_BOOK, closed) shouldBe (Tab.SHELVES to closed)
        }

        test("last place reopens only a book still open") {
            launch(StartScreen.LAST_PLACE, open) shouldBe (Tab.SHELVES to open)
            launch(StartScreen.LAST_PLACE, closed) shouldBe (Tab.SHELVES to null)
        }

        test("a deleted file or another account's book falls back to the tab") {
            launch(StartScreen.LAST_BOOK, exists = false) shouldBe (Tab.SHELVES to null)
            launch(StartScreen.LAST_BOOK, account = 2) shouldBe (Tab.SHELVES to null)
        }

        test("nothing remembered yet opens Home") {
            launch(StartScreen.LAST_PLACE, book = null, tab = null) shouldBe (Tab.HOME to null)
        }
    })
