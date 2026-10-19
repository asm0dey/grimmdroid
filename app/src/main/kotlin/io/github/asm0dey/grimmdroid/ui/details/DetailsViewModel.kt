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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.api.BookDetailDto
import io.github.asm0dey.grimmdroid.api.BookFileDto
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.download.DownloadWorker
import io.github.asm0dey.grimmdroid.export.FolderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DetailsState(
    val account: AccountEntity? = null,
    val book: BookEntity? = null,
    /** True once the catalogue has answered that the book isn't there (removed by a sync). */
    val missing: Boolean = false,
    /** Fetched while online and kept in memory; null offline or until it arrives. */
    val detail: BookDetailDto? = null,
    /** Null until the book is loaded. */
    val action: PrimaryAction? = null,
    /** The last download's failure kind ([DownloadWorker.KEY_ERROR]), while it is the book's current state. */
    val failure: String? = null,
)

sealed interface DetailsEvent {
    data class Share(
        val file: File,
        val title: String,
    ) : DetailsEvent

    data class Saved(
        val result: Result<FolderResult>,
    ) : DetailsEvent

    data object DeleteFailed : DetailsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModel(
    accountId: Long,
    private val bookId: Long,
    accounts: Flow<List<AccountEntity>>,
    private val online: StateFlow<Boolean>,
    book: (accountId: Long, bookId: Long) -> Flow<BookEntity?>,
    download: (accountId: Long, bookId: Long) -> Flow<DownloadEntity?>,
    work: (accountId: Long, bookId: Long) -> Flow<WorkInfo?>,
    private val loadDetail: suspend (AccountEntity, bookId: Long) -> BookDetailDto,
    private val enqueue: (accountId: Long, bookId: Long, fileId: Long?, isPrimary: Boolean) -> Unit,
    private val cancelDownload: (accountId: Long, bookId: Long) -> Unit,
    private val delete: suspend (accountId: Long, bookId: Long) -> Unit,
    private val save: suspend (accountId: Long, file: File) -> FolderResult,
) : ViewModel() {
    private val detail = MutableStateFlow<BookDetailDto?>(null)

    // Pinned to the account the book was opened under: a switch must never re-query this book id in another account.
    private val account = accounts.map { list -> list.firstOrNull { it.id == accountId } }.distinctUntilChanged()

    val state: StateFlow<DetailsState> =
        account
            .flatMapLatest { acc ->
                if (acc == null) {
                    flowOf(DetailsState())
                } else {
                    combine(book(acc.id, bookId), download(acc.id, bookId), work(acc.id, bookId), online, detail) { b, row, info, on, det ->
                        val pending = info?.state?.isFinished == false
                        // The worker writes its row only once it starts; until then the enqueued work stands in for it.
                        val stale =
                            row == null || row.state == DownloadState.FAILED ||
                                (row.state == DownloadState.DONE && !File(row.path).exists())
                        val effective =
                            if (pending && stale) {
                                DownloadEntity(acc.id, bookId, 0, "", "", 0, DownloadState.QUEUED, -1f)
                            } else {
                                row
                            }
                        DetailsState(
                            account = acc,
                            book = b,
                            missing = b == null,
                            detail = det,
                            action =
                                b?.let {
                                    primaryAction(
                                        it,
                                        acc.canDownload,
                                        acc.isAdmin,
                                        effective,
                                        effective?.let { d ->
                                            File(d.path)
                                        },
                                        on,
                                    )
                                },
                            failure =
                                if (effective?.state == DownloadState.FAILED) {
                                    info?.outputData?.getString(DownloadWorker.KEY_ERROR) ?: DownloadWorker.ERROR_OTHER
                                } else {
                                    null
                                },
                        )
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsState())

    private val _events = Channel<DetailsEvent>(Channel.BUFFERED)
    val events: Flow<DetailsEvent> = _events.receiveAsFlow()

    // Share pressed before the book was on the phone: share once the download lands, or give up when it ends otherwise.
    private var shareWhenDone = false
    private var sawDownloading = false

    init {
        viewModelScope.launch { combine(account, online) { acc, _ -> acc }.collect(::fetchDetail) }
        viewModelScope.launch {
            state.collect { s ->
                if (!shareWhenDone) return@collect
                when (val action = s.action) {
                    is PrimaryAction.Downloading -> {
                        sawDownloading = true
                    }

                    is PrimaryAction.Downloaded -> {
                        shareWhenDone = false
                        _events.send(DetailsEvent.Share(action.file, s.book?.title.orEmpty()))
                    }

                    else -> {
                        if (sawDownloading) shareWhenDone = false
                    }
                }
            }
        }
    }

    private var fetching: Job? = null

    /** Fetches the detail unless it is already here or on its way; called on reconnect and whenever the screen shows again. */
    fun refreshDetail() = fetchDetail(state.value.account)

    private fun fetchDetail(acc: AccountEntity?) {
        if (acc == null) return
        if (!online.value || detail.value != null || fetching?.isActive == true) return
        fetching =
            viewModelScope.launch {
                try {
                    detail.value = loadDetail(acc, bookId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline-first: the catalogue row is enough; reconnecting or coming back to the screen retries.
                }
            }
    }

    /** [file] picks one of the detail's files; null downloads the primary file. [share] shares it once downloaded. */
    fun download(
        file: BookFileDto?,
        share: Boolean,
    ) {
        val s = state.value
        val acc = s.account ?: return
        val book = s.book ?: return
        enqueue(acc.id, bookId, file?.id ?: book.primaryFileId, file?.isPrimary ?: true)
        if (share) {
            shareWhenDone = true
            sawDownloading = false
        }
    }

    fun cancel() {
        val acc = state.value.account ?: return
        shareWhenDone = false
        cancelDownload(acc.id, bookId)
    }

    fun deleteFromPhone() {
        val acc = state.value.account ?: return
        shareWhenDone = false
        viewModelScope.launch {
            try {
                delete(acc.id, bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.send(DetailsEvent.DeleteFailed)
            }
        }
    }

    fun saveToFolder() {
        val acc = state.value.account ?: return
        val file = (state.value.action as? PrimaryAction.Downloaded)?.file ?: return
        viewModelScope.launch {
            val result =
                try {
                    Result.success(save(acc.id, file))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            _events.send(DetailsEvent.Saved(result))
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            accountId: Long,
            bookId: Long,
        ) = viewModelFactory {
            initializer {
                val app = container.appContext
                val repo = container.downloadRepository
                DetailsViewModel(
                    accountId,
                    bookId,
                    container.accounts.accounts,
                    container.connectivity.online,
                    container.db.catalogDao()::book,
                    repo::observe,
                    { a, b -> repo.work(app, a, b) },
                    { acc, id -> container.api(acc).book(id) },
                    { a, b, file, primary -> repo.enqueue(app, a, b, file, primary) },
                    { a, b -> repo.cancel(app, a, b) },
                    { a, b -> repo.delete(app, a, b) },
                    container.folderExport::save,
                )
            }
        }
    }
}
