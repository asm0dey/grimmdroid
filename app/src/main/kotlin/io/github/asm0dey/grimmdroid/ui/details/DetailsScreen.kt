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
package io.github.asm0dey.grimmdroid.ui.details

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.api.BookDetailDto
import io.github.asm0dey.grimmdroid.api.BookFileDto
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.github.asm0dey.grimmdroid.download.DownloadWorker
import io.github.asm0dey.grimmdroid.export.ExportResult
import io.github.asm0dey.grimmdroid.export.Exporter
import io.github.asm0dey.grimmdroid.export.FolderResult
import io.github.asm0dey.grimmdroid.ui.library.Cover
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun webUiBookUrl(
    baseUrl: String,
    bookId: Long,
) = "${baseUrl.trimEnd('/')}/book/$bookId"

/** [setFolder] stores the chosen export folder for the current account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    vm: DetailsViewModel,
    baseUrl: String?,
    snackbar: SnackbarHostState,
    exporter: Exporter,
    setFolder: suspend (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Non-null while the file picker is open: whether the chosen file is shared once downloaded.
    var pickFor by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var saveAfterPick by remember { mutableStateOf(false) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                scope.launch {
                    setFolder(uri)
                    if (saveAfterPick) vm.saveToFolder()
                }
            }
        }
    val savedTo = stringResource(R.string.saved_to_folder, "%s")
    val change = stringResource(R.string.change_action)
    val folderLost = stringResource(R.string.folder_lost_hint)
    val saveFailed = stringResource(R.string.save_failed)
    val deleteFailed = stringResource(R.string.delete_book_failed)
    val noApp = stringResource(R.string.no_app_to_open)

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is DetailsEvent.Share -> {
                    exporter.share(event.file, event.title)
                }

                DetailsEvent.DeleteFailed -> {
                    snackbar.showSnackbar(deleteFailed)
                }

                is DetailsEvent.Saved -> {
                    when (val outcome = event.result.getOrNull()) {
                        FolderResult.NeedsFolder -> {
                            saveAfterPick = true
                            folderPicker.launch(null)
                        }

                        is FolderResult.Saved -> {
                            val shown = snackbar.showSnackbar(savedTo.format(outcome.folderName), change, duration = SnackbarDuration.Long)
                            if (shown == SnackbarResult.ActionPerformed) {
                                saveAfterPick = false
                                folderPicker.launch(null)
                            }
                        }

                        FolderResult.FolderLost -> {
                            snackbar.showSnackbar(folderLost)
                        }

                        null -> {
                            snackbar.showSnackbar(saveFailed)
                        }
                    }
                }
            }
        }
    }

    val book = s.book
    val action = s.action
    val files = s.detail?.files.orEmpty()
    val requestDownload: (Boolean) -> Unit = { share -> if (files.size > 1) pickFor = share else vm.download(null, share) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = {},
            // The shell already pads for the status bar above its banners.
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action)) }
            },
            actions = {
                if (action != null && action != PrimaryAction.NoPermission && action != PrimaryAction.Audiobook) {
                    IconButton(onClick = {
                        if (action is PrimaryAction.Downloaded) {
                            exporter.share(
                                action.file,
                                book?.title.orEmpty(),
                            )
                        } else {
                            requestDownload(true)
                        }
                    }) { Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share_action)) }
                }
                IconButton(
                    onClick = { menuOpen = true },
                ) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options)) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (action is PrimaryAction.Downloaded || action is PrimaryAction.Downloading) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_from_phone)) }, onClick = {
                            menuOpen = false
                            vm.deleteFromPhone()
                        })
                    }
                    if (baseUrl != null && book != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_in_web)) }, onClick = {
                            menuOpen = false
                            CustomTabsIntent.Builder().build().launchUrl(context, webUiBookUrl(baseUrl, book.id).toUri())
                        })
                    }
                }
            },
        )
        if (book == null || action == null) return@Column
        Header(book, s.detail, action is PrimaryAction.Downloaded, baseUrl)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionArea(
                action,
                book,
                s.failure,
                onDownload = { requestDownload(false) },
                onCancel = vm::cancel,
                onOpen = { file -> if (exporter.openWith(file) == ExportResult.NoApp) scope.launch { snackbar.showSnackbar(noApp) } },
                onSave = vm::saveToFolder,
            )
        }
        About(book, s.detail)
    }

    pickFor?.let { share ->
        AlertDialog(
            onDismissRequest = { pickFor = null },
            title = { Text(stringResource(R.string.choose_file)) },
            text = {
                Column {
                    files.forEach { file ->
                        Text(
                            fileLabel(file),
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    pickFor = null
                                    vm.download(file, share)
                                }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickFor = null }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
}

private fun fileLabel(file: BookFileDto) =
    listOfNotNull(file.fileName ?: file.extension?.uppercase(), file.fileSizeKb?.let { sizeLabel(it * 1024) }).joinToString(" · ")

@Composable
private fun Header(
    book: BookEntity,
    detail: BookDetailDto?,
    downloaded: Boolean,
    baseUrl: String?,
) {
    Row(Modifier.padding(horizontal = 16.dp)) {
        Cover(BookRow(book, downloaded), baseUrl, Modifier.width(128.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.headlineSmall)
            if (book.authors.isNotBlank()) Text(book.authors, style = MaterialTheme.typography.titleSmall)
            val series = book.seriesName
            if (series != null) {
                val number = book.seriesNumber?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
                Text(
                    if (number != null) stringResource(R.string.series_number, series, number) else series,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val facts =
                listOfNotNull(
                    detail?.categories?.firstOrNull(),
                    detail?.publishedDate?.take(4),
                    detail?.pageCount?.let { pluralStringResource(R.plurals.page_count, it, it) },
                )
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val chips =
                detail?.files?.takeIf { it.isNotEmpty() }?.map { f ->
                    listOfNotNull((f.extension ?: f.bookType)?.uppercase(), f.fileSizeKb?.let { sizeLabel(it * 1024) }).joinToString(" · ")
                } ?: listOf(listOfNotNull(book.primaryFileType, book.fileSizeKb?.let { sizeLabel(it * 1024) }).joinToString(" · "))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                chips.filter { it.isNotEmpty() }.forEach { chip ->
                    Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Text(chip, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionArea(
    action: PrimaryAction,
    book: BookEntity,
    failure: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (File) -> Unit,
    onSave: () -> Unit,
) {
    val full = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    when (action) {
        PrimaryAction.Download -> {
            Button(onDownload, full) { Text(stringResource(R.string.download_action)) }
        }

        is PrimaryAction.Downloading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (action.progress < 0) {
                    LinearProgressIndicator(Modifier.weight(1f))
                } else {
                    LinearProgressIndicator({ action.progress }, Modifier.weight(1f))
                }
                TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel_action)) }
            }
        }

        is PrimaryAction.Downloaded -> {
            Button({ onOpen(action.file) }, full) { Text(stringResource(R.string.open_with_action)) }
            OutlinedButton(onSave, full) { Text(stringResource(R.string.save_to_folder_action)) }
            // Green from the mockup, at a tone readable on both light and dark surfaces.
            val green = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF86EFAC) else Color(0xFF15803D)
            val format =
                action.file.extension
                    .uppercase()
                    .ifEmpty { book.primaryFileType.orEmpty() }
            Text(
                stringResource(
                    R.string.on_this_phone_line,
                    listOf(format, action.sizeLabel).filter { it.isNotEmpty() }.joinToString(" · "),
                ),
                color = green,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        PrimaryAction.NoPermission -> {
            Text(stringResource(R.string.no_download_permission), style = MaterialTheme.typography.bodyMedium)
        }

        PrimaryAction.Audiobook -> {
            Button({}, full, enabled = false) { Text(stringResource(R.string.audiobook_unsupported)) }
        }

        PrimaryAction.Offline -> {
            Button({}, full, enabled = false) { Text(stringResource(R.string.download_action)) }
            Text(
                stringResource(R.string.offline_reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (failure != null && action is PrimaryAction.Download) {
        val message =
            when (failure) {
                DownloadWorker.ERROR_NO_PERMISSION -> R.string.no_download_permission
                DownloadWorker.ERROR_NETWORK -> R.string.download_failed_network
                else -> R.string.download_failed
            }
        Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun About(
    book: BookEntity,
    detail: BookDetailDto?,
) {
    val facts =
        listOfNotNull(
            detail?.libraryName?.let { R.string.fact_library to it },
            detail?.publisher?.let { R.string.fact_publisher to it },
            detail?.language?.takeIf { it.isNotBlank() }?.let { tag ->
                R.string.fact_language to Locale.forLanguageTag(tag).displayLanguage.ifBlank { tag }
            },
            book.addedOn?.let { R.string.fact_added to DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
        )
    val description = detail?.description?.takeIf { it.isNotBlank() }
    if (description == null && facts.isEmpty() && detail?.categories.isNullOrEmpty()) return
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            var overflows by remember { mutableStateOf(false) }
            Text(
                remember(description) { AnnotatedString.fromHtml(description) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (overflows || expanded) {
                TextButton({ expanded = !expanded }) { Text(stringResource(if (expanded) R.string.show_less else R.string.show_more)) }
            }
        }
        detail?.categories?.takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (pair.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}
