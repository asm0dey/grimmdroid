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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.readium.r2.navigator.epub.EpubPreferences
import java.nio.file.Files

class ReaderSettingsStoreSpec :
    FunSpec({
        val scopes = mutableListOf<CoroutineScope>()

        fun dataStore() =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO).also { scopes += it }) {
                Files.createTempDirectory("ds").resolve("reader.preferences_pb").toFile()
            }

        afterSpec { scopes.forEach { it.cancel() } }

        test("nothing stored yet gives the defaults") {
            ReaderSettingsStore(dataStore()).settings.first() shouldBe ReaderSettings()
        }

        test("round-trips every field") {
            val store = ReaderSettingsStore(dataStore())
            val changed =
                ReaderSettings(
                    epub = EpubPreferences(fontSize = 1.4, scroll = true),
                    themeName = "sepia",
                    dark = true,
                    footnotePopup = false,
                    statusItems = listOf(StatusItem.CLOCK, StatusItem.BATTERY),
                    statusBarVisible = false,
                    keepSystemStatusBar = true,
                    fontFamily = "serif",
                )
            store.update { changed }
            store.settings.first() shouldBe changed
        }

        test("an emptied status bar stays empty") {
            val store = ReaderSettingsStore(dataStore())
            store.update { it.copy(statusItems = emptyList()) }
            store.settings.first().statusItems shouldBe emptyList()
        }

        test("going back to follow-system clears the stored dark choice") {
            val store = ReaderSettingsStore(dataStore())
            store.update { it.copy(dark = true) }
            store.update { it.copy(dark = null) }
            store.settings.first().dark shouldBe null
        }

        test("corrupted or old stored values fall back to the defaults") {
            val ds = dataStore()
            ds.edit {
                it[stringPreferencesKey("epub")] = "{not json"
                it[stringPreferencesKey("status_items")] = "CLOCK,NO_SUCH_ITEM"
            }
            val settings = ReaderSettingsStore(ds).settings.first()
            settings.epub shouldBe EpubPreferences()
            settings.statusItems shouldBe listOf(StatusItem.CLOCK)
        }
    })
