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
package io.github.asm0dey.grimmdroid.ui.downloads

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.github.asm0dey.grimmdroid.catalog.DownloadWithBook
import io.github.asm0dey.grimmdroid.export.FolderResult
import io.github.asm0dey.grimmdroid.ui.library.Cover
import kotlinx.coroutines.launch

/** [setFolder] stores the chosen export folder for the current account. */
@Composable
fun DownloadsScreen(
    vm: DownloadsViewModel,
    baseUrl: String?,
    snackbar: SnackbarHostState,
    setFolder: suspend (Uri) -> Unit,
    onBook: (Long) -> Unit,
) {
    val items by vm.items.collectAsState()
    val total by vm.totalBytes.collectAsState()
    val selection by vm.selection.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Whether a picked folder should also finish the save that asked for it (vs. a plain "Change").
    var saveAfterPick by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                scope.launch {
                    setFolder(uri)
                    if (saveAfterPick) vm.saveSelected()
                }
            }
        }
    val savedTo = stringResource(R.string.saved_to_folder, "%s")
    val change = stringResource(R.string.change_action)
    val folderLost = stringResource(R.string.folder_lost_hint)
    val saveFailed = stringResource(R.string.save_failed)

    val deleteFailed = stringResource(R.string.delete_failed)
    LaunchedEffect(vm) { vm.deleteFailed.collect { snackbar.showSnackbar(deleteFailed) } }
    LaunchedEffect(vm) {
        vm.saveResults.collect { result ->
            val outcome = result.getOrNull()
            when {
                outcome is FolderResult.NeedsFolder -> {
                    saveAfterPick = true
                    picker.launch(null)
                }

                outcome is FolderResult.Saved -> {
                    val shown = snackbar.showSnackbar(savedTo.format(outcome.folderName), change, duration = SnackbarDuration.Long)
                    if (shown == SnackbarResult.ActionPerformed) {
                        saveAfterPick = false
                        picker.launch(null)
                    }
                }

                outcome is FolderResult.FolderLost -> {
                    snackbar.showSnackbar(folderLost)
                }

                else -> {
                    snackbar.showSnackbar(saveFailed)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            Text(
                stringResource(R.string.downloads_total, Formatter.formatFileSize(context, total)),
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = vm::clearSelection,
                ) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_selection)) }
                Text(
                    stringResource(R.string.selected_count, selection.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = vm::saveSelected) {
                    Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.save_to_folder_action))
                }
                IconButton(
                    onClick = vm::deleteSelected,
                ) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete_action)) }
            }
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.downloads_empty),
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.download.bookId }) { item ->
                DownloadRow(item, baseUrl, item.download.bookId in selection, selection.isNotEmpty(), vm::toggle, onBook)
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadWithBook,
    baseUrl: String?,
    selected: Boolean,
    selecting: Boolean,
    onToggle: (Long) -> Unit,
    onBook: (Long) -> Unit,
) {
    val context = LocalContext.current
    val id = item.book.id
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(onLongClick = { onToggle(id) }, onClick = { if (selecting) onToggle(id) else onBook(id) })
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(BookRow(item.book, true), baseUrl, Modifier.width(48.dp), showTitle = false)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(item.book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(item.book.primaryFileType, Formatter.formatShortFileSize(context, item.download.sizeBytes))
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selecting) Checkbox(selected, onCheckedChange = { onToggle(id) })
    }
}
