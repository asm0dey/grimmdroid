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
package io.github.asm0dey.grimmdroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

private fun contrast(
    a: Color,
    b: Color,
): Double {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

class ThemeContrastSpec :
    StringSpec({
        "light fallback keeps the paper background" {
            FallbackLight.background shouldBe Color(0xFFFFFBEB)
        }

        listOf("light" to FallbackLight, "dark" to FallbackDark).forEach { (name, s) ->
            "$name fallback text meets 4.5:1" {
                contrast(s.onBackground, s.background) shouldBeGreaterThanOrEqual 4.5
                contrast(s.onSurface, s.surface) shouldBeGreaterThanOrEqual 4.5
                contrast(s.onSurfaceVariant, s.surface) shouldBeGreaterThanOrEqual 4.5
                contrast(s.error, s.surfaceContainerLow) shouldBeGreaterThanOrEqual 4.5
                contrast(s.onErrorContainer, s.errorContainer) shouldBeGreaterThanOrEqual 4.5
                contrast(s.onSecondaryContainer, s.secondaryContainer) shouldBeGreaterThanOrEqual 4.5
                contrast(s.onTertiaryContainer, s.tertiaryContainer) shouldBeGreaterThanOrEqual 4.5
            }
        }
    })
