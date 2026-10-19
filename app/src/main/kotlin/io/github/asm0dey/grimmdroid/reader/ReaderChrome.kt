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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

/** The reader palette as a Material colour scheme, so every bar, sheet and dialog of the reader draws in it, not the app theme. */
private fun Palette.colorScheme(dark: Boolean): ColorScheme {
    val muted = fg.copy(alpha = 0.12f).compositeOver(bg)
    val outline = fg.copy(alpha = 0.5f).compositeOver(bg)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = link,
        onPrimary = bg,
        primaryContainer = muted,
        onPrimaryContainer = fg,
        inversePrimary = bg,
        secondary = link,
        onSecondary = bg,
        secondaryContainer = muted,
        onSecondaryContainer = fg,
        tertiary = link,
        onTertiary = bg,
        tertiaryContainer = muted,
        onTertiaryContainer = fg,
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = muted,
        onSurfaceVariant = fg,
        surfaceTint = fg,
        inverseSurface = fg,
        inverseOnSurface = bg,
        outline = outline,
        outlineVariant = muted,
        surfaceBright = bg,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = bg,
        surfaceContainer = bg,
        surfaceContainerHigh = bg,
        surfaceContainerHighest = muted,
    )
}

@Composable
fun ReaderColors(
    palette: Palette,
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = remember(palette, dark) { palette.colorScheme(dark) }, content = content)
}

/** A sheet's own window sets the system bar icons: match them to the reader palette, not the app theme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun readerSheetProperties(): ModalBottomSheetProperties {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return ModalBottomSheetProperties(isAppearanceLightStatusBars = light, isAppearanceLightNavigationBars = light)
}

/** One TOC row: [depth] levels of nesting. */
data class TocEntry(
    val link: Link,
    val depth: Int,
)

fun List<Link>.flattenToc(depth: Int = 0): List<TocEntry> = flatMap { listOf(TocEntry(it, depth)) + it.children.flattenToc(depth + 1) }

/**
 * The reader's overlay: top bar and bottom progress while [visible], the jump bar whenever there is an offer.
 * Draws nothing over the page outside its bars, so taps there reach the navigator. Call it inside [ReaderColors].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChrome(
    visible: Boolean,
    title: String,
    locator: Locator?,
    toc: List<TocEntry>,
    jump: JumpOffer?,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSyncNow: () -> Unit,
    onMakeCurrent: () -> Unit,
    onJump: () -> Unit,
    onDismissJump: () -> Unit,
    onSeek: (Double) -> Unit,
    onTocEntry: (Link) -> Unit,
) {
    var tocOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter), slideInVertically { -it }, slideOutVertically { -it }) {
            TopBar(title, onBack, { tocOpen = true }, onSettings, onSyncNow, onMakeCurrent)
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (jump != null) JumpBar(jump, onJump, onDismissJump)
            AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                BottomBar(locator, onSeek)
            }
        }
    }
    if (tocOpen) {
        ModalBottomSheet(onDismissRequest = { tocOpen = false }, properties = readerSheetProperties()) {
            Text(stringResource(R.string.table_of_contents), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(toc) { entry ->
                    Text(
                        entry.link.title ?: entry.link.href.toString(),
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                tocOpen = false
                                onTocEntry(entry.link)
                            }.padding(start = 16.dp + 16.dp * entry.depth, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
    onSyncNow: () -> Unit,
    onMakeCurrent: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action)) }
        },
        actions = {
            IconButton(onClick = onToc) { Icon(painterResource(R.drawable.ic_view_list), stringResource(R.string.table_of_contents)) }
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.reader_settings)) }
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.sync_position)) }, {
                    menu = false
                    onSyncNow()
                })
                DropdownMenuItem({ Text(stringResource(R.string.make_position_current)) }, {
                    menu = false
                    onMakeCurrent()
                })
            }
        },
    )
}

@Composable
private fun JumpBar(
    jump: JumpOffer,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    val where = if (jump.approximate) stringResource(R.string.about_percent, jump.percent) else "${jump.percent}%"
    Surface(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.jump_further_ahead, where), Modifier.weight(1f))
            TextButton(onClick = onJump) { Text(stringResource(R.string.jump_action)) }
            IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_action)) }
        }
    }
}

@Composable
private fun BottomBar(
    locator: Locator?,
    onSeek: (Double) -> Unit,
) {
    val total = locator?.locations?.totalProgression ?: 0.0
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) value else total.toFloat()
    Surface(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row {
                Text(locator?.title.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(shown * 100).roundToInt()}%")
            }
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    value = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(value.toDouble())
                },
            )
        }
    }
}
