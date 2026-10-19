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

import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.math.roundToInt

/** Readium's textZoom ignores Android's font scale, so the default reads small; boost it. */
const val DEFAULT_FONT_SCALE_BOOST = 1.25

/** Default reader font size (1.0 = 100%) for the system [fontScale], on 0.05 steps so the stepper stays tidy. */
fun defaultFontSize(fontScale: Float): Double = (fontScale * DEFAULT_FONT_SCALE_BOOST * 20).roundToInt() / 20.0

fun readerNavigatorConfiguration(fontScale: Float) =
    EpubNavigatorFactory.Configuration(defaults = EpubDefaults(fontSize = defaultFontSize(fontScale)))

/** The navigator fragment's configuration: each of [fonts] declared as a family, loaded through [FontServingContainer]. */
@OptIn(ExperimentalReadiumApi::class)
fun readerFragmentConfiguration(fonts: List<ReaderFont>) =
    EpubNavigatorFragment.Configuration {
        fonts.forEach { font ->
            addFontFamilyDeclaration(FontFamily(font.family)) {
                addFontFace {
                    addSource(fontUrl(font.file))
                    setFontStyle(FontStyle.NORMAL)
                    setFontWeight(FontWeight.NORMAL)
                }
            }
        }
    }
