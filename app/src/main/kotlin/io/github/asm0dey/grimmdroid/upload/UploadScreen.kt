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
package io.github.asm0dey.grimmdroid.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.ui.details.DetailsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A book file another app (or the in-app picker) handed us; AppNav starts its upload once an account is signed in. */
object IncomingBooks {
    val incoming = MutableStateFlow<Uri?>(null)
}

private const val EPUB_MIME = "application/epub+zip"

/** Copies [uri] into the cache so the upload doesn't depend on the sender's grant; null when it can't be read. */
private suspend fun copyToCache(
    context: Context,
    uri: Uri,
): Pair<File, String>? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val shown =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "book"
        // Some senders give a name without the extension but the right type.
        val name = if (resolver.getType(uri) == EPUB_MIME && !shown.endsWith(".epub", ignoreCase = true)) "$shown.epub" else shown
        val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
        val target = File.createTempFile("upload", ".epub", dir)
        try {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            target to name
        } catch (_: IOException) {
            target.delete()
            null
        } catch (_: SecurityException) {
            target.delete()
            null
        }
    }

fun uploadViewModelFactory(
    container: AppContainer,
    accountId: Long,
    uri: Uri,
) = viewModelFactory {
    initializer {
        val account =
            suspend {
                container.accounts.accounts
                    .first()
                    .first { it.id == accountId }
            }
        UploadViewModel(
            copy = { copyToCache(container.appContext, uri) },
            libraries = { container.api(account()).uploadLibraries() },
            savedTarget = { container.settings.uploadTarget(accountId).first() },
            saveTarget = { container.settings.setUploadTarget(accountId, it) },
            upload = { file, name, target, progress ->
                BookUploader(
                    container.api(account()),
                ) { title, kb ->
                    container.db
                        .catalogDao()
                        .bookByTitle(accountId, title, kb)
                        ?.id
                }.upload(file, name, target, progress)
            },
            open = { bookId ->
                // The new book must be in the catalogue for the download and the reader to know it.
                container.librarySync.sync(accountId)
                val file = container.downloadRepository.download(accountId, bookId, null, true) {}
                container.bookOpens.tryEmit(DetailsEvent.Read(accountId, bookId, file))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    vm: UploadViewModel,
    server: String,
    onDone: () -> Unit,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state == UploadState.Opened) onDone() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.upload_book)) },
            navigationIcon = {
                IconButton(onClick = onDone) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action)) }
            },
        )
        val body: @Composable (String) -> Unit = {
            Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        when (val s = state) {
            UploadState.Preparing, UploadState.Opened -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }

            is UploadState.ChooseTarget -> {
                body(stringResource(R.string.upload_to))
                LazyColumn {
                    items(s.options) { option ->
                        ListItem(headlineContent = { Text(option.label) }, modifier = Modifier.clickable { vm.choose(option) })
                    }
                }
            }

            is UploadState.Uploading -> {
                body(stringResource(R.string.upload_progress, server))
                LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth().padding(16.dp))
            }

            UploadState.Opening -> {
                body(stringResource(R.string.upload_opening))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }

            UploadState.Waiting -> {
                body(stringResource(R.string.upload_waiting))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }

            UploadState.NotPickedUp -> {
                body(stringResource(R.string.upload_not_picked_up))
            }

            UploadState.NoPermission -> {
                body(stringResource(R.string.upload_no_permission))
            }

            UploadState.NotEpub -> {
                body(stringResource(R.string.upload_not_epub))
            }

            is UploadState.Failed -> {
                body(stringResource(R.string.upload_failed, s.message))
            }

            is UploadState.OpenFailed -> {
                body(stringResource(R.string.upload_open_failed, s.message))
            }
        }
    }
}
