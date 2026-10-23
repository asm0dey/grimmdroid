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
package io.github.asm0dey.grimmdroid.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.nio.file.Files

class SettingsStoreSpec :
    FunSpec({
        val scopes = mutableListOf<CoroutineScope>()

        fun dataStore() =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO).also { scopes += it }) {
                Files.createTempDirectory("ds").resolve("settings.preferences_pb").toFile()
            }

        fun store() = SettingsStore(dataStore())

        afterSpec { scopes.forEach { it.cancel() } }

        test("start screen defaults to Home and round-trips") {
            val s = store()
            s.startScreen.first() shouldBe StartScreen.HOME
            s.setStartScreen(StartScreen.LAST_PLACE)
            s.startScreen.first() shouldBe StartScreen.LAST_PLACE
        }

        test("opening a book marks it open; leaving the reader keeps the book but closes it") {
            val s = store()
            s.lastBook.first() shouldBe null
            s.setLastBook(1, 7, "/b.epub")
            s.lastBook.first() shouldBe LastBook(1, 7, "/b.epub", open = true)
            s.setReaderClosed()
            s.lastBook.first() shouldBe LastBook(1, 7, "/b.epub", open = false)
        }

        test("fresh install migrates to SLIDE") {
            val s = store()
            s.migratePageTurn()
            s.pageTurn.first() shouldBe PageTurn.SLIDE
        }

        test("existing user with the switch on migrates to SLIDE") {
            val ds = dataStore()
            val s = SettingsStore(ds)
            ds.edit { it[LEGACY_ANIMATE_PAGE_TURNS] = true }
            s.setCurrentAccountId(1)
            s.migratePageTurn()
            s.pageTurn.first() shouldBe PageTurn.SLIDE
        }

        test("existing user who never touched the switch migrates to NONE") {
            val s = store()
            s.setCurrentAccountId(1)
            s.migratePageTurn()
            s.pageTurn.first() shouldBe PageTurn.NONE
        }

        test("migration runs once") {
            val s = store()
            s.migratePageTurn()
            s.setPageTurn(PageTurn.CURL)
            s.migratePageTurn()
            s.pageTurn.first() shouldBe PageTurn.CURL
        }

        test("turn speed defaults to 450 and clamps") {
            val s = store()
            s.turnSpeedMs.first() shouldBe 450
            s.setTurnSpeedMs(50)
            s.turnSpeedMs.first() shouldBe 200
            s.setTurnSpeedMs(5000)
            s.turnSpeedMs.first() shouldBe 1000
        }
    })
