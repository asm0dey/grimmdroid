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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    /** The book's author names from the BookAuthor rows; empty until sync has filled them. */
    val authors: List<String> = emptyList(),
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

    /** Sent to the app-wide `opens` flow, not [DetailsViewModel.events]: AppNav opens the reader. */
    data class Read(
        val accountId: Long,
        val bookId: Long,
        val file: File,
    ) : DetailsEvent

    /** Sent to the app-wide `opens` flow, not [DetailsViewModel.events]: AppNav opens the file with another app. */
    data class OpenWith(
        val file: File,
    ) : DetailsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModel(
    accountId: Long,
    private val bookId: Long,
    accounts: Flow<List<AccountEntity>>,
    private val online: StateFlow<Boolean>,
    book: (accountId: Long, bookId: Long) -> Flow<BookEntity?>,
    authors: (accountId: Long, bookId: Long) -> Flow<List<String>>,
    download: (accountId: Long, bookId: Long) -> Flow<DownloadEntity?>,
    work: (accountId: Long, bookId: Long) -> Flow<WorkInfo?>,
    private val loadDetail: suspend (AccountEntity, bookId: Long) -> BookDetailDto,
    private val enqueue: (accountId: Long, bookId: Long, fileId: Long?, isPrimary: Boolean, readAfter: Boolean) -> Unit,
    private val cancelDownload: (accountId: Long, bookId: Long) -> Unit,
    private val delete: suspend (accountId: Long, bookId: Long) -> Unit,
    private val save: suspend (accountId: Long, file: File) -> FolderResult,
    /** App-wide [DetailsEvent.Read] / [DetailsEvent.OpenWith] sink, collected by AppNav. */
    private val opens: MutableSharedFlow<DetailsEvent>,
    /** Outlives this ViewModel, so a read pressed before the download still opens after the user leaves the screen. */
    private val pendingScope: CoroutineScope,
) : ViewModel() {
    private val detail = MutableStateFlow<BookDetailDto?>(null)

    // Pinned to the account the book was opened under: a switch must never re-query this book id in another account.
    private val account = accounts.map { list -> list.firstOrNull { it.id == accountId } }.distinctUntilChanged()

    private val states: Flow<DetailsState> =
        account
            .flatMapLatest { acc ->
                if (acc == null) {
                    flowOf(DetailsState())
                } else {
                    combine(
                        book(acc.id, bookId).combine(authors(acc.id, bookId), ::Pair),
                        download(acc.id, bookId),
                        work(acc.id, bookId),
                        online,
                        detail,
                    ) { (b, names), row, info, on, det ->
                        val pending = info?.state?.isFinished == false
                        // The worker writes its row only once it starts; until then the enqueued work stands in for it.
                        val stale =
                            row == null || row.state == DownloadState.FAILED ||
                                (row.state == DownloadState.DONE && !File(row.path).exists())
                        // A queued/running row with no pending work outlived its worker (e.g. process death): offer Download.
                        val orphaned = !pending && (row?.state == DownloadState.QUEUED || row?.state == DownloadState.RUNNING)
                        val effective =
                            when {
                                pending && stale -> DownloadEntity(acc.id, bookId, 0, "", "", 0, DownloadState.QUEUED, -1f)
                                orphaned -> null
                                else -> row
                            }
                        DetailsState(
                            account = acc,
                            book = b,
                            authors = names,
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
            }

    val state: StateFlow<DetailsState> = states.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsState())

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
        enqueue(acc.id, bookId, file?.id ?: book.primaryFileId, file?.isPrimary ?: true, false)
        if (share) {
            shareWhenDone = true
            sawDownloading = false
        }
    }

    private var pendingOpen: Job? = null

    /** Opens the book in the reader, downloading its primary file first if it isn't on the phone. */
    fun read() {
        val acc = state.value.account ?: return
        open(readAfter = true) { DetailsEvent.Read(acc.id, bookId, it) }
    }

    fun openWith() = open(readAfter = false) { DetailsEvent.OpenWith(it) }

    private fun open(
        readAfter: Boolean,
        event: (File) -> DetailsEvent,
    ) {
        val s = state.value
        val acc = s.account ?: return
        val book = s.book ?: return
        when (val action = s.action) {
            is PrimaryAction.Downloaded -> {
                opens.tryEmit(event(action.file))
            }

            is PrimaryAction.Download -> {
                enqueue(acc.id, bookId, book.primaryFileId, true, readAfter)
                pendingOpen?.cancel()
                pendingOpen =
                    pendingScope.launch {
                        var sawDownloading = false
                        // Like the pending share: open once it lands, give up when the download ends any other way.
                        val end =
                            states.map { it.action }.first { a ->
                                if (a is PrimaryAction.Downloading) sawDownloading = true
                                a is PrimaryAction.Downloaded || (sawDownloading && a !is PrimaryAction.Downloading)
                            }
                        if (end is PrimaryAction.Downloaded) opens.tryEmit(event(end.file))
                    }
            }

            else -> {}
        }
    }

    fun cancel() {
        val acc = state.value.account ?: return
        shareWhenDone = false
        pendingOpen?.cancel()
        cancelDownload(acc.id, bookId)
    }

    fun deleteFromPhone() {
        val acc = state.value.account ?: return
        shareWhenDone = false
        pendingOpen?.cancel()
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
                    container.db.catalogDao()::bookAuthors,
                    repo::observe,
                    { a, b -> repo.work(app, a, b) },
                    { acc, id -> container.api(acc).book(id) },
                    { a, b, file, primary, read -> repo.enqueue(app, a, b, file, primary, read) },
                    { a, b -> repo.cancel(app, a, b) },
                    { a, b -> repo.delete(app, a, b) },
                    container.folderExport::save,
                    container.bookOpens,
                    container.appScope,
                )
            }
        }
    }
}
