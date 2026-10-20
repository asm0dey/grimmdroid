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

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Color as ReadiumColor

class ReaderThemesSpec :
    FunSpec({
        test("every Grimmory web reader theme, in Grimmory's order") {
            readerThemes.map { it.name } shouldBe
                listOf(
                    "default",
                    "gray",
                    "sepia",
                    "crimson",
                    "meadow",
                    "rosewood",
                    "azure",
                    "dawnlight",
                    "ember",
                    "aurora",
                    "ocean",
                    "mist",
                    "amoled",
                )
        }

        test("Sepia light uses Grimmory's exact colours") {
            readerThemes.single { it.name == "sepia" }.light shouldBe
                Palette(fg = Color(0xFF5B4636), bg = Color(0xFFF1E8D0), link = Color(0xFF008B8B))
        }

        test("following the system in dark mode picks the theme's dark palette") {
            val prefs = ReaderSettings(themeName = "sepia", dark = null).toEpubPreferences(systemDark = true)
            prefs.backgroundColor shouldBe ReadiumColor(0xFF342E25.toInt())
            prefs.textColor shouldBe ReadiumColor(0xFFFFD595.toInt())
            prefs.theme shouldBe null
        }

        test("an explicit light choice wins over a dark system") {
            ReaderSettings(themeName = "sepia", dark = false).toEpubPreferences(systemDark = true).backgroundColor shouldBe
                ReadiumColor(0xFFF1E8D0.toInt())
        }

        test("an unknown theme name falls back to Default") {
            ReaderSettings(themeName = "gone").toEpubPreferences(systemDark = false).backgroundColor shouldBe
                ReadiumColor(0xFFFFFFFF.toInt())
        }

        test("the chosen font family reaches the navigator; none keeps the publisher's") {
            ReaderSettings(fontFamily = "OpenDyslexic").toEpubPreferences(false).fontFamily shouldBe FontFamily.OPEN_DYSLEXIC
            ReaderSettings().toEpubPreferences(false).fontFamily shouldBe null
        }
    })
