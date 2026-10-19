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
import androidx.compose.ui.graphics.toArgb
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Color as ReadiumColor

data class Palette(
    val fg: Color,
    val bg: Color,
    val link: Color,
)

data class ReaderTheme(
    val name: String,
    val label: String,
    val light: Palette,
    val dark: Palette,
)

private fun theme(
    name: String,
    label: String,
    light: Triple<Long, Long, Long>,
    dark: Triple<Long, Long, Long>,
) = ReaderTheme(name, label, light.palette(), dark.palette())

private fun Triple<Long, Long, Long>.palette() = Palette(Color(first), Color(second), Color(third))

/** Grimmory's web reader themes (frontend/.../ebook-reader/state/themes.constant.ts), verbatim: fg, bg, link. */
val readerThemes: List<ReaderTheme> =
    listOf(
        theme("default", "Default", Triple(0xFF000000, 0xFFFFFFFF, 0xFF0066CC), Triple(0xFFE0E0E0, 0xFF222222, 0xFF77BBEE)),
        theme("gray", "Gray", Triple(0xFF222222, 0xFFE0E0E0, 0xFF4488CC), Triple(0xFFC6C6C6, 0xFF444444, 0xFF88CCEE)),
        theme("sepia", "Sepia", Triple(0xFF5B4636, 0xFFF1E8D0, 0xFF008B8B), Triple(0xFFFFD595, 0xFF342E25, 0xFF48D1CC)),
        theme("crimson", "Crimson", Triple(0xFF212529, 0xFFFFFFFF, 0xFFDD0031), Triple(0xFFDEE2E6, 0xFF343A40, 0xFFFF4081)),
        theme("meadow", "Meadow", Triple(0xFF232C16, 0xFFD7DBBD, 0xFF177B4D), Triple(0xFFD8DEBA, 0xFF333627, 0xFFA6D608)),
        theme("rosewood", "Rosewood", Triple(0xFF4E1609, 0xFFF0D1D5, 0xFFDE3838), Triple(0xFFE5C4C8, 0xFF462F32, 0xFFFF646E)),
        theme("azure", "Azure", Triple(0xFF262D48, 0xFFCEDEF5, 0xFF2D53E5), Triple(0xFFBABEE1, 0xFF282E47, 0xFFFF646E)),
        theme("dawnlight", "Dawnlight", Triple(0xFF586E75, 0xFFFDF6E3, 0xFF268BD2), Triple(0xFF93A1A1, 0xFF002B36, 0xFF268BD2)),
        theme("ember", "Ember", Triple(0xFF3C3836, 0xFFFBF1C7, 0xFF076678), Triple(0xFFEBDBB2, 0xFF282828, 0xFF83A598)),
        theme("aurora", "Aurora", Triple(0xFF2E3440, 0xFFECEFF4, 0xFF5E81AC), Triple(0xFFD8DEE9, 0xFF2E3440, 0xFF88C0D0)),
        theme("ocean", "Ocean", Triple(0xFF0A4D4D, 0xFFE0F7FA, 0xFF00838F), Triple(0xFFB2DFDB, 0xFF263238, 0xFF4DD0E1)),
        theme("mist", "Mist", Triple(0xFF4A148C, 0xFFF3E5F5, 0xFF7B1FA2), Triple(0xFFC7B6DD, 0xFF3A3150, 0xFFB39DDB)),
        theme("amoled", "AMOLED", Triple(0xFF000000, 0xFFFFFFFF, 0xFF0066CC), Triple(0xFFFFFFFF, 0xFF000000, 0xFF77BBEE)),
    )

/** Whether the page is dark: the user's choice, else the system's. */
fun ReaderSettings.isDark(systemDark: Boolean): Boolean = dark ?: systemDark

/** The palette in use; a theme name no longer known falls back to Default. */
fun ReaderSettings.palette(systemDark: Boolean): Palette {
    val theme = readerThemes.firstOrNull { it.name == themeName } ?: readerThemes.first()
    return if (isDark(systemDark)) theme.dark else theme.light
}

/** What the navigator gets: the stored Readium preferences plus our colours and font. */
fun ReaderSettings.toEpubPreferences(systemDark: Boolean): EpubPreferences {
    val palette = palette(systemDark)
    // Readium's own Theme stays unset so it draws our colours instead of its light/dark/sepia.
    return epub.copy(
        backgroundColor = ReadiumColor(palette.bg.toArgb()),
        textColor = ReadiumColor(palette.fg.toArgb()),
        theme = null,
        fontFamily = fontFamily?.let(::FontFamily),
    )
}

/** The font families the settings offer; null is the publisher's own. Task 12 adds custom fonts here. */
fun readerFontFamilies(): List<FontFamily?> =
    listOf(
        null,
        FontFamily.SERIF,
        FontFamily.SANS_SERIF,
        FontFamily.CURSIVE,
        FontFamily.MONOSPACE,
        FontFamily.OPEN_DYSLEXIC,
        FontFamily.ACCESSIBLE_DFA,
        FontFamily.IA_WRITER_DUOSPACE,
    )
