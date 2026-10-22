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
package io.github.asm0dey.grimmdroid.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.github.asm0dey.grimmdroid.catalog.ReadState
import io.github.asm0dey.grimmdroid.catalog.readState
import io.github.asm0dey.grimmdroid.ui.library.BookListRow

/** A series, or an author's books by series; each row marks its read state and the series' next book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    vm: GroupViewModel,
    baseUrl: String?,
    onBook: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val page by vm.page.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                page?.let {
                    Column {
                        Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.read_of_total, it.total, it.readCount, it.total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            // The shell already pads for the status bar above its banners.
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action)) }
            },
        )
        val sections = page?.sections.orEmpty()
        // A series page has one section named after the page itself: no header for it.
        val headers = sections.size > 1 || sections.firstOrNull()?.seriesName != page?.title
        LazyColumn(Modifier.fillMaxSize()) {
            sections.forEach { section ->
                if (headers) {
                    item(key = section.seriesName?.let { "s:$it" } ?: "none") {
                        Text(
                            section.seriesName ?: stringResource(R.string.not_in_series),
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(section.books, key = { it.id }) { book ->
                    GroupRow(book, book.id == section.next?.id, baseUrl, onBook)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    book: BookEntity,
    next: Boolean,
    baseUrl: String?,
    onBook: (Long) -> Unit,
) {
    val state = book.readState
    val modifier =
        when {
            next -> Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
            state == ReadState.DISMISSED -> Modifier.alpha(0.5f)
            else -> Modifier
        }
    // shortcut: seriesBooks/authorBooks carry no download state, so no row shows as downloaded; join DownloadEntity if that matters.
    BookListRow(BookRow(book, downloaded = false), baseUrl, onBook, modifier) {
        Row(
            Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (next) {
                Text(
                    stringResource(R.string.up_next),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            book.seriesNumber?.let { n ->
                Text(
                    stringResource(R.string.series_position, if (n % 1f == 0f) n.toInt().toString() else n.toString()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReadMarker(state, book.readProgress ?: 0f)
        }
    }
}

/** Read is a filled check, in progress a progress ring, dismissed a faint cross; not started shows nothing. */
@Composable
private fun ReadMarker(
    state: ReadState,
    progress: Float,
) {
    val size = Modifier.size(24.dp)
    when (state) {
        ReadState.READ -> {
            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.state_read), size, MaterialTheme.colorScheme.primary)
        }

        ReadState.IN_PROGRESS -> {
            val label = stringResource(R.string.state_in_progress, progress.toInt())
            CircularProgressIndicator(
                progress = { (progress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier.size(20.dp).semantics { contentDescription = label },
                color = MaterialTheme.colorScheme.tertiary,
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        ReadState.DISMISSED -> {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.state_dismissed), size, MaterialTheme.colorScheme.outline)
        }

        // Unread is the default state, so it gets no marker.
        ReadState.NOT_STARTED -> Unit
    }
}
