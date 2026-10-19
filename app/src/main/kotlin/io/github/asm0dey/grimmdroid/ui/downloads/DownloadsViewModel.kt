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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.catalog.DownloadWithBook
import io.github.asm0dey.grimmdroid.export.FolderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    accountId: Flow<Long?>,
    downloads: (Long) -> Flow<List<DownloadWithBook>>,
    private val delete: suspend (accountId: Long, bookId: Long) -> Unit,
    private val save: suspend (accountId: Long, file: File) -> FolderResult,
) : ViewModel() {
    val items: StateFlow<List<DownloadWithBook>> =
        accountId
            .flatMapLatest { id -> id?.let(downloads) ?: flowOf(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalBytes: StateFlow<Long> =
        items.map { list -> list.sumOf { it.download.sizeBytes } }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _saveResults = Channel<Result<FolderResult>>(Channel.BUFFERED)

    /** One result per [saveSelected]: the outcome, or the exception that stopped it. */
    val saveResults: Flow<Result<FolderResult>> = _saveResults.receiveAsFlow()

    private val _deleteFailed = Channel<Unit>(Channel.BUFFERED)

    /** Fires once per [deleteSelected] that couldn't delete every selected book. */
    val deleteFailed: Flow<Unit> = _deleteFailed.receiveAsFlow()

    init {
        // Book ids repeat across accounts, so a selection must not outlive the account it was made in.
        viewModelScope.launch { accountId.distinctUntilChanged().collect { clearSelection() } }
    }

    fun toggle(bookId: Long) = _selection.update { if (bookId in it) it - bookId else it + bookId }

    fun clearSelection() = _selection.update { emptySet() }

    private fun selected() = items.value.filter { it.download.bookId in _selection.value }

    fun deleteSelected() {
        val chosen = selected()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            var failed = false
            for (item in chosen) {
                try {
                    delete(item.download.accountId, item.download.bookId)
                    _selection.update { it - item.download.bookId }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                }
            }
            if (failed) _deleteFailed.send(Unit)
        }
    }

    /** Stops at the first file that can't be saved; the selection keeps what wasn't saved so the user can retry. */
    fun saveSelected() {
        val chosen = selected()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            var result: Result<FolderResult> = Result.failure(IllegalStateException())
            for (item in chosen) {
                result =
                    try {
                        Result.success(save(item.download.accountId, File(item.download.path)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                if (result.getOrNull() !is FolderResult.Saved) break
                _selection.update { it - item.download.bookId }
            }
            _saveResults.send(result)
        }
    }

    companion object {
        fun factory(container: AppContainer) =
            viewModelFactory {
                initializer {
                    val app = container.appContext
                    DownloadsViewModel(
                        container.accounts.current.map { it?.id },
                        container.db.catalogDao()::downloads,
                        { account, book -> container.downloadRepository.delete(app, account, book) },
                        container.folderExport::save,
                    )
                }
            }
    }
}
