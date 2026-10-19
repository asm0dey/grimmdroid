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

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.asm0dey.grimmdroid.settings.ThemeMode

// Material Color Utilities tonal-spot schemes (spec 2021, as Material Theme Builder outputs) for seed #92400E;
// light background overridden to paper #FFFBEB.
internal val FallbackLight =
    lightColorScheme(
        primary = Color(0xFF8D4E2C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBCB),
        onPrimaryContainer = Color(0xFF703717),
        inversePrimary = Color(0xFFFFB693),
        secondary = Color(0xFF765749),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBCB),
        onSecondaryContainer = Color(0xFF5C4033),
        tertiary = Color(0xFF655F31),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFEDE4AA),
        onTertiaryContainer = Color(0xFF4D481C),
        background = Color(0xFFFFFBEB),
        onBackground = Color(0xFF221A16),
        surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF221A16),
        surfaceVariant = Color(0xFFF4DED5),
        onSurfaceVariant = Color(0xFF52443D),
        surfaceTint = Color(0xFF8D4E2C),
        inverseSurface = Color(0xFF382E2A),
        inverseOnSurface = Color(0xFFFFEDE6),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF85736C),
        outlineVariant = Color(0xFFD7C2B9),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFF8F6),
        surfaceContainer = Color(0xFFFCEAE3),
        surfaceContainerHigh = Color(0xFFF6E5DE),
        surfaceContainerHighest = Color(0xFFF0DFD8),
        surfaceContainerLow = Color(0xFFFFF1EB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE8D7D0),
    )

internal val FallbackDark =
    darkColorScheme(
        primary = Color(0xFFFFB693),
        onPrimary = Color(0xFF542104),
        primaryContainer = Color(0xFF703717),
        onPrimaryContainer = Color(0xFFFFDBCB),
        inversePrimary = Color(0xFF8D4E2C),
        secondary = Color(0xFFE6BEAC),
        onSecondary = Color(0xFF432A1E),
        secondaryContainer = Color(0xFF5C4033),
        onSecondaryContainer = Color(0xFFFFDBCB),
        tertiary = Color(0xFFD0C890),
        onTertiary = Color(0xFF363107),
        tertiaryContainer = Color(0xFF4D481C),
        onTertiaryContainer = Color(0xFFEDE4AA),
        background = Color(0xFF1A120E),
        onBackground = Color(0xFFF0DFD8),
        surface = Color(0xFF1A120E),
        onSurface = Color(0xFFF0DFD8),
        surfaceVariant = Color(0xFF52443D),
        onSurfaceVariant = Color(0xFFD7C2B9),
        surfaceTint = Color(0xFFFFB693),
        inverseSurface = Color(0xFFF0DFD8),
        inverseOnSurface = Color(0xFF382E2A),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFFA08D85),
        outlineVariant = Color(0xFF52443D),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF423732),
        surfaceContainer = Color(0xFF271E1A),
        surfaceContainerHigh = Color(0xFF322824),
        surfaceContainerHighest = Color(0xFF3D332E),
        surfaceContainerLow = Color(0xFF221A16),
        surfaceContainerLowest = Color(0xFF140C09),
        surfaceDim = Color(0xFF1A120E),
    )

@Composable
fun ThemeMode.isDark(): Boolean =
    when (this) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

@Composable
fun GrimmdroidTheme(
    mode: ThemeMode,
    dynamic: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark()
    val scheme =
        when {
            dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            dark -> {
                FallbackDark
            }

            else -> {
                FallbackLight
            }
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
