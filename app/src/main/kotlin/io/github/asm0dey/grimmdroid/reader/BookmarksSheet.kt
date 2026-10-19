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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import kotlin.math.roundToInt

/** The book's bookmarks: tap to go there, long-press to delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkRow>,
    onGo: (BookmarkRow) -> Unit,
    onRemove: (BookmarkRow) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = readerSheetProperties()) {
        Text(stringResource(R.string.bookmarks), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        if (bookmarks.isEmpty()) {
            Text(stringResource(R.string.no_bookmarks), Modifier.padding(16.dp))
        }
        LazyColumn {
            items(bookmarks, key = { it.localId }) { row ->
                val percent = "${(row.totalProgression * 100).roundToInt()}%"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .combinedClickable(
                            onLongClickLabel = stringResource(R.string.remove_bookmark),
                            onLongClick = { onRemove(row) },
                            onClick = { onGo(row) },
                        ).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.title.ifEmpty { percent }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(percent, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
