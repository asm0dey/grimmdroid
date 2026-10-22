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
package io.github.asm0dey.grimmdroid.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.github.asm0dey.grimmdroid.catalog.BookSort
import io.github.asm0dey.grimmdroid.settings.Layout
import io.github.asm0dey.grimmdroid.sync.AUDIOBOOK_FILE_TYPE

// Grimmory's BookFileType values, as stored in BookEntity.primaryFileType.
private val Formats = listOf("EPUB", "PDF", "CBX", "FB2", "MOBI", "AZW3", AUDIOBOOK_FILE_TYPE)

private val SortLabels =
    mapOf(
        BookSort.TITLE to R.string.sort_title,
        BookSort.AUTHOR to R.string.sort_author,
        BookSort.ADDED to R.string.sort_added,
        BookSort.SERIES to R.string.sort_series,
    )

/** The library grid or list; Library and ShelfBooks both show it, Library with a search bar in [header]. */
@Composable
fun LibraryContent(
    vm: LibraryViewModel,
    baseUrl: String?,
    syncStatus: String?,
    onBook: (Long) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val query by vm.query.collectAsState()
    val libraries by vm.libraries.collectAsState()
    val layout by vm.layout.collectAsState()
    val books = vm.books.collectAsLazyPagingItems()
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        header()
        // Also shown while filtered, so a library that vanished in a sync can still be cleared with "All".
        if (libraries.size > 1 || query.libraryId != null) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(query.libraryId == null, { vm.setLibrary(null) }, { Text(stringResource(R.string.all_libraries)) })
                }
                items(libraries, key = { it.id }) { lib ->
                    FilterChip(query.libraryId == lib.id, { vm.setLibrary(lib.id) }, { Text(lib.name) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                syncStatus.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SortMenu(query.sort, vm::setSort)
            IconButton(onClick = { filtersOpen = true }) {
                Icon(painterResource(R.drawable.ic_filter_list), stringResource(R.string.filter))
            }
            if (layout == Layout.GRID) {
                IconButton(onClick = { vm.setLayout(Layout.LIST) }) {
                    Icon(painterResource(R.drawable.ic_view_list), stringResource(R.string.list_view))
                }
            } else {
                IconButton(onClick = { vm.setLayout(Layout.GRID) }) {
                    Icon(painterResource(R.drawable.ic_grid_view), stringResource(R.string.grid_view))
                }
            }
        }

        Box(Modifier.weight(1f)) {
            val grid = rememberLazyGridState()
            LazyVerticalGrid(
                columns = if (layout == Layout.GRID) GridCells.Adaptive(110.dp) else GridCells.Fixed(1),
                state = grid,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books.itemCount, key = books.itemKey { it.book.id }, contentType = books.itemContentType { layout }) { i ->
                    val row = books[i]
                    when {
                        layout == Layout.LIST -> BookListRow(row, baseUrl, onBook)
                        row == null -> CoverPlaceholder(Modifier.padding(4.dp))
                        else -> BookTile(row, baseUrl, onBook)
                    }
                }
            }
            if (books.itemCount == 0 && books.loadState.refresh is LoadState.NotLoading) {
                Text(
                    stringResource(R.string.no_books),
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (query.sort == BookSort.TITLE || query.sort == BookSort.AUTHOR) {
                FastScroller(grid, books.itemCount, { i ->
                    books
                        .peek(i)
                        ?.book
                        ?.let { if (query.sort == BookSort.TITLE) it.title else it.authors }
                        ?.firstOrNull()
                        ?.uppercase()
                }, Modifier.align(Alignment.TopEnd))
            }
        }
    }

    if (filtersOpen) {
        FilterSheet(query.formats, query.downloadedOnly, vm::setFormats, vm::setDownloadedOnly) { filtersOpen = false }
    }
}

@Composable
private fun BookTile(
    row: BookRow,
    baseUrl: String?,
    onBook: (Long) -> Unit,
) {
    Column(Modifier.clickable(role = Role.Button) { onBook(row.book.id) }.padding(4.dp)) {
        Cover(row, baseUrl)
        Text(
            row.book.title,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.book.authors,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small cover, title, author, format and size; also used for search results. */
@Composable
fun BookListRow(
    row: BookRow?,
    baseUrl: String?,
    onBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (row != null) Modifier.clickable(role = Role.Button) { onBook(row.book.id) } else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row == null) {
            CoverPlaceholder(Modifier.width(48.dp))
            return@Row
        }
        Cover(row, baseUrl, Modifier.width(48.dp), showTitle = false)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            val book = row.book
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                book.authors,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(book.primaryFileType, book.fileSizeKb?.let { Formatter.formatShortFileSize(context, it * 1024) })
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SortMenu(
    sort: BookSort,
    onSort: (BookSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.sort)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            SortLabels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onSort(value)
                    },
                    trailingIcon = {
                        if (value ==
                            sort
                        ) {
                            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.sort_selected))
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    formats: Set<String>,
    downloadedOnly: Boolean,
    onFormats: (Set<String>) -> Unit,
    onDownloadedOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.format), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Formats.forEach { f ->
                    FilterChip(f in formats, { onFormats(if (f in formats) formats - f else formats + f) }, { Text(f) })
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Switch) { onDownloadedOnly(!downloadedOnly) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.downloaded_only), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(downloadedOnly, onCheckedChange = null)
            }
        }
    }
}

/** Looks like a collapsed M3 search bar; tapping it opens the full-screen search. */
@Composable
fun SearchLauncher(
    onSearch: () -> Unit,
    onAccount: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSearch,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                Text(
                    stringResource(R.string.search_books),
                    Modifier.padding(start = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onAccount) {
            Icon(painterResource(R.drawable.ic_account_circle), stringResource(R.string.accounts))
        }
    }
}
