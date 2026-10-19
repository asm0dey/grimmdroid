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
package io.github.asm0dey.grimmdroid.ui.shelves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.ShelfEntity

/** Two sections, Shelves and Magic shelves; tapping one opens the library scoped to it. */
@Composable
fun ShelvesScreen(
    shelves: List<ShelfEntity>,
    onShelf: (ShelfEntity) -> Unit,
) {
    if (shelves.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_shelves), Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val (magic, regular) = shelves.partition { it.magic }
    LazyColumn(Modifier.fillMaxSize()) {
        listOf(R.string.shelves_section to regular, R.string.magic_shelves_section to magic).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            item(key = title) {
                Text(
                    stringResource(title),
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(list, key = { "${it.magic}-${it.id}" }) { shelf ->
                ListItem(
                    headlineContent = { Text(shelf.name) },
                    supportingContent = { Text(pluralStringResource(R.plurals.book_count, shelf.bookCount, shelf.bookCount)) },
                    modifier = Modifier.clickable { onShelf(shelf) },
                )
            }
        }
    }
}
